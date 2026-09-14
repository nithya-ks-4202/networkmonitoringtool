package com.nms.server.domain;

import com.nms.common.Severity;
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
 * An immutable record that something changed state at a point in time.
 *
 * <p>Events are append-only and never updated. The mutable view of an ongoing
 * incident is {@link Problem}. Keeping them apart is what lets the problem
 * table stay small and indexed for "what is broken right now" while events
 * accumulate indefinitely for audit and reporting.
 */
@Entity
@Table(name = "event")
@Getter
@Setter
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private EventSource source = EventSource.TRIGGER;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false)
    private EventObjectType objectType = EventObjectType.TRIGGER;

    /** Identifier of the trigger, discovery rule or item that produced this. */
    @Column(name = "object_id", nullable = false)
    private Long objectId;

    @Column(name = "clock", nullable = false)
    private Instant clock = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "value", nullable = false)
    private TriggerValue value;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity = Severity.NOT_CLASSIFIED;

    /**
     * Numeric form of {@link #severity}, for filtering and sorting.
     *
     * <p>Comparing enum names compares them as text, which orders Warning above
     * Disaster. Computed by the database from {@code severity}, so it cannot
     * drift however the row was written.
     */
    @Column(name = "severity_level", insertable = false, updatable = false)
    private short severityLevel;

    @Column(name = "name", nullable = false)
    private String name = "";

    @Column(name = "acknowledged", nullable = false)
    private boolean acknowledged = false;
}
