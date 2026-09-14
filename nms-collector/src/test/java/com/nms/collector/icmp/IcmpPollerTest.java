package com.nms.collector.icmp;

import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import com.nms.common.ItemValueType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the full ICMP path -- process execution, output parsing and the
 * mapping from a ping run onto each item key -- against a stub ping binary.
 *
 * <p>A stub is used rather than a live target because the answer must be the
 * same on a developer laptop, in CI and inside a stripped container: real
 * addresses behave differently depending on the network, and reserved ranges
 * are not reliably unroutable behind a NAT or a sandbox proxy.
 */
class IcmpPollerTest {

    private static IcmpPoller poller;

    @TempDir
    static Path tempDir;

    /**
     * A stub ping that answers for 10.0.0.1 and reports total loss for
     * anything else, mimicking GNU iputils output and its exit codes.
     */
    @BeforeAll
    static void createStubPing() throws IOException {
        Path script = tempDir.resolve("ping");
        Files.writeString(script, """
                #!/bin/sh
                # Target is the final argument.
                for target; do :; done
                if [ "$target" = "10.0.0.1" ]; then
                  echo "PING $target ($target) 56(84) bytes of data."
                  echo ""
                  echo "--- $target ping statistics ---"
                  echo "3 packets transmitted, 3 received, 0% packet loss, time 2003ms"
                  echo "rtt min/avg/max/mdev = 4.000/5.000/6.000/0.500 ms"
                  exit 0
                fi
                echo "PING $target ($target) 56(84) bytes of data."
                echo ""
                echo "--- $target ping statistics ---"
                echo "3 packets transmitted, 0 received, 100% packet loss, time 2049ms"
                exit 1
                """, StandardCharsets.UTF_8);

        Files.setPosixFilePermissions(script, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));

        // A zero TTL keeps each test independent; the caching behaviour has
        // its own test below.
        poller = new IcmpPoller(new IcmpPinger(Duration.ZERO, script.toString()));
    }

    private static CheckRequest request(String key, String address, ItemValueType valueType) {
        return new CheckRequest(1L, 1L, "test-host", CheckType.ICMP_PING, valueType,
                key, address, 0, Duration.ofSeconds(2),
                Map.of("packets", "3", "interval", "200", "size", "56"));
    }

    @Test
    void reportsReachableHostAsOne() {
        CheckResult result = poller.poll(request("icmpping", "10.0.0.1", ItemValueType.UNSIGNED));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(1L);
    }

    @Test
    void reportsUnreachableHostAsZeroRatherThanAsAnError() {
        CheckResult result = poller.poll(request("icmpping", "10.9.9.9", ItemValueType.UNSIGNED));

        // The check succeeded; its measured answer is "not reachable". An item
        // in the error state stops feeding its triggers, so marking this a
        // failure would mean a host going down silently stops alerting -- the
        // exact opposite of what the item is for.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(0L);
    }

    @Test
    void reportsPacketLossPercentage() {
        CheckResult healthy = poller.poll(request("icmppingloss", "10.0.0.1", ItemValueType.FLOAT));
        CheckResult down = poller.poll(request("icmppingloss", "10.9.9.9", ItemValueType.FLOAT));

        assertThat((Double) healthy.value()).isZero();
        assertThat((Double) down.value()).isEqualTo(100.0);
    }

    @Test
    void convertsLatencyFromMillisecondsToSeconds() {
        CheckResult result = poller.poll(request("icmppingsec", "10.0.0.1", ItemValueType.FLOAT));

        assertThat(result.isSuccess()).isTrue();
        assertThat((Double) result.value()).isEqualTo(0.005);
    }

    @Test
    void latencyModeSelectsMinAndMax() {
        CheckRequest min = new CheckRequest(1L, 1L, "h", CheckType.ICMP_PING, ItemValueType.FLOAT,
                "icmppingsec", "10.0.0.1", 0, Duration.ofSeconds(2), Map.of("mode", "min"));
        CheckRequest max = new CheckRequest(1L, 1L, "h", CheckType.ICMP_PING, ItemValueType.FLOAT,
                "icmppingsec", "10.0.0.1", 0, Duration.ofSeconds(2), Map.of("mode", "max"));

        assertThat((Double) poller.poll(min).value()).isEqualTo(0.004);
        assertThat((Double) poller.poll(max).value()).isEqualTo(0.006);
    }

    @Test
    void withholdsLatencySampleWhenNothingReplied() {
        CheckResult result = poller.poll(request("icmppingsec", "10.9.9.9", ItemValueType.FLOAT));

        // Storing 0 would make a dead host the fastest on the network and drag
        // every latency average and percentile down with it.
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("no ICMP reply");
    }

    @Test
    void rejectsUnknownIcmpKey() {
        CheckResult result = poller.poll(request("icmppingjitter", "10.0.0.1", ItemValueType.FLOAT));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("Unsupported ICMP item key");
    }

    @Test
    void stripsBracketParametersFromTheKey() {
        CheckResult result = poller.poll(request("icmpping[,,,,200]", "10.0.0.1", ItemValueType.UNSIGNED));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(1L);
    }

    @Test
    void sharesOnePingRunAcrossTheItemsDerivedFromIt() throws IOException {
        // A host template has three ICMP items. Without the cache each poll
        // cycle would spawn three processes and put three times the packets on
        // the network for the same information.
        Path counter = tempDir.resolve("invocations");
        Path script = tempDir.resolve("counting-ping");
        Files.writeString(script, """
                #!/bin/sh
                echo x >> "%s"
                echo "--- t ping statistics ---"
                echo "3 packets transmitted, 3 received, 0%% packet loss, time 2003ms"
                echo "rtt min/avg/max/mdev = 4.000/5.000/6.000/0.500 ms"
                """.formatted(counter), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));

        IcmpPoller cachingPoller =
                new IcmpPoller(new IcmpPinger(Duration.ofSeconds(30), script.toString()));

        cachingPoller.poll(request("icmpping", "10.0.0.1", ItemValueType.UNSIGNED));
        cachingPoller.poll(request("icmppingloss", "10.0.0.1", ItemValueType.FLOAT));
        cachingPoller.poll(request("icmppingsec", "10.0.0.1", ItemValueType.FLOAT));

        assertThat(Files.readAllLines(counter)).hasSize(1);
    }

    @Test
    void doesNotShareResultsBetweenDifferentHosts() throws IOException {
        Path counter = tempDir.resolve("per-host-invocations");
        Path script = tempDir.resolve("per-host-ping");
        Files.writeString(script, """
                #!/bin/sh
                echo x >> "%s"
                echo "--- t ping statistics ---"
                echo "3 packets transmitted, 3 received, 0%% packet loss, time 2003ms"
                echo "rtt min/avg/max/mdev = 4.000/5.000/6.000/0.500 ms"
                """.formatted(counter), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));

        IcmpPoller cachingPoller =
                new IcmpPoller(new IcmpPinger(Duration.ofSeconds(30), script.toString()));

        cachingPoller.poll(request("icmpping", "10.0.0.1", ItemValueType.UNSIGNED));
        cachingPoller.poll(request("icmpping", "10.0.0.2", ItemValueType.UNSIGNED));

        assertThat(Files.readAllLines(counter)).hasSize(2);
    }

    @Test
    void fallsBackToReachabilityProbeWhenNoPingBinaryExists() {
        IcmpPoller fallbackPoller = new IcmpPoller(new IcmpPinger(Duration.ZERO, null));

        CheckResult result = fallbackPoller.poll(
                request("icmpping", "127.0.0.1", ItemValueType.UNSIGNED));

        // Degraded but functional: loopback is reachable by any mechanism.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(1L);
    }
}
