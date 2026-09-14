package com.nms.common;

/**
 * Storage class of a collected value. Determines which history table receives
 * the value and which trigger functions may be applied to it.
 */
public enum ItemValueType {
    /** 64-bit unsigned integer, e.g. a byte counter. */
    UNSIGNED,
    /** Double precision float, e.g. a temperature or percentage. */
    FLOAT,
    /** Short character string (up to 255 chars). */
    CHARACTER,
    /** Log line with an optional source timestamp and severity. */
    LOG,
    /** Unbounded text blob. */
    TEXT;

    /** Numeric types support arithmetic trigger functions and trend aggregation. */
    public boolean isNumeric() {
        return this == UNSIGNED || this == FLOAT;
    }
}
