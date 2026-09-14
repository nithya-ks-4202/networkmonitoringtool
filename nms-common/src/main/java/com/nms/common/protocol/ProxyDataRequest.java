package com.nms.common.protocol;

import com.nms.common.CheckResult;

import java.time.Instant;
import java.util.List;

/**
 * A batch of collected values uploaded by a proxy.
 *
 * <p>Uploads are idempotent on {@code batchId} so a proxy may safely re-send a
 * batch it never saw acknowledged — the normal case after a network drop.
 *
 * @param proxyName   name the proxy enrolled under
 * @param batchId     client-generated unique id for this batch
 * @param results     collected values, oldest first
 * @param proxyClock  proxy's wall clock, used to detect clock skew
 * @param queueDepth  how many results remain buffered on the proxy
 * @param version     proxy build version, surfaced in the UI
 */
public record ProxyDataRequest(
        String proxyName,
        String batchId,
        List<CheckResult> results,
        Instant proxyClock,
        int queueDepth,
        String version) {
}
