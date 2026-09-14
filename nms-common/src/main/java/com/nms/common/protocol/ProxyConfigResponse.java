package com.nms.common.protocol;

import com.nms.common.CheckRequest;

import java.time.Instant;
import java.util.List;

/**
 * The item assignment a proxy pulls from the cloud server.
 *
 * <p>{@code revision} lets a proxy poll cheaply: it sends the revision it last
 * applied and the server replies with {@code changed=false} and an empty item
 * list when nothing has moved.
 *
 * @param proxyId        server-side identifier of the requesting proxy
 * @param revision       monotonically increasing configuration revision
 * @param changed        false when the proxy's cached configuration is current
 * @param items          full set of checks this proxy is responsible for
 * @param generatedAt    server time the configuration was produced
 * @param uploadInterval seconds between result uploads
 */
public record ProxyConfigResponse(
        long proxyId,
        long revision,
        boolean changed,
        List<CheckRequest> items,
        Instant generatedAt,
        int uploadInterval) {

    public static ProxyConfigResponse unchanged(long proxyId, long revision, int uploadInterval) {
        return new ProxyConfigResponse(proxyId, revision, false, List.of(), Instant.now(), uploadInterval);
    }
}
