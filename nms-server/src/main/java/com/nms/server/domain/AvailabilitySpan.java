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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;

/**
 * A continuous period during which a host held one availability state.
 *
 * <p>Uptime reporting straight from raw history means scanning every ping
 * sample in the period -- for a thousand cameras at one minute each, that is
 * forty million rows a month to answer "what was our uptime?". Recording
 * transitions instead makes it a handful of rows per device.
 *
 * <p>Exactly one span per host is open at a time, enforced by a partial unique
 * index on {@code ended_at IS NULL}.
 */
@Entity
@Table(name = "availability_span")
@Getter
@Setter
public class AvailabilitySpan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "span_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false)
    private Host host;

    /** The item whose value decided the state, for traceability. */
    @Column(name = "item_id")
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private AvailabilityState state;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    /** Null while this is the current state. */
    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "reason", nullable = false)
    private String reason = "";

    public Duration duration() {
        return Duration.between(startedAt, endedAt == null ? Instant.now() : endedAt);
    }

    public boolean isOpen() {
        return endedAt == null;
    }
}
