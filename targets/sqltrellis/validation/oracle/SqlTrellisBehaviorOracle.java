import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.Node;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.util.TablesNamesFinder;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.StatementDeParser;
import net.sf.jsqlparser.util.validation.Validation;
import net.sf.jsqlparser.util.validation.ValidationCapability;
import net.sf.jsqlparser.util.validation.feature.DatabaseType;
import net.sf.jsqlparser.util.validation.feature.FeaturesAllowed;

/** Deterministic behavior oracle compiled from the pinned JSqlParser 5.3 sources. */
public final class SqlTrellisBehaviorOracle {
  private interface ThrowingAction {
    void run() throws Exception;
  }

  private static final List<String> observations = new ArrayList<String>();

  static {
    observations.add("DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1");
  }

  private SqlTrellisBehaviorOracle() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Expected an output trace path.");
    }

    observeAst();
    observeDeparsing();
    observeDialects();
    observeErrors();
    observeFeatures();
    observeMutation();
    observeParsing();
    observeResources();
    observeRoundTrips();
    observeValidation();
    observeVisitors();

    Files.write(Path.of(args[0]), observations, StandardCharsets.UTF_8);
    System.out.println(
        "Pinned JSqlParser 5.3 behavior oracle passed: "
            + (observations.size() - 1)
            + " observations.");
  }

  private static void observeAst() throws Exception {
    String sql =
        "SELECT u.id, COUNT(o.id) AS total FROM users u "
            + "LEFT JOIN orders o ON o.user_id = u.id "
            + "WHERE u.active = TRUE GROUP BY u.id ORDER BY total DESC";
    Node root = CCJSqlParserUtil.parseAST(sql);
    observe("ast", "jjtree-root", join(root.toString(), root.jjtGetNumChildren() > 0));

    Set<String> tables = new TreeSet<String>(TablesNamesFinder.findTables(sql));
    observe("ast", "table-discovery", String.join(",", tables));

    Expression expression = CCJSqlParserUtil.parseExpression("price * quantity + tax");
    observe("ast", "expression-tree", expression.toString());
  }

  private static void observeDeparsing() throws Exception {
    Statement statement =
        CCJSqlParserUtil.parse(
            "SELECT id, name FROM users WHERE active = TRUE ORDER BY name");
    StringBuilder statementBuffer = new StringBuilder();
    statement.accept(new StatementDeParser(statementBuffer));
    observe("deparse", "statement-visitor", statementBuffer.toString());

    Expression expression = CCJSqlParserUtil.parseExpression("price * quantity + tax");
    StringBuilder expressionBuffer = new StringBuilder();
    ExpressionDeParser expressionDeParser = new ExpressionDeParser();
    expressionDeParser.setBuilder(expressionBuffer);
    expression.accept(expressionDeParser, null);
    observe("deparse", "expression-visitor", expressionBuffer.toString());
  }

  private static void observeDialects() throws Exception {
    observe(
        "dialects",
        "mysql",
        CCJSqlParserUtil.parse(
                "SELECT SQL_CALC_FOUND_ROWS `id` FROM `users` LIMIT 5")
            .toString());
    observe(
        "dialects",
        "oracle",
        CCJSqlParserUtil.parse(
                "SELECT /*+ INDEX(t idx_t) */ t.id FROM t WHERE ROWNUM <= 10")
            .toString());
    observe(
        "dialects",
        "postgresql",
        CCJSqlParserUtil.parse(
                "SELECT payload->>'name' FROM events WHERE id = $1")
            .toString());
    observe(
        "dialects",
        "sqlite",
        CCJSqlParserUtil.parse(
                "INSERT OR REPLACE INTO users(id, name) VALUES (1, 'Ada')")
            .toString());
    observe(
        "dialects",
        "sqlserver",
        CCJSqlParserUtil.parse(
                "SELECT TOP 5 [u].[id] FROM [users] [u] ORDER BY [u].[id]",
                parser -> parser.withSquareBracketQuotation(true))
            .toString());
  }

  private static void observeErrors() {
    observe(
        "errors",
        "invalid-long",
        failureKind(() -> new LongValue("")));
    observe(
        "errors",
        "invalid-sql",
        failureKind(() -> CCJSqlParserUtil.parse("SELECT FROM")));
    observe(
        "errors",
        "unsupported-disabled",
        failureKind(
            () ->
                CCJSqlParserUtil.parse(
                    "this is an unsupported statement",
                    parser -> parser.withUnsupportedStatements(false))));
  }

  private static void observeFeatures() throws Exception {
    Statement bracketed =
        CCJSqlParserUtil.parse(
            "SELECT [u].[id] FROM [users] [u]",
            parser -> parser.withSquareBracketQuotation(true));
    observe("features", "square-bracket-quotation", bracketed.toString());

    Statement unsupported =
        CCJSqlParserUtil.parse(
            "this is an unsupported statement",
            parser -> parser.withUnsupportedStatements(true));
    observe("features", "unsupported-statements", unsupported.toString());

    Statements recovered =
        CCJSqlParserUtil.parseStatements(
            "SELECT 1; this is an unsupported statement; SELECT 2;",
            parser -> parser.withUnsupportedStatements(true).withErrorRecovery(true));
    observe(
        "features",
        "error-recovery",
        join(recovered.size(), recovered.get(1).toString()));
  }

  private static void observeMutation() {
    LongValue value = new LongValue("+0042");
    boolean sameValue = value == value.withValue(7);
    observe("mutation", "long-value", join(sameValue, value.getValue(), value.toString()));

    Table table = new Table("public", "orders");
    table.withAlias(new Alias("o").withUseAs(false));
    Column column = new Column(table, "id");
    boolean sameColumn = column == column.withColumnName("order_id");
    observe(
        "mutation",
        "schema-column",
        join(sameColumn, table.toString(), column.getFullyQualifiedName(), column.toString()));

    ExpressionList<Expression> expressions = new ExpressionList<Expression>(value, column);
    boolean sameList = expressions == expressions.addExpression(new LongValue(9));
    observe("mutation", "expression-list", join(sameList, expressions.size(), expressions));

    Addition addition =
        new Addition()
            .withLeftExpression(new LongValue(2))
            .withRightExpression(new LongValue(3));
    boolean sameAddition = addition == addition.withRightExpression(new LongValue(5));
    PlainSelect select =
        new PlainSelect()
            .withSelectItems(
                new SelectItem<Expression>(value), new SelectItem<Expression>(column))
            .withFromItem(table);
    observe(
        "mutation",
        "programmatic-ast",
        join(sameAddition, addition.toString(), select.toString()));
  }

  private static void observeParsing() throws Exception {
    observe(
        "parsing",
        "select",
        CCJSqlParserUtil.parse(
                "select a, count(*) as n from t where a > 1 group by a")
            .toString());
    observe(
        "parsing",
        "expression",
        CCJSqlParserUtil.parseExpression("a + b * 3").toString());
    observe(
        "parsing",
        "condition",
        CCJSqlParserUtil.parseCondExpression("a BETWEEN 1 AND 3 OR b IS NULL")
            .toString());
    Statements statements =
        CCJSqlParserUtil.parseStatements(
            "UPDATE t SET value = 1 WHERE id = 2; DELETE FROM t WHERE id = 3;");
    observe("parsing", "statement-list", join(statements.size(), statements.toString()));
  }

  private static void observeResources() throws Exception {
    InputStream stream =
        SqlTrellisBehaviorOracle.class.getClassLoader().getResourceAsStream("rr/xhtml2rst.xsl");
    if (stream == null) {
      throw new IllegalStateException("JSqlParser resource rr/xhtml2rst.xsl is missing.");
    }
    try {
      observe("resources", "xhtml2rst", hex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));
    } finally {
      stream.close();
    }
  }

  private static void observeRoundTrips() throws Exception {
    roundTrip(
        "select",
        "SELECT u.id, COUNT(o.id) AS total FROM users u LEFT JOIN orders o "
            + "ON o.user_id = u.id GROUP BY u.id ORDER BY total DESC");
    roundTrip(
        "insert",
        "INSERT INTO audit_log(id, message) VALUES (7, 'created')");
    roundTrip(
        "ddl",
        "CREATE TABLE sample (id BIGINT PRIMARY KEY, name VARCHAR(80) NOT NULL)");
  }

  private static void observeValidation() throws Exception {
    List<ValidationCapability> none = Collections.emptyList();
    observe(
        "validation",
        "parse-only",
        String.valueOf(Validation.validate(none, "SELECT * FROM tab1").size()));

    String oldJoin = "SELECT * FROM tab1, tab2 WHERE tab1.id (+) = tab2.ref";
    observe(
        "validation",
        "database-feature",
        String.valueOf(
            Validation.validate(
                    Arrays.<ValidationCapability>asList(DatabaseType.SQLSERVER), oldJoin)
                .size()));

    String update = "UPDATE tab1 t1 SET t1.ref = ? WHERE t1.id = ?";
    observe(
        "validation",
        "allowed-features",
        String.valueOf(
            Validation.validate(
                    Arrays.<ValidationCapability>asList(
                        DatabaseType.POSTGRESQL,
                        FeaturesAllowed.SELECT.copy().add(FeaturesAllowed.JDBC)),
                    update)
                .size()));
  }

  private static void observeVisitors() throws Exception {
    LongValue value = new LongValue(7);
    observe("visitors", "expression-dispatch", value.accept(new LongVisitor(), "long"));

    ExplainStatement explain = new ExplainStatement("EXPLAIN", new Table("orders"));
    observe("visitors", "statement-dispatch", explain.accept(new ExplainVisitor(), "stmt"));

    Select select = (Select) CCJSqlParserUtil.parse("SELECT id, name FROM users");
    observe("visitors", "select-dispatch", select.accept(new SelectShapeVisitor(), "select"));

    ColumnCollector collector = new ColumnCollector();
    CCJSqlParserUtil.parseExpression("a + b * c").accept(collector, null);
    observe("visitors", "expression-traversal", String.join(",", collector.columns));
  }

  private static void roundTrip(String id, String sql) throws Exception {
    String first = CCJSqlParserUtil.parse(sql).toString();
    String second = CCJSqlParserUtil.parse(first).toString();
    observe("roundtrip", id, join(first.equals(second), second));
  }

  private static void observe(String family, String id, String value) {
    if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
      value = value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
    observations.add(family + "\t" + id + "\t" + value);
  }

  private static String join(Object... values) {
    StringBuilder result = new StringBuilder();
    for (int index = 0; index < values.length; index++) {
      if (index > 0) {
        result.append('|');
      }
      result.append(String.valueOf(values[index]).toLowerCase());
    }
    return result.toString();
  }

  private static String failureKind(ThrowingAction action) {
    try {
      action.run();
      return "none";
    } catch (IllegalArgumentException error) {
      return "argument";
    } catch (JSQLParserException error) {
      return "parse";
    } catch (Exception error) {
      return "other";
    }
  }

  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(String.format("%02x", value & 0xff));
    }
    return result.toString();
  }

  private static final class LongVisitor extends ExpressionVisitorAdapter<String> {
    @Override
    public <S> String visit(LongValue longValue, S context) {
      return context + ":" + longValue.getValue();
    }
  }

  private static final class ExplainVisitor extends StatementVisitorAdapter<String> {
    @Override
    public <S> String visit(ExplainStatement statement, S context) {
      return context + ":" + statement.getKeyword();
    }
  }

  private static final class SelectShapeVisitor extends SelectVisitorAdapter<String> {
    @Override
    public <S> String visit(PlainSelect select, S context) {
      return context + ":" + select.getSelectItems().size();
    }
  }

  private static final class ColumnCollector extends ExpressionVisitorAdapter<Void> {
    private final List<String> columns = new ArrayList<String>();

    @Override
    public <S> Void visit(Column column, S context) {
      columns.add(column.getColumnName());
      return null;
    }
  }
}
