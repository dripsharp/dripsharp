using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;
using Pkl.Parser;
using Pkl.Parser.Syntax;
using Pkl.Parser.Syntax.Generic;
using GenericNode = Pkl.Parser.Syntax.Generic.Node;
using SyntaxNode = Pkl.Parser.Syntax.Node;

static class PackageProbe
{
    static readonly Dictionary<Token, string> TokenNames = ConstantNames<Token>();
    static readonly Dictionary<NodeType, string> NodeTypeNames = ConstantNames<NodeType>();

    public static void Main(string[] args)
    {
        if (args.Length != 2) throw new ArgumentException("manifest and output paths are required");
        using var writer = new StreamWriter(args[1], false, new UTF8Encoding(false));
        Write(writer, "@span", "SPAN", SpanObservations());
        Write(writer, "@identifier", "IDENTIFIER", IdentifierObservations());
        Write(writer, "@equality", "EQUALITY", EqualityObservations());
        foreach (string line in File.ReadLines(args[0], Encoding.UTF8))
        {
            if (line.Length == 0) continue;
            string[] fields = line.Split('\t');
            if (fields.Length != 2) throw new ArgumentException("invalid manifest line");
            string id = fields[0];
            string source = Encoding.UTF8.GetString(Convert.FromBase64String(fields[1]));
            Write(writer, id, "LEXER", ObserveLexer(source));
            Write(writer, id, "GENERIC", ObserveGeneric(source));
            Write(writer, id, "TYPED", ObserveTyped(source));
        }
    }

    static string SpanObservations()
    {
        var result = new StringBuilder();
        AppendSpan(result, new Span(10, 20).EndWith(new Span(20, 20)));
        AppendSpan(result, new Span(10, 20).EndWith(new Span(0, 40)));
        AppendSpan(result, new Span(10, 30).EndWith(new Span(20, 20)));
        AppendSpan(result, new Span(10, 30).EndWith(new Span(20, 5)));
        var span = new Span(2, 3);
        result.Append('|').Append(span.StopIndex()).Append(',').Append(span.StopIndexExclusive());
        result.Append(',').Append(span.Adjacent(new Span(5, 2)).ToString().ToLowerInvariant());
        AppendSpan(result, span.Grow(2));
        AppendSpan(result, span.Move(4));
        result.Append('|').Append(new Span(0, 10).Contains(new Span(2, 3)).ToString().ToLowerInvariant());
        return result.ToString();
    }

    static string IdentifierObservations()
    {
        string[] identifiers = { "pigeon", "_pigeon", "f_red", "$pigeon", "f$red", "जावास्क्रिप्ट", "this", "😀" };
        var result = new StringBuilder();
        foreach (string identifier in identifiers)
        {
            result.Append(B64(identifier)).Append(',')
                .Append(Lexer.IsRegularIdentifier(identifier).ToString().ToLowerInvariant()).Append(',')
                .Append(B64(Lexer.MaybeQuoteIdentifier(identifier))).Append(';');
        }
        return result.ToString();
    }

    static string EqualityObservations()
    {
        const string source = "name = 42\n";
        GenericNode generic = new GenericParser().ParseModule(source);
        GenericNode equivalentGeneric = new GenericParser().ParseModule(source);
        GenericNode differentGeneric = new GenericParser().ParseModule("other = 420\n");
        SyntaxNode typed = new Parser().ParseModule(source);
        SyntaxNode equivalentTyped = new Parser().ParseModule(source);
        SyntaxNode differentTyped = new Parser().ParseModule("other = 420\n");

        var list = new List<object> { "outer", new List<string> { "inner" } };
        var equivalentList = new List<object> { "outer", new List<string> { "inner" } };
        var differentList = new List<object> { "outer", new List<string> { "different" } };
        object[] array = { "outer", new object[] { "inner" } };
        object[] equivalentArray = { "outer", new object[] { "inner" } };
        object[] differentArray = { "outer", new object[] { "different" } };

        System.Type compat = typeof(Parser).Assembly.GetType("DripSharp.Runtime.JavaCompat", throwOnError: true)!;
        MethodInfo equals = compat.GetMethod("Equals", BindingFlags.NonPublic | BindingFlags.Static)!;
        MethodInfo deepEquals = compat.GetMethod("DeepEquals", BindingFlags.NonPublic | BindingFlags.Static)!;
        MethodInfo hash = compat.GetMethod("Hash", BindingFlags.NonPublic | BindingFlags.Static)!;
        bool CompatEquals(object left, object right) => (bool)equals.Invoke(null, new[] { left, right })!;
        bool CompatDeepEquals(object left, object right) => (bool)deepEquals.Invoke(null, new[] { left, right })!;
        int CompatHash(object value) => (int)hash.Invoke(null, new object?[] { new[] { value } })!;

        return Observations(
            generic.Equals(equivalentGeneric),
            generic.GetHashCode() == equivalentGeneric.GetHashCode(),
            !generic.Equals(differentGeneric),
            typed.Equals(equivalentTyped),
            typed.GetHashCode() == equivalentTyped.GetHashCode(),
            !typed.Equals(differentTyped),
            CompatEquals(list, equivalentList),
            CompatHash(list) == CompatHash(equivalentList),
            !CompatEquals(list, differentList),
            CompatDeepEquals(array, equivalentArray),
            !CompatDeepEquals(array, differentArray));
    }

    static string Observations(params bool[] values)
    {
        var result = new StringBuilder();
        foreach (bool value in values) result.Append(value.ToString().ToLowerInvariant()).Append(';');
        return result.ToString();
    }

    static string ObserveLexer(string source)
    {
        try
        {
            var lexer = new Lexer(source);
            var result = new StringBuilder("OK|");
            while (true)
            {
                Token token = lexer.Next();
                FullSpan span = lexer.FullSpan();
                result.Append(TokenNames[token]).Append('@');
                AppendFullSpan(result, span);
                result.Append(',').Append(lexer.GetNewLinesBetween()).Append(',').Append(B64(lexer.Text())).Append(';');
                if (ReferenceEquals(token, Token.EOF)) break;
            }
            return result.ToString();
        }
        catch (ParserError error)
        {
            return ParserErrorObservation(error);
        }
    }

    static string ObserveGeneric(string source)
    {
        try
        {
            GenericNode root = new GenericParser().ParseModule(source);
            var result = new StringBuilder("OK|");
            AppendGeneric(result, root);
            return result.ToString();
        }
        catch (GenericParserError error)
        {
            var result = new StringBuilder("ERROR|GenericParserError|");
            AppendFullSpan(result, error.GetSpan());
            return result.Append('|').Append(B64(error.Message)).ToString();
        }
        catch (ParserError error)
        {
            return ParserErrorObservation(error);
        }
    }

    static string ObserveTyped(string source)
    {
        try
        {
            SyntaxNode root = new Parser().ParseModule(source);
            var result = new StringBuilder("OK|");
            AppendTyped(result, root);
            result.Append("|VISITOR|").Append(root.Accept(new IdentifierOrderVisitor()));
            return result.ToString();
        }
        catch (ParserError error)
        {
            return ParserErrorObservation(error);
        }
    }

    static string ParserErrorObservation(ParserError error)
    {
        var result = new StringBuilder("ERROR|ParserError|");
        AppendSpan(result, error.Span());
        return result.Append('|').Append(B64(error.Message)).ToString();
    }

    static void AppendGeneric(StringBuilder result, GenericNode node)
    {
        result.Append(NodeTypeNames[node.type]).Append('@');
        AppendFullSpan(result, node.span);
        result.Append('[');
        foreach (GenericNode child in node.children) AppendGeneric(result, child);
        result.Append(']');
    }

    static void AppendTyped(StringBuilder result, SyntaxNode node)
    {
        result.Append(node.GetType().Name).Append('@');
        AppendSpan(result, node.Span());
        result.Append('[');
        foreach (SyntaxNode? child in node.Children()) if (child is not null) AppendTyped(result, child);
        result.Append(']');
    }

    static void AppendSpan(StringBuilder result, Span span) =>
        result.Append(span.CharIndex).Append(',').Append(span.Length);

    static void AppendFullSpan(StringBuilder result, FullSpan span) =>
        result.Append(span.CharIndex).Append(',').Append(span.Length).Append(',')
            .Append(span.LineBegin).Append(',').Append(span.ColBegin).Append(',')
            .Append(span.LineEnd).Append(',').Append(span.ColEnd);

    static void Write(StreamWriter writer, string id, string kind, string observation) =>
        writer.WriteLine($"{id}\t{kind}\t{B64(observation)}");

    static string B64(string value) => Convert.ToBase64String(Encoding.UTF8.GetBytes(value));

    static Dictionary<T, string> ConstantNames<T>() where T : class
    {
        var result = new Dictionary<T, string>(ReferenceEqualityComparer.Instance);
        foreach (FieldInfo field in typeof(T).GetFields(BindingFlags.Public | BindingFlags.Static))
        {
            if (field.FieldType == typeof(T)) result.Add((T)field.GetValue(null)!, field.Name);
        }
        return result;
    }

    sealed class IdentifierOrderVisitor : BaseParserVisitor<string>
    {
        protected override string DefaultValue() => "";
        protected override string AggregateResult(string result, string nextResult) => result + nextResult;
        public override string VisitIdentifier(Identifier identifier) =>
            $"{identifier.Span().CharIndex},{identifier.Span().Length};";
    }
}
