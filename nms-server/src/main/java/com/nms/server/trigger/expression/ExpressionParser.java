package com.nms.server.trigger.expression;

import com.nms.server.trigger.expression.ExpressionNode.Argument;
import com.nms.server.trigger.expression.ExpressionNode.Binary;
import com.nms.server.trigger.expression.ExpressionNode.Function;
import com.nms.server.trigger.expression.ExpressionNode.ItemReference;
import com.nms.server.trigger.expression.ExpressionNode.NumberLiteral;
import com.nms.server.trigger.expression.ExpressionNode.Operator;
import com.nms.server.trigger.expression.ExpressionNode.StringLiteral;
import com.nms.server.trigger.expression.ExpressionNode.Unary;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses trigger expressions.
 *
 * <p>The syntax follows Zabbix's, so existing templates and the considerable
 * body of operator knowledge around them carry over:
 *
 * <pre>
 *   max(/switch-01/icmpping,3m)=0
 *   min(/web-01/system.cpu.util,5m)&gt;90 and last(/web-01/system.cpu.num)&gt;1
 *   nodata(/camera-12/agent.ping,5m)=1
 * </pre>
 *
 * <p>Recursive descent, by precedence: {@code or} binds loosest, then
 * {@code and}, then {@code not}, comparison, additive, multiplicative, unary.
 *
 * <p>Written by hand rather than generated because the expression is also the
 * thing operators debug most often, and the error messages matter: "expected a
 * closing parenthesis at position 34" is actionable in a way that a parser
 * generator's default output is not.
 */
public class ExpressionParser {

    private final String expression;
    private int position;

    /**
     * Item references seen while parsing.
     *
     * <p>Collected so the caller can populate {@code trigger_item} and make the
     * item-to-trigger lookup an index hit instead of a scan over every
     * expression on every collected value.
     */
    private final Set<ItemReference> referencedItems = new LinkedHashSet<>();

    public ExpressionParser(String expression) {
        this.expression = expression == null ? "" : expression;
    }

    /** Parses an expression, or throws explaining where it went wrong. */
    public static ExpressionNode parse(String expression) {
        return new ExpressionParser(expression).parseExpression();
    }

    /** Parses and reports which items the expression reads. */
    public static ParsedExpression parseWithReferences(String expression) {
        ExpressionParser parser = new ExpressionParser(expression);
        ExpressionNode root = parser.parseExpression();
        return new ParsedExpression(root, List.copyOf(parser.referencedItems));
    }

    /** Entry point; verifies the whole string was consumed. */
    public ExpressionNode parseExpression() {
        if (expression.isBlank()) {
            throw error("the expression is empty");
        }
        ExpressionNode root = parseOr();
        skipWhitespace();
        if (position < expression.length()) {
            throw error("unexpected '" + expression.charAt(position) + "'");
        }
        return root;
    }

    private ExpressionNode parseOr() {
        ExpressionNode left = parseAnd();
        while (matchKeyword("or")) {
            left = new Binary(Operator.OR, left, parseAnd());
        }
        return left;
    }

    private ExpressionNode parseAnd() {
        ExpressionNode left = parseNot();
        while (matchKeyword("and")) {
            left = new Binary(Operator.AND, left, parseNot());
        }
        return left;
    }

    private ExpressionNode parseNot() {
        if (matchKeyword("not")) {
            return new Unary(Operator.NOT, parseNot());
        }
        return parseComparison();
    }

    private ExpressionNode parseComparison() {
        ExpressionNode left = parseAdditive();
        skipWhitespace();

        Operator operator = matchComparisonOperator();
        if (operator == null) {
            return left;
        }
        // Comparisons do not chain: "a < b < c" is a mistake far more often
        // than an intention, so it is rejected rather than silently reassociated.
        return new Binary(operator, left, parseAdditive());
    }

    private Operator matchComparisonOperator() {
        // Two-character operators first, or "<=" would be read as "<" then "=".
        if (match("<>") || match("!=")) {
            return Operator.NOT_EQUAL;
        }
        if (match("<=")) {
            return Operator.LESS_OR_EQUAL;
        }
        if (match(">=")) {
            return Operator.GREATER_OR_EQUAL;
        }
        if (match("=")) {
            return Operator.EQUAL;
        }
        if (match("<")) {
            return Operator.LESS;
        }
        if (match(">")) {
            return Operator.GREATER;
        }
        return null;
    }

    private ExpressionNode parseAdditive() {
        ExpressionNode left = parseMultiplicative();
        while (true) {
            skipWhitespace();
            if (match("+")) {
                left = new Binary(Operator.ADD, left, parseMultiplicative());
            } else if (matchMinusNotPartOfItemPath()) {
                left = new Binary(Operator.SUBTRACT, left, parseMultiplicative());
            } else {
                return left;
            }
        }
    }

    private ExpressionNode parseMultiplicative() {
        ExpressionNode left = parseUnary();
        while (true) {
            skipWhitespace();
            if (match("*")) {
                left = new Binary(Operator.MULTIPLY, left, parseUnary());
            } else if (peek() == '/' && !startsItemReference()) {
                position++;
                left = new Binary(Operator.DIVIDE, left, parseUnary());
            } else {
                return left;
            }
        }
    }

    private ExpressionNode parseUnary() {
        skipWhitespace();
        if (match("-")) {
            return new Unary(Operator.NEGATE, parseUnary());
        }
        if (match("+")) {
            return parseUnary();
        }
        return parsePrimary();
    }

    private ExpressionNode parsePrimary() {
        skipWhitespace();
        if (position >= expression.length()) {
            throw error("the expression ended unexpectedly");
        }

        if (match("(")) {
            ExpressionNode inner = parseOr();
            skipWhitespace();
            if (!match(")")) {
                throw error("expected a closing parenthesis");
            }
            return inner;
        }

        char current = peek();

        if (current == '"' || current == '\'') {
            return new StringLiteral(parseQuotedString());
        }
        if (Character.isDigit(current) || current == '.') {
            return new NumberLiteral(parseNumberWithSuffix());
        }
        if (Character.isLetter(current) || current == '_') {
            return parseFunction();
        }

        throw error("unexpected '" + current + "'");
    }

    /**
     * Parses {@code name(/host/key,arg,...)}.
     *
     * <p>Every supported function reads history for exactly one item, so the
     * item reference is required and validated here rather than left to the
     * evaluator -- a typo in a host name should fail on save, not at 3am.
     */
    private ExpressionNode parseFunction() {
        int nameStart = position;
        while (position < expression.length()
                && (Character.isLetterOrDigit(expression.charAt(position)) || expression.charAt(position) == '_')) {
            position++;
        }
        String name = expression.substring(nameStart, position).toLowerCase(Locale.ROOT);

        skipWhitespace();
        if (!match("(")) {
            throw error("expected '(' after the function name '" + name + "'");
        }

        skipWhitespace();
        // Checked before anything else is consumed, so a call written without
        // one fails saying what is missing rather than complaining about a
        // parenthesis several characters later.
        if (peek() != '/') {
            throw error("function '" + name + "' needs an item reference such as /host/key"
                    + " as its first argument");
        }
        ItemReference item = parseItemReference();
        referencedItems.add(item);

        List<Argument> arguments = new ArrayList<>();
        skipWhitespace();
        while (match(",")) {
            arguments.add(parseArgument());
            skipWhitespace();
        }

        if (!match(")")) {
            throw error("expected ')' to close the call to '" + name + "'");
        }

        return new Function(name, item, List.copyOf(arguments));
    }

    /** Parses {@code /host/key}, where the key may itself contain slashes. */
    private ItemReference parseItemReference() {
        if (!match("/")) {
            throw error("expected an item reference starting with '/'");
        }

        int hostStart = position;
        while (position < expression.length() && expression.charAt(position) != '/') {
            position++;
        }
        if (position >= expression.length()) {
            throw error("item reference is missing its key");
        }
        String host = expression.substring(hostStart, position);
        position++;

        // The key runs to the next comma or the closing parenthesis, but
        // bracketed key parameters may legally contain both -- as in
        // net.if.in[eth0,bytes] -- so bracket depth has to be tracked.
        int keyStart = position;
        int bracketDepth = 0;
        while (position < expression.length()) {
            char current = expression.charAt(position);
            if (current == '[') {
                bracketDepth++;
            } else if (current == ']') {
                bracketDepth--;
            } else if (bracketDepth == 0 && (current == ',' || current == ')')) {
                break;
            }
            position++;
        }

        String key = expression.substring(keyStart, position).trim();
        if (host.isBlank()) {
            throw error("item reference has an empty host name");
        }
        if (key.isBlank()) {
            throw error("item reference has an empty key");
        }
        return new ItemReference(host, key);
    }

    private Argument parseArgument() {
        skipWhitespace();
        int start = position;
        int bracketDepth = 0;

        while (position < expression.length()) {
            char current = expression.charAt(position);
            if (current == '[') {
                bracketDepth++;
            } else if (current == ']') {
                bracketDepth--;
            } else if (bracketDepth == 0 && (current == ',' || current == ')')) {
                break;
            }
            position++;
        }

        String text = expression.substring(start, position).trim();
        return toArgument(text);
    }

    /**
     * Interprets an argument's text.
     *
     * <p>{@code #5} means the last five values; {@code 5m} means the last five
     * minutes. Conflating them is a classic source of triggers that look right
     * and fire wrongly, so they are distinguished here and kept distinct all
     * the way through evaluation.
     */
    static Argument toArgument(String text) {
        if (text.isEmpty()) {
            return new Argument(text, null, null);
        }

        if (text.startsWith("#")) {
            try {
                return new Argument(text, null, Integer.parseInt(text.substring(1).trim()));
            } catch (NumberFormatException e) {
                return new Argument(text, null, null);
            }
        }

        Double seconds = parseTimeSuffix(text);
        return new Argument(text, seconds, null);
    }

    /** Parses {@code 30s}, {@code 5m}, {@code 2h}, {@code 7d}, {@code 2w} or a bare number. */
    static Double parseTimeSuffix(String text) {
        if (text.isEmpty()) {
            return null;
        }
        char suffix = text.charAt(text.length() - 1);
        String digits = Character.isDigit(suffix) ? text : text.substring(0, text.length() - 1);

        double multiplier = switch (suffix) {
            case 's' -> 1;
            case 'm' -> 60;
            case 'h' -> 3600;
            case 'd' -> 86_400;
            case 'w' -> 604_800;
            default -> Character.isDigit(suffix) ? 1 : -1;
        };
        if (multiplier < 0) {
            return null;
        }

        try {
            return Double.parseDouble(digits.trim()) * multiplier;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double parseNumberWithSuffix() {
        int start = position;
        while (position < expression.length()
                && (Character.isDigit(expression.charAt(position)) || expression.charAt(position) == '.')) {
            position++;
        }
        String digits = expression.substring(start, position);

        // A suffix on a bare number is a unit: 10K is ten kilobytes, 5m is five
        // minutes. Without this a threshold written "1G" would be a parse error
        // rather than a gigabyte.
        double multiplier = 1;
        if (position < expression.length()) {
            Double suffixMultiplier = suffixMultiplier(expression.charAt(position));
            if (suffixMultiplier != null) {
                multiplier = suffixMultiplier;
                position++;
            }
        }

        try {
            return Double.parseDouble(digits) * multiplier;
        } catch (NumberFormatException e) {
            throw error("'" + digits + "' is not a number");
        }
    }

    /** Unit multiplier for a numeric suffix, or null when the char is not one. */
    private static Double suffixMultiplier(char suffix) {
        return switch (suffix) {
            case 'K' -> 1024d;
            case 'M' -> 1024d * 1024;
            case 'G' -> 1024d * 1024 * 1024;
            case 'T' -> 1024d * 1024 * 1024 * 1024;
            case 's' -> 1d;
            case 'm' -> 60d;
            case 'h' -> 3600d;
            case 'd' -> 86_400d;
            case 'w' -> 604_800d;
            default -> null;
        };
    }

    private String parseQuotedString() {
        char quote = expression.charAt(position);
        position++;
        StringBuilder value = new StringBuilder();

        while (position < expression.length()) {
            char current = expression.charAt(position);
            if (current == '\\' && position + 1 < expression.length()) {
                position++;
                value.append(expression.charAt(position));
            } else if (current == quote) {
                position++;
                return value.toString();
            } else {
                value.append(current);
            }
            position++;
        }
        throw error("unterminated string literal");
    }

    /**
     * True when the '/' at the cursor begins an item reference rather than a
     * division. An item reference only ever follows '(' or ',', which is what
     * keeps {@code a/b} unambiguous.
     */
    private boolean startsItemReference() {
        int back = position - 1;
        while (back >= 0 && Character.isWhitespace(expression.charAt(back))) {
            back--;
        }
        if (back < 0) {
            return true;
        }
        char previous = expression.charAt(back);
        return previous == '(' || previous == ',';
    }

    /**
     * A '-' that is subtraction rather than part of a key or a negative literal
     * already consumed by parseUnary.
     */
    private boolean matchMinusNotPartOfItemPath() {
        return match("-");
    }

    private void skipWhitespace() {
        while (position < expression.length() && Character.isWhitespace(expression.charAt(position))) {
            position++;
        }
    }

    private char peek() {
        return position < expression.length() ? expression.charAt(position) : '\0';
    }

    private boolean match(String token) {
        skipWhitespace();
        if (expression.startsWith(token, position)) {
            position += token.length();
            return true;
        }
        return false;
    }

    /**
     * Matches a word operator, requiring a non-word character after it so a
     * key named "android" is not read as "and" followed by "roid".
     */
    private boolean matchKeyword(String keyword) {
        skipWhitespace();
        if (!expression.regionMatches(true, position, keyword, 0, keyword.length())) {
            return false;
        }
        int after = position + keyword.length();
        if (after < expression.length()) {
            char next = expression.charAt(after);
            if (Character.isLetterOrDigit(next) || next == '_') {
                return false;
            }
        }
        position = after;
        return true;
    }

    private ExpressionException error(String message) {
        return new ExpressionException(
                message + " (at position " + position + " in \"" + expression + "\")");
    }

    /** An expression together with the items it reads. */
    public record ParsedExpression(ExpressionNode root, List<ItemReference> referencedItems) {
    }

    /** Raised when an expression cannot be parsed. */
    public static class ExpressionException extends RuntimeException {
        public ExpressionException(String message) {
            super(message);
        }
    }
}
