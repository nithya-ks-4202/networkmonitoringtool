package com.nms.server.domain;

/** How an action's conditions combine. */
public enum ConditionEvaluation {
    /**
     * Conditions of the same type are OR-ed, different types AND-ed. This is
     * almost always what is meant: "host group A or B, and severity >= high".
     */
    AND_OR,
    AND,
    OR,
    /** A hand-written boolean formula over the condition labels. */
    CUSTOM
}
