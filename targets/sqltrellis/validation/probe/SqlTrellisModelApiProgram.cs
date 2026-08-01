using System;
using DripSharp.SqlTrellis.Expression;
using DripSharp.SqlTrellis.Expression.Operators.Relational;
using DripSharp.SqlTrellis.Schema;
using DripSharp.SqlTrellis.Statement;

internal static class Program
{
    private const string SuccessMessage =
        "Independent SqlTrellis model API behavior passed.";

    private static void Main()
    {
        var value = new LongValue("+0042");
        Equal(42L, value.getValue(), "numeric value");
        Equal("0042", value.ToString(), "lexical value");
        True(value.Equals(new LongValue("0042")), "value equality");
        Equal(value.GetHashCode(), new LongValue("0042").GetHashCode(),
            "value hash code");
        Same(value, value.withValue(7), "expression fluent mutation");
        Equal("7", value.ToString(), "mutated lexical value");
        Equal("long:7", value.accept(new LongVisitor(), "long"),
            "generic expression visitor");

        var table = new Table("public", "orders");
        Equal("public.orders", table.getFullyQualifiedName(), "table name");
        var alias = new Alias("o").withUseAs(false);
        Same(table, table.withAlias(alias), "schema fluent mutation");
        Equal("public.orders o", table.ToString(), "aliased table");

        var column = new Column(table, "id");
        Equal("public.orders.id", column.getFullyQualifiedName(),
            "qualified column");
        Equal("o.id", column.ToString(), "alias-aware column rendering");
        Same(column, column.withColumnName("order_id"),
            "column fluent mutation");
        Equal("o.order_id", column.ToString(), "mutated column rendering");

        var expressions = new ExpressionList<Expression>(value, column);
        Same(expressions, expressions.addExpression(new LongValue(9)),
            "generic expression-list mutation");
        Equal(3, expressions.Count, "generic expression-list count");
        Equal("7, o.order_id, 9", expressions.ToString(),
            "generic expression-list rendering");

        var option = new ExplainStatement.Option(
            ExplainStatement.OptionType.PLAN_FOR);
        Same(option, option.withValue("NEXT"), "nested option mutation");
        Equal("NEXT", option.getValue(), "nested option getter");
        Equal("PLAN FOR NEXT", option.formatOption(), "nested enum formatting");

        var explain = new ExplainStatement("EXPLAIN", table);
        Equal("EXPLAIN public.orders o", explain.ToString(),
            "statement rendering");
        Equal("stmt:EXPLAIN", explain.accept(new ExplainVisitor(), "stmt"),
            "generic statement visitor");

        Throws<ArgumentException>(() => _ = new LongValue(""),
            "constructor validation");
        Console.WriteLine(SuccessMessage);
    }

    private sealed class LongVisitor : ExpressionVisitorAdapter<string>
    {
        public override string visit<S>(LongValue longValue, S context) =>
            $"{context}:{longValue.getValue()}";
    }

    private sealed class ExplainVisitor : StatementVisitorAdapter<string>
    {
        public override string visit<S>(ExplainStatement statement, S context) =>
            $"{context}:{statement.getKeyword()}";
    }

    private static void Equal<T>(T expected, T actual, string label)
    {
        if (!Equals(expected, actual))
        {
            throw new InvalidOperationException(
                $"{label}: expected '{expected}', got '{actual}'.");
        }
    }

    private static void Same(object expected, object actual, string label)
    {
        if (!ReferenceEquals(expected, actual))
        {
            throw new InvalidOperationException($"{label}: identity changed.");
        }
    }

    private static void True(bool condition, string label)
    {
        if (!condition)
        {
            throw new InvalidOperationException($"{label}: condition was false.");
        }
    }

    private static void Throws<T>(Action action, string label) where T : Exception
    {
        try
        {
            action();
        }
        catch (T)
        {
            return;
        }

        throw new InvalidOperationException(
            $"{label}: expected {typeof(T).FullName}.");
    }
}
