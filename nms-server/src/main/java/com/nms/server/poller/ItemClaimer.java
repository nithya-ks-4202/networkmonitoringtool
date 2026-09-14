package com.nms.server.poller;

import com.nms.collector.PollerRegistry;
import com.nms.common.CheckRequest;
import com.nms.server.domain.Item;
import com.nms.server.repository.ItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Claims due items and prepares them for execution.
 *
 * <p>A separate bean from {@link PollerScheduler} on purpose. Spring's
 * transaction support works through a proxy, so a {@code @Transactional} method
 * called from another method of the same class runs with no transaction at all
 * -- and this one must be transactional, because the claim and the reschedule
 * have to commit together or an item can be handed to two workers.
 */
@Component
public class ItemClaimer {

    private static final Logger log = LoggerFactory.getLogger(ItemClaimer.class);

    private final ItemRepository items;
    private final CheckRequestFactory requestFactory;
    private final PollerRegistry pollers;
    private final ValueProcessor valueProcessor;
    private final ItemScheduleCalculator scheduleCalculator;

    public ItemClaimer(ItemRepository items,
                       CheckRequestFactory requestFactory,
                       PollerRegistry pollers,
                       ValueProcessor valueProcessor,
                       ItemScheduleCalculator scheduleCalculator) {
        this.items = items;
        this.requestFactory = requestFactory;
        this.pollers = pollers;
        this.valueProcessor = valueProcessor;
        this.scheduleCalculator = scheduleCalculator;
    }

    /**
     * Claims up to {@code batchSize} due items, moves their next check forward,
     * and returns the requests that can be executed here.
     *
     * <p>Requests are built inside the transaction because doing so touches the
     * host, its interfaces and its macros; the worker threads run outside this
     * session and would hit a lazy-initialisation failure.
     *
     * @return requests ready to hand to the worker pool
     */
    @Transactional
    public List<CheckRequest> claim(int batchSize) {
        Instant now = Instant.now();
        List<Item> due = items.claimDueItems(now, null, batchSize);
        if (due.isEmpty()) {
            return List.of();
        }

        List<CheckRequest> requests = new ArrayList<>(due.size());
        for (Item item : due) {
            // Rescheduled whatever happens next, so a permanently
            // misconfigured item does not spin at full speed.
            items.reschedule(item.getId(), scheduleCalculator.nextCheck(item, now), now);

            if (!pollers.supports(item.getCheckType())) {
                // Collected by a proxy, or pushed by an agent. Not an error:
                // the value will arrive through another path.
                continue;
            }

            try {
                requests.add(requestFactory.build(item));
            } catch (CheckRequestFactory.UncollectableItemException e) {
                // A configuration fault rather than a device fault. Recorded
                // against the item so the reason is visible in the interface
                // instead of showing up as an unexplained gap in a graph.
                valueProcessor.recordFailure(item.getId(), e.getMessage());
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("Claimed {} due items, {} executable here", due.size(), requests.size());
        }
        return requests;
    }
}
