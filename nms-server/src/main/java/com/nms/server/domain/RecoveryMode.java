package com.nms.server.domain;

/** How a trigger decides that its problem has ended. */
public enum RecoveryMode {
    /** Recover when the problem expression becomes false. */
    EXPRESSION,
    /** Recover only when a separate expression becomes true, giving hysteresis. */
    RECOVERY_EXPRESSION,
    /** Never recover automatically; an operator must close the problem. */
    NONE
}
