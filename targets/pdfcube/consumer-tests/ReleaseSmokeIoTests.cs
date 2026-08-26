using DripSharp.PdfCarton.IO;
using Xunit;

namespace DripSharp.PdfCarton.ReleaseSmoke;

public sealed class ReleaseSmokeIoTests
{
    [Fact]
    public void BufferedFileSupportsReadsSeekingViewsAndCloseState()
    {
        string root = Path.Combine(
            Path.GetTempPath(), $"pdfcarton-release-io-{Guid.NewGuid():N}");
        Directory.CreateDirectory(root);
        try
        {
            string file = Path.Combine(root, "bytes.bin");
            File.WriteAllBytes(file, new byte[] { 0, 1, 127, 128, 255 });

            var input = new RandomAccessReadBufferedFile(new FileInfo(file));
            Assert.Equal(5, input.Length());
            Assert.Equal(0, input.Read());
            input.Seek(3);
            Assert.Equal(128, input.Read());

            using (RandomAccessRead view = input.CreateView(1, 3))
            {
                Assert.Equal(1, view.Read());
                Assert.Equal(127, view.Read());
                Assert.Equal(128, view.Read());
                Assert.Equal(-1, view.Read());
            }

            Assert.False(input.IsClosed());
            input.Dispose();
            Assert.True(input.IsClosed());
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }
}
