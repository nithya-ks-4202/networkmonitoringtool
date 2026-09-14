package com.nms.collector.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nms.collector.Failures;
import com.nms.collector.Poller;
import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import com.nms.common.ItemValueType;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

/**
 * Performs HTTP(S) checks: availability, response time, status code, body
 * content, JSON extraction and TLS certificate expiry.
 *
 * <p>Two clients are held. The default one validates certificates. The second
 * does not, and is used only when an item explicitly opts out -- necessary for
 * the large population of appliances, cameras and internal services that ship
 * self-signed certificates and cannot be made to present a trusted chain.
 * Keeping them separate means a normal item can never silently lose validation.
 */
@Component
public class HttpPoller implements Poller {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Ceiling on a stored response body, to keep one endpoint from filling history. */
    private static final int MAX_BODY_CHARS = 64 * 1024;

    private final HttpClient validatingClient;
    private final HttpClient insecureClient;

    public HttpPoller() {
        this.validatingClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.insecureClient = buildInsecureClient();
    }

    @Override
    public CheckType checkType() {
        return CheckType.HTTP_AGENT;
    }

    @Override
    public CheckResult poll(CheckRequest request) {
        String url = resolveUrl(request);
        if (url == null) {
            return CheckResult.failed(request.itemId(),
                    "HTTP item '" + request.key() + "' has no URL configured");
        }

        boolean verifyTls = !"false".equalsIgnoreCase(request.param("verifyTls", "true"));
        String mode = request.param("mode", "").toLowerCase(Locale.ROOT);

        HttpRequest httpRequest;
        try {
            httpRequest = buildRequest(request, url);
        } catch (IllegalArgumentException e) {
            return CheckResult.failed(request.itemId(), "Invalid HTTP request: " + e.getMessage());
        }

        long startNanos = System.nanoTime();
        try {
            HttpResponse<String> response = (verifyTls ? validatingClient : insecureClient)
                    .send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

            return switch (mode) {
                case "responsetime" ->
                        CheckResult.ok(request.itemId(), seconds, ItemValueType.FLOAT);
                case "statuscode" ->
                        CheckResult.ok(request.itemId(), (long) response.statusCode(), ItemValueType.UNSIGNED);
                case "certexpiry" ->
                        certificateExpiry(request, response);
                case "jsonpath" ->
                        extractJson(request, response.body());
                case "body" ->
                        CheckResult.ok(request.itemId(), truncate(response.body()), ItemValueType.TEXT);
                default ->
                    // Availability: any response we could parse means the
                    // endpoint served us, unless a required status was named.
                        availability(request, response);
            };
        } catch (IOException e) {
            // Connection refused, DNS failure, TLS rejection, timeout: the
            // service did not serve a response.
            return switch (mode) {
                case "responsetime" -> CheckResult.ok(request.itemId(), 0.0, ItemValueType.FLOAT);
                case "statuscode" -> CheckResult.ok(request.itemId(), 0L, ItemValueType.UNSIGNED);
                case "certexpiry", "jsonpath", "body" ->
                        CheckResult.failed(request.itemId(), "HTTP request failed: " + Failures.describe(e));
                default -> CheckResult.ok(request.itemId(), 0L, ItemValueType.UNSIGNED);
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CheckResult.failed(request.itemId(), "interrupted during HTTP check");
        }
    }

    private HttpRequest buildRequest(CheckRequest request, String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(request.timeout());

        String method = request.param("method", "GET").toUpperCase(Locale.ROOT);
        String body = request.param("body", "");

        switch (method) {
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body));
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body));
            case "DELETE" -> builder.DELETE();
            case "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
            default -> builder.GET();
        }

        String headerSpec = request.param("headers", "");
        if (!headerSpec.isBlank()) {
            try {
                JsonNode headers = JSON.readTree(headerSpec);
                headers.fields().forEachRemaining(entry -> {
                    // Restricted headers (Host, Connection, ...) are rejected by
                    // HttpClient; skipping them beats failing the whole check.
                    try {
                        builder.header(entry.getKey(), entry.getValue().asText());
                    } catch (IllegalArgumentException ignored) {
                        // Header not settable by the client; leave it out.
                    }
                });
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("headers must be a JSON object: " + e.getOriginalMessage());
            }
        }

        String username = request.param("username", "");
        if (!username.isEmpty()) {
            String credentials = username + ':' + request.param("password", "");
            builder.header("Authorization", "Basic " + java.util.Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }

        return builder.build();
    }

    private CheckResult availability(CheckRequest request, HttpResponse<String> response) {
        String requiredStatus = request.param("requiredStatus", "");
        String requiredPattern = request.param("requiredPattern", "");

        boolean statusOk = requiredStatus.isEmpty()
                ? response.statusCode() >= 200 && response.statusCode() < 400
                : matchesStatus(response.statusCode(), requiredStatus);

        boolean bodyOk = requiredPattern.isEmpty()
                || java.util.regex.Pattern.compile(requiredPattern).matcher(response.body()).find();

        return CheckResult.ok(request.itemId(), statusOk && bodyOk ? 1L : 0L, ItemValueType.UNSIGNED);
    }

    /** Accepts "200", "200,204" or "200-299". */
    private static boolean matchesStatus(int status, String spec) {
        for (String part : spec.split(",")) {
            String token = part.trim();
            int dash = token.indexOf('-');
            try {
                if (dash > 0) {
                    if (status >= Integer.parseInt(token.substring(0, dash).trim())
                            && status <= Integer.parseInt(token.substring(dash + 1).trim())) {
                        return true;
                    }
                } else if (status == Integer.parseInt(token)) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // A malformed element simply does not match.
            }
        }
        return false;
    }

    /**
     * Days until the served certificate expires.
     *
     * <p>Certificate expiry causes more outages than almost any other single
     * cause, and it is entirely predictable, so it deserves a first-class item.
     */
    private CheckResult certificateExpiry(CheckRequest request, HttpResponse<String> response) {
        SSLSession session = response.sslSession().orElse(null);
        if (session == null) {
            return CheckResult.failed(request.itemId(),
                    "no TLS session: certificate expiry requires an https URL");
        }
        try {
            java.security.cert.Certificate[] chain = session.getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate certificate)) {
                return CheckResult.failed(request.itemId(), "peer presented no X.509 certificate");
            }
            long days = ChronoUnit.DAYS.between(Instant.now(), certificate.getNotAfter().toInstant());
            return CheckResult.ok(request.itemId(), days, ItemValueType.UNSIGNED);
        } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
            return CheckResult.failed(request.itemId(), "TLS peer unverified: " + Failures.describe(e));
        }
    }

    private CheckResult extractJson(CheckRequest request, String body) {
        String path = request.param("jsonPath", "");
        if (path.isEmpty()) {
            return CheckResult.failed(request.itemId(), "mode=jsonpath requires a jsonPath parameter");
        }
        try {
            JsonNode node = JSON.readTree(body);
            // Dotted and bracketed paths: a.b[0].c
            for (String segment : path.replace("[", ".").replace("]", "").split("\\.")) {
                if (segment.isBlank() || "$".equals(segment)) {
                    continue;
                }
                node = segment.matches("\\d+") ? node.path(Integer.parseInt(segment)) : node.path(segment);
            }
            if (node.isMissingNode() || node.isNull()) {
                return CheckResult.failed(request.itemId(), "JSON path '" + path + "' matched nothing");
            }
            Object value = switch (request.valueType()) {
                case UNSIGNED -> node.asLong();
                case FLOAT -> node.asDouble();
                default -> node.isValueNode() ? node.asText() : node.toString();
            };
            return CheckResult.ok(request.itemId(), value, request.valueType());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return CheckResult.failed(request.itemId(),
                    "response is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static String resolveUrl(CheckRequest request) {
        String url = request.param("url", "");
        if (!url.isBlank()) {
            return url;
        }
        // Fall back to building one from the interface, so an HTTP item can be
        // defined with nothing but a path.
        String address = request.address();
        if (address == null || address.isBlank()) {
            return null;
        }
        String scheme = request.port() == 443 ? "https" : "http";
        String path = request.param("path", "/");
        return scheme + "://" + address + (request.port() > 0 ? ":" + request.port() : "")
                + (path.startsWith("/") ? path : "/" + path);
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_BODY_CHARS ? body : body.substring(0, MAX_BODY_CHARS);
    }

    /**
     * A client that accepts any certificate, for items that explicitly set
     * {@code verifyTls=false}.
     */
    private static HttpClient buildInsecureClient() {
        try {
            TrustManager[] trustAll = {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // Intentionally permissive: this client exists only for
                    // items that have opted out of verification.
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // As above.
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());

            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .sslContext(context)
                    .build();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Unable to build the non-validating HTTP client", e);
        }
    }
}
