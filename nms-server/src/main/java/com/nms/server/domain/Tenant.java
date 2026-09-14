package com.nms.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * An isolated customer or business unit.
 *
 * <p>Every configuration row carries a tenant. In a hosted deployment that
 * isolation has to be a property of the data, not of which server the request
 * reached -- there is only one set of servers.
 */
@Entity
@Table(name = "tenant")
@Getter
@Setter
public class Tenant {

    /** The single-tenant default, created by the initial migration. */
    public static final long DEFAULT_ID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tenant_id")
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    /** URL-safe identifier, used for per-tenant hostnames. */
    @Column(name = "slug", nullable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TenantStatus status = TenantStatus.ACTIVE;

    /** Retention is a commercial term, so it belongs to the tenant. */
    @Column(name = "history_retention_days", nullable = false)
    private int historyRetentionDays = 90;

    @Column(name = "trend_retention_days", nullable = false)
    private int trendRetentionDays = 730;

    /** Zero means no limit. */
    @Column(name = "max_hosts", nullable = false)
    private int maxHosts = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
