// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

using BenchmarkDotNet.Attributes;
using DripSharp.PdfCarton.Pdmodel.Font;
using DripSharp.PdfCarton.Pdmodel.Font.Encoding;

namespace PdfCarton.Benchmarks;

/// <summary>
/// Cost of the String.split/matches calls PDFBox makes while loading font
/// metadata. GlyphListLoad parses the bundled Adobe glyph list (about 4,300
/// entries, two String.split calls each) through the public GlyphList
/// constructor, the work GlyphList's static initialiser does on first font or
/// text use. FontCacheLoad constructs FileSystemFontProvider over the installed
/// system fonts from an on-disk cache in a private temporary directory
/// (pdfbox.fontcache); it includes the font directory scan and file checks,
/// and one or two String.split calls per cached font. Building that cache is
/// setup and is not timed. SplitSemicolon and MatchesGlyphName isolate one
/// String.split(";") and one String.matches("g\\d+") (TrueTypeFont's
/// unresolved glyph-name check).
/// </summary>
[MemoryDiagnoser]
public class StringRegexBenchmarks
{
    private byte[] glyphList = Array.Empty<byte>();
    private string cacheDirectory = string.Empty;
    private int cachedFonts;

    [GlobalSetup]
    public void Setup()
    {
        using (var resource = typeof(GlyphList).Assembly.GetManifestResourceStream(
                   "org.apache.pdfbox.resources.glyphlist.glyphlist.txt")
               ?? throw new InvalidOperationException("glyphlist.txt resource is missing."))
        using (var buffer = new MemoryStream())
        {
            resource.CopyTo(buffer);
            glyphList = buffer.ToArray();
        }
        if (GlyphListLoad().ToUnicode("Euro") != "€")
            throw new InvalidOperationException("Glyph list lost Euro.");

        cacheDirectory = Path.Combine(Path.GetTempPath(), "pdfcarton-fontcache-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(cacheDirectory);
        DripSharp.Runtime.JavaCompat.SetProperty("pdfbox.fontcache", cacheDirectory);
        FontCacheLoad();
        var cacheFile = Path.Combine(cacheDirectory, ".pdfbox.cache");
        cachedFonts = File.Exists(cacheFile) ? File.ReadAllLines(cacheFile).Length : 0;
        if (cachedFonts == 0) throw new InvalidOperationException("No installed fonts were cached.");
        if (FontCacheLoad() != cachedFonts)
            throw new InvalidOperationException("Font cache load lost fonts.");
        Console.WriteLine($"Font cache: fonts={cachedFonts} glyph-list-bytes={glyphList.Length}");
    }

    [Benchmark]
    public GlyphList GlyphListLoad() => new(new MemoryStream(glyphList, false), 4300);

    [Benchmark]
    public int FontCacheLoad() => new FileSystemFontProvider(new FontCache()).GetFontInfo().Count;

    [Benchmark(OperationsPerInvoke = 1000)]
    public int SplitSemicolon()
    {
        var total = 0;
        for (var index = 0; index < 1000; index++)
            total += DripSharp.Runtime.JavaCompat.StringSplit("Euro;20AC", ";", 0).Length;
        return total;
    }

    [Benchmark(OperationsPerInvoke = 1000)]
    public int MatchesGlyphName()
    {
        var total = 0;
        for (var index = 0; index < 1000; index++)
            total += DripSharp.Runtime.JavaCompat.StringMatches("g1234", "g\\d+") ? 1 : 0;
        return total;
    }

    [GlobalCleanup]
    public void Cleanup()
    {
        if (cacheDirectory.Length != 0 && Directory.Exists(cacheDirectory))
            Directory.Delete(cacheDirectory, true);
    }
}
