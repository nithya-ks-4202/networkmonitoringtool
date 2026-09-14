package com.nms.common;

import java.time.Instant;

/**
 * Outcome of a single check.
 *
 * <p>A failed check is a first-class result rather than an exception: the
 * scheduler records the error against the item so operators can see *why*
 * collection stopped, which is the difference between "the camera is down" and
 * "we stopped asking".
 *
 * @param itemId    item the value belongs to
 * @param clock     when the value was collected
 * @param state     whether collection succeeded
 * @param value     collected value, or {@code null} when {@code state} is NOT_SUPPORTED
 * @param valueType how {@code value} should be stored
 * @param error     human-readable failure reason, or {@code null} on success
 */
public record CheckResult(
        long itemId,
        Instant clock,
        ItemState state,
        Object value,
        ItemValueType valueType,
        String error) {

    public static CheckResult ok(long itemId, Object value, ItemValueType valueType) {
        return new CheckResult(itemId, Instant.now(), ItemState.NORMAL, value, valueType, null);
    }

    public static CheckResult ok(long itemId, Instant clock, Object value, ItemValueType valueType) {
        return new CheckResult(itemId, clock, ItemState.NORMAL, value, valueType, null);
    }

    public static CheckResult failed(long itemId, String error) {
        return new CheckResult(itemId, Instant.now(), ItemState.NOT_SUPPORTED, null, null, error);
    }

    public boolean isSuccess() {
        return state == ItemState.NORMAL;
    }

    /** Best-effort numeric view of the value, for trend and trigger arithmetic. */
    public Double numericValue() {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
