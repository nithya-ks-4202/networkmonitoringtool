package com.nms.common.protocol;

/**
 * Server acknowledgement of an uploaded batch.
 *
 * @param batchId        the batch being acknowledged
 * @param accepted       number of values written
 * @param rejected       number of values discarded (unknown or deleted items)
 * @param configRevision current configuration revision, so the proxy can
 *                       notice it is stale without a separate request
 * @param clockSkewMs    difference between proxy and server clocks
 */
public record ProxyDataResponse(
        String batchId,
        int accepted,
        int rejected,
        long configRevision,
        long clockSkewMs) {
}
