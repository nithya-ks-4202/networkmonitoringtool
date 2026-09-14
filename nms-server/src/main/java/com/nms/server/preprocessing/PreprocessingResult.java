package com.nms.server.preprocessing;

import com.nms.common.ItemValueType;

import java.time.Instant;

/**
 * Outcome of running a value through an item's preprocessing steps.
 *
 * <p>Three outcomes, not two. A discarded value is distinct from a failed one:
 * discarding is what the first sample of a rate item and a
 * discard-unchanged step are supposed to do, and treating either as an error
 * would put a perfectly healthy item into the error state every time it was
 * working correctly.
 *
 * @param status    what happened
 * @param value     the transformed value, when the status is STORED
 * @param valueType how it should be stored
 * @param clock     collection time, carried through unchanged
 * @param error     explanation, when the status is ERROR
 */
public record PreprocessingResult(
        Status status,
        Object value,
        ItemValueType valueType,
        Instant clock,
        String error) {

    public enum Status {
        /** Store this value. */
        STORED,
        /** Nothing to store, and nothing is wrong. */
        DISCARDED,
        /** A step could not be applied; the item goes into the error state. */
        ERROR
    }

    public static PreprocessingResult stored(Object value, ItemValueType valueType, Instant clock) {
        return new PreprocessingResult(Status.STORED, value, valueType, clock, null);
    }

    public static PreprocessingResult discarded(Instant clock) {
        return new PreprocessingResult(Status.DISCARDED, null, null, clock, null);
    }

    public static PreprocessingResult error(String error, Instant clock) {
        return new PreprocessingResult(Status.ERROR, null, null, clock, error);
    }

    public boolean isStored() {
        return status == Status.STORED;
    }

    public boolean isDiscarded() {
        return status == Status.DISCARDED;
    }

    public boolean isError() {
        return status == Status.ERROR;
    }
}
