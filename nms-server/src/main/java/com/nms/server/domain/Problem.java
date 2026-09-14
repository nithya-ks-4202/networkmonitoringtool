package com.nms.server.domain;

import com.nms.common.Severity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An incident: something that is wrong, or was.
 *
 * <p>A problem is created from a PROBLEM event and closed by an OK event or by
 * an operator. Resolved problems are retained rather than deleted so the
 * problem view can show recent history and so mean-time-to-resolve can be
 * computed without reconstructing it from the event log.
 */
@Entity
@Table(name = "problem")
@Getter
@Setter
public class Problem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "problem_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** The event that opened this problem. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private EventSource source = EventSource.TRIGGER;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_type", nullable = false)
    private EventObjectType objectType = EventObjectType.TRIGGER;

    @Column(name = "object_id", nullable = false)
    private Long objectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id")
    private Host host;

    @Column(name = "clock", nullable = false)
    private Instant clock = Instant.now();

    @Column(name = "name", nullable = false)
    private String name = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity = Severity.NOT_CLASSIFIED;

    /**
     * Severity as the trigger defined it, preserved when an operator changes
     * the working severity during triage, so reporting is not rewritten by
     * human action after the fact.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "original_severity", nullable = false)
    private Severity originalSeverity = Severity.NOT_CLASSIFIED;

    @Column(name = "acknowledged", nullable = false)
    private boolean acknowledged = false;

    /**
     * True while a maintenance window or an operator suppression is in force.
     * A suppressed problem is still open and still recorded; it simply does not
     * page anyone. Discarding it instead would hide a real fault that began
     * during a maintenance window.
     */
    @Column(name = "suppressed", nullable = false)
    private boolean suppressed = false;

    @Column(name = "suppressed_until")
    private Instant suppressedUntil;

    @Column(name = "opdata", nullable = false)
    private String opdata = "";

    /** The event that closed this problem; null while it is open. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "r_event_id")
    private Event recoveryEvent;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @OneToMany(mappedBy = "problem", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProblemTag> tags = new ArrayList<>();

    @OneToMany(mappedBy = "problem", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProblemAck> acknowledgements = new ArrayList<>();

    public boolean isOpen() {
        return resolvedAt == null;
    }

    /** How long this problem has been, or was, open. */
    public Duration duration() {
        return Duration.between(clock, resolvedAt == null ? Instant.now() : resolvedAt);
    }
}
