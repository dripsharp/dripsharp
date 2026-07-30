using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using DripSharp.Runtime;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Contentstream;
using DripSharp.PdfCarton.Cos;
using DripSharp.PdfCarton.Pdfwriter.Compress;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Common;
using DripSharp.PdfCarton.Pdmodel.Common.Function;
using DripSharp.PdfCarton.Pdmodel.Graphics;
using DripSharp.PdfCarton.Pdmodel.Graphics.Blend;
using DripSharp.PdfCarton.Pdmodel.Graphics.Color;
using DripSharp.PdfCarton.Pdmodel.Graphics.Form;
using DripSharp.PdfCarton.Pdmodel.Graphics.Image;
using DripSharp.PdfCarton.Pdmodel.Graphics.Pattern;
using DripSharp.PdfCarton.Pdmodel.Graphics.Shading;
using DripSharp.PdfCarton.Pdmodel.Graphics.State;
using PdfOperator = DripSharp.PdfCarton.Contentstream.@operator.Operator;

internal static class Program
{
    private static readonly List<string> Observations = new();

    private static int Main(string[] args)
    {
        try
        {
            if (args.Length is < 3 or > 4)
                throw new ArgumentException(
                    "Expected output trace, resource root, exchange directory, and optional --write-only.");
            if (args.Length == 4 && args[3] != "--write-only")
                throw new ArgumentException("The only supported probe mode is --write-only.");

            var output = args[0];
            var resources = args[1];
            var exchange = args[2];
            Directory.CreateDirectory(exchange);
            var ownPdf = Path.Combine(exchange, "dotnet-graphics.pdf");
            WriteRepresentative(ownPdf);
            if (args.Length == 4)
                return 0;

            ObserveModels();
            ProcessPdf(ownPdf, "synthetic", null);
            ProcessPdf(
                Path.Combine(resources, "input", "merge", "jpegrgb.pdf"),
                "jpegrgb",
                "representative-pdf");
            ProcessPdf(
                Path.Combine(exchange, "java-graphics.pdf"),
                "cross",
                "cross-reopen");

            File.WriteAllLines(output, Observations, new UTF8Encoding(false));
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    private static void ObserveModels()
    {
        Observe(
            "backend",
            "canonical",
            "cpu",
            "operator-and-state",
            "gpu-independent");

        var gray = PDDeviceGray.Instance;
        var rgb = PDDeviceRGB.Instance;
        var cmyk = PDDeviceCMYK.Instance;
        Observe(
            "device-color",
            "spaces",
            gray.GetName(),
            gray.GetNumberOfComponents(),
            Numbers(gray.GetDefaultDecode(8)),
            Numbers(gray.GetInitialColor().GetComponents()),
            Numbers(gray.ToRGB(new[] { 0.25f })),
            rgb.GetName(),
            rgb.GetNumberOfComponents(),
            Numbers(rgb.GetDefaultDecode(8)),
            Numbers(rgb.ToRGB(new[] { 0.1f, 0.2f, 0.3f })),
            cmyk.GetName(),
            cmyk.GetNumberOfComponents(),
            Numbers(cmyk.GetInitialColor().GetComponents()));

        var calGray = new PDCalGray();
        calGray.SetWhitePoint(new PDTristimulus(new[] { 0.95f, 1f, 1.09f }));
        calGray.SetBlackPoint(new PDTristimulus(new[] { 0.01f, 0.02f, 0.03f }));
        calGray.SetGamma(2.2f);
        var calRgb = new PDCalRGB();
        calRgb.SetWhitePoint(new PDTristimulus(new[] { 0.95f, 1f, 1.09f }));
        calRgb.SetGamma(new PDGamma(Floats(2.1f, 2.2f, 2.3f)));
        calRgb.GetCOSObject();
        var lab = new PDLab();
        lab.SetWhitePoint(new PDTristimulus(new[] { 0.95f, 1f, 1.09f }));
        lab.SetARange(new PDRange(Floats(-80, 90)));
        lab.SetBRange(new PDRange(Floats(-70, 75)));
        Observe(
            "calibrated-color",
            "models",
            calGray.GetName(),
            calGray.GetGamma(),
            Tristimulus(calGray.GetWhitepoint()),
            Tristimulus(calGray.GetBlackPoint()),
            calRgb.GetName(),
            Numbers(calRgb.GetGamma().GetCOSArray().ToFloatArray()),
            Numbers(calRgb.GetMatrix()),
            lab.GetName(),
            Numbers(lab.GetDefaultDecode(8)),
            Numbers(lab.GetInitialColor().GetComponents()));

        var tint = Type2(
            new[] { 0f, 0f, 0f },
            new[] { 1f, 0.5f, 0.25f });
        var separation = new PDSeparation();
        separation.SetColorantName("SpotOrange");
        separation.SetAlternateColorSpace(rgb);
        separation.SetTintTransform(tint);
        var deviceN = new PDDeviceN();
        deviceN.SetColorantNames(new[] { "Cyan", "SpotOrange" });
        deviceN.SetAlternateColorSpace(rgb);
        deviceN.SetTintTransform(tint);
        Observe(
            "special-color",
            "separation-and-devicen",
            separation.GetName(),
            separation.GetColorantName(),
            separation.GetAlternateColorSpace().GetName(),
            Numbers(separation.ToRGB(new[] { 0.4f })),
            deviceN.GetName(),
            string.Join(",", deviceN.GetColorantNames()),
            deviceN.GetNumberOfComponents(),
            deviceN.GetAlternateColorSpace().GetName(),
            Numbers(deviceN.ToRGB(new[] { 0.4f, 0.8f })));

        var patternResources = new PDResources();
        var tiling = CreateTilingPattern();
        var tilingName = patternResources.Add(tiling);
        var patternSpace = new PDPattern(patternResources, rgb);
        var patternColor =
            new PDColor(new[] { 0.2f, 0.4f, 0.6f }, tilingName, patternSpace);
        Observe(
            "pattern",
            "tiling-and-color",
            tiling.GetPatternType(),
            tiling.GetPaintType(),
            tiling.GetTilingType(),
            tiling.GetXStep(),
            tiling.GetYStep(),
            Rectangle(tiling.GetBBox()),
            Matrix(tiling.GetMatrix()),
            patternSpace.GetName(),
            patternSpace.GetUnderlyingColorSpace().GetName(),
            patternColor.IsPattern(),
            patternColor.GetPatternName().GetName(),
            patternSpace.GetPattern(patternColor).GetPatternType());

        var shading = CreateShading();
        var shadingPattern = new PDShadingPattern();
        shadingPattern.SetShading(shading);
        var patternState = new PDExtendedGraphicsState();
        patternState.SetBlendMode(BlendMode.Screen);
        shadingPattern.SetExtendedGraphicsState(patternState);
        Observe(
            "shading",
            "axial-and-pattern",
            shading.GetShadingType(),
            shading.GetColorSpace().GetName(),
            Numbers(shading.GetCoords().ToFloatArray()),
            Numbers(shading.GetDomain().ToFloatArray()),
            Bools(shading.GetExtend()),
            Numbers(shading.EvalFunction(0.25f)),
            shading.GetAntiAlias(),
            Rectangle(shading.GetBBox()),
            shadingPattern.GetPatternType(),
            shadingPattern.GetShading().GetShadingType(),
            shadingPattern.GetExtendedGraphicsState()
                .GetBlendMode().GetCOSName().GetName());

        var multiply = BlendMode.GetInstance(COSName.Multiply);
        var modes = new COSArray();
        modes.Add(COSName.GetPDFName("UnsupportedBlend"));
        modes.Add(COSName.Screen);
        var selected = BlendMode.GetInstance(modes);
        var hueResult = new float[3];
        BlendMode.Hue.GetBlendFunction().Blend(
            new[] { 0.2f, 0.7f, 0.4f },
            new[] { 0.8f, 0.3f, 0.6f },
            hueResult);
        Observe(
            "blend-mode",
            "selection-and-functions",
            multiply.GetCOSName().GetName(),
            multiply.IsSeparableBlendMode(),
            multiply.GetBlendChannelFunction().BlendChannel(0.25f, 0.8f),
            selected.GetCOSName().GetName(),
            BlendMode.GetInstance(COSName.GetPDFName("NoSuchMode"))
                .GetCOSName().GetName(),
            BlendMode.Hue.IsSeparableBlendMode(),
            Numbers(hueResult));

        using var document = new PDDocument();
        var group = CreateTransparencyGroup(document);
        var softMask = CreateSoftMask(group);
        var ext = CreateExtendedState(softMask);
        var state = new PDGraphicsState(new PDRectangle(200, 100));
        state.SetCurrentTransformationMatrix(new DripSharp.PdfCarton.Util.Matrix(
            2, 0.5f, -0.25f, 3, 7, 11));
        ext.CopyIntoGraphicsState(state);
        var clipping = state.GetCurrentClippingPath().Bounds;
        Observe(
            "graphics-state",
            "copy-and-clone",
            State(state),
            state.Clone().GetTextState().GetKnockoutFlag());
        Observe(
            "clipping",
            "initial-page-clip",
            clipping.Left,
            clipping.Top,
            clipping.Width,
            clipping.Height);
        Observe(
            "extended-state",
            "copy",
            state.GetLineWidth(),
            state.GetLineCap(),
            state.GetLineJoin(),
            state.GetMiterLimit(),
            Numbers(state.GetLineDashPattern().GetDashArray()),
            state.GetLineDashPattern().GetPhase(),
            state.GetRenderingIntent().StringValue(),
            state.IsOverprint(),
            state.IsNonStrokingOverprint(),
            state.GetOverprintMode(),
            state.GetFlatness(),
            state.GetSmoothness(),
            state.IsStrokeAdjustment(),
            state.GetAlphaConstant(),
            state.GetNonStrokeAlphaConstant(),
            state.IsAlphaSource(),
            state.GetTextState().GetKnockoutFlag(),
            state.GetBlendMode().GetCOSName().GetName(),
            state.GetSoftMask() is not null);
        Observe(
            "transparency-group",
            "attributes",
            group.GetGroup().IsIsolated(),
            group.GetGroup().IsKnockout(),
            group.GetGroup().GetColorSpace().GetName(),
            Rectangle(group.GetBBox()));
        Observe(
            "soft-mask",
            "model",
            softMask.GetSubType().GetName(),
            softMask.GetGroup() is not null,
            Numbers(softMask.GetBackdropColor().ToFloatArray()),
            Numbers(softMask.GetTransferFunction().Eval(new[] { 0.25f })));

        Observe(
            "malformed-operand",
            "model-factories",
            Fails<IOException>(() => PDColorSpace.Create(new COSArray())),
            Fails<IOException>(() => PDShading.Create(new COSDictionary())),
            Fails<IOException>(
                () => PDAbstractPattern.Create(new COSDictionary(), null!)),
            PDSoftMask.Create(COSInteger.Zero) is null);
    }

    private static void WriteRepresentative(string path)
    {
        using var document = new PDDocument();
        var page = new PDPage(new PDRectangle(300, 300));
        var resources = new PDResources();
        page.SetResources(resources);
        document.AddPage(page);

        resources.Put(COSName.GetPDFName("Cal1"), CreateCalRgb());
        var tint = Type2(
            new[] { 0f, 0f, 0f },
            new[] { 1f, 0.5f, 0.25f });
        var separation = new PDSeparation();
        separation.SetColorantName("SpotOrange");
        separation.SetAlternateColorSpace(PDDeviceRGB.Instance);
        separation.SetTintTransform(tint);
        resources.Put(COSName.GetPDFName("Sep1"), separation);
        var deviceN = new PDDeviceN();
        deviceN.SetColorantNames(new[] { "Cyan", "SpotOrange" });
        deviceN.SetAlternateColorSpace(PDDeviceRGB.Instance);
        deviceN.SetTintTransform(tint);
        resources.Put(COSName.GetPDFName("DN1"), deviceN);
        resources.Put(COSName.GetPDFName("P1"), CreateTilingPattern());
        resources.Put(COSName.GetPDFName("Sh1"), CreateShading());
        var group = CreateTransparencyGroup(document);
        resources.Put(
            COSName.GetPDFName("GS1"),
            CreateExtendedState(CreateSoftMask(group)));

        using (var content = new PDPageContentStream(
                   document,
                   page,
                   PDPageContentStream.AppendMode.Overwrite,
                   false))
        {
            content.AppendRawCommands(SyntheticContent);
        }
        document.Save(new FileInfo(path), CompressParameters.NoCompression);
    }

    private static readonly string SyntheticContent = string.Join(
        "\n",
        "q",
        "2 0.5 -0.25 3 7 11 cm",
        "2.5 w",
        "1 J",
        "2 j",
        "8 M",
        "[3 2] -1 d",
        "/RelativeColorimetric ri",
        "0.2 G",
        "0.3 g",
        "0.1 0.2 0.3 RG",
        "0.4 0.5 0.6 rg",
        "/Cal1 cs",
        "0.2 0.3 0.4 sc",
        "/Sep1 CS",
        "0.7 SCN",
        "/DN1 cs",
        "0.2 0.8 scn",
        "/Pattern cs",
        "/P1 scn",
        "10 20 m",
        "30 40 l",
        "41 42 43 44 45 46 c",
        "47 48 49 50 v",
        "51 52 53 54 y",
        "h",
        "S",
        "10 10 20 30 re",
        "W",
        "n",
        "5 5 m 20 5 l 20 20 l h B",
        "25 25 m 40 25 l 40 40 l h f*",
        "BT",
        "/F1 12 Tf",
        "1 Tc",
        "2 Tw",
        "80 Tz",
        "14 TL",
        "3 Ts",
        "2 Tr",
        "1 0 0 1 15 25 Tm",
        "4 5 Td",
        "6 7 TD",
        "T*",
        "ET",
        "/GS1 gs",
        "/Span BMC",
        "/Span << /MCID 7 >> BDC",
        "EMC",
        "EMC",
        "/Sh1 sh",
        "BX",
        "12 13 UnknownOp",
        "EX",
        "Q",
        "1 m",
        "(bad) 2 m",
        "Q",
        "/Missing cs",
        "");

    private static PDCalRGB CreateCalRgb()
    {
        var cal = new PDCalRGB();
        cal.SetWhitePoint(new PDTristimulus(new[] { 0.95f, 1f, 1.09f }));
        cal.SetGamma(new PDGamma(Floats(2.1f, 2.2f, 2.3f)));
        return cal;
    }

    private static PDTilingPattern CreateTilingPattern()
    {
        var pattern = new PDTilingPattern();
        pattern.SetPaintType(PDTilingPattern.PaintUncolored);
        pattern.SetTilingType(PDTilingPattern.TilingConstantSpacing);
        pattern.SetXStep(12.5f);
        pattern.SetYStep(9.5f);
        pattern.SetBBox(new PDRectangle(0, 0, 10, 8));
        pattern.GetCOSObject().SetItem(
            COSName.Matrix,
            Floats(1, 0.25f, -0.5f, 1, 3, 4));
        return pattern;
    }

    private static PDShadingType2 CreateShading()
    {
        var dictionary = new COSDictionary();
        dictionary.SetInt(COSName.ShadingType, PDShading.ShadingType2);
        var shading = new PDShadingType2(dictionary);
        shading.SetShadingType(PDShading.ShadingType2);
        shading.SetColorSpace(PDDeviceRGB.Instance);
        shading.SetCoords(Floats(0, 0, 100, 50));
        shading.SetDomain(Floats(0, 1));
        shading.SetExtend(Bools(true, false));
        shading.SetFunction(Type2(
            new[] { 0f, 0.2f, 0.4f },
            new[] { 1f, 0.8f, 0.6f }));
        shading.SetBackground(Floats(0.1f, 0.2f, 0.3f));
        shading.SetBBox(new PDRectangle(0, 0, 100, 50));
        shading.SetAntiAlias(true);
        return shading;
    }

    private static PDTransparencyGroup CreateTransparencyGroup(
        PDDocument document)
    {
        var group = new PDTransparencyGroup(document);
        group.SetBBox(new PDRectangle(0, 0, 40, 30));
        group.SetResources(new PDResources());
        var attributes = new PDTransparencyGroupAttributes();
        attributes.GetCOSObject().SetBoolean(COSName.I, true);
        attributes.GetCOSObject().SetBoolean(COSName.K, true);
        attributes.GetCOSObject().SetItem(
            COSName.Cs,
            PDDeviceRGB.Instance.GetCOSObject());
        group.SetGroup(attributes);
        return group;
    }

    private static PDSoftMask CreateSoftMask(PDTransparencyGroup group)
    {
        var dictionary = new COSDictionary();
        dictionary.SetItem(COSName.S, COSName.Luminosity);
        dictionary.SetItem(COSName.G, group);
        dictionary.SetItem(COSName.Bc, Floats(0.1f, 0.2f, 0.3f));
        dictionary.SetItem(
            COSName.Tr,
            Type2(new[] { 0f }, new[] { 1f }));
        return new PDSoftMask(dictionary);
    }

    private static PDExtendedGraphicsState CreateExtendedState(
        PDSoftMask softMask)
    {
        var state = new PDExtendedGraphicsState();
        state.SetLineWidth(4.25f);
        state.SetLineCapStyle(2);
        state.SetLineJoinStyle(1);
        state.SetMiterLimit(8f);
        state.SetLineDashPattern(new PDLineDashPattern(Floats(3, 2), -1));
        state.SetRenderingIntent("AbsoluteColorimetric");
        state.SetStrokingOverprintControl(true);
        state.SetNonStrokingOverprintControl(false);
        state.SetOverprintMode(1);
        state.SetFlatnessTolerance(0.5f);
        state.SetSmoothnessTolerance(0.25f);
        state.SetAutomaticStrokeAdjustment(true);
        state.SetStrokingAlphaConstant(0.7f);
        state.SetNonStrokingAlphaConstant(0.3f);
        state.SetAlphaSourceFlag(true);
        state.SetTextKnockoutFlag(false);
        state.SetBlendMode(BlendMode.Multiply);
        state.GetCOSObject().SetItem(COSName.Smask, softMask);
        return state;
    }

    private static PDFunctionType2 Type2(float[] c0, float[] c1)
    {
        var dictionary = new COSDictionary();
        dictionary.SetInt(COSName.FunctionType, 2);
        dictionary.SetItem(COSName.Domain, Floats(0, 1));
        dictionary.SetItem(COSName.C0, Floats(c0));
        dictionary.SetItem(COSName.C1, Floats(c1));
        dictionary.SetFloat(COSName.N, 1);
        var range = new COSArray();
        for (var index = 0; index < Math.Min(c0.Length, c1.Length); index++)
        {
            range.Add(COSFloat.Zero);
            range.Add(COSFloat.One);
        }
        dictionary.SetItem(COSName.Range, range);
        return new PDFunctionType2(dictionary);
    }

    private static void ProcessPdf(
        string path,
        string prefix,
        string? familyOverride)
    {
        if (!File.Exists(path))
            throw new FileNotFoundException("Required graphics fixture is missing", path);
        using var document = Loader.LoadPDF(new FileInfo(path));
        var page = document.GetPage(0);
        var engine = new TraceEngine(page, prefix, familyOverride);
        engine.ProcessPage(page);
    }

    private static COSArray Floats(params float[] values)
    {
        var array = new COSArray();
        array.SetFloatArray(values);
        return array;
    }

    private static COSArray Bools(params bool[] values)
    {
        var array = new COSArray();
        foreach (var value in values)
            array.Add(value ? COSBoolean.True : COSBoolean.False);
        return array;
    }

    private static string Bools(COSArray values)
    {
        var result = new List<string>();
        for (var index = 0; index < values.Size(); index++)
            result.Add(
                Value(((COSBoolean)values.GetObject(index)!).GetValue()));
        return string.Join(",", result);
    }

    private static string State(PDGraphicsState state)
    {
        var text = state.GetTextState();
        return string.Join(
            ";",
            "stack",
            Matrix(state.GetCurrentTransformationMatrix()),
            "line=" + string.Join(
                ",",
                Number(state.GetLineWidth()),
                state.GetLineCap(),
                state.GetLineJoin(),
                Number(state.GetMiterLimit()),
                Numbers(state.GetLineDashPattern().GetDashArray()),
                state.GetLineDashPattern().GetPhase()),
            "stroke=" + Color(state.GetStrokingColor()),
            "fill=" + Color(state.GetNonStrokingColor()),
            "text=" + string.Join(
                ",",
                Number(text.GetCharacterSpacing()),
                Number(text.GetWordSpacing()),
                Number(text.GetHorizontalScaling()),
                Number(text.GetLeading()),
                Number(text.GetFontSize()),
                text.GetRenderingMode().IntValue(),
                Number(text.GetRise()),
                Value(text.GetKnockoutFlag())),
            "tm=" + Matrix(state.GetTextMatrix()),
            "tlm=" + Matrix(state.GetTextLineMatrix()),
            "blend=" + state.GetBlendMode().GetCOSName().GetName(),
            "alpha=" + Number(state.GetAlphaConstant()) + "," +
                Number(state.GetNonStrokeAlphaConstant()));
    }

    private static string Color(PDColor color)
    {
        if (color is null)
            return "null";
        var colorSpace = color.GetColorSpace();
        return (colorSpace?.GetName() ?? "null") + ":" +
            Numbers(color.GetComponents()) + ":" +
            (color.GetPatternName()?.GetName() ?? "null");
    }

    private static string Matrix(DripSharp.PdfCarton.Util.Matrix? matrix)
    {
        if (matrix is null)
            return "null";
        return string.Join(
            ",",
            Number(matrix.GetScaleX()),
            Number(matrix.GetShearY()),
            Number(matrix.GetShearX()),
            Number(matrix.GetScaleY()),
            Number(matrix.GetTranslateX()),
            Number(matrix.GetTranslateY()));
    }

    private static string Rectangle(PDRectangle? rectangle)
    {
        if (rectangle is null)
            return "null";
        return string.Join(
            ",",
            Number(rectangle.GetLowerLeftX()),
            Number(rectangle.GetLowerLeftY()),
            Number(rectangle.GetWidth()),
            Number(rectangle.GetHeight()));
    }

    private static string Tristimulus(PDTristimulus value) =>
        string.Join(
            ",",
            Number(value.GetX()),
            Number(value.GetY()),
            Number(value.GetZ()));

    private static string Numbers(IEnumerable<float> values) =>
        string.Join(",", values.Select(value => Number(value)));

    private static string Number(double value)
    {
        var text = value.ToString("0.######", CultureInfo.InvariantCulture);
        return text == "-0" ? "0" : text;
    }

    private static string Value(object? value) =>
        value switch
        {
            null => "null",
            bool boolean => boolean ? "true" : "false",
            float single => Number(single),
            double number => Number(number),
            _ => Convert.ToString(value, CultureInfo.InvariantCulture) ?? "null"
        };

    private static void Observe(
        string family,
        string id,
        params object?[] values)
    {
        Observations.Add(
            family + "\t" + id + "\t" +
            string.Join("|", values.Select(Value)));
    }

    private static bool Fails<T>(Action action)
        where T : Exception
    {
        try
        {
            action();
            return false;
        }
        catch (T)
        {
            return true;
        }
    }

    private sealed class TraceEngine : PDFGraphicsStreamEngine
    {
        private static readonly HashSet<string> MatrixOperators =
            new(StringComparer.Ordinal) { "cm", "Tm", "Td", "TD", "T*" };
        private static readonly HashSet<string> GraphicsOperators =
            new(StringComparer.Ordinal)
            {
                "w", "J", "j", "M", "d", "ri", "i", "gs"
            };
        private static readonly HashSet<string> ColorOperators =
            new(StringComparer.Ordinal)
            {
                "G", "g", "RG", "rg", "CS", "cs", "SC", "sc", "SCN", "scn",
                "K", "k"
            };
        private static readonly HashSet<string> TextOperators =
            new(StringComparer.Ordinal)
            {
                "BT", "ET", "Tc", "Tw", "Tz", "TL", "Tf", "Tr", "Ts",
                "Td", "TD", "T*", "Tm", "Tj", "TJ", "'", "\""
            };
        private static readonly HashSet<string> PathOperators =
            new(StringComparer.Ordinal)
            {
                "m", "l", "c", "v", "y", "h", "re"
            };
        private static readonly HashSet<string> PaintOperators =
            new(StringComparer.Ordinal)
            {
                "S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n"
            };

        private readonly string prefix;
        private readonly string? familyOverride;
        private int eventIndex;
        private JavaPoint2D? currentPoint;

        internal TraceEngine(
            PDPage page,
            string prefix,
            string? familyOverride)
            : base(page)
        {
            this.prefix = prefix;
            this.familyOverride = familyOverride;
        }

        protected override void ProcessOperator(
            PdfOperator op,
            IList<COSBase> operands)
        {
            base.ProcessOperator(op, operands);
            var name = op.GetName();
            Emit(
                "operator-dispatch",
                "operator",
                name,
                operands.Count,
                GetGraphicsStackSize(),
                State(GetGraphicsState()));
            var family = Classify(name);
            if (family is not null)
            {
                Emit(
                    family,
                    "state",
                    name,
                    GetGraphicsStackSize(),
                    State(GetGraphicsState()));
            }
        }

        protected override void UnsupportedOperator(
            PdfOperator op,
            IList<COSBase> operands)
        {
            Emit(
                "compatibility",
                "unsupported",
                op.GetName(),
                operands.Count,
                GetGraphicsStackSize());
            base.UnsupportedOperator(op, operands);
        }

        protected override void OperatorException(
            PdfOperator op,
            IList<COSBase> operands,
            IOException exception)
        {
            Emit(
                "malformed-operand",
                "operator-error",
                op.GetName(),
                operands.Count,
                exception.GetType().Name,
                GetGraphicsStackSize());
            base.OperatorException(op, operands, exception);
        }

        public override void BeginMarkedContentSequence(
            COSName tag,
            COSDictionary properties)
        {
            Emit(
                "marked-content",
                "begin",
                tag.GetName(),
                properties?.GetInt(COSName.Mcid, -1) ?? -1);
        }

        public override void EndMarkedContentSequence()
        {
            Emit("marked-content", "end");
        }

        public override void AppendRectangle(
            JavaPoint2D p0,
            JavaPoint2D p1,
            JavaPoint2D p2,
            JavaPoint2D p3)
        {
            currentPoint = p0;
            Emit(
                "path",
                "rectangle",
                Point(p0),
                Point(p1),
                Point(p2),
                Point(p3));
        }

        public override void DrawImage(PDImage pdImage)
        {
            Emit(
                "operator-dispatch",
                "image",
                pdImage.GetWidth(),
                pdImage.GetHeight(),
                pdImage.GetBitsPerComponent());
        }

        public override void Clip(int windingRule)
        {
            Emit("clipping", "clip", windingRule, Point(currentPoint));
        }

        public override void MoveTo(float x, float y)
        {
            currentPoint = new JavaPoint2D(x, y);
            Emit("path", "move", x, y);
        }

        public override void LineTo(float x, float y)
        {
            currentPoint = new JavaPoint2D(x, y);
            Emit("path", "line", x, y);
        }

        public override void CurveTo(
            float x1,
            float y1,
            float x2,
            float y2,
            float x3,
            float y3)
        {
            currentPoint = new JavaPoint2D(x3, y3);
            Emit("path", "curve", x1, y1, x2, y2, x3, y3);
        }

        public override JavaPoint2D GetCurrentPoint() =>
            currentPoint ?? new JavaPoint2D(0, 0);

        public override void ClosePath()
        {
            Emit("path", "close", Point(currentPoint));
        }

        public override void EndPath()
        {
            Emit("stroke-fill", "end");
            currentPoint = null;
        }

        public override void StrokePath()
        {
            Emit("stroke-fill", "stroke", State(GetGraphicsState()));
            currentPoint = null;
        }

        public override void FillPath(int windingRule)
        {
            Emit(
                "stroke-fill",
                "fill",
                windingRule,
                State(GetGraphicsState()));
            currentPoint = null;
        }

        public override void FillAndStrokePath(int windingRule)
        {
            Emit(
                "stroke-fill",
                "fill-stroke",
                windingRule,
                State(GetGraphicsState()));
            currentPoint = null;
        }

        public override void ShadingFill(COSName shadingName)
        {
            var shading = GetResources().GetShading(shadingName);
            Emit(
                "shading",
                "operator",
                shadingName.GetName(),
                shading?.GetShadingType() ?? -1);
        }

        private void Emit(
            string family,
            string eventName,
            params object?[] values)
        {
            eventIndex++;
            var actualFamily = familyOverride ?? family;
            var allValues = new object?[values.Length + 1];
            allValues[0] = eventName;
            Array.Copy(values, 0, allValues, 1, values.Length);
            Observe(
                actualFamily,
                prefix + "-" + eventIndex.ToString("D6", CultureInfo.InvariantCulture),
                allValues);
        }

        private static string? Classify(string name)
        {
            if (name is "q" or "Q")
                return "graphics-stack";
            if (MatrixOperators.Contains(name))
                return "matrix";
            if (GraphicsOperators.Contains(name))
                return "graphics-state";
            if (ColorOperators.Contains(name))
                return "color-operator";
            if (TextOperators.Contains(name))
                return "text-state";
            if (PathOperators.Contains(name))
                return "path";
            if (PaintOperators.Contains(name))
                return "stroke-fill";
            if (name is "W" or "W*")
                return "clipping";
            if (name is "BMC" or "BDC" or "EMC" or "MP" or "DP")
                return "marked-content";
            if (name is "BX" or "EX")
                return "compatibility";
            if (name == "sh")
                return "shading";
            return null;
        }

        private static string Point(JavaPoint2D? point) =>
            point is null
                ? "null"
                : Number(point.X) + "," + Number(point.Y);
    }
}
