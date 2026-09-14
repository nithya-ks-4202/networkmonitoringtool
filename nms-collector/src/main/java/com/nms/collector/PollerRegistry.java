package com.nms.collector;

import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Routes a check to the poller that implements its type.
 *
 * <p>Adding a protocol means adding a {@link Poller} bean and nothing else --
 * the registry discovers it, and neither the scheduler nor the proxy needs to
 * know it exists.
 */
@Component
public class PollerRegistry {

    private static final Logger log = LoggerFactory.getLogger(PollerRegistry.class);

    private final Map<CheckType, Poller> pollers = new EnumMap<>(CheckType.class);

    public PollerRegistry(List<Poller> discovered) {
        for (Poller poller : discovered) {
            Poller previous = pollers.put(poller.checkType(), poller);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two pollers claim check type " + poller.checkType() + ": "
                                + previous.getClass().getName() + " and " + poller.getClass().getName());
            }
        }
        log.info("Registered {} pollers: {}", pollers.size(), pollers.keySet());
    }

    /** True when this collector can execute the given check type. */
    public boolean supports(CheckType checkType) {
        return pollers.containsKey(checkType);
    }

    /**
     * Executes a check.
     *
     * <p>Never throws. A poller that fails unexpectedly yields a failed result
     * describing the fault, because one broken protocol implementation must not
     * be able to kill the worker thread that every other item shares.
     */
    public CheckResult poll(CheckRequest request) {
        Poller poller = pollers.get(request.checkType());
        if (poller == null) {
            return CheckResult.failed(request.itemId(),
                    "No poller is registered for check type " + request.checkType()
                            + ". If this item is collected by a proxy, check that the proxy is running.");
        }

        try {
            CheckResult result = poller.poll(request);
            if (result == null) {
                return CheckResult.failed(request.itemId(),
                        poller.getClass().getSimpleName() + " returned no result");
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("Poller {} threw while checking item {} ({} on {})",
                    poller.getClass().getSimpleName(), request.itemId(),
                    request.key(), request.address(), e);
            return CheckResult.failed(request.itemId(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
