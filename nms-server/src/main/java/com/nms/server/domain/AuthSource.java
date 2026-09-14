package com.nms.server.domain;

/** Where a user's credentials are verified. */
public enum AuthSource {
    LOCAL,
    LDAP,
    SAML,
    OIDC
}
