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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A record of who changed what.
 *
 * <p>The username is denormalised alongside the id: an audit trail that becomes
 * unreadable once an account is deleted is not an audit trail.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "clock", nullable = false)
    private Instant clock = Instant.now();

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username", nullable = false)
    private String username = "";

    @Column(name = "ip", nullable = false)
    private String ip = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private AuditAction action;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "resource_name", nullable = false)
    private String resourceName = "";

    /** Field-level before and after values. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false)
    private Map<String, Object> details = new HashMap<>();
}
