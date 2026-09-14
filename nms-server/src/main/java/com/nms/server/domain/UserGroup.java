package com.nms.server.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A set of users.
 *
 * <p>Alert operations target groups rather than individuals, so an on-call
 * rotation changes membership in one place instead of every escalation ladder.
 */
@Entity
@Table(name = "user_group")
@Getter
@Setter
public class UserGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "usrgrp_id")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    /** When false, every member is denied sign-in. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "debug_mode", nullable = false)
    private boolean debugMode = false;
}
