package com.nms.server.poller;

import com.nms.common.CheckResult;
import com.nms.common.ItemState;
import com.nms.common.ItemValueType;
import com.nms.server.history.HistoryRecord;
import com.nms.server.history.HistoryWriter;
import com.nms.server.preprocessing.PreprocessingPipeline;
import com.nms.server.preprocessing.PreprocessingResult;
import com.nms.server.trigger.TriggerEvaluationQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Receives a collected value and moves it through the rest of the pipeline:
 * preprocessing, storage, then trigger evaluation.
 *
 * <p>Deliberately not transactional. It runs on a poller worker, and a value is
 * not worth holding a database connection for -- storage is handed to the
 * batching writer, and trigger evaluation happens on its own thread. Keeping
 * this path free of transactions is what allows fifty workers to share a pool
 * of thirty connections.
 */
@Component
public class ValueProcessor {

    private static final Logger log = LoggerFactory.getLogger(ValueProcessor.class);

    private final HistoryWriter historyWriter;
    private final PreprocessingPipeline preprocessing;
    private final TriggerEvaluationQueue triggerQueue;
    private final JdbcTemplate jdbc;

    public ValueProcessor(HistoryWriter historyWriter,
                          PreprocessingPipeline preprocessing,
                          TriggerEvaluationQueue triggerQueue,
                          JdbcTemplate jdbc) {
        this.historyWriter = historyWriter;
        this.preprocessing = preprocessing;
        this.triggerQueue = triggerQueue;
        this.jdbc = jdbc;
    }

    /** Handles one check result, successful or not. */
    public void accept(CheckResult result) {
        if (!result.isSuccess()) {
            recordFailure(result.itemId(), result.error());
            return;
        }

        PreprocessingResult processed = preprocessing.apply(result);

        if (processed.isDiscarded()) {
            // A legitimate outcome, not a failure: the first sample of a
            // change-per-second item has nothing to compare against, and a
            // discard-unchanged step drops a repeat on purpose.
            return;
        }
        if (processed.isError()) {
            recordFailure(result.itemId(), processed.error());
            return;
        }

        HistoryRecord record = toRecord(result.itemId(), processed.clock(),
                processed.valueType(), processed.value());
        if (record == null) {
            recordFailure(result.itemId(),
                    "value '" + abbreviate(processed.value()) + "' cannot be stored as "
                            + processed.valueType());
            return;
        }

        historyWriter.submit(record);

        // Triggers are evaluated off this thread. A trigger expression can read
        // a time window of history, which is a database round trip, and doing
        // that inline would tie up a poller worker per value.
        triggerQueue.submit(result.itemId(), processed.clock());
    }

    /**
     * Records that an item could not be collected.
     *
     * <p>Written straight to the latest-value table rather than through the
     * entity, because this happens on a worker thread with no session and
     * must not take a transaction. The error is what an operator sees against
     * the item, so it is the difference between a diagnosable gap and a
     * mysterious one.
     */
    public void recordFailure(long itemId, String error) {
        String message = error == null ? "collection failed" : abbreviate(error);
        Instant now = Instant.now();

        try {
            jdbc.update("""
                    INSERT INTO item_latest (item_id, clock, state, error)
                    VALUES (?, ?, 'NOT_SUPPORTED', ?)
                    ON CONFLICT (item_id) DO UPDATE SET
                        state = 'NOT_SUPPORTED',
                        error = EXCLUDED.error
                    """, itemId, Timestamp.from(now), message);

            // Mirrored onto the item so the configuration views and the
            // "unsupported items" report do not have to join to item_latest.
            jdbc.update("UPDATE item SET state = 'NOT_SUPPORTED', error = ? WHERE item_id = ?",
                    message, itemId);
        } catch (org.springframework.dao.DataAccessException e) {
            // A deleted item is the common case here: it was claimed, then
            // removed while the check was in flight. Not worth a stack trace.
            log.debug("Could not record failure for item {}: {}", itemId, e.getMessage());
        }
    }

    private static HistoryRecord toRecord(long itemId, Instant clock,
                                          ItemValueType valueType, Object value) {
        if (value == null) {
            return null;
        }
        if (valueType == null) {
            return HistoryRecord.text(itemId, clock, ItemValueType.TEXT, String.valueOf(value));
        }

        return switch (valueType) {
            case UNSIGNED, FLOAT -> {
                Double numeric = asDouble(value);
                yield numeric == null ? null : HistoryRecord.numeric(itemId, clock, valueType, numeric);
            }
            case CHARACTER -> {
                String text = String.valueOf(value);
                // history_str is varchar(255); truncating here beats letting
                // the insert fail and losing the whole batch it travels in.
                yield HistoryRecord.text(itemId, clock, valueType,
                        text.length() > 255 ? text.substring(0, 255) : text);
            }
            case TEXT -> HistoryRecord.text(itemId, clock, valueType, String.valueOf(value));
            case LOG -> HistoryRecord.logLine(itemId, clock, String.valueOf(value), null, "", 0, 0);
        };
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }

    /** Clears an item's error state after a successful collection. */
    public void clearError(long itemId) {
        try {
            jdbc.update("UPDATE item SET state = 'NORMAL', error = '' WHERE item_id = ? AND state <> 'NORMAL'",
                    itemId);
        } catch (org.springframework.dao.DataAccessException e) {
            log.debug("Could not clear error for item {}: {}", itemId, e.getMessage());
        }
    }

    /** Accepts a value that arrived from a proxy or an active agent. */
    public void acceptExternal(CheckResult result, ItemState previousState) {
        accept(result);
        if (result.isSuccess() && previousState == ItemState.NOT_SUPPORTED) {
            clearError(result.itemId());
        }
    }
}
