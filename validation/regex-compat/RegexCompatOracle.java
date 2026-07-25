import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RegexCompatOracle {
  private static final String NULL = "~";

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("expected manifest and output paths");
    var output = new StringBuilder();
    try (BufferedReader reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) continue;
        String[] fields = line.split("\\t", -1);
        if (fields.length != 6) throw new IllegalArgumentException("invalid manifest row: " + line);
        String id = fields[0];
        try {
          String result = execute(
              fields[1],
              Integer.parseInt(fields[2]),
              decode(fields[3]),
              decode(fields[4]),
              decode(fields[5]));
          output.append(id).append("\tOK\t").append(encode(result)).append('\n');
        } catch (RuntimeException error) {
          output.append(id).append("\tERROR\t").append(encode("error")).append('\n');
        }
      }
    }
    Files.writeString(Path.of(args[1]), output, StandardCharsets.UTF_8);
  }

  private static String execute(
      String operation, int flags, String source, String input, String argument) {
    if (operation.equals("QUOTE_PATTERN")) {
      String quoted = Pattern.quote(source);
      return encode(quoted) + ":" + Pattern.compile(quoted).matcher(input).matches();
    }
    if (operation.equals("QUOTE_REPLACEMENT")) return Matcher.quoteReplacement(source);

    Pattern pattern = Pattern.compile(source, flags);
    Matcher matcher = pattern.matcher(input);
    return switch (operation) {
      case "PATTERN" -> encode(pattern.pattern()) + ":" + pattern.flags() + ":" + matcher.groupCount();
      case "MATCHES" -> Boolean.toString(matcher.matches());
      case "LOOKING_AT" -> Boolean.toString(matcher.lookingAt());
      case "FIND" -> allMatches(matcher);
      case "REGION" -> region(matcher, argument);
      case "SPLIT" -> sequence(pattern.split(input, Integer.parseInt(argument)));
      case "REPLACE_ALL" -> matcher.replaceAll(argument);
      case "REPLACE_FIRST" -> matcher.replaceFirst(argument);
      case "REPLACE_LAST" -> replaceLast(matcher, input, argument);
      case "REPLACE_ALL_MAPPED" -> replaceAllMapped(matcher, input, argument);
      case "REPLACE_FIRST_MAPPED" -> replaceFirstMapped(matcher, input, argument);
      case "REPLACE_LAST_MAPPED" -> replaceLastMapped(matcher, input, argument);
      case "APPEND" -> append(matcher, argument);
      default -> throw new IllegalArgumentException("unknown operation: " + operation);
    };
  }

  private static String allMatches(Matcher matcher) {
    var matches = new ArrayList<String>();
    while (matcher.find()) matches.add(match(matcher));
    return sequence(matches.toArray(String[]::new));
  }

  private static String region(Matcher matcher, String argument) {
    String[] fields = argument.split(",", -1);
    matcher.region(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]));
    boolean success = fields[2].equals("matches") ? matcher.matches()
        : fields[2].equals("lookingAt") ? matcher.lookingAt()
        : matcher.find();
    return success ? "true:" + match(matcher) : "false";
  }

  private static String append(Matcher matcher, String replacement) {
    var result = new StringBuffer();
    while (matcher.find()) matcher.appendReplacement(result, replacement);
    matcher.appendTail(result);
    return result.toString();
  }

  private static boolean findLast(Matcher matcher) {
    if (!matcher.find()) return false;
    java.util.regex.MatchResult last;
    do {
      last = matcher.toMatchResult();
    } while (matcher.find());
    return matcher.region(last.start(), last.end()).lookingAt();
  }

  private static String replaceLast(Matcher matcher, String input, String replacement) {
    if (!findLast(matcher)) return input;
    var result = new StringBuffer();
    matcher.appendReplacement(result, replacement);
    matcher.appendTail(result);
    return result.toString();
  }

  private static String mappedReplacement(Matcher matcher, String template) {
    return Matcher.quoteReplacement(template.replace("{}", matcher.group()));
  }

  private static String replaceAllMapped(Matcher matcher, String input, String template) {
    if (!matcher.find()) return input;
    var result = new StringBuffer();
    do {
      matcher.appendReplacement(result, mappedReplacement(matcher, template));
    } while (matcher.find());
    matcher.appendTail(result);
    return result.toString();
  }

  private static String replaceFirstMapped(Matcher matcher, String input, String template) {
    if (!matcher.find()) return input;
    var result = new StringBuffer();
    matcher.appendReplacement(result, mappedReplacement(matcher, template));
    matcher.appendTail(result);
    return result.toString();
  }

  private static String replaceLastMapped(Matcher matcher, String input, String template) {
    if (!findLast(matcher)) return input;
    var result = new StringBuffer();
    matcher.appendReplacement(result, mappedReplacement(matcher, template));
    matcher.appendTail(result);
    return result.toString();
  }

  private static String match(Matcher matcher) {
    var groups = new ArrayList<String>();
    for (int group = 0; group <= matcher.groupCount(); group++) {
      String value = matcher.group(group);
      groups.add(matcher.start(group) + "," + matcher.end(group) + "," +
          (value == null ? NULL : encode(value)));
    }
    return String.join(";", groups);
  }

  private static String sequence(String[] values) {
    var encoded = new ArrayList<String>(values.length);
    for (String value : values) encoded.add(encode(value));
    return values.length + ":" + String.join(",", encoded);
  }

  private static String decode(String value) {
    return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
  }

  private static String encode(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
