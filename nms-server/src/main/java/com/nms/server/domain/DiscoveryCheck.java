package com.nms.server.domain;

import com.nms.common.CheckType;
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

import java.util.HashMap;
import java.util.Map;

/** One probe performed against every address in a rule's range. */
@Entity
@Table(name = "discovery_check")
@Getter
@Setter
public class DiscoveryCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "dcheck_id")
    private Long id;

    @ManyToOne
    @JoinColumn(name = "drule_id", nullable = false)
    private DiscoveryRule rule;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false)
    private CheckType checkType = CheckType.ICMP_PING;

    /**
     * Comma-separated ports, for the check types that need them.
     *
     * <p>Several per check rather than one check per port: an operator thinks
     * "look for web interfaces on 80 and 443", not "run two checks".
     */
    @Column(name = "ports", nullable = false)
    private String ports = "";

    /** SNMP OID, HTTP path -- whatever the check type reads. */
    @Column(name = "key_", nullable = false)
    private String key = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false)
    private Map<String, String> params = new HashMap<>();

    @Column(name = "uniq", nullable = false)
    private boolean uniq = false;

    @Column(name = "host_source", nullable = false)
    private boolean hostSource = false;

    /** Use this check's value as the discovered device's display name. */
    @Column(name = "name_source", nullable = false)
    private boolean nameSource = false;
}
