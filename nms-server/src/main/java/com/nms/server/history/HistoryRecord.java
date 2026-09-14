package com.nms.server.history;

import com.nms.common.ItemValueType;

import java.time.Instant;

/**
 * One value on its way to storage.
 *
 * <p>Flat and immutable so it can cross a queue between the poller threads and
 * the writer thread without any shared mutable state.
 *
 * @param itemId       item the value belongs to
 * @param clock        when it was collected
 * @param valueType    which history table receives it
 * @param numericValue value for numeric types, null otherwise
 * @param stringValue  value for text types, null otherwise
 * @param sourceClock  timestamp parsed from a log line, if any
 * @param logSource    origin of a log line, e.g. a Windows event log name
 * @param logSeverity  severity carried by a log line
 * @param logEventId   event identifier carried by a log line
 */
public record HistoryRecord(
        long itemId,
        Instant clock,
        ItemValueType valueType,
        Double numericValue,
        String stringValue,
        Instant sourceClock,
        String logSource,
        int logSeverity,
        int logEventId) {

    public static HistoryRecord numeric(long itemId, Instant clock, ItemValueType type, double value) {
        return new HistoryRecord(itemId, clock, type, value, null, null, null, 0, 0);
    }

    public static HistoryRecord text(long itemId, Instant clock, ItemValueType type, String value) {
        return new HistoryRecord(itemId, clock, type, null, value, null, null, 0, 0);
    }

    public static HistoryRecord logLine(long itemId, Instant clock, String value,
                                        Instant sourceClock, String source, int severity, int eventId) {
        return new HistoryRecord(itemId, clock, ItemValueType.LOG, null, value,
                sourceClock, source, severity, eventId);
    }
}
