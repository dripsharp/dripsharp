using DripSharp.PdfCarton.Fonts.Util;
using Xunit;

namespace DripSharp.PdfCarton.Tests;

public sealed class FontsConsumerTests
{
    [Fact]
    public void BoundingBoxPreservesTranslatedGeometryBehavior()
    {
        var bounds = new BoundingBox(1, 2, 6, 10);

        Assert.Equal(5, bounds.GetWidth());
        Assert.Equal(8, bounds.GetHeight());
        Assert.True(bounds.Contains(3, 4));
        Assert.False(bounds.Contains(7, 4));
    }
}
