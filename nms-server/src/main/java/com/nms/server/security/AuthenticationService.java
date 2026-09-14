package com.nms.server.security;

import com.nms.server.domain.AppUser;
import com.nms.server.domain.AuditAction;
import com.nms.server.domain.AuditLog;
import com.nms.server.domain.Role;
import com.nms.server.repository.CoreRepositories.AppUserRepository;
import com.nms.server.repository.CoreRepositories.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Verifies credentials and issues sessions.
 *
 * <p>Failed attempts are counted and lock the account temporarily. Temporarily,
 * not permanently: a permanent lock turns a password-guessing attempt into a
 * denial of service against the operator who needs to log in during the
 * incident the attacker may have caused.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final AppUserRepository users;
    private final AuditLogRepository auditLog;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final int maxFailedLogins;
    private final Duration lockoutDuration;

    public AuthenticationService(AppUserRepository users,
                                 AuditLogRepository auditLog,
                                 PasswordEncoder passwordEncoder,
                                 JwtService jwtService,
                                 @Value("${nms.security.max-failed-logins:5}") int maxFailedLogins,
                                 @Value("${nms.security.lockout-minutes:15}") long lockoutMinutes) {
        this.users = users;
        this.auditLog = auditLog;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.maxFailedLogins = maxFailedLogins;
        this.lockoutDuration = Duration.ofMinutes(lockoutMinutes);
    }

    /**
     * Authenticates a username and password.
     *
     * @throws AuthenticationFailedException with a deliberately vague message.
     *         Distinguishing "no such user" from "wrong password" would let
     *         anyone enumerate valid accounts.
     */
    @Transactional
    public Session login(String username, String password, String clientIp) {
        Optional<AppUser> found = users.findByUsernameWithAuthorities(username);

        if (found.isEmpty()) {
            // The password is still hashed against a dummy value so that a
            // missing account and a wrong password take the same time. Without
            // it, response timing alone reveals which usernames exist.
            passwordEncoder.matches(password, "$2a$12$0000000000000000000000000000000000000000000000000000");
            recordFailure(null, username, clientIp, "no such user");
            throw new AuthenticationFailedException("Invalid username or password");
        }

        AppUser user = found.get();

        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            recordFailure(user.getId(), username, clientIp, "account is locked");
            throw new AuthenticationFailedException(
                    "This account is temporarily locked after repeated failed sign-in attempts. "
                            + "Try again later or ask an administrator to unlock it.");
        }

        if (!user.isActive()) {
            recordFailure(user.getId(), username, clientIp, "account is disabled");
            throw new AuthenticationFailedException("This account is disabled");
        }

        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            registerFailedAttempt(user);
            recordFailure(user.getId(), username, clientIp, "wrong password");
            throw new AuthenticationFailedException("Invalid username or password");
        }

        user.setFailedLogins(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());
        users.save(user);

        recordSuccess(user, clientIp);
        return issueSession(user);
    }

    private void registerFailedAttempt(AppUser user) {
        int failures = user.getFailedLogins() + 1;
        user.setFailedLogins(failures);

        if (failures >= maxFailedLogins) {
            user.setLockedUntil(Instant.now().plus(lockoutDuration));
            log.warn("Account '{}' locked for {} after {} failed sign-in attempts",
                    user.getUsername(), lockoutDuration, failures);
        }
        users.save(user);
    }

    /**
     * Exchanges a refresh token for a new session.
     *
     * <p>Permissions are re-read from the database rather than copied from the
     * old token, so revoking a role takes effect at the next refresh instead of
     * whenever the last issued token happens to expire.
     */
    @Transactional
    public Session refresh(String refreshToken) {
        JwtService.TokenClaims claims = jwtService.verifyRefreshToken(refreshToken);
        if (claims == null) {
            throw new AuthenticationFailedException("The refresh token is invalid or has expired");
        }

        AppUser user = users.findById(claims.userId())
                .orElseThrow(() -> new AuthenticationFailedException("The account no longer exists"));

        if (!user.isActive()) {
            throw new AuthenticationFailedException("This account is disabled");
        }

        return issueSession(user);
    }

    private Session issueSession(AppUser user) {
        List<String> permissions = permissionsOf(user);
        String accessToken = jwtService.issueAccessToken(
                user.getId(), user.getUsername(), user.getTenantId(), permissions);
        String refreshToken = jwtService.issueRefreshToken(user.getId(), user.getTenantId());

        return new Session(accessToken, refreshToken,
                jwtService.accessTokenLifetime().toSeconds(),
                user.getId(), user.getUsername(), user.displayName(),
                user.getRole() == null ? null : user.getRole().getName(),
                user.getTenantId(), permissions, user.getTheme(), user.getTimezone());
    }

    private static List<String> permissionsOf(AppUser user) {
        Role role = user.getRole();
        // No role means no rights. Defaulting to anything else would make a
        // misconfigured account more privileged than intended, which is the
        // wrong direction to fail in.
        return role == null ? List.of() : List.copyOf(role.getPermissions());
    }

    private void recordSuccess(AppUser user, String clientIp) {
        AuditLog entry = new AuditLog();
        entry.setTenantId(user.getTenantId());
        entry.setUserId(user.getId());
        entry.setUsername(user.getUsername());
        entry.setIp(clientIp == null ? "" : clientIp);
        entry.setAction(AuditAction.LOGIN);
        entry.setResourceType("session");
        auditLog.save(entry);
    }

    private void recordFailure(Long userId, String username, String clientIp, String reason) {
        AuditLog entry = new AuditLog();
        // Failures on an unknown username still belong to the default tenant's
        // audit trail: a burst of them is exactly what an administrator needs
        // to see, and discarding them would hide an attack in progress.
        entry.setTenantId(com.nms.server.domain.Tenant.DEFAULT_ID);
        entry.setUserId(userId);
        entry.setUsername(username == null ? "" : username);
        entry.setIp(clientIp == null ? "" : clientIp);
        entry.setAction(AuditAction.LOGIN_FAILED);
        entry.setResourceType("session");
        entry.setDetails(java.util.Map.of("reason", reason));
        auditLog.save(entry);
    }

    /** An issued session. */
    public record Session(String accessToken, String refreshToken, long expiresInSeconds,
                          Long userId, String username, String fullName, String role,
                          Long tenantId, List<String> permissions, String theme, String timezone) {
    }

    /** Raised when credentials are rejected. */
    public static class AuthenticationFailedException extends RuntimeException {
        public AuthenticationFailedException(String message) {
            super(message);
        }
    }
}
