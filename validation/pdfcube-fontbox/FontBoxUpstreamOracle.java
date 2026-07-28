import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

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
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.FontHeaders;
import org.apache.fontbox.ttf.GlyfCompositeDescript;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.GlyphSubstitutionTable;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.fontbox.ttf.PostScriptTable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TTFSubsetter;
import org.apache.fontbox.ttf.TTFTable;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.fontbox.ttf.gsub.GsubWorker;
import org.apache.fontbox.ttf.gsub.GsubWorkerFactory;
import org.apache.fontbox.ttf.model.GsubData;
import org.apache.fontbox.type1.Type1Font;
import org.apache.fontbox.util.autodetect.FontFileFinder;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;

public final class FontBoxUpstreamOracle {
  private interface ThrowingAction {
    void run() throws Exception;
  }

  private static final List<String> observations = new ArrayList<String>();

  static {
    observations.add("DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1");
  }

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
    observeTrueType(new File(resources, "ttf"));
    observeOpenType(new File(resources, "ttf"), fonts);
    observeGsub(new File(resources, "ttf"));
    observeCollection(new File(resources, "ttf"));
    observeSubsetting(new File(resources, "ttf"));
    observeDiscovery(new File(resources, "ttf"), fonts);
    observeFailures(new File(resources, "afm"));

    Files.write(new File(args[0]).toPath(), observations, StandardCharsets.UTF_8);
    System.out.println(
        "Pinned reviewed PDFBox baseline FontBox oracle passed: "
            + (observations.size() - 1)
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

  private static void observeTrueType(File ttfDirectory) throws Exception {
    File liberation = new File(ttfDirectory, "LiberationSans-Regular.ttf");
    RandomAccessRead input = new RandomAccessReadBufferedFile(liberation);
    TrueTypeFont font = new TTFParser().parse(input);
    try {
      List<String> tags = new ArrayList<String>();
      boolean initialized = true;
      for (TTFTable table : font.getTables()) {
        tags.add(table.getTag());
        initialized &= table.getInitialized();
      }
      Collections.sort(tags);
      observe(
          "truetype",
          "directory-and-lifecycle",
          join(
              font.getVersion(),
              font.getOriginalDataSize(),
              input.isClosed(),
              tags.size(),
              initialized,
              String.join(",", tags)));

      observe(
          "tables",
          "headers-names-and-os2",
          join(
              font.getName(),
              font.getNaming().getFontFamily(),
              font.getNaming().getFontSubFamily(),
              font.getNaming().getNameRecords().size(),
              font.getHeader().getCreated().toInstant(),
              font.getHeader().getUnitsPerEm(),
              font.getMaximumProfile().getNumGlyphs(),
              font.getOS2Windows().getWeightClass(),
              font.getOS2Windows().getAchVendId()));

      CmapLookup cmap = font.getUnicodeCmapLookup();
      int a = cmap.getGlyphId('A');
      int trademark = cmap.getGlyphId(0x2122);
      int euro = cmap.getGlyphId(0x20ac);
      observe(
          "tables",
          "cmap-metrics-post",
          join(
              a,
              trademark,
              euro,
              cmap.getCharCodes(trademark),
              font.getAdvanceWidth(a),
              font.getHorizontalMetrics().getLeftSideBearing(a),
              font.getPostScript().getGlyphNames()[trademark],
              font.getPostScript().getGlyphNames()[euro]));

      GlyphData glyph = font.getGlyph().getGlyph(131);
      GlyfCompositeDescript description = (GlyfCompositeDescript) glyph.getDescription();
      observe(
          "tables",
          "composite-glyph",
          join(
              glyph.getNumberOfContours(),
              glyph.getXMinimum(),
              glyph.getYMinimum(),
              glyph.getXMaximum(),
              glyph.getYMaximum(),
              description.isComposite(),
              description.getComponentCount(),
              description.getComponents().get(0).getGlyphIndex(),
              description.getComponents().get(1).getGlyphIndex(),
              failureKind(() -> description.getComponents().remove(0))));

      try (java.io.InputStream original = font.getOriginalData()) {
        byte[] signature = new byte[4];
        int signatureLength = original.read(signature);
        observe(
            "lifecycle",
            "owned-copy-after-source-close",
            join(
                input.isClosed(),
                signatureLength == signature.length ? unsigned(signature) : "short",
                font.getOriginalDataSize()));
      }

      RandomAccessRead headerInput = new RandomAccessReadBufferedFile(liberation);
      FontHeaders headers = new TTFParser().parseTableHeaders(headerInput);
      observe(
          "lifecycle",
          "selective-header-scan",
          join(
              headerInput.isClosed(),
              headers.getError(),
              headers.getName(),
              headers.getFontFamily(),
              headers.getFontSubFamily(),
              headers.getHeaderMacStyle(),
              headers.getOS2Windows().getWeightClass()));
    } finally {
      font.close();
      font.close();
    }
  }

  private static void observeOpenType(File ttfDirectory, File fonts) throws Exception {
    OpenTypeFont liberation =
        new OTFParser()
            .parse(
                new RandomAccessReadBufferedFile(
                    new File(ttfDirectory, "LiberationSans-Regular.ttf")));
    try {
      GlyphSubstitutionTable gsub = liberation.getGsub();
      observe(
          "opentype",
          "truetype-outlines-and-layout",
          join(
              liberation.isPostScript(),
              liberation.isSupportedOTF(),
              liberation.hasLayoutTables(),
              new TreeSet<String>(gsub.getSupportedScriptTags())));
    } finally {
      liberation.close();
    }

    OpenTypeFont sourceSans =
        new OTFParser()
            .parse(new RandomAccessReadBufferedFile(new File(fonts, "SourceSansProBold.otf")));
    try {
      observe(
          "opentype",
          "cff-outlines",
          join(
              sourceSans.getName(),
              sourceSans.isPostScript(),
              sourceSans.isSupportedOTF(),
              sourceSans.hasLayoutTables(),
              sourceSans.getCFF().getFont().getName(),
              sourceSans.getCFF().getFont().getNumCharStrings(),
              failureKind(sourceSans::getGlyph)));
    } finally {
      sourceSans.close();
    }

    OpenTypeFont noto =
        new OTFParser(false)
            .parse(new RandomAccessReadBufferedFile(new File(fonts, "NotoSansSC-Regular.otf")));
    try {
      int gid = 8712;
      observe(
          "tables",
          "multiple-cmap-codes",
          join(
              noto.getUnicodeCmapLookup().getCharCodes(gid),
              noto.getCmap().getSubtable(0, 4).getCharCodes(gid),
              noto.getCmap().getSubtable(0, 3).getCharCodes(gid)));
    } finally {
      noto.close();
    }
  }

  private static void observeGsub(File ttfDirectory) throws Exception {
    TrueTypeFont liberation =
        new TTFParser()
            .parse(
                new RandomAccessReadBufferedFile(
                    new File(ttfDirectory, "LiberationSans-Regular.ttf")));
    try {
      GlyphSubstitutionTable table = liberation.getGsub();
      GsubData defaultData = liberation.getGsubData();
      GsubData cyrillic = table.getGsubData("cyrl");
      observe(
          "gsub",
          "scripts-and-features",
          join(
              new TreeSet<String>(table.getSupportedScriptTags()),
              defaultData.getActiveScriptName(),
              cyrillic.getActiveScriptName(),
              new TreeSet<String>(cyrillic.getSupportedFeatures()),
              table.getGsubData("missing") == null));
    } finally {
      liberation.close();
    }

    TrueTypeFont bengali =
        new TTFParser()
            .parse(
                new RandomAccessReadBufferedFile(new File(ttfDirectory, "Lohit-Bengali.ttf")));
    try {
      CmapLookup cmap = bengali.getUnicodeCmapLookup();
      GsubData data = bengali.getGsubData();
      GsubWorker worker = new GsubWorkerFactory().getGsubWorker(cmap, data);
      observe(
          "gsub",
          "bengali-model",
          join(
              data.getLanguage(),
              data.getActiveScriptName(),
              new TreeSet<String>(data.getSupportedFeatures()),
              data.getFeature("rphf").getAllGlyphIdsForSubstitution().size()));
      observe(
          "gsub",
          "bengali-shaping",
          join(
              worker.applyTransforms(glyphIds(cmap, "আমি")),
              worker.applyTransforms(glyphIds(cmap, "ব্যাস")),
              worker.applyTransforms(glyphIds(cmap, "বেলা")),
              worker.applyTransforms(glyphIds(cmap, "দ্রুত"))));
    } finally {
      bengali.close();
    }
  }

  private static void observeCollection(File ttfDirectory) throws Exception {
    byte[] collectionBytes =
        buildCollection(
            Files.readAllBytes(new File(ttfDirectory, "LiberationSans-Regular.ttf").toPath()),
            Files.readAllBytes(new File(ttfDirectory, "JosefinSans-Italic.ttf").toPath()));
    List<String> names = new ArrayList<String>();
    try (TrueTypeCollection collection =
        new TrueTypeCollection(new ByteArrayInputStream(collectionBytes))) {
      collection.processAllFonts(font -> names.add(font.getName()));
      TrueTypeFont selected = collection.getFontByName("JosefinSans-Italic");
      observe(
          "collection",
          "generated-two-font-ttc",
          join(names, selected.getName(), selected.getNumberOfGlyphs(), selected.getUnitsPerEm()));
    }
  }

  private static void observeSubsetting(File ttfDirectory) throws Exception {
    File liberation = new File(ttfDirectory, "LiberationSans-Regular.ttf");
    byte[] first;
    Map<Integer, Integer> gidMap;
    try (TrueTypeFont full =
        new TTFParser().parse(new RandomAccessReadBufferedFile(liberation))) {
      TTFSubsetter subsetter = new TTFSubsetter(full);
      subsetter.setPrefix("ABCDEF+");
      subsetter.add('Ö');
      subsetter.add('\u200A');
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      subsetter.writeToStream(output);
      first = output.toByteArray();
      gidMap = subsetter.getGIDMap();
    }

    byte[] second;
    try (TrueTypeFont full =
        new TTFParser().parse(new RandomAccessReadBufferedFile(liberation))) {
      TTFSubsetter subsetter = new TTFSubsetter(full);
      subsetter.setPrefix("ABCDEF+");
      subsetter.add('Ö');
      subsetter.add('\u200A');
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      subsetter.writeToStream(output);
      second = output.toByteArray();
    }
    try (TrueTypeFont subset =
        new TTFParser(true).parse(new RandomAccessReadBuffer(first))) {
      List<String> tags = new ArrayList<String>();
      for (TTFTable table : subset.getTables()) {
        tags.add(table.getTag());
      }
      Collections.sort(tags);
      PostScriptTable post = subset.getPostScript();
      observe(
          "subsetting",
          "composite-closure-names-and-tables",
          join(
              first.length,
              Arrays.equals(first, second),
              sha256(first),
              subset.getNumberOfGlyphs(),
              gidMap.values(),
              subset.getName(),
              subset.getNaming().getFontFamily(),
              subset.getNaming().getFontSubFamily(),
              Arrays.asList(
                  post.getName(0),
                  post.getName(1),
                  post.getName(2),
                  post.getName(3),
                  post.getName(4)),
              subset.getUnicodeCmapLookup().getGlyphId('Ö'),
              subset.getUnicodeCmapLookup().getGlyphId('\u200A'),
              tags));
    }

    try (TrueTypeFont full =
        new TTFParser().parse(new RandomAccessReadBufferedFile(liberation))) {
      TTFSubsetter emptySubsetter = new TTFSubsetter(full);
      ByteArrayOutputStream emptyOutput = new ByteArrayOutputStream();
      emptySubsetter.writeToStream(emptyOutput);
      try (TrueTypeFont empty =
          new TTFParser(true)
              .parse(new RandomAccessReadBuffer(emptyOutput.toByteArray()))) {
        observe(
            "subsetting",
            "empty-subset",
            join(
                empty.getNumberOfGlyphs(),
                empty.nameToGID(".notdef"),
                empty.getGlyph().getGlyph(0) != null));
      }

      List<String> required =
          Arrays.asList("head", "hhea", "loca", "maxp", "glyf", "hmtx");
      TTFSubsetter selectedSubsetter = new TTFSubsetter(full, required);
      selectedSubsetter.add('A');
      ByteArrayOutputStream selectedOutput = new ByteArrayOutputStream();
      selectedSubsetter.writeToStream(selectedOutput);
      try (TrueTypeFont selected =
          new TTFParser(true)
              .parse(new RandomAccessReadBuffer(selectedOutput.toByteArray()))) {
        List<String> tags = new ArrayList<String>();
        for (TTFTable table : selected.getTables()) {
          tags.add(table.getTag());
        }
        Collections.sort(tags);
        observe(
            "subsetting",
            "required-table-selection",
            join(
                selected.getNumberOfGlyphs(),
                selected.getAdvanceWidth(1),
                selected.getHorizontalMetrics().getLeftSideBearing(1),
                tags));
      }
    }

    try (TrueTypeFont full =
        new TTFParser().parse(new RandomAccessReadBufferedFile(liberation))) {
      TTFSubsetter invisibleSubsetter = new TTFSubsetter(full);
      invisibleSubsetter.add('A');
      invisibleSubsetter.add('B');
      invisibleSubsetter.add('\u200C');
      invisibleSubsetter.forceInvisible('B');
      invisibleSubsetter.forceInvisible('\u200C');
      ByteArrayOutputStream invisibleOutput = new ByteArrayOutputStream();
      invisibleSubsetter.writeToStream(invisibleOutput);
      try (TrueTypeFont invisible =
          new TTFParser(true)
              .parse(new RandomAccessReadBuffer(invisibleOutput.toByteArray()))) {
        observe(
            "subsetting",
            "forced-invisible-encoding",
            join(
                invisible.getNumberOfGlyphs(),
                invisible.getPath("A").getBounds2D().isEmpty(),
                invisible.getPath("B").getBounds2D().isEmpty(),
                invisible.getPath("uni200C").getBounds2D().isEmpty(),
                invisible.getWidth("A"),
                invisible.getWidth("B"),
                invisible.getWidth("uni200C")));
      }
    }

    observe(
        "subsetting",
        "invalid-glyph-failure",
        failureKind(
            () -> {
              try (TrueTypeFont full =
                  new TTFParser()
                      .parse(new RandomAccessReadBufferedFile(liberation))) {
                TTFSubsetter invalid = new TTFSubsetter(full);
                invalid.addGlyphIds(
                    new TreeSet<Integer>(Arrays.asList(999999)));
                invalid.writeToStream(new ByteArrayOutputStream());
              }
            }));
  }

  private static void observeDiscovery(File ttfDirectory, File fonts)
      throws Exception {
    Path root = Files.createTempDirectory("fontbox-discovery-");
    Path scan = Files.createDirectories(root.resolve("scan"));
    Path nested = Files.createDirectories(scan.resolve("nested"));
    Path hidden = Files.createDirectories(scan.resolve(".hidden"));
    File liberation = new File(ttfDirectory, "LiberationSans-Regular.ttf");
    Files.copy(liberation.toPath(), scan.resolve("Valid.TTF"));
    Files.copy(liberation.toPath(), nested.resolve("Duplicate.ttf"));
    Files.write(scan.resolve("Corrupt.otf"), bytes(0, 1, 2, 3));
    Files.write(scan.resolve("TypeOne.PFB"), bytes(0));
    Files.write(scan.resolve("Collection.TTC"), bytes(0));
    Files.write(scan.resolve("fonts.dir"), bytes(0));
    Files.write(scan.resolve("notes.txt"), bytes(0));
    Files.write(hidden.resolve("Ignored.ttf"), bytes(0));

    FontFileFinder finder = new FontFileFinder();
    List<String> discovered = relativeUris(root, finder.find(scan.toString()));
    observe(
        "discovery",
        "extension-filter-missing-duplicate-and-corrupt",
        join(
            discovered,
            finder.find(root.resolve("missing").toString()).size(),
            Arrays.equals(
                Files.readAllBytes(scan.resolve("Valid.TTF")),
                Files.readAllBytes(nested.resolve("Duplicate.ttf")))));

    List<String> collectionNames = new ArrayList<String>();
    byte[] collectionBytes =
        buildCollection(
            Files.readAllBytes(liberation.toPath()),
            Files.readAllBytes(
                new File(ttfDirectory, "JosefinSans-Italic.ttf").toPath()));
    try (TrueTypeCollection collection =
        new TrueTypeCollection(new ByteArrayInputStream(collectionBytes))) {
      collection.processAllFonts(font -> collectionNames.add(font.getName()));
    }
    String ttfName;
    try (TrueTypeFont ttf =
        new TTFParser().parse(new RandomAccessReadBufferedFile(liberation))) {
      ttfName = ttf.getName();
    }
    boolean cff;
    try (OpenTypeFont otf =
        new OTFParser()
            .parse(
                new RandomAccessReadBufferedFile(
                    new File(fonts, "SourceSansProBold.otf")))) {
      cff = otf.isPostScript();
    }
    int pfbSize;
    try (FileInputStream input =
        new FileInputStream(new File(fonts, "OpenSans-Regular.pfb"))) {
      pfbSize = new PfbParser(input).size();
    }
    observe(
        "discovery",
        "type-detection-and-failures",
        join(
            ttfName,
            cff,
            pfbSize,
            collectionNames,
            failureKind(
                () ->
                    new TTFParser()
                        .parse(
                            new RandomAccessReadBufferedFile(
                                scan.resolve("Corrupt.otf").toFile()))),
            failureKind(
                () ->
                    new TTFParser()
                        .parse(
                            new RandomAccessReadBufferedFile(
                                root.resolve("missing.ttf").toFile())))));

    Path home = Files.createDirectories(root.resolve("home"));
    Path macFonts =
        Files.createDirectories(home.resolve("Library").resolve("Fonts"));
    Path unixFonts = Files.createDirectories(home.resolve(".fonts"));
    Path windows =
        Files.createDirectories(root.resolve("windows").resolve("FONTS"));
    Files.write(macFonts.resolve("Mac.ttf"), bytes(0));
    Files.write(unixFonts.resolve("Unix.otf"), bytes(0));
    Files.write(windows.resolve("Windows.pfb"), bytes(0));
    System.setProperty("user.home", home.toString());
    System.setProperty("env.windir", root.resolve("windows").toString());

    List<String> matrix = new ArrayList<String>();
    String[][] platforms = {
      {"Windows 11", "windows"},
      {"Mac OS X", "mac"},
      {"Linux", "unix"}
    };
    String[] architectures = {"amd64", "aarch64"};
    for (String[] platform : platforms) {
      for (String architecture : architectures) {
        System.setProperty("os.name", platform[0]);
        System.setProperty("os.arch", architecture);
        matrix.add(
            platform[1]
                + "/"
                + architecture
                + "="
                + relativeUris(root, new FontFileFinder().find()));
      }
    }
    System.setProperty("os.name", "Plan 9");
    matrix.add(
        "fallback="
            + relativeUris(root, new FontFileFinder().find()));
    observe("discovery", "supported-host-selection", join(matrix));

    System.setProperty("os.name", "Mac OS X");
    FontFileFinder cached = new FontFileFinder();
    List<String> first = relativeUris(root, cached.find());
    System.setProperty("os.name", "Linux");
    List<String> second = relativeUris(root, cached.find());
    List<String> fresh = relativeUris(root, new FontFileFinder().find());
    observe(
        "discovery",
        "finder-selection-cache-and-fallback",
        join(first, second, fresh));

    Path inaccessible = Files.createDirectories(root.resolve("inaccessible"));
    Files.write(inaccessible.resolve("Blocked.ttf"), bytes(0));
    File inaccessibleFile = inaccessible.toFile();
    inaccessibleFile.setReadable(false, false);
    inaccessibleFile.setExecutable(false, false);
    observe(
        "discovery",
        "inaccessible-directory",
        failureKind(
            () -> new FontFileFinder().find(inaccessible.toString())));
    inaccessibleFile.setReadable(true, false);
    inaccessibleFile.setExecutable(true, false);
  }

  private static List<String> relativeUris(Path root, List<URI> values) {
    Path absoluteRoot = root.toAbsolutePath().normalize();
    List<String> relative = new ArrayList<String>();
    for (URI value : values) {
      Path path = new File(value).toPath().toAbsolutePath().normalize();
      if (path.startsWith(absoluteRoot)) {
        relative.add(
            absoluteRoot.relativize(path).toString().replace(File.separatorChar, '/'));
      }
    }
    Collections.sort(relative);
    return relative;
  }

  private static String sha256(byte[] bytes) throws Exception {
    return unsignedHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static String unsignedHex(byte[] values) {
    StringBuilder result = new StringBuilder();
    for (byte value : values) {
      result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
    }
    return result.toString();
  }

  private static List<Integer> glyphIds(CmapLookup cmap, String text) {
    List<Integer> glyphs = new ArrayList<Integer>();
    for (int index = 0; index < text.length(); index++) {
      glyphs.add(cmap.getGlyphId(text.charAt(index)));
    }
    return glyphs;
  }

  private static byte[] buildCollection(byte[] first, byte[] second) {
    int firstOffset = 20;
    int secondOffset = firstOffset + ((first.length + 3) & ~3);
    byte[] collection = new byte[secondOffset + second.length];
    writeInt(collection, 0, 0x74746366);
    writeInt(collection, 4, 0x00010000);
    writeInt(collection, 8, 2);
    writeInt(collection, 12, firstOffset);
    writeInt(collection, 16, secondOffset);
    copyFontIntoCollection(first, collection, firstOffset);
    copyFontIntoCollection(second, collection, secondOffset);
    return collection;
  }

  private static void copyFontIntoCollection(byte[] font, byte[] collection, int offset) {
    System.arraycopy(font, 0, collection, offset, font.length);
    int tableCount = readUnsignedShort(collection, offset + 4);
    for (int index = 0; index < tableCount; index++) {
      int tableOffsetPosition = offset + 12 + index * 16 + 8;
      writeInt(
          collection,
          tableOffsetPosition,
          readInt(collection, tableOffsetPosition) + offset);
    }
  }

  private static int readUnsignedShort(byte[] data, int offset) {
    return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
  }

  private static int readInt(byte[] data, int offset) {
    return ((data[offset] & 0xff) << 24)
        | ((data[offset + 1] & 0xff) << 16)
        | ((data[offset + 2] & 0xff) << 8)
        | (data[offset + 3] & 0xff);
  }

  private static void writeInt(byte[] data, int offset, int value) {
    data[offset] = (byte) (value >>> 24);
    data[offset + 1] = (byte) (value >>> 16);
    data[offset + 2] = (byte) (value >>> 8);
    data[offset + 3] = (byte) value;
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
    observe(
        "failure",
        "malformed-truetype",
        failureKind(() -> new TTFParser().parse(new RandomAccessReadBuffer(bytes(0, 1, 2, 3)))));
    observe(
        "failure",
        "bad-collection-header",
        failureKind(() -> new TrueTypeCollection(new ByteArrayInputStream(bytes(0, 1, 2, 3)))));
    observe(
        "failure",
        "bad-collection-count",
        failureKind(
            () ->
                new TrueTypeCollection(
                    new ByteArrayInputStream(
                        bytes(
                            0x74, 0x74, 0x63, 0x66, 0, 0, 0, 0, 0x7f, 0xff, 0xff, 0xff)))));
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
    if (value instanceof Iterable<?>) {
      StringBuilder result = new StringBuilder("[");
      int index = 0;
      for (Object item : (Iterable<?>) value) {
        if (index++ > 0) {
          result.append(", ");
        }
        result.append(value(item).toLowerCase(Locale.ROOT));
      }
      return result.append(']').toString();
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
