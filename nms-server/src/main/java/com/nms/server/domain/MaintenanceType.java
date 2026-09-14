package com.nms.server.domain;

/** Whether collection continues during a maintenance window. */
public enum MaintenanceType {
    /**
     * Keep collecting, suppress problems. Preferred: capacity and performance
     * reports do not develop a hole every time a host is patched.
     */
    WITH_DATA,
    /** Stop collecting entirely, e.g. when the host will be powered off. */
    WITHOUT_DATA
}
