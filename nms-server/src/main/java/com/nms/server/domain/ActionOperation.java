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

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One rung of an escalation ladder.
 *
 * <p>An operation covers a range of escalation steps. {@code stepTo == 0} means
 * "from this step onward, indefinitely", which is how a final rung keeps
 * reminding someone until the problem is acknowledged or resolved.
 */
@Entity
@Table(name = "action_operation")
@Getter
@Setter
public class ActionOperation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "operation_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "action_id", nullable = false)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false)
    private OperationType operationType = OperationType.SEND_MESSAGE;

    @Column(name = "step_from", nullable = false)
    private int stepFrom = 1;

    /** Last step this operation applies to; 0 means unbounded. */
    @Column(name = "step_to", nullable = false)
    private int stepTo = 1;

    /** Overrides the action's escalation period for this rung; 0 inherits. */
    @Column(name = "step_duration_seconds", nullable = false)
    private int stepDurationSeconds = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "eval_type", nullable = false)
    private ConditionEvaluation evalType = ConditionEvaluation.AND_OR;

    /** Channel to deliver on. Null means every channel the recipient has. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_type_id")
    private MediaType mediaType;

    @Column(name = "command", nullable = false)
    private String command = "";

    @Column(name = "command_target", nullable = false)
    private String commandTarget = "CURRENT_HOST";

    @Column(name = "custom_message", nullable = false)
    private boolean customMessage = false;

    @Column(name = "subject", nullable = false)
    private String subject = "";

    @Column(name = "message", nullable = false)
    private String message = "";

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "action_operation_user",
            joinColumns = @JoinColumn(name = "operation_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private Set<AppUser> users = new LinkedHashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "action_operation_group",
            joinColumns = @JoinColumn(name = "operation_id"),
            inverseJoinColumns = @JoinColumn(name = "usrgrp_id"))
    private Set<UserGroup> userGroups = new LinkedHashSet<>();

    /** True when this operation should run at the given escalation step. */
    public boolean appliesToStep(int step) {
        return step >= stepFrom && (stepTo == 0 || step <= stepTo);
    }
}
