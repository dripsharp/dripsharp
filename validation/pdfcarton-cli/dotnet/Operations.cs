// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using System.Globalization;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Cos;
using DripSharp.PdfCarton.IO;
using DripSharp.PdfCarton.Multipdf;
using DripSharp.PdfCarton.Pdfwriter.Compress;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Common;
using DripSharp.PdfCarton.Pdmodel.Common.Filespecification;
using DripSharp.PdfCarton.Pdmodel.Encryption;
using DripSharp.PdfCarton.Pdmodel.Font;
using DripSharp.PdfCarton.Pdmodel.Graphics.Form;
using DripSharp.PdfCarton.Pdmodel.Graphics.Image;
using DripSharp.PdfCarton.Pdmodel.Interactive.Documentnavigation.Outline;
using DripSharp.PdfCarton.Pdmodel.Interactive.Form;
using DripSharp.PdfCarton.Rendering;
using DripSharp.PdfCarton.Text;
using DripSharp.PdfCarton.Xmp;
using DripSharp.PdfCarton.Xmp.Xml;
using DripSharp.Runtime;
using SkiaSharp;

namespace PdfCli;

/// <summary>
/// The commands of the contract. <see cref="Execute"/> is the timed
/// operation; <see cref="Summarize"/> turns its value into the result object
/// (reopening written files, hashing written PNGs) and is not timed.
/// </summary>
internal static class Operations
{
    public static object? Execute(Context ctx) => ctx.Command switch
    {
        "info" => Info(ctx),
        "text" => Text(ctx),
        "render" => Render(ctx),
        "images" => Images(ctx),
        "fonts" => Fonts(ctx),
        "outline" => Outline(ctx),
        "annotations" => Annotations(ctx),
        "form-fields" => FormFields(ctx),
        "form-fill" => FormFill(ctx),
        "attachments" => Attachments(ctx),
        "merge" => Merge(ctx),
        "split" => Split(ctx),
        "rotate" => Rotate(ctx),
        "encrypt" => Encrypt(ctx),
        "decrypt" => Decrypt(ctx),
        "create" => Create(ctx),
        "save" => Save(ctx),
        _ => throw new InvalidOperationException(ctx.Command),
    };

    public static object? Summarize(Context ctx, object? value) => ctx.Command switch
    {
        "render" => SummarizeRender((RenderValue)value!),
        "form-fill" => SummarizeFormFill(ctx),
        "merge" => Written(ctx.Get<string>("--out")!, null, _ => ["inputCount", ctx.Positional.Count]),
        "split" => SummarizeSplit(ctx, (List<(string Range, string File)>)value!),
        "rotate" => Written(ctx.Get<string>("--out")!, ctx.Password,
            doc => ["rotations", doc.GetPages().Select(p => p.GetRotation()).ToList()]),
        "encrypt" => Written(ctx.Get<string>("--out")!, ctx.Get<string>("--owner-password"),
            doc => ["encryption", EncryptionSummary(doc, false)]),
        "decrypt" => Written(ctx.Get<string>("--out")!, null, doc => ["encrypted", doc.IsEncrypted()]),
        "create" => Written(ctx.Get<string>("--out")!, null),
        "save" => Written(ctx.Get<string>("--out")!, ctx.Password, _ => ["compressed", ctx.Has("--compress")]),
        _ => value,
    };

    // ------------------------------------------------------------------
    // Helpers

    private static string Hex(ReadOnlySpan<byte> digest) => Convert.ToHexString(digest).ToLowerInvariant();

    private static string Sha256(sbyte[] data) => Hex(SHA256.HashData(MemoryMarshal.AsBytes(data.AsSpan())));

    /// <summary>The contract's pixel hash: rows top to bottom, R, G, B, A from getRGB.</summary>
    private static string PixelSha256(SKBitmap image)
    {
        var w = image.Width;
        var h = image.Height;
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var row = new int[w];
        var bytes = new byte[4 * w];
        for (var y = 0; y < h; y++)
        {
            PdfCartonFontCompat.GetRgb(image, 0, y, w, 1, row, 0, w);
            for (var x = 0; x < w; x++)
            {
                var argb = row[x];
                bytes[4 * x] = (byte)(argb >> 16);
                bytes[4 * x + 1] = (byte)(argb >> 8);
                bytes[4 * x + 2] = (byte)argb;
                bytes[4 * x + 3] = (byte)((uint)argb >> 24);
            }
            hash.AppendData(bytes);
        }
        return Hex(hash.GetHashAndReset());
    }

    /// <summary>ImageIO.write(image, "png", file): the image's stored samples as PNG.</summary>
    private static void WritePng(SKBitmap image, string path)
    {
        using var data = image.Encode(SKEncodedImageFormat.Png, 100)
            ?? throw new IOException($"Cannot encode a {image.ColorType} image as PNG");
        using var stream = File.Create(path);
        data.SaveTo(stream);
    }

    private static string Numbered(string pattern, params int[] numbers) =>
        string.Format(CultureInfo.InvariantCulture, pattern, numbers.Cast<object>().ToArray());

    private static PDDocument Load(string path, string? password)
    {
        try
        {
            return Loader.LoadPDF(new FileInfo(path), password ?? "");
        }
        catch (InvalidPasswordException e)
        {
            throw new CliException("invalid-password", e.Message, e);
        }
        catch (Exception e)
        {
            throw new CliException("load-failed", e.Message, e);
        }
    }

    private static PDDocument LoadInput(Context ctx) => Load(ctx.Input, ctx.Password);

    private static PDDocument Reopen(string path, string? password) => Loader.LoadPDF(new FileInfo(path), password ?? "");

    private static (int Start, int End) Selection(Context ctx, int pageCount)
    {
        var (a, b) = ctx.Has("--pages") ? ctx.Get<(int, int)>("--pages") : (1, int.MaxValue);
        return Resolve((a, b), pageCount);
    }

    private static (int Start, int End) Resolve((int Start, int End) range, int pageCount) =>
        (Math.Max(1, range.Start), Math.Min(range.End, pageCount));

    private static IEnumerable<int> Pages((int Start, int End) selection)
    {
        for (var p = selection.Start; p <= selection.End; p++) yield return p;
    }

    private static List<float>? Rect(PDRectangle? r) =>
        r is null ? null : [r.GetLowerLeftX(), r.GetLowerLeftY(), r.GetUpperRightX(), r.GetUpperRightY()];

    private static string? TextOrName(COSBase? v) => v switch
    {
        COSString s => s.GetString(),
        COSName n => n.GetName(),
        _ => null,
    };

    private static COSName Name(string key) => COSName.GetPDFName(key);

    private static List<string> SortedNames(COSDictionary? d) =>
        d is null ? [] : d.KeySet().Select(k => k.GetName()).OrderBy(k => k, StringComparer.Ordinal).ToList();

    private static T? Safe<T>(Func<T?> f)
    {
        try { return f(); }
        catch (Exception) { return default; }
    }

    private static JObj? EncryptionSummary(PDDocument doc, bool withOwnerPermission)
    {
        if (!doc.IsEncrypted()) return null;
        var e = doc.GetEncryption();
        var summary = JObj.Of("filter", e.GetFilter(), "version", e.GetVersion(), "revision", e.GetRevision(),
            "keyLength", e.GetLength(), "permissions", e.GetPermissions());
        if (withOwnerPermission)
            summary.Add("ownerPermission", doc.GetCurrentAccessPermission().IsOwnerPermission());
        return summary;
    }

    /// <summary>The written summary of a PDF, from reopening it.</summary>
    private static JObj Written(string path, string? password, Func<PDDocument, object?[]>? extra = null)
    {
        using var doc = Reopen(path, password);
        var result = JObj.Of("output", path, "bytes", new FileInfo(path).Length, "pageCount", doc.GetNumberOfPages());
        if (extra is not null)
        {
            var kv = extra(doc);
            for (var i = 0; i < kv.Length; i += 2) result.Add((string)kv[i]!, kv[i + 1]);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // info

    private static readonly string[] InfoKeys =
        ["Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate", "Trapped"];

    private static JObj? InfoSummary(PDDocument doc)
    {
        var d = doc.GetDocument().GetTrailer().GetCOSDictionary(Name("Info"));
        if (d is null) return null;
        var result = new JObj();
        foreach (var k in InfoKeys) result.Add(k, TextOrName(d.GetDictionaryObject(Name(k))));
        result.Add("custom", JObj.Sorted(SortedNames(d).Where(k => !InfoKeys.Contains(k))
            .Select(k => new KeyValuePair<string, object?>(k, TextOrName(d.GetDictionaryObject(Name(k)))))));
        return result;
    }

    private static JObj? XmpSummary(PDDocument doc)
    {
        var md = doc.GetDocumentCatalog().GetMetadata();
        if (md is null) return null;
        var data = Safe(() => md.ToByteArray());
        XMPMetadata? meta = null;
        if (data is not null)
        {
            meta = Safe(() =>
            {
                var parser = new DomXmpParser();
                parser.SetStrictParsing(false);
                return parser.Parse(data);
            });
        }
        return JObj.Of(
            "length", data?.Length,
            "sha256", data is null ? null : Sha256(data),
            "parsed", meta is not null,
            "schemas", meta?.GetAllSchemas().Select(s => s.GetNamespace()).Where(n => n is not null).Distinct()
                .OrderBy(n => n, StringComparer.Ordinal).ToList(),
            "dcTitle", meta is null ? null : Safe(() => meta.GetDublinCoreSchema()?.GetTitle()),
            "dcCreators", meta is null ? null : Safe(() => meta.GetDublinCoreSchema()?.GetCreators()?.ToList()),
            "pdfProducer", meta is null ? null : Safe(() => meta.GetAdobePDFSchema()?.GetProducer()),
            "xmpCreatorTool", meta is null ? null : Safe(() => meta.GetXMPBasicSchema()?.GetCreatorTool()),
            "pdfaPart", meta is null ? null : Safe(() => meta.GetPDFAIdentificationSchema()?.GetPart()),
            "pdfaConformance", meta is null ? null : Safe(() => meta.GetPDFAIdentificationSchema()?.GetConformance()));
    }

    private static JObj Info(Context ctx)
    {
        using var doc = LoadInput(ctx);
        var pages = new List<JObj>();
        var i = 0;
        foreach (var p in doc.GetPages())
        {
            i++;
            pages.Add(JObj.Of("page", i, "mediaBox", Rect(p.GetMediaBox()), "cropBox", Rect(p.GetCropBox()),
                "rotation", p.GetRotation()));
        }
        return JObj.Of(
            "version", doc.GetVersion(),
            "headerVersion", doc.GetDocument().GetVersion(),
            "pageCount", doc.GetNumberOfPages(),
            "encrypted", doc.IsEncrypted(),
            "encryption", EncryptionSummary(doc, true),
            "info", InfoSummary(doc),
            "xmp", XmpSummary(doc),
            "pages", pages);
    }

    // ------------------------------------------------------------------
    // text

    private static JObj Text(Context ctx)
    {
        using var doc = LoadInput(ctx);
        var (s, e) = Selection(ctx, doc.GetNumberOfPages());
        var sort = ctx.Has("--sort");
        var text = "";
        if (s <= e)
        {
            var stripper = new PDFTextStripper();
            stripper.SetSortByPosition(sort);
            stripper.SetLineSeparator("\n");
            stripper.SetPageEnd("\n");
            stripper.SetStartPage(s);
            stripper.SetEndPage(e);
            text = stripper.GetText(doc);
        }
        return JObj.Of("startPage", s, "endPage", e, "sortByPosition", sort, "text", text);
    }

    // ------------------------------------------------------------------
    // render

    private sealed record RenderValue(float Dpi, string Type, string? OutDir, List<(int Page, int Width, int Height, string? File)> Pages);

    private static ImageType ToImageType(string name) => name switch
    {
        "argb" => ImageType.Argb,
        "gray" => ImageType.Gray,
        "binary" => ImageType.Binary,
        _ => ImageType.Rgb,
    };

    private static RenderValue Render(Context ctx)
    {
        using var doc = LoadInput(ctx);
        var selection = Selection(ctx, doc.GetNumberOfPages());
        var dpi = ctx.Has("--dpi") ? ctx.Get<float>("--dpi") : 72f;
        var typeName = ctx.Get<string>("--image-type") ?? "rgb";
        var type = ToImageType(typeName);
        var outDir = ctx.Get<string>("--out-dir");
        var renderer = new PDFRenderer(doc);
        var pages = new List<(int, int, int, string?)>();
        foreach (var p in Pages(selection))
        {
            using var image = renderer.RenderImageWithDPI(p - 1, dpi, type);
            var name = Numbered("page-{0:D4}.png", p);
            if (outDir is not null) WritePng(image, Path.Combine(outDir, name));
            pages.Add((p, image.Width, image.Height, outDir is null ? null : name));
        }
        return new RenderValue(dpi, typeName, outDir, pages);
    }

    private static JObj SummarizeRender(RenderValue v) => JObj.Of(
        "dpi", v.Dpi,
        "imageType", v.Type,
        "outDir", v.OutDir,
        "pages", v.Pages.Select(p => JObj.Of("page", p.Page, "width", p.Width, "height", p.Height, "file", p.File,
            "sha256", p.File is null ? null : Hex(SHA256.HashData(File.ReadAllBytes(Path.Combine(v.OutDir!, p.File))))))
            .ToList());

    // ------------------------------------------------------------------
    // Resource walk

    private static string? StreamSubtype(COSBase? v) => v is COSStream s ? s.GetNameAsString(Name("Subtype")) : null;

    private static COSDictionary? XObjectDict(PDResources resources) => resources.GetCOSObject().GetCOSDictionary(Name("XObject"));

    /// <summary>
    /// The contract's resource walk: onResources on entry to each resources
    /// dictionary, then, in sorted XObject name order, onImage for each image
    /// and a descent into each form not already on the descent path.
    /// </summary>
    private static void Walk(PDResources? resources, string prefix, List<COSBase> stack,
        Action<string, PDResources>? onResources, Action<string, string, PDResources>? onImage)
    {
        if (resources is null) return;
        onResources?.Invoke(prefix, resources);
        var xobjects = XObjectDict(resources);
        foreach (var name in SortedNames(xobjects))
        {
            var v = xobjects!.GetDictionaryObject(Name(name));
            var path = prefix + name;
            switch (StreamSubtype(v))
            {
                case "Image":
                    onImage?.Invoke(path, name, resources);
                    break;
                case "Form" when !stack.Any(s => ReferenceEquals(s, v)):
                    Walk(new PDFormXObject((COSStream)v).GetResources(), path + "/", [.. stack, v],
                        onResources, onImage);
                    break;
            }
        }
    }

    // ------------------------------------------------------------------
    // images

    private static List<string> FilterNames(COSStream s) => s.GetFilters() switch
    {
        COSName n => [n.GetName()],
        COSArray a => a.ToList().OfType<COSName>().Select(n => n.GetName()).ToList(),
        _ => [],
    };

    private static JObj Images(Context ctx)
    {
        using var doc = LoadInput(ctx);
        var selection = Selection(ctx, doc.GetNumberOfPages());
        var outDir = ctx.Get<string>("--out-dir");
        var result = new List<JObj>();
        foreach (var p in Pages(selection))
        {
            var counter = 0;
            Walk(doc.GetPage(p - 1).GetResources(), "", [], null, (path, name, resources) =>
            {
                var seq = ++counter;
                var image = Safe(() => resources.GetXObject(Name(name)) as PDImageXObject);
                var s = (COSStream)XObjectDict(resources)!.GetDictionaryObject(Name(name));
                var decoded = image is null ? null : Safe(() => image.GetImage());
                var file = decoded is not null && outDir is not null ? Numbered("page-{0:D4}-{1:D3}.png", p, seq) : null;
                if (file is not null) WritePng(decoded!, Path.Combine(outDir!, file));
                result.Add(JObj.Of(
                    "page", p,
                    "name", path,
                    "width", s.GetInt(Name("Width"), -1),
                    "height", s.GetInt(Name("Height"), -1),
                    "colorSpace", image is null ? null : Safe(() => image.GetColorSpace().GetName()),
                    "bitsPerComponent", image is null ? null : Safe<int?>(() => image.GetBitsPerComponent()),
                    "filters", FilterNames(s),
                    "stencil", image is not null && image.IsStencil(),
                    "suffix", image is null ? null : Safe(() => image.GetSuffix()),
                    "decodedSha256", decoded is null ? null : PixelSha256(decoded),
                    "file", file,
                    "error", image is null ? "load-failed" : decoded is null ? "decode-failed" : null));
            });
        }
        return JObj.Of("images", result);
    }

    // ------------------------------------------------------------------
    // fonts

    private static string? FontEncoding(PDFont font) => font switch
    {
        PDType0Font t0 => t0.GetCMap()?.GetName(),
        PDSimpleFont simple => simple.GetEncoding()?.GetEncodingName(),
        _ => null,
    };

    private static JObj Fonts(Context ctx)
    {
        using var doc = LoadInput(ctx);
        var selection = Selection(ctx, doc.GetNumberOfPages());
        var result = new List<JObj>();
        foreach (var p in Pages(selection))
        {
            Walk(doc.GetPage(p - 1).GetResources(), "", [], (prefix, resources) =>
            {
                var fonts = resources.GetCOSObject().GetCOSDictionary(Name("Font"));
                foreach (var name in SortedNames(fonts))
                {
                    if (fonts!.GetDictionaryObject(Name(name)) is not COSDictionary d) continue;
                    var font = Safe(() => resources.GetFont(Name(name)));
                    result.Add(JObj.Of(
                        "page", p,
                        "name", prefix + name,
                        "baseFont", d.GetNameAsString(Name("BaseFont")),
                        "subtype", d.GetNameAsString(Name("Subtype")),
                        "embedded", font is null ? (bool?)null : font.IsEmbedded(),
                        "encoding", font is null ? null : Safe(() => FontEncoding(font)),
                        "toUnicode", d.ContainsKey(Name("ToUnicode")),
                        "error", font is null ? "load-failed" : null));
                }
            }, null);
        }
        return JObj.Of("fonts", result);
    }

    // ------------------------------------------------------------------
    // outline

    private static List<JObj> OutlineItems(PDDocument doc, PDOutlineNode node, List<COSDictionary> seen)
    {
        var items = new List<JObj>();
        var item = node.GetFirstChild();
        while (item is not null && !seen.Any(s => ReferenceEquals(s, item.GetCOSObject())))
        {
            seen.Add(item.GetCOSObject());
            var current = item;
            var page = Safe<int?>(() =>
            {
                var pg = current.FindDestinationPage(doc);
                if (pg is null) return null;
                var i = doc.GetPages().IndexOf(pg);
                return i >= 0 ? i + 1 : null;
            });
            var action = Safe(() => current.GetAction()?.GetSubType());
            items.Add(JObj.Of("title", item.GetTitle(), "page", page, "action", action, "open", item.IsNodeOpen(),
                "children", OutlineItems(doc, item, seen)));
            item = item.GetNextSibling();
        }
        return items;
    }

    private static JObj Outline(Context ctx)
    {
        using var doc = LoadInput(ctx);
        var outline = doc.GetDocumentCatalog().GetDocumentOutline();
        return JObj.Of("hasOutline", outline is not null,
            "items", outline is null ? new List<JObj>() : OutlineItems(doc, outline, []));
    }

    // ------------------------------------------------------------------
    // annotations

    private static JObj Annotations(Context ctx)
    {
        using var doc = LoadInput(ctx);
        var selection = Selection(ctx, doc.GetNumberOfPages());
        var result = new List<JObj>();
        foreach (var p in Pages(selection))
        {
            foreach (var a in doc.GetPage(p - 1).GetAnnotations())
            {
                result.Add(JObj.Of("page", p, "subtype", a.GetSubtype(), "rect", Rect(a.GetRectangle()),
                    "contents", a.GetContents(), "name", a.GetAnnotationName(), "flags", a.GetAnnotationFlags()));
            }
        }
        return JObj.Of("annotations", result);
    }

    // ------------------------------------------------------------------
    // form fields

    private static string FieldKind(PDField? f) => f switch
    {
        PDTextField => "text",
        PDCheckBox => "checkbox",
        PDRadioButton => "radio",
        PDPushButton => "pushbutton",
        PDComboBox => "combo",
        PDListBox => "list",
        PDSignatureField => "signature",
        PDNonTerminalField => "nonterminal",
        _ => "other",
    };

    private static object? FieldValue(PDField? f) => f switch
    {
        PDTextField t => t.GetValue(),
        PDPushButton => null,
        PDButton b => b.GetValue(),
        PDChoice c => c.GetValue().ToList(),
        _ => null,
    };

    private static List<string>? FieldOptions(PDField f) => f switch
    {
        PDPushButton => null,
        PDButton b => b.GetOnValues().OrderBy(v => v, StringComparer.Ordinal).ToList(),
        PDChoice c => c.GetOptions().ToList(),
        _ => null,
    };

    private static void FieldEntries(IEnumerable<PDField> fields, List<JObj> into)
    {
        foreach (var f in fields)
        {
            into.Add(JObj.Of("name", f.GetFullyQualifiedName(), "type", f.GetFieldType(), "kind", FieldKind(f),
                "value", FieldValue(f), "options", FieldOptions(f), "flags", f.GetFieldFlags(),
                "readOnly", f.IsReadOnly(), "required", f.IsRequired()));
            if (f is PDNonTerminalField nonTerminal) FieldEntries(nonTerminal.GetChildren(), into);
        }
    }

    private static JObj FormFields(Context ctx)
    {
        using var doc = LoadInput(ctx);
        var form = doc.GetDocumentCatalog().GetAcroForm();
        var fields = new List<JObj>();
        if (form is not null) FieldEntries(form.GetFields(), fields);
        return JObj.Of("hasAcroForm", form is not null,
            "needAppearances", form is not null && form.GetNeedAppearances(),
            "fields", fields);
    }

    // ------------------------------------------------------------------
    // form fill

    private static object? FormFill(Context ctx)
    {
        using var doc = LoadInput(ctx);
        var form = doc.GetDocumentCatalog().GetAcroForm()
            ?? throw new CliException("no-acroform", "The document has no AcroForm");
        foreach (var (name, value) in ctx.Values!)
        {
            var field = form.GetField(name) ?? throw new CliException("field-not-found", $"No field named {name}");
            if (value is List<string> list)
            {
                if (field is PDChoice choice) choice.SetValue(list);
                else throw new CliException("invalid-value", $"Field {name} does not take a list");
            }
            else
            {
                field.SetValue((string)value);
            }
        }
        doc.Save(new FileInfo(ctx.Get<string>("--out")!));
        return null;
    }

    private static JObj SummarizeFormFill(Context ctx) => Written(ctx.Get<string>("--out")!, ctx.Password, doc =>
    {
        var form = doc.GetDocumentCatalog().GetAcroForm();
        return ["fields", ctx.Values!.Keys.Select(name =>
        {
            var f = form.GetField(name);
            return JObj.Of("name", name, "kind", FieldKind(f), "value", FieldValue(f));
        }).ToList()];
    });

    // ------------------------------------------------------------------
    // attachments

    private static void NameTreeEntries(COSDictionary? node, List<COSDictionary> seen, List<(string, COSBase?)> into)
    {
        if (node is null || seen.Any(s => ReferenceEquals(s, node))) return;
        seen.Add(node);
        var names = node.GetCOSArray(Name("Names"));
        if (names is not null)
        {
            for (var i = 0; i < names.Size() - 1; i += 2)
            {
                if (names.GetObject(i) is COSString key) into.Add((key.GetString(), names.GetObject(i + 1)));
            }
        }
        var kids = node.GetCOSArray(Name("Kids"));
        if (kids is not null)
        {
            var children = Enumerable.Range(0, kids.Size()).Select(k => kids.GetObject(k)).ToList();
            foreach (var kid in children)
                if (kid is COSDictionary d) NameTreeEntries(d, seen, into);
        }
    }

    private static string SanitizeFileName(string s)
    {
        var sb = new System.Text.StringBuilder();
        foreach (var rune in s.EnumerateRunes())
        {
            var v = rune.Value;
            if (v is >= 'a' and <= 'z' or >= 'A' and <= 'Z' or >= '0' and <= '9' or '.' or '_' or '-')
                sb.Append((char)v);
            else
                sb.Append('_');
        }
        return sb.ToString();
    }

    private static JObj Attachments(Context ctx)
    {
        using var doc = LoadInput(ctx);
        var catalog = doc.GetDocumentCatalog().GetCOSObject();
        var root = catalog.GetCOSDictionary(Name("Names"))?.GetCOSDictionary(Name("EmbeddedFiles"));
        var outDir = ctx.Get<string>("--out-dir");
        var entries = new List<(string, COSBase?)>();
        NameTreeEntries(root, [], entries);
        var result = new List<JObj>();
        for (var i = 0; i < entries.Count; i++)
        {
            var (k, v) = entries[i];
            var spec = v is COSDictionary d ? new PDComplexFileSpecification(d) : null;
            var fileName = spec is not null ? spec.GetFilename() : v is COSString str ? str.GetString() : null;
            var ef = spec is null
                ? null
                : spec.GetEmbeddedFileUnicode() ?? spec.GetEmbeddedFile() ?? spec.GetEmbeddedFileDos()
                  ?? spec.GetEmbeddedFileMac() ?? spec.GetEmbeddedFileUnix();
            var data = ef?.ToByteArray();
            var parameters = ef?.GetCOSObject().GetCOSDictionary(Name("Params"));
            var file = data is not null && outDir is not null
                ? Numbered("{0:D3}-", i + 1) + SanitizeFileName(fileName ?? k ?? "attachment")
                : null;
            if (file is not null) File.WriteAllBytes(Path.Combine(outDir!, file), MemoryMarshal.AsBytes(data!.AsSpan()).ToArray());
            result.Add(JObj.Of(
                "name", k,
                "fileName", fileName,
                "description", spec?.GetFileDescription(),
                "subtype", ef?.GetSubtype(),
                "size", parameters is not null && parameters.ContainsKey(Name("Size"))
                    ? parameters.GetInt(Name("Size"))
                    : (int?)null,
                "length", data?.Length,
                "sha256", data is null ? null : Sha256(data),
                "file", file));
        }
        return JObj.Of("attachments", result);
    }

    // ------------------------------------------------------------------
    // merge

    private static object? Merge(Context ctx)
    {
        var merger = new PDFMergerUtility();
        foreach (var p in ctx.Positional) merger.AddSource(new FileInfo(p));
        merger.SetDestinationFileName(ctx.Get<string>("--out")!);
        merger.MergeDocuments(IOUtils.CreateMemoryOnlyStreamCache());
        return null;
    }

    // ------------------------------------------------------------------
    // split

    private static List<(string Range, string File)> Split(Context ctx)
    {
        using var doc = LoadInput(ctx);
        var n = doc.GetNumberOfPages();
        var ranges = ctx.Get<List<(int, int)>>("--ranges")!.Select(r => Resolve(r, n)).ToList();
        foreach (var (s, e) in ranges)
            if (s > e) throw new CliException("page-range", $"Range selects no page of {n}");
        var result = new List<(string, string)>();
        for (var i = 0; i < ranges.Count; i++)
        {
            var (s, e) = ranges[i];
            var file = Numbered("part-{0:D3}.pdf", i + 1);
            var splitter = new Splitter();
            splitter.SetStartPage(s);
            splitter.SetEndPage(e);
            splitter.SetSplitAtPage(e - s + 1);
            var parts = splitter.Split(doc);
            try
            {
                parts[0].Save(new FileInfo(Path.Combine(ctx.Get<string>("--out-dir")!, file)));
            }
            finally
            {
                foreach (var part in parts) part.Dispose();
            }
            result.Add((s.ToString(CultureInfo.InvariantCulture) + "-" + e.ToString(CultureInfo.InvariantCulture), file));
        }
        return result;
    }

    private static JObj SummarizeSplit(Context ctx, List<(string Range, string File)> parts)
    {
        var dir = ctx.Get<string>("--out-dir")!;
        return JObj.Of("outDir", dir, "parts", parts.Select(part =>
        {
            var path = Path.Combine(dir, part.File);
            using var d = Reopen(path, ctx.Password);
            return JObj.Of("range", part.Range, "file", part.File, "bytes", new FileInfo(path).Length,
                "pageCount", d.GetNumberOfPages());
        }).ToList());
    }

    // ------------------------------------------------------------------
    // rotate

    private static object? Rotate(Context ctx)
    {
        using var doc = LoadInput(ctx);
        var selection = Selection(ctx, doc.GetNumberOfPages());
        long angle = ctx.Get<int>("--angle");
        foreach (var p in Pages(selection))
        {
            var page = doc.GetPage(p - 1);
            page.SetRotation((int)(((page.GetRotation() + angle) % 360 + 360) % 360));
        }
        doc.Save(new FileInfo(ctx.Get<string>("--out")!));
        return null;
    }

    // ------------------------------------------------------------------
    // encrypt / decrypt

    private static AccessPermission Permissions(HashSet<string>? granted)
    {
        var ap = new AccessPermission();
        if (granted is not null)
        {
            ap.SetCanPrint(granted.Contains("print"));
            ap.SetCanModify(granted.Contains("modify"));
            ap.SetCanExtractContent(granted.Contains("extract"));
            ap.SetCanModifyAnnotations(granted.Contains("annotate"));
            ap.SetCanFillInForm(granted.Contains("fill-in"));
            ap.SetCanExtractForAccessibility(granted.Contains("extract-for-accessibility"));
            ap.SetCanAssembleDocument(granted.Contains("assemble"));
            ap.SetCanPrintFaithful(granted.Contains("print-faithful"));
        }
        return ap;
    }

    private static object? Encrypt(Context ctx)
    {
        using var doc = LoadInput(ctx);
        var policy = new StandardProtectionPolicy(ctx.Get<string>("--owner-password")!,
            ctx.Get<string>("--user-password") ?? "", Permissions(ctx.Get<HashSet<string>>("--permissions")));
        policy.SetEncryptionKeyLength(ctx.Has("--key-length") ? ctx.Get<int>("--key-length") : 256);
        policy.SetPreferAES(true);
        doc.Protect(policy);
        doc.Save(new FileInfo(ctx.Get<string>("--out")!));
        return null;
    }

    private static object? Decrypt(Context ctx)
    {
        using var doc = LoadInput(ctx);
        if (!doc.IsEncrypted()) throw new CliException("not-encrypted", "The document is not encrypted");
        if (!doc.GetCurrentAccessPermission().IsOwnerPermission())
            throw new CliException("owner-password-required", "Decryption needs the owner password");
        doc.SetAllSecurityToBeRemoved(true);
        doc.Save(new FileInfo(ctx.Get<string>("--out")!));
        return null;
    }

    // ------------------------------------------------------------------
    // create

    internal const string DefaultCreateText = "Hello from pdfcli";

    private static SKBitmap CreateImage()
    {
        var image = PdfCartonFontCompat.CreateBitmap(16, 16, PdfCartonFontCompat.TYPE_INT_RGB);
        for (var y = 0; y < 16; y++)
            for (var x = 0; x < 16; x++)
                PdfCartonFontCompat.SetRgb(image, x, y, ((16 * x) << 16) | ((16 * y) << 8) | 128);
        return image;
    }

    private static object? Create(Context ctx)
    {
        using var doc = new PDDocument();
        var page = new PDPage(PDRectangle.Letter);
        doc.AddPage(page);
        using var pixels = CreateImage();
        var image = LosslessFactory.CreateFromImage(doc, pixels);
        using (var cs = new PDPageContentStream(doc, page))
        {
            cs.SetNonStrokingColor(0.2f, 0.4f, 0.8f);
            cs.SetStrokingColor(0f, 0f, 0f);
            cs.SetLineWidth(2f);
            cs.AddRect(72f, 620f, 200f, 60f);
            cs.FillAndStroke();
            cs.DrawImage(image, 72f, 520f, 64f, 64f);
            cs.BeginText();
            cs.SetFont(new PDType1Font(Standard14Fonts.FontName.Helvetica), 12f);
            cs.NewLineAtOffset(72f, 720f);
            cs.ShowText(ctx.Get<string>("--text") ?? DefaultCreateText);
            cs.EndText();
        }
        doc.Save(new FileInfo(ctx.Get<string>("--out")!));
        return null;
    }

    // ------------------------------------------------------------------
    // save

    private static object? Save(Context ctx)
    {
        using var doc = LoadInput(ctx);
        doc.Save(new FileInfo(ctx.Get<string>("--out")!),
            ctx.Has("--compress") ? CompressParameters.DefaultCompression : CompressParameters.NoCompression);
        return null;
    }
}
