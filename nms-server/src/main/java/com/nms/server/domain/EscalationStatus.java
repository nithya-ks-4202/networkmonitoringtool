package com.nms.server.domain;

/** Where an escalation is in its lifecycle. */
public enum EscalationStatus {
    /** Climbing the ladder. */
    ACTIVE,
    /** Held because the host entered a maintenance window. */
    PAUSED_MAINTENANCE,
    /** Held because an operator acknowledged the problem. */
    PAUSED_ACK,
    /** The problem resolved; recovery messages are being sent. */
    RECOVERING,
    COMPLETED,
    /** Abandoned, e.g. the action or problem was deleted. */
    CANCELLED
}
