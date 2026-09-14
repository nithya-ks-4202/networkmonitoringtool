package com.nms.server.domain;

/** Broad privilege tier a role belongs to. */
public enum RoleType {
    GUEST,
    USER,
    ADMIN,
    /** Can administer tenants, proxies and other super admins. */
    SUPER_ADMIN
}
