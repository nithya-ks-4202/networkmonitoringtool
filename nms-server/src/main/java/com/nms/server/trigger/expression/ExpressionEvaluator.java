package com.nms.server.trigger.expression;

import com.nms.server.trigger.expression.ExpressionNode.Binary;
import com.nms.server.trigger.expression.ExpressionNode.Function;
import com.nms.server.trigger.expression.ExpressionNode.NumberLiteral;
import com.nms.server.trigger.expression.ExpressionNode.Operator;
import com.nms.server.trigger.expression.ExpressionNode.StringLiteral;
import com.nms.server.trigger.expression.ExpressionNode.Unary;

/**
 * Evaluates a parsed trigger expression.
 *
 * <p>Function calls are delegated to a {@link FunctionContext}, which is what
 * separates the language from the data: the evaluator knows about operators and
 * precedence, the context knows how to read history. That split is also what
 * makes the whole engine testable without a database.
 *
 * <p>Unknown propagates through every operator except the short-circuit cases
 * where the answer is already decided -- {@code false and unknown} is false,
 * because no value of the unknown operand could make it true.
 */
public class ExpressionEvaluator {

    private final FunctionContext context;

    public ExpressionEvaluator(FunctionContext context) {
        this.context = context;
    }

    public EvalValue evaluate(ExpressionNode node) {
        return switch (node) {
            case NumberLiteral literal -> EvalValue.of(literal.value());
            case StringLiteral literal -> new EvalValue.Text(literal.value());
            case Function function -> context.evaluate(function);
            case Unary unary -> evaluateUnary(unary);
            case Binary binary -> evaluateBinary(binary);
        };
    }

    private EvalValue evaluateUnary(Unary unary) {
        EvalValue operand = evaluate(unary.operand());
        if (operand.isUnknown()) {
            return operand;
        }

        return switch (unary.operator()) {
            case NOT -> EvalValue.of(!operand.isTrue());
            case NEGATE -> {
                Double number = operand.asNumber();
                yield number == null
                        ? EvalValue.unknown("cannot negate the non-numeric value '" + operand.asText() + "'")
                        : EvalValue.of(-number);
            }
            default -> EvalValue.unknown("unsupported unary operator " + unary.operator());
        };
    }

    private EvalValue evaluateBinary(Binary binary) {
        // Logical operators short-circuit, which also decides several Unknown
        // cases without needing the other operand at all.
        if (binary.operator() == Operator.AND) {
            EvalValue left = evaluate(binary.left());
            if (!left.isUnknown() && !left.isTrue()) {
                return EvalValue.FALSE;
            }
            EvalValue right = evaluate(binary.right());
            if (!right.isUnknown() && !right.isTrue()) {
                return EvalValue.FALSE;
            }
            if (left.isUnknown()) {
                return left;
            }
            if (right.isUnknown()) {
                return right;
            }
            return EvalValue.TRUE;
        }

        if (binary.operator() == Operator.OR) {
            EvalValue left = evaluate(binary.left());
            if (!left.isUnknown() && left.isTrue()) {
                return EvalValue.TRUE;
            }
            EvalValue right = evaluate(binary.right());
            if (!right.isUnknown() && right.isTrue()) {
                return EvalValue.TRUE;
            }
            if (left.isUnknown()) {
                return left;
            }
            if (right.isUnknown()) {
                return right;
            }
            return EvalValue.FALSE;
        }

        EvalValue left = evaluate(binary.left());
        if (left.isUnknown()) {
            return left;
        }
        EvalValue right = evaluate(binary.right());
        if (right.isUnknown()) {
            return right;
        }

        if (binary.operator().isComparison()) {
            return compare(binary.operator(), left, right);
        }
        return arithmetic(binary.operator(), left, right);
    }

    private EvalValue compare(Operator operator, EvalValue left, EvalValue right) {
        // Equality against a string is compared as text; everything else
        // numerically. This is what makes last(/host/key)="running" work on a
        // text item while thresholds still behave as numbers.
        boolean textComparison = left instanceof EvalValue.Text || right instanceof EvalValue.Text;
        if (textComparison && (operator == Operator.EQUAL || operator == Operator.NOT_EQUAL)) {
            boolean equal = left.asText().equals(right.asText());
            return EvalValue.of(operator == Operator.EQUAL ? equal : !equal);
        }

        Double leftNumber = left.asNumber();
        Double rightNumber = right.asNumber();
        if (leftNumber == null || rightNumber == null) {
            return EvalValue.unknown("cannot compare '" + left.asText() + "' with '" + right.asText()
                    + "': not both numeric");
        }

        return EvalValue.of(switch (operator) {
            case EQUAL -> leftNumber.doubleValue() == rightNumber.doubleValue();
            case NOT_EQUAL -> leftNumber.doubleValue() != rightNumber.doubleValue();
            case LESS -> leftNumber < rightNumber;
            case LESS_OR_EQUAL -> leftNumber <= rightNumber;
            case GREATER -> leftNumber > rightNumber;
            case GREATER_OR_EQUAL -> leftNumber >= rightNumber;
            default -> false;
        });
    }

    private EvalValue arithmetic(Operator operator, EvalValue left, EvalValue right) {
        Double leftNumber = left.asNumber();
        Double rightNumber = right.asNumber();
        if (leftNumber == null || rightNumber == null) {
            return EvalValue.unknown("arithmetic needs numbers, got '"
                    + left.asText() + "' and '" + right.asText() + "'");
        }

        return switch (operator) {
            case ADD -> EvalValue.of(leftNumber + rightNumber);
            case SUBTRACT -> EvalValue.of(leftNumber - rightNumber);
            case MULTIPLY -> EvalValue.of(leftNumber * rightNumber);
            case DIVIDE -> rightNumber == 0
                    // Unknown rather than an exception or an infinity: an
                    // infinity compares true against every threshold and would
                    // fire the trigger on a divisor that happened to be zero.
                    ? EvalValue.unknown("division by zero")
                    : EvalValue.of(leftNumber / rightNumber);
            default -> EvalValue.unknown("unsupported operator " + operator);
        };
    }

    /** Supplies the values of history functions to an evaluation. */
    public interface FunctionContext {
        EvalValue evaluate(Function function);
    }
}
