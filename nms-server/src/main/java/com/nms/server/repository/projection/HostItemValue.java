package com.nms.server.repository.projection;

import com.nms.common.ItemState;

import java.time.Instant;

/**
 * One host's value for a shared item key.
 *
 * <p>Backs group-wide views such as the camera wall, where the whole grid is
 * one query rather than one per device.
 */
public record HostItemValue(
        Long hostId,
        String hostName,
        String technicalName,
        Long itemId,
        Instant clock,
        Double valueNum,
        String valueStr,
        ItemState state,
        String error) {

    public boolean hasValue() {
        return clock != null;
    }
}
