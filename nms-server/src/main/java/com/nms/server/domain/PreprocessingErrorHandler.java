package com.nms.server.domain;

/** What to do when a preprocessing step cannot be applied. */
public enum PreprocessingErrorHandler {
    /** Put the item into the error state, surfacing the failure. */
    ERROR,
    /**
     * Drop the sample silently. Correct for the first sample of a
     * change-per-second item, which has nothing to compare against.
     */
    DISCARD_VALUE,
    /** Substitute a fixed value. */
    SET_VALUE,
    /** Fail with a specific message instead of the raw one. */
    SET_ERROR
}
