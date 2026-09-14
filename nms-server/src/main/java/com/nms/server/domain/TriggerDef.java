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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A boolean expression over collected values that defines a problem.
 *
 * <p>Named {@code TriggerDef} rather than {@code Trigger} because "trigger" is
 * a reserved word in SQL and a heavily overloaded one in a database-backed
 * application.
 */
@Entity
@Table(name = "trigger_def")
@Getter
@Setter
public class TriggerDef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "trigger_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /**
     * The host this trigger belongs to. A trigger may reference items on other
     * hosts -- that is how "the switch is down" suppresses the cameras behind
     * it -- but it is owned by, and listed under, one.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false)
    private Host host;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "expression", nullable = false)
    private String expression;

    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_mode", nullable = false)
    private RecoveryMode recoveryMode = RecoveryMode.EXPRESSION;

    /**
     * Evaluated to decide recovery when {@code recoveryMode} is
     * RECOVERY_EXPRESSION. This is what implements hysteresis: alert above 90%,
     * recover below 80%, so a metric sitting on the threshold does not produce
     * a problem every polling interval.
     */
    @Column(name = "recovery_expression", nullable = false)
    private String recoveryExpression = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity = Severity.NOT_CLASSIFIED;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EntityStatus status = EntityStatus.ENABLED;

    /**
     * UNKNOWN when the expression could not be evaluated -- a referenced item
     * has no data, or is unsupported. Distinct from OK, because "we do not
     * know" must not be reported as "everything is fine".
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private TriggerState state = TriggerState.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "value", nullable = false)
    private TriggerValue value = TriggerValue.OK;

    @Column(name = "error", nullable = false)
    private String error = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "event_generation", nullable = false)
    private EventGeneration eventGeneration = EventGeneration.SINGLE;

    @Column(name = "manual_close", nullable = false)
    private boolean manualClose = false;

    @Column(name = "url", nullable = false)
    private String url = "";

    @Column(name = "comments", nullable = false)
    private String comments = "";

    /** Operational data shown alongside the problem, with macros expanded. */
    @Column(name = "opdata", nullable = false)
    private String opdata = "";

    /** Overrides {@code description} as the problem's name when set. */
    @Column(name = "event_name", nullable = false)
    private String eventName = "";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_trigger_id")
    private TriggerDef templateTrigger;

    @Column(name = "discovered_by_rule_id")
    private Long discoveredByRuleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "flags", nullable = false)
    private TriggerFlags flags = TriggerFlags.NORMAL;

    @Column(name = "last_change_at")
    private Instant lastChangeAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Items this expression reads, maintained by the parser on save.
     *
     * <p>Without it, a new value would have to scan every trigger to find the
     * ones that care. With it, the lookup is an index hit -- which at tens of
     * thousands of values per second is the difference between working and not.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "trigger_item",
            joinColumns = @JoinColumn(name = "trigger_id"),
            inverseJoinColumns = @JoinColumn(name = "item_id"))
    private Set<Item> items = new LinkedHashSet<>();

    /**
     * Triggers that must be OK for this one to fire.
     *
     * <p>This is the single most effective tool against alert storms: one dead
     * uplink raises one problem instead of one per device behind it.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "trigger_dependency",
            joinColumns = @JoinColumn(name = "trigger_id"),
            inverseJoinColumns = @JoinColumn(name = "depends_on_trigger_id"))
    private Set<TriggerDef> dependencies = new LinkedHashSet<>();

    @OneToMany(mappedBy = "triggerDef", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<TriggerTag> tags = new ArrayList<>();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public boolean isEnabled() {
        return status == EntityStatus.ENABLED;
    }

    /** The name a problem raised by this trigger carries. */
    public String problemName() {
        return eventName.isBlank() ? description : eventName;
    }
}
