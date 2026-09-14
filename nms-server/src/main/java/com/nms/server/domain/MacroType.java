package com.nms.server.domain;

/** How a macro's value is stored and whether it may be read back. */
public enum MacroType {
    TEXT,
    /** Never returned by the API and masked in the interface. */
    SECRET,
    /** Resolved at use time from an external secret store. */
    VAULT
}
