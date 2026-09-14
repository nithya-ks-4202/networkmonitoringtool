package com.nms.server.domain;

/** What kind of activity produced an event. */
public enum EventSource {
    /** A trigger expression changed value. */
    TRIGGER,
    /** Network discovery found, lost or changed a device. */
    DISCOVERY,
    /** A host registered itself with the server. */
    AUTOREGISTRATION,
    /** The platform reporting on itself, e.g. an item becoming unsupported. */
    INTERNAL,
    /** A business service changed state. */
    SERVICE
}
