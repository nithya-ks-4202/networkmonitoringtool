package com.nms.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nms.common.CheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Buffers collected results until the server acknowledges them.
 *
 * <p>A site's internet link is the least reliable part of the whole system, and
 * it fails exactly when someone most wants the data. Results are therefore held
 * in memory for the common case and spilled to disk when the server is
 * unreachable, so an overnight outage costs nothing but latency.
 *
 * <p>The spool is bounded and drops the oldest results first when full. A proxy
 * that fills its disk stops collecting and takes the host with it; and an
 * operator responding to an incident now needs the last ten minutes far more
 * than they need last Tuesday's.
 */
@Component
public class ResultSpool {

    private static final Logger log = LoggerFactory.getLogger(ResultSpool.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules();

    /** Results held before any disk write, which covers a healthy proxy entirely. */
    private static final int MEMORY_CAPACITY = 20_000;

    private final ProxyProperties properties;
    private final ConcurrentLinkedQueue<CheckResult> memory = new ConcurrentLinkedQueue<>();
    private final AtomicLong memoryDepth = new AtomicLong();
    private final AtomicLong spilled = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private Path spoolDirectory;

    public ResultSpool(ProxyProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void prepare() throws IOException {
        spoolDirectory = Paths.get(properties.getSpoolDirectory());
        Files.createDirectories(spoolDirectory);

        long recovered = countSpooledFiles();
        if (recovered > 0) {
            // Results left by a previous run. Saying so at startup makes a
            // restart during an outage visible rather than mysterious.
            log.info("Recovered {} spooled batch file(s) from a previous run", recovered);
        }
    }

    /** Accepts a collected result. */
    public void add(CheckResult result) {
        memory.add(result);
        if (memoryDepth.incrementAndGet() > MEMORY_CAPACITY) {
            spillToDisk();
        }
    }

    /**
     * Takes up to {@code limit} results for upload.
     *
     * <p>Disk is drained before memory so results leave in roughly the order
     * they were collected, which keeps the server's view of a recovering site
     * chronological rather than scrambled.
     */
    public List<CheckResult> take(int limit) {
        List<CheckResult> batch = new ArrayList<>(limit);

        batch.addAll(readSpooledBatch(limit));
        if (batch.size() >= limit) {
            return batch;
        }

        CheckResult result;
        while (batch.size() < limit && (result = memory.poll()) != null) {
            memoryDepth.decrementAndGet();
            batch.add(result);
        }
        return batch;
    }

    /**
     * Returns results to the buffer after a failed upload.
     *
     * <p>Put back rather than discarded: an upload failure means the network
     * was unavailable, not that the measurements were wrong.
     */
    public void returnToBuffer(List<CheckResult> results) {
        results.forEach(memory::add);
        memoryDepth.addAndGet(results.size());

        if (memoryDepth.get() > MEMORY_CAPACITY) {
            spillToDisk();
        }
    }

    /** Writes the in-memory buffer out as one batch file. */
    private synchronized void spillToDisk() {
        if (memory.isEmpty()) {
            return;
        }

        if (spoolSizeBytes() >= properties.getSpoolMaxBytes()) {
            evictOldest();
        }

        List<CheckResult> batch = new ArrayList<>();
        CheckResult result;
        while (batch.size() < MEMORY_CAPACITY && (result = memory.poll()) != null) {
            memoryDepth.decrementAndGet();
            batch.add(result);
        }
        if (batch.isEmpty()) {
            return;
        }

        // Named by timestamp so the natural sort order is also the collection
        // order, which is what lets the reader drain oldest-first without an
        // index.
        Path file = spoolDirectory.resolve("batch-" + System.currentTimeMillis()
                + "-" + System.nanoTime() + ".json");
        try {
            Files.writeString(file, JSON.writeValueAsString(batch), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            spilled.addAndGet(batch.size());
            log.info("Server unreachable; spooled {} result(s) to {}", batch.size(), file.getFileName());
        } catch (IOException e) {
            // The disk is full or unwritable. The results are lost, and that
            // has to be said plainly -- silently discarding monitoring data is
            // how an outage becomes invisible after the fact.
            dropped.addAndGet(batch.size());
            log.error("Could not spool {} result(s) to {}: {}. These values are lost.",
                    batch.size(), spoolDirectory, e.getMessage());
        }
    }

    /** Reads and removes one spooled batch. */
    private synchronized List<CheckResult> readSpooledBatch(int limit) {
        try (Stream<Path> files = Files.list(spoolDirectory)) {
            Path oldest = files
                    .filter(path -> path.getFileName().toString().startsWith("batch-"))
                    .min(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElse(null);

            if (oldest == null) {
                return List.of();
            }

            String content = Files.readString(oldest, StandardCharsets.UTF_8);
            List<CheckResult> results = JSON.readerForListOf(CheckResult.class).readValue(content);

            // Deleted only after a successful parse: a truncated file would
            // otherwise be removed along with everything it still contained.
            Files.deleteIfExists(oldest);

            if (results.size() > limit) {
                // The remainder goes back to memory rather than being rewritten
                // to disk, since it is about to be uploaded anyway.
                List<CheckResult> head = new ArrayList<>(results.subList(0, limit));
                returnToBuffer(results.subList(limit, results.size()));
                return head;
            }
            return results;

        } catch (IOException | UncheckedIOException e) {
            log.error("Could not read a spooled batch from {}: {}", spoolDirectory, e.getMessage());
            return List.of();
        }
    }

    /** Removes the oldest spool files to stay under the size ceiling. */
    private void evictOldest() {
        try (Stream<Path> files = Files.list(spoolDirectory)) {
            List<Path> sorted = files
                    .filter(path -> path.getFileName().toString().startsWith("batch-"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();

            long size = spoolSizeBytes();
            for (Path file : sorted) {
                if (size < properties.getSpoolMaxBytes() * 8 / 10) {
                    break;
                }
                long fileSize = Files.size(file);
                Files.deleteIfExists(file);
                size -= fileSize;
                log.warn("Spool is full; discarded the oldest batch {}", file.getFileName());
            }
        } catch (IOException e) {
            log.error("Could not evict old spool files: {}", e.getMessage());
        }
    }

    private long spoolSizeBytes() {
        try (Stream<Path> files = Files.list(spoolDirectory)) {
            return files.mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private long countSpooledFiles() {
        try (Stream<Path> files = Files.list(spoolDirectory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("batch-")).count();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Results waiting to be uploaded, reported to the server for monitoring. */
    public int depth() {
        return (int) Math.min(Integer.MAX_VALUE, memoryDepth.get());
    }

    public long spilledCount() {
        return spilled.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    /** Flushes memory to disk, so a clean shutdown loses nothing. */
    public void flush() {
        spillToDisk();
    }
}
