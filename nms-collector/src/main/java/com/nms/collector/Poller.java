package com.nms.collector;

import com.nms.common.CheckRequest;
import com.nms.common.CheckResult;
import com.nms.common.CheckType;

/**
 * Collects the value for one check.
 *
 * <p>Implementations must be thread-safe and stateless with respect to a
 * request: a single poller instance serves every worker thread.
 *
 * <p>A poller must not throw for an unreachable target. "The device did not
 * answer" is an ordinary, expected outcome in monitoring and must come back as
 * a failed {@link CheckResult} so the scheduler can record the reason against
 * the item. Exceptions are reserved for programming errors.
 */
public interface Poller {

    /** Which check type this poller implements. */
    CheckType checkType();

    /**
     * Executes the check.
     *
     * <p>Implementations must honour {@link CheckRequest#timeout()}: a poller
     * that blocks past its timeout occupies a worker thread that thousands of
     * other items are waiting for.
     *
     * @param request the check to perform
     * @return the collected value, or a failed result explaining why not
     */
    CheckResult poll(CheckRequest request);
}
