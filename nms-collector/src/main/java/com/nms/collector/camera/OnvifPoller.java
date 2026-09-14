package com.nms.collector.camera;

import com.nms.collector.Poller;
import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import com.nms.common.ItemValueType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probes a camera's ONVIF device service.
 *
 * <p>ONVIF is the management plane of an IP camera: it answers even when the
 * video pipeline is broken, and it carries the identity of the device.
 * Two operations are used.
 *
 * <p>{@code GetSystemDateAndTime} is specified as unauthenticated, which makes
 * it the ideal liveness probe -- it works on a camera whose credentials are
 * unknown or have not been configured in the platform yet. It also exposes
 * clock drift, which is worth alerting on in its own right: footage with the
 * wrong timestamp is worthless as evidence.
 *
 * <p>{@code GetDeviceInformation} needs WS-Security and returns manufacturer,
 * model, firmware and serial number, which populate host inventory
 * automatically.
 */
@Component
public class OnvifPoller implements Poller {

    private static final Logger log = LoggerFactory.getLogger(OnvifPoller.class);

    private static final String DEVICE_SERVICE_PATH = "/onvif/device_service";
    private static final String SOAP_ENVELOPE_NS = "http://www.w3.org/2003/05/soap-envelope";
    private static final String DEVICE_NS = "http://www.onvif.org/ver10/device/wsdl";
    private static final String WSSE_NS =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    private static final String WSU_NS =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd";
    private static final String PASSWORD_DIGEST_TYPE =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Fields lifted out of a GetDeviceInformation response. */
    private static final String[] DEVICE_INFO_FIELDS =
            {"Manufacturer", "Model", "FirmwareVersion", "SerialNumber", "HardwareId"};

    private final HttpClient httpClient;

    public OnvifPoller() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // Cameras routinely present self-signed certificates and
                // redirect between http and https; following redirects keeps
                // the check working without per-device configuration.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public CheckType checkType() {
        return CheckType.ONVIF;
    }

    @Override
    public CheckResult poll(CheckRequest request) {
        int port = request.intParam("port", request.port() > 0 ? request.port() : 80);
        String scheme = "true".equalsIgnoreCase(request.param("tls", "false")) ? "https" : "http";
        String endpoint = scheme + "://" + request.address() + ':' + port
                + request.param("path", DEVICE_SERVICE_PATH);
        String operation = request.param("operation", "GetSystemDateAndTime");
        String username = request.param("username", "");
        String password = request.param("password", "");

        try {
            return switch (operation) {
                case "GetDeviceInformation" ->
                        deviceInformation(request, endpoint, username, password);
                case "GetSystemDateAndTime" ->
                        systemDateAndTime(request, endpoint);
                default -> CheckResult.failed(request.itemId(),
                        "Unsupported ONVIF operation '" + operation + "'");
            };
        } catch (IOException e) {
            // The camera is not answering ONVIF. For a status item that is a
            // measured 0; for an informational item there is nothing to store.
            log.debug("ONVIF {} failed for {}: {}", operation, endpoint, e.getMessage());
            if (isStatusItem(request)) {
                return CheckResult.ok(request.itemId(), 0L, ItemValueType.UNSIGNED);
            }
            return CheckResult.failed(request.itemId(), "ONVIF unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CheckResult.failed(request.itemId(), "interrupted during ONVIF check");
        }
    }

    private CheckResult systemDateAndTime(CheckRequest request, String endpoint)
            throws IOException, InterruptedException {
        String body = soapEnvelope(null, "<tds:GetSystemDateAndTime/>");
        HttpResponse<String> response = send(endpoint, body, request.timeout());

        boolean ok = response.statusCode() == 200
                && response.body().contains("GetSystemDateAndTimeResponse");

        if (isStatusItem(request)) {
            return CheckResult.ok(request.itemId(), ok ? 1L : 0L, ItemValueType.UNSIGNED);
        }

        if (!ok) {
            return CheckResult.failed(request.itemId(),
                    "ONVIF returned HTTP " + response.statusCode() + ": " + soapFault(response.body()));
        }

        // Clock skew: the camera reports UTC in the response body.
        Instant cameraTime = parseUtcDateTime(response.body());
        if (cameraTime == null) {
            return CheckResult.failed(request.itemId(), "no UTCDateTime in ONVIF response");
        }
        long skewSeconds = Math.abs(Duration.between(cameraTime, Instant.now()).toSeconds());
        return CheckResult.ok(request.itemId(), skewSeconds, ItemValueType.UNSIGNED);
    }

    private CheckResult deviceInformation(CheckRequest request, String endpoint,
                                          String username, String password)
            throws IOException, InterruptedException {
        String security = username.isEmpty() ? null : wsSecurityHeader(username, password);
        String body = soapEnvelope(security, "<tds:GetDeviceInformation/>");
        HttpResponse<String> response = send(endpoint, body, request.timeout());

        if (response.statusCode() != 200) {
            return CheckResult.failed(request.itemId(),
                    "ONVIF GetDeviceInformation returned HTTP " + response.statusCode()
                            + ": " + soapFault(response.body()));
        }

        Map<String, String> info = new LinkedHashMap<>();
        for (String field : DEVICE_INFO_FIELDS) {
            String value = extractElement(response.body(), field);
            if (value != null && !value.isBlank()) {
                info.put(field, value);
            }
        }

        if (info.isEmpty()) {
            return CheckResult.failed(request.itemId(),
                    "ONVIF response contained no device information fields");
        }

        // Stored as JSON so host inventory population and LLD can both consume
        // it without a second parse of raw SOAP.
        return CheckResult.ok(request.itemId(), JSON.writeValueAsString(info), ItemValueType.TEXT);
    }

    private HttpResponse<String> send(String endpoint, String soapBody, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/soap+xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(soapBody, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String soapEnvelope(String securityHeader, String bodyContent) {
        StringBuilder envelope = new StringBuilder(512);
        envelope.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<soap:Envelope xmlns:soap=\"").append(SOAP_ENVELOPE_NS)
                .append("\" xmlns:tds=\"").append(DEVICE_NS).append("\">");
        if (securityHeader != null) {
            envelope.append("<soap:Header>").append(securityHeader).append("</soap:Header>");
        }
        envelope.append("<soap:Body>").append(bodyContent).append("</soap:Body></soap:Envelope>");
        return envelope.toString();
    }

    /**
     * Builds a WS-Security UsernameToken with a digested password.
     *
     * <p>PasswordDigest is Base64(SHA1(nonce + created + password)), with the
     * nonce transmitted Base64-encoded but hashed raw. Getting that distinction
     * wrong is the usual cause of ONVIF authentication failing against every
     * camera regardless of credentials.
     */
    private static String wsSecurityHeader(String username, String password) {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String created = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));

        byte[] createdBytes = created.getBytes(StandardCharsets.UTF_8);
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);

        byte[] toHash = new byte[nonce.length + createdBytes.length + passwordBytes.length];
        System.arraycopy(nonce, 0, toHash, 0, nonce.length);
        System.arraycopy(createdBytes, 0, toHash, nonce.length, createdBytes.length);
        System.arraycopy(passwordBytes, 0, toHash, nonce.length + createdBytes.length, passwordBytes.length);

        String digest;
        try {
            digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(toHash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }

        return "<wsse:Security xmlns:wsse=\"" + WSSE_NS + "\" xmlns:wsu=\"" + WSU_NS + "\">"
                + "<wsse:UsernameToken>"
                + "<wsse:Username>" + escapeXml(username) + "</wsse:Username>"
                + "<wsse:Password Type=\"" + PASSWORD_DIGEST_TYPE + "\">" + digest + "</wsse:Password>"
                + "<wsse:Nonce>" + Base64.getEncoder().encodeToString(nonce) + "</wsse:Nonce>"
                + "<wsu:Created>" + created + "</wsu:Created>"
                + "</wsse:UsernameToken></wsse:Security>";
    }

    /**
     * Pulls a single element's text out of a SOAP response.
     *
     * <p>Deliberately a regex rather than a DOM parse: camera SOAP is small,
     * frequently namespace-inconsistent, and occasionally malformed enough that
     * a strict parser rejects a response a human would read fine. A full parser
     * here would also mean defending against XXE on input from an untrusted
     * device.
     */
    private static String extractElement(String xml, String localName) {
        Matcher matcher = Pattern.compile(
                "<(?:[\\w.-]+:)?" + Pattern.quote(localName) + "(?:\\s[^>]*)?>(.*?)</(?:[\\w.-]+:)?"
                        + Pattern.quote(localName) + ">",
                Pattern.DOTALL).matcher(xml);
        return matcher.find() ? unescapeXml(matcher.group(1).trim()) : null;
    }

    private static Instant parseUtcDateTime(String xml) {
        String year = extractElement(xml, "Year");
        String month = extractElement(xml, "Month");
        String day = extractElement(xml, "Day");
        String hour = extractElement(xml, "Hour");
        String minute = extractElement(xml, "Minute");
        String second = extractElement(xml, "Second");

        if (year == null || month == null || day == null
                || hour == null || minute == null || second == null) {
            return null;
        }
        try {
            return java.time.LocalDateTime.of(
                            Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day),
                            Integer.parseInt(hour), Integer.parseInt(minute), Integer.parseInt(second))
                    .toInstant(java.time.ZoneOffset.UTC);
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null;
        }
    }

    private static String soapFault(String body) {
        String reason = extractElement(body, "Text");
        if (reason != null) {
            return reason;
        }
        return body == null || body.length() <= 200 ? String.valueOf(body) : body.substring(0, 200) + "...";
    }

    private static boolean isStatusItem(CheckRequest request) {
        return request.valueType() == ItemValueType.UNSIGNED;
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String unescapeXml(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
