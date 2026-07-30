#nullable enable

using System;
using System.IO;
using System.Linq;
using DripSharp.PdfCarton.Fonts.Util;
using DripSharp.PdfCarton.Fonts.Util.Autodetect;
using DripSharp.PdfCarton.IO;

internal static class Program
{
    private static void Main()
    {
        var bounds = new BoundingBox(1, 2, 6, 10);
        if (bounds.GetWidth() != 5 ||
            bounds.GetHeight() != 8 ||
            !bounds.Contains(3, 4) ||
            bounds.Contains(7, 4))
        {
            throw new InvalidOperationException(
                "Translated FontBox bounding-box behavior did not match Java.");
        }

        using var input = new RandomAccessReadBuffer(new sbyte[] { 1, -2 });
        if (input.Read() != 1 || input.Read() != 254 || input.Read() != -1)
        {
            throw new InvalidOperationException(
                "The transitive DripSharp.PdfCarton.IO package boundary is not usable.");
        }

        var fontRoot = Path.Combine(
            Path.GetTempPath(),
            "pdfcube-fontbox-consumer-" + Guid.NewGuid().ToString("N"));
        try
        {
            Directory.CreateDirectory(fontRoot);
            Directory.CreateDirectory(Path.Combine(fontRoot, ".hidden"));
            File.WriteAllBytes(Path.Combine(fontRoot, "Detected.TTF"), [0, 1, 2, 3]);
            File.WriteAllBytes(Path.Combine(fontRoot, "fonts.dir"), [0]);
            File.WriteAllBytes(
                Path.Combine(fontRoot, ".hidden", "Ignored.otf"),
                [0, 1, 2, 3]);

            var discovered = new FontFileFinder().Find(fontRoot);
            if (discovered.Count != 1 ||
                !string.Equals(
                    Path.GetFileName(discovered.Single().LocalPath),
                    "Detected.TTF",
                    StringComparison.Ordinal))
            {
                throw new InvalidOperationException(
                    "Package-only FontBox discovery did not preserve the upstream filter.");
            }
        }
        finally
        {
            if (Directory.Exists(fontRoot))
                Directory.Delete(fontRoot, recursive: true);
        }

        Console.WriteLine("DripSharp.PdfCarton.Fonts focused behavior passed.");
    }
}
