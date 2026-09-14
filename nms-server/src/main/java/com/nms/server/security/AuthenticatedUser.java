package com.nms.server.security;

import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

/**
 * The caller behind the current request.
 *
 * <p>Carries the tenant, which every query needs in order to scope itself.
 * Taking it from the authenticated token rather than from a request parameter
 * is what stops one tenant reading another's data by changing a number in a URL.
 *
 * @param userId      the authenticated user
 * @param username    their login name, for audit entries
 * @param tenantId    the tenant every query must be scoped to
 * @param permissions named rights granted by their role
 */
public record AuthenticatedUser(Long userId, String username, Long tenantId, List<String> permissions) {

    public boolean grants(String permission) {
        return permissions.contains("*") || permissions.contains(permission);
    }

    /** The caller for the current request, if any. */
    public static Optional<AuthenticatedUser> current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    /**
     * The current caller's tenant.
     *
     * @throws IllegalStateException when called outside an authenticated
     *         request, which would mean a query was about to run unscoped
     */
    public static Long currentTenantId() {
        return current()
                .map(AuthenticatedUser::tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "No authenticated user: a tenant-scoped query cannot run unauthenticated"));
    }
}
