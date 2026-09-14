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

/**
 * A {@code {$NAME}} substitution defined on one host.
 *
 * <p>Macros are how one template serves a fleet: the template references
 * {@code {$CAMERA.RTSP.PORT}} and each camera overrides it where its firmware
 * differs, without forking the template.
 */
@Entity
@Table(name = "host_macro")
@Getter
@Setter
public class HostMacro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "hostmacro_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false)
    private Host host;

    /** Including the delimiters, e.g. {@code {$SNMP.COMMUNITY}}. */
    @Column(name = "macro", nullable = false)
    private String macro;

    @Column(name = "value", nullable = false)
    private String value = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private MacroType type = MacroType.TEXT;

    @Column(name = "description", nullable = false)
    private String description = "";
}
