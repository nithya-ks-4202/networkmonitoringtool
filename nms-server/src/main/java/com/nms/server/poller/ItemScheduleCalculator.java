package com.nms.server.poller;

import com.nms.server.domain.Item;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides when an item should next be collected.
 *
 * <p>The next time is derived from the item's identifier rather than from
 * "now plus the interval". That distributes items evenly across each interval
 * instead of letting them bunch: without it, every item created in one bulk
 * import, or every item on a server that just restarted, would fire in the same
 * second and leave the rest of the minute idle. Spreading them keeps the load on
 * the collector, the network and the monitored devices flat.
 */
@Component
public class ItemScheduleCalculator {

    /** Shortest interval accepted, to stop a misconfiguration saturating the pool. */
    private static final int MIN_DELAY_SECONDS = 1;

    /** How long an item with no interval at all waits before being looked at again. */
    private static final Duration NO_SCHEDULE_FALLBACK = Duration.ofHours(1);

    /**
     * Computes the next check time for an item.
     *
     * @param item the item just polled, or being scheduled for the first time
     * @param now  the reference instant
     */
    public Instant nextCheck(Item item, Instant now) {
        int delaySeconds = item.getDelaySeconds();
        if (delaySeconds <= 0) {
            // Zero means "custom intervals only". Until those are configured
            // the item still needs some cadence, or it would be re-claimed on
            // every single dispatch pass.
            return now.plus(NO_SCHEDULE_FALLBACK);
        }

        delaySeconds = Math.max(MIN_DELAY_SECONDS, delaySeconds);

        // Place the item at a stable offset within its interval, derived from
        // its id. The same item always lands in the same slot, so its samples
        // stay evenly spaced rather than drifting by the poll duration each
        // cycle -- which matters for rate calculations over counters.
        long offset = Math.floorMod(item.getId() == null ? 0L : item.getId(), delaySeconds);
        long epochSecond = now.getEpochSecond();
        long slotStart = epochSecond - Math.floorMod(epochSecond - offset, delaySeconds);
        long next = slotStart + delaySeconds;

        // A check that took longer than its own interval would otherwise be
        // scheduled in the past and re-run immediately, forever.
        while (next <= epochSecond) {
            next += delaySeconds;
        }

        return Instant.ofEpochSecond(next);
    }

    /**
     * Next check time for an item whose collection failed.
     *
     * <p>Backs off so a host that is down, or an item that is misconfigured,
     * does not consume a worker slot at full rate while healthy items queue
     * behind it. The back-off is capped: an availability item on a dead camera
     * must keep reporting that it is dead, and the recovery has to be noticed
     * promptly.
     *
     * @param consecutiveFailures how many times in a row this item has failed
     */
    public Instant nextCheckAfterFailure(Item item, Instant now, int consecutiveFailures) {
        int base = Math.max(MIN_DELAY_SECONDS, item.getDelaySeconds());
        if (consecutiveFailures <= 1) {
            return nextCheck(item, now);
        }

        // Doubling, capped at four intervals or ten minutes, whichever is
        // smaller. Beyond that the data becomes too sparse to alert on.
        long multiplier = Math.min(4, 1L << Math.min(2, consecutiveFailures - 1));
        long backoffSeconds = Math.min(base * multiplier, Duration.ofMinutes(10).toSeconds());
        return now.plusSeconds(Math.max(base, backoffSeconds));
    }
}
