package com.nms.server.trigger.expression;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers operator semantics, and above all how missing data propagates.
 *
 * <p>Functions are stubbed by name here, so these test the language rather than
 * the history layer.
 */
class ExpressionEvaluatorTest {

    private final Map<String, EvalValue> functionResults = new HashMap<>();

    /** Resolves a call by its item key, so tests can name values readably. */
    private EvalValue eval(String expression) {
        ExpressionNode root = ExpressionParser.parse(expression);
        ExpressionEvaluator evaluator = new ExpressionEvaluator(function -> {
            EvalValue stubbed = functionResults.get(function.item().key());
            return stubbed == null
                    ? EvalValue.unknown("no stub for " + function.item().key())
                    : stubbed;
        });
        return evaluator.evaluate(root);
    }

    private void given(String itemKey, double value) {
        functionResults.put(itemKey, EvalValue.of(value));
    }

    private void givenText(String itemKey, String value) {
        functionResults.put(itemKey, new EvalValue.Text(value));
    }

    private void givenNoData(String itemKey) {
        functionResults.put(itemKey, EvalValue.unknown("no data for " + itemKey));
    }

    @Test
    void comparisonsProduceTruthValues() {
        given("ping", 0);

        assertThat(eval("max(/h/ping,3m)=0").isTrue()).isTrue();
        assertThat(eval("max(/h/ping,3m)>0").isTrue()).isFalse();
        assertThat(eval("max(/h/ping,3m)<>1").isTrue()).isTrue();
        assertThat(eval("max(/h/ping,3m)<=0").isTrue()).isTrue();
    }

    @Test
    void arithmeticWorksAcrossFunctions() {
        given("load", 8);
        given("cores", 4);

        assertThat(eval("last(/h/load)/last(/h/cores)>1").isTrue()).isTrue();
        assertThat(eval("last(/h/load)/last(/h/cores)>2").isTrue()).isFalse();
    }

    @Test
    void missingDataYieldsUnknownRatherThanFalse() {
        givenNoData("ping");

        EvalValue result = eval("max(/h/ping,3m)=0");

        // The single most important property in the engine. If an item with no
        // data read as OK, a host that stopped reporting entirely would look
        // healthy -- an all-clear because the sensor was unplugged.
        assertThat(result.isUnknown()).isTrue();
        assertThat(((EvalValue.Unknown) result).reason()).contains("no data");
    }

    @Test
    void unknownPropagatesThroughArithmetic() {
        given("load", 8);
        givenNoData("cores");

        assertThat(eval("last(/h/load)/last(/h/cores)>2").isUnknown()).isTrue();
    }

    @Test
    void falseAndUnknownIsFalse() {
        given("a", 0);
        givenNoData("b");

        // No value of the unknown operand could make this true, so the answer
        // is already decided and reporting Unknown would be needlessly timid.
        assertThat(eval("last(/h/a)=1 and last(/h/b)=1").isTrue()).isFalse();
    }

    @Test
    void trueOrUnknownIsTrue() {
        given("a", 1);
        givenNoData("b");

        assertThat(eval("last(/h/a)=1 or last(/h/b)=1").isTrue()).isTrue();
    }

    @Test
    void trueAndUnknownIsUnknown() {
        given("a", 1);
        givenNoData("b");

        // Here the unknown operand really does decide the outcome, so the
        // honest answer is that we do not know.
        assertThat(eval("last(/h/a)=1 and last(/h/b)=1").isUnknown()).isTrue();
    }

    @Test
    void falseOrUnknownIsUnknown() {
        given("a", 0);
        givenNoData("b");

        assertThat(eval("last(/h/a)=1 or last(/h/b)=1").isUnknown()).isTrue();
    }

    @Test
    void divisionByZeroIsUnknownNotInfinity() {
        given("load", 8);
        given("cores", 0);

        // An infinity compares greater than every threshold, so letting one
        // through would fire the trigger purely because a divisor was zero.
        EvalValue result = eval("last(/h/load)/last(/h/cores)>2");

        assertThat(result.isUnknown()).isTrue();
        assertThat(((EvalValue.Unknown) result).reason()).contains("division by zero");
    }

    @Test
    void comparesTextItemsAsStrings() {
        givenText("service.state", "running");

        assertThat(eval("last(/h/service.state)=\"running\"").isTrue()).isTrue();
        assertThat(eval("last(/h/service.state)=\"stopped\"").isTrue()).isFalse();
        assertThat(eval("last(/h/service.state)<>\"stopped\"").isTrue()).isTrue();
    }

    @Test
    void comparingANonNumericStringNumericallyIsUnknown() {
        givenText("service.state", "running");

        // "running" > 5 has no defensible answer, and guessing one would make
        // the trigger fire or not fire for reasons nobody could explain.
        assertThat(eval("last(/h/service.state)>5").isUnknown()).isTrue();
    }

    @Test
    void negationInvertsTruth() {
        given("ping", 1);

        assertThat(eval("not last(/h/ping)=0").isTrue()).isTrue();
        assertThat(eval("not last(/h/ping)=1").isTrue()).isFalse();
    }

    @Test
    void evaluatesTheCameraStreamDownExpression() {
        // The camera is reachable but not serving RTSP: exactly the case a
        // simple ping check cannot see.
        given("icmpping", 1);
        given("net.tcp.service[rtsp]", 0);

        String expression = "max(/cam/net.tcp.service[rtsp],3m)=0 and max(/cam/icmpping,3m)=1";
        assertThat(eval(expression).isTrue()).isTrue();

        // Once the stream recovers the expression must go false.
        given("net.tcp.service[rtsp]", 1);
        assertThat(eval(expression).isTrue()).isFalse();
    }

    @Test
    void cameraStreamTriggerDoesNotFireWhenTheCameraIsFullyDown() {
        given("icmpping", 0);
        given("net.tcp.service[rtsp]", 0);

        // The "stream is down" trigger must stay quiet when the whole camera
        // is offline; the offline trigger is what should report that, and
        // firing both would double every alert.
        assertThat(eval("max(/cam/net.tcp.service[rtsp],3m)=0 and max(/cam/icmpping,3m)=1").isTrue())
                .isFalse();
    }

    @Test
    void precedenceHoldsAcrossMixedOperators() {
        given("a", 1);
        given("b", 0);
        given("c", 1);

        // a or (b and c) is true because a is; (a or b) and c would also be
        // true here, so the second case below is what actually distinguishes
        // them.
        assertThat(eval("last(/h/a)=1 or last(/h/b)=1 and last(/h/c)=1").isTrue()).isTrue();

        given("a", 0);
        given("c", 0);
        // a or (b and c) is false; (a or b) and c would also be false.
        assertThat(eval("last(/h/a)=1 or last(/h/b)=1 and last(/h/c)=1").isTrue()).isFalse();

        given("a", 1);
        given("b", 1);
        given("c", 0);
        // a or (b and c) is true; (a or b) and c would be false. This is the
        // case that proves the precedence.
        assertThat(eval("last(/h/a)=1 or last(/h/b)=1 and last(/h/c)=1").isTrue()).isTrue();
        assertThat(eval("(last(/h/a)=1 or last(/h/b)=1) and last(/h/c)=1").isTrue()).isFalse();
    }
}
