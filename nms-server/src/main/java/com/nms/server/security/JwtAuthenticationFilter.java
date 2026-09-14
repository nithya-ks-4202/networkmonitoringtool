package com.nms.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates a request from its bearer token.
 *
 * <p>Permissions from the token become Spring Security authorities, so
 * controllers can express their requirements declaratively with
 * {@code @PreAuthorize("hasAuthority('host.write')")} rather than checking by
 * hand -- which is the kind of check that gets forgotten on the one endpoint
 * that mattered.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            JwtService.TokenClaims claims = jwtService.verifyAccessToken(token);
            if (claims != null) {
                authenticate(request, claims);
            }
            // An invalid token is left unauthenticated rather than rejected
            // here, so the security chain decides: a public endpoint still
            // works, and a protected one returns a clean 401.
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, JwtService.TokenClaims claims) {
        // The wildcard is expanded into concrete authorities: hasAuthority() is
        // an exact string match, so a literal "*" in the token would grant
        // nothing at all rather than everything.
        List<SimpleGrantedAuthority> authorities = Permissions.expand(claims.permissions()).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        var authentication = new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(claims.userId(), claims.username(), claims.tenantId(),
                        claims.permissions()),
                null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length()).trim();
        }
        return null;
    }
}
