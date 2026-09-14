package com.nms.server.trigger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nms.server.domain.EntityStatus;
import com.nms.server.domain.Item;
import com.nms.server.domain.RecoveryMode;
import com.nms.server.domain.TriggerDef;
import com.nms.server.domain.TriggerState;
import com.nms.server.domain.TriggerValue;
import com.nms.server.history.HistoryQueryService;
import com.nms.server.repository.TriggerRepository;
import com.nms.server.trigger.expression.EvalValue;
import com.nms.server.trigger.expression.ExpressionEvaluator;
import com.nms.server.trigger.expression.ExpressionNode;
import com.nms.server.trigger.expression.ExpressionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the triggers that read a given item and records any state change.
 *
 * <p>Parsed expressions are cached: parsing is pure CPU work whose result
 * changes only when someone edits the trigger, and at thousands of values a
 * second re-parsing would dominate the cost of evaluation.
 */
@Service
public class TriggerEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TriggerEvaluator.class);

    private final TriggerRepository triggers;
    private final HistoryQueryService history;
    private final ItemReferenceResolver referenceResolver;
    private final ProblemService problemService;

    /** triggerId to its parsed expression and recovery expression. */
    private final Cache<Long, CompiledTrigger> compiled = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    public TriggerEvaluator(TriggerRepository triggers,
                            HistoryQueryService history,
                            ItemReferenceResolver referenceResolver,
                            ProblemService problemService) {
        this.triggers = triggers;
        this.history = history;
        this.referenceResolver = referenceResolver;
        this.problemService = problemService;
    }

    /**
     * Evaluates every enabled trigger that reads this item.
     *
     * @param itemId the item that just produced a value
     * @param clock  the value's collection time, used as the evaluation instant
     *               so a backlog replayed by a proxy is judged against the time
     *               the data describes rather than the time it arrived
     */
    @Transactional
    public void evaluateForItem(long itemId, Instant clock) {
        List<TriggerDef> affected = triggers.findEnabledByItemId(itemId);
        for (TriggerDef trigger : affected) {
            try {
                evaluate(trigger, clock);
            } catch (RuntimeException e) {
                // One broken trigger must not stop the others on the same
                // item from being evaluated.
                log.error("Failed to evaluate trigger {} ({}): {}",
                        trigger.getId(), trigger.getDescription(), e.getMessage(), e);
                recordUnknown(trigger, e.getMessage(), clock);
            }
        }
    }

    /** Evaluates one trigger and applies the outcome. */
    public void evaluate(TriggerDef trigger, Instant clock) {
        CompiledTrigger expression = compiled.get(trigger.getId(), id -> compile(trigger));
        if (expression.parseError() != null) {
            recordUnknown(trigger, expression.parseError(), clock);
            return;
        }

        HistoryFunctionContext context = new HistoryFunctionContext(
                history, referenceResolver.lookupFor(trigger), clock);
        ExpressionEvaluator evaluator = new ExpressionEvaluator(context);

        EvalValue result = evaluator.evaluate(expression.problemExpression());

        if (result.isUnknown()) {
            // Deliberately not a state change to OK. An expression that cannot
            // be evaluated says nothing about whether the thing it watches is
            // healthy, and reporting OK would be the monitoring equivalent of
            // an all-clear because the sensor was unplugged.
            recordUnknown(trigger, ((EvalValue.Unknown) result).reason(), clock);
            return;
        }

        boolean problemNow = result.isTrue();
        TriggerValue previousValue = trigger.getValue();

        if (problemNow && previousValue == TriggerValue.PROBLEM) {
            // Already firing; nothing changes. This is the common case for a
            // sustained outage and must stay cheap.
            clearErrorIfSet(trigger, clock);
            return;
        }

        if (problemNow) {
            transitionToProblem(trigger, clock);
            return;
        }

        if (previousValue == TriggerValue.PROBLEM) {
            handleRecovery(trigger, expression, evaluator, clock);
            return;
        }

        clearErrorIfSet(trigger, clock);
    }

    /**
     * Decides whether a trigger currently in PROBLEM should recover.
     *
     * <p>With a recovery expression the problem stays open until that
     * expression becomes true, not merely until the problem expression becomes
     * false. That gap is hysteresis, and it is what stops a metric hovering on
     * a threshold from producing a problem and a recovery every polling
     * interval -- the single most common cause of alert fatigue.
     */
    private void handleRecovery(TriggerDef trigger, CompiledTrigger expression,
                                ExpressionEvaluator evaluator, Instant clock) {
        if (trigger.getRecoveryMode() == RecoveryMode.NONE) {
            // Closes only by hand. Used where an operator must confirm the
            // condition was dealt with rather than merely stopped showing.
            return;
        }

        if (trigger.getRecoveryMode() == RecoveryMode.RECOVERY_EXPRESSION) {
            if (expression.recoveryExpression() == null) {
                recordUnknown(trigger, "recovery mode is RECOVERY_EXPRESSION but no expression is set", clock);
                return;
            }
            EvalValue recovered = evaluator.evaluate(expression.recoveryExpression());
            if (recovered.isUnknown()) {
                recordUnknown(trigger, ((EvalValue.Unknown) recovered).reason(), clock);
                return;
            }
            if (!recovered.isTrue()) {
                return;
            }
        }

        transitionToOk(trigger, clock);
    }

    private void transitionToProblem(TriggerDef trigger, Instant clock) {
        triggers.updateValue(trigger.getId(), TriggerValue.PROBLEM, TriggerState.NORMAL, "", clock);
        trigger.setValue(TriggerValue.PROBLEM);

        if (isSuppressedByDependency(trigger)) {
            // The cause is already reported by whatever this depends on. The
            // trigger still holds PROBLEM -- it really is in that state -- but
            // no problem is raised, so one dead uplink produces one incident
            // rather than one per device behind it.
            log.debug("Trigger {} fired but is suppressed by a dependency", trigger.getId());
            return;
        }

        problemService.openProblem(trigger, clock);
    }

    private void transitionToOk(TriggerDef trigger, Instant clock) {
        triggers.updateValue(trigger.getId(), TriggerValue.OK, TriggerState.NORMAL, "", clock);
        trigger.setValue(TriggerValue.OK);
        problemService.resolveProblems(trigger, clock);
    }

    /**
     * True when a trigger this one depends on is currently in PROBLEM.
     *
     * <p>Walks transitively, so a chain of site, switch and camera suppresses
     * correctly. Depth is bounded because a dependency cycle -- which the
     * configuration API rejects, but which could still arrive through a direct
     * database edit -- would otherwise loop forever on the alerting path.
     */
    private boolean isSuppressedByDependency(TriggerDef trigger) {
        return hasFiringDependency(trigger, 0);
    }

    private boolean hasFiringDependency(TriggerDef trigger, int depth) {
        if (depth > 16) {
            log.warn("Trigger {} has a dependency chain deeper than 16; not descending further",
                    trigger.getId());
            return false;
        }
        for (TriggerDef dependency : trigger.getDependencies()) {
            if (dependency.getStatus() != EntityStatus.ENABLED) {
                continue;
            }
            if (dependency.getValue() == TriggerValue.PROBLEM) {
                return true;
            }
            if (hasFiringDependency(dependency, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private void recordUnknown(TriggerDef trigger, String reason, Instant clock) {
        if (trigger.getState() == TriggerState.UNKNOWN
                && reason != null && reason.equals(trigger.getError())) {
            // Already recorded with the same reason; writing it again on every
            // evaluation would be a pointless update per collected value.
            return;
        }
        String message = reason == null ? "expression could not be evaluated" : abbreviate(reason);
        triggers.updateValue(trigger.getId(), trigger.getValue(), TriggerState.UNKNOWN, message, clock);
        trigger.setState(TriggerState.UNKNOWN);
        trigger.setError(message);
    }

    private void clearErrorIfSet(TriggerDef trigger, Instant clock) {
        if (trigger.getState() == TriggerState.UNKNOWN) {
            triggers.updateValue(trigger.getId(), trigger.getValue(), TriggerState.NORMAL, "", clock);
            trigger.setState(TriggerState.NORMAL);
            trigger.setError("");
        }
    }

    private CompiledTrigger compile(TriggerDef trigger) {
        try {
            ExpressionNode problem = ExpressionParser.parse(trigger.getExpression());
            ExpressionNode recovery = trigger.getRecoveryExpression().isBlank()
                    ? null
                    : ExpressionParser.parse(trigger.getRecoveryExpression());
            return new CompiledTrigger(problem, recovery, null);
        } catch (ExpressionParser.ExpressionException e) {
            return new CompiledTrigger(null, null, e.getMessage());
        }
    }

    /** Drops a cached expression so an edit takes effect immediately. */
    public void invalidate(long triggerId) {
        compiled.invalidate(triggerId);
    }

    private static String abbreviate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }

    /**
     * A trigger's parsed expressions, or the reason they could not be parsed.
     *
     * <p>A parse failure is cached alongside a success so a malformed
     * expression is not re-parsed on every collected value just to fail again.
     */
    private record CompiledTrigger(ExpressionNode problemExpression,
                                   ExpressionNode recoveryExpression,
                                   String parseError) {
    }

    /** Resolves the item references inside a trigger's expression. */
    public interface ItemReferenceResolver {
        HistoryFunctionContext.ItemLookup lookupFor(TriggerDef trigger);
    }

    /** Builds a lookup from the items already associated with a trigger. */
    public static HistoryFunctionContext.ItemLookup lookupFromItems(List<Item> items) {
        Map<ExpressionNode.ItemReference, HistoryFunctionContext.ResolvedItem> byReference = new HashMap<>();
        for (Item item : items) {
            ExpressionNode.ItemReference reference =
                    new ExpressionNode.ItemReference(item.getHost().getTechnicalName(), item.getKey());
            byReference.put(reference,
                    new HistoryFunctionContext.ResolvedItem(item.getId(), item.getValueType(), reference));
        }
        return HistoryFunctionContext.ItemLookup.of(byReference);
    }
}
