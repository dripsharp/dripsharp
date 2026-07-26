import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Bidi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BidiOracle
{
    private static final List<String> OBSERVATIONS = new ArrayList<>();
    private static final Map<Character, Character> MIRRORING_CHAR_MAP = new HashMap<>();

    private record Case(String id, String text)
    {
    }

    private static final Case[] CASES = {
        new Case("empty", ""),
        new Case("ltr", "plain Latin"),
        new Case("rtl-hebrew", "\u05D0\u05D1\u05D2"),
        new Case("rtl-arabic", "\u0627\u0644\u0639\u0631\u0628\u064A\u0629"),
        new Case("ltr-rtl", "abc \u05D0\u05D1\u05D2"),
        new Case("rtl-ltr", "\u05D0\u05D1\u05D2 abc"),
        new Case("rtl-number", "\u05D0\u05D1 123 \u05D2"),
        new Case("arabic-number", "\u0627\u0628 12.34 \u062C"),
        new Case("neutral", "  (123) - "),
        new Case("nested-embedding", "a \u202B\u05D0 12 \u202Axy\u202C \u05D1\u202C z"),
        new Case("nested-override", "a \u202Eab\u202C \u202D\u05D0\u05D1\u202C z"),
        new Case("isolates", "a\u2067\u05D0 12\u2066xy\u2069\u05D1\u2069z"),
        new Case("isolate-only", "\u2067\u05D0\u05D1\u2069"),
        new Case("unclosed-isolate-only", "\u2067\u05D0\u05D1"),
        new Case("unclosed-controls", "\u202B\u05D0\u202A12\u2067\u05D1"),
        new Case("stray-controls", "a\u202C\u2069\u202C\u05D0\u2069z"),
        new Case("paragraph-break", "abc\n\u05D0\u05D1"),
        new Case("unpaired-surrogates", "\uD800a\uDC00\u05D0\uDFFF"),
        new Case("supplementary-rtl", "a \uD83A\uDD00\uD83A\uDD01 12 z"),
        new Case("supplementary-neutral", "\u05D0\uD83D\uDE00(\u05D1"),
        new Case("brackets", "abc [\u05D0\u05D1 (12)] xyz"),
        new Case("ligatures", "\u05D0 \uFB01 \uFEFB abc")
    };

    private static final int[] DIRECTIONS = {
        Bidi.DIRECTION_LEFT_TO_RIGHT,
        Bidi.DIRECTION_RIGHT_TO_LEFT,
        Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT,
        Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT
    };

    private BidiOracle()
    {
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 2)
        {
            throw new IllegalArgumentException(
                    "Expected output trace and PDFBox BidiMirroring.txt.");
        }
        loadMirroring(Path.of(args[1]));

        for (Case value : CASES)
        {
            observeCase(value);
        }
        for (Case value : stressCases())
        {
            observeCase(value);
        }
        for (Case value : randomCases())
        {
            observe("input", value.id(), utf16(value.text()));
            for (int direction : DIRECTIONS)
            {
                observeBidi(value.id() + "-" + direction, value.text(), direction);
            }
        }

        byte[][] reorderCases = {
            { 0, 1, 1 },
            { 1, 1, 0 },
            { 0, 1, 2, 2, 1, 0 },
            { 3, 2, 2, 1 },
            { 0, 0, 0 },
            { 126, 125, 124, 125 }
        };
        for (int index = 0; index < reorderCases.length; index++)
        {
            observeReorder("case-" + index, reorderCases[index]);
        }

        int[] mirrored = {
            '(', ')', '<', '>', '[', ']', '{', '}',
            0x2045, 0x2046, 0x2140, 0x2201, 0x2211, 0x221B,
            0x22A6, 0x22A7, 0x22A8, 0x22A9, 0x1D6DB, 0x1D715,
            0x0041, 0x05D0, 0x1F600
        };
        for (int codePoint : mirrored)
        {
            observe("mirrored", String.format("u+%04x", codePoint),
                    Character.isMirrored(codePoint));
        }
        observeMirroredProperty();

        Files.write(Path.of(args[0]), OBSERVATIONS, StandardCharsets.UTF_8);
    }

    private static void observeBidi(String id, String text, int direction)
    {
        Bidi bidi = new Bidi(text, direction);
        StringBuilder value = new StringBuilder();
        value.append("base=").append(bidi.getBaseLevel());
        value.append(",mixed=").append(bidi.isMixed());
        value.append(",ltr=").append(bidi.isLeftToRight());
        value.append(",rtl=").append(bidi.isRightToLeft());
        value.append(",runs=").append(bidi.getRunCount());
        for (int run = 0; run < bidi.getRunCount(); run++)
        {
            value.append(';')
                    .append(bidi.getRunStart(run)).append('-')
                    .append(bidi.getRunLimit(run)).append('-')
                    .append(bidi.getRunLevel(run));
        }
        observe("analysis", id, value);
    }

    private static void observeCase(Case value)
    {
        for (int direction : DIRECTIONS)
        {
            observeBidi(value.id() + "-" + direction, value.text(), direction);
        }
        observe("direction", value.id(), utf16(handleDirection(value.text())));
    }

    private static List<Case> stressCases()
    {
        return List.of(
                new Case(
                        "deep-embedding",
                        "a" + "\u202B".repeat(130) + "\u05D0\u05D1 12"
                                + "\u202C".repeat(130) + "z"),
                new Case(
                        "deep-isolates",
                        "a" + "\u2067".repeat(130) + "\u05D0\u05D1 12"
                                + "\u2069".repeat(130) + "z"),
                new Case(
                        "overflow-overrides",
                        "\u202D\u202E".repeat(70) + "abc \u05D0\u05D1"
                                + "\u202C".repeat(140)),
                new Case(
                        "stray-controls-heavy",
                        "\u202C\u2069".repeat(130) + "a \u05D0 12"),
                new Case(
                        "crlf-reset",
                        "a\u202B\u05D0\r\n\u202Eabc\u202C\r\u05D1\nz"));
    }

    private static void observeReorder(String id, byte[] levels)
    {
        Integer[] objects = new Integer[levels.length + 2];
        for (int index = 0; index < objects.length; index++)
        {
            objects[index] = index;
        }
        Bidi.reorderVisually(levels, 0, objects, 1, levels.length);
        observe("reorder", id, join(objects));
    }

    private static List<Case> randomCases()
    {
        int[] alphabet = {
            0x0041, 0x0062, 0x05D0, 0x05D1, 0x0627, 0x0644,
            0x0031, 0x0661, 0x002B, 0x002C, 0x002E, 0x0024,
            0x0301, 0x0020, 0x000A, 0x0009, 0x00AD,
            0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
            0x2066, 0x2067, 0x2068, 0x2069,
            0x0028, 0x0029, 0x005B, 0x005D,
            0x200E, 0x200F, 0x061C, 0x1E900, 0x1F600
        };
        List<Case> result = new ArrayList<>();
        long state = 0x5EEDB1D1L;
        for (int caseIndex = 0; caseIndex < 250; caseIndex++)
        {
            state = next(state);
            int length = 1 + (int) (state % 40);
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < length; index++)
            {
                state = next(state);
                text.appendCodePoint(alphabet[(int) (state % alphabet.length)]);
            }
            result.add(new Case("random-" + caseIndex, text.toString()));
        }
        return result;
    }

    private static long next(long state)
    {
        return (state * 1664525L + 1013904223L) & 0xFFFFFFFFL;
    }

    private static void observeMirroredProperty()
    {
        long hash = 1469598103934665603L;
        int count = 0;
        for (int codePoint = Character.MIN_CODE_POINT;
                codePoint <= Character.MAX_CODE_POINT;
                codePoint++)
        {
            if (Character.isMirrored(codePoint))
            {
                count++;
                hash ^= codePoint;
                hash *= 1099511628211L;
            }
        }
        observe("mirrored", "all",
                count + ":" + Long.toUnsignedString(hash, 16));
    }

    private static String handleDirection(String word)
    {
        Bidi bidi = new Bidi(word, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
        if (!bidi.isMixed()
                && bidi.getBaseLevel() == Bidi.DIRECTION_LEFT_TO_RIGHT)
        {
            return word;
        }

        int runCount = bidi.getRunCount();
        byte[] levels = new byte[runCount];
        Integer[] runs = new Integer[runCount];
        for (int index = 0; index < runCount; index++)
        {
            levels[index] = (byte) bidi.getRunLevel(index);
            runs[index] = index;
        }
        Bidi.reorderVisually(levels, 0, runs, 0, runCount);

        StringBuilder result = new StringBuilder();
        for (int visualRun = 0; visualRun < runCount; visualRun++)
        {
            int index = runs[visualRun];
            int start = bidi.getRunStart(index);
            int end = bidi.getRunLimit(index);
            if ((levels[index] & 1) != 0)
            {
                while (--end >= start)
                {
                    char character = word.charAt(end);
                    if (Character.isMirrored(word.codePointAt(end)))
                    {
                        result.append(MIRRORING_CHAR_MAP.getOrDefault(
                                character, character));
                    }
                    else
                    {
                        result.append(character);
                    }
                }
            }
            else
            {
                result.append(word, start, end);
            }
        }
        return result.toString();
    }

    private static void loadMirroring(Path path) throws Exception
    {
        try (BufferedReader reader = Files.newBufferedReader(
                path, StandardCharsets.US_ASCII))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                int comment = line.indexOf('#');
                if (comment >= 0)
                {
                    line = line.substring(0, comment);
                }
                String[] fields = line.split(";");
                if (fields.length == 2)
                {
                    MIRRORING_CHAR_MAP.put(
                            (char) Integer.parseInt(fields[0].trim(), 16),
                            (char) Integer.parseInt(fields[1].trim(), 16));
                }
            }
        }
    }

    private static String utf16(String value)
    {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++)
        {
            if (index > 0)
            {
                result.append(',');
            }
            result.append(String.format("%04x", (int) value.charAt(index)));
        }
        return result.toString();
    }

    private static String join(Object[] values)
    {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length; index++)
        {
            if (index > 0)
            {
                result.append(',');
            }
            result.append(values[index]);
        }
        return result.toString();
    }

    private static void observe(String family, String id, Object value)
    {
        OBSERVATIONS.add(family + "\t" + id + "\t" + value);
    }
}
