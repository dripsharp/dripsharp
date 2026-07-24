import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.fontbox.afm.AFMParser;
import org.apache.fontbox.afm.CharMetric;
import org.apache.fontbox.afm.FontMetrics;
import org.apache.fontbox.cff.CFFExpertCharset;
import org.apache.fontbox.cff.CFFExpertEncoding;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.cff.CFFStandardEncoding;
import org.apache.fontbox.cff.CFFType1Font;
import org.apache.fontbox.cff.Type1FontUtil;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.fontbox.cmap.CodespaceRange;
import org.apache.fontbox.encoding.MacRomanEncoding;
import org.apache.fontbox.encoding.StandardEncoding;
import org.apache.fontbox.pfb.PfbParser;
import org.apache.fontbox.type1.Type1Font;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;

public final class FontBoxUpstreamOracle {
  private interface ThrowingAction {
    void run() throws Exception;
  }

  private static final List<String> observations = new ArrayList<String>();

  private FontBoxUpstreamOracle() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException(
          "Expected output trace, FontBox test resources, and downloaded font directory.");
    }

    File resources = new File(args[1]);
    File fonts = new File(args[2]);
    observeEncodings();
    observeCff(new File(fonts, "SourceSansProBold.otf"));
    observeAfm(new File(resources, "afm"));
    observeCMaps(new File(resources, "cmap"));
    observePfbAndType1(
        new File(fonts, "OpenSans-Regular.pfb"),
        new File(fonts, "DejaVuSerifCondensed.pfb"));
    observeFailures(new File(resources, "afm"));

    Files.write(new File(args[0]).toPath(), observations, StandardCharsets.UTF_8);
    System.out.println(
        "Pinned PDFBox 3.0.8 FontBox oracle passed: "
            + observations.size()
            + " observations.");
  }

  private static void observeEncodings() {
    StandardEncoding standard = StandardEncoding.INSTANCE;
    observe(
        "encoding",
        "standard",
        join(
            standard.getName(0),
            standard.getName(32),
            standard.getName(112),
            standard.getName(172),
            standard.getCode("space"),
            standard.getCode("p"),
            standard.getCode("guilsinglleft"),
            standard.getCode("missing")));
    observe(
        "encoding",
        "standard-map-view",
        join(
            standard.getCodeToNameMap().size(),
            failureKind(() -> standard.getCodeToNameMap().put(32, "changed"))));

    MacRomanEncoding mac = MacRomanEncoding.INSTANCE;
    observe(
        "encoding",
        "mac-roman",
        join(
            mac.getName(0),
            mac.getName(32),
            mac.getName(112),
            mac.getName(167),
            mac.getCode("germandbls")));

    observe(
        "encoding",
        "cff-built-in",
        join(
            CFFStandardEncoding.getInstance().getName(251),
            CFFStandardEncoding.getInstance().getCode("germandbls"),
            CFFExpertEncoding.getInstance().getName(112),
            CFFExpertEncoding.getInstance().getCode("Ucircumflexsmall"),
            CFFExpertCharset.getInstance().getSIDForGID(32),
            CFFExpertCharset.getInstance().getNameForGID(134)));
  }

  private static void observeCff(File sourceSans) throws Exception {
    CFFType1Font font;
    try (RandomAccessReadBufferedFile input = new RandomAccessReadBufferedFile(sourceSans)) {
      List<CFFFont> parsed = new CFFParser().parse(input);
      font = (CFFType1Font) parsed.get(0);
    }

    observe(
        "cff",
        "font-model",
        join(
            font.getName(),
            font.getFontBBox().getLowerLeftX(),
            font.getFontBBox().getLowerLeftY(),
            font.getFontBBox().getUpperRightX(),
            font.getFontBBox().getUpperRightY(),
            font.getNumCharStrings(),
            font.getCharStringBytes().size()));
    observe(
        "cff",
        "charset-encoding",
        join(
            font.getCharset().isCIDFont(),
            font.getCharset().getNameForGID(1),
            font.getCharset().getSIDForGID(300),
            font.getCharset().getSID("infinity"),
            font.getEncoding().getClass().getSimpleName()));
    observe(
        "cff",
        "font-matrix-and-private",
        join(
            numberList(font.getFontMatrix()),
            numberList((List<?>) font.getPrivateDict().get("BlueValues")),
            numberList((List<?>) font.getPrivateDict().get("StemSnapV"))));

    byte[] sample = new byte[] {0, 1, 2, -1, 127, -128};
    observe(
        "cff",
        "type1-font-util",
        join(
            Type1FontUtil.hexEncode(sample),
            unsigned(Type1FontUtil.hexDecode(Type1FontUtil.hexEncode(sample))),
            unsigned(Type1FontUtil.eexecDecrypt(Type1FontUtil.eexecEncrypt(sample))),
            unsigned(Type1FontUtil.charstringDecrypt(Type1FontUtil.charstringEncrypt(sample, 4), 4))));
  }

  private static void observeAfm(File afmDirectory) throws Exception {
    FontMetrics metrics;
    try (FileInputStream input = new FileInputStream(new File(afmDirectory, "Helvetica.afm"))) {
      metrics = new AFMParser(input).parse();
    }
    CharMetric ring = null;
    for (CharMetric metric : metrics.getCharMetrics()) {
      if ("ring".equals(metric.getName())) {
        ring = metric;
        break;
      }
    }
    observe(
        "afm",
        "helvetica-model",
        join(
            metrics.getAFMVersion(),
            metrics.getFontName(),
            metrics.getWeight(),
            metrics.getFontBBox().getLowerLeftX(),
            metrics.getFontBBox().getUpperRightY(),
            metrics.getComments().size(),
            metrics.getCharMetrics().size(),
            metrics.getKernPairs().size()));
    observe(
        "afm",
        "char-metric",
        join(
            ring.getCharacterCode(),
            ring.getWx(),
            ring.getBoundingBox().getLowerLeftX(),
            ring.getBoundingBox().getUpperRightY()));

    try (FileInputStream input = new FileInputStream(new File(afmDirectory, "Helvetica.afm"))) {
      FontMetrics reduced = new AFMParser(input).parse(true);
    observe(
        "afm",
        "reduced-dataset",
        join(reduced.getCharMetrics().size(), reduced.getKernPairs().size()));

    FontMetrics nullableState = new FontMetrics();
    boolean withoutVector = nullableState.getIsFixedV();
    nullableState.setVVector(new float[] {1, 2});
    boolean inferredFromVector = nullableState.getIsFixedV();
    nullableState.setIsFixedV(false);
    observe(
        "afm",
        "nullable-fixed-v",
        join(withoutVector, inferredFromVector, nullableState.getIsFixedV()));
    }
  }

  private static void observeCMaps(File cmapDirectory) throws Exception {
    CMap cmap;
    try (RandomAccessReadBufferedFile input =
        new RandomAccessReadBufferedFile(new File(cmapDirectory, "CMapTest"))) {
      cmap = new CMapParser().parse(input);
    }
    observe(
        "cmap",
        "fixture-mappings",
        join(
            cmap.toUnicode(bytes(0, 1)),
            cmap.toUnicode(bytes(1, 32)),
            cmap.toUnicode(bytes(0, 10)),
            cmap.toCID(bytes(0, 65)),
            cmap.toCID(bytes(1, 24)),
            cmap.toCID(bytes(2, 8))));

    CMap identity = new CMapParser().parsePredefined("Identity-H");
    CMap unicode = new CMapParser().parsePredefined("Adobe-GB1-UCS2");
    observe(
        "cmap",
        "embedded-resources",
        join(
            identity.toCID(bytes(0, 65)),
            identity.toCID(bytes(48, 57)),
            unicode.toUnicode(bytes(0, 17)),
            unicode.getName(),
            unicode.hasCIDMappings(),
            unicode.hasUnicodeMappings()));

    try (RandomAccessReadBufferedFile input =
        new RandomAccessReadBufferedFile(new File(cmapDirectory, "CMapMalformedbfrange2"))) {
      CMap lenient = new CMapParser().parse(input);
      observe(
          "cmap",
          "lenient-malformed-range",
          join(lenient.toUnicode(bytes(0, 1)), present(lenient.toUnicode(bytes(2, 241)))));
    }
    try (RandomAccessReadBufferedFile input =
        new RandomAccessReadBufferedFile(new File(cmapDirectory, "CMapMalformedbfrange2"))) {
      CMap strict = new CMapParser(true).parse(input);
      observe(
          "cmap",
          "strict-malformed-range",
          join(
              present(strict.toUnicode(bytes(2, 240))),
              present(strict.toUnicode(bytes(2, 241)))));
    }

    CodespaceRange range = new CodespaceRange(bytes(129, 64), bytes(159, 252));
    observe(
        "cmap",
        "codespace",
        join(
            range.getCodeLength(),
            range.matches(bytes(129, 64)),
            range.matches(bytes(144, 64)),
            range.matches(bytes(130, 32)),
            range.matches(bytes(160, 64))));

    byte[] directZeroSource =
        ("begincmap\n"
                + "1 begincodespacerange\n<01> <01>\nendcodespacerange\n"
                + "1 begincidrange\n<01> <01> 5\nendcidrange\n"
                + "1 begincidchar\n<01> 0\nendcidchar\n"
                + "endcmap\n")
            .getBytes(StandardCharsets.US_ASCII);
    CMap directZero = new CMapParser().parse(new RandomAccessReadBuffer(directZeroSource));
    observe("cmap", "direct-zero-precedes-range", join(directZero.toCID(bytes(1))));
  }

  private static void observePfbAndType1(File openSans, File dejavu) throws Exception {
    PfbParser pfb;
    try (FileInputStream input = new FileInputStream(openSans)) {
      pfb = new PfbParser(input);
    }
    observe(
        "pfb",
        "segments",
        join(
            pfb.getLengths()[0],
            pfb.getLengths()[1],
            pfb.getLengths()[2],
            pfb.getSegment1().length,
            pfb.getSegment2().length,
            pfb.size()));

    Type1Font openSansFont;
    try (FileInputStream input = new FileInputStream(openSans)) {
      openSansFont = Type1Font.createWithPFB(input);
    }
    observe(
        "type1",
        "open-sans",
        join(
            openSansFont.getVersion(),
            openSansFont.getFontName(),
            openSansFont.getFullName(),
            openSansFont.getFamilyName(),
            openSansFont.getWeight(),
            openSansFont.getEncoding().getClass().getSimpleName(),
            openSansFont.getASCIISegment().length,
            openSansFont.getBinarySegment().length,
            openSansFont.getCharStringsDict().size(),
            openSansFont.hasGlyph("A"),
            openSansFont.getPath("A").getBounds2D().isEmpty()));

    Type1Font dejavuFont;
    try (FileInputStream input = new FileInputStream(dejavu)) {
      dejavuFont = Type1Font.createWithPFB(input);
    }
    observe(
        "type1",
        "multiple-binary-segments",
        join(
            dejavuFont.getVersion(),
            dejavuFont.getFontName(),
            dejavuFont.getASCIISegment().length,
            dejavuFont.getBinarySegment().length,
            dejavuFont.getCharStringsDict().size()));
  }

  private static void observeFailures(File afmDirectory) throws Exception {
    observe(
        "failure",
        "empty-pfb",
        failureKind(() -> Type1Font.createWithPFB(new byte[0])));
    byte[] negativeRecord =
        bytes(128, 1, 1, 0, 0, 255, 255, 255, 255, 255, 255, 255, 39, 5, 248, 255, 210, 64);
    observe(
        "failure",
        "negative-pfb-record",
        failureKind(() -> new PfbParser(negativeRecord)));
    observe(
        "failure",
        "odd-hex",
        failureKind(() -> Type1FontUtil.hexDecode("123")));
    observe(
        "failure",
        "missing-cmap-resource",
        failureKind(() -> new CMapParser().parsePredefined("Missing-CMap")));
    observe(
        "failure",
        "bad-codespace",
        failureKind(() -> new CodespaceRange(bytes(1), bytes(1, 32))));
    observe(
        "failure",
        "malformed-cff",
        failureKind(
            () -> new CFFParser().parse(new RandomAccessReadBuffer(bytes(1, 0, 4, 4)))));
    observe(
        "failure",
        "malformed-afm-start",
        failureKind(
            () ->
                new AFMParser(
                        new ByteArrayInputStream("huhu".getBytes(StandardCharsets.US_ASCII)))
                    .parse()));
    observe(
        "failure",
        "malformed-afm-number",
        failureKind(
            () -> {
              try (FileInputStream input =
                  new FileInputStream(new File(afmDirectory, "MalformedFloat.afm"))) {
                new AFMParser(input).parse();
              }
            }));
  }

  private static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];
    for (int index = 0; index < values.length; index++) {
      result[index] = (byte) values[index];
    }
    return result;
  }

  private static String unsigned(byte[] values) {
    StringBuilder result = new StringBuilder();
    for (int index = 0; index < values.length; index++) {
      if (index > 0) {
        result.append(',');
      }
      result.append(values[index] & 0xff);
    }
    return result.toString();
  }

  private static String numberList(List<?> values) {
    StringBuilder result = new StringBuilder();
    for (int index = 0; index < values.size(); index++) {
      if (index > 0) {
        result.append(',');
      }
      result.append(value(values.get(index)));
    }
    return result.toString();
  }

  private static String present(String value) {
    return String.valueOf(value != null);
  }

  private static String join(Object... values) {
    StringBuilder result = new StringBuilder();
    for (int index = 0; index < values.length; index++) {
      if (index > 0) {
        result.append('|');
      }
      result.append(value(values[index]).toLowerCase(Locale.ROOT));
    }
    return result.toString();
  }

  private static String value(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Number) {
      return new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
    }
    return String.valueOf(value);
  }

  private static String failureKind(ThrowingAction action) {
    try {
      action.run();
      return "none";
    } catch (UnsupportedOperationException error) {
      return "unsupported";
    } catch (IllegalArgumentException error) {
      return "argument";
    } catch (IllegalStateException error) {
      return "state";
    } catch (IOException error) {
      return "io";
    } catch (Exception error) {
      return "other";
    }
  }

  private static void observe(String family, String id, String value) {
    observations.add(family + "\t" + id + "\t" + value);
  }
}
