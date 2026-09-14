package com.nms.server.trigger;

import com.nms.common.ItemValueType;
import com.nms.server.history.HistoryQueryService;
import com.nms.server.history.HistoryQueryService.Sample;
import com.nms.server.trigger.expression.EvalValue;
import com.nms.server.trigger.expression.ExpressionEvaluator;
import com.nms.server.trigger.expression.ExpressionNode.Argument;
import com.nms.server.trigger.expression.ExpressionNode.Function;
import com.nms.server.trigger.expression.ExpressionNode.ItemReference;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
// java.util.function.Function is referenced by its full name below: the
// expression AST also has a Function node, and importing both would shadow one.
import java.util.function.ToDoubleFunction;

/**
 * Evaluates the history functions a trigger expression can call.
 *
 * <p>Item references are resolved through a supplied lookup rather than a
 * repository, so the whole trigger engine can be exercised in tests with a map
 * of canned values and no database at all.
 */
public class HistoryFunctionContext implements ExpressionEvaluator.FunctionContext {

    /** Default window when a function that accepts one is given none. */
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);

    private final HistoryQueryService history;
    private final ItemLookup itemLookup;
    private final Instant evaluationTime;

    public HistoryFunctionContext(HistoryQueryService history, ItemLookup itemLookup, Instant evaluationTime) {
        this.history = history;
        this.itemLookup = itemLookup;
        this.evaluationTime = evaluationTime;
    }

    @Override
    public EvalValue evaluate(Function function) {
        ResolvedItem item = itemLookup.resolve(function.item());
        if (item == null) {
            // A reference to an item that does not exist is a configuration
            // error. Reported as Unknown so the trigger shows the reason
            // rather than silently reading as OK.
            return EvalValue.unknown("unknown item " + function.item());
        }

        return switch (function.name()) {
            case "last" -> last(item, function.arguments());
            case "prev" -> previous(item);
            case "min" -> aggregate(item, function.arguments(), samples ->
                    samples.stream().mapToDouble(Sample::value).min().orElse(Double.NaN));
            case "max" -> aggregate(item, function.arguments(), samples ->
                    samples.stream().mapToDouble(Sample::value).max().orElse(Double.NaN));
            case "avg" -> aggregate(item, function.arguments(), samples ->
                    samples.stream().mapToDouble(Sample::value).average().orElse(Double.NaN));
            case "sum" -> aggregate(item, function.arguments(), samples ->
                    samples.stream().mapToDouble(Sample::value).sum());
            case "count" -> count(item, function.arguments());
            case "change" -> change(item);
            case "diff" -> diff(item);
            case "abs" -> absoluteLast(item);
            case "nodata" -> noData(item, function.arguments());
            case "percentile" -> percentile(item, function.arguments());
            case "fuzzytime" -> fuzzyTime(item, function.arguments());
            case "timeleft" -> EvalValue.unknown("timeleft() is not implemented in this build");
            default -> EvalValue.unknown("unknown function '" + function.name() + "()'");
        };
    }

    /** The most recent value. */
    private EvalValue last(ResolvedItem item, List<Argument> arguments) {
        // last(/host/key,#3) means the third most recent value, not the last
        // three: the shift is what lets an expression compare a value against
        // one from several samples ago.
        int shift = arguments.isEmpty() ? 1 : Math.max(1, valueCount(arguments.get(0), 1));

        if (!item.valueType().isNumeric()) {
            List<HistoryQueryService.TextSample> textSamples =
                    history.lastTextValues(item.itemId(), item.valueType(), shift);
            return textSamples.size() < shift
                    ? EvalValue.unknown("no value for " + item.reference())
                    : new EvalValue.Text(textSamples.get(shift - 1).value());
        }

        List<Sample> samples = history.lastNumericValues(item.itemId(), item.valueType(), shift);
        return samples.size() < shift
                ? EvalValue.unknown("no value for " + item.reference())
                : EvalValue.of(samples.get(shift - 1).value());
    }

    /** The value before the most recent one. */
    private EvalValue previous(ResolvedItem item) {
        List<Sample> samples = history.lastNumericValues(item.itemId(), item.valueType(), 2);
        return samples.size() < 2
                ? EvalValue.unknown("no previous value for " + item.reference())
                : EvalValue.of(samples.get(1).value());
    }

    /**
     * Applies an aggregate over a window.
     *
     * <p>An empty window yields Unknown rather than zero. {@code max(...)=0} on
     * a camera with no data would otherwise read as "the camera is down"
     * regardless of whether it is -- an alert derived from the absence of
     * evidence rather than from evidence.
     */
    private EvalValue aggregate(ResolvedItem item, List<Argument> arguments,
                                ToDoubleFunction<List<Sample>> reducer) {
        List<Sample> samples = window(item, arguments);
        if (samples.isEmpty()) {
            return EvalValue.unknown("no data for " + item.reference() + " in the requested window");
        }
        double result = reducer.applyAsDouble(samples);
        return Double.isNaN(result)
                ? EvalValue.unknown("no numeric data for " + item.reference())
                : EvalValue.of(result);
    }

    /** Number of values in a window, optionally matching a comparison. */
    private EvalValue count(ResolvedItem item, List<Argument> arguments) {
        List<Sample> samples = window(item, arguments);
        if (arguments.size() < 2) {
            // count() legitimately returns 0 for an empty window: "how many
            // values" has a correct answer even when there are none.
            return EvalValue.of(samples.size());
        }

        String operator = arguments.get(1).text().trim();
        Double threshold = arguments.size() > 2 ? parseDouble(arguments.get(2).text()) : null;
        if (threshold == null) {
            return EvalValue.unknown("count() needs a numeric threshold with its operator");
        }

        long matching = samples.stream().filter(sample -> switch (operator) {
            case "eq", "=" -> sample.value() == threshold;
            case "ne", "<>" -> sample.value() != threshold;
            case "gt", ">" -> sample.value() > threshold;
            case "ge", ">=" -> sample.value() >= threshold;
            case "lt", "<" -> sample.value() < threshold;
            case "le", "<=" -> sample.value() <= threshold;
            default -> false;
        }).count();

        return EvalValue.of(matching);
    }

    /** Difference between the last two values. */
    private EvalValue change(ResolvedItem item) {
        List<Sample> samples = history.lastNumericValues(item.itemId(), item.valueType(), 2);
        return samples.size() < 2
                ? EvalValue.unknown("fewer than two values for " + item.reference())
                : EvalValue.of(samples.get(0).value() - samples.get(1).value());
    }

    /** 1 when the last two values differ, 0 when they are the same. */
    private EvalValue diff(ResolvedItem item) {
        List<Sample> samples = history.lastNumericValues(item.itemId(), item.valueType(), 2);
        return samples.size() < 2
                ? EvalValue.unknown("fewer than two values for " + item.reference())
                : EvalValue.of(samples.get(0).value() != samples.get(1).value());
    }

    private EvalValue absoluteLast(ResolvedItem item) {
        List<Sample> samples = history.lastNumericValues(item.itemId(), item.valueType(), 1);
        return samples.isEmpty()
                ? EvalValue.unknown("no value for " + item.reference())
                : EvalValue.of(Math.abs(samples.get(0).value()));
    }

    /**
     * 1 when nothing has been collected for the given period.
     *
     * <p>The one function whose answer is a definite 0 or 1 even with no data:
     * silence is exactly what it measures. It is how "the agent stopped
     * responding" is distinguished from "the agent says everything is fine".
     */
    private EvalValue noData(ResolvedItem item, List<Argument> arguments) {
        Duration window = windowDuration(arguments, DEFAULT_WINDOW);
        return history.lastValueClock(item.itemId())
                .map(clock -> EvalValue.of(clock.isBefore(evaluationTime.minus(window))))
                // Never collected at all, which for this function is the
                // strongest possible "no data".
                .orElse(EvalValue.TRUE);
    }

    /** The nth percentile of a window. */
    private EvalValue percentile(ResolvedItem item, List<Argument> arguments) {
        List<Sample> samples = window(item, arguments);
        if (samples.isEmpty()) {
            return EvalValue.unknown("no data for " + item.reference() + " in the requested window");
        }
        Double rank = arguments.size() > 1 ? parseDouble(arguments.get(1).text()) : null;
        if (rank == null || rank < 0 || rank > 100) {
            return EvalValue.unknown("percentile() needs a rank between 0 and 100");
        }

        List<Double> sorted = samples.stream().map(Sample::value).sorted(Comparator.naturalOrder()).toList();
        // Nearest-rank: the smallest value at or above the given percentage of
        // the ordered set. Exact and defensible, unlike interpolation, which
        // can report a latency figure no request ever experienced.
        int index = (int) Math.ceil(rank / 100.0 * sorted.size()) - 1;
        return EvalValue.of(sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))));
    }

    /** 1 when the item's value, read as a timestamp, is close to now. */
    private EvalValue fuzzyTime(ResolvedItem item, List<Argument> arguments) {
        Duration tolerance = windowDuration(arguments, Duration.ofSeconds(60));
        List<Sample> samples = history.lastNumericValues(item.itemId(), item.valueType(), 1);
        if (samples.isEmpty()) {
            return EvalValue.unknown("no value for " + item.reference());
        }
        long reported = (long) samples.get(0).value();
        long difference = Math.abs(evaluationTime.getEpochSecond() - reported);
        return EvalValue.of(difference <= tolerance.toSeconds());
    }

    /** Reads a window argument as either a count of values or a period. */
    private List<Sample> window(ResolvedItem item, List<Argument> arguments) {
        if (arguments.isEmpty()) {
            return history.numericWindow(item.itemId(), item.valueType(),
                    evaluationTime.minus(DEFAULT_WINDOW), evaluationTime.plusSeconds(1));
        }

        Argument first = arguments.get(0);
        if (first.isValueCount()) {
            return history.lastNumericValues(item.itemId(), item.valueType(), first.valueCount());
        }

        Duration window = first.seconds() == null
                ? DEFAULT_WINDOW
                : Duration.ofSeconds(Math.max(1, first.seconds()));
        // The upper bound is nudged past now so a value written in this same
        // second is included; an exclusive bound at exactly now would miss the
        // value that triggered this evaluation.
        return history.numericWindow(item.itemId(), item.valueType(),
                evaluationTime.minus(window), evaluationTime.plusSeconds(1));
    }

    private static Duration windowDuration(List<Argument> arguments, Duration fallback) {
        if (arguments.isEmpty() || arguments.get(0).seconds() == null) {
            return fallback;
        }
        return Duration.ofSeconds(Math.max(1, arguments.get(0).seconds()));
    }

    private static int valueCount(Argument argument, int fallback) {
        return argument.isValueCount() ? argument.valueCount() : fallback;
    }

    private static Double parseDouble(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    /** Resolves {@code /host/key} references to stored items. */
    public interface ItemLookup {
        ResolvedItem resolve(ItemReference reference);

        /** A lookup backed by a prepared map, for tests and for cached evaluation. */
        static ItemLookup of(Map<ItemReference, ResolvedItem> items) {
            return items::get;
        }
    }

    /**
     * An item reference resolved to a stored item.
     *
     * @param itemId    identifier used to read history
     * @param valueType decides which history table holds its values
     * @param reference the original reference, for error messages
     */
    public record ResolvedItem(long itemId, ItemValueType valueType, ItemReference reference) {
    }
}
