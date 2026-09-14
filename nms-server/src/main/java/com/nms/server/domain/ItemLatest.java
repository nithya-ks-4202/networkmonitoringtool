package com.nms.server.domain;

import com.nms.common.ItemState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * The current value of an item.
 *
 * <p>"What is this item's value right now" is the most frequent read in the
 * product: every dashboard tile, every host row, every camera on the wall.
 * Answering it with {@code ORDER BY clock DESC LIMIT 1} over history would mean
 * touching a compressed chunk on each one. This table keeps it a primary-key
 * lookup.
 *
 * <p>The previous value is kept alongside so {@code change()} and
 * {@code diff()} trigger functions, and change-per-second preprocessing, need
 * no history read on the hot path.
 */
@Entity
@Table(name = "item_latest")
@Getter
@Setter
public class ItemLatest {

    /** Shares the item's identifier; there is exactly one row per item. */
    @Id
    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "clock", nullable = false)
    private Instant clock;

    @Column(name = "value_num")
    private Double valueNum;

    @Column(name = "value_str")
    private String valueStr;

    @Column(name = "prev_clock")
    private Instant prevClock;

    @Column(name = "prev_value_num")
    private Double prevValueNum;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private ItemState state = ItemState.NORMAL;

    @Column(name = "error", nullable = false)
    private String error = "";

    /** Human-readable rendering, preferring the string form when present. */
    public String displayValue() {
        if (valueStr != null && !valueStr.isBlank()) {
            return valueStr;
        }
        return valueNum == null ? "" : formatNumber(valueNum);
    }

    private static String formatNumber(double value) {
        // Integral values print without a decimal tail; a camera's 1/0 status
        // reading "1.0" looks like a bug to an operator.
        return value == Math.rint(value) && !Double.isInfinite(value)
                ? Long.toString((long) value)
                : Double.toString(value);
    }
}
