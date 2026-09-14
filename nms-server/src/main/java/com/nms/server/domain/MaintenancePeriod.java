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

/**
 * When, inside a maintenance's outer window, suppression actually applies.
 *
 * <p>The split exists so a recurring window can be expressed once: "every
 * Sunday 02:00-04:00, for the next six months" is one maintenance with an
 * active range of six months and a weekly period.
 */
@Entity
@Table(name = "maintenance_period")
@Getter
@Setter
public class MaintenancePeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "period_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "maintenance_id", nullable = false)
    private Maintenance maintenance;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false)
    private MaintenancePeriodType periodType = MaintenancePeriodType.ONE_TIME;

    /** Start instant for a one-time period. */
    @Column(name = "start_date")
    private Instant startDate;

    /** Seconds past local midnight, for recurring periods. */
    @Column(name = "start_time_seconds", nullable = false)
    private int startTimeSeconds = 0;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds = 3600;

    /** Recur every N days, weeks or months depending on the type. */
    @Column(name = "every", nullable = false)
    private int every = 1;

    /** Bit mask of weekdays, Monday = bit 0. */
    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek = 0;

    @Column(name = "day_of_month", nullable = false)
    private int dayOfMonth = 0;

    /** Bit mask of months, January = bit 0. */
    @Column(name = "month", nullable = false)
    private int month = 0;
}
