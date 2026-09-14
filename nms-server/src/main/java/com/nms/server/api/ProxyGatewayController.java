package com.nms.server.api;

import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.protocol.ProxyConfigResponse;
import com.nms.common.protocol.ProxyDataRequest;
import com.nms.common.protocol.ProxyDataResponse;
import com.nms.server.domain.Proxy;
import com.nms.server.service.ProxyGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * The endpoint on-premise proxies talk to.
 *
 * <p>This is what lets a cloud-hosted platform monitor a private network. The
 * cameras and switches on a customer LAN are not reachable from the internet,
 * and no security team is going to open inbound firewall rules to a monitoring
 * vendor. A proxy inside the network polls locally and pushes results out over
 * an ordinary outbound HTTPS connection -- the same direction as any other
 * outbound traffic, through the same egress controls.
 *
 * <p>Authentication is by enrolment token rather than by user session, so these
 * routes sit outside the main security filter chain and verify the token
 * themselves.
 */
@RestController
@RequestMapping("/api/proxy")
@Tag(name = "Proxy gateway", description = "Configuration and data upload for on-premise collectors")
public class ProxyGatewayController {

    private static final Logger log = LoggerFactory.getLogger(ProxyGatewayController.class);
    private static final String TOKEN_HEADER = "X-Proxy-Token";

    private final ProxyGatewayService gateway;

    public ProxyGatewayController(ProxyGatewayService gateway) {
        this.gateway = gateway;
    }

    /**
     * Returns the checks this proxy is responsible for.
     *
     * <p>The proxy sends the revision it last applied, and gets back
     * {@code changed=false} when nothing has moved. A proxy responsible for
     * thousands of items would otherwise re-download its whole assignment every
     * minute over a link that is often the customer's office broadband.
     */
    @GetMapping("/config")
    @Operation(summary = "Fetch this proxy's item assignment")
    public ResponseEntity<ProxyConfigResponse> config(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestParam(required = false, defaultValue = "0") long knownRevision,
            HttpServletRequest request) {

        Optional<Proxy> authenticated = gateway.authenticate(token);
        if (authenticated.isEmpty()) {
            log.warn("Rejected a proxy configuration request from {} with an invalid token",
                    request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(gateway.buildConfiguration(authenticated.get(), knownRevision));
    }

    /**
     * Accepts a batch of collected values.
     *
     * <p>Idempotent on the batch identifier, so a proxy may safely re-send a
     * batch it never saw acknowledged -- the normal outcome of a connection
     * dropping mid-upload, which on a site with an unreliable link is a daily
     * event rather than an edge case.
     */
    @PostMapping("/data")
    @Operation(summary = "Upload collected values")
    public ResponseEntity<ProxyDataResponse> data(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody ProxyDataRequest request,
            HttpServletRequest httpRequest) {

        Optional<Proxy> authenticated = gateway.authenticate(token);
        if (authenticated.isEmpty()) {
            log.warn("Rejected a proxy data upload from {} with an invalid token",
                    httpRequest.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return ResponseEntity.ok(gateway.acceptData(authenticated.get(), request));
    }

    /**
     * Confirms a token before a proxy commits to using it.
     *
     * <p>Exists so that an operator running the install command gets an
     * immediate, clear answer rather than discovering the token was mistyped
     * only when data fails to appear an hour later.
     */
    @PostMapping("/enrol")
    @Operation(summary = "Verify an enrolment token and return the proxy's identity")
    public ResponseEntity<EnrolmentResponse> enrol(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody EnrolmentRequest request) {

        return gateway.authenticate(token)
                .map(proxy -> {
                    gateway.recordEnrolment(proxy, request.version());
                    return ResponseEntity.ok(new EnrolmentResponse(
                            proxy.getId(), proxy.getName(), proxy.getConfigRevision(),
                            gateway.uploadIntervalSeconds()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    /** What a proxy reports about itself on enrolment. */
    public record EnrolmentRequest(String version, String hostname) {
    }

    /** What the server tells a proxy once its token is accepted. */
    public record EnrolmentResponse(Long proxyId, String proxyName,
                                    long configRevision, int uploadIntervalSeconds) {
    }
}
