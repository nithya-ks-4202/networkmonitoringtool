package com.nms.server.trigger.expression;

/**
 * A value produced during expression evaluation.
 *
 * <p>{@link Unknown} is the reason this is not simply a {@code double}. When a
 * referenced item has no data, the honest answer is "we do not know", and that
 * is not the same as false. Collapsing it to 0 would mean a trigger watching a
 * host that stopped reporting reads as OK -- the monitoring equivalent of
 * reporting all clear because the sensor was unplugged.
 *
 * <p>Unknown propagates through arithmetic and comparison, so any expression
 * touching missing data yields Unknown, and the trigger goes to the UNKNOWN
 * state rather than to OK.
 */
public sealed interface EvalValue {

    /** A number. Truth values are carried as 1 and 0, as in the source syntax. */
    record Number(double value) implements EvalValue {
    }

    /** A string, from a text item or a literal. */
    record Text(String value) implements EvalValue {
    }

    /** No data available; the reason is kept for the trigger's error field. */
    record Unknown(String reason) implements EvalValue {
    }

    EvalValue TRUE = new Number(1);
    EvalValue FALSE = new Number(0);

    static EvalValue of(double value) {
        return new Number(value);
    }

    static EvalValue of(boolean value) {
        return value ? TRUE : FALSE;
    }

    static EvalValue unknown(String reason) {
        return new Unknown(reason);
    }

    default boolean isUnknown() {
        return this instanceof Unknown;
    }

    /**
     * Interprets the value as a truth value.
     *
     * @throws IllegalStateException when called on Unknown, which callers must
     *                               check for first
     */
    default boolean isTrue() {
        return switch (this) {
            case Number number -> number.value() != 0;
            case Text text -> !text.value().isEmpty();
            case Unknown unknown -> throw new IllegalStateException(
                    "Unknown has no truth value: " + unknown.reason());
        };
    }

    /** The numeric view, or null for a string that is not a number. */
    default Double asNumber() {
        return switch (this) {
            case Number number -> number.value();
            case Text text -> {
                try {
                    yield Double.parseDouble(text.value().trim());
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case Unknown ignored -> null;
        };
    }

    /** The string view. */
    default String asText() {
        return switch (this) {
            case Number number -> number.value() == Math.rint(number.value())
                    ? Long.toString((long) number.value())
                    : Double.toString(number.value());
            case Text text -> text.value();
            case Unknown unknown -> unknown.reason();
        };
    }
}
