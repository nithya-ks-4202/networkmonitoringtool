package com.nms.collector.icmp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes ICMP echo requests and reports reachability, loss and round-trip time.
 *
 * <p>Raw ICMP sockets need privileges the JVM cannot request, so this delegates
 * to the system {@code ping} binary, which is installed setuid (or granted
 * {@code CAP_NET_RAW}) on every supported platform. {@code InetAddress.isReachable}
 * is not a substitute: without root it silently degrades to a TCP connect on
 * port 7, which reports a firewalled-but-alive host as unreachable and cannot
 * measure loss at all.
 *
 * <p>Results are cached for a short window because a single host typically has
 * three items derived from one ping run -- reachability, loss and latency. A
 * camera template with three ICMP items would otherwise triple both the packet
 * count on the network and the process spawns on the collector.
 */
public class IcmpPinger {

    private static final Logger log = LoggerFactory.getLogger(IcmpPinger.class);

    /** "3 packets transmitted, 3 received, 0% packet loss, time 2003ms" */
    private static final Pattern LOSS_PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)?)%\\s+packet\\s+loss");

    /** "rtt min/avg/max/mdev = 10.190/10.339/10.470/0.114 ms" */
    private static final Pattern RTT_PATTERN =
            Pattern.compile("(?:rtt|round-trip)\\s+min/avg/max(?:/[a-z]+)?\\s*=\\s*"
                    + "([\\d.]+)/([\\d.]+)/([\\d.]+)");

    /** Sub-200ms intervals require root on Linux; clamp so we never get EPERM. */
    private static final double MIN_INTERVAL_SECONDS = 0.2;

    private final ConcurrentHashMap<String, CachedResult> cache = new ConcurrentHashMap<>();
    private final Duration cacheTtl;
    private final String pingCommand;

    public IcmpPinger() {
        this(Duration.ofSeconds(10));
    }

    public IcmpPinger(Duration cacheTtl) {
        this(cacheTtl, locatePing());
    }

    /**
     * @param pingCommand path to the ping binary, or {@code null} to use the
     *                    reachability fallback. Injectable so the process and
     *                    parsing paths can be tested without depending on the
     *                    host's network or on ping being installed.
     */
    IcmpPinger(Duration cacheTtl, String pingCommand) {
        this.cacheTtl = cacheTtl;
        this.pingCommand = pingCommand;
    }

    /** True when real ICMP is available; false when running on the fallback. */
    public boolean hasIcmp() {
        return pingCommand != null;
    }

    /**
     * Pings a host, reusing a recent result for the same parameters when one
     * is available.
     *
     * @param address    target IP address or hostname
     * @param packets    echo requests to send
     * @param intervalMs delay between requests
     * @param sizeBytes  ICMP payload size
     * @param timeout    ceiling on the whole operation
     */
    public PingResult ping(String address, int packets, int intervalMs, int sizeBytes, Duration timeout) {
        String cacheKey = address + '|' + packets + '|' + intervalMs + '|' + sizeBytes;
        long now = System.nanoTime();

        CachedResult cached = cache.get(cacheKey);
        if (cached != null && now - cached.storedAt < cacheTtl.toNanos()) {
            return cached.result;
        }

        PingResult result = execute(address, packets, intervalMs, sizeBytes, timeout);
        cache.put(cacheKey, new CachedResult(result, now));
        evictExpired(now);
        return result;
    }

    private PingResult execute(String address, int packets, int intervalMs, int sizeBytes, Duration timeout) {
        if (pingCommand == null) {
            return fallbackReachability(address, timeout);
        }

        double intervalSeconds = Math.max(MIN_INTERVAL_SECONDS, intervalMs / 1000.0);
        // Bound the whole run: the packets themselves, plus one timeout's grace
        // for the last reply to arrive.
        long deadlineSeconds = Math.max(1,
                (long) Math.ceil(packets * intervalSeconds) + Math.max(1, timeout.toSeconds()));

        List<String> command = new ArrayList<>(List.of(
                pingCommand,
                "-n",                                   // numeric output, no reverse DNS
                "-q",                                   // summary only
                "-c", Integer.toString(packets),
                "-i", formatSeconds(intervalSeconds),
                "-W", Long.toString(Math.max(1, timeout.toSeconds())),
                "-w", Long.toString(deadlineSeconds),
                "-s", Integer.toString(sizeBytes)));
        command.add(address);

        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().reduce("", (a, b) -> a + '\n' + b);
            }

            // Give the process the deadline it was told to honour, plus a
            // small margin, then take it out. A ping that ignores -w would
            // otherwise hold a worker thread indefinitely.
            if (!process.waitFor(deadlineSeconds + 2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return PingResult.unreachable("ping did not terminate within " + deadlineSeconds + "s");
            }

            return parse(output, packets);
        } catch (IOException e) {
            log.debug("ping of {} failed to start: {}", address, e.getMessage());
            return fallbackReachability(address, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PingResult.unreachable("interrupted while pinging " + address);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Extracts loss and round-trip statistics from ping's summary output.
     *
     * <p>Package-visible so the several output dialects in the wild -- GNU
     * iputils, BSD, busybox -- can be covered directly by tests rather than by
     * hoping the host happens to run one of them.
     */
    static PingResult parse(String output, int packets) {
        Matcher lossMatcher = LOSS_PATTERN.matcher(output);
        if (!lossMatcher.find()) {
            // No statistics line at all: unresolvable name, no route, or the
            // binary refused to run. Treat as fully lost rather than guessing.
            return PingResult.unreachable(firstLine(output));
        }

        double lossPercent = Double.parseDouble(lossMatcher.group(1));
        int received = (int) Math.round(packets * (100.0 - lossPercent) / 100.0);

        Matcher rttMatcher = RTT_PATTERN.matcher(output);
        if (!rttMatcher.find()) {
            // 100% loss produces no rtt line, which is the normal case here.
            return new PingResult(received > 0, packets, received, lossPercent, 0, 0, 0, null);
        }

        // ping reports milliseconds; seconds is the unit used throughout the
        // platform so graphs and thresholds do not need per-item conversion.
        double min = Double.parseDouble(rttMatcher.group(1)) / 1000.0;
        double avg = Double.parseDouble(rttMatcher.group(2)) / 1000.0;
        double max = Double.parseDouble(rttMatcher.group(3)) / 1000.0;

        return new PingResult(received > 0, packets, received, lossPercent, min, avg, max, null);
    }

    /**
     * Used only when no {@code ping} binary exists. This cannot measure loss or
     * latency, so it reports a binary reachability verdict and says so.
     */
    private PingResult fallbackReachability(String address, Duration timeout) {
        try {
            boolean reachable = InetAddress.getByName(address)
                    .isReachable((int) Math.max(1, timeout.toMillis()));
            return new PingResult(reachable, 1, reachable ? 1 : 0, reachable ? 0 : 100,
                    0, 0, 0, reachable ? null : "host did not respond");
        } catch (IOException e) {
            return PingResult.unreachable(e.getMessage());
        }
    }

    private static String locatePing() {
        for (String candidate : List.of("/bin/ping", "/usr/bin/ping", "/sbin/ping", "/usr/sbin/ping")) {
            if (new java.io.File(candidate).canExecute()) {
                return candidate;
            }
        }
        log.warn("No ping binary found; ICMP checks will fall back to TCP reachability "
                + "and will not report loss or latency");
        return null;
    }

    private static String formatSeconds(double seconds) {
        return String.format(java.util.Locale.ROOT, "%.1f", seconds);
    }

    private static String firstLine(String output) {
        String trimmed = output == null ? "" : output.trim();
        if (trimmed.isEmpty()) {
            return "no response";
        }
        int newline = trimmed.indexOf('\n');
        return newline < 0 ? trimmed : trimmed.substring(0, newline);
    }

    private void evictExpired(long now) {
        // Cheap opportunistic sweep: without it the map grows with every host
        // that is deleted or renamed over the process lifetime.
        if (cache.size() > 10_000) {
            cache.entrySet().removeIf(e -> now - e.getValue().storedAt > cacheTtl.toNanos());
        }
    }

    private record CachedResult(PingResult result, long storedAt) {
    }
}
