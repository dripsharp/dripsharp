using DripSharp.Brine.Parser;
using DripSharp.Brine.Parser.Syntax;
using Xunit;
using GenericNode = DripSharp.Brine.Parser.Syntax.Generic.Node;
using PklParser = DripSharp.Brine.Parser.Parser;

namespace DripSharp.Brine.Tests;

public sealed class ParserConsumerTests
{
    [Fact]
    public void LexerAndParsersConsumeAProductRepositoryFixture()
    {
        string source = File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "Fixtures", "sample.pkl"));
        char[] sourceChars = source.ToCharArray();

        var lexer = new Lexer(source);
        Assert.Same(Token.IDENTIFIER, lexer.Next());
        Assert.Equal("greeting", lexer.Text());
        Assert.Equal(new Span(0, 8), lexer.Span());
        Assert.Same(Token.ASSIGN, lexer.Next());
        Assert.Same(Token.STRING_START, lexer.Next());

        var parser = new PklParser();
        DripSharp.Brine.Parser.Syntax.Module module = parser.ParseModule(source);
        Assert.Equal(2, module.GetProperties().Count);
        Assert.Equal("greeting", module.GetProperties()[0].GetName().GetValue());
        Assert.Equal("greeting", module.GetProperties()[0].GetName().Text(sourceChars));
        Assert.True(module.Children().Any());

        GenericNode generic = new GenericParser().ParseModule(source);
        Assert.NotEmpty(generic.children);
        Assert.Equal(source.TrimEnd('\n'), generic.Text(sourceChars));

        ParserError error = Assert.Throws<ParserError>(
            (Action)(() => parser.ParseModule("name =")));
        Assert.Contains("Unexpected end of file", error.Message);
        Assert.True(error.Span().CharIndex >= 0);
    }
}
