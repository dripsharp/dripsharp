import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.pkl.parser.GenericParser;
import org.pkl.parser.GenericParserError;
import org.pkl.parser.Lexer;
import org.pkl.parser.BaseParserVisitor;
import org.pkl.parser.Parser;
import org.pkl.parser.ParserError;
import org.pkl.parser.Span;
import org.pkl.parser.Token;
import org.pkl.parser.syntax.Node;
import org.pkl.parser.syntax.Identifier;
import org.pkl.parser.syntax.generic.FullSpan;
import org.pkl.parser.syntax.generic.NodeType;

/** Independent JVM behavior oracle for the public upstream pkl-parser API. */
public final class UpstreamOracle {
  private static final Base64.Encoder BASE64 = Base64.getEncoder();
  private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();
  private static final Map<Token, String> TOKEN_NAMES = constantNames(Token.class);
  private static final Map<NodeType, String> NODE_TYPE_NAMES = constantNames(NodeType.class);

  private UpstreamOracle() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("manifest and output paths are required");
    var manifest = Path.of(args[0]);
    var output = Path.of(args[1]);
    try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
      write(writer, "@span", "SPAN", spanObservations());
      write(writer, "@identifier", "IDENTIFIER", identifierObservations());
      write(writer, "@equality", "EQUALITY", equalityObservations());
      for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
        if (line.isEmpty()) continue;
        String[] fields = line.split("\\t", -1);
        if (fields.length != 2) throw new IllegalArgumentException("invalid manifest line");
        String id = fields[0];
        String source = new String(BASE64_DECODER.decode(fields[1]), StandardCharsets.UTF_8);
        write(writer, id, "LEXER", observeLexer(source));
        write(writer, id, "GENERIC", observeGeneric(source));
        write(writer, id, "TYPED", observeTyped(source));
      }
    }
  }

  private static String spanObservations() {
    var results = new StringBuilder();
    appendSpan(results, new Span(10, 20).endWith(new Span(20, 20)));
    appendSpan(results, new Span(10, 20).endWith(new Span(0, 40)));
    appendSpan(results, new Span(10, 30).endWith(new Span(20, 20)));
    appendSpan(results, new Span(10, 30).endWith(new Span(20, 5)));
    var span = new Span(2, 3);
    results.append('|').append(span.stopIndex()).append(',').append(span.stopIndexExclusive());
    results.append(',').append(span.adjacent(new Span(5, 2)));
    appendSpan(results, span.grow(2));
    appendSpan(results, span.move(4));
    results.append('|').append(new Span(0, 10).contains(new Span(2, 3)));
    return results.toString();
  }

  private static String identifierObservations() {
    String[] identifiers = {"pigeon", "_pigeon", "f_red", "$pigeon", "f$red", "जावास्क्रिप्ट", "this", "😀"};
    var result = new StringBuilder();
    for (String identifier : identifiers) {
      result.append(BASE64.encodeToString(identifier.getBytes(StandardCharsets.UTF_8)))
          .append(',').append(Lexer.isRegularIdentifier(identifier))
          .append(',').append(BASE64.encodeToString(Lexer.maybeQuoteIdentifier(identifier).getBytes(StandardCharsets.UTF_8)))
          .append(';');
    }
    return result.toString();
  }

  private static String equalityObservations() {
    var source = "name = 42\n";
    var generic = new GenericParser().parseModule(source);
    var equivalentGeneric = new GenericParser().parseModule(source);
    var differentGeneric = new GenericParser().parseModule("other = 420\n");
    Node typed = new Parser().parseModule(source);
    Node equivalentTyped = new Parser().parseModule(source);
    Node differentTyped = new Parser().parseModule("other = 420\n");

    var list = List.of("outer", List.of("inner"));
    var equivalentList = Arrays.asList("outer", List.of("inner"));
    var differentList = List.of("outer", List.of("different"));
    Object[] array = {"outer", new Object[] {"inner"}};
    Object[] equivalentArray = {"outer", new Object[] {"inner"}};
    Object[] differentArray = {"outer", new Object[] {"different"}};

    return observations(
        generic.equals(equivalentGeneric),
        generic.hashCode() == equivalentGeneric.hashCode(),
        !generic.equals(differentGeneric),
        typed.equals(equivalentTyped),
        typed.hashCode() == equivalentTyped.hashCode(),
        !typed.equals(differentTyped),
        Objects.equals(list, equivalentList),
        Objects.hash(list) == Objects.hash(equivalentList),
        !Objects.equals(list, differentList),
        Objects.deepEquals(array, equivalentArray),
        !Objects.deepEquals(array, differentArray));
  }

  private static String observations(boolean... values) {
    var result = new StringBuilder();
    for (boolean value : values) result.append(value).append(';');
    return result.toString();
  }

  private static String observeLexer(String source) {
    try {
      var lexer = new Lexer(source);
      var result = new StringBuilder("OK|");
      while (true) {
        Token token = lexer.next();
        FullSpan span = lexer.fullSpan();
        result.append(TOKEN_NAMES.get(token)).append('@');
        appendFullSpan(result, span);
        result.append(',').append(lexer.getNewLinesBetween()).append(',')
            .append(BASE64.encodeToString(lexer.text().getBytes(StandardCharsets.UTF_8))).append(';');
        if (token == Token.EOF) break;
      }
      return result.toString();
    } catch (ParserError error) {
      return parserError(error);
    }
  }

  private static String observeGeneric(String source) {
    try {
      var root = new GenericParser().parseModule(source);
      var result = new StringBuilder("OK|");
      appendGeneric(result, root);
      return result.toString();
    } catch (GenericParserError error) {
      var result = new StringBuilder("ERROR|GenericParserError|");
      appendFullSpan(result, error.getSpan());
      result.append('|').append(BASE64.encodeToString(error.getMessage().getBytes(StandardCharsets.UTF_8)));
      return result.toString();
    } catch (ParserError error) {
      return parserError(error);
    }
  }

  private static String observeTyped(String source) {
    try {
      Node root = new Parser().parseModule(source);
      var result = new StringBuilder("OK|");
      appendTyped(result, root);
      result.append("|VISITOR|").append(root.accept(new IdentifierOrderVisitor()));
      return result.toString();
    } catch (ParserError error) {
      return parserError(error);
    }
  }

  private static String parserError(ParserError error) {
    var result = new StringBuilder("ERROR|ParserError|");
    appendSpan(result, error.span());
    result.append('|').append(BASE64.encodeToString(error.getMessage().getBytes(StandardCharsets.UTF_8)));
    return result.toString();
  }

  private static void appendGeneric(StringBuilder result, org.pkl.parser.syntax.generic.Node node) {
    result.append(NODE_TYPE_NAMES.get(node.type)).append('@');
    appendFullSpan(result, node.span);
    result.append('[');
    for (var child : node.children) appendGeneric(result, child);
    result.append(']');
  }

  private static void appendTyped(StringBuilder result, Node node) {
    result.append(node.getClass().getSimpleName()).append('@');
    appendSpan(result, node.span());
    result.append('[');
    for (Node child : node.children()) if (child != null) appendTyped(result, child);
    result.append(']');
  }

  private static void appendSpan(StringBuilder result, Span span) {
    result.append(span.charIndex()).append(',').append(span.length());
  }

  private static void appendFullSpan(StringBuilder result, FullSpan span) {
    result.append(span.charIndex()).append(',').append(span.length()).append(',')
        .append(span.lineBegin()).append(',').append(span.colBegin()).append(',')
        .append(span.lineEnd()).append(',').append(span.colEnd());
  }

  private static void write(BufferedWriter writer, String id, String kind, String observation) throws Exception {
    writer.write(id);
    writer.write('\t');
    writer.write(kind);
    writer.write('\t');
    writer.write(BASE64.encodeToString(observation.getBytes(StandardCharsets.UTF_8)));
    writer.newLine();
  }

  private static <T> Map<T, String> constantNames(Class<T> type) {
    Map<T, String> names = new IdentityHashMap<>();
    for (var field : type.getFields()) {
      if (field.getType() == type) {
        try {
          names.put(type.cast(field.get(null)), field.getName());
        } catch (ReflectiveOperationException error) {
          throw new ExceptionInInitializerError(error);
        }
      }
    }
    return names;
  }

  private static final class IdentifierOrderVisitor extends BaseParserVisitor<String> {
    @Override
    protected String defaultValue() {
      return "";
    }

    @Override
    protected String aggregateResult(String result, String nextResult) {
      return result + nextResult;
    }

    @Override
    public String visitIdentifier(Identifier identifier) {
      return identifier.span().charIndex() + "," + identifier.span().length() + ";";
    }
  }
}
