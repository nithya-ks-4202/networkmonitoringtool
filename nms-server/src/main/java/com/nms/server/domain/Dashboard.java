package com.nms.server.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A saved arrangement of widgets. */
@Entity
@Table(name = "dashboard")
@Getter
@Setter
public class Dashboard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dashboard_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private AppUser owner;

    @Column(name = "private", nullable = false)
    private boolean privateDashboard = true;

    @Column(name = "auto_refresh_seconds", nullable = false)
    private int autoRefreshSeconds = 30;

    /** Seconds each page is shown for when the dashboard is on a wall display. */
    @Column(name = "display_period_seconds", nullable = false)
    private int displayPeriodSeconds = 30;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "dashboard", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<DashboardWidget> widgets = new ArrayList<>();
}
