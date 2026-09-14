package com.nms.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A person who can sign in.
 *
 * <p>Named {@code AppUser} because {@code user} is a reserved word in
 * PostgreSQL and unquoted references to it resolve to the session user.
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "username", nullable = false)
    private String username;

    /** Bcrypt hash. Null for users authenticated entirely by an identity provider. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "email")
    private String email;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    private Role role;

    @Column(name = "timezone", nullable = false)
    private String timezone = "UTC";

    @Column(name = "lang", nullable = false)
    private String lang = "en";

    @Column(name = "theme", nullable = false)
    private String theme = "dark";

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_source", nullable = false)
    private AuthSource authSource = AuthSource.LOCAL;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "mfa_secret")
    private String mfaSecret;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled = false;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "failed_logins", nullable = false)
    private int failedLogins = 0;

    /**
     * Set after repeated failures. Lockout is time-based rather than permanent
     * so a brute-force attempt cannot be used to deny a real operator access
     * during an incident.
     */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_group_member",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "usrgrp_id"))
    private Set<UserGroup> groups = new LinkedHashSet<>();

    /** True when the account may authenticate right now. */
    public boolean isActive() {
        if (!enabled) {
            return false;
        }
        if (lockedUntil != null && lockedUntil.isAfter(Instant.now())) {
            return false;
        }
        // A disabled group revokes access regardless of the user's own flag,
        // which is how access is cut for a whole team at once.
        return groups.stream().allMatch(UserGroup::isEnabled);
    }

    public String displayName() {
        return fullName == null || fullName.isBlank() ? username : fullName;
    }
}
