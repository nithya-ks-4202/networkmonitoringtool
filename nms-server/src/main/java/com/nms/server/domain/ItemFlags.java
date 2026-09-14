package com.nms.server.domain;

/** What role an item plays. */
public enum ItemFlags {
    /** An ordinary metric. */
    NORMAL,
    /** Its value is a JSON list of entities that drives low-level discovery. */
    DISCOVERY_RULE,
    /** A pattern discovery instantiates per discovered entity; never polled. */
    PROTOTYPE,
    /** Created from a prototype; removed when its entity disappears. */
    DISCOVERED
}
