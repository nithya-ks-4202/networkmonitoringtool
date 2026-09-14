package com.nms.server.domain;

/** The kind of object an event's {@code objectId} refers to. */
public enum EventObjectType {
    TRIGGER,
    ITEM,
    DISCOVERY_RULE,
    DISCOVERED_HOST,
    DISCOVERED_SERVICE,
    AUTOREGISTRATION_HOST,
    SERVICE
}
