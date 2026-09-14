package com.nms.server.api;

import com.nms.server.domain.Proxy;
import com.nms.server.domain.ProxyMode;
import com.nms.server.repository.CoreRepositories.ProxyRepository;
import com.nms.server.repository.HostRepository;
import com.nms.server.security.AuthenticatedUser;
import com.nms.server.service.ProxyGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Managing on-premise collectors.
 *
 * <p>Separate from {@link ProxyGatewayController}, which is what the proxies
 * themselves talk to. This is what an operator uses, and it authenticates as a
 * user; that one authenticates by enrolment token and sits outside the session
 * security chain entirely.
 */
@RestController
@RequestMapping("/api/proxies")
@Tag(name = "Proxies", description = "On-premise collector administration")
public class ProxyAdminController {

    private final ProxyGatewayService gateway;
    private final ProxyRepository proxies;
    private final HostRepository hosts;
    private final Duration offlineAfter;

    public ProxyAdminController(ProxyGatewayService gateway,
                                ProxyRepository proxies,
                                HostRepository hosts,
                                @Value("${nms.proxy.offline-after-seconds:180}") long offlineAfterSeconds) {
        this.gateway = gateway;
        this.proxies = proxies;
        this.hosts = hosts;
        this.offlineAfter = Duration.ofSeconds(offlineAfterSeconds);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('proxy.read')")
    @Operation(summary = "List proxies with their current state")
    @Transactional(readOnly = true)
    public List<ProxyView> list() {
        Long tenantId = AuthenticatedUser.currentTenantId();
        return proxies.findByTenantIdOrderByName(tenantId).stream()
                .map(proxy -> ProxyView.from(proxy, hosts.findByProxyId(proxy.getId()).size(), offlineAfter))
                .toList();
    }

    /**
     * Creates a proxy and returns its enrolment token.
     *
     * <p>The token is shown here and never again: only its hash is stored, so a
     * database leak does not hand out working collector credentials. An
     * operator who loses it issues a new one rather than recovering the old.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('proxy.write')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a proxy; returns the enrolment token once")
    public NewProxyView create(@Valid @RequestBody ProxyRequest request) {
        ProxyGatewayService.NewProxy created = gateway.create(
                AuthenticatedUser.currentTenantId(), request.name(), request.description());

        return new NewProxyView(created.proxyId(), created.name(), created.token(),
                "Copy this token now. It is not stored in a form that can be shown again.");
    }

    /**
     * Issues a new token, invalidating the previous one immediately.
     *
     * <p>The old token stops working the moment this returns, so the proxy is
     * offline until it is reconfigured. That is the intended behaviour for a
     * token believed compromised, and worth knowing before clicking it.
     */
    @PostMapping("/{proxyId}/token")
    @PreAuthorize("hasAuthority('proxy.write')")
    @Operation(summary = "Replace a proxy's token; the old one stops working at once")
    public NewProxyView regenerateToken(@PathVariable Long proxyId) {
        ProxyGatewayService.NewProxy issued =
                gateway.regenerateToken(AuthenticatedUser.currentTenantId(), proxyId);

        return new NewProxyView(issued.proxyId(), issued.name(), issued.token(),
                "The previous token no longer works. This proxy is offline until it is "
                        + "restarted with the new one.");
    }

    @DeleteMapping("/{proxyId}")
    @PreAuthorize("hasAuthority('proxy.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a proxy that has no hosts assigned")
    @Transactional
    public void delete(@PathVariable Long proxyId) {
        Long tenantId = AuthenticatedUser.currentTenantId();
        Proxy proxy = proxies.findById(proxyId)
                .filter(candidate -> candidate.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("No such proxy: " + proxyId));

        // Deleting a proxy with hosts still on it would silently move them to
        // being polled by the server, which cannot reach them -- so every one
        // would go unreachable with no obvious cause.
        int assigned = hosts.findByProxyId(proxyId).size();
        if (assigned > 0) {
            throw new IllegalStateException(
                    "Cannot delete '" + proxy.getName() + "': " + assigned
                            + " host(s) are assigned to it. Reassign them first, or the server "
                            + "would try to poll them directly and fail.");
        }

        proxies.delete(proxy);
    }

    /** A proxy as the interface lists it. */
    public record ProxyView(
            Long id,
            String name,
            String description,
            ProxyMode mode,
            boolean enabled,
            boolean online,
            Instant lastSeenAt,
            String lastSeenAge,
            String version,
            int assignedHosts,
            /** Results still buffered on the proxy; a rising value means uploads are failing. */
            int queueDepth,
            /**
             * Difference between the proxy's clock and the server's. A large
             * value makes every timestamp it reports wrong, which shows up as
             * graphs and trigger windows that are subtly and confusingly off.
             */
            long clockSkewMs,
            String tokenPrefix) {

        static ProxyView from(Proxy proxy, int assignedHosts, Duration offlineAfter) {
            Instant lastSeen = proxy.getLastSeenAt();
            return new ProxyView(
                    proxy.getId(),
                    proxy.getName(),
                    proxy.getDescription(),
                    proxy.getMode(),
                    proxy.isEnabled(),
                    proxy.isOnline(offlineAfter),
                    lastSeen,
                    lastSeen == null ? "never"
                            : com.nms.server.util.Durations.human(Duration.between(lastSeen, Instant.now())),
                    proxy.getVersion(),
                    assignedHosts,
                    proxy.getQueueDepth(),
                    proxy.getClockSkewMs(),
                    // The prefix only, so an operator can tell which token a
                    // proxy is configured with without the token being readable.
                    proxy.getTokenPrefix());
        }
    }

    /** A proxy being created. */
    public record ProxyRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 1000) String description) {
    }

    /** A newly issued token, returned once. */
    public record NewProxyView(Long proxyId, String name, String token, String warning) {
    }
}
