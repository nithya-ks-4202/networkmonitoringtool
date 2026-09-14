package com.nms.server.domain;

/** How a host's inventory fields are populated. */
public enum InventoryMode {
    DISABLED,
    MANUAL,
    /** Filled from collected values, e.g. ONVIF device information. */
    AUTOMATIC
}
