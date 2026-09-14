package com.nms.common;

/** Whether an item is currently collectable. */
public enum ItemState {
    /** Last collection succeeded. */
    NORMAL,
    /** Last collection failed; the recorded error explains why. */
    NOT_SUPPORTED
}
