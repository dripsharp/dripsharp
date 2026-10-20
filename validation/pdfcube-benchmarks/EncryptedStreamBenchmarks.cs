// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using BenchmarkDotNet.Attributes;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Cos;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Encryption;

namespace PdfCarton.Benchmarks;

/// <summary>
/// Large-stream byte plumbing. Each document has one page and one stream of
/// PayloadBytes synthetic bytes (FlateBenchmarks' payload) referenced from the
/// catalog. SaveFlate builds and saves it unencrypted as /FlateDecode
/// (FlateFilter.encode through DeflaterOutputStream, then COSWriter).
/// SaveAes128 builds it unfiltered, protects it with AES-128 and saves it
/// (SecurityHandler.encryptStream: IOUtils.toByteArray, ByteArrayInputStream
/// and Cipher.update over 256-byte reads). DecryptAes128 loads that encrypted
/// PDF and reads the stream with 4 KiB reads (SecurityHandler.decryptStream:
/// Cipher.update over 256-byte reads). The AES streams are unfiltered so that
/// zlib does not dominate. Building the inputs is setup and is not timed.
/// </summary>
[MemoryDiagnoser]
public class EncryptedStreamBenchmarks
{
    private static readonly COSName PayloadKey = COSName.GetPDFName("Payload");
    private byte[] payload = [];
    private sbyte[] encrypted = [];
    private readonly sbyte[] readBuffer = new sbyte[4096];

    [Params(1048576, 8388608)]
    public int PayloadBytes { get; set; }

    [GlobalSetup]
    public void Setup()
    {
        payload = FlateBenchmarks.SyntheticPayload(PayloadBytes);
        encrypted = (sbyte[])(Array)Build(aes: true);
        if (Read(verify: true) != PayloadBytes)
            throw new InvalidOperationException("AES-128 round trip lost bytes.");
        Console.WriteLine($"Encrypted stream input: payload={PayloadBytes} pdf={encrypted.Length}");
    }

    [Benchmark]
    public long SaveFlate() => Build(aes: false).Length;

    [Benchmark]
    public long SaveAes128() => Build(aes: true).Length;

    [Benchmark]
    public long DecryptAes128() => Read(verify: false);

    private long Read(bool verify)
    {
        using var document = Loader.LoadPDF(encrypted, "");
        if (verify && (document.GetEncryption().GetLength() != 128 ||
                       !COSName.Aesv2.Equals(
                           document.GetEncryption().GetStdCryptFilterDictionary()?.GetCryptFilterMethod())))
            throw new InvalidOperationException("The document is not AES-128 encrypted.");
        var stream = document.GetDocumentCatalog().GetCOSObject().GetCOSStream(PayloadKey);
        using var input = stream.CreateInputStream();
        long total = 0;
        int count;
        while ((count = input.Read(readBuffer, 0, readBuffer.Length)) > 0)
        {
            if (verify)
                for (var index = 0; index < count; index++)
                    if (unchecked((byte)readBuffer[index]) != payload[total + index])
                        throw new InvalidOperationException($"Decrypted byte {total + index} differs.");
            total += count;
        }
        return total;
    }

    private byte[] Build(bool aes)
    {
        using var document = new PDDocument();
        document.SetDocumentId(20261020L);
        document.AddPage(new PDPage());
        var stream = document.GetDocument().CreateCOSStream();
        using (var output = aes ? stream.CreateOutputStream() : stream.CreateOutputStream(COSName.FlateDecode))
            output.Write(payload);
        document.GetDocumentCatalog().GetCOSObject().SetItem(PayloadKey, stream);
        if (aes)
        {
            var policy = new StandardProtectionPolicy("owner", "", new AccessPermission());
            policy.SetEncryptionKeyLength(128);
            policy.SetPreferAES(true);
            document.Protect(policy);
        }
        using var saved = new MemoryStream();
        document.Save(saved);
        return saved.ToArray();
    }
}
