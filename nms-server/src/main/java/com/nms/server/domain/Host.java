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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A monitored entity, a template, or a prototype for one.
 *
 * <p>Templates are hosts that are never polled. Keeping them in the same table
 * is what makes linking a template a reference operation rather than a schema
 * translation: a template's items are items, and copying them onto a host is a
 * straight copy.
 */
@Entity
@Table(name = "host")
@Getter
@Setter
public class Host {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "host_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** Technical name, referenced by trigger expressions. Unique per tenant. */
    @Column(name = "host", nullable = false)
    private String technicalName;

    /** Name shown in the interface; defaults to the technical name. */
    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "flags", nullable = false)
    private HostFlags flags = HostFlags.MONITORED;

    @Enumerated(EnumType.STRING)
    @Column(name = "host_class", nullable = false)
    private HostClass hostClass = HostClass.GENERIC;

    @Column(name = "description", nullable = false)
    private String description = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EntityStatus status = EntityStatus.ENABLED;

    /**
     * Collector responsible for this host. Null means the server polls it
     * directly, which only works when the server can reach the host -- for a
     * cloud deployment that means public endpoints only.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proxy_id")
    private Proxy proxy;

    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_mode", nullable = false)
    private InventoryMode inventoryMode = InventoryMode.MANUAL;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inventory", nullable = false)
    private Map<String, String> inventory = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "maintenance_status", nullable = false)
    private MaintenanceStatus maintenanceStatus = MaintenanceStatus.NONE;

    @Column(name = "maintenance_from")
    private Instant maintenanceFrom;

    @Column(name = "discovered_by_rule_id")
    private Long discoveredByRuleId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "host", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<HostInterface> interfaces = new ArrayList<>();

    @OneToMany(mappedBy = "host", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<HostMacro> macros = new ArrayList<>();

    @OneToMany(mappedBy = "host", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<HostTag> tags = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "host_group_member",
            joinColumns = @JoinColumn(name = "host_id"),
            inverseJoinColumns = @JoinColumn(name = "group_id"))
    private Set<HostGroup> groups = new LinkedHashSet<>();

    /** Templates linked to this host. Self-referential: templates nest. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "host_template",
            joinColumns = @JoinColumn(name = "host_id"),
            inverseJoinColumns = @JoinColumn(name = "template_id"))
    private Set<Host> templates = new LinkedHashSet<>();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /** True when this host should be scheduled for collection. */
    public boolean isMonitored() {
        return flags == HostFlags.MONITORED && status == EntityStatus.ENABLED;
    }

    /**
     * The default interface of a given type, which is what an item binds to
     * when it does not name one explicitly.
     */
    public Optional<HostInterface> mainInterface(InterfaceType type) {
        return interfaces.stream()
                .filter(i -> i.getType() == type && i.isMain())
                .findFirst();
    }

    /**
     * Address used to reach this host, preferring the requested interface type
     * and falling back to any interface that has one. A camera monitored only
     * over RTSP still needs an address for its ICMP item.
     */
    public Optional<String> connectionAddress(InterfaceType preferred) {
        return mainInterface(preferred)
                .or(() -> interfaces.stream().filter(HostInterface::isMain).findFirst())
                .or(() -> interfaces.stream().findFirst())
                .map(HostInterface::connectionAddress);
    }
}
