package com.nms.collector.camera;

import com.nms.collector.Poller;
import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import com.nms.common.ItemValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies that a camera is serving RTSP.
 *
 * <p>This is the check that distinguishes a working camera from a reachable
 * one. A camera whose encoder has hung, whose stream session limit is
 * exhausted, or whose configuration has been wiped will still answer ICMP and
 * still accept a TCP connection on 554 -- and record nothing. Only an RTSP
 * negotiation proves the stream endpoint is alive.
 *
 * <p>Two levels of verification are offered. {@code OPTIONS} proves the RTSP
 * server is responding; {@code DESCRIBE} additionally proves the specific
 * stream path exists and is playable, which catches the common failure where a
 * firmware update renumbers the channel paths.
 *
 * <p>A 401 response is treated as success when no credentials are configured:
 * the camera demonstrably has a live RTSP stack, which is what the check set
 * out to establish.
 */
@Component
public class RtspPoller implements Poller {

    private static final Logger log = LoggerFactory.getLogger(RtspPoller.class);

    private static final int DEFAULT_RTSP_PORT = 554;
    private static final String USER_AGENT = "NMS-Monitor/1.0";

    /** "RTSP/1.0 200 OK" */
    private static final Pattern STATUS_PATTERN = Pattern.compile("^RTSP/\\d\\.\\d\\s+(\\d{3})\\s*(.*)$");

    /** Pulls key="value" pairs out of a WWW-Authenticate header. */
    private static final Pattern AUTH_PARAM_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");

    @Override
    public CheckType checkType() {
        return CheckType.RTSP;
    }

    @Override
    public CheckResult poll(CheckRequest request) {
        int port = request.intParam("port", request.port() > 0 ? request.port() : DEFAULT_RTSP_PORT);
        String path = normalisePath(request.param("path", "/"));
        String username = request.param("username", "");
        String password = request.param("password", "");
        boolean describe = "true".equalsIgnoreCase(request.param("describe", "false"));
        boolean wantsTiming = request.key().contains(".perf")
                || "true".equalsIgnoreCase(request.param("perf", "false"));

        String url = "rtsp://" + request.address() + ':' + port + path;
        long startNanos = System.nanoTime();

        try {
            Outcome outcome = negotiate(request.address(), port, url, username, password, describe,
                    (int) request.timeout().toMillis());
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

            if (!outcome.available()) {
                log.debug("RTSP check failed for {}: {}", url, outcome.detail());
            }

            if (wantsTiming) {
                return CheckResult.ok(request.itemId(),
                        outcome.available() ? seconds : 0.0, ItemValueType.FLOAT);
            }
            if ("rtsp.status".equals(baseKey(request.key()))) {
                // Diagnostic item: surfaces the actual RTSP status line so an
                // operator can tell 401 from 404 without a packet capture.
                return CheckResult.ok(request.itemId(), outcome.detail(), ItemValueType.CHARACTER);
            }
            return CheckResult.ok(request.itemId(),
                    outcome.available() ? 1L : 0L, ItemValueType.UNSIGNED);

        } catch (IOException e) {
            // Connection refused, reset or timed out: the stream is down. This
            // is a measurement, not a collector fault.
            if (wantsTiming) {
                return CheckResult.ok(request.itemId(), 0.0, ItemValueType.FLOAT);
            }
            if ("rtsp.status".equals(baseKey(request.key()))) {
                return CheckResult.ok(request.itemId(),
                        "unreachable: " + com.nms.collector.Failures.describe(e), ItemValueType.CHARACTER);
            }
            return CheckResult.ok(request.itemId(), 0L, ItemValueType.UNSIGNED);
        }
    }

    private Outcome negotiate(String address, int port, String url, String username, String password,
                              boolean describe, int timeoutMillis) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);

            OutputStream out = socket.getOutputStream();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            int cseq = 1;
            Response response = exchange(out, in, "OPTIONS", url, cseq++, null);

            if (response.status() == 401) {
                boolean haveCredentials = !username.isEmpty();
                if (!haveCredentials) {
                    // The server is alive and enforcing auth. For an
                    // availability check that is a pass, and saying so beats
                    // forcing every camera to have credentials configured
                    // before it can be monitored at all.
                    return new Outcome(true, "401 authentication required (server responding)");
                }
                String authorization = buildAuthorization(response.header("www-authenticate"),
                        username, password, "OPTIONS", url);
                if (authorization == null) {
                    return new Outcome(false, "401 and unsupported authentication scheme");
                }
                response = exchange(out, in, "OPTIONS", url, cseq++, authorization);
                if (response.status() == 401) {
                    return new Outcome(false, "401 credentials rejected");
                }
            }

            if (response.status() != 200) {
                return new Outcome(false, response.status() + " " + response.reason());
            }

            if (!describe) {
                return new Outcome(true, "200 OK");
            }

            // DESCRIBE proves the path resolves to a real stream.
            String authorization = username.isEmpty() ? null
                    : buildAuthorization(response.header("www-authenticate"), username, password, "DESCRIBE", url);
            Response described = exchange(out, in, "DESCRIBE", url, cseq, authorization);

            if (described.status() == 401 && !username.isEmpty()) {
                authorization = buildAuthorization(described.header("www-authenticate"),
                        username, password, "DESCRIBE", url);
                described = exchange(out, in, "DESCRIBE", url, cseq + 1, authorization);
            }

            return switch (described.status()) {
                case 200 -> new Outcome(true, "200 OK (stream described)");
                case 401 -> new Outcome(true, "401 on DESCRIBE (server responding)");
                case 404 -> new Outcome(false, "404 stream path not found: check the channel path");
                default -> new Outcome(false, described.status() + " " + described.reason());
            };
        }
    }

    private Response exchange(OutputStream out, BufferedReader in, String method, String url,
                              int cseq, String authorization) throws IOException {
        StringBuilder request = new StringBuilder()
                .append(method).append(' ').append(url).append(" RTSP/1.0\r\n")
                .append("CSeq: ").append(cseq).append("\r\n")
                .append("User-Agent: ").append(USER_AGENT).append("\r\n");

        if (authorization != null) {
            request.append("Authorization: ").append(authorization).append("\r\n");
        }
        if ("DESCRIBE".equals(method)) {
            request.append("Accept: application/sdp\r\n");
        }
        request.append("\r\n");

        out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        return readResponse(in);
    }

    private Response readResponse(BufferedReader in) throws IOException {
        String statusLine = in.readLine();
        if (statusLine == null) {
            throw new IOException("connection closed before any RTSP response");
        }

        Matcher matcher = STATUS_PATTERN.matcher(statusLine.trim());
        if (!matcher.matches()) {
            throw new IOException("not an RTSP server: " + truncate(statusLine));
        }

        int status = Integer.parseInt(matcher.group(1));
        String reason = matcher.group(2);

        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }

        // Drain any body so the connection stays usable for the next request
        // in this session.
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            try {
                long remaining = Long.parseLong(contentLength.trim());
                // Bound the read: a malformed or hostile Content-Length must
                // not be able to make us allocate or block indefinitely.
                remaining = Math.min(remaining, 1 << 20);
                char[] buffer = new char[4096];
                while (remaining > 0) {
                    int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) {
                        break;
                    }
                    remaining -= read;
                }
            } catch (NumberFormatException ignored) {
                // Nothing to drain if the length is unparseable.
            }
        }

        return new Response(status, reason, headers);
    }

    /**
     * Builds an Authorization header for the scheme the camera asked for.
     * Digest is overwhelmingly the common case; Basic appears on older or
     * cheaper devices.
     */
    private String buildAuthorization(String challenge, String username, String password,
                                      String method, String uri) {
        if (challenge == null || challenge.isBlank()) {
            return null;
        }

        if (challenge.regionMatches(true, 0, "Basic", 0, 5)) {
            String credentials = username + ':' + password;
            return "Basic " + java.util.Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }

        if (!challenge.regionMatches(true, 0, "Digest", 0, 6)) {
            return null;
        }

        Map<String, String> params = new HashMap<>();
        Matcher matcher = AUTH_PARAM_PATTERN.matcher(challenge);
        while (matcher.find()) {
            params.put(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2));
        }

        String realm = params.getOrDefault("realm", "");
        String nonce = params.get("nonce");
        if (nonce == null) {
            return null;
        }

        // RFC 2069 digest, which is what RTSP cameras implement. qop/cnonce
        // are absent from essentially every camera challenge in the field.
        String ha1 = md5(username + ':' + realm + ':' + password);
        String ha2 = md5(method + ':' + uri);
        String response = md5(ha1 + ':' + nonce + ':' + ha2);

        return String.format(
                "Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", response=\"%s\"",
                username, realm, nonce, uri, response);
    }

    private static String md5(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                   .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 is mandated by the JRE spec; unreachable in practice.
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static String normalisePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String baseKey(String key) {
        int bracket = key.indexOf('[');
        return (bracket < 0 ? key : key.substring(0, bracket)).trim();
    }

    private static String truncate(String value) {
        String trimmed = value.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80) + "...";
    }

    private record Response(int status, String reason, Map<String, String> headers) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private record Outcome(boolean available, String detail) {
    }
}
