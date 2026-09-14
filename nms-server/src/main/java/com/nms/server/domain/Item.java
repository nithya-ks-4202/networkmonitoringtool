package com.nms.server.domain;

import com.nms.common.CheckType;
import com.nms.common.ItemState;
import com.nms.common.ItemValueType;
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
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One metric collected from one host.
 *
 * <p>The item is the unit of scheduling, storage and trigger reference. Its
 * {@code key} is both its identity within the host and, for most check types,
 * the instruction describing what to collect.
 */
@Entity
@Table(name = "item")
@Getter
@Setter
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false)
    private Host host;

    /** Interface to poll over. Null binds to the host's default for the type. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interface_id")
    private HostInterface hostInterface;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "key_", nullable = false)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false)
    private CheckType checkType;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false)
    private ItemValueType valueType = ItemValueType.FLOAT;

    @Column(name = "units", nullable = false)
    private String units = "";

    /** Seconds between polls. Zero means only the custom intervals apply. */
    @Column(name = "delay_seconds", nullable = false)
    private int delaySeconds = 60;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_intervals", nullable = false)
    private List<String> customIntervals = new ArrayList<>();

    @Column(name = "history_days", nullable = false)
    private int historyDays = 31;

    @Column(name = "trend_days", nullable = false)
    private int trendDays = 365;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EntityStatus status = EntityStatus.ENABLED;

    /**
     * Whether the last collection succeeded. An item in NOT_SUPPORTED still
     * exists and is still scheduled -- it simply has no fresh value, and its
     * error is shown so the cause is visible rather than inferred from a gap
     * in a graph.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private ItemState state = ItemState.NORMAL;

    @Column(name = "error", nullable = false)
    private String error = "";

    @Column(name = "description", nullable = false)
    private String description = "";

    /** Check-type specific configuration: OID, URL, RTSP path, script, and so on. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false)
    private Map<String, String> params = new HashMap<>();

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 3;

    /** The template item this one was copied from, when the host links a template. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_item_id")
    private Item templateItem;

    @Column(name = "discovered_by_rule_id")
    private Long discoveredByRuleId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lld_macro_values", nullable = false)
    private Map<String, String> lldMacroValues = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "flags", nullable = false)
    private ItemFlags flags = ItemFlags.NORMAL;

    @Column(name = "next_check_at")
    private Instant nextCheckAt;

    @Column(name = "last_check_at")
    private Instant lastCheckAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("step ASC")
    private List<ItemPreprocessing> preprocessing = new ArrayList<>();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * True when the scheduler should queue this item.
     *
     * <p>Discovery rules are included: they are polled like any other item,
     * and their value happens to be a list of entities rather than a number.
     * Prototypes are excluded because they are patterns, not measurements.
     */
    public boolean isSchedulable() {
        return status == EntityStatus.ENABLED
                && flags != ItemFlags.PROTOTYPE
                && checkType != null
                && checkType.isScheduled();
    }

    /** True when this item's value is a list of discovered entities. */
    public boolean isDiscoveryRule() {
        return flags == ItemFlags.DISCOVERY_RULE;
    }
}
