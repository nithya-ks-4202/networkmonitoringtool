package com.nms.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A device a sweep found at an address.
 *
 * <p>Not a monitored host. It is a sighting: something answered here, and this
 * is what it said. It becomes a host only when someone chooses to monitor it,
 * at which point {@link #host} records the link so the same device is not
 * offered again on the next sweep.
 */
@Entity
@Table(name = "discovered_host")
@Getter
@Setter
public class DiscoveredHost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dhost_id")
    private Long id;

    @ManyToOne
    @JoinColumn(name = "drule_id", nullable = false)
    private DiscoveryRule rule;

    @Column(name = "ip", nullable = false)
    private String ip;

    @Column(name = "dns", nullable = false)
    private String dns = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DiscoveredHostStatus status = DiscoveredHostStatus.UP;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    @Column(name = "last_down_at")
    private Instant lastDownAt;

    /**
     * What each probe returned, keyed by probe.
     *
     * <p>Kept verbatim rather than reduced to a verdict, because the useful
     * question later is usually "why did it think that" -- an SNMP sysDescr
     * or an ONVIF model string identifies a device far more precisely than
     * the class guessed from it.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "check_results", nullable = false)
    private Map<String, String> checkResults = new HashMap<>();

    /** Set once this device has been turned into a monitored host. */
    @ManyToOne
    @JoinColumn(name = "host_id")
    private Host host;
}
