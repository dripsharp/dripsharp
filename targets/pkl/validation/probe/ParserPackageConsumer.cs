using System;
using System.Linq;
using DripSharp.Brine.Parser;
using DripSharp.Brine.Parser.Syntax;
using GenericNode = DripSharp.Brine.Parser.Syntax.Generic.Node;

static class Check
{
    public static void That(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }
}

sealed class IdentifierVisitor : BaseParserVisitor<int>
{
    protected override int DefaultValue() => 0;

    protected override int AggregateResult(int result, int nextResult) => result + nextResult;

    public override int VisitIdentifier(Identifier identifier) => 1;
}

static class PackageConsumer
{
    public static void Main()
    {
        const string source = "name = 42\n";
        var sourceChars = source.ToCharArray();

        var lexer = new Lexer(source);
        Check.That(ReferenceEquals(lexer.Next(), Token.IDENTIFIER), "identifier token");
        Check.That(lexer.Text() == "name" && lexer.Span() == new Span(0, 4), "identifier text/span");
        Check.That(ReferenceEquals(lexer.Next(), Token.ASSIGN), "assignment token");
        Check.That(lexer.Span() == new Span(5, 1), "assignment span");
        Check.That(ReferenceEquals(lexer.Next(), Token.INT), "integer token");
        Check.That(lexer.Text() == "42" && lexer.Span() == new Span(7, 2), "integer text/span");
        Check.That(ReferenceEquals(lexer.Next(), Token.EOF), "end token");

        var span = new Span(2, 3);
        Check.That(span.StopIndex() == 4 && span.StopIndexExclusive() == 5, "span endpoints");
        Check.That(span.Adjacent(new Span(5, 2)), "span adjacency");
        Check.That(span.Grow(2) == new Span(2, 5), "span growth");

        var parser = new Parser();
        DripSharp.Brine.Parser.Syntax.Module module = parser.ParseModule(source);
        DripSharp.Brine.Parser.Syntax.Module equivalentModule = new Parser().ParseModule(source);
        DripSharp.Brine.Parser.Syntax.Module differentModule = new Parser().ParseModule("other = 420\n");
        Check.That(module.Equals(equivalentModule), "independent typed tree structural equality");
        Check.That(module.GetHashCode() == equivalentModule.GetHashCode(), "typed equality/hash consistency");
        Check.That(!module.Equals(differentModule), "different typed trees compare unequal");
        Check.That(module.GetProperties().Count == 1, "syntax property count");
        ClassProperty property = module.GetProperties()[0];
        Check.That(property.GetName().GetValue() == "name", "syntax property name");
        Check.That(property.GetName().Text(sourceChars) == "name", "syntax node text");
        Check.That(module.Children().Any(), "syntax children traversal");
        Check.That(module.Accept(new IdentifierVisitor()) == 1, "visitor traversal");

        GenericNode generic = new GenericParser().ParseModule(source);
        GenericNode equivalentGeneric = new GenericParser().ParseModule(source);
        GenericNode differentGeneric = new GenericParser().ParseModule("other = 420\n");
        Check.That(generic.Equals(equivalentGeneric), "independent generic tree structural equality");
        Check.That(generic.GetHashCode() == equivalentGeneric.GetHashCode(), "generic equality/hash consistency");
        Check.That(!generic.Equals(differentGeneric), "different generic trees compare unequal");
        Check.That(generic.children.Count > 0, "generic parser children");
        Check.That(generic.Text(sourceChars) == source.TrimEnd('\n'), "generic parser source span");
        Check.That(generic.span.CharIndex == 0 && generic.span.LineBegin == 1, "generic full span");

        try
        {
            parser.ParseModule("name =");
            throw new InvalidOperationException("invalid input did not produce ParserError");
        }
        catch (ParserError error)
        {
            Check.That(error.Message.Contains("Unexpected end of file", StringComparison.Ordinal),
                "resource-backed parser error message");
            Check.That(error.Span().CharIndex >= 0, "parser error span");
        }

        Console.WriteLine("Independent Brine Pkl parser package consumer passed.");
    }
}
