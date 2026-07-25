import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/** Generates deterministic JDK Unicode name, block, and script data for the .NET regex boundary. */
public final class GenerateRegexUnicodeData {
  private static final String[] PROPERTY_NAMES = {
      "Cn", "Lu", "Ll", "Lt", "Lm", "Lo", "Mn", "Me", "Mc", "Nd", "Nl", "No",
      "Zs", "Zl", "Zp", "Cc", "Cf", "Co", "Cs", "Pd", "Ps", "Pe", "Pc", "Po",
      "Sm", "Sc", "Sk", "So", "Pi", "Pf", "L", "M", "N", "Z", "C", "P", "S",
      "LC", "LD", "L1", "all", "ASCII", "Alnum", "Alpha", "Blank", "Cntrl", "Digit",
      "Graph", "Lower", "Print", "Punct", "Space", "Upper", "XDigit",
      "javaLowerCase", "javaUpperCase", "javaAlphabetic", "javaIdeographic", "javaTitleCase",
      "javaDigit", "javaDefined", "javaLetter", "javaLetterOrDigit", "javaJavaIdentifierStart",
      "javaJavaIdentifierPart", "javaUnicodeIdentifierStart", "javaUnicodeIdentifierPart",
      "javaIdentifierIgnorable", "javaSpaceChar", "javaWhitespace", "javaISOControl", "javaMirrored",
      "Alphabetic", "Assigned", "Control", "Emoji", "Emoji_Presentation", "Emoji_Modifier",
      "Emoji_Modifier_Base", "Emoji_Component", "Extended_Pictographic", "Hex_Digit", "Ideographic",
      "Join_Control", "Letter", "Lowercase", "Noncharacter_Code_Point", "Titlecase", "Punctuation",
      "Uppercase", "White_Space", "Word"
  };

  private static final String[] UNICODE_BINARY_PROPERTY_NAMES = {
      "Alphabetic", "Assigned", "Control", "Emoji", "Emoji_Presentation", "Emoji_Modifier",
      "Emoji_Modifier_Base", "Emoji_Component", "Extended_Pictographic", "Hex_Digit", "Ideographic",
      "Join_Control", "Letter", "Lowercase", "Noncharacter_Code_Point", "Titlecase", "Punctuation",
      "Uppercase", "White_Space", "Word"
  };

  private record PropertyMatcher(String key, Matcher matcher, Ranges ranges) {}

  private static final class Ranges {
    private final List<int[]> values = new ArrayList<>();

    void add(int codePoint) {
      if (!values.isEmpty() && values.get(values.size() - 1)[1] + 1 == codePoint) {
        values.get(values.size() - 1)[1] = codePoint;
      } else {
        values.add(new int[] {codePoint, codePoint});
      }
    }

    String encode() {
      var result = new StringBuilder();
      for (int[] range : values) {
        if (!result.isEmpty()) result.append(',');
        result.append(Integer.toHexString(range[0]));
        if (range[1] != range[0]) result.append('-').append(Integer.toHexString(range[1]));
      }
      return result.toString();
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2)
      throw new IllegalArgumentException("expected TSV output and optional C# output paths");

    var blocks = new IdentityHashMap<Character.UnicodeBlock, Ranges>();
    var scripts = new LinkedHashMap<Character.UnicodeScript, Ranges>();
    for (Character.UnicodeScript script : Character.UnicodeScript.values()) {
      scripts.put(script, new Ranges());
    }

    var names = new TreeMap<String, Integer>();
    var caseFolds = new ArrayList<int[]>();
    var graphemeTypes = new Ranges[15];
    for (int index = 0; index < graphemeTypes.length; index++) graphemeTypes[index] = new Ranges();
    var indicConsonants = new Ranges();
    var indicExtends = new Ranges();
    var indicLinkers = new Ranges();
    Class<?> grapheme = Class.forName("jdk.internal.util.regex.Grapheme");
    Method graphemeType = grapheme.getDeclaredMethod("getType", int.class);
    graphemeType.setAccessible(true);
    Class<?> indic = Class.forName("jdk.internal.util.regex.IndicConjunctBreak");
    Method isConsonant = indic.getDeclaredMethod("isConsonant", int.class);
    Method isExtend = indic.getDeclaredMethod("isExtend", int.class);
    Method isLinker = indic.getDeclaredMethod("isLinker", int.class);
    isConsonant.setAccessible(true);
    isExtend.setAccessible(true);
    isLinker.setAccessible(true);
    var propertyMatchers = new ArrayList<PropertyMatcher>();
    var propertyKeys = new HashSet<String>();
    for (int flags : new int[] {0, Pattern.CASE_INSENSITIVE, Pattern.UNICODE_CHARACTER_CLASS,
                                Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE}) {
      for (String name : PROPERTY_NAMES) {
        try {
          String key = ((flags & Pattern.UNICODE_CHARACTER_CLASS) == 0 ? "a" : "u")
              + ((flags & Pattern.CASE_INSENSITIVE) == 0 ? "s" : "i") + normalize(name);
          Matcher matcher = Pattern.compile("\\p{" + name + "}", flags).matcher("");
          if (propertyKeys.add(key))
            propertyMatchers.add(new PropertyMatcher(key, matcher, new Ranges()));
        } catch (IllegalArgumentException ignored) {
          // A property can intentionally exist only under UNICODE_CHARACTER_CLASS.
        }
      }
      for (String name : UNICODE_BINARY_PROPERTY_NAMES) {
        String key = ((flags & Pattern.UNICODE_CHARACTER_CLASS) == 0 ? "a" : "u")
            + ((flags & Pattern.CASE_INSENSITIVE) == 0 ? "s" : "i") + normalize(name);
        if (propertyKeys.add(key)) propertyMatchers.add(new PropertyMatcher(
            key, Pattern.compile("\\p{Is" + name + "}", flags).matcher(""), new Ranges()));
      }
    }
    for (int codePoint = Character.MIN_CODE_POINT; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
      Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
      if (block != null) blocks.computeIfAbsent(block, ignored -> new Ranges()).add(codePoint);
      scripts.get(Character.UnicodeScript.of(codePoint)).add(codePoint);
      String name = Character.getName(codePoint);
      if (name != null && !isAlgorithmicName(codePoint, block, name)) names.put(name, codePoint);
      String input = new String(Character.toChars(codePoint));
      for (PropertyMatcher property : propertyMatchers) {
        if (property.matcher().reset(input).matches()) property.ranges().add(codePoint);
      }
      int upper = Character.toUpperCase(codePoint);
      int folded = Character.toLowerCase(upper);
      if (upper != codePoint || folded != codePoint) caseFolds.add(new int[] {codePoint, upper, folded});
      graphemeTypes[(int) graphemeType.invoke(null, codePoint)].add(codePoint);
      if ((boolean) isConsonant.invoke(null, codePoint)) indicConsonants.add(codePoint);
      if ((boolean) isExtend.invoke(null, codePoint)) indicExtends.add(codePoint);
      if ((boolean) isLinker.invoke(null, codePoint)) indicLinkers.add(codePoint);
    }

    var blockAliases = new TreeMap<String, Character.UnicodeBlock>();
    for (Field field : Character.UnicodeBlock.class.getFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType() == Character.UnicodeBlock.class) {
        blockAliases.put(normalize(field.getName()), (Character.UnicodeBlock) field.get(null));
      }
    }

    var output = new StringBuilder();
    output.append("V\t").append(Runtime.version()).append('\n');
    for (Map.Entry<String, Character.UnicodeBlock> entry : blockAliases.entrySet()) {
      Ranges ranges = blocks.get(entry.getValue());
      if (ranges != null) output.append("B\t").append(entry.getKey()).append('\t').append(ranges.encode()).append('\n');
    }
    blocks.entrySet().stream()
        .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
        .forEach(entry -> output.append("A\t").append(normalize(entry.getKey().toString())).append('\t')
            .append(entry.getValue().encode()).append('\n'));
    scripts.entrySet().stream()
        .sorted(Comparator.comparing(entry -> entry.getKey().name()))
        .forEach(entry -> output.append("S\t").append(normalize(entry.getKey().name())).append('\t')
            .append(entry.getValue().encode()).append('\n'));
    Field scriptAliasesField = Character.UnicodeScript.class.getDeclaredField("aliases");
    scriptAliasesField.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Character.UnicodeScript> scriptAliases =
        (Map<String, Character.UnicodeScript>) scriptAliasesField.get(null);
    scriptAliases.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> output.append("S\t").append(normalize(entry.getKey())).append('\t')
            .append(scripts.get(entry.getValue()).encode()).append('\n'));
    propertyMatchers.stream()
        .sorted(Comparator.comparing(PropertyMatcher::key))
        .forEach(property -> output.append("P\t").append(property.key()).append('\t')
            .append(property.ranges().encode()).append('\n'));
    for (int[] mapping : caseFolds) {
      output.append("F\t").append(Integer.toHexString(mapping[0])).append('\t')
          .append(Integer.toHexString(mapping[1])).append(',')
          .append(Integer.toHexString(mapping[2])).append('\n');
    }
    for (int type = 0; type < graphemeTypes.length; type++) {
      output.append("G\t").append(type).append('\t').append(graphemeTypes[type].encode()).append('\n');
    }
    output.append("I\tconsonant\t").append(indicConsonants.encode()).append('\n');
    output.append("I\textend\t").append(indicExtends.encode()).append('\n');
    output.append("I\tlinker\t").append(indicLinkers.encode()).append('\n');
    for (Map.Entry<String, Integer> entry : names.entrySet()) {
      output.append("N\t").append(entry.getKey()).append('\t')
          .append(Integer.toHexString(entry.getValue())).append('\n');
    }
    Files.writeString(Path.of(args[0]), output, StandardCharsets.UTF_8);
    if (args.length == 2) Files.writeString(Path.of(args[1]), csharp(output.toString()), StandardCharsets.UTF_8);
  }

  private static String normalize(String value) {
    var result = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isLetterOrDigit(character)) result.append(Character.toLowerCase(character));
    }
    return result.toString();
  }

  private static boolean isAlgorithmicName(
      int codePoint, Character.UnicodeBlock block, String name) {
    String hex = Integer.toHexString(codePoint).toUpperCase(Locale.ROOT);
    if (block != null && name.equals(block.toString().replace('_', ' ') + " " + hex)) return true;
    if (codePoint >= 0xac00 && codePoint <= 0xd7a3 && name.startsWith("HANGUL SYLLABLE ")) return true;
    int separator = name.lastIndexOf('-');
    if (separator < 0 || !name.substring(separator + 1).equals(hex)) return false;
    return switch (normalize(name.substring(0, separator))) {
      case "cjkunifiedideograph", "cjkcompatibilityideograph", "tangutideograph",
          "khitansmallscriptcharacter", "nushucharacter" -> true;
      default -> false;
    };
  }

  private static String csharp(String data) throws Exception {
    var compressed = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(compressed)) {
      gzip.write(data.getBytes(StandardCharsets.UTF_8));
    }
    String encoded = Base64.getEncoder().encodeToString(compressed.toByteArray());
    var result = new StringBuilder(
        "// Deterministic JDK Unicode data used only by the generic Java regex boundary.\n" +
        "// Regenerate with GenerateRegexUnicodeData.java; do not edit by hand.\n" +
        "#nullable enable\n\nnamespace DripSharp.Runtime;\n\n" +
        "internal static class JavaRegexUnicodeData\n{\n" +
        "    internal static readonly string GzipBase64 = string.Concat(new string[]\n    {\n");
    for (int offset = 0; offset < encoded.length(); offset += 8000) {
      result.append("        \"")
          .append(encoded, offset, Math.min(offset + 8000, encoded.length()))
          .append("\",\n");
    }
    return result.append("    });\n}\n").toString();
  }
}
