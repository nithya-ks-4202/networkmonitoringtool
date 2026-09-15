package com.nms.collector.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers an HTTP digest challenge.
 *
 * <p>Separate from the RTSP implementation in
 * {@code com.nms.collector.camera.RtspPoller} because the two dialects differ
 * where it matters: RTSP cameras almost universally speak the older RFC 2069
 * form with no {@code qop}, while camera HTTP APIs send {@code qop="auth"} and
 * reject a response computed without the client nonce and counter. Sharing one
 * implementation would mean one of them silently failing to authenticate,
 * which presents as a permanently unsupported item rather than as an error
 * anyone can read.
 *
 * <p>Both forms are produced here, chosen by what the challenge actually
 * offers, so this also covers the older cameras.
 */
public final class HttpDigestAuth {

    private static final Pattern PARAMETER =
            Pattern.compile("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))");

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The nonce counter sent as {@code nc}.
     *
     * <p>Servers that enforce it reject a repeated value as a replay, so it
     * has to advance on every request. Static and process-wide: a single
     * ever-increasing sequence is always acceptable, whereas per-poller
     * counters could hand the same camera the same nc twice.
     */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private HttpDigestAuth() {
    }

    /**
     * Builds the {@code Authorization} header for a challenge.
     *
     * @param challenge the {@code WWW-Authenticate} header value
     * @param method    HTTP method, uppercase
     * @param uri       request path, exactly as sent on the request line
     * @return the header value, or {@code null} if the challenge is not one
     *         this understands
     */
    public static String authorization(String challenge, String method, String uri,
                                       String username, String password) {
        if (challenge == null) {
            return null;
        }

        String trimmed = challenge.trim();
        if (trimmed.regionMatches(true, 0, "Basic", 0, 5)) {
            // Older firmware, and some cameras once a user is set to "basic".
            // Accepted because refusing it would mean the check cannot run at
            // all; it is no weaker than the camera itself is configured to be.
            String credentials = java.util.Base64.getEncoder().encodeToString(
                    (username + ':' + password).getBytes(StandardCharsets.UTF_8));
            return "Basic " + credentials;
        }

        if (!trimmed.regionMatches(true, 0, "Digest", 0, 6)) {
            return null;
        }

        Map<String, String> parameters = parse(trimmed.substring(6));
        String realm = parameters.get("realm");
        String nonce = parameters.get("nonce");
        if (realm == null || nonce == null) {
            return null;
        }

        String qop = selectQop(parameters.get("qop"));
        String opaque = parameters.get("opaque");
        String algorithm = parameters.getOrDefault("algorithm", "MD5");

        // Only MD5 is handled. SHA-256 digest exists in RFC 7616 but no
        // camera firmware in circulation uses it, and guessing wrong produces
        // a 401 loop rather than a clear failure.
        if (!algorithm.equalsIgnoreCase("MD5")) {
            return null;
        }

        String ha1 = md5(username + ':' + realm + ':' + password);
        String ha2 = md5(method + ':' + uri);

        StringBuilder header = new StringBuilder("Digest ");
        header.append("username=\"").append(username).append("\", ");
        header.append("realm=\"").append(realm).append("\", ");
        header.append("nonce=\"").append(nonce).append("\", ");
        header.append("uri=\"").append(uri).append("\"");

        String response;
        if (qop != null) {
            String cnonce = randomHex();
            String nc = String.format("%08x", COUNTER.incrementAndGet());
            response = md5(ha1 + ':' + nonce + ':' + nc + ':' + cnonce + ':' + qop + ':' + ha2);
            header.append(", qop=").append(qop);
            header.append(", nc=").append(nc);
            header.append(", cnonce=\"").append(cnonce).append("\"");
        } else {
            response = md5(ha1 + ':' + nonce + ':' + ha2);
        }

        header.append(", response=\"").append(response).append("\"");
        if (opaque != null) {
            header.append(", opaque=\"").append(opaque).append("\"");
        }
        header.append(", algorithm=MD5");
        return header.toString();
    }

    /**
     * Picks a qop the client can honour.
     *
     * <p>The header may list several. {@code auth-int} is not implemented --
     * it digests the request body, which these requests do not have -- so it
     * is passed over rather than claimed and then computed wrongly.
     */
    private static String selectQop(String offered) {
        if (offered == null || offered.isBlank()) {
            return null;
        }
        for (String candidate : offered.split(",")) {
            if (candidate.trim().equalsIgnoreCase("auth")) {
                return "auth";
            }
        }
        return null;
    }

    static Map<String, String> parse(String parameters) {
        Map<String, String> values = new HashMap<>();
        Matcher matcher = PARAMETER.matcher(parameters);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            values.put(matcher.group(1).toLowerCase(Locale.ROOT), value);
        }
        return values;
    }

    private static String randomHex() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return hex(bytes);
    }

    static String md5(String input) {
        try {
            return hex(MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Mandated by the JRE specification; unreachable in practice.
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            text.append(Character.forDigit((b >> 4) & 0xF, 16));
            text.append(Character.forDigit(b & 0xF, 16));
        }
        return text.toString();
    }
}
