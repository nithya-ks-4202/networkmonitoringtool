package com.nms.server.api;

import com.nms.server.security.AuthenticatedUser;
import com.nms.server.security.AuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Sign in and session management")
public class AuthController {

    private final AuthenticationService authentication;

    public AuthController(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange a username and password for a session")
    public AuthenticationService.Session login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        return authentication.login(request.username(), request.password(), clientIp(httpRequest));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new access token")
    public AuthenticationService.Session refresh(@Valid @RequestBody RefreshRequest request) {
        return authentication.refresh(request.refreshToken());
    }

    @GetMapping("/me")
    @Operation(summary = "Describe the currently authenticated caller")
    public ResponseEntity<Map<String, Object>> me() {
        return AuthenticatedUser.current()
                .map(user -> ResponseEntity.ok(Map.<String, Object>of(
                        "userId", user.userId(),
                        "username", user.username(),
                        "tenantId", user.tenantId(),
                        "permissions", user.permissions())))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }

    /**
     * The caller's address for the audit trail.
     *
     * <p>{@code X-Forwarded-For} is read first because the server sits behind a
     * load balancer in every hosted deployment, where the socket address would
     * otherwise be the balancer's and identical for every user. The leftmost
     * entry is the original client; it is only as trustworthy as the proxy in
     * front, which is why this is used for audit rather than for access control.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp : request.getRemoteAddr();
    }

    /** Credentials submitted to sign in. */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /** A refresh token being exchanged. */
    public record RefreshRequest(@NotBlank String refreshToken) {
    }
}
