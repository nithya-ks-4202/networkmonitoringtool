package com.nms.collector.icmp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the ping output dialects the collector has to read.
 *
 * <p>These run against captured output rather than a live network, so they
 * assert the parser's behaviour rather than the behaviour of whichever ping
 * implementation the build machine happens to have -- or, as in a minimal
 * container, of no ping at all.
 */
class IcmpPingerParseTest {

    /**
     * Dividing a parsed millisecond value by 1000 is not exact in binary
     * floating point, so latency is compared within a nanosecond rather than
     * bit-for-bit.
     */
    private static final org.assertj.core.data.Offset<Double> TOLERANCE =
            org.assertj.core.data.Offset.offset(1e-9);

    @Test
    void parsesGnuIputilsSuccess() {
        String output = """
                PING 10.0.0.5 (10.0.0.5) 56(84) bytes of data.

                --- 10.0.0.5 ping statistics ---
                3 packets transmitted, 3 received, 0% packet loss, time 2003ms
                rtt min/avg/max/mdev = 10.190/10.339/10.470/0.114 ms
                """;

        PingResult result = IcmpPinger.parse(output, 3);

        assertThat(result.reachable()).isTrue();
        assertThat(result.received()).isEqualTo(3);
        assertThat(result.lossPercent()).isZero();
        // Milliseconds on the wire, seconds everywhere in the platform.
        assertThat(result.minSeconds()).isCloseTo(0.010190, TOLERANCE);
        assertThat(result.avgSeconds()).isCloseTo(0.010339, TOLERANCE);
        assertThat(result.maxSeconds()).isCloseTo(0.010470, TOLERANCE);
    }

    @Test
    void parsesTotalLossAndReportsUnreachable() {
        String output = """
                PING 10.0.0.9 (10.0.0.9) 56(84) bytes of data.

                --- 10.0.0.9 ping statistics ---
                3 packets transmitted, 0 received, 100% packet loss, time 2049ms
                """;

        PingResult result = IcmpPinger.parse(output, 3);

        assertThat(result.reachable()).isFalse();
        assertThat(result.received()).isZero();
        assertThat(result.lossPercent()).isEqualTo(100.0);
        // No rtt line is emitted when nothing came back.
        assertThat(result.avgSeconds()).isZero();
    }

    @Test
    void partialLossStillCountsAsReachable() {
        String output = """
                --- camera-12 ping statistics ---
                4 packets transmitted, 3 received, 25% packet loss, time 3004ms
                rtt min/avg/max/mdev = 1.234/2.345/3.456/0.500 ms
                """;

        PingResult result = IcmpPinger.parse(output, 4);

        // A camera dropping a quarter of its packets is degraded, not down.
        // Treating it as down here would hide the distinction the loss item
        // exists to expose.
        assertThat(result.reachable()).isTrue();
        assertThat(result.received()).isEqualTo(3);
        assertThat(result.lossPercent()).isEqualTo(25.0);
        assertThat(result.avgSeconds()).isCloseTo(0.002345, TOLERANCE);
    }

    @Test
    void parsesBsdRoundTripWording() {
        // macOS and the BSDs say "round-trip" rather than "rtt".
        String output = """
                --- 10.0.0.5 ping statistics ---
                3 packets transmitted, 3 packets received, 0.0% packet loss
                round-trip min/avg/max/stddev = 0.123/0.456/0.789/0.100 ms
                """;

        PingResult result = IcmpPinger.parse(output, 3);

        assertThat(result.reachable()).isTrue();
        assertThat(result.lossPercent()).isZero();
        assertThat(result.avgSeconds()).isCloseTo(0.000456, TOLERANCE);
    }

    @Test
    void parsesFractionalLossPercentage() {
        String output = """
                --- 10.0.0.5 ping statistics ---
                1000 packets transmitted, 999 received, 0.1% packet loss, time 1002ms
                rtt min/avg/max/mdev = 1.000/2.000/3.000/0.100 ms
                """;

        PingResult result = IcmpPinger.parse(output, 1000);

        assertThat(result.lossPercent()).isEqualTo(0.1);
        assertThat(result.reachable()).isTrue();
    }

    @Test
    void treatsOutputWithNoStatisticsLineAsUnreachable() {
        // Unresolvable name, no route, or a ping that refused to start. There
        // is no measurement here, so guessing "up" would be the worst option.
        String output = "ping: connect: Network is unreachable";

        PingResult result = IcmpPinger.parse(output, 3);

        assertThat(result.reachable()).isFalse();
        assertThat(result.lossPercent()).isEqualTo(100.0);
        assertThat(result.error()).contains("Network is unreachable");
    }

    @Test
    void treatsEmptyOutputAsUnreachable() {
        PingResult result = IcmpPinger.parse("", 3);

        assertThat(result.reachable()).isFalse();
        assertThat(result.error()).isEqualTo("no response");
    }

    @Test
    void parsesWindowsSuccess() {
        String output = """
                Pinging 10.0.0.5 with 32 bytes of data:
                Reply from 10.0.0.5: bytes=32 time=11ms TTL=128
                Reply from 10.0.0.5: bytes=32 time=12ms TTL=128
                Reply from 10.0.0.5: bytes=32 time=10ms TTL=128

                Ping statistics for 10.0.0.5:
                    Packets: Sent = 3, Received = 3, Lost = 0 (0% loss),
                Approximate round trip times in milli-seconds:
                    Minimum = 10ms, Maximum = 12ms, Average = 11ms
                """;

        PingResult result = IcmpPinger.parse(output, 3);

        assertThat(result.reachable()).isTrue();
        assertThat(result.received()).isEqualTo(3);
        assertThat(result.lossPercent()).isZero();
        assertThat(result.minSeconds()).isCloseTo(0.010, TOLERANCE);
        assertThat(result.maxSeconds()).isCloseTo(0.012, TOLERANCE);
        assertThat(result.avgSeconds()).isCloseTo(0.011, TOLERANCE);
    }

    /**
     * Windows prints minimum, maximum, average -- iputils prints min, avg, max.
     * Reading one as the other swaps a host's typical latency with its worst,
     * which would quietly mis-fire every latency trigger rather than fail.
     */
    @Test
    void doesNotConfuseWindowsFieldOrderWithIputils() {
        String output = """
                Ping statistics for 10.0.0.5:
                    Packets: Sent = 4, Received = 4, Lost = 0 (0% loss),
                Approximate round trip times in milli-seconds:
                    Minimum = 1ms, Maximum = 300ms, Average = 20ms
                """;

        PingResult result = IcmpPinger.parse(output, 4);

        assertThat(result.avgSeconds()).isCloseTo(0.020, TOLERANCE);
        assertThat(result.maxSeconds()).isCloseTo(0.300, TOLERANCE);
        assertThat(result.avgSeconds()).isLessThan(result.maxSeconds());
    }

    @Test
    void parsesWindowsPartialLoss() {
        String output = """
                Ping statistics for 10.0.0.5:
                    Packets: Sent = 4, Received = 3, Lost = 1 (25% loss),
                Approximate round trip times in milli-seconds:
                    Minimum = 10ms, Maximum = 14ms, Average = 12ms
                """;

        PingResult result = IcmpPinger.parse(output, 4);

        assertThat(result.reachable()).isTrue();
        assertThat(result.lossPercent()).isEqualTo(25.0);
        assertThat(result.received()).isEqualTo(3);
    }

    /**
     * Total loss on Windows omits the timings block altogether, rather than
     * printing zeroes.
     */
    @Test
    void parsesWindowsTotalLoss() {
        String output = """
                Pinging 10.0.0.9 with 32 bytes of data:
                Request timed out.
                Request timed out.

                Ping statistics for 10.0.0.9:
                    Packets: Sent = 2, Received = 0, Lost = 2 (100% loss),
                """;

        PingResult result = IcmpPinger.parse(output, 2);

        assertThat(result.reachable()).isFalse();
        assertThat(result.received()).isZero();
        assertThat(result.lossPercent()).isEqualTo(100.0);
        assertThat(result.avgSeconds()).isZero();
    }

    /**
     * Windows translates this output. The figures and punctuation are what the
     * parser matches on, so a German-language server reports real numbers
     * rather than falling back to "unreachable" on every host.
     */
    @Test
    void parsesLocalisedWindowsOutput() {
        String output = """
                Ping-Statistik für 10.0.0.5:
                    Pakete: Gesendet = 4, Empfangen = 4, Verloren = 0 (0% Verlust),
                Ca. Zeitangaben in Millisek.:
                    Minimum = 10ms, Maximum = 12ms, Mittelwert = 11ms
                """;

        PingResult result = IcmpPinger.parse(output, 4);

        assertThat(result.reachable()).isTrue();
        assertThat(result.lossPercent()).isZero();
        assertThat(result.avgSeconds()).isCloseTo(0.011, TOLERANCE);
    }

    @Test
    void buildsWindowsArgumentsRatherThanIputilsOnes() {
        // -n means "numeric output" to iputils and "how many echo requests" to
        // Windows. Sending the iputils form to PING.EXE does not fail; it
        // pings once and reports a host as up on a single packet.
        IcmpPinger windows = new IcmpPinger(
                java.time.Duration.ofSeconds(10), "C:\\Windows\\System32\\PING.EXE", true);

        java.util.List<String> command = windows.buildCommand(
                "10.0.0.5", 3, 0.2, 56, java.time.Duration.ofSeconds(2), 5);

        assertThat(command).containsSequence("-n", "3");
        // Milliseconds on Windows, seconds on iputils.
        assertThat(command).containsSequence("-w", "2000");
        assertThat(command).containsSequence("-l", "56");
        assertThat(command).doesNotContain("-c", "-q", "-i", "-W", "-s");
        assertThat(command.get(command.size() - 1)).isEqualTo("10.0.0.5");
    }

    @Test
    void buildsIputilsArgumentsOnLinux() {
        IcmpPinger linux = new IcmpPinger(
                java.time.Duration.ofSeconds(10), "/bin/ping", false);

        java.util.List<String> command = linux.buildCommand(
                "10.0.0.5", 3, 0.2, 56, java.time.Duration.ofSeconds(2), 5);

        assertThat(command).containsSequence("-c", "3");
        assertThat(command).containsSequence("-W", "2");
        assertThat(command).containsSequence("-s", "56");
        assertThat(command.get(command.size() - 1)).isEqualTo("10.0.0.5");
    }
}
