using System.Reflection;
using DripSharp.Brine.Parser;
using DripSharp.Brine.Parser.Syntax;
using Xunit;
using GenericNode = DripSharp.Brine.Parser.Syntax.Generic.Node;
using PklParser = DripSharp.Brine.Parser.Parser;
using SyntaxModule = DripSharp.Brine.Parser.Syntax.Module;

namespace DripSharp.Brine.ReleaseSmoke;

public sealed class ReleaseSmokeParserTests
{
    [Fact]
    public void LexerAndParsersAcceptValidInputAndReportInvalidInput()
    {
        const string source = "greeting = \"hello\"\nanswer = 40 + 2\n";
        var lexer = new Lexer(source);

        Assert.Same(Token.IDENTIFIER, lexer.Next());
        Assert.Equal("greeting", lexer.Text());
        Assert.Equal(new Span(0, 8), lexer.Span());
        Assert.Same(Token.ASSIGN, lexer.Next());
        Assert.Same(Token.STRING_START, lexer.Next());

        ParserError lexerError = Assert.Throws<ParserError>(
            () => new Lexer("\0").Next());
        Assert.Equal(new Span(0, 1), lexerError.Span());
        Assert.NotEmpty(lexerError.Message);

        var parser = new PklParser();
        SyntaxModule module = parser.ParseModule(source);
        Assert.Equal(2, module.GetProperties().Count);

        GenericNode generic = new GenericParser().ParseModule(source);
        Assert.NotEmpty(generic.children);

        ParserError parserError = Assert.Throws<ParserError>(
            () => parser.ParseModule("name ="));
        Assert.Contains("Unexpected end of file", parserError.Message);
        Assert.True(parserError.Span().CharIndex >= 0);
    }

    [Fact]
    public void SyntaxTreeTraversalAndVisitorDispatchRemainPublic()
    {
        const string source = "answer = 42";
        char[] sourceChars = source.ToCharArray();
        SyntaxModule module = new PklParser().ParseModule(source);
        ClassProperty property = Assert.Single(module.GetProperties());

        Assert.Same(module, property.Parent());
        Assert.Contains(property, module.Children());
        Assert.Equal(source, module.Text(sourceChars));
        Assert.Equal(source, property.Text(sourceChars));

        ParserVisitor<string> visitor =
            DispatchProxy.Create<ParserVisitor<string>, RecordingVisitor>();
        var recorder = (RecordingVisitor)(object)visitor;

        Assert.Equal("VisitModule", module.Accept(visitor));
        Assert.Same(module, recorder.Argument);
    }

    public class RecordingVisitor : DispatchProxy
    {
        public object? Argument { get; private set; }

        protected override object? Invoke(MethodInfo? targetMethod, object?[]? args)
        {
            Argument = Assert.Single(args!);
            return targetMethod!.Name;
        }
    }
}
