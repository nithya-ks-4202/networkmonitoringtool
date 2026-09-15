package com.nms.collector.camera;

import com.nms.collector.Failures;
import com.nms.collector.Poller;
import com.nms.collector.http.HttpDigestAuth;
import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import com.nms.common.ItemValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a camera's own storage: is the SD card there, healthy, and has it room.
 *
 * <p>This exists because of a failure that every other check in the camera
 * template passes cleanly. A camera whose SD card has died still answers ICMP,
 * still negotiates RTSP, still serves live video and still reports itself
 * healthy over ONVIF -- and records nothing. The first anyone knows is when
 * footage is asked for and there is none, typically weeks later, which is the
 * moment monitoring was supposed to prevent.
 *
 * <p>No interoperable standard reports it. ONVIF's storage configuration
 * describes configured targets rather than card health, and its recording
 * search service is optional and widely unimplemented on fixed cameras. So
 * this reads the vendor's own API. Hikvision's ISAPI is implemented here; the
 * shape below -- fetch once, answer several keys -- is what another vendor
 * would slot into.
 */
@Component
public class CameraStoragePoller implements Poller {

    private static final Logger log = LoggerFactory.getLogger(CameraStoragePoller.class);

    private static final String ISAPI_STORAGE = "/ISAPI/ContentMgmt/Storage";

    /** Hikvision reports capacity and free space in mebibytes. */
    private static final long MIB = 1024L * 1024L;

    /**
     * One fetch serves every storage item on a host.
     *
     * <p>The template defines five, and a camera's embedded web server is not
     * a web server -- five HTTP round trips a minute against a device with a
     * few megabytes of RAM is a meaningful load, and some firmware simply
     * stops answering under it.
     */
    private static final Duration CACHE_TTL = Duration.ofSeconds(20);

    private final ConcurrentHashMap<String, CachedStorage> cache = new ConcurrentHashMap<>();
    private final HttpClient client;

    public CameraStoragePoller() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public CheckType checkType() {
        return CheckType.CAMERA_STORAGE;
    }

    @Override
    public CheckResult poll(CheckRequest request) {
        String key = stripParameters(request.key());

        Storage storage;
        try {
            storage = fetch(request);
        } catch (StorageUnavailableException e) {
            return CheckResult.failed(request.itemId(), e.getMessage());
        }

        return switch (key) {
            // 1 or 0 rather than the vendor's word, so a trigger can be
            // written against it. The word is kept for the error text, which
            // is where an operator actually needs it.
            case "camera.storage.status" -> CheckResult.ok(request.itemId(),
                    storage.healthy() ? 1L : 0L, ItemValueType.UNSIGNED);

            // Zero means no card is present at all. Worth its own item: a
            // card that has fallen out of its slot reports no error, it
            // simply stops being listed, and "status ok" on an empty list
            // would otherwise read as healthy.
            case "camera.storage.count" -> CheckResult.ok(request.itemId(),
                    (long) storage.devices().size(), ItemValueType.UNSIGNED);

            case "camera.storage.total" -> CheckResult.ok(request.itemId(),
                    storage.totalBytes(), ItemValueType.UNSIGNED);

            case "camera.storage.free" -> CheckResult.ok(request.itemId(),
                    storage.freeBytes(), ItemValueType.UNSIGNED);

            case "camera.storage.pfree" -> storage.totalBytes() == 0
                    // Not zero percent: with no card there is no percentage,
                    // and reporting 0 would fire a "disk full" trigger on a
                    // camera whose actual fault is that the card is missing.
                    ? CheckResult.failed(request.itemId(),
                            "No storage device is present, so free space has no value")
                    : CheckResult.ok(request.itemId(),
                            100.0 * storage.freeBytes() / storage.totalBytes(),
                            ItemValueType.FLOAT);

            case "camera.storage.info" -> CheckResult.ok(request.itemId(),
                    storage.describe(), ItemValueType.TEXT);

            default -> CheckResult.failed(request.itemId(),
                    "Unknown camera storage key '" + key + "'");
        };
    }

    private Storage fetch(CheckRequest request) {
        String cacheKey = request.address() + ':' + request.port();
        CachedStorage cached = cache.get(cacheKey);
        long now = System.nanoTime();
        if (cached != null && now - cached.storedAt < CACHE_TTL.toNanos()) {
            if (cached.failure != null) {
                throw new StorageUnavailableException(cached.failure);
            }
            return cached.storage;
        }

        try {
            Storage storage = request(request);
            cache.put(cacheKey, new CachedStorage(storage, null, now));
            return storage;
        } catch (StorageUnavailableException e) {
            // Failures are cached too, for the same reason successes are: an
            // unreachable camera would otherwise be asked five times a minute
            // and each one would wait out the full timeout.
            cache.put(cacheKey, new CachedStorage(null, e.getMessage(), now));
            throw e;
        }
    }

    private Storage request(CheckRequest request) {
        String username = request.params().getOrDefault("username", "");
        String password = request.params().getOrDefault("password", "");
        String scheme = "true".equalsIgnoreCase(request.params().get("https")) ? "https" : "http";
        int port = request.port() > 0 ? request.port() : 80;
        String path = request.params().getOrDefault("path", ISAPI_STORAGE);

        URI uri = URI.create(scheme + "://" + request.address() + ':' + port + path);
        Duration timeout = request.timeout();

        try {
            HttpResponse<String> response = send(uri, timeout, null);

            if (response.statusCode() == 401) {
                String challenge = response.headers().firstValue("WWW-Authenticate").orElse(null);
                if (username.isEmpty()) {
                    throw new StorageUnavailableException(
                            "The camera requires credentials for its storage API. "
                                    + "Set {$CAMERA.USER} and {$CAMERA.PASSWORD}.");
                }
                String authorization = HttpDigestAuth.authorization(
                        challenge, "GET", path, username, password);
                if (authorization == null) {
                    throw new StorageUnavailableException(
                            "Unsupported authentication scheme: " + challenge);
                }
                response = send(uri, timeout, authorization);
            }

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new StorageUnavailableException(
                        "The camera rejected the credentials in {$CAMERA.USER} / {$CAMERA.PASSWORD}");
            }
            if (response.statusCode() == 404) {
                // Said precisely: this is a model that does not expose the
                // API, not a broken card, and the two call for entirely
                // different responses from whoever reads it.
                throw new StorageUnavailableException(
                        "This camera does not expose " + path
                                + ". It may not be a Hikvision model, or the firmware predates ISAPI.");
            }
            if (response.statusCode() != 200) {
                throw new StorageUnavailableException(
                        "Storage API returned HTTP " + response.statusCode());
            }

            // An unrecognisable body is reported as "could not read", never
            // parsed into zero devices. Zero devices means "this camera has
            // no card", which raises an alarm -- and a truncated reply, a
            // login page from a reverse proxy, or a firmware that answers 200
            // with something else entirely would otherwise raise that alarm
            // on a camera whose card is perfectly fine.
            if (!looksLikeStorageResponse(response.body())) {
                throw new StorageUnavailableException(
                        "The storage API answered with something that is not a storage "
                                + "listing. Check that " + path + " is the right path for this model.");
            }

            return parse(response.body());

        } catch (IOException e) {
            throw new StorageUnavailableException(
                    "Storage API unreachable: " + Failures.describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageUnavailableException("Interrupted while reading camera storage");
        }
    }

    private HttpResponse<String> send(URI uri, Duration timeout, String authorization)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/xml, text/xml, */*")
                .GET();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // --- Parsing -----------------------------------------------------------
    //
    // Deliberately regex over a tolerant subset rather than a full XML parse.
    // Camera firmware emits XML that is frequently not well formed -- stray
    // ampersands, unclosed elements, a BOM mid-document -- and a strict parser
    // turns a readable status into "unsupported item". The fields wanted here
    // are flat scalars, so the looser read costs nothing and survives firmware
    // that a DocumentBuilder rejects outright.

    private static final Pattern HDD_BLOCK =
            Pattern.compile("<hdd>(.*?)</hdd>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * Whether a 200 response is actually a storage listing.
     *
     * <p>Only the envelope is checked, not the contents: an empty
     * {@code <hddList size="0"/>} is a perfectly valid answer meaning there
     * is no card, and must stay distinguishable from a reply that was never
     * a storage listing at all.
     */
    static boolean looksLikeStorageResponse(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("<hddlist") || lower.contains("<storage");
    }

    static Storage parse(String xml) {
        List<Device> devices = new ArrayList<>();

        Matcher blocks = HDD_BLOCK.matcher(xml == null ? "" : xml);
        while (blocks.find()) {
            String block = blocks.group(1);
            devices.add(new Device(
                    text(block, "hddName"),
                    text(block, "hddType"),
                    text(block, "status"),
                    number(block, "capacity") * MIB,
                    number(block, "freeSpace") * MIB,
                    text(block, "property")));
        }

        return new Storage(devices);
    }

    private static String text(String block, String element) {
        Matcher matcher = Pattern.compile(
                        "<" + element + ">\\s*(.*?)\\s*</" + element + ">",
                        Pattern.DOTALL | Pattern.CASE_INSENSITIVE)
                .matcher(block);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static long number(String block, String element) {
        String value = text(block, element);
        if (value.isEmpty()) {
            return 0;
        }
        try {
            // Some firmware writes a decimal here despite the schema.
            return (long) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            log.debug("Unparseable <{}> value '{}' in camera storage response", element, value);
            return 0;
        }
    }

    /** Strips the {@code [...]} parameter list Zabbix-style keys carry. */
    private static String stripParameters(String key) {
        if (key == null) {
            return "";
        }
        int bracket = key.indexOf('[');
        return bracket < 0 ? key.trim() : key.substring(0, bracket).trim();
    }

    /** One storage device as the camera reports it. */
    record Device(String name, String type, String status,
                  long capacityBytes, long freeBytes, String property) {

        /**
         * Whether this device is usable for recording.
         *
         * <p>"ok" and "idle" are both healthy: idle means present and working
         * but not currently being written to, which is normal on a camera set
         * to record on motion.
         */
        boolean healthy() {
            String state = status == null ? "" : status.toLowerCase(Locale.ROOT);
            return state.equals("ok") || state.equals("idle") || state.equals("normal");
        }

        /**
         * A card mounted read-only.
         *
         * <p>Worth separating: the status often still reads "ok", and the
         * camera carries on as though recording. It is a common late stage of
         * flash wear -- the controller refuses writes rather than reporting a
         * fault.
         */
        boolean readOnly() {
            return property != null && property.equalsIgnoreCase("R");
        }
    }

    /** Everything the camera reported, and the verdicts drawn from it. */
    record Storage(List<Device> devices) {

        boolean healthy() {
            // An empty list is not healthy. No card is a fault on a camera
            // that is supposed to be recording, and it is the way a card that
            // has failed hard or worked loose actually presents.
            return !devices.isEmpty()
                    && devices.stream().allMatch(d -> d.healthy() && !d.readOnly());
        }

        long totalBytes() {
            return devices.stream().mapToLong(Device::capacityBytes).sum();
        }

        long freeBytes() {
            return devices.stream().mapToLong(Device::freeBytes).sum();
        }

        /** Human-readable summary, kept as an item so it shows in the interface. */
        String describe() {
            if (devices.isEmpty()) {
                return "No storage device present";
            }
            StringBuilder text = new StringBuilder();
            for (Device device : devices) {
                if (!text.isEmpty()) {
                    text.append("; ");
                }
                text.append(device.type().isEmpty() ? "storage" : device.type())
                        .append(' ').append(device.name())
                        .append(": ").append(device.status());
                if (device.readOnly()) {
                    text.append(" (read-only)");
                }
                if (device.capacityBytes() > 0) {
                    text.append(String.format(Locale.ROOT, ", %.1f of %.1f GB free",
                            device.freeBytes() / 1e9, device.capacityBytes() / 1e9));
                }
            }
            return text.toString();
        }
    }

    private record CachedStorage(Storage storage, String failure, long storedAt) {
    }

    /** The camera could not be asked, as distinct from answering badly. */
    private static final class StorageUnavailableException extends RuntimeException {
        StorageUnavailableException(String message) {
            super(message);
        }
    }
}
