using BenchmarkDotNet.Attributes;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Rendering;
using System.Security.Cryptography;

namespace PdfCarton.Benchmarks;

/// <summary>
/// Steady-state rendering of loaded PDFBox regression documents at 72 DPI.
/// Document loading and the initial population of font/image caches are outside
/// the measurement; each operation allocates and disposes its output bitmap.
/// </summary>
[MemoryDiagnoser]
public class RenderingBenchmarks
{
    private PDDocument document = null!;
    private PDFRenderer renderer = null!;

    [Params("Text", "Vector", "Image", "Transparency")]
    public string Workload { get; set; } = "Text";

    [GlobalSetup]
    public void Setup()
    {
        var relativePath = Workload switch
        {
            "Text" => "input/rendering/survey.pdf",
            "Vector" => "input/rendering/tiger-as-form-xobject.pdf",
            "Image" => "input/merge/jpegrgb.pdf",
            "Transparency" => "input/PDFBOX-3195.pdf",
            _ => throw new ArgumentOutOfRangeException(nameof(Workload), Workload, "Unknown PDF workload.")
        };
        var fixture = Path.Combine(FindResourceRoot(), relativePath);
        document = Loader.LoadPDF(new FileInfo(fixture));
        renderer = new PDFRenderer(document);
        // Explicitly warm the document caches even when using a short BDN job.
        // BenchmarkDotNet separately warms and measures the benchmark method.
        using var image = renderer.RenderImage(0, 1f, ImageType.Argb);
        if (image.Width == 0 || image.Height == 0)
            throw new InvalidOperationException($"Rendering produced an empty bitmap: {fixture}");
        Console.WriteLine($"PDF fixture: {fixture}; page=1; DPI=72; pixels={image.Width}x{image.Height}; ARGB");
        // Setup-only output comparison across revisions; never timed. Skip row
        // padding, whose bytes are not part of the rendered image.
        using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var bytes = image.GetPixelSpan();
        for (var row = 0; row < image.Height; row++)
            digest.AppendData(bytes.Slice(row * image.RowBytes, image.Width * image.BytesPerPixel));
        Console.WriteLine($"Rendered pixels: {Workload}; {image.ColorType}; {image.AlphaType}; SHA256={Convert.ToHexString(digest.GetHashAndReset())}");
    }

    [Benchmark]
    public int RenderPage()
    {
        using var image = renderer.RenderImage(0, 1f, ImageType.Argb);
        return image.Width * image.Height;
    }

    [GlobalCleanup]
    public void Cleanup() => document?.Dispose();

    private static string FindResourceRoot()
    {
        var configuredRoot = Environment.GetEnvironmentVariable("PDFBOX_RESOURCE_ROOT");
        if (!string.IsNullOrWhiteSpace(configuredRoot))
        {
            var fullPath = Path.GetFullPath(configuredRoot);
            if (!Directory.Exists(fullPath))
                throw new DirectoryNotFoundException($"PDFBOX_RESOURCE_ROOT does not exist: {fullPath}");
            return fullPath;
        }

        // BDN child executables live several levels below the project; also
        // support launching the parent from elsewhere with an absolute path.
        foreach (var start in new[] { AppContext.BaseDirectory, Environment.CurrentDirectory })
        {
            for (var directory = new DirectoryInfo(start); directory != null; directory = directory.Parent)
            {
                var candidate = Path.Combine(directory.FullName, "research", "pdfbox", "pdfbox", "src", "test", "resources");
                if (Directory.Exists(candidate))
                    return candidate;
            }
        }

        throw new DirectoryNotFoundException(
            "Cannot find PDFBox fixtures. Set PDFBOX_RESOURCE_ROOT to pdfbox/src/test/resources in the upstream checkout.");
    }
}
