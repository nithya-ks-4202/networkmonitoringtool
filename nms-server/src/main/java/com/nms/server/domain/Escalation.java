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

import java.time.Instant;

/**
 * The live state of one action running against one problem.
 *
 * <p>There is exactly one escalation per (action, problem) pair, enforced by a
 * unique constraint. That constraint is what makes concurrent event processing
 * safe: two server instances handling the same event race to insert, one wins,
 * and the loser's duplicate is rejected by the database rather than producing a
 * second set of pages.
 */
@Entity
@Table(name = "escalation")
@Getter
@Setter
public class Escalation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "escalation_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "action_id", nullable = false)
    private Action action;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    @Column(name = "trigger_id")
    private Long triggerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id")
    private Host host;

    /** Rungs completed so far; the next run executes step + 1. */
    @Column(name = "step", nullable = false)
    private int step = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EscalationStatus status = EscalationStatus.ACTIVE;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt = Instant.now();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
