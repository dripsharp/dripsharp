import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRange;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType2;
import org.apache.pdfbox.pdmodel.graphics.PDLineDashPattern;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.color.PDCalGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDCalRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceN;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDGamma;
import org.apache.pdfbox.pdmodel.graphics.color.PDLab;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.color.PDSeparation;
import org.apache.pdfbox.pdmodel.graphics.color.PDTristimulus;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroupAttributes;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDShadingPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask;
import org.apache.pdfbox.pdmodel.graphics.state.PDTextState;
import org.apache.pdfbox.util.Matrix;

public final class PdfBoxGraphicsOracle
{
    private static final List<String> OBSERVATIONS = new ArrayList<>();

    public static void main(String[] args) throws Exception
    {
        if (args.length != 3)
        {
            throw new IllegalArgumentException(
                    "Expected output trace, resource root, and exchange directory.");
        }
        Path output = Paths.get(args[0]);
        Path resources = Paths.get(args[1]);
        Path exchange = Paths.get(args[2]);
        Files.createDirectories(exchange);
        Path ownPdf = exchange.resolve("java-graphics.pdf");
        writeRepresentative(ownPdf);

        observeModels();
        processPdf(ownPdf, "synthetic", null);
        processPdf(
                resources.resolve("input/merge/jpegrgb.pdf"),
                "jpegrgb",
                "representative-pdf");
        processPdf(
                exchange.resolve("dotnet-graphics.pdf"),
                "cross",
                "cross-reopen");

        Files.write(output, OBSERVATIONS, StandardCharsets.UTF_8);
    }

    private static void observeModels() throws Exception
    {
        observe(
                "backend",
                "canonical",
                "cpu",
                "operator-and-state",
                "gpu-independent");

        PDDeviceGray gray = PDDeviceGray.INSTANCE;
        PDDeviceRGB rgb = PDDeviceRGB.INSTANCE;
        PDDeviceCMYK cmyk = PDDeviceCMYK.INSTANCE;
        observe(
                "device-color",
                "spaces",
                gray.getName(),
                gray.getNumberOfComponents(),
                numbers(gray.getDefaultDecode(8)),
                numbers(gray.getInitialColor().getComponents()),
                numbers(gray.toRGB(new float[] { 0.25f })),
                rgb.getName(),
                rgb.getNumberOfComponents(),
                numbers(rgb.getDefaultDecode(8)),
                numbers(rgb.toRGB(new float[] { 0.1f, 0.2f, 0.3f })),
                cmyk.getName(),
                cmyk.getNumberOfComponents(),
                numbers(cmyk.getInitialColor().getComponents()));

        PDCalGray calGray = new PDCalGray();
        calGray.setWhitePoint(new PDTristimulus(new float[] { 0.95f, 1f, 1.09f }));
        calGray.setBlackPoint(new PDTristimulus(new float[] { 0.01f, 0.02f, 0.03f }));
        calGray.setGamma(2.2f);
        PDCalRGB calRgb = new PDCalRGB();
        calRgb.setWhitePoint(new PDTristimulus(new float[] { 0.95f, 1f, 1.09f }));
        calRgb.setGamma(new PDGamma(floats(2.1f, 2.2f, 2.3f)));
        PDLab lab = new PDLab();
        lab.setWhitePoint(new PDTristimulus(new float[] { 0.95f, 1f, 1.09f }));
        lab.setARange(new PDRange(floats(-80, 90)));
        lab.setBRange(new PDRange(floats(-70, 75)));
        observe(
                "calibrated-color",
                "models",
                calGray.getName(),
                calGray.getGamma(),
                tristimulus(calGray.getWhitepoint()),
                tristimulus(calGray.getBlackPoint()),
                calRgb.getName(),
                numbers(calRgb.getGamma().getCOSArray().toFloatArray()),
                numbers(calRgb.getMatrix()),
                lab.getName(),
                numbers(lab.getDefaultDecode(8)),
                numbers(lab.getInitialColor().getComponents()));

        PDFunctionType2 tint = type2(
                new float[] { 0f, 0f, 0f },
                new float[] { 1f, 0.5f, 0.25f });
        PDSeparation separation = new PDSeparation();
        separation.setColorantName("SpotOrange");
        separation.setAlternateColorSpace(rgb);
        separation.setTintTransform(tint);
        PDDeviceN deviceN = new PDDeviceN();
        deviceN.setColorantNames(Arrays.asList("Cyan", "SpotOrange"));
        deviceN.setAlternateColorSpace(rgb);
        deviceN.setTintTransform(tint);
        observe(
                "special-color",
                "separation-and-devicen",
                separation.getName(),
                separation.getColorantName(),
                separation.getAlternateColorSpace().getName(),
                numbers(separation.toRGB(new float[] { 0.4f })),
                deviceN.getName(),
                String.join(",", deviceN.getColorantNames()),
                deviceN.getNumberOfComponents(),
                deviceN.getAlternateColorSpace().getName(),
                numbers(deviceN.toRGB(new float[] { 0.4f, 0.8f })));

        PDResources patternResources = new PDResources();
        PDTilingPattern tiling = createTilingPattern();
        COSName tilingName = patternResources.add(tiling);
        PDPattern patternSpace = new PDPattern(patternResources, rgb);
        PDColor patternColor =
                new PDColor(new float[] { 0.2f, 0.4f, 0.6f }, tilingName, patternSpace);
        observe(
                "pattern",
                "tiling-and-color",
                tiling.getPatternType(),
                tiling.getPaintType(),
                tiling.getTilingType(),
                tiling.getXStep(),
                tiling.getYStep(),
                rectangle(tiling.getBBox()),
                matrix(tiling.getMatrix()),
                patternSpace.getName(),
                patternSpace.getUnderlyingColorSpace().getName(),
                patternColor.isPattern(),
                patternColor.getPatternName().getName(),
                patternSpace.getPattern(patternColor).getPatternType());

        PDShadingType2 shading = createShading();
        PDShadingPattern shadingPattern = new PDShadingPattern();
        shadingPattern.setShading(shading);
        PDExtendedGraphicsState patternState = new PDExtendedGraphicsState();
        patternState.setBlendMode(BlendMode.SCREEN);
        shadingPattern.setExtendedGraphicsState(patternState);
        observe(
                "shading",
                "axial-and-pattern",
                shading.getShadingType(),
                shading.getColorSpace().getName(),
                numbers(shading.getCoords().toFloatArray()),
                numbers(shading.getDomain().toFloatArray()),
                bools(shading.getExtend()),
                numbers(shading.evalFunction(0.25f)),
                shading.getAntiAlias(),
                rectangle(shading.getBBox()),
                shadingPattern.getPatternType(),
                shadingPattern.getShading().getShadingType(),
                shadingPattern.getExtendedGraphicsState()
                        .getBlendMode().getCOSName().getName());

        BlendMode multiply = BlendMode.getInstance(COSName.MULTIPLY);
        COSArray modes = new COSArray();
        modes.add(COSName.getPDFName("UnsupportedBlend"));
        modes.add(COSName.SCREEN);
        BlendMode selected = BlendMode.getInstance(modes);
        float[] hueResult = new float[3];
        BlendMode.HUE.getBlendFunction().blend(
                new float[] { 0.2f, 0.7f, 0.4f },
                new float[] { 0.8f, 0.3f, 0.6f },
                hueResult);
        observe(
                "blend-mode",
                "selection-and-functions",
                multiply.getCOSName().getName(),
                multiply.isSeparableBlendMode(),
                multiply.getBlendChannelFunction().blendChannel(0.25f, 0.8f),
                selected.getCOSName().getName(),
                BlendMode.getInstance(COSName.getPDFName("NoSuchMode"))
                        .getCOSName().getName(),
                BlendMode.HUE.isSeparableBlendMode(),
                numbers(hueResult));

        try (PDDocument document = new PDDocument())
        {
            PDTransparencyGroup group = createTransparencyGroup(document);
            PDSoftMask softMask = createSoftMask(group);
            PDExtendedGraphicsState ext = createExtendedState(softMask);
            PDGraphicsState state = new PDGraphicsState(new PDRectangle(200, 100));
            state.setCurrentTransformationMatrix(
                    new Matrix(2, 0.5f, -0.25f, 3, 7, 11));
            ext.copyIntoGraphicsState(state);
            Rectangle2D clipping = state.getCurrentClippingPath().getBounds2D();
            observe(
                    "graphics-state",
                    "copy-and-clone",
                    state(state),
                    state.clone().getTextState().getKnockoutFlag());
            observe(
                    "clipping",
                    "initial-page-clip",
                    clipping.getX(),
                    clipping.getY(),
                    clipping.getWidth(),
                    clipping.getHeight());
            observe(
                    "extended-state",
                    "copy",
                    state.getLineWidth(),
                    state.getLineCap(),
                    state.getLineJoin(),
                    state.getMiterLimit(),
                    numbers(state.getLineDashPattern().getDashArray()),
                    state.getLineDashPattern().getPhase(),
                    state.getRenderingIntent().stringValue(),
                    state.isOverprint(),
                    state.isNonStrokingOverprint(),
                    state.getOverprintMode(),
                    state.getFlatness(),
                    state.getSmoothness(),
                    state.isStrokeAdjustment(),
                    state.getAlphaConstant(),
                    state.getNonStrokeAlphaConstant(),
                    state.isAlphaSource(),
                    state.getTextState().getKnockoutFlag(),
                    state.getBlendMode().getCOSName().getName(),
                    state.getSoftMask() != null);
            observe(
                    "transparency-group",
                    "attributes",
                    group.getGroup().isIsolated(),
                    group.getGroup().isKnockout(),
                    group.getGroup().getColorSpace().getName(),
                    rectangle(group.getBBox()));
            observe(
                    "soft-mask",
                    "model",
                    softMask.getSubType().getName(),
                    softMask.getGroup() != null,
                    numbers(softMask.getBackdropColor().toFloatArray()),
                    numbers(softMask.getTransferFunction().eval(new float[] { 0.25f })));
        }

        observe(
                "malformed-operand",
                "model-factories",
                fails(IOException.class, () -> PDColorSpace.create(new COSArray())),
                fails(IOException.class, () -> PDShading.create(new COSDictionary())),
                fails(
                        IOException.class,
                        () -> PDAbstractPattern.create(new COSDictionary(), null)),
                PDSoftMask.create(COSInteger.ZERO) == null);
    }

    private static void writeRepresentative(Path path) throws Exception
    {
        try (PDDocument document = new PDDocument())
        {
            PDPage page = new PDPage(new PDRectangle(300, 300));
            PDResources resources = new PDResources();
            page.setResources(resources);
            document.addPage(page);

            resources.put(COSName.getPDFName("Cal1"), createCalRgb());
            PDFunctionType2 tint = type2(
                    new float[] { 0f, 0f, 0f },
                    new float[] { 1f, 0.5f, 0.25f });
            PDSeparation separation = new PDSeparation();
            separation.setColorantName("SpotOrange");
            separation.setAlternateColorSpace(PDDeviceRGB.INSTANCE);
            separation.setTintTransform(tint);
            resources.put(COSName.getPDFName("Sep1"), separation);
            PDDeviceN deviceN = new PDDeviceN();
            deviceN.setColorantNames(Arrays.asList("Cyan", "SpotOrange"));
            deviceN.setAlternateColorSpace(PDDeviceRGB.INSTANCE);
            deviceN.setTintTransform(tint);
            resources.put(COSName.getPDFName("DN1"), deviceN);
            resources.put(COSName.getPDFName("P1"), createTilingPattern());
            resources.put(COSName.getPDFName("Sh1"), createShading());
            PDTransparencyGroup group = createTransparencyGroup(document);
            resources.put(
                    COSName.getPDFName("GS1"),
                    createExtendedState(createSoftMask(group)));

            try (PDPageContentStream content = new PDPageContentStream(
                    document,
                    page,
                    PDPageContentStream.AppendMode.OVERWRITE,
                    false))
            {
                content.appendRawCommands(SYNTHETIC_CONTENT);
            }
            document.save(path.toFile(), CompressParameters.NO_COMPRESSION);
        }
    }

    private static final String SYNTHETIC_CONTENT = String.join(
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

    private static PDCalRGB createCalRgb()
    {
        PDCalRGB cal = new PDCalRGB();
        cal.setWhitePoint(new PDTristimulus(new float[] { 0.95f, 1f, 1.09f }));
        cal.setGamma(new PDGamma(floats(2.1f, 2.2f, 2.3f)));
        return cal;
    }

    private static PDTilingPattern createTilingPattern()
    {
        PDTilingPattern pattern = new PDTilingPattern();
        pattern.setPaintType(PDTilingPattern.PAINT_UNCOLORED);
        pattern.setTilingType(PDTilingPattern.TILING_CONSTANT_SPACING);
        pattern.setXStep(12.5f);
        pattern.setYStep(9.5f);
        pattern.setBBox(new PDRectangle(0, 0, 10, 8));
        pattern.setMatrix(new AffineTransform(1, 0.25, -0.5, 1, 3, 4));
        return pattern;
    }

    private static PDShadingType2 createShading()
    {
        COSDictionary dictionary = new COSDictionary();
        dictionary.setInt(COSName.SHADING_TYPE, PDShading.SHADING_TYPE2);
        PDShadingType2 shading = new PDShadingType2(dictionary);
        shading.setShadingType(PDShading.SHADING_TYPE2);
        shading.setColorSpace(PDDeviceRGB.INSTANCE);
        shading.setCoords(floats(0, 0, 100, 50));
        shading.setDomain(floats(0, 1));
        shading.setExtend(bools(true, false));
        shading.setFunction(type2(
                new float[] { 0f, 0.2f, 0.4f },
                new float[] { 1f, 0.8f, 0.6f }));
        shading.setBackground(floats(0.1f, 0.2f, 0.3f));
        shading.setBBox(new PDRectangle(0, 0, 100, 50));
        shading.setAntiAlias(true);
        return shading;
    }

    private static PDTransparencyGroup createTransparencyGroup(PDDocument document)
    {
        PDTransparencyGroup group = new PDTransparencyGroup(document);
        group.setBBox(new PDRectangle(0, 0, 40, 30));
        group.setResources(new PDResources());
        PDTransparencyGroupAttributes attributes =
                new PDTransparencyGroupAttributes();
        attributes.getCOSObject().setBoolean(COSName.I, true);
        attributes.getCOSObject().setBoolean(COSName.K, true);
        attributes.getCOSObject().setItem(
                COSName.CS,
                PDDeviceRGB.INSTANCE.getCOSObject());
        group.setGroup(attributes);
        return group;
    }

    private static PDSoftMask createSoftMask(PDTransparencyGroup group)
    {
        COSDictionary dictionary = new COSDictionary();
        dictionary.setItem(COSName.S, COSName.LUMINOSITY);
        dictionary.setItem(COSName.G, group);
        dictionary.setItem(COSName.BC, floats(0.1f, 0.2f, 0.3f));
        dictionary.setItem(
                COSName.TR,
                type2(new float[] { 0f }, new float[] { 1f }));
        return new PDSoftMask(dictionary);
    }

    private static PDExtendedGraphicsState createExtendedState(
            PDSoftMask softMask)
    {
        PDExtendedGraphicsState state = new PDExtendedGraphicsState();
        state.setLineWidth(4.25f);
        state.setLineCapStyle(2);
        state.setLineJoinStyle(1);
        state.setMiterLimit(8f);
        state.setLineDashPattern(new PDLineDashPattern(floats(3, 2), -1));
        state.setRenderingIntent("AbsoluteColorimetric");
        state.setStrokingOverprintControl(true);
        state.setNonStrokingOverprintControl(false);
        state.setOverprintMode(1);
        state.setFlatnessTolerance(0.5f);
        state.setSmoothnessTolerance(0.25f);
        state.setAutomaticStrokeAdjustment(true);
        state.setStrokingAlphaConstant(0.7f);
        state.setNonStrokingAlphaConstant(0.3f);
        state.setAlphaSourceFlag(true);
        state.setTextKnockoutFlag(false);
        state.setBlendMode(BlendMode.MULTIPLY);
        state.getCOSObject().setItem(COSName.SMASK, softMask);
        return state;
    }

    private static PDFunctionType2 type2(float[] c0, float[] c1)
    {
        COSDictionary dictionary = new COSDictionary();
        dictionary.setInt(COSName.FUNCTION_TYPE, 2);
        dictionary.setItem(COSName.DOMAIN, floats(0, 1));
        dictionary.setItem(COSName.C0, floats(c0));
        dictionary.setItem(COSName.C1, floats(c1));
        dictionary.setFloat(COSName.N, 1);
        COSArray range = new COSArray();
        for (int index = 0; index < Math.min(c0.length, c1.length); index++)
        {
            range.add(COSFloat.ZERO);
            range.add(COSFloat.ONE);
        }
        dictionary.setItem(COSName.RANGE, range);
        return new PDFunctionType2(dictionary);
    }

    private static void processPdf(
            Path path,
            String prefix,
            String familyOverride) throws Exception
    {
        if (!Files.isRegularFile(path))
        {
            throw new FileNotFoundException(
                    "Required graphics fixture is missing: " + path);
        }
        try (PDDocument document = Loader.loadPDF(path.toFile()))
        {
            PDPage page = document.getPage(0);
            TraceEngine engine = new TraceEngine(page, prefix, familyOverride);
            engine.processPage(page);
        }
    }

    private static COSArray floats(float... values)
    {
        COSArray array = new COSArray();
        array.setFloatArray(values);
        return array;
    }

    private static COSArray bools(boolean... values)
    {
        COSArray array = new COSArray();
        for (boolean value : values)
        {
            array.add(value ? COSBoolean.TRUE : COSBoolean.FALSE);
        }
        return array;
    }

    private static String bools(COSArray values)
    {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++)
        {
            result.add(value(((COSBoolean) values.getObject(index)).getValue()));
        }
        return String.join(",", result);
    }

    private static String state(PDGraphicsState state)
    {
        PDTextState text = state.getTextState();
        return String.join(
                ";",
                "stack",
                matrix(state.getCurrentTransformationMatrix()),
                "line=" + String.join(
                        ",",
                        number(state.getLineWidth()),
                        Integer.toString(state.getLineCap()),
                        Integer.toString(state.getLineJoin()),
                        number(state.getMiterLimit()),
                        numbers(state.getLineDashPattern().getDashArray()),
                        Integer.toString(state.getLineDashPattern().getPhase())),
                "stroke=" + color(state.getStrokingColor()),
                "fill=" + color(state.getNonStrokingColor()),
                "text=" + String.join(
                        ",",
                        number(text.getCharacterSpacing()),
                        number(text.getWordSpacing()),
                        number(text.getHorizontalScaling()),
                        number(text.getLeading()),
                        number(text.getFontSize()),
                        Integer.toString(text.getRenderingMode().intValue()),
                        number(text.getRise()),
                        value(text.getKnockoutFlag())),
                "tm=" + matrix(state.getTextMatrix()),
                "tlm=" + matrix(state.getTextLineMatrix()),
                "blend=" + state.getBlendMode().getCOSName().getName(),
                "alpha=" + number(state.getAlphaConstant()) + "," +
                        number(state.getNonStrokeAlphaConstant()));
    }

    private static String color(PDColor color)
    {
        if (color == null)
        {
            return "null";
        }
        PDColorSpace colorSpace = color.getColorSpace();
        return (colorSpace == null ? "null" : colorSpace.getName()) + ":" +
                numbers(color.getComponents()) + ":" +
                (color.getPatternName() == null
                        ? "null"
                        : color.getPatternName().getName());
    }

    private static String matrix(Matrix matrix)
    {
        if (matrix == null)
        {
            return "null";
        }
        return String.join(
                ",",
                number(matrix.getScaleX()),
                number(matrix.getShearY()),
                number(matrix.getShearX()),
                number(matrix.getScaleY()),
                number(matrix.getTranslateX()),
                number(matrix.getTranslateY()));
    }

    private static String rectangle(PDRectangle rectangle)
    {
        if (rectangle == null)
        {
            return "null";
        }
        return String.join(
                ",",
                number(rectangle.getLowerLeftX()),
                number(rectangle.getLowerLeftY()),
                number(rectangle.getWidth()),
                number(rectangle.getHeight()));
    }

    private static String tristimulus(PDTristimulus value)
    {
        return String.join(
                ",",
                number(value.getX()),
                number(value.getY()),
                number(value.getZ()));
    }

    private static String numbers(float[] values)
    {
        List<String> result = new ArrayList<>(values.length);
        for (float value : values)
        {
            result.add(number(value));
        }
        return String.join(",", result);
    }

    private static String number(double value)
    {
        String text = String.format(Locale.ROOT, "%.6f", value);
        while (text.contains(".") && text.endsWith("0"))
        {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith("."))
        {
            text = text.substring(0, text.length() - 1);
        }
        return text.equals("-0") ? "0" : text;
    }

    private static String value(Object value)
    {
        if (value == null)
        {
            return "null";
        }
        if (value instanceof Boolean)
        {
            return (Boolean) value ? "true" : "false";
        }
        if (value instanceof Float || value instanceof Double)
        {
            return number(((Number) value).doubleValue());
        }
        return String.valueOf(value);
    }

    private static void observe(String family, String id, Object... values)
    {
        OBSERVATIONS.add(
                family + "\t" + id + "\t" +
                        Arrays.stream(values)
                                .map(PdfBoxGraphicsOracle::value)
                                .collect(Collectors.joining("|")));
    }

    private static boolean fails(
            Class<? extends Throwable> type,
            ThrowingAction action)
    {
        try
        {
            action.run();
            return false;
        }
        catch (Throwable error)
        {
            return type.isInstance(error);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction
    {
        void run() throws Exception;
    }

    private static final class TraceEngine extends PDFGraphicsStreamEngine
    {
        private static final Set<String> MATRIX_OPERATORS =
                new HashSet<>(Arrays.asList("cm", "Tm", "Td", "TD", "T*"));
        private static final Set<String> GRAPHICS_OPERATORS =
                new HashSet<>(Arrays.asList("w", "J", "j", "M", "d", "ri", "i", "gs"));
        private static final Set<String> COLOR_OPERATORS =
                new HashSet<>(Arrays.asList(
                        "G", "g", "RG", "rg", "CS", "cs", "SC", "sc", "SCN", "scn",
                        "K", "k"));
        private static final Set<String> TEXT_OPERATORS =
                new HashSet<>(Arrays.asList(
                        "BT", "ET", "Tc", "Tw", "Tz", "TL", "Tf", "Tr", "Ts",
                        "Td", "TD", "T*", "Tm", "Tj", "TJ", "'", "\""));
        private static final Set<String> PATH_OPERATORS =
                new HashSet<>(Arrays.asList("m", "l", "c", "v", "y", "h", "re"));
        private static final Set<String> PAINT_OPERATORS =
                new HashSet<>(Arrays.asList(
                        "S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n"));

        private final String prefix;
        private final String familyOverride;
        private int eventIndex;
        private Point2D currentPoint;

        private TraceEngine(PDPage page, String prefix, String familyOverride)
        {
            super(page);
            this.prefix = prefix;
            this.familyOverride = familyOverride;
        }

        @Override
        protected void processOperator(
                Operator operator,
                List<COSBase> operands) throws IOException
        {
            super.processOperator(operator, operands);
            String name = operator.getName();
            emit(
                    "operator-dispatch",
                    "operator",
                    name,
                    operands.size(),
                    getGraphicsStackSize(),
                    state(getGraphicsState()));
            String family = classify(name);
            if (family != null)
            {
                emit(
                        family,
                        "state",
                        name,
                        getGraphicsStackSize(),
                        state(getGraphicsState()));
            }
        }

        @Override
        protected void unsupportedOperator(
                Operator operator,
                List<COSBase> operands) throws IOException
        {
            emit(
                    "compatibility",
                    "unsupported",
                    operator.getName(),
                    operands.size(),
                    getGraphicsStackSize());
            super.unsupportedOperator(operator, operands);
        }

        @Override
        protected void operatorException(
                Operator operator,
                List<COSBase> operands,
                IOException exception) throws IOException
        {
            emit(
                    "malformed-operand",
                    "operator-error",
                    operator.getName(),
                    operands.size(),
                    exception.getClass().getSimpleName(),
                    getGraphicsStackSize());
            super.operatorException(operator, operands, exception);
        }

        @Override
        public void beginMarkedContentSequence(COSName tag, COSDictionary properties)
        {
            emit(
                    "marked-content",
                    "begin",
                    tag.getName(),
                    properties == null ? -1 : properties.getInt(COSName.MCID, -1));
        }

        @Override
        public void endMarkedContentSequence()
        {
            emit("marked-content", "end");
        }

        @Override
        public void appendRectangle(
                Point2D p0,
                Point2D p1,
                Point2D p2,
                Point2D p3)
        {
            currentPoint = p0;
            emit(
                    "path",
                    "rectangle",
                    point(p0),
                    point(p1),
                    point(p2),
                    point(p3));
        }

        @Override
        public void drawImage(PDImage image)
        {
            emit(
                    "operator-dispatch",
                    "image",
                    image.getWidth(),
                    image.getHeight(),
                    image.getBitsPerComponent());
        }

        @Override
        public void clip(int windingRule)
        {
            emit("clipping", "clip", windingRule, point(currentPoint));
        }

        @Override
        public void moveTo(float x, float y)
        {
            currentPoint = new Point2D.Float(x, y);
            emit("path", "move", x, y);
        }

        @Override
        public void lineTo(float x, float y)
        {
            currentPoint = new Point2D.Float(x, y);
            emit("path", "line", x, y);
        }

        @Override
        public void curveTo(
                float x1,
                float y1,
                float x2,
                float y2,
                float x3,
                float y3)
        {
            currentPoint = new Point2D.Float(x3, y3);
            emit("path", "curve", x1, y1, x2, y2, x3, y3);
        }

        @Override
        public Point2D getCurrentPoint()
        {
            return currentPoint == null ? new Point2D.Float(0, 0) : currentPoint;
        }

        @Override
        public void closePath()
        {
            emit("path", "close", point(currentPoint));
        }

        @Override
        public void endPath()
        {
            emit("stroke-fill", "end");
            currentPoint = null;
        }

        @Override
        public void strokePath()
        {
            emit("stroke-fill", "stroke", state(getGraphicsState()));
            currentPoint = null;
        }

        @Override
        public void fillPath(int windingRule)
        {
            emit(
                    "stroke-fill",
                    "fill",
                    windingRule,
                    state(getGraphicsState()));
            currentPoint = null;
        }

        @Override
        public void fillAndStrokePath(int windingRule)
        {
            emit(
                    "stroke-fill",
                    "fill-stroke",
                    windingRule,
                    state(getGraphicsState()));
            currentPoint = null;
        }

        @Override
        public void shadingFill(COSName shadingName) throws IOException
        {
            PDShading shading = getResources().getShading(shadingName);
            emit(
                    "shading",
                    "operator",
                    shadingName.getName(),
                    shading == null ? -1 : shading.getShadingType());
        }

        private void emit(String family, String eventName, Object... values)
        {
            eventIndex++;
            String actualFamily =
                    familyOverride == null ? family : familyOverride;
            Object[] allValues = new Object[values.length + 1];
            allValues[0] = eventName;
            System.arraycopy(values, 0, allValues, 1, values.length);
            observe(
                    actualFamily,
                    prefix + "-" + String.format(Locale.ROOT, "%06d", eventIndex),
                    allValues);
        }

        private static String classify(String name)
        {
            if (name.equals("q") || name.equals("Q"))
            {
                return "graphics-stack";
            }
            if (MATRIX_OPERATORS.contains(name))
            {
                return "matrix";
            }
            if (GRAPHICS_OPERATORS.contains(name))
            {
                return "graphics-state";
            }
            if (COLOR_OPERATORS.contains(name))
            {
                return "color-operator";
            }
            if (TEXT_OPERATORS.contains(name))
            {
                return "text-state";
            }
            if (PATH_OPERATORS.contains(name))
            {
                return "path";
            }
            if (PAINT_OPERATORS.contains(name))
            {
                return "stroke-fill";
            }
            if (name.equals("W") || name.equals("W*"))
            {
                return "clipping";
            }
            if (Arrays.asList("BMC", "BDC", "EMC", "MP", "DP").contains(name))
            {
                return "marked-content";
            }
            if (name.equals("BX") || name.equals("EX"))
            {
                return "compatibility";
            }
            if (name.equals("sh"))
            {
                return "shading";
            }
            return null;
        }

        private static String point(Point2D point)
        {
            return point == null
                    ? "null"
                    : number(point.getX()) + "," + number(point.getY());
        }
    }
}
