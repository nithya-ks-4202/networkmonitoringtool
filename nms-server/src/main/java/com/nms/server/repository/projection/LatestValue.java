package com.nms.server.repository.projection;

import com.nms.common.ItemState;
import com.nms.common.ItemValueType;

import java.time.Instant;

/**
 * An item's current value together with the metadata needed to render it.
 *
 * <p>A projection rather than an entity graph: the latest-data view wants name,
 * key, units and value in one row, and assembling that from two loaded entities
 * would cost several times the work for the same output.
 */
public record LatestValue(
        Long itemId,
        String name,
        String key,
        String units,
        ItemValueType valueType,
        ItemState state,
        String error,
        Instant clock,
        Double valueNum,
        String valueStr) {

    /** True when a value has ever been collected for this item. */
    public boolean hasValue() {
        return clock != null;
    }
}
