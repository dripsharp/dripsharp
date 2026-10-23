// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using BenchmarkDotNet.Attributes;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Cos;
using DripSharp.PdfCarton.Pdmodel;

namespace PdfCarton.Benchmarks;

/// <summary>
/// COSDictionary is a JavaLinkedHashMap keyed by COSName, whose hashCode is
/// Arrays.hashCode(nameBytes). NameHash isolates that hash. Lookup is one
/// GetDictionaryObject hit in a 12-entry dictionary. Build creates a dictionary
/// and inserts 12 entries. IterateEntries walks EntrySet() of a 12-entry
/// dictionary, as COSWriter and SecurityHandler do. LoadDocument parses an
/// in-memory PDF of Pages pages, each with its own font resources, and reads
/// every page's /MediaBox and /Resources entries; the PDF is saved with
/// PDFBox's default compression, so its page and font dictionaries live in
/// object streams (setup checks this). GetEveryPage calls getPage(i) for every
/// page of that document, loaded once in setup: its page tree is one flat
/// /Kids array, as after a merge, so each lookup walks the kids up to the page
/// as PDFBox does. Building, saving and loading the input is setup and is not
/// timed.
/// </summary>
[MemoryDiagnoser]
public class COSDictionaryBenchmarks
{
    private const int Batch = 1000;
    private readonly COSName[] names = new COSName[12];
    private COSDictionary dictionary = null!;
    private sbyte[] pdf = null!;
    private PDDocument loaded = null!;

    [Params(500)]
    public int Pages { get; set; }

    [GlobalSetup]
    public void Setup()
    {
        dictionary = new COSDictionary();
        for (var index = 0; index < names.Length; index++)
        {
            names[index] = COSName.GetPDFName("Key" + index);
            dictionary.SetInt(names[index], index);
        }

        using (var document = new PDDocument())
        {
            for (var index = 0; index < Pages; index++)
            {
                var page = new PDPage();
                var font = new COSDictionary();
                font.SetItem(COSName.Type, COSName.Font);
                font.SetItem(COSName.Subtype, COSName.Type1);
                font.SetName(COSName.BaseFont, "Helvetica");
                var fonts = new COSDictionary();
                fonts.SetItem(COSName.GetPDFName("F" + index), font);
                var resources = new COSDictionary();
                resources.SetItem(COSName.Font, fonts);
                page.GetCOSObject().SetItem(COSName.Resources, resources);
                document.AddPage(page);
            }
            using var output = new MemoryStream();
            document.Save(output);
            pdf = (sbyte[])(Array)output.ToArray();
        }
        if (LoadDocument() != Pages)
            throw new InvalidOperationException("Document lost pages.");
        loaded = Loader.LoadPDF(pdf);
        var compressed = 0;
        foreach (var offset in loaded.GetDocument().GetXrefTable().Values)
            if (offset < 0) compressed++;
        if (compressed < Pages)
            throw new InvalidOperationException("Pages are not stored in object streams.");
        if (GetEveryPage() != Pages)
            throw new InvalidOperationException("getPage lost pages.");
        Console.WriteLine($"COSDictionary: pages={Pages} pdf-bytes={pdf.Length}");
    }

    [Benchmark(OperationsPerInvoke = Batch)]
    public int NameHash()
    {
        var hash = 0;
        for (var index = 0; index < Batch; index++) hash ^= names[index % names.Length].GetHashCode();
        return hash;
    }

    [Benchmark(OperationsPerInvoke = Batch)]
    public int Lookup()
    {
        var found = 0;
        for (var index = 0; index < Batch; index++)
            if (dictionary.GetDictionaryObject(names[index % names.Length]) is not null) found++;
        return found;
    }

    [Benchmark]
    public COSDictionary Build()
    {
        var built = new COSDictionary();
        for (var index = 0; index < names.Length; index++) built.SetItem(names[index], COSName.Type);
        return built;
    }

    [Benchmark]
    public int IterateEntries()
    {
        var count = 0;
        foreach (var entry in dictionary.EntrySet())
            if (entry.Value is COSInteger) count++;
        return count;
    }

    [GlobalCleanup]
    public void Cleanup() => loaded.Dispose();

    [Benchmark]
    public int LoadDocument()
    {
        using var document = Loader.LoadPDF(pdf);
        var count = 0;
        foreach (var page in document.GetPages())
        {
            var dictionary = page.GetCOSObject();
            if (dictionary.GetDictionaryObject(COSName.Resources) is COSDictionary &&
                page.GetMediaBox() is not null)
                count++;
        }
        return count;
    }

    [Benchmark]
    public int GetEveryPage()
    {
        var count = 0;
        var pages = loaded.GetNumberOfPages();
        for (var index = 0; index < pages; index++)
            if (loaded.GetPage(index).GetCOSObject() is not null) count++;
        return count;
    }
}
