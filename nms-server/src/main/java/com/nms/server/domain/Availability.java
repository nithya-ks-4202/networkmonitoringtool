package com.nms.server.domain;

/** Whether an interface has recently answered. */
public enum Availability {
    /** Never polled, or polled only since the last restart. */
    UNKNOWN,
    AVAILABLE,
    UNAVAILABLE
}
