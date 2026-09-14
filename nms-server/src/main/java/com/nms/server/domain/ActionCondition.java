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

/** One test an event must satisfy for its action to run. */
@Entity
@Table(name = "action_condition")
@Getter
@Setter
public class ActionCondition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "condition_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "action_id", nullable = false)
    private Action action;

    /** Short label referenced by a custom formula. */
    @Column(name = "label", nullable = false)
    private String label = "A";

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false)
    private ActionConditionType conditionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "operator", nullable = false)
    private ConditionOperator operator = ConditionOperator.EQUALS;

    @Column(name = "value", nullable = false)
    private String value = "";

    /** Second operand; holds the tag value when {@code value} holds the tag. */
    @Column(name = "value2", nullable = false)
    private String value2 = "";
}
