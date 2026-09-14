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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A planned window during which problems on the named hosts are suppressed.
 *
 * <p>Suppressed, not discarded: the problem is still raised and still recorded,
 * it simply does not page anyone. A fault that starts during a reboot window is
 * still a fault, and it needs to be visible the moment the window closes.
 */
@Entity
@Table(name = "maintenance")
@Getter
@Setter
public class Maintenance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "maintenance_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "maintenance_type", nullable = false)
    private MaintenanceType maintenanceType = MaintenanceType.WITH_DATA;

    @Column(name = "active_since", nullable = false)
    private Instant activeSince;

    @Column(name = "active_till", nullable = false)
    private Instant activeTill;

    @Enumerated(EnumType.STRING)
    @Column(name = "tags_eval_type", nullable = false)
    private ConditionEvaluation tagsEvalType = ConditionEvaluation.AND_OR;

    @Column(name = "description", nullable = false)
    private String description = "";

    @OneToMany(mappedBy = "maintenance", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<MaintenancePeriod> periods = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "maintenance_host",
            joinColumns = @JoinColumn(name = "maintenance_id"),
            inverseJoinColumns = @JoinColumn(name = "host_id"))
    private Set<Host> hosts = new LinkedHashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "maintenance_group",
            joinColumns = @JoinColumn(name = "maintenance_id"),
            inverseJoinColumns = @JoinColumn(name = "group_id"))
    private Set<HostGroup> groups = new LinkedHashSet<>();

    /** True when now falls inside the outer active window. */
    public boolean isWithinActiveWindow(Instant now) {
        return !now.isBefore(activeSince) && now.isBefore(activeTill);
    }
}
