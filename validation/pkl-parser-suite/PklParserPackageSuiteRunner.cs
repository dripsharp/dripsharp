using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using DripSharp.Brine.Parser;
using DripSharp.Brine.Parser.Syntax;
using DripSharp.Brine.Parser.Syntax.Generic;
using GenericNode = DripSharp.Brine.Parser.Syntax.Generic.Node;
using SyntaxNode = DripSharp.Brine.Parser.Syntax.Node;

static class Program
{
    const string ContractMagic = "DRIPSHARP_PKL_PARSER_TEST_CONTRACT_V1";
    const string ResultMagic = "DRIPSHARP_PKL_PARSER_SUITE_RESULTS_V1";

    static readonly Dictionary<Token, string> TokenNames = ConstantNames<Token>();
    static readonly Dictionary<NodeType, string> NodeTypeNames = ConstantNames<NodeType>();
    static readonly int[] UnicodeCommentCodepoints =
    {
        0x0000, 0x0001, 0x007f, 0x0080, 0x7ffe, 0x7fff, 0x8000, 0xfffe, 0xffff
    };

    public static int Main(string[] args)
    {
        if (args.Length != 4)
        {
            Console.Error.WriteLine(
                "Usage: runner <contract> <output> <fixtures-root> <workers>");
            return 2;
        }

        try
        {
            string contractPath = Path.GetFullPath(args[0]);
            string outputPath = Path.GetFullPath(args[1]);
            string fixturesRoot = Path.GetFullPath(args[2]);
            int workers = int.Parse(args[3], System.Globalization.CultureInfo.InvariantCulture);
            if (workers <= 0) throw new ArgumentOutOfRangeException(nameof(workers));

            Contract contract = Contract.Read(contractPath);
            var results = new Result[contract.Cases.Count];
            Parallel.ForEach(
                Enumerable.Range(0, contract.Cases.Count),
                new ParallelOptions { MaxDegreeOfParallelism = workers },
                index => results[index] = Execute(contract.Cases[index], fixturesRoot));
            WriteResults(outputPath, results);
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    static Result Execute(ContractCase row, string fixturesRoot)
    {
        try
        {
            string observation = row.SourceMethod switch
            {
                "isRegularIdentifier" => IdentifierRegularObservations(),
                "maybeQuoteIdentifier" => IdentifierQuoteObservations(),
                "lexSingleBacktick" => ObserveLexer("`"),
                "rejectsSentinelBetweenTokens" =>
                    ObserveLexer("// Comment with \uFFFF character\nclass \uFFFF Bar"),
                "lineContinuationWithCRLF" =>
                    ObserveLexer("x = \"\"\"\n  hello \\\r\n  world\r\n  \"\"\""),
                "lineContinuationWithCR" =>
                    ObserveLexer("x = \"\"\"\n  hello \\\r  world\n  \"\"\""),
                "lineContinuationWhitespaceErrorWithCRLF" =>
                    ObserveLexer("x = \"\"\"\n  hello \\ \r\n  world\n  \"\"\""),
                "acceptsAllUnicodeCodepointsInComments" => UnicodeCommentObservations(),
                "endWith test" => SpanObservations(),
                "compareSnippetTests" => ParserComparisonObservation(row, fixturesRoot),
                _ => throw new InvalidDataException(
                    $"No pkl-parser package adaptation exists for {row.SourceClass}/{row.SourceMethod}.")
            };
            string actual = Sha256(observation);
            return actual == row.ExpectedSha256
                ? new Result(row.CaseId, "PASS", actual, "")
                : new Result(
                    row.CaseId,
                    "FAIL",
                    actual,
                    $"Expected observation SHA-256 {row.ExpectedSha256}, observed {actual}.");
        }
        catch (Exception error)
        {
            return new Result(
                row.CaseId,
                "FAIL",
                "-",
                error.GetType().FullName + ": " + error.Message);
        }
    }

    static string ParserComparisonObservation(ContractCase row, string fixturesRoot)
    {
        const string prefix = "pkl-core/src/test/files/LanguageSnippetTests/input/";
        Require(row.FixturePath.StartsWith(prefix, StringComparison.Ordinal),
            $"Fixture path escaped the parser corpus: {row.FixturePath}");
        string relative = row.FixturePath[prefix.Length..].Replace('/', Path.DirectorySeparatorChar);
        string fixture = Path.GetFullPath(Path.Combine(fixturesRoot, relative));
        string expectedRoot = Path.GetFullPath(fixturesRoot) + Path.DirectorySeparatorChar;
        Require(fixture.StartsWith(expectedRoot, StringComparison.Ordinal),
            $"Fixture path escaped the generated checkout: {row.FixturePath}");
        Require(File.Exists(fixture), $"Parser fixture is missing: {row.FixturePath}");
        Require(Sha256Bytes(File.ReadAllBytes(fixture)) == row.FixtureSha256,
            $"Parser fixture hash drifted: {row.FixturePath}");
        string source = File.ReadAllText(fixture, Encoding.UTF8);
        return ObserveGeneric(source) + "\n" + ObserveTyped(source);
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

    static string IdentifierRegularObservations()
    {
        var result = new StringBuilder();
        foreach (string identifier in Identifiers())
        {
            result.Append(B64(identifier)).Append(',')
                .Append(Lexer.IsRegularIdentifier(identifier).ToString().ToLowerInvariant())
                .Append(';');
        }
        return result.ToString();
    }

    static string IdentifierQuoteObservations()
    {
        var result = new StringBuilder();
        foreach (string identifier in Identifiers())
        {
            result.Append(B64(identifier)).Append(',')
                .Append(B64(Lexer.MaybeQuoteIdentifier(identifier))).Append(';');
        }
        return result.ToString();
    }

    static string[] Identifiers() =>
        new[] { "pigeon", "_pigeon", "f_red", "$pigeon", "f$red", "जावास्क्रिप्ट", "this", "😀" };

    static string UnicodeCommentObservations() =>
        string.Join(
            "\n",
            UnicodeCommentCodepoints.Select(codepoint =>
                ObserveLexer($"// Test {(char)codepoint}\nmodule Test")));

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
                result.Append(',').Append(lexer.GetNewLinesBetween()).Append(',')
                    .Append(B64(lexer.Text())).Append(';');
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
        foreach (SyntaxNode? child in node.Children())
            if (child is not null) AppendTyped(result, child);
        result.Append(']');
    }

    static void AppendSpan(StringBuilder result, Span span) =>
        result.Append(span.CharIndex).Append(',').Append(span.Length);

    static void AppendFullSpan(StringBuilder result, FullSpan span) =>
        result.Append(span.CharIndex).Append(',').Append(span.Length).Append(',')
            .Append(span.LineBegin).Append(',').Append(span.ColBegin).Append(',')
            .Append(span.LineEnd).Append(',').Append(span.ColEnd);

    static void WriteResults(string output, IEnumerable<Result> results)
    {
        using var writer = new StreamWriter(output, false, new UTF8Encoding(false));
        writer.WriteLine(ResultMagic);
        writer.WriteLine("case-id\tstatus\tobservation-sha256\tdiagnostic-base64");
        foreach (Result result in results)
        {
            writer.Write("case\t");
            writer.Write(result.CaseId);
            writer.Write('\t');
            writer.Write(result.Status);
            writer.Write('\t');
            writer.Write(result.ObservationSha256);
            writer.Write('\t');
            writer.WriteLine(B64(result.Diagnostic));
        }
    }

    static string Sha256(string value) => Sha256Bytes(Encoding.UTF8.GetBytes(value));

    static string Sha256Bytes(byte[] value) => Convert.ToHexString(SHA256.HashData(value)).ToLowerInvariant();

    static string B64(string value) => Convert.ToBase64String(Encoding.UTF8.GetBytes(value));

    static void Require(bool condition, string message)
    {
        if (!condition) throw new InvalidDataException(message);
    }

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

    sealed record Result(string CaseId, string Status, string ObservationSha256, string Diagnostic);

    sealed record ContractCase(
        string CaseId,
        string SourceClass,
        string SourceMethod,
        string FixturePath,
        string FixtureSha256,
        string ExpectedSha256);

    sealed class Contract
    {
        public List<ContractCase> Cases { get; } = new();

        public static Contract Read(string path)
        {
            string[] lines = File.ReadAllLines(path, Encoding.UTF8);
            Require(lines.Length > 2 && lines[0] == ContractMagic,
                "The pkl-parser test contract schema drifted.");
            string[]? columns = null;
            string? revision = null;
            var contract = new Contract();
            var ids = new HashSet<string>(StringComparer.Ordinal);
            foreach (string line in lines.Skip(1))
            {
                string[] fields = line.Split('\t');
                if (fields[0] == "meta" && fields.Length == 3 && fields[1] == "source-revision")
                    revision = fields[2];
                else if (fields[0] == "case-columns")
                    columns = fields.Skip(1).ToArray();
                else if (fields[0] == "case")
                {
                    Require(columns is not null && fields.Length == columns.Length + 1,
                        "A pkl-parser contract case row is malformed.");
                    var row = columns!.Zip(fields.Skip(1)).ToDictionary(
                        pair => pair.First, pair => pair.Second, StringComparer.Ordinal);
                    var value = new ContractCase(
                        Required(row, "case-id"),
                        Required(row, "source-class"),
                        Required(row, "source-method"),
                        Required(row, "fixture-path"),
                        Required(row, "fixture-sha256"),
                        Required(row, "expected-observation-sha256"));
                    Require(ids.Add(value.CaseId), $"Duplicate parser case ID {value.CaseId}.");
                    contract.Cases.Add(value);
                }
            }
            Require(revision == "f7cac257ade5775c1dfc255f4fda2eacc296e9d0",
                "The pkl-parser contract revision drifted.");
            Require(contract.Cases.Count == 849,
                $"The pkl-parser adapted case count drifted: {contract.Cases.Count}.");
            return contract;
        }

        static string Required(IReadOnlyDictionary<string, string> row, string name)
        {
            Require(row.TryGetValue(name, out string? value) && value.Length > 0,
                $"The pkl-parser contract omits {name}.");
            return value!;
        }
    }
}
