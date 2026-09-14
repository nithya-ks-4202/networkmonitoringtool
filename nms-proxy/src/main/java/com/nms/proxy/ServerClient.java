package com.nms.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nms.collector.Failures;
import com.nms.common.CheckResult;
import com.nms.common.protocol.ProxyConfigResponse;
import com.nms.common.protocol.ProxyDataRequest;
import com.nms.common.protocol.ProxyDataResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Talks to the monitoring server.
 *
 * <p>Every request is outbound. Nothing listens on this machine, so the proxy
 * needs no inbound firewall rule, no port forward and no public address -- the
 * property that makes it deployable inside a customer network at all.
 */
@Component
public class ServerClient {

    private static final Logger log = LoggerFactory.getLogger(ServerClient.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String TOKEN_HEADER = "X-Proxy-Token";

    private final ProxyProperties properties;
    private final HttpClient httpClient;

    public ServerClient(ProxyProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Confirms the token before the proxy commits to using it.
     *
     * @return the server's reply, or empty when the token was rejected
     */
    public Optional<EnrolmentResult> enrol(String version) {
        try {
            String body = JSON.writeValueAsString(new EnrolmentRequest(version, hostname()));
            HttpResponse<String> response = send("/api/proxy/enrol", "POST", body);

            if (response.statusCode() == 401) {
                // Said plainly, because the alternative is an operator watching
                // an empty dashboard wondering why no data arrives.
                log.error("The server rejected this proxy's token. Check nms.proxy.token "
                        + "against the value shown when the proxy was created.");
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                log.warn("Enrolment returned HTTP {}: {}", response.statusCode(), abbreviate(response.body()));
                return Optional.empty();
            }

            return Optional.of(JSON.readValue(response.body(), EnrolmentResult.class));

        } catch (IOException e) {
            log.warn("Could not reach the server at {}: {}",
                    properties.getServerUrl(), Failures.describe(e));
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Fetches the item assignment.
     *
     * @param knownRevision the revision already held, so an unchanged
     *                      configuration costs a few bytes instead of a full
     *                      transfer over what is often an office broadband line
     */
    public Optional<ProxyConfigResponse> fetchConfiguration(long knownRevision) {
        try {
            HttpResponse<String> response =
                    send("/api/proxy/config?knownRevision=" + knownRevision, "GET", null);

            if (response.statusCode() == 401) {
                log.error("The server rejected this proxy's token while fetching configuration");
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                log.warn("Configuration fetch returned HTTP {}", response.statusCode());
                return Optional.empty();
            }

            return Optional.of(JSON.readValue(response.body(), ProxyConfigResponse.class));

        } catch (IOException e) {
            log.warn("Could not fetch configuration: {}", Failures.describe(e));
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Uploads a batch of results.
     *
     * <p>The batch carries a generated identifier so the server can recognise a
     * re-send. A connection dropping mid-upload is routine on a poor link, and
     * without an identifier the proxy would have to choose between losing the
     * batch and duplicating it.
     *
     * @return the acknowledgement, or empty when the upload failed and the
     *         results must be returned to the buffer
     */
    public Optional<ProxyDataResponse> upload(List<CheckResult> results, int queueDepth, String version) {
        if (results.isEmpty()) {
            return Optional.empty();
        }

        try {
            ProxyDataRequest request = new ProxyDataRequest(
                    properties.getName(), UUID.randomUUID().toString(), results,
                    Instant.now(), queueDepth, version);

            HttpResponse<String> response =
                    send("/api/proxy/data", "POST", JSON.writeValueAsString(request));

            if (response.statusCode() != 200) {
                log.warn("Upload of {} result(s) returned HTTP {}: {}",
                        results.size(), response.statusCode(), abbreviate(response.body()));
                return Optional.empty();
            }

            ProxyDataResponse acknowledgement =
                    JSON.readValue(response.body(), ProxyDataResponse.class);

            if (acknowledgement.rejected() > 0) {
                // Usually means the proxy holds stale configuration and is
                // still collecting items that have moved or been deleted.
                log.warn("The server rejected {} of {} uploaded value(s); "
                                + "this proxy's configuration may be out of date",
                        acknowledgement.rejected(), results.size());
            }

            return Optional.of(acknowledgement);

        } catch (IOException e) {
            log.warn("Could not upload {} result(s): {}", results.size(), Failures.describe(e));
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private HttpResponse<String> send(String path, String method, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create(properties.getServerUrl() + path))
                .timeout(properties.getRequestTimeout())
                .header(TOKEN_HEADER, properties.getToken())
                .header("Content-Type", "application/json; charset=utf-8");

        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }

        return httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }

    /** What this proxy reports about itself when enrolling. */
    public record EnrolmentRequest(String version, String hostname) {
    }

    /** What the server returns once the token is accepted. */
    public record EnrolmentResult(Long proxyId, String proxyName,
                                  long configRevision, int uploadIntervalSeconds) {
    }
}
