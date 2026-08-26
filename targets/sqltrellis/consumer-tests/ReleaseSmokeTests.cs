using System.Text;
using DripSharp.SqlTrellis;
using DripSharp.SqlTrellis.Expression;
using DripSharp.SqlTrellis.Expression.Operators.Arithmetic;
using DripSharp.SqlTrellis.Parser;
using DripSharp.SqlTrellis.Schema;
using DripSharp.SqlTrellis.Statement;
using DripSharp.SqlTrellis.Statement.Insert;
using DripSharp.SqlTrellis.Statement.Select;
using DripSharp.SqlTrellis.Statement.Update;
using DripSharp.SqlTrellis.Util.Deparser;
using Xunit;
using SqlExpression = DripSharp.SqlTrellis.Expression.Expression;
using SqlStatement = DripSharp.SqlTrellis.Statement.Statement;

namespace DripSharp.SqlTrellis.ReleaseSmoke;

/// <summary>
/// A bounded release gate that complements, and does not replace, the complete
/// adapted upstream test suite shipped in DripSharp.SqlTrellis.Tests.
/// </summary>
public sealed class ReleaseSmokeTests
{
    [Fact]
    public void RepresentativeSqlParsesAndInvalidSqlFails()
    {
        var select = Assert.IsType<PlainSelect>(
            CCJSqlParserUtil.parse(
                "SELECT u.id, COUNT(o.id) AS total FROM users u " +
                "LEFT JOIN orders o ON o.user_id = u.id " +
                "WHERE u.active = TRUE GROUP BY u.id ORDER BY total DESC"));
        Assert.Equal(2, select.getSelectItems().Count);

        Assert.IsType<Insert>(
            CCJSqlParserUtil.parse(
                "INSERT INTO audit_log(id, message) VALUES (7, 'created')"));
        Assert.IsType<Update>(
            CCJSqlParserUtil.parse(
                "UPDATE accounts SET active = FALSE WHERE id = 42"));

        Assert.Throws<JSQLParserException>(
            () => CCJSqlParserUtil.parse("SELECT FROM"));
        Assert.Throws<JSQLParserException>(
            () => CCJSqlParserUtil.parse(
                "this is an unsupported statement",
                parser => parser.withUnsupportedStatements(false)));
    }

    [Fact]
    public void AstMutationIsObservedByRecursiveVisitorTraversal()
    {
        var addition = Assert.IsType<Addition>(
            CCJSqlParserUtil.parseExpression("subtotal + tax"));
        var subtotal = Assert.IsType<Column>(addition.getLeftExpression());

        Assert.Same(subtotal, subtotal.withColumnName("net_total"));

        var collector = new ColumnCollector();
        addition.accept<object, object>(collector, null!);

        Assert.Equal(new[] { "net_total", "tax" }, collector.Columns);
        Assert.Equal("net_total + tax", addition.ToString());
    }

    [Fact]
    public void PublicDeparsersRenderStatementsAndExpressions()
    {
        SqlStatement statement = CCJSqlParserUtil.parse(
            "select id, name from users where active = true order by name");
        Assert.Equal(
            "SELECT id, name FROM users WHERE active = true ORDER BY name",
            Deparse(statement));

        SqlExpression expression =
            CCJSqlParserUtil.parseExpression("price * quantity + tax");
        var expressionBuffer = new StringBuilder();
        var expressionDeParser = new ExpressionDeParser();
        expressionDeParser.setBuilder(expressionBuffer);
        expression.accept<StringBuilder, object>(expressionDeParser, null!);

        Assert.Equal("price * quantity + tax", expressionBuffer.ToString());
    }

    [Theory]
    [InlineData(
        "SELECT u.id, COUNT(o.id) AS total FROM users u LEFT JOIN orders o " +
        "ON o.user_id = u.id GROUP BY u.id ORDER BY total DESC")]
    [InlineData("INSERT INTO audit_log(id, message) VALUES (7, 'created')")]
    [InlineData("UPDATE accounts SET active = FALSE WHERE id = 42")]
    public void ParseDeparseRoundTripsAreStable(string sql)
    {
        SqlStatement first = CCJSqlParserUtil.parse(sql);
        string deparsed = Deparse(first);
        SqlStatement reparsed = CCJSqlParserUtil.parse(deparsed);

        Assert.Equal(first.ToString(), reparsed.ToString());
        Assert.Equal(deparsed, Deparse(reparsed));
    }

    private static string Deparse(SqlStatement statement)
    {
        var buffer = new StringBuilder();
        statement.accept<StringBuilder, object>(
            new StatementDeParser(buffer), null!);
        return buffer.ToString();
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
