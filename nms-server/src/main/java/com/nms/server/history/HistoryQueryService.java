package com.nms.server.history;

import com.nms.common.ItemValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reads history and trends.
 *
 * <p>Serves two very different callers. Trigger evaluation asks for a short,
 * recent window and needs it fast, many times a second. Graphing asks for long
 * ranges and cares about how many points come back rather than how fresh they
 * are, so it is answered from trends once the range is wide enough that raw
 * samples would be both slower and denser than any display could use.
 */
@Service
public class HistoryQueryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryQueryService.class);

    /**
     * Ranges wider than this are served from hourly trends.
     *
     * <p>Two days of one-minute samples is under 3,000 points, which any chart
     * can draw. A month is 43,000 -- far more than a 1,000-pixel-wide graph can
     * show, so reading them would be effort spent producing pixels nobody sees.
     */
    private static final Duration TREND_THRESHOLD = Duration.ofDays(2);

    /** Ceiling on rows returned to a trigger function, to bound a bad window. */
    private static final int MAX_TRIGGER_WINDOW_ROWS = 100_000;

    private final JdbcTemplate jdbc;

    public HistoryQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Numeric values for an item over a time window, newest first.
     *
     * @param itemId    the item
     * @param valueType decides which table is read
     * @param since     inclusive lower bound
     * @param until     exclusive upper bound
     */
    public List<Sample> numericWindow(long itemId, ItemValueType valueType, Instant since, Instant until) {
        String table = numericTable(valueType);
        return jdbc.query("""
                SELECT clock, value FROM %s
                WHERE item_id = ? AND clock >= ? AND clock < ?
                ORDER BY clock DESC
                LIMIT %d
                """.formatted(table, MAX_TRIGGER_WINDOW_ROWS),
                (rs, rowNum) -> new Sample(rs.getTimestamp(1).toInstant(), rs.getDouble(2)),
                itemId, Timestamp.from(since), Timestamp.from(until));
    }

    /**
     * The most recent {@code count} numeric values, newest first.
     *
     * <p>Distinct from a time window: {@code #5} means the last five values
     * however long they took to arrive, which is what a trigger on a slow or
     * irregular item needs.
     */
    public List<Sample> lastNumericValues(long itemId, ItemValueType valueType, int count) {
        String table = numericTable(valueType);
        return jdbc.query("""
                SELECT clock, value FROM %s
                WHERE item_id = ?
                ORDER BY clock DESC
                LIMIT ?
                """.formatted(table),
                (rs, rowNum) -> new Sample(rs.getTimestamp(1).toInstant(), rs.getDouble(2)),
                itemId, Math.max(1, Math.min(count, 10_000)));
    }

    /** The most recent text values, newest first. */
    public List<TextSample> lastTextValues(long itemId, ItemValueType valueType, int count) {
        String table = valueType == ItemValueType.CHARACTER ? "history_str"
                : valueType == ItemValueType.LOG ? "history_log" : "history_text";
        return jdbc.query("""
                SELECT clock, value FROM %s
                WHERE item_id = ?
                ORDER BY clock DESC
                LIMIT ?
                """.formatted(table),
                (rs, rowNum) -> new TextSample(rs.getTimestamp(1).toInstant(), rs.getString(2)),
                itemId, Math.max(1, Math.min(count, 10_000)));
    }

    /**
     * When an item last produced any value.
     *
     * <p>Read from the latest-value table rather than by scanning history,
     * because {@code nodata()} asks this of every item it guards on every
     * evaluation.
     */
    public Optional<Instant> lastValueClock(long itemId) {
        List<Timestamp> rows = jdbc.query(
                "SELECT clock FROM item_latest WHERE item_id = ?",
                (rs, rowNum) -> rs.getTimestamp(1),
                itemId);
        return rows.isEmpty() || rows.get(0) == null
                ? Optional.empty()
                : Optional.of(rows.get(0).toInstant());
    }

    /**
     * Points for a graph, from history or trends depending on the range.
     *
     * @param maxPoints target number of points; the series is bucketed down to
     *                  roughly this many so the payload stays proportional to
     *                  the display rather than to the range
     */
    public List<GraphPoint> graphSeries(long itemId, ItemValueType valueType,
                                        Instant since, Instant until, int maxPoints) {
        Duration range = Duration.between(since, until);
        boolean useTrends = range.compareTo(TREND_THRESHOLD) > 0;

        int buckets = Math.max(1, Math.min(maxPoints, 5_000));
        long bucketSeconds = Math.max(1, range.toSeconds() / buckets);

        String table = useTrends
                ? (valueType == ItemValueType.UNSIGNED ? "trends_uint" : "trends_float")
                : numericTable(valueType);

        // Trends already carry min/avg/max per hour; re-aggregating them keeps
        // the extremes rather than averaging away the spike that mattered.
        String valueExpression = useTrends
                ? "min(value_min) AS lo, avg(value_avg) AS mid, max(value_max) AS hi"
                : "min(value) AS lo, avg(value) AS mid, max(value) AS hi";

        try {
            return jdbc.query("""
                    SELECT to_timestamp(floor(extract(epoch FROM clock) / ?) * ?) AS bucket,
                           %s
                    FROM %s
                    WHERE item_id = ? AND clock >= ? AND clock < ?
                    GROUP BY bucket
                    ORDER BY bucket
                    """.formatted(valueExpression, table),
                    (rs, rowNum) -> new GraphPoint(
                            rs.getTimestamp("bucket").toInstant(),
                            rs.getDouble("lo"), rs.getDouble("mid"), rs.getDouble("hi")),
                    bucketSeconds, bucketSeconds, itemId, Timestamp.from(since), Timestamp.from(until));
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("Graph query failed for item {}: {}", itemId, e.getMessage());
            return List.of();
        }
    }

    private static String numericTable(ItemValueType valueType) {
        return valueType == ItemValueType.UNSIGNED ? "history_uint" : "history_float";
    }

    /** One stored numeric value. */
    public record Sample(Instant clock, double value) {
    }

    /** One stored text value. */
    public record TextSample(Instant clock, String value) {
    }

    /**
     * One bucket of a graph series.
     *
     * <p>Carries min, average and max rather than a single number so a chart
     * can shade the range: a bucket averaging 40% that peaked at 100% is a very
     * different picture from one that sat flat at 40%.
     */
    public record GraphPoint(Instant clock, double min, double avg, double max) {
    }
}
