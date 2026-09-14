package com.nms.server.domain;

/** How a condition's value is compared. */
public enum ConditionOperator {
    EQUALS,
    NOT_EQUALS,
    CONTAINS,
    NOT_CONTAINS,
    IN,
    NOT_IN,
    GREATER_OR_EQUAL,
    LESS_OR_EQUAL,
    MATCHES,
    NOT_MATCHES,
    YES,
    NO
}
