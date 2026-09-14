package com.nms.server.domain;

/** The transformations a value may pass through before storage. */
public enum PreprocessingType {
    /** Multiply by a constant, e.g. octets to bits. */
    MULTIPLIER,
    /** Subtract the previous raw value. */
    CHANGE,
    /**
     * Difference from the previous value divided by elapsed seconds.
     * This is what turns a monotonic interface counter into a throughput rate,
     * and it handles the counter wrapping that would otherwise produce a
     * enormous negative spike once per rollover.
     */
    CHANGE_PER_SECOND,
    /** Keep only the part matching a regular expression. */
    REGEX,
    /** Trim the given characters from both ends. */
    TRIM,
    /** Extract a value from a JSON document by path. */
    JSONPATH,
    /** Extract a value from an XML document by XPath. */
    XMLPATH,
    /** Discard the value unless it differs from the previous one. */
    DISCARD_UNCHANGED,
    /** As above, but re-store periodically so graphs do not develop gaps. */
    DISCARD_UNCHANGED_WITH_HEARTBEAT,
    /** Reject the value when it falls outside a range. */
    IN_RANGE,
    /** Map a value through a lookup table. */
    VALUE_MAP,
    /** Interpret the value as a boolean and store 1 or 0. */
    BOOL_TO_DECIMAL,
    /** Interpret a hexadecimal string as a number. */
    HEX_TO_DECIMAL,
    /** Interpret an octal string as a number. */
    OCTAL_TO_DECIMAL,
    /** Evaluate a small arithmetic expression over the value. */
    CUSTOM_FORMULA,
    /** Fail the item when the value matches a pattern. */
    CHECK_FOR_ERROR_REGEX
}
