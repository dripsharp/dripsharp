using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using DripSharp.SqlTrellis;
using DripSharp.SqlTrellis.Expression;
using DripSharp.SqlTrellis.Expression.Operators.Arithmetic;
using DripSharp.SqlTrellis.Expression.Operators.Relational;
using DripSharp.SqlTrellis.Parser;
using DripSharp.SqlTrellis.Schema;
using DripSharp.SqlTrellis.Statement;
using DripSharp.SqlTrellis.Statement.Select;
using DripSharp.SqlTrellis.Util;
using DripSharp.SqlTrellis.Util.Deparser;
using DripSharp.SqlTrellis.Util.Validation;
using DripSharp.SqlTrellis.Util.Validation.Feature;

internal static class Program
{
    private static readonly List<string> Observations =
        ["DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1"];

    private static void Main(string[] args)
    {
        if (args.Length is not (1 or 2))
            throw new ArgumentException(
                "Expected an output trace path and optional canonical trace path.");

        ObserveAst();
        ObserveDeparsing();
        ObserveDialects();
        ObserveErrors();
        ObserveFeatures();
        ObserveMutation();
        ObserveParsing();
        ObserveResources();
        ObserveRoundTrips();
        ObserveValidation();
        ObserveVisitors();

        File.WriteAllLines(args[0], Observations, new UTF8Encoding(false));
        if (args.Length == 2)
            ValidateCanonical(args[1]);

        Console.WriteLine(
            $"SqlTrellis package behavior probe passed: {Observations.Count - 1} observations.");
    }

    private static void ObserveAst()
    {
        const string sql =
            "SELECT u.id, COUNT(o.id) AS total FROM users u " +
            "LEFT JOIN orders o ON o.user_id = u.id " +
            "WHERE u.active = TRUE GROUP BY u.id ORDER BY total DESC";
        var root = CCJSqlParserUtil.parseAST(sql);
        Observe("ast", "jjtree-root", Join(root.ToString(), root.jjtGetNumChildren() > 0));

        var tables = TablesNamesFinder<object>.findTables(sql)
            .OrderBy(value => value, StringComparer.Ordinal);
        Observe("ast", "table-discovery", string.Join(",", tables));

        var expression = CCJSqlParserUtil.parseExpression("price * quantity + tax");
        Observe("ast", "expression-tree", expression.ToString());
    }

    private static void ObserveDeparsing()
    {
        var statement = CCJSqlParserUtil.parse(
            "SELECT id, name FROM users WHERE active = TRUE ORDER BY name");
        var statementBuffer = new StringBuilder();
        statement.accept<StringBuilder, object>(
            new StatementDeParser(statementBuffer), null!);
        Observe("deparse", "statement-visitor", statementBuffer.ToString());

        var expression = CCJSqlParserUtil.parseExpression("price * quantity + tax");
        var expressionBuffer = new StringBuilder();
        var expressionDeParser = new ExpressionDeParser();
        expressionDeParser.setBuilder(expressionBuffer);
        expression.accept<StringBuilder, object>(expressionDeParser, null!);
        Observe("deparse", "expression-visitor", expressionBuffer.ToString());
    }

    private static void ObserveDialects()
    {
        Observe(
            "dialects",
            "mysql",
            CCJSqlParserUtil.parse(
                "SELECT SQL_CALC_FOUND_ROWS `id` FROM `users` LIMIT 5").ToString());
        Observe(
            "dialects",
            "oracle",
            CCJSqlParserUtil.parse(
                "SELECT /*+ INDEX(t idx_t) */ t.id FROM t WHERE ROWNUM <= 10").ToString());
        Observe(
            "dialects",
            "postgresql",
            CCJSqlParserUtil.parse(
                "SELECT payload->>'name' FROM events WHERE id = $1").ToString());
        Observe(
            "dialects",
            "sqlite",
            CCJSqlParserUtil.parse(
                "INSERT OR REPLACE INTO users(id, name) VALUES (1, 'Ada')").ToString());
        Observe(
            "dialects",
            "sqlserver",
            CCJSqlParserUtil.parse(
                "SELECT TOP 5 [u].[id] FROM [users] [u] ORDER BY [u].[id]",
                parser => parser.withSquareBracketQuotation(true)).ToString());
    }

    private static void ObserveErrors()
    {
        Observe(
            "errors",
            "invalid-long",
            FailureKind(() => _ = new LongValue("")));
        Observe(
            "errors",
            "invalid-sql",
            FailureKind(() => _ = CCJSqlParserUtil.parse("SELECT FROM")));
        Observe(
            "errors",
            "unsupported-disabled",
            FailureKind(() => _ = CCJSqlParserUtil.parse(
                "this is an unsupported statement",
                parser => parser.withUnsupportedStatements(false))));
    }

    private static void ObserveFeatures()
    {
        var bracketed = CCJSqlParserUtil.parse(
            "SELECT [u].[id] FROM [users] [u]",
            parser => parser.withSquareBracketQuotation(true));
        Observe("features", "square-bracket-quotation", bracketed.ToString());

        var unsupported = CCJSqlParserUtil.parse(
            "this is an unsupported statement",
            parser => parser.withUnsupportedStatements(true));
        Observe("features", "unsupported-statements", unsupported.ToString());

        var recovered = CCJSqlParserUtil.parseStatements(
            "SELECT 1; this is an unsupported statement; SELECT 2;",
            parser => parser.withUnsupportedStatements(true).withErrorRecovery(true));
        Observe(
            "features",
            "error-recovery",
            Join(recovered.Count, recovered[1].ToString()));
    }

    private static void ObserveMutation()
    {
        var value = new LongValue("+0042");
        var sameValue = ReferenceEquals(value, value.withValue(7));
        Observe("mutation", "long-value", Join(sameValue, value.getValue(), value.ToString()));

        var table = new Table("public", "orders");
        table.withAlias(new Alias("o").withUseAs(false));
        var column = new Column(table, "id");
        var sameColumn = ReferenceEquals(column, column.withColumnName("order_id"));
        Observe(
            "mutation",
            "schema-column",
            Join(sameColumn, table.ToString(), column.getFullyQualifiedName(), column.ToString()));

        var expressions = new ExpressionList<Expression>(value, column);
        var sameList = ReferenceEquals(expressions, expressions.addExpression(new LongValue(9)));
        Observe("mutation", "expression-list", Join(sameList, expressions.Count, expressions));

        var addition = new Addition()
            .withLeftExpression(new LongValue(2))
            .withRightExpression(new LongValue(3));
        var sameAddition = ReferenceEquals(
            addition, addition.withRightExpression(new LongValue(5)));
        var select = new PlainSelect()
            .withSelectItems(
                new SelectItem<Expression>(value),
                new SelectItem<Expression>(column))
            .withFromItem(table);
        Observe(
            "mutation",
            "programmatic-ast",
            Join(sameAddition, addition.ToString(), select.ToString()));
    }

    private static void ObserveParsing()
    {
        Observe(
            "parsing",
            "select",
            CCJSqlParserUtil.parse(
                "select a, count(*) as n from t where a > 1 group by a").ToString());
        Observe(
            "parsing",
            "expression",
            CCJSqlParserUtil.parseExpression("a + b * 3").ToString());
        Observe(
            "parsing",
            "condition",
            CCJSqlParserUtil.parseCondExpression(
                "a BETWEEN 1 AND 3 OR b IS NULL").ToString());
        var statements = CCJSqlParserUtil.parseStatements(
            "UPDATE t SET value = 1 WHERE id = 2; DELETE FROM t WHERE id = 3;");
        Observe("parsing", "statement-list", Join(statements.Count, statements.ToString()));
    }

    private static void ObserveResources()
    {
        using var stream = typeof(CCJSqlParserUtil).Assembly
            .GetManifestResourceStream("rr/xhtml2rst.xsl")
            ?? throw new InvalidOperationException(
                "SqlTrellis resource rr/xhtml2rst.xsl is missing.");
        Observe(
            "resources",
            "xhtml2rst",
            Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant());
    }

    private static void ObserveRoundTrips()
    {
        RoundTrip(
            "select",
            "SELECT u.id, COUNT(o.id) AS total FROM users u LEFT JOIN orders o " +
            "ON o.user_id = u.id GROUP BY u.id ORDER BY total DESC");
        RoundTrip(
            "insert",
            "INSERT INTO audit_log(id, message) VALUES (7, 'created')");
        RoundTrip(
            "ddl",
            "CREATE TABLE sample (id BIGINT PRIMARY KEY, name VARCHAR(80) NOT NULL)");
    }

    private static void ObserveValidation()
    {
        Observe(
            "validation",
            "parse-only",
            Validation.validate(
                Array.Empty<ValidationCapability>(), "SELECT * FROM tab1").Count.ToString(
                    CultureInfo.InvariantCulture));

        const string oldJoin =
            "SELECT * FROM tab1, tab2 WHERE tab1.id (+) = tab2.ref";
        Observe(
            "validation",
            "database-feature",
            Validation.validate(
                new List<ValidationCapability> { DatabaseType.SQLSERVER }, oldJoin).Count.ToString(
                    CultureInfo.InvariantCulture));

        const string update =
            "UPDATE tab1 t1 SET t1.ref = ? WHERE t1.id = ?";
        Observe(
            "validation",
            "allowed-features",
            Validation.validate(
                new List<ValidationCapability>
                {
                    DatabaseType.POSTGRESQL,
                    FeaturesAllowed.SELECT.copy().add(FeaturesAllowed.JDBC)
                },
                update).Count.ToString(CultureInfo.InvariantCulture));
    }

    private static void ObserveVisitors()
    {
        var value = new LongValue(7);
        Observe(
            "visitors",
            "expression-dispatch",
            value.accept(new LongVisitor(), "long"));

        var explain = new ExplainStatement("EXPLAIN", new Table("orders"));
        Observe(
            "visitors",
            "statement-dispatch",
            explain.accept(new ExplainVisitor(), "stmt"));

        var select = (Select)CCJSqlParserUtil.parse("SELECT id, name FROM users");
        Observe(
            "visitors",
            "select-dispatch",
            select.accept(new SelectShapeVisitor(), "select"));

        var collector = new ColumnCollector();
        CCJSqlParserUtil.parseExpression("a + b * c")
            .accept<object, object>(collector, null!);
        Observe(
            "visitors",
            "expression-traversal",
            string.Join(",", collector.Columns));
    }

    private static void RoundTrip(string id, string sql)
    {
        var first = CCJSqlParserUtil.parse(sql).ToString();
        var second = CCJSqlParserUtil.parse(first).ToString();
        Observe("roundtrip", id, Join(string.Equals(first, second, StringComparison.Ordinal), second));
    }

    private static void Observe(string family, string id, string? value)
    {
        value = (value ?? "<null>")
            .Replace("\r", "\\r", StringComparison.Ordinal)
            .Replace("\n", "\\n", StringComparison.Ordinal)
            .Replace("\t", "\\t", StringComparison.Ordinal);
        Observations.Add($"{family}\t{id}\t{value}");
    }

    private static string Join(params object?[] values) =>
        string.Join(
            "|",
            values.Select(value =>
                Convert.ToString(value, CultureInfo.InvariantCulture)!.ToLowerInvariant()));

    private static string FailureKind(Action action)
    {
        try
        {
            action();
            return "none";
        }
        catch (ArgumentException)
        {
            return "argument";
        }
        catch (JSQLParserException)
        {
            return "parse";
        }
        catch (Exception)
        {
            return "other";
        }
    }

    private static void ValidateCanonical(string canonicalPath)
    {
        var expected = File.ReadAllLines(canonicalPath, Encoding.UTF8);
        if (expected.SequenceEqual(Observations, StringComparer.Ordinal))
            return;

        var mismatch = Enumerable.Range(0, Math.Max(expected.Length, Observations.Count))
            .First(index =>
                index >= expected.Length ||
                index >= Observations.Count ||
                !string.Equals(expected[index], Observations[index], StringComparison.Ordinal));
        throw new InvalidOperationException(
            $"Canonical SqlTrellis behavior mismatch at line {mismatch + 1}. " +
            $"Expected '{At(expected, mismatch)}', observed '{At(Observations, mismatch)}'.");
    }

    private static string At(IReadOnlyList<string> values, int index) =>
        index < values.Count ? values[index] : "<missing>";

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

    private sealed class SelectShapeVisitor : SelectVisitorAdapter<string>
    {
        public override string visit<S>(PlainSelect select, S context) =>
            $"{context}:{select.getSelectItems().Count}";
    }

    private sealed class ColumnCollector : ExpressionVisitorAdapter<object>
    {
        internal List<string> Columns { get; } = [];

        public override object visit<S>(Column column, S context)
        {
            Columns.Add(column.getColumnName());
            return null!;
        }
    }
}
