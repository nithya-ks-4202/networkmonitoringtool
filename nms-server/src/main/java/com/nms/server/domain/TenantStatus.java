package com.nms.server.domain;

/** Whether a tenant may be used. */
public enum TenantStatus {
    ACTIVE,
    /** Retained but denied access, e.g. for non-payment. */
    SUSPENDED
}
