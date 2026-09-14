package com.nms.server.history;

import com.nms.common.ItemValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Batches collected values into the history tables.
 *
 * <p>Monitoring produces an unrelenting stream of very small writes: a thousand
 * cameras on a one-minute interval is over fifty inserts a second, each a
 * handful of bytes. Writing them individually spends nearly all its time on
 * round trips and per-statement overhead. Buffering and writing in batches turns
 * that into a few statements a second.
 *
 * <p>The buffer is bounded. When it fills -- because the database is slow or
 * unreachable -- values are dropped and counted rather than queued without
 * limit, because an unbounded queue converts a database problem into an
 * out-of-memory crash that also loses everything already buffered.
 */
@Component
public class HistoryWriter {

    private static final Logger log = LoggerFactory.getLogger(HistoryWriter.class);

    private final JdbcTemplate jdbc;
    private final BlockingQueue<HistoryRecord> queue;
    private final int batchSize;
    private final long flushIntervalMs;

    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private Thread flusher;
    private volatile boolean running = true;

    public HistoryWriter(JdbcTemplate jdbc,
                         @Value("${nms.history.batch-size:1000}") int batchSize,
                         @Value("${nms.history.flush-interval-ms:1000}") long flushIntervalMs,
                         @Value("${nms.history.queue-capacity:200000}") int queueCapacity) {
        this.jdbc = jdbc;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
    }

    @PostConstruct
    void start() {
        flusher = new Thread(this::flushLoop, "history-writer");
        flusher.setDaemon(true);
        flusher.start();
        log.info("History writer started (batch {}, flush every {}ms, capacity {})",
                batchSize, flushIntervalMs, queue.remainingCapacity());
    }

    /**
     * Queues a value.
     *
     * <p>Never blocks: the caller is a poller worker, and stalling it would
     * back pressure into collection itself, so a slow database would stop us
     * noticing that anything is down.
     *
     * @return false when the buffer is full and the value was discarded
     */
    public boolean submit(HistoryRecord record) {
        if (queue.offer(record)) {
            return true;
        }
        long total = dropped.incrementAndGet();
        // Log on a rising scale rather than per value: a full buffer means
        // thousands a second, and logging each one makes the outage worse.
        if (total == 1 || Long.toString(total).matches("10*")) {
            log.error("History buffer is full; {} values discarded so far. "
                    + "The database is not keeping up with collection.", total);
        }
        return false;
    }

    private void flushLoop() {
        List<HistoryRecord> batch = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty()) {
            try {
                // Block for the first record so an idle writer does not spin,
                // then drain whatever else has accumulated.
                HistoryRecord first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);

                flush(batch);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // A failed batch must not kill the writer thread, or every
                // subsequent value is lost too.
                log.error("Failed to write a history batch of {} values: {}",
                        batch.size(), e.getMessage(), e);
                batch.clear();
            }
        }
    }

    private void flush(List<HistoryRecord> batch) {
        // Grouped by value type because each type has its own table, and a
        // single-table batch is what the JDBC driver can actually pipeline.
        Map<ItemValueType, List<HistoryRecord>> byType = new EnumMap<>(ItemValueType.class);
        for (HistoryRecord record : batch) {
            byType.computeIfAbsent(record.valueType(), t -> new ArrayList<>()).add(record);
        }

        byType.forEach((type, records) -> {
            switch (type) {
                case UNSIGNED -> insertUnsigned(records);
                case FLOAT -> insertFloat(records);
                case CHARACTER -> insertString(records, "history_str");
                case TEXT -> insertString(records, "history_text");
                case LOG -> insertLog(records);
            }
        });

        upsertLatest(batch);
        written.addAndGet(batch.size());
    }

    private void insertUnsigned(List<HistoryRecord> records) {
        // ON CONFLICT guards against a proxy re-sending a batch it never saw
        // acknowledged, which is the normal outcome of a dropped connection.
        jdbc.batchUpdate("""
                INSERT INTO history_uint (item_id, clock, value) VALUES (?, ?, ?)
                ON CONFLICT (item_id, clock) DO NOTHING
                """,
                records, records.size(), (ps, record) -> {
                    ps.setLong(1, record.itemId());
                    ps.setTimestamp(2, Timestamp.from(record.clock()));
                    ps.setLong(3, record.numericValue() == null ? 0L : record.numericValue().longValue());
                });
    }

    private void insertFloat(List<HistoryRecord> records) {
        jdbc.batchUpdate("""
                INSERT INTO history_float (item_id, clock, value) VALUES (?, ?, ?)
                ON CONFLICT (item_id, clock) DO NOTHING
                """,
                records, records.size(), (ps, record) -> {
                    ps.setLong(1, record.itemId());
                    ps.setTimestamp(2, Timestamp.from(record.clock()));
                    ps.setDouble(3, record.numericValue() == null ? 0d : record.numericValue());
                });
    }

    private void insertString(List<HistoryRecord> records, String table) {
        // These tables have no unique index: a device can legitimately report
        // the same string twice in one second, and text history is read as a
        // log rather than as a series.
        jdbc.batchUpdate("INSERT INTO " + table + " (item_id, clock, value) VALUES (?, ?, ?)",
                records, records.size(), (ps, record) -> {
                    ps.setLong(1, record.itemId());
                    ps.setTimestamp(2, Timestamp.from(record.clock()));
                    ps.setString(3, record.stringValue() == null ? "" : record.stringValue());
                });
    }

    private void insertLog(List<HistoryRecord> records) {
        jdbc.batchUpdate("""
                INSERT INTO history_log (item_id, clock, source_clock, source, severity, log_event_id, value)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                records, records.size(), (ps, record) -> {
                    ps.setLong(1, record.itemId());
                    ps.setTimestamp(2, Timestamp.from(record.clock()));
                    if (record.sourceClock() == null) {
                        ps.setNull(3, Types.TIMESTAMP);
                    } else {
                        ps.setTimestamp(3, Timestamp.from(record.sourceClock()));
                    }
                    ps.setString(4, record.logSource() == null ? "" : record.logSource());
                    ps.setInt(5, record.logSeverity());
                    ps.setInt(6, record.logEventId());
                    ps.setString(7, record.stringValue() == null ? "" : record.stringValue());
                });
    }

    /**
     * Refreshes the latest-value table.
     *
     * <p>The previous value is carried across from the row being replaced, so
     * change-based trigger functions and delta preprocessing have it without a
     * history read. The clock guard stops an out-of-order arrival -- a proxy
     * flushing a backlog after a network outage -- from overwriting a newer
     * value with an older one.
     */
    private void upsertLatest(List<HistoryRecord> batch) {
        jdbc.batchUpdate("""
                INSERT INTO item_latest (item_id, clock, value_num, value_str, state, error)
                VALUES (?, ?, ?, ?, 'NORMAL', '')
                ON CONFLICT (item_id) DO UPDATE SET
                    prev_clock     = item_latest.clock,
                    prev_value_num = item_latest.value_num,
                    clock          = EXCLUDED.clock,
                    value_num      = EXCLUDED.value_num,
                    value_str      = EXCLUDED.value_str,
                    state          = 'NORMAL',
                    error          = ''
                WHERE EXCLUDED.clock >= item_latest.clock
                """,
                batch, batch.size(), (ps, record) -> {
                    ps.setLong(1, record.itemId());
                    ps.setTimestamp(2, Timestamp.from(record.clock()));
                    if (record.numericValue() == null) {
                        ps.setNull(3, Types.DOUBLE);
                    } else {
                        ps.setDouble(3, record.numericValue());
                    }
                    ps.setString(4, record.stringValue());
                });
    }

    /** Values written since start, for self-monitoring. */
    public long writtenCount() {
        return written.get();
    }

    /** Values discarded because the buffer was full. */
    public long droppedCount() {
        return dropped.get();
    }

    /** Current buffer depth; a sustained rise means the database is behind. */
    public int queueDepth() {
        return queue.size();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (flusher != null) {
            flusher.interrupt();
            try {
                // Give the buffer a chance to drain so a rolling restart does
                // not discard the last second of collection.
                flusher.join(TimeUnit.SECONDS.toMillis(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("History writer stopped after {} values ({} dropped)", written.get(), dropped.get());
    }
}
