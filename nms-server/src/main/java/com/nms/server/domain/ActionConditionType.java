package com.nms.server.domain;

/** The attribute of an event an action condition tests. */
public enum ActionConditionType {
    HOST_GROUP,
    HOST,
    TRIGGER,
    TRIGGER_NAME,
    TRIGGER_SEVERITY,
    /** Restricts the action to certain hours, e.g. out-of-hours paging only. */
    TIME_PERIOD,
    HOST_TEMPLATE,
    /** Whether the problem is currently suppressed by maintenance. */
    PROBLEM_SUPPRESSED,
    EVENT_TAG,
    EVENT_TAG_VALUE,
    HOST_CLASS,
    PROXY
}
