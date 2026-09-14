package com.nms.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;

/**
 * An on-premise collector.
 *
 * <p>This is what makes a cloud-hosted platform able to monitor a private
 * network. Cameras, switches and servers on a customer LAN are not reachable
 * from the internet, and opening inbound firewall rules to a monitoring vendor
 * is not something a security team will accept. A proxy sits inside the
 * network, polls locally, and pushes results out over an ordinary outbound
 * HTTPS connection.
 */
@Entity
@Table(name = "proxy")
@Getter
@Setter
public class Proxy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "proxy_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", nullable = false)
    private String description = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false)
    private ProxyMode mode = ProxyMode.ACTIVE;

    /** Only meaningful in PASSIVE mode, where the server dials the proxy. */
    @Column(name = "address")
    private String address;

    @Column(name = "port", nullable = false)
    private int port = 10151;

    /** Bcrypt hash of the enrolment token; the plaintext is shown once. */
    @Column(name = "token_hash")
    private String tokenHash;

    /**
     * First characters of the token, stored in clear.
     *
     * <p>Bcrypt cannot be searched, so without a prefix every incoming request
     * would have to be verified against every proxy's hash in turn -- which is
     * both slow and a timing oracle. The prefix narrows it to one candidate.
     */
    @Column(name = "token_prefix")
    private String tokenPrefix;

    @Column(name = "tls_accept", nullable = false)
    private String tlsAccept = "TOKEN";

    /**
     * Bumped whenever this proxy's item assignment changes, so the proxy can
     * ask "is my configuration still current?" without transferring it.
     */
    @Column(name = "config_revision", nullable = false)
    private long configRevision = 1;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_config_at")
    private Instant lastConfigAt;

    @Column(name = "version")
    private String version;

    /** Results buffered on the proxy; a rising value means uploads are failing. */
    @Column(name = "queue_depth", nullable = false)
    private int queueDepth = 0;

    @Column(name = "clock_skew_ms", nullable = false)
    private long clockSkewMs = 0;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * True when the proxy has reported recently enough to be trusted.
     *
     * @param threshold how long silence is tolerated before it counts as offline
     */
    public boolean isOnline(Duration threshold) {
        return lastSeenAt != null && lastSeenAt.isAfter(Instant.now().minus(threshold));
    }
}
