package com.nms.collector.icmp;

/**
 * Outcome of one ICMP ping run.
 *
 * @param reachable    true when at least one echo reply arrived
 * @param sent         echo requests transmitted
 * @param received     echo replies received
 * @param lossPercent  percentage of requests with no reply
 * @param minSeconds   fastest round trip, 0 when nothing came back
 * @param avgSeconds   mean round trip, 0 when nothing came back
 * @param maxSeconds   slowest round trip, 0 when nothing came back
 * @param error        why the run failed, or {@code null} when it completed
 */
public record PingResult(
        boolean reachable,
        int sent,
        int received,
        double lossPercent,
        double minSeconds,
        double avgSeconds,
        double maxSeconds,
        String error) {

    public static PingResult unreachable(String error) {
        return new PingResult(false, 0, 0, 100.0, 0, 0, 0, error);
    }
}
