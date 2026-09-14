package com.nms.server.domain;

/** Delivery state of a single alert. */
public enum AlertStatus {
    /** Queued, not yet attempted. */
    NEW,
    /** Claimed by a sender; in flight. */
    SENDING,
    SENT,
    /** All attempts exhausted; the error column says why. */
    FAILED,
    /** Abandoned before delivery, e.g. the problem resolved first. */
    CANCELLED
}
