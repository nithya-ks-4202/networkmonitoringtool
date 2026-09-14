package com.nms.collector.icmp;

import com.nms.collector.Poller;
import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import com.nms.common.ItemValueType;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Turns a ping run into the value of a specific ICMP item.
 *
 * <p>Three item keys share one underlying ping, mirroring the Zabbix key set so
 * existing templates and operator habits carry over:
 * <ul>
 *   <li>{@code icmpping} -- 1 when reachable, 0 when not</li>
 *   <li>{@code icmppingloss} -- packet loss percentage</li>
 *   <li>{@code icmppingsec} -- round-trip time in seconds</li>
 * </ul>
 */
@Component
public class IcmpPoller implements Poller {

    private final IcmpPinger pinger;

    public IcmpPoller(IcmpPinger pinger) {
        this.pinger = pinger;
    }

    @Override
    public CheckType checkType() {
        return CheckType.ICMP_PING;
    }

    @Override
    public CheckResult poll(CheckRequest request) {
        int packets = clamp(request.intParam("packets", 3), 1, 100);
        int intervalMs = clamp(request.intParam("interval", 200), 20, 10_000);
        int size = clamp(request.intParam("size", 56), 8, 65_000);

        PingResult ping = pinger.ping(request.address(), packets, intervalMs, size, request.timeout());

        String key = baseKey(request.key());
        return switch (key) {
            case "icmpping" ->
                // Reachability is never "unsupported": a host that does not
                // answer is reporting 0, which is the whole point of the check.
                    CheckResult.ok(request.itemId(), ping.reachable() ? 1L : 0L, ItemValueType.UNSIGNED);

            case "icmppingloss" ->
                    CheckResult.ok(request.itemId(), ping.lossPercent(), ItemValueType.FLOAT);

            case "icmppingsec" -> {
                // With no replies there is no latency to report. Storing 0
                // would poison averages and make a dead host look instant, so
                // the sample is withheld instead.
                if (!ping.reachable()) {
                    yield CheckResult.failed(request.itemId(),
                            "no ICMP reply: " + (ping.error() == null ? "100% packet loss" : ping.error()));
                }
                double value = switch (request.param("mode", "avg").toLowerCase(Locale.ROOT)) {
                    case "min" -> ping.minSeconds();
                    case "max" -> ping.maxSeconds();
                    default -> ping.avgSeconds();
                };
                yield CheckResult.ok(request.itemId(), value, ItemValueType.FLOAT);
            }

            default -> CheckResult.failed(request.itemId(),
                    "Unsupported ICMP item key '" + request.key()
                            + "'. Expected icmpping, icmppingloss or icmppingsec.");
        };
    }

    /** Strips bracketed parameters: {@code icmpping[,,,,200]} becomes {@code icmpping}. */
    private static String baseKey(String key) {
        int bracket = key.indexOf('[');
        return (bracket < 0 ? key : key.substring(0, bracket)).trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
