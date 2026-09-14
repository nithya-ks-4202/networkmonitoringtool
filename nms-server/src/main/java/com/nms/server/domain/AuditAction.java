package com.nms.server.domain;

/** The kind of operation an audit entry records. */
public enum AuditAction {
    LOGIN,
    LOGOUT,
    LOGIN_FAILED,
    CREATE,
    UPDATE,
    DELETE,
    EXECUTE,
    ACKNOWLEDGE
}
