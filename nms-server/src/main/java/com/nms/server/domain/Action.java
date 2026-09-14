package com.nms.server.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * What to do when an event matches a set of conditions.
 *
 * <p>An action holds a ladder of operations keyed by escalation step, which is
 * what turns a notification into a process: tell the on-call engineer, wait,
 * tell them again, then widen to the team.
 */
@Entity
@Table(name = "action")
@Getter
@Setter
public class Action {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "action_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_source", nullable = false)
    private EventSource eventSource = EventSource.TRIGGER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EntityStatus status = EntityStatus.ENABLED;

    @Enumerated(EnumType.STRING)
    @Column(name = "eval_type", nullable = false)
    private ConditionEvaluation evalType = ConditionEvaluation.AND_OR;

    /** Boolean formula over condition labels, used when evalType is CUSTOM. */
    @Column(name = "formula", nullable = false)
    private String formula = "";

    /** Default delay between escalation steps. */
    @Column(name = "escalation_period_seconds", nullable = false)
    private int escalationPeriodSeconds = 3600;

    /**
     * Hold escalation while the host is in maintenance rather than discarding
     * the alert. A fault that begins during a maintenance window is still a
     * fault, and it should page someone once the window ends.
     */
    @Column(name = "pause_during_maintenance", nullable = false)
    private boolean pauseDuringMaintenance = true;

    /** Stop escalating once a human has taken the problem. */
    @Column(name = "pause_on_acknowledge", nullable = false)
    private boolean pauseOnAcknowledge = true;

    @Column(name = "notify_on_recovery", nullable = false)
    private boolean notifyOnRecovery = true;

    @Column(name = "notify_on_update", nullable = false)
    private boolean notifyOnUpdate = false;

    @Column(name = "description", nullable = false)
    private String description = "";

    @OneToMany(mappedBy = "action", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ActionCondition> conditions = new ArrayList<>();

    @OneToMany(mappedBy = "action", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("stepFrom ASC")
    private List<ActionOperation> operations = new ArrayList<>();

    public boolean isEnabled() {
        return status == EntityStatus.ENABLED;
    }
}
