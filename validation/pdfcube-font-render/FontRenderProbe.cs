// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Common;
using DripSharp.PdfCarton.Pdmodel.Font;
using DripSharp.PdfCarton.Pdfwriter.Compress;
using DripSharp.PdfCarton.Rendering;
using DripSharp.PdfCarton.Text;
using DripSharp.PdfCarton.Util;
using SkiaSharp;

internal static class FontRenderProbe
{
    private static readonly List<string> Trace = new();
    private static void Row(string key, object value) => Trace.Add(key + "\t" + value);
    private static string Bits(float value) => BitConverter.SingleToInt32Bits(value).ToString("X8", CultureInfo.InvariantCulture);
    private static string Units(string value) => string.Join(",", value.Select(c => ((int)c).ToString("X4", CultureInfo.InvariantCulture)));
    private static string Points(string value) => string.Join(",", value.EnumerateRunes().Select(r => r.Value));
    private static float F(string value) => float.Parse(value, CultureInfo.InvariantCulture);

    private static int Main(string[] args)
    {
        try
        {
            CultureInfo.CurrentCulture = CultureInfo.InvariantCulture;
            if (args.Length != 5) throw new ArgumentException("mode input assets exchange output");
            var input = args[1]; var assets = args[2]; var exchange = args[3]; var output = args[4];
            Directory.CreateDirectory(exchange); Directory.CreateDirectory(output);
            switch (args[0])
            {
                case "write": Write(input, assets, Path.Combine(exchange, "dotnet.pdf")); break;
                case "observe":
                    Observe(Path.Combine(exchange, "java.pdf"), output, "java", 1f, false);
                    Observe(Path.Combine(exchange, "dotnet.pdf"), output, "dotnet", 1f, false);
                    break;
                case "fixtures":
                    foreach (var line in File.ReadAllLines(input, Encoding.UTF8))
                    {
                        var fields = line.Split('\t');
                        Observe(Path.Combine(assets, fields[1]), output, fields[0], 0.25f, true);
                    }
                    break;
                default: throw new ArgumentException("Unknown mode");
            }
            File.WriteAllLines(Path.Combine(output, "trace.tsv"), Trace, new UTF8Encoding(false));
            return 0;
        }
        catch (Exception error) { Console.Error.WriteLine(error); return 1; }
    }

    private static void Write(string input, string assets, string pdf)
    {
        var lines = File.ReadAllLines(input, Encoding.UTF8);
        if (lines[0] != "font-render\t1") throw new ArgumentException("Input version");
        using var doc = new PDDocument();
        foreach (var line in lines.Skip(1))
        {
            var p = line.Split('\t');
            if (p.Length != 17 || p[0] != "page") throw new ArgumentException("Page input");
            var page = new PDPage(new PDRectangle(240, 160));
            page.SetRotation(int.Parse(p[6], CultureInfo.InvariantCulture)); doc.AddPage(page);
            PDType0Font font;
            using (var stream = File.OpenRead(Path.Combine(assets, p[1])))
                font = PDType0Font.Load(doc, stream, false);
            using var content = new PDPageContentStream(doc, page,
                PDPageContentStream.AppendMode.Overwrite, false, false);
            content.SaveGraphicsState();
            content.AddRect(F(p[13]), F(p[14]), F(p[15]), F(p[16])); content.Clip();
            content.Transform(new Matrix(F(p[7]), F(p[8]), F(p[9]), F(p[10]), F(p[11]), F(p[12])));
            content.BeginText(); content.SetFont(font, F(p[3]));
            content.SetTextMatrix(Matrix.GetTranslateInstance(F(p[4]), F(p[5])));
            content.ShowText(Encoding.UTF8.GetString(Convert.FromBase64String(p[2])));
            content.EndText(); content.RestoreGraphicsState();
        }
        doc.Save(new FileInfo(pdf), CompressParameters.NoCompression);
    }

    private sealed class Collector : PDFTextStripper
    {
        internal readonly List<string> Positions = new();
        internal readonly List<string> Glyphs = new();
        protected override void ShowGlyph(Matrix m, PDFont font, int code, DripSharp.PdfCarton.Util.Vector displacement)
        {
            var unicode = font.ToUnicode(code);
            Glyphs.Add(code + ":" + (unicode == null ? "null" : Units(unicode)) + "@"
                + Bits(m.GetScaleX()) + "," + Bits(m.GetShearY()) + ","
                + Bits(m.GetShearX()) + "," + Bits(m.GetScaleY()) + ","
                + Bits(m.GetTranslateX()) + "," + Bits(m.GetTranslateY()) + ","
                + Bits(displacement.GetX()) + "," + Bits(displacement.GetY()));
            base.ShowGlyph(m, font, code, displacement);
        }
        protected override void ProcessTextPosition(TextPosition p)
        {
            Positions.Add(Units(p.GetUnicode()) + "[" + string.Join(",", p.GetCharacterCodes()) + "]@"
                + Bits(p.GetXDirAdj()) + "," + Bits(p.GetYDirAdj()) + ","
                + Bits(p.GetWidthDirAdj()) + "," + Bits(p.GetHeightDir()) + ","
                + Bits(p.GetDir()) + "," + Bits(p.GetFontSizeInPt()));
            base.ProcessTextPosition(p);
        }
    }

    private static void Observe(string path, string output, string id, float scale, bool fixture)
    {
        using var doc = Loader.LoadPDF(new FileInfo(path));
        Row(id + "/pages", doc.GetNumberOfPages());
        int count = fixture ? 1 : doc.GetNumberOfPages();
        for (int index = 0; index < count; index++)
        {
            var key = id + "/p" + index; var page = doc.GetPage(index); var box = page.GetCropBox();
            Row(key + "/structure", page.GetRotation() + "," + Bits(box.GetLowerLeftX())
                + "," + Bits(box.GetLowerLeftY()) + "," + Bits(box.GetWidth())
                + "," + Bits(box.GetHeight()) + "," + page.GetAnnotations().Count);
            foreach (bool sorted in new[] { false, true })
            {
                var collector = new Collector();
                collector.SetStartPage(index + 1); collector.SetEndPage(index + 1);
                collector.SetSortByPosition(sorted); collector.SetLineSeparator("\n");
                var text = collector.GetText(doc); var suffix = sorted ? "true" : "false";
                Row(key + "/text-" + suffix, Units(text));
                Row(key + "/codepoints-" + suffix, Points(text));
                Row(key + "/positions-" + suffix, string.Join(";", collector.Positions));
                Row(key + "/glyphs-" + suffix, string.Join(";", collector.Glyphs));
            }
            var fontRows = new List<string>();
            foreach (var name in page.GetResources().GetFontNames())
            {
                var font = page.GetResources().GetFont(name);
                fontRows.Add(name.GetName() + ":" + font.GetSubType() + ":"
                    + font.IsEmbedded().ToString().ToLowerInvariant() + ":" + font.IsDamaged().ToString().ToLowerInvariant());
                if (!fixture && !font.IsEmbedded()) throw new InvalidOperationException("Unembedded font");
            }
            fontRows.Sort(StringComparer.Ordinal); Row(key + "/fonts", string.Join(";", fontRows));
            if (!fixture)
            {
                using var stream = page.GetContents(); using var bytes = new MemoryStream();
                stream.CopyTo(bytes); Row(key + "/content", Convert.ToBase64String(bytes.ToArray()));
            }
            int width = (int)Math.Floor(box.GetWidth() * scale), height = (int)Math.Floor(box.GetHeight() * scale);
            if (width < 1 || height < 1 || width > 512 || height > 512) throw new ArgumentException("Raster bounds");
            var image = new PDFRenderer(doc).RenderImage(index, scale, ImageType.Argb);
            using var bitmap = image.ToSKBitmap();
            var imageId = id + "-p" + index;
            Row(key + "/image", image.Width + "," + image.Height);
            var rgba = new byte[checked(image.Width * image.Height * 4)]; int offset = 0;
            for (int y = 0; y < image.Height; y++) for (int x = 0; x < image.Width; x++)
            {
                var pixel = bitmap.GetPixel(x, y);
                rgba[offset++] = pixel.Red; rgba[offset++] = pixel.Green;
                rgba[offset++] = pixel.Blue; rgba[offset++] = pixel.Alpha;
            }
            File.WriteAllBytes(Path.Combine(output, imageId + ".rgba"), rgba);
            File.WriteAllBytes(Path.Combine(output, imageId + ".png"), image.EncodePng());
        }
    }
}
