package com.nms.server.domain;

/** How many simultaneous problems one trigger may hold open. */
public enum EventGeneration {
    /** One problem at a time; further matches update the existing one. */
    SINGLE,
    /**
     * A new problem per occurrence. Needed for log and trap driven triggers,
     * where each matching line is its own incident.
     */
    MULTIPLE
}
