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
}
