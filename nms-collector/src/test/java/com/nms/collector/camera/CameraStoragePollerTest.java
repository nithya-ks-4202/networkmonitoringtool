package com.nms.collector.camera;

import com.nms.collector.camera.CameraStoragePoller.Storage;
import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import com.nms.common.ItemState;
import com.nms.common.ItemValueType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Covers the failure this check exists for: a camera that passes every other
 * test in the template while recording nothing.
 *
 * <p>Parsing is exercised against captured ISAPI response shapes rather than
 * hardware, and the request path against a stub that speaks the same digest
 * challenge Hikvision firmware does.
 */
class CameraStoragePollerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** A healthy 64 GB card with room on it. */
    private static final String HEALTHY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <storage version="2.0" xmlns="http://www.hikvision.com/ver20/XMLSchema">
              <hddList size="1">
                <hdd>
                  <id>1</id>
                  <hddName>hdd1</hddName>
                  <hddPath></hddPath>
                  <hddType>SD</hddType>
                  <status>ok</status>
                  <capacity>60906</capacity>
                  <freeSpace>20480</freeSpace>
                  <property>RW</property>
                </hdd>
              </hddList>
              <nasList size="0"/>
            </storage>
            """;

    @Test
    void readsCapacityAndFreeSpaceFromAHealthyCard() {
        Storage storage = CameraStoragePoller.parse(HEALTHY);

        assertThat(storage.devices()).hasSize(1);
        assertThat(storage.healthy()).isTrue();
        // Hikvision reports mebibytes; the platform stores bytes.
        assertThat(storage.totalBytes()).isEqualTo(60906L * 1024 * 1024);
        assertThat(storage.freeBytes()).isEqualTo(20480L * 1024 * 1024);
    }

    @Test
    @DisplayName("a failed card is not healthy")
    void detectsAFailedCard() {
        Storage storage = CameraStoragePoller.parse(HEALTHY.replace(
                "<status>ok</status>", "<status>error</status>"));

        assertThat(storage.healthy()).isFalse();
        assertThat(storage.describe()).contains("error");
    }

    @Test
    void detectsAnUnformattedCard() {
        Storage storage = CameraStoragePoller.parse(HEALTHY.replace(
                "<status>ok</status>", "<status>unformatted</status>"));

        assertThat(storage.healthy()).isFalse();
    }

    /**
     * "idle" means present and working but not currently being written to,
     * which is the normal state of a camera recording on motion. Treating it
     * as a fault would alert on every correctly configured camera.
     */
    @Test
    void treatsIdleAsHealthy() {
        Storage storage = CameraStoragePoller.parse(HEALTHY.replace(
                "<status>ok</status>", "<status>idle</status>"));

        assertThat(storage.healthy()).isTrue();
    }

    /**
     * The late stage of flash wear: the controller refuses writes, the status
     * still reads ok, and the camera carries on as though it were recording.
     */
    @Test
    @DisplayName("a read-only card is a fault even while reporting ok")
    void detectsAReadOnlyCard() {
        Storage storage = CameraStoragePoller.parse(HEALTHY.replace(
                "<property>RW</property>", "<property>R</property>"));

        assertThat(storage.devices().get(0).status()).isEqualTo("ok");
        assertThat(storage.healthy()).isFalse();
        assertThat(storage.describe()).contains("read-only");
    }

    /**
     * A card that has failed hard or worked loose is not listed at all. An
     * empty list must not read as healthy -- that is precisely the silent
     * failure this check exists to catch.
     */
    @Test
    @DisplayName("no card at all is a fault, not a pass")
    void treatsAnAbsentCardAsAFault() {
        Storage storage = CameraStoragePoller.parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <storage version="2.0">
                  <hddList size="0"/>
                  <nasList size="0"/>
                </storage>
                """);

        assertThat(storage.devices()).isEmpty();
        assertThat(storage.healthy()).isFalse();
        assertThat(storage.totalBytes()).isZero();
        assertThat(storage.describe()).isEqualTo("No storage device present");
    }

    @Test
    void readsSeveralDevices() {
        Storage storage = CameraStoragePoller.parse("""
                <storage>
                  <hddList size="2">
                    <hdd><hddName>hdd1</hddName><hddType>SD</hddType><status>ok</status>
                         <capacity>30000</capacity><freeSpace>10000</freeSpace><property>RW</property></hdd>
                    <hdd><hddName>hdd2</hddName><hddType>SD</hddType><status>error</status>
                         <capacity>30000</capacity><freeSpace>0</freeSpace><property>RW</property></hdd>
                  </hddList>
                </storage>
                """);

        assertThat(storage.devices()).hasSize(2);
        // One bad device makes the camera's storage unhealthy: half a
        // recording estate is not a pass.
        assertThat(storage.healthy()).isFalse();
        assertThat(storage.totalBytes()).isEqualTo(60000L * 1024 * 1024);
    }

    /**
     * Camera firmware emits XML a strict parser rejects: unescaped ampersands
     * in a user-set card name, and a byte-order mark before the declaration.
     * The fields wanted here are flat scalars, so the looser read survives
     * what a DocumentBuilder would refuse outright.
     */
    @Test
    void survivesMalformedFirmwareXml() {
        Storage storage = CameraStoragePoller.parse("﻿" + """
                <?xml version="1.0" encoding="UTF-8"?>
                <storage>
                  <hddList size="1">
                    <hdd>
                      <hddName>SD & card</hddName>
                      <hddType>SD</hddType>
                      <status>ok</status>
                      <capacity>15000</capacity>
                      <freeSpace>7500</freeSpace>
                      <property>RW</property>
                    </hdd>
                  </hddList>
                </storage>
                """);

        assertThat(storage.devices()).hasSize(1);
        assertThat(storage.healthy()).isTrue();
        assertThat(storage.devices().get(0).name()).isEqualTo("SD & card");
        assertThat(storage.freeBytes()).isEqualTo(7500L * 1024 * 1024);
    }

    /**
     * A reply that is not a storage listing at all -- a proxy login page, a
     * truncated body, a firmware answering 200 with something else.
     *
     * <p>It must not parse to "zero devices", because zero devices means "no
     * card" and raises an alarm. A camera with a healthy card must never be
     * reported as having lost it because its answer could not be read.
     */
    @Test
    @DisplayName("an unreadable reply is not mistaken for a missing card")
    void doesNotReadAGarbledReplyAsAMissingCard() throws IOException {
        int port = startStub(exchange ->
                new StubResponse(200, "<html><body>Please sign in</body></html>"));

        CheckResult result = new CameraStoragePoller().poll(
                storageRequest(port, "camera.storage.status"));

        assertThat(result.state()).isEqualTo(ItemState.NOT_SUPPORTED);
        assertThat(result.error()).contains("not a storage listing");

        // And the distinction the guard rests on: an empty list from a camera
        // that genuinely has no card is still a real answer, and still alarms.
        assertThat(CameraStoragePoller.looksLikeStorageResponse(
                "<storage><hddList size=\"0\"/></storage>")).isTrue();
        assertThat(CameraStoragePoller.looksLikeStorageResponse(
                "<html><body>Please sign in</body></html>")).isFalse();
    }

    @Test
    void ignoresAnUnparseableCapacity() {
        Storage storage = CameraStoragePoller.parse(
                HEALTHY.replace("<capacity>60906</capacity>", "<capacity>N/A</capacity>"));

        assertThat(storage.totalBytes()).isZero();
        // The status is still readable, so the card is still judged on it.
        assertThat(storage.devices().get(0).healthy()).isTrue();
    }

    // --- Request path ------------------------------------------------------

    @Test
    @DisplayName("authenticates with digest and reports the card's state")
    void authenticatesAndReports() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        List<String> authorizations = new ArrayList<>();

        int port = startStub(exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            requests.incrementAndGet();
            if (authorization == null) {
                // The challenge Hikvision firmware actually sends, qop and all.
                exchange.getResponseHeaders().add("WWW-Authenticate",
                        "Digest qop=\"auth\", realm=\"IP Camera\", "
                                + "nonce=\"4e4f4e43453a\", stale=\"FALSE\"");
                return new StubResponse(401, "");
            }
            authorizations.add(authorization);
            return new StubResponse(200, HEALTHY);
        });

        CheckResult status = new CameraStoragePoller().poll(
                storageRequest(port, "camera.storage.status"));

        assertThat(status.state()).isEqualTo(ItemState.NORMAL);
        assertThat(status.value()).isEqualTo(1L);

        // Challenged once, then answered: the second request carries the header.
        assertThat(requests.get()).isEqualTo(2);
        assertThat(authorizations).hasSize(1);
        String authorization = authorizations.get(0);
        assertThat(authorization).startsWith("Digest ");
        // qop was offered, so the response must include the client nonce and
        // counter. Without them Hikvision replies 401 forever.
        assertThat(authorization).contains("qop=auth");
        assertThat(authorization).contains("nc=");
        assertThat(authorization).contains("cnonce=");
    }

    @Test
    void reportsUnsupportedFirmwareDistinctlyFromAFailedCard() throws IOException {
        int port = startStub(exchange -> new StubResponse(404, "not found"));

        CheckResult result = new CameraStoragePoller().poll(
                storageRequest(port, "camera.storage.status"));

        assertThat(result.state()).isEqualTo(ItemState.NOT_SUPPORTED);
        // The distinction matters: this is a model without the API, not a
        // camera that has lost its card, and the two need different actions.
        assertThat(result.error()).contains("does not expose");
    }

    @Test
    void reportsRejectedCredentialsPlainly() throws IOException {
        int port = startStub(exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate",
                    "Digest qop=\"auth\", realm=\"IP Camera\", nonce=\"abc\"");
            return new StubResponse(401, "");
        });

        CheckResult result = new CameraStoragePoller().poll(
                storageRequest(port, "camera.storage.status"));

        assertThat(result.state()).isEqualTo(ItemState.NOT_SUPPORTED);
        assertThat(result.error()).contains("{$CAMERA.USER}");
    }

    @Test
    @DisplayName("free space percentage is unsupported rather than zero when no card is present")
    void freePercentageIsUnsupportedWithNoCard() throws IOException {
        int port = startStub(exchange ->
                new StubResponse(200, "<storage><hddList size=\"0\"/></storage>"));

        CameraStoragePoller poller = new CameraStoragePoller();

        CheckResult percentage = poller.poll(storageRequest(port, "camera.storage.pfree"));
        // Reporting 0% would fire a "card full" trigger on a camera whose
        // actual fault is that there is no card at all.
        assertThat(percentage.state()).isEqualTo(ItemState.NOT_SUPPORTED);

        CheckResult count = poller.poll(storageRequest(port, "camera.storage.count"));
        assertThat(count.value()).isEqualTo(0L);
    }

    @Test
    void computesFreePercentage() throws IOException {
        int port = startStub(exchange -> new StubResponse(200, HEALTHY));

        CheckResult result = new CameraStoragePoller().poll(
                storageRequest(port, "camera.storage.pfree"));

        assertThat(result.valueType()).isEqualTo(ItemValueType.FLOAT);
        assertThat((Double) result.value()).isCloseTo(20480.0 / 60906 * 100, within(0.01));
    }

    /**
     * The template defines several storage items. A camera's embedded web
     * server is not a web server, so they must cost one request between them.
     */
    @Test
    void fetchesOncePerCameraForAllItems() throws IOException {
        AtomicInteger fetches = new AtomicInteger();
        int port = startStub(exchange -> {
            if (exchange.getRequestHeaders().getFirst("Authorization") == null) {
                exchange.getResponseHeaders().add("WWW-Authenticate",
                        "Digest qop=\"auth\", realm=\"IP Camera\", nonce=\"abc\"");
                return new StubResponse(401, "");
            }
            fetches.incrementAndGet();
            return new StubResponse(200, HEALTHY);
        });

        CameraStoragePoller poller = new CameraStoragePoller();
        for (String key : List.of("camera.storage.status", "camera.storage.count",
                "camera.storage.total", "camera.storage.free", "camera.storage.pfree")) {
            assertThat(poller.poll(storageRequest(port, key)).state()).isEqualTo(ItemState.NORMAL);
        }

        assertThat(fetches.get()).isEqualTo(1);
    }

    @Test
    void reportsAnUnreachableCameraWithoutThrowing() {
        // Port 1 is reserved and nothing listens there.
        CheckResult result = new CameraStoragePoller().poll(storageRequest(1, "camera.storage.status"));

        assertThat(result.state()).isEqualTo(ItemState.NOT_SUPPORTED);
        assertThat(result.error()).isNotBlank();
        assertThat(result.error()).doesNotContain("null");
    }

    // --- Harness -----------------------------------------------------------

    private record StubResponse(int status, String body) {
    }

    private interface StubHandler {
        StubResponse handle(com.sun.net.httpserver.HttpExchange exchange);
    }

    private int startStub(StubHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            StubResponse response = handler.handle(exchange);
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    private static CheckRequest storageRequest(int port, String key) {
        return new CheckRequest(1L, 1L, "cam-01", CheckType.CAMERA_STORAGE,
                ItemValueType.UNSIGNED, key, "127.0.0.1", port,
                Duration.ofSeconds(3),
                Map.of("username", "admin", "password", "secret"));
    }
}
