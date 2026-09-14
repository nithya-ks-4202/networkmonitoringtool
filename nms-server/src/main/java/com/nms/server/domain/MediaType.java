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

import java.util.HashMap;
import java.util.Map;

/**
 * A channel alerts can be delivered through.
 *
 * <p>Message bodies live here rather than on the action so that wording is
 * written once per channel: what reads well in an email is not what fits in an
 * SMS or a Slack card.
 */
@Entity
@Table(name = "media_type")
@Getter
@Setter
public class MediaType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "media_type_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private MediaTypeKind type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EntityStatus status = EntityStatus.DISABLED;

    /** Channel configuration: SMTP settings, webhook URL, routing key. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private Map<String, Object> config = new HashMap<>();

    /**
     * Message templates keyed by {@code SOURCE.PHASE}, e.g.
     * {@code TRIGGER.PROBLEM}, each holding a subject and a body.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "templates", nullable = false)
    private Map<String, Map<String, String>> templates = new HashMap<>();

    /**
     * Concurrent deliveries allowed on this channel. Bounded because an alert
     * storm must not be able to exhaust an SMTP relay's connection limit or
     * trip a provider's rate limiter -- which would delay the one alert that
     * mattered.
     */
    @Column(name = "max_sessions", nullable = false)
    private int maxSessions = 1;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "attempt_interval_seconds", nullable = false)
    private int attemptIntervalSeconds = 10;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 30;

    @Column(name = "description", nullable = false)
    private String description = "";

    public boolean isEnabled() {
        return status == EntityStatus.ENABLED;
    }

    /** Looks up a template, falling back to a generic one when absent. */
    public Map<String, String> template(String key) {
        return templates.getOrDefault(key, Map.of());
    }

    /** Reads a configuration value as a string. */
    public String configString(String key, String defaultValue) {
        Object value = config.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    /** Reads a configuration value as an int. */
    public int configInt(String key, int defaultValue) {
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
