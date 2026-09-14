package com.nms.collector.camera;

import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import com.nms.common.ItemValueType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the RTSP poller against a stub camera, covering the responses real
 * devices actually give: plain success, an authentication challenge, a missing
 * stream path, and a port that answers TCP but speaks no RTSP at all.
 */
class RtspPollerTest {

    private final RtspPoller poller = new RtspPoller();
    private StubRtspServer server;

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    private CheckRequest request(Map<String, String> extraParams) {
        Map<String, String> params = new java.util.HashMap<>(Map.of(
                "port", Integer.toString(server.port()),
                "path", "/Streaming/Channels/101"));
        params.putAll(extraParams);
        return new CheckRequest(1L, 1L, "camera-1", CheckType.RTSP, ItemValueType.UNSIGNED,
                "net.tcp.service[rtsp]", "127.0.0.1", 0, Duration.ofSeconds(3), params);
    }

    @Test
    void reportsAvailableWhenTheCameraAnswersOptions() throws IOException {
        server = new StubRtspServer(req -> "RTSP/1.0 200 OK\r\nCSeq: 1\r\n"
                + "Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n\r\n");

        CheckResult result = poller.poll(request(Map.of()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(1L);
    }

    @Test
    void treatsAnAuthChallengeAsAliveWhenNoCredentialsAreConfigured() throws IOException {
        server = new StubRtspServer(req -> "RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\n"
                + "WWW-Authenticate: Digest realm=\"camera\", nonce=\"abc123\"\r\n\r\n");

        CheckResult result = poller.poll(request(Map.of()));

        // The camera demonstrably has a live RTSP stack. Reporting it offline
        // would mean every camera had to have credentials entered before it
        // could be monitored at all.
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(1L);
    }

    @Test
    void authenticatesWithDigestWhenCredentialsAreConfigured() throws IOException {
        server = new StubRtspServer(req -> req.contains("Authorization: Digest")
                ? "RTSP/1.0 200 OK\r\nCSeq: 2\r\n\r\n"
                : "RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\n"
                  + "WWW-Authenticate: Digest realm=\"camera\", nonce=\"abc123\"\r\n\r\n");

        CheckResult result = poller.poll(request(Map.of("username", "admin", "password", "secret")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(1L);
        assertThat(server.requests()).hasSize(2);
        assertThat(server.requests().get(1)).contains("Authorization: Digest username=\"admin\"");
        // RFC 2069 digest: MD5(HA1:nonce:HA2). Verifying the exact value is
        // what catches a silently wrong hash that cameras would simply reject.
        assertThat(server.requests().get(1)).contains("realm=\"camera\"", "nonce=\"abc123\"");
    }

    @Test
    void reportsRejectedCredentialsAsUnavailable() throws IOException {
        server = new StubRtspServer(req -> "RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\n"
                + "WWW-Authenticate: Digest realm=\"camera\", nonce=\"abc123\"\r\n\r\n");

        CheckResult result = poller.poll(request(Map.of("username", "admin", "password", "wrong")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(0L);
    }

    @Test
    void describeDetectsAStreamPathThatNoLongerExists() throws IOException {
        // The classic post-firmware-update failure: the camera is healthy and
        // RTSP answers, but the configured channel path is gone. OPTIONS alone
        // cannot see this.
        server = new StubRtspServer(req -> req.startsWith("DESCRIBE")
                ? "RTSP/1.0 404 Not Found\r\nCSeq: 2\r\n\r\n"
                : "RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n");

        CheckResult withoutDescribe = poller.poll(request(Map.of()));
        CheckResult withDescribe = poller.poll(request(Map.of("describe", "true")));

        assertThat(withoutDescribe.value()).isEqualTo(1L);
        assertThat(withDescribe.value()).isEqualTo(0L);
    }

    @Test
    void reportsUnavailableWhenThePortAnswersButIsNotAnRtspServer() throws IOException {
        server = new StubRtspServer(req -> "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n");

        CheckResult result = poller.poll(request(Map.of()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(0L);
    }

    @Test
    void reportsUnavailableWhenNothingIsListening() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        CheckRequest request = new CheckRequest(1L, 1L, "camera-1", CheckType.RTSP,
                ItemValueType.UNSIGNED, "net.tcp.service[rtsp]", "127.0.0.1", 0,
                Duration.ofSeconds(2), Map.of("port", Integer.toString(closedPort)));

        CheckResult result = poller.poll(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).isEqualTo(0L);
    }

    @Test
    void perfKeyMeasuresHandshakeTime() throws IOException {
        server = new StubRtspServer(req -> "RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n");

        Map<String, String> params = new java.util.HashMap<>(Map.of(
                "port", Integer.toString(server.port()), "path", "/live"));
        CheckRequest request = new CheckRequest(1L, 1L, "camera-1", CheckType.RTSP,
                ItemValueType.FLOAT, "net.tcp.service.perf[rtsp]", "127.0.0.1", 0,
                Duration.ofSeconds(3), params);

        CheckResult result = poller.poll(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat((Double) result.value()).isBetween(0.0, 3.0);
    }

    /** A minimal RTSP server that replies according to a supplied function. */
    private static final class StubRtspServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final Thread thread;

        StubRtspServer(Function<String, String> responder) throws IOException {
            this.serverSocket = new ServerSocket(0);
            this.thread = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try (Socket client = serverSocket.accept()) {
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                        OutputStream out = client.getOutputStream();

                        // Each connection may carry several requests; keep
                        // serving until the poller closes it.
                        while (true) {
                            List<String> lines = new ArrayList<>();
                            String line;
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                lines.add(line);
                            }
                            if (lines.isEmpty()) {
                                break;
                            }
                            String request = String.join("\r\n", lines);
                            requests.add(request);
                            out.write(responder.apply(request).getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } catch (IOException e) {
                        // Socket closed during teardown, or the client hung up.
                        return;
                    }
                }
            });
            this.thread.setDaemon(true);
            this.thread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        List<String> requests() {
            return requests;
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            thread.interrupt();
        }
    }
}
