// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using BenchmarkDotNet.Attributes;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Font;
using DripSharp.PdfCarton.Text;
using Compat = DripSharp.Runtime;

namespace PdfCarton.Benchmarks;

/// <summary>
/// java.util.WeakHashMap is translated to JavaWeakHashMap. Get is one hit in a
/// map of Keys live keys.
/// </summary>
[MemoryDiagnoser]
public class WeakHashMapBenchmarks
{
    private const int Batch = 1000;
    private object[] keys = null!;
    private Compat.JavaWeakHashMap<object, object> map = null!;

    [Params(10, 10000)]
    public int Keys { get; set; }

    [GlobalSetup]
    public void Setup()
    {
        keys = new object[Keys];
        map = new Compat.JavaWeakHashMap<object, object>();
        for (var index = 0; index < keys.Length; index++)
        {
            keys[index] = new object();
            map[keys[index]] = index;
        }
    }

    [Benchmark(OperationsPerInvoke = Batch)]
    public int Get()
    {
        var found = 0;
        for (var index = 0; index < Batch; index++)
            if (map.TryGetValue(keys[index % keys.Length], out _)) found++;
        return found;
    }
}

/// <summary>
/// ExtractText runs PDFTextStripper over an in-memory document of Pages pages
/// whose 4,000 text runs of RunLength glyphs cycle through Fonts distinct font
/// dictionaries. LegacyPDFStreamEngine looks each glyph's font dictionary up in
/// its fontHeightMap WeakHashMap. Each run selects its font, so the RunLength 40
/// minus RunLength 10 time is the cost of 120,000 extra glyphs at a fixed font
/// count and number of font selections. Building the document is setup and is
/// not timed.
/// </summary>
[MemoryDiagnoser]
public class TextExtractionFontCountBenchmarks
{
    private const int Pages = 100;
    private const int RunsPerPage = 40;
    private PDDocument document = null!;
    private int expectedLetters;

    [Params(10, 2000)]
    public int Fonts { get; set; }

    [Params(10, 40)]
    public int RunLength { get; set; }

    [GlobalSetup]
    public void Setup()
    {
        var fonts = new PDFont[Fonts];
        for (var index = 0; index < fonts.Length; index++)
            fonts[index] = new PDType1Font(Standard14Fonts.FontName.Helvetica);
        document = new PDDocument();
        var run = string.Concat(Enumerable.Repeat("abcdefghij", RunLength / 10));
        var next = 0;
        for (var pageIndex = 0; pageIndex < Pages; pageIndex++)
        {
            var page = new PDPage();
            document.AddPage(page);
            using var contents = new PDPageContentStream(document, page);
            for (var line = 0; line < RunsPerPage; line++)
            {
                contents.BeginText();
                contents.SetFont(fonts[next++ % fonts.Length], 10);
                contents.NewLineAtOffset(20, 760 - line * 18);
                contents.ShowText(run);
                contents.EndText();
            }
        }
        expectedLetters = Pages * RunsPerPage * run.Length;
        if (ExtractText() != expectedLetters)
            throw new InvalidOperationException("Text extraction lost glyphs.");
        Console.WriteLine($"TextExtraction: fonts={Fonts} run-length={RunLength} glyphs={expectedLetters}");
    }

    [GlobalCleanup]
    public void Cleanup() => document.Dispose();

    [Benchmark]
    public int ExtractText()
    {
        var text = new PDFTextStripper().GetText(document);
        var letters = 0;
        foreach (var character in text)
            if (character is >= 'a' and <= 'j') letters++;
        return letters;
    }
}
