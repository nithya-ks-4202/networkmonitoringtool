package com.nms.server.trigger.expression;

import java.util.List;

/**
 * A parsed trigger expression.
 *
 * <p>A sealed hierarchy so the evaluator's switch is exhaustive: adding a node
 * type without handling it becomes a compile error rather than a runtime
 * surprise on someone's alerting path.
 */
public sealed interface ExpressionNode {

    /** A numeric constant, including one written with a suffix such as {@code 5m}. */
    record NumberLiteral(double value) implements ExpressionNode {
    }

    /** A quoted string, used on the right of a comparison against a text item. */
    record StringLiteral(String value) implements ExpressionNode {
    }

    /**
     * A history function applied to an item, e.g. {@code max(/host/key,5m)}.
     *
     * @param name      function name, lower case
     * @param item      the item it reads, or null for functions that take none
     * @param arguments literal arguments after the item reference
     */
    record Function(String name, ItemReference item, List<Argument> arguments) implements ExpressionNode {
    }

    /** Arithmetic or comparison between two operands. */
    record Binary(Operator operator, ExpressionNode left, ExpressionNode right) implements ExpressionNode {
    }

    /** Negation, either arithmetic {@code -x} or logical {@code not x}. */
    record Unary(Operator operator, ExpressionNode operand) implements ExpressionNode {
    }

    /**
     * A reference to an item, written {@code /host/key}.
     *
     * <p>The host is the technical name, so renaming a host's visible name
     * never breaks an expression that mentions it.
     */
    record ItemReference(String host, String key) {
        @Override
        public String toString() {
            return "/" + host + "/" + key;
        }
    }

    /**
     * One argument to a function.
     *
     * <p>Time-window arguments are kept as seconds alongside their original
     * text, because {@code #5} (the last five values) and {@code 5m} (the last
     * five minutes) are both legal and mean very different things.
     */
    record Argument(String text, Double numericValue, Integer valueCount) {

        /** True when this argument selects a count of values rather than a period. */
        public boolean isValueCount() {
            return valueCount != null;
        }

        /** Window length in seconds, or null when this is not a period. */
        public Long seconds() {
            return numericValue == null ? null : numericValue.longValue();
        }
    }

    /** Operators the grammar supports. */
    enum Operator {
        OR("or"),
        AND("and"),
        NOT("not"),
        EQUAL("="),
        NOT_EQUAL("<>"),
        LESS("<"),
        LESS_OR_EQUAL("<="),
        GREATER(">"),
        GREATER_OR_EQUAL(">="),
        ADD("+"),
        SUBTRACT("-"),
        MULTIPLY("*"),
        DIVIDE("/"),
        NEGATE("-");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        /** True for operators whose result is a truth value rather than a number. */
        public boolean isLogical() {
            return this == OR || this == AND || this == NOT;
        }

        public boolean isComparison() {
            return switch (this) {
                case EQUAL, NOT_EQUAL, LESS, LESS_OR_EQUAL, GREATER, GREATER_OR_EQUAL -> true;
                default -> false;
            };
        }
    }
}
