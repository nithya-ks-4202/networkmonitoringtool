package com.nms.server.domain;

/** Availability states a host can occupy over time. */
public enum AvailabilityState {
    UP,
    DOWN,
    /** No data yet, or collection has stopped. */
    UNKNOWN,
    /**
     * Inside a maintenance window. Kept distinct from DOWN so planned work does
     * not count against an availability target.
     */
    MAINTENANCE
}
