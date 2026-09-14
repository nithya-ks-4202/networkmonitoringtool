package com.nms.server.domain;

/** Whether a trigger's expression can currently be evaluated at all. */
public enum TriggerState {
    NORMAL,
    /**
     * A referenced item has no usable data. Kept separate from an OK value so
     * an unevaluatable trigger is never mistaken for a healthy one.
     */
    UNKNOWN
}
