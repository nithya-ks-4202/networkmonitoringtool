package com.nms.server.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * A standing instruction to sweep a range of addresses looking for devices.
 *
 * <p>This is what answers "will it find my cameras automatically". It does not
 * create hosts by itself: it reports what is there and what each device looks
 * like, and a person decides what to monitor. Automatic host creation sounds
 * convenient until a scan of an office VLAN silently adds three hundred
 * laptops and a printer to the monitoring estate.
 */
@Entity
@Table(name = "discovery_rule")
@Getter
@Setter
public class DiscoveryRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "drule_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    /**
     * Which collector performs the sweep.
     *
     * <p>Null means the server itself. A camera VLAN is usually not routable
     * from a hosted server, so for anything on a private network this names
     * the proxy that sits inside it -- the same reason the proxy exists at
     * all.
     */
    @ManyToOne
    @JoinColumn(name = "proxy_id")
    private Proxy proxy;

    /** Comma-separated: {@code 10.0.0.1-254, 192.168.1.0/24}. */
    @Column(name = "ip_range", nullable = false)
    private String ipRange;

    @Column(name = "delay_seconds", nullable = false)
    private int delaySeconds = 3600;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EntityStatus status = EntityStatus.ENABLED;

    /**
     * How many addresses are probed at once.
     *
     * <p>Deliberately modest by default. A sweep is the one part of a
     * monitoring system that generates traffic to machines it knows nothing
     * about, and a fast scan of an unfamiliar network looks exactly like a
     * port scan to anything watching.
     */
    @Column(name = "concurrency", nullable = false)
    private int concurrency = 32;

    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DiscoveryCheck> checks = new ArrayList<>();
}
