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

    /**
     * "Packets: Sent = 4, Received = 4, Lost = 0 (0% loss),"
     *
     * <p>Matched on the number before the percent sign rather than on the word
     * after it, because Windows translates its output: the same line reads
     * "(0% Verlust)" on a German install and "(0% perte)" on a French one. The
     * digits and the punctuation around them are what stay put.
     */
    private static final Pattern WINDOWS_LOSS_PATTERN =
            Pattern.compile("\\((\\d+(?:\\.\\d+)?)\\s*%");

    /**
     * "Minimum = 10ms, Maximum = 12ms, Average = 11ms"
     *
     * <p>Localised in the same way, so the labels are not matched either -- the
     * three figures are taken in the order Windows prints them, which is
     * minimum, maximum, average. Note that this is <em>not</em> the min/avg/max
     * order used by iputils; reading it as though it were silently swaps a
     * host's average and worst latency.
     */
    private static final Pattern WINDOWS_RTT_PATTERN =
            Pattern.compile("=\\s*(\\d+)\\s*ms");

    /** Sub-200ms intervals require root on Linux; clamp so we never get EPERM. */
    private static final double MIN_INTERVAL_SECONDS = 0.2;

    private final ConcurrentHashMap<String, CachedResult> cache = new ConcurrentHashMap<>();
    private final Duration cacheTtl;
    private final String pingCommand;
    private final boolean windowsDialect;

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
        this(cacheTtl, pingCommand, isWindows());
    }

    /**
     * @param windowsDialect whether to invoke {@code ping} the way Windows
     *                       expects. Separate from the OS check so the argument
     *                       construction for either platform can be tested from
     *                       either platform.
     */
    IcmpPinger(Duration cacheTtl, String pingCommand, boolean windowsDialect) {
        this.cacheTtl = cacheTtl;
        this.pingCommand = pingCommand;
        this.windowsDialect = windowsDialect;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
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

        List<String> command = buildCommand(address, packets, intervalSeconds, sizeBytes,
                timeout, deadlineSeconds);

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
     * Builds the command line for the platform's {@code ping}.
     *
     * <p>The two dialects share no flags worth sharing, and one overlap is
     * actively dangerous: {@code -n} means "numeric output" to iputils and
     * "number of echo requests" to Windows. Passing Linux arguments to
     * {@code PING.EXE} does not fail loudly -- it pings the wrong number of
     * times, or treats a flag as the target. So the two are built separately
     * rather than by patching one into the other.
     */
    List<String> buildCommand(String address, int packets, double intervalSeconds,
                              int sizeBytes, Duration timeout, long deadlineSeconds) {
        List<String> command = new ArrayList<>();
        command.add(pingCommand);

        if (windowsDialect) {
            // Windows has no interval control and no quiet mode, so the
            // per-reply timeout is the only bound available. It is milliseconds
            // here, where iputils takes seconds.
            command.addAll(List.of(
                    "-n", Integer.toString(packets),
                    "-w", Long.toString(Math.max(1, timeout.toMillis())),
                    "-l", Integer.toString(sizeBytes)));
        } else {
            command.addAll(List.of(
                    "-n",                               // numeric output, no reverse DNS
                    "-q",                               // summary only
                    "-c", Integer.toString(packets),
                    "-i", formatSeconds(intervalSeconds),
                    "-W", Long.toString(Math.max(1, timeout.toSeconds())),
                    "-w", Long.toString(deadlineSeconds),
                    "-s", Integer.toString(sizeBytes)));
        }

        command.add(address);
        return command;
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
            // Not an iputils/BSD summary. Windows prints a different one, so
            // try that before concluding the ping failed -- the dialect is
            // detected from the output rather than from the host OS, which
            // keeps a proxy that shells out to either one working and lets
            // both be tested anywhere.
            PingResult windows = parseWindows(output, packets);
            if (windows != null) {
                return windows;
            }
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
     * Reads the summary {@code PING.EXE} prints.
     *
     * @return the result, or {@code null} if this is not Windows ping output
     */
    private static PingResult parseWindows(String output, int packets) {
        Matcher lossMatcher = WINDOWS_LOSS_PATTERN.matcher(output);
        if (!lossMatcher.find()) {
            return null;
        }

        double lossPercent = Double.parseDouble(lossMatcher.group(1));
        int received = (int) Math.round(packets * (100.0 - lossPercent) / 100.0);

        // Windows omits the timings block entirely when nothing came back, and
        // prints exactly three figures when something did.
        //
        // Searched only after the packet-count line, because each individual
        // reply above it also ends in "time=11ms" -- matching from the start
        // picks up the first three replies instead of the summary, which is
        // close enough to look right and wrong whenever a reply is slow.
        List<Double> timings = new ArrayList<>(3);
        Matcher rttMatcher = WINDOWS_RTT_PATTERN.matcher(output)
                .region(lossMatcher.end(), output.length());
        while (rttMatcher.find() && timings.size() < 3) {
            timings.add(Double.parseDouble(rttMatcher.group(1)) / 1000.0);
        }
        if (timings.size() < 3) {
            return new PingResult(received > 0, packets, received, lossPercent, 0, 0, 0, null);
        }

        // Printed as minimum, maximum, average -- reordered here to the
        // min/avg/max the rest of the platform uses.
        return new PingResult(received > 0, packets, received, lossPercent,
                timings.get(0), timings.get(2), timings.get(1), null);
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
            return PingResult.unreachable(com.nms.collector.Failures.describe(e));
        }
    }

    private static String locatePing() {
        for (String candidate : pingCandidates()) {
            if (new java.io.File(candidate).canExecute()) {
                return candidate;
            }
        }
        // Warned about loudly because the fallback looks like it works. It
        // reports every firewalled-but-alive host as down, which on a camera
        // estate means a screen of red with nothing actually wrong -- and the
        // only sign of the real cause is this line at startup.
        log.warn("No ping binary found; ICMP checks will fall back to TCP reachability. "
                + "Loss and latency will always read zero, and hosts that block TCP "
                + "will be reported unreachable even while responding to ICMP.");
        return null;
    }

    private static List<String> pingCandidates() {
        if (isWindows()) {
            // Located through SystemRoot rather than assumed to be on C:, and
            // by absolute path rather than by name, so a stray ping.exe earlier
            // on PATH cannot be picked up instead.
            String systemRoot = System.getenv("SystemRoot");
            String root = systemRoot == null || systemRoot.isBlank() ? "C:\\Windows" : systemRoot;
            return List.of(root + "\\System32\\PING.EXE", root + "\\Sysnative\\PING.EXE");
        }
        return List.of("/bin/ping", "/usr/bin/ping", "/sbin/ping", "/usr/sbin/ping");
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
