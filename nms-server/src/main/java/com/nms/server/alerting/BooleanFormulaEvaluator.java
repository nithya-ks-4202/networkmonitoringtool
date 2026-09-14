package com.nms.server.alerting;

import java.util.Map;

/**
 * Evaluates a boolean formula over named condition results.
 *
 * <p>Supports {@code and}, {@code or}, {@code not} and parentheses over
 * single-letter labels, as in {@code A and (B or C)}. Recursive descent, with
 * {@code or} binding loosest.
 *
 * <p>A purpose-built parser rather than a scripting engine, for the same reason
 * the arithmetic evaluator is: the formula comes from user configuration, and
 * this grammar can express a boolean combination and nothing else.
 */
class BooleanFormulaEvaluator {

    private final String formula;
    private final Map<String, Boolean> values;
    private int position;

    BooleanFormulaEvaluator(String formula, Map<String, Boolean> values) {
        this.formula = formula == null ? "" : formula;
        this.values = values;
    }

    boolean evaluate() {
        if (formula.isBlank()) {
            throw new IllegalArgumentException("the formula is empty");
        }
        position = 0;
        boolean result = parseOr();
        skipWhitespace();
        if (position < formula.length()) {
            throw new IllegalArgumentException(
                    "unexpected '" + formula.charAt(position) + "' at position " + position);
        }
        return result;
    }

    private boolean parseOr() {
        boolean left = parseAnd();
        while (matchKeyword("or")) {
            // Both sides are evaluated: the condition results are already
            // computed, so there is nothing to short-circuit, and evaluating
            // fully keeps a malformed right-hand side from passing unnoticed.
            boolean right = parseAnd();
            left = left || right;
        }
        return left;
    }

    private boolean parseAnd() {
        boolean left = parseNot();
        while (matchKeyword("and")) {
            boolean right = parseNot();
            left = left && right;
        }
        return left;
    }

    private boolean parseNot() {
        if (matchKeyword("not")) {
            return !parseNot();
        }
        return parsePrimary();
    }

    private boolean parsePrimary() {
        skipWhitespace();
        if (position >= formula.length()) {
            throw new IllegalArgumentException("the formula ended unexpectedly");
        }

        if (formula.charAt(position) == '(') {
            position++;
            boolean inner = parseOr();
            skipWhitespace();
            if (position >= formula.length() || formula.charAt(position) != ')') {
                throw new IllegalArgumentException("missing closing parenthesis");
            }
            position++;
            return inner;
        }

        int start = position;
        while (position < formula.length() && Character.isLetterOrDigit(formula.charAt(position))) {
            position++;
        }
        if (start == position) {
            throw new IllegalArgumentException(
                    "expected a condition label at position " + position);
        }

        String label = formula.substring(start, position);
        Boolean value = values.get(label);
        if (value == null) {
            throw new IllegalArgumentException("the formula references condition '" + label
                    + "', which the action does not define");
        }
        return value;
    }

    private void skipWhitespace() {
        while (position < formula.length() && Character.isWhitespace(formula.charAt(position))) {
            position++;
        }
    }

    /**
     * Matches a keyword, requiring a non-word character after it so a label
     * named "AND1" is not read as the operator "and".
     */
    private boolean matchKeyword(String keyword) {
        skipWhitespace();
        if (!formula.regionMatches(true, position, keyword, 0, keyword.length())) {
            return false;
        }
        int after = position + keyword.length();
        if (after < formula.length() && Character.isLetterOrDigit(formula.charAt(after))) {
            return false;
        }
        position = after;
        return true;
    }
}
