package com.nms.collector.net;

import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import com.nms.common.ItemValueType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TcpPortPollerTest {

    private final TcpPortPoller poller = new TcpPortPoller();

    private static CheckRequest request(String key, int port, ItemValueType valueType) {
        return new CheckRequest(1L, 1L, "test-host", CheckType.TCP_PORT, valueType,
                key, "127.0.0.1", port, Duration.ofSeconds(2), Map.of());
    }

    @Test
    void reportsOpenPortAsAvailable() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            CheckResult result = poller.poll(
                    request("net.tcp.service[custom]", server.getLocalPort(), ItemValueType.UNSIGNED));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.value()).isEqualTo(1L);
        }
    }

    @Test
    void reportsClosedPortAsUnavailableRatherThanAsAnError() throws IOException {
        int closedPort;
        try (ServerSocket server = new ServerSocket(0)) {
            closedPort = server.getLocalPort();
        }
        // The socket is now closed, so nothing is listening on that port.

        CheckResult result = poller.poll(
                request("net.tcp.service[custom]", closedPort, ItemValueType.UNSIGNED));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(0L);
    }

    @Test
    void perfKeyReturnsConnectTimeInSeconds() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            CheckResult result = poller.poll(
                    request("net.tcp.service.perf[custom]", server.getLocalPort(), ItemValueType.FLOAT));

            assertThat(result.isSuccess()).isTrue();
            assertThat((Double) result.value()).isBetween(0.0, 2.0);
        }
    }

    @Test
    void perfKeyReturnsZeroWhenTheServiceIsDown() throws IOException {
        int closedPort;
        try (ServerSocket server = new ServerSocket(0)) {
            closedPort = server.getLocalPort();
        }

        CheckResult result = poller.poll(
                request("net.tcp.service.perf[custom]", closedPort, ItemValueType.FLOAT));

        assertThat(result.isSuccess()).isTrue();
        assertThat((Double) result.value()).isZero();
    }

    @Test
    void derivesWellKnownPortFromTheServiceNameInTheKey() {
        CheckRequest request = new CheckRequest(1L, 1L, "camera", CheckType.TCP_PORT,
                ItemValueType.UNSIGNED, "net.tcp.service[rtsp]", "127.0.0.1", 0,
                Duration.ofSeconds(1), Map.of());

        assertThat(TcpPortPoller.resolvePort(request)).isEqualTo(554);
    }

    @Test
    void explicitPortParameterWinsOverTheInterfacePort() {
        CheckRequest request = new CheckRequest(1L, 1L, "camera", CheckType.TCP_PORT,
                ItemValueType.UNSIGNED, "net.tcp.service[http]", "127.0.0.1", 80,
                Duration.ofSeconds(1), Map.of("port", "8080"));

        assertThat(TcpPortPoller.resolvePort(request)).isEqualTo(8080);
    }

    @Test
    void rejectsAnItemWithNoResolvablePort() {
        CheckResult result = poller.poll(
                request("net.tcp.service[somethingunknown]", 0, ItemValueType.UNSIGNED));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("Invalid or missing TCP port");
    }
}
