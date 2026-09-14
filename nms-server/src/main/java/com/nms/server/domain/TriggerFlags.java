package com.nms.server.domain;

/** Whether a trigger is real, a discovery pattern, or discovery-created. */
public enum TriggerFlags {
    NORMAL,
    PROTOTYPE,
    DISCOVERED
}
