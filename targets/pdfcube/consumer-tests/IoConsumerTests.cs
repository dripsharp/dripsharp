using DripSharp.PdfCarton.IO;
using Xunit;

namespace DripSharp.PdfCarton.Tests;

public sealed class IoConsumerTests
{
    [Fact]
    public void RandomAccessBufferPreservesUnsignedReadsSeekingAndViews()
    {
        using var input =
            new RandomAccessReadBuffer(new sbyte[] { 1, -2, 3, 4 });
        Assert.Equal(1, input.Read());
        Assert.Equal(254, input.Read());
        input.Seek(1);
        Assert.Equal(254, input.Read());

        using RandomAccessRead view = input.CreateView(2, 2);
        Assert.Equal(3, view.Read());
        Assert.Equal(4, view.Read());
        Assert.Equal(-1, view.Read());
    }
}
