package com.nms.server.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Issues and verifies the tokens that carry a session.
 *
 * <p>Two token types. The access token is short-lived and carries the
 * permissions the API checks on every request. The refresh token is long-lived
 * and can do nothing except obtain a new access token, so a leaked access token
 * expires on its own and a leaked refresh token is visible as an unusual
 * refresh.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String CLAIM_TENANT = "tenant";
    private static final String CLAIM_PERMISSIONS = "perms";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    /** HS256 needs at least 256 bits of key material to be used safely. */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;
    private final Duration accessTokenLifetime;
    private final Duration refreshTokenLifetime;

    public JwtService(@Value("${nms.security.jwt-secret:}") String configuredSecret,
                      @Value("${nms.security.access-token-minutes:60}") long accessTokenMinutes,
                      @Value("${nms.security.refresh-token-days:7}") long refreshTokenDays) {
        this.signingKey = resolveKey(configuredSecret);
        this.accessTokenLifetime = Duration.ofMinutes(accessTokenMinutes);
        this.refreshTokenLifetime = Duration.ofDays(refreshTokenDays);
    }

    /**
     * Builds the signing key.
     *
     * <p>A generated key is used when none is configured, so a developer can
     * start the server without ceremony. It is generated rather than defaulted
     * to a constant on purpose: a shipped default secret is a shipped
     * authentication bypass, because anyone can read it in the source and mint
     * their own administrator token. The cost of generating is that tokens do
     * not survive a restart, and that is exactly the warning printed below.
     */
    private static SecretKey resolveKey(String configuredSecret) {
        if (configuredSecret != null && !configuredSecret.isBlank()) {
            byte[] material = configuredSecret.getBytes(StandardCharsets.UTF_8);
            if (material.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException(
                        "nms.security.jwt-secret must be at least " + MIN_SECRET_BYTES
                                + " characters; it is currently " + material.length);
            }
            return Keys.hmacShaKeyFor(material);
        }

        byte[] generated = new byte[64];
        new SecureRandom().nextBytes(generated);
        log.warn("""
                No NMS_JWT_SECRET is configured, so a random signing key was generated.
                Sessions will not survive a restart, and separate instances behind a load
                balancer will reject each other's tokens. Set NMS_JWT_SECRET to a value of
                at least {} characters before deploying. A suitable value:
                  {}""",
                MIN_SECRET_BYTES, Base64.getEncoder().encodeToString(generated).substring(0, 48));
        return Keys.hmacShaKeyFor(generated);
    }

    /** Issues an access token carrying the caller's identity and permissions. */
    public String issueAccessToken(Long userId, String username, Long tenantId, List<String> permissions) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim(CLAIM_TENANT, tenantId)
                .claim(CLAIM_PERMISSIONS, permissions)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenLifetime)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Issues a refresh token.
     *
     * <p>It deliberately carries no permissions: its only power is to be
     * exchanged, so a stolen one cannot be used against the API directly and
     * the exchange re-reads the user's current rights from the database.
     */
    public String issueRefreshToken(Long userId, Long tenantId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TENANT, tenantId)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTokenLifetime)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Verifies a token and returns what it asserts.
     *
     * @return the token's claims, or null when it is invalid, expired or of the
     *         wrong type. Null rather than an exception because an invalid
     *         token on a public endpoint is routine, not exceptional.
     */
    public TokenClaims verifyAccessToken(String token) {
        return verify(token, TYPE_ACCESS);
    }

    public TokenClaims verifyRefreshToken(String token) {
        return verify(token, TYPE_REFRESH);
    }

    private TokenClaims verify(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // Checked explicitly: without it a refresh token would be accepted
            // as an access token, defeating the point of separating them.
            if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
                return null;
            }

            @SuppressWarnings("unchecked")
            List<String> permissions = claims.get(CLAIM_PERMISSIONS, List.class);

            return new TokenClaims(
                    Long.parseLong(claims.getSubject()),
                    claims.get("username", String.class),
                    claims.get(CLAIM_TENANT, Number.class).longValue(),
                    permissions == null ? List.of() : permissions,
                    claims.getExpiration().toInstant());

        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            log.debug("Rejected a token: {}", e.getMessage());
            return null;
        }
    }

    public Duration accessTokenLifetime() {
        return accessTokenLifetime;
    }

    /** What a verified token asserts about its bearer. */
    public record TokenClaims(Long userId, String username, Long tenantId,
                              List<String> permissions, Instant expiresAt) {

        public boolean grants(String permission) {
            return permissions.contains("*") || permissions.contains(permission);
        }

        public Map<String, Object> toMap() {
            return Map.of("userId", userId, "username", username,
                    "tenantId", tenantId, "permissions", permissions);
        }
    }
}
