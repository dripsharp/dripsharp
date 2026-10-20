// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using BenchmarkDotNet.Attributes;
using DripSharp.PdfCarton.Cos;
using DripSharp.PdfCarton.IO;
using DripSharp.PdfCarton.Pdmodel;

namespace PdfCarton.Benchmarks;

/// <summary>
/// ScratchFile page allocation. RoundTrip builds a document of Streams
/// two-page streams under a SetupMixed(100 MiB) stream cache (25,600 in-memory
/// pages, so every page comes from ScratchFile.getNewPage's nextSetBit(0)),
/// saves it, loads the result under the same setting and saves it again.
/// FreePageScan is the free-page map alone: set(0, 100000) followed by 25,600
/// nextSetBit(0)/clear cycles, as an unrestricted ScratchFile does.
/// </summary>
[MemoryDiagnoser]
public class ScratchFileBenchmarks
{
    private const long MainMemoryBytes = 100L * 1024 * 1024;
    private byte[] payload = null!;

    [Params(1000, 4000, 12000)]
    public int Streams { get; set; }

    [GlobalSetup]
    public void Setup()
    {
        payload = new byte[2 * 4096 - 1];
        for (var index = 0; index < payload.Length; index++)
            payload[index] = unchecked((byte)(index * 31));
        var length = RoundTrip();
        Console.WriteLine($"ScratchFile round trip: streams={Streams} bytes={length}");
    }

    [Benchmark]
    public long RoundTrip()
    {
        byte[] saved;
        using (var document = new PDDocument(MemoryUsageSetting.SetupMixed(MainMemoryBytes).StreamCache))
        {
            document.SetDocumentId(20261019L);
            document.AddPage(new PDPage());
            var payloads = new COSArray();
            for (var index = 0; index < Streams; index++)
            {
                var stream = document.GetDocument().CreateCOSStream();
                using (var output = stream.CreateOutputStream())
                    output.Write(payload);
                payloads.Add(stream);
            }
            document.GetDocumentCatalog().GetCOSObject()
                .SetItem(COSName.GetPDFName("Payloads"), payloads);
            using var savedOutput = new MemoryStream();
            document.Save(savedOutput);
            saved = savedOutput.ToArray();
        }
        using var reloaded = DripSharp.PdfCarton.Loader.LoadPDF(
            (sbyte[])(Array)saved, "", null!, null!,
            MemoryUsageSetting.SetupMixed(MainMemoryBytes).StreamCache);
        using var resaved = new MemoryStream();
        reloaded.Save(resaved);
        return resaved.Length;
    }

    [Benchmark]
    public long FreePageScan()
    {
        var bits = new DripSharp.Runtime.JavaBitSet();
        bits.set(0, 100_000);
        long taken = 0;
        for (var index = 0; index < 25_600; index++)
        {
            var page = bits.nextSetBit(0);
            bits.clear(page);
            taken += page;
        }
        return taken;
    }
}
