using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using PdfCube.PdfBox;
using PdfCube.PdfBox.Text;

internal static class Program
{
    private static readonly List<string> Observations = new();

    private static int Main(string[] args)
    {
        try
        {
            if (args.Length != 2)
            {
                throw new ArgumentException(
                    "Expected output trace and PDFBox checkout.");
            }
            var output = args[0];
            var resources = Path.Combine(
                args[1], "pdfbox", "src", "test", "resources");

            ObserveFixture(
                Path.Combine(
                    resources,
                    "org",
                    "apache",
                    "pdfbox",
                    "text",
                    "BidiSample.pdf"),
                "bidi-sample");
            ObserveFixture(
                Path.Combine(
                    resources,
                    "input",
                    "PDFBOX-4531-bidi-ligature-1.pdf"),
                "bidi-ligature-1");
            ObserveFixture(
                Path.Combine(
                    resources,
                    "input",
                    "PDFBOX-4531-bidi-ligature-2.pdf"),
                "bidi-ligature-2");

            File.WriteAllLines(output, Observations, new UTF8Encoding(false));
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    private static void ObserveFixture(string pdf, string id)
    {
        foreach (var sorted in new[] { false, true })
        {
            var suffix = sorted ? "-sorted.txt" : ".txt";
            var expected = Normalize(File.ReadAllText(
                pdf + suffix, Encoding.UTF8));
            string actual;
            using (var document = Loader.LoadPDF(new FileInfo(pdf)))
            {
                var stripper = new PDFTextStripper();
                stripper.SetSortByPosition(sorted);
                stripper.SetLineSeparator("\n");
                actual = Normalize(stripper.GetText(document));
            }
            Observe(
                "extraction",
                id + (sorted ? "-sorted" : "-logical"),
                actual == expected,
                actual);
        }
    }

    private static string Normalize(string value)
    {
        return string.Join(
            "\n",
            value.Replace("\uFEFF", "", StringComparison.Ordinal)
                .Replace("\r\n", "\n", StringComparison.Ordinal)
                .Replace('\r', '\n')
                .Split('\n')
                .Select(line => line.Trim(' ', '\t', '\r', '\n'))
                .Where(line => line.Length > 0));
    }

    private static void Observe(
        string family, string id, bool expected, string value)
    {
        Observations.Add(
            $"{family}\t{id}\t{(expected ? "true" : "false")}|{Escape(value)}");
    }

    private static string Escape(string value)
    {
        return value.Replace("\\", "\\\\", StringComparison.Ordinal)
            .Replace("\t", "\\t", StringComparison.Ordinal)
            .Replace("\r", "\\r", StringComparison.Ordinal)
            .Replace("\n", "\\n", StringComparison.Ordinal);
    }
}
