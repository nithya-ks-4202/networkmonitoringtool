package com.nms.server.domain;

import com.nms.common.Severity;
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
 * How to reach one user on one channel, and when they are willing to be.
 *
 * <p>The severity filter and active period are what make a paging system
 * survivable: everything by email all day, only a disaster by SMS at 03:00.
 */
@Entity
@Table(name = "user_media")
@Getter
@Setter
public class UserMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_media_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_type_id", nullable = false)
    private MediaType mediaType;

    @Column(name = "send_to", nullable = false)
    private String sendTo;

    /** Time periods in {@code 1-5,09:00-18:00} form; days are Monday-based. */
    @Column(name = "active_period", nullable = false)
    private String activePeriod = "1-7,00:00-24:00";

    /** Lowest severity delivered on this channel. */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity_filter", nullable = false)
    private Severity severityFilter = Severity.NOT_CLASSIFIED;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;
}
