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

/**
 * A tenant-wide {@code {$NAME}} substitution.
 *
 * <p>Sits at the bottom of the resolution order: a host macro overrides a
 * template macro, which overrides this.
 */
@Entity
@Table(name = "global_macro")
@Getter
@Setter
public class GlobalMacro {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "macro_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

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
