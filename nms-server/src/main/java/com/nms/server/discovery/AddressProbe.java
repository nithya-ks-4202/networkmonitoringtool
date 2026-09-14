package com.nms.server.discovery;

import com.nms.collector.PollerRegistry;
import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import com.nms.common.ItemValueType;
import com.nms.server.domain.DiscoveryCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a rule's checks against one address.
 *
 * <p>Built on the same pollers the scheduler uses rather than on a second set
 * written for discovery. A sweep that decided a camera was reachable by a
 * different code path from the one that later monitors it would be able to
 * disagree with itself, and the disagreement would surface as a host that was
 * discovered fine and has been unreachable ever since.
 */
@Component
public class AddressProbe {

    private static final Logger log = LoggerFactory.getLogger(AddressProbe.class);

    /**
     * Short by monitoring standards, because it is paid per address. Three
     * seconds across a /24 of mostly empty addresses is twelve minutes of
     * waiting for nothing; the devices that are there answer in milliseconds.
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(1200);

    /** Synthetic item id. Discovery probes belong to no item. */
    private static final long NO_ITEM = 0L;

    private final PollerRegistry pollers;

    public AddressProbe(PollerRegistry pollers) {
        this.pollers = pollers;
    }

    /**
     * Probes one address.
     *
     * @return what each check returned, or an empty map if nothing answered
     *         at all -- which is the normal case for most of a range
     */
    public Map<String, String> probe(String address, List<DiscoveryCheck> checks) {
        Map<String, String> results = new LinkedHashMap<>();
        List<Integer> openPorts = new ArrayList<>();

        for (DiscoveryCheck check : checks) {
            switch (check.getCheckType()) {
                case ICMP_PING -> icmp(address, results);
                case TCP_PORT -> tcp(address, check, openPorts);
                case SNMP -> snmp(address, check, results);
                case ONVIF -> onvif(address, check, results);
                default -> log.debug("Discovery check type {} is not supported; skipping",
                        check.getCheckType());
            }
        }

        if (!openPorts.isEmpty()) {
            results.put(DeviceClassifier.OPEN_PORTS, join(openPorts));
        }
        return results;
    }

    private void icmp(String address, Map<String, String> results) {
        CheckResult result = run(new CheckRequest(NO_ITEM, NO_ITEM, address,
                CheckType.ICMP_PING, ItemValueType.UNSIGNED, "icmpping",
                address, 0, PROBE_TIMEOUT, Map.of("packets", "1")));

        if (isTrue(result)) {
            results.put("icmp", "up");
        }
    }

    private void tcp(String address, DiscoveryCheck check, List<Integer> openPorts) {
        for (int port : ports(check)) {
            CheckResult result = run(new CheckRequest(NO_ITEM, NO_ITEM, address,
                    CheckType.TCP_PORT, ItemValueType.UNSIGNED, "net.tcp.service",
                    address, port, PROBE_TIMEOUT, Map.of()));

            if (isTrue(result)) {
                openPorts.add(port);
            }
        }
    }

    private void snmp(String address, DiscoveryCheck check, Map<String, String> results) {
        // sysDescr and sysName. Between them they identify a device far more
        // precisely than an open port does, and sysName is usually the name
        // the network team already calls it by.
        readSnmp(address, check, "1.3.6.1.2.1.1.1.0", DeviceClassifier.SNMP_DESCRIPTION, results);
        readSnmp(address, check, "1.3.6.1.2.1.1.5.0", DeviceClassifier.SNMP_NAME, results);
    }

    private void readSnmp(String address, DiscoveryCheck check, String oid,
                          String resultKey, Map<String, String> results) {
        Map<String, String> params = new LinkedHashMap<>(check.getParams());
        params.put("oid", oid);
        params.putIfAbsent("community", "public");
        params.putIfAbsent("version", "V2C");

        int port = ports(check).stream().findFirst().orElse(161);

        CheckResult result = run(new CheckRequest(NO_ITEM, NO_ITEM, address,
                CheckType.SNMP, ItemValueType.CHARACTER, oid,
                address, port, PROBE_TIMEOUT, params));

        if (result.value() != null && !result.value().toString().isBlank()) {
            results.put(resultKey, result.value().toString().trim());
        }
    }

    private void onvif(String address, DiscoveryCheck check, Map<String, String> results) {
        int port = ports(check).stream().findFirst().orElse(80);

        CheckResult result = run(new CheckRequest(NO_ITEM, NO_ITEM, address,
                CheckType.ONVIF, ItemValueType.CHARACTER, "onvif.device.info",
                address, port, PROBE_TIMEOUT, check.getParams()));

        if (result.value() != null && !result.value().toString().isBlank()) {
            results.put(DeviceClassifier.ONVIF_INFO, result.value().toString().trim());
        }
    }

    /**
     * A probe never propagates a failure.
     *
     * <p>Most addresses in a range are empty, so "nothing answered" is the
     * expected outcome rather than an error, and one unreachable address must
     * not abandon the rest of the sweep.
     */
    private CheckResult run(CheckRequest request) {
        try {
            return pollers.poll(request);
        } catch (RuntimeException e) {
            return CheckResult.failed(NO_ITEM, e.getClass().getSimpleName());
        }
    }

    private static boolean isTrue(CheckResult result) {
        if (result == null || result.value() == null) {
            return false;
        }
        // Pollers report reachability as 1/0, but as several numeric types
        // depending on the protocol, so it is read as a number rather than
        // compared against a particular boxed type.
        if (result.value() instanceof Number number) {
            return number.doubleValue() > 0;
        }
        return "1".equals(result.value().toString());
    }

    private static List<Integer> ports(DiscoveryCheck check) {
        List<Integer> parsed = new ArrayList<>();
        for (String part : check.getPorts().split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int port = Integer.parseInt(trimmed);
                if (port > 0 && port <= 65535) {
                    parsed.add(port);
                }
            } catch (NumberFormatException e) {
                log.debug("Ignoring unparseable port '{}' in discovery check {}",
                        trimmed, check.getId());
            }
        }
        return parsed;
    }

    private static String join(List<Integer> values) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(values.get(i));
        }
        return text.toString();
    }
}
