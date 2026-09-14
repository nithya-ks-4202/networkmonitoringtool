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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * How to reach a host over one protocol.
 *
 * <p>SNMP credentials live here rather than on the item because they describe
 * the path to the device, not the question being asked. A switch has one
 * community string and two hundred items.
 *
 * <p>Availability is tracked per interface: a camera can answer ICMP while its
 * RTSP endpoint is dead, and collapsing that into a single per-host flag would
 * erase the distinction operators most need.
 */
@Entity
@Table(name = "host_interface")
@Getter
@Setter
public class HostInterface {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "interface_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false)
    private Host host;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private InterfaceType type = InterfaceType.AGENT;

    /** The interface items of this type bind to when they do not name one. */
    @Column(name = "main", nullable = false)
    private boolean main = true;

    @Column(name = "use_ip", nullable = false)
    private boolean useIp = true;

    @Column(name = "ip")
    private String ip;

    @Column(name = "dns")
    private String dns;

    @Column(name = "port", nullable = false)
    private int port;

    @Enumerated(EnumType.STRING)
    @Column(name = "snmp_version")
    private SnmpVersion snmpVersion;

    @Column(name = "snmp_community")
    private String snmpCommunity;

    @Column(name = "snmp_v3_security_name")
    private String snmpSecurityName;

    @Column(name = "snmp_v3_security_level")
    private String snmpSecurityLevel;

    @Column(name = "snmp_v3_auth_protocol")
    private String snmpAuthProtocol;

    @Column(name = "snmp_v3_auth_passphrase")
    private String snmpAuthPassphrase;

    @Column(name = "snmp_v3_priv_protocol")
    private String snmpPrivProtocol;

    @Column(name = "snmp_v3_priv_passphrase")
    private String snmpPrivPassphrase;

    @Column(name = "snmp_v3_context_name")
    private String snmpContextName;

    @Column(name = "snmp_max_repetitions", nullable = false)
    private int snmpMaxRepetitions = 10;

    @Column(name = "snmp_bulk", nullable = false)
    private boolean snmpBulk = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "available", nullable = false)
    private Availability available = Availability.UNKNOWN;

    @Column(name = "error", nullable = false)
    private String error = "";

    @Column(name = "errors_from")
    private Instant errorsFrom;

    /**
     * Set when repeated failures cause this interface to be backed off.
     * Continuing to poll an interface that has refused a hundred consecutive
     * requests wastes the worker pool that healthy hosts are queued behind.
     */
    @Column(name = "disable_until")
    private Instant disableUntil;

    /** Address a poller should connect to, honouring the IP-or-DNS choice. */
    public String connectionAddress() {
        return useIp ? ip : dns;
    }

    /** Effective port, falling back to the protocol default when unset. */
    public int effectivePort() {
        return port > 0 ? port : type.defaultPort();
    }

    /**
     * SNMP settings rendered as poller parameters.
     *
     * <p>The collector module cannot see this entity -- it also runs inside a
     * proxy with no database -- so credentials travel with the check request.
     */
    public Map<String, String> snmpParams() {
        Map<String, String> params = new HashMap<>();
        if (type != InterfaceType.SNMP) {
            return params;
        }
        params.put("snmpVersion", snmpVersion == null ? "V2C" : snmpVersion.name());
        params.put("snmpPort", Integer.toString(effectivePort()));
        putIfPresent(params, "snmpCommunity", snmpCommunity);
        putIfPresent(params, "snmpSecurityName", snmpSecurityName);
        putIfPresent(params, "snmpSecurityLevel", snmpSecurityLevel);
        putIfPresent(params, "snmpAuthProtocol", snmpAuthProtocol);
        putIfPresent(params, "snmpAuthPassphrase", snmpAuthPassphrase);
        putIfPresent(params, "snmpPrivProtocol", snmpPrivProtocol);
        putIfPresent(params, "snmpPrivPassphrase", snmpPrivPassphrase);
        putIfPresent(params, "snmpContextName", snmpContextName);
        params.put("maxRepetitions", Integer.toString(snmpMaxRepetitions));
        return params;
    }

    private static void putIfPresent(Map<String, String> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.put(key, value);
        }
    }
}
