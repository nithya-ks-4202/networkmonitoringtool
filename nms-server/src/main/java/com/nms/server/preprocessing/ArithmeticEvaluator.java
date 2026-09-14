package com.nms.server.preprocessing;

/**
 * Evaluates a small arithmetic formula over a collected value.
 *
 * <p>A hand-written recursive-descent parser rather than a scripting engine.
 * Formulas come from user configuration, and handing user input to a general
 * interpreter would let anyone with permission to edit an item run code inside
 * the server. This grammar can express arithmetic and nothing else, so there is
 * nothing to escape from.
 *
 * <p>Grammar, in precedence order:
 * <pre>
 *   expression := term (('+' | '-') term)*
 *   term       := factor (('*' | '/' | '%') factor)*
 *   factor     := ['-'] primary ['^' factor]
 *   primary    := number | 'x' | function '(' expression ')' | '(' expression ')'
 * </pre>
 */
public class ArithmeticEvaluator {

    private final String formula;
    private final double x;
    private int position;

    /**
     * @param formula the expression, using {@code x} for the collected value
     * @param x       the value being transformed
     */
    public ArithmeticEvaluator(String formula, double x) {
        this.formula = formula == null ? "x" : formula;
        this.x = x;
    }

    /**
     * @throws PreprocessingPipeline.PreprocessingException when the formula is
     *         malformed or produces a non-finite result
     */
    public double evaluate() {
        position = 0;
        double result = parseExpression();
        skipWhitespace();
        if (position < formula.length()) {
            throw error("unexpected '" + formula.charAt(position) + "' at position " + position);
        }
        if (!Double.isFinite(result)) {
            // An infinity or NaN reaching storage would silently corrupt every
            // aggregate computed over the item from then on.
            throw error("formula produced a non-finite result");
        }
        return result;
    }

    private double parseExpression() {
        double value = parseTerm();
        while (true) {
            skipWhitespace();
            if (consume('+')) {
                value += parseTerm();
            } else if (consume('-')) {
                value -= parseTerm();
            } else {
                return value;
            }
        }
    }

    private double parseTerm() {
        double value = parseFactor();
        while (true) {
            skipWhitespace();
            if (consume('*')) {
                value *= parseFactor();
            } else if (consume('/')) {
                double divisor = parseFactor();
                if (divisor == 0) {
                    throw error("division by zero");
                }
                value /= divisor;
            } else if (consume('%')) {
                double divisor = parseFactor();
                if (divisor == 0) {
                    throw error("modulo by zero");
                }
                value %= divisor;
            } else {
                return value;
            }
        }
    }

    private double parseFactor() {
        skipWhitespace();
        if (consume('-')) {
            return -parseFactor();
        }
        if (consume('+')) {
            return parseFactor();
        }

        double base = parsePrimary();
        skipWhitespace();
        if (consume('^')) {
            // Right-associative, so 2^3^2 is 2^(3^2) as in ordinary notation.
            return Math.pow(base, parseFactor());
        }
        return base;
    }

    private double parsePrimary() {
        skipWhitespace();
        if (position >= formula.length()) {
            throw error("formula ended unexpectedly");
        }

        if (consume('(')) {
            double value = parseExpression();
            skipWhitespace();
            if (!consume(')')) {
                throw error("missing closing parenthesis");
            }
            return value;
        }

        char current = formula.charAt(position);

        if (Character.isDigit(current) || current == '.') {
            return parseNumber();
        }

        if (Character.isLetter(current) || current == '_') {
            return parseIdentifier();
        }

        throw error("unexpected '" + current + "' at position " + position);
    }

    private double parseNumber() {
        int start = position;
        while (position < formula.length()
                && (Character.isDigit(formula.charAt(position)) || formula.charAt(position) == '.')) {
            position++;
        }
        // Scientific notation, e.g. 1.5e-3.
        if (position < formula.length()
                && (formula.charAt(position) == 'e' || formula.charAt(position) == 'E')) {
            int exponentStart = position;
            position++;
            if (position < formula.length()
                    && (formula.charAt(position) == '+' || formula.charAt(position) == '-')) {
                position++;
            }
            if (position < formula.length() && Character.isDigit(formula.charAt(position))) {
                while (position < formula.length() && Character.isDigit(formula.charAt(position))) {
                    position++;
                }
            } else {
                // Not an exponent after all; it was an identifier starting
                // with 'e'. Rewind so the caller reads it as one.
                position = exponentStart;
            }
        }

        try {
            return Double.parseDouble(formula.substring(start, position));
        } catch (NumberFormatException e) {
            throw error("'" + formula.substring(start, position) + "' is not a number");
        }
    }

    private double parseIdentifier() {
        int start = position;
        while (position < formula.length()
                && (Character.isLetterOrDigit(formula.charAt(position)) || formula.charAt(position) == '_')) {
            position++;
        }
        String name = formula.substring(start, position).toLowerCase(java.util.Locale.ROOT);

        if ("x".equals(name) || "value".equals(name)) {
            return x;
        }
        if ("pi".equals(name)) {
            return Math.PI;
        }
        if ("e".equals(name)) {
            return Math.E;
        }

        skipWhitespace();
        if (!consume('(')) {
            throw error("unknown identifier '" + name + "'");
        }
        double argument = parseExpression();
        skipWhitespace();
        if (!consume(')')) {
            throw error("missing closing parenthesis after " + name);
        }

        return switch (name) {
            case "abs" -> Math.abs(argument);
            case "sqrt" -> {
                if (argument < 0) {
                    throw error("sqrt of a negative number");
                }
                yield Math.sqrt(argument);
            }
            case "log" -> {
                if (argument <= 0) {
                    throw error("log of a non-positive number");
                }
                yield Math.log(argument);
            }
            case "log10" -> {
                if (argument <= 0) {
                    throw error("log10 of a non-positive number");
                }
                yield Math.log10(argument);
            }
            case "exp" -> Math.exp(argument);
            case "floor" -> Math.floor(argument);
            case "ceil" -> Math.ceil(argument);
            case "round" -> Math.rint(argument);
            case "min" -> argument;
            case "max" -> argument;
            default -> throw error("unknown function '" + name + "'");
        };
    }

    private void skipWhitespace() {
        while (position < formula.length() && Character.isWhitespace(formula.charAt(position))) {
            position++;
        }
    }

    private boolean consume(char expected) {
        if (position < formula.length() && formula.charAt(position) == expected) {
            position++;
            return true;
        }
        return false;
    }

    private PreprocessingPipeline.PreprocessingException error(String message) {
        return new PreprocessingPipeline.PreprocessingException(
                "formula '" + formula + "': " + message);
    }
}
