package com.nms.server.trigger.expression;

import com.nms.server.trigger.expression.ExpressionNode.Binary;
import com.nms.server.trigger.expression.ExpressionNode.Function;
import com.nms.server.trigger.expression.ExpressionNode.NumberLiteral;
import com.nms.server.trigger.expression.ExpressionNode.Operator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionParserTest {

    @Test
    void parsesTheCanonicalHostDownExpression() {
        ExpressionNode node = ExpressionParser.parse("max(/switch-01/icmpping,3m)=0");

        assertThat(node).isInstanceOf(Binary.class);
        Binary comparison = (Binary) node;
        assertThat(comparison.operator()).isEqualTo(Operator.EQUAL);

        Function function = (Function) comparison.left();
        assertThat(function.name()).isEqualTo("max");
        assertThat(function.item().host()).isEqualTo("switch-01");
        assertThat(function.item().key()).isEqualTo("icmpping");
        assertThat(function.arguments().get(0).seconds()).isEqualTo(180L);

        assertThat(((NumberLiteral) comparison.right()).value()).isZero();
    }

    @Test
    void reportsTheItemsAnExpressionReads() {
        var parsed = ExpressionParser.parseWithReferences(
                "max(/camera-1/net.tcp.service[rtsp],3m)=0 and max(/camera-1/icmpping,3m)=1");

        // Both references are recorded so the item-to-trigger lookup can be an
        // index hit rather than a scan over every expression.
        assertThat(parsed.referencedItems()).hasSize(2);
        assertThat(parsed.referencedItems()).extracting(ExpressionNode.ItemReference::key)
                .containsExactly("net.tcp.service[rtsp]", "icmpping");
    }

    @Test
    void keepsBracketedKeyParametersIntact() {
        var parsed = ExpressionParser.parseWithReferences(
                "last(/server-1/vfs.fs.size[/var,pused])>90");

        // The key contains both a comma and a slash. Splitting naively on
        // either would truncate it and silently point the trigger at an item
        // that does not exist.
        assertThat(parsed.referencedItems()).hasSize(1);
        assertThat(parsed.referencedItems().get(0).key()).isEqualTo("vfs.fs.size[/var,pused]");
    }

    @Test
    void andBindsTighterThanOr() {
        Binary root = (Binary) ExpressionParser.parse(
                "last(/h/a)=1 or last(/h/b)=1 and last(/h/c)=1");

        // Parsed as a or (b and c), not (a or b) and c.
        assertThat(root.operator()).isEqualTo(Operator.OR);
        assertThat(((Binary) root.right()).operator()).isEqualTo(Operator.AND);
    }

    @Test
    void parenthesesOverridePrecedence() {
        Binary root = (Binary) ExpressionParser.parse(
                "(last(/h/a)=1 or last(/h/b)=1) and last(/h/c)=1");

        assertThat(root.operator()).isEqualTo(Operator.AND);
        assertThat(((Binary) root.left()).operator()).isEqualTo(Operator.OR);
    }

    @Test
    void parsesArithmeticAcrossItems() {
        // Load normalised by core count: the same threshold then means the
        // same thing on a 2-core and a 64-core machine.
        ExpressionNode node = ExpressionParser.parse(
                "min(/server-1/system.cpu.load[all,avg5],5m)/last(/server-1/system.cpu.num)>2");

        Binary comparison = (Binary) node;
        assertThat(comparison.operator()).isEqualTo(Operator.GREATER);
        assertThat(((Binary) comparison.left()).operator()).isEqualTo(Operator.DIVIDE);
    }

    @Test
    void distinguishesDivisionFromAnItemPath() {
        var parsed = ExpressionParser.parseWithReferences("last(/h/a)/last(/h/b)>2");

        // The slash between the two calls is division; the ones inside them
        // begin item references. Getting this wrong would make every ratio
        // expression unparseable.
        assertThat(parsed.referencedItems()).hasSize(2);
        assertThat(((Binary) parsed.root()).left()).isInstanceOf(Binary.class);
    }

    @Test
    void parsesTimeSuffixes() {
        assertThat(ExpressionParser.parseTimeSuffix("30s")).isEqualTo(30.0);
        assertThat(ExpressionParser.parseTimeSuffix("5m")).isEqualTo(300.0);
        assertThat(ExpressionParser.parseTimeSuffix("2h")).isEqualTo(7200.0);
        assertThat(ExpressionParser.parseTimeSuffix("7d")).isEqualTo(604_800.0);
        assertThat(ExpressionParser.parseTimeSuffix("1w")).isEqualTo(604_800.0);
        assertThat(ExpressionParser.parseTimeSuffix("90")).isEqualTo(90.0);
    }

    @Test
    void distinguishesAValueCountFromATimeWindow() {
        Function byCount = (Function) ((Binary) ExpressionParser.parse("max(/h/k,#5)=0")).left();
        Function byTime = (Function) ((Binary) ExpressionParser.parse("max(/h/k,5m)=0")).left();

        // "the last five values" and "the last five minutes" are different
        // questions, and conflating them produces triggers that look correct
        // and fire wrongly.
        assertThat(byCount.arguments().get(0).isValueCount()).isTrue();
        assertThat(byCount.arguments().get(0).valueCount()).isEqualTo(5);
        assertThat(byTime.arguments().get(0).isValueCount()).isFalse();
        assertThat(byTime.arguments().get(0).seconds()).isEqualTo(300L);
    }

    @Test
    void parsesSizeSuffixesOnThresholds() {
        Binary node = (Binary) ExpressionParser.parse("last(/h/vm.memory.size[available])<512M");

        assertThat(((NumberLiteral) node.right()).value()).isEqualTo(512d * 1024 * 1024);
    }

    @Test
    void parsesStringComparison() {
        Binary node = (Binary) ExpressionParser.parse("last(/h/service.state)=\"running\"");

        assertThat(node.right()).isInstanceOf(ExpressionNode.StringLiteral.class);
        assertThat(((ExpressionNode.StringLiteral) node.right()).value()).isEqualTo("running");
    }

    @Test
    void doesNotMistakeAKeyBeginningWithAnOperatorNameForAnOperator() {
        // "android.status" starts with "and". A naive keyword match would
        // split it and produce a baffling parse error.
        var parsed = ExpressionParser.parseWithReferences("last(/phone/android.status)=1");

        assertThat(parsed.referencedItems().get(0).key()).isEqualTo("android.status");
    }

    @Test
    void parsesNotAndNegation() {
        assertThat(ExpressionParser.parse("not last(/h/k)=1")).isInstanceOf(ExpressionNode.Unary.class);
        assertThat(ExpressionParser.parse("-last(/h/k)<0")).isInstanceOf(Binary.class);
    }

    @Test
    void rejectsAnEmptyExpression() {
        assertThatThrownBy(() -> ExpressionParser.parse("   "))
                .isInstanceOf(ExpressionParser.ExpressionException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsAnUnclosedParenthesis() {
        assertThatThrownBy(() -> ExpressionParser.parse("(last(/h/k)=1"))
                .isInstanceOf(ExpressionParser.ExpressionException.class)
                .hasMessageContaining("closing parenthesis");
    }

    @Test
    void rejectsAFunctionWithNoItemReference() {
        assertThatThrownBy(() -> ExpressionParser.parse("max(3m)=0"))
                .isInstanceOf(ExpressionParser.ExpressionException.class)
                .hasMessageContaining("item reference");
    }

    @Test
    void errorMessagesNameThePositionAndTheExpression() {
        // The expression is the thing operators debug most often, so a parse
        // failure has to say where it went wrong, not just that it did.
        assertThatThrownBy(() -> ExpressionParser.parse("last(/h/k) === 1"))
                .isInstanceOf(ExpressionParser.ExpressionException.class)
                .hasMessageContaining("position")
                .hasMessageContaining("last(/h/k) === 1");
    }
}
