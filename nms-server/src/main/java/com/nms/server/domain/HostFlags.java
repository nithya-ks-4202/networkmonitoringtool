package com.nms.server.domain;

/** What kind of record a {@link Host} row is. */
public enum HostFlags {
    /** A real host that is polled. */
    MONITORED,
    /** A reusable definition linked to hosts; never polled itself. */
    TEMPLATE,
    /** A pattern from which discovery creates real hosts. */
    PROTOTYPE
}
