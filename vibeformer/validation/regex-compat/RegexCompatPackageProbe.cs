using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using Pkl.Core;

internal static class RegexCompatPackageProbe
{
    private const string Null = "~";
    private static readonly Type Compat = typeof(Evaluator).Assembly.GetType("Vibeformer.Runtime.JavaCompat", true)!;

    public static int Main(string[] args)
    {
        if (args.Length != 2) throw new ArgumentException("expected manifest and output paths");
        var output = new StringBuilder();
        foreach (var line in File.ReadLines(args[0], Encoding.UTF8))
        {
            if (line.Length == 0) continue;
            var fields = line.Split('\t');
            if (fields.Length != 6) throw new ArgumentException("invalid manifest row: " + line);
            var id = fields[0];
            try
            {
                var result = Execute(fields[1], int.Parse(fields[2], CultureInfo.InvariantCulture),
                    Decode(fields[3]), Decode(fields[4]), Decode(fields[5]));
                output.Append(id).Append("\tOK\t").Append(Encode(result)).Append('\n');
            }
            catch (Exception error) when (Unwrap(error) is not OutOfMemoryException and not StackOverflowException)
            {
                if (Environment.GetEnvironmentVariable("VIBEFORMER_DIFFERENTIAL_DEBUG") is not null)
                    Console.Error.WriteLine(Unwrap(error));
                output.Append(id).Append("\tERROR\t").Append(Encode("error")).Append('\n');
            }
        }
        File.WriteAllText(args[1], output.ToString(), new UTF8Encoding(false));
        return 0;
    }

    private static string Execute(string operation, int flags, string source, string input, string argument)
    {
        if (operation == "QUOTE_PATTERN")
        {
            var quoted = (string)InvokeStatic("QuoteRegex", source)!;
            var quotedPattern = Compile(quoted, 0);
            return Encode(quoted) + ":" + MatcherBoolean(quotedPattern, input, "Matches").ToString().ToLowerInvariant();
        }
        if (operation == "QUOTE_REPLACEMENT") return (string)InvokeStatic("QuoteReplacement", source)!;

        var regex = Compile(source, flags);
        var matcher = InvokeStatic("RegexMatcher", regex, input)!;
        return operation switch
        {
            "PATTERN" => Encode((string)InvokeStatic("RegexPattern", regex)!) + ":" +
                InvokeStatic("RegexFlags", regex) + ":" + Invoke(matcher, "GroupCount"),
            "MATCHES" => ((bool)Invoke(matcher, "Matches")!).ToString().ToLowerInvariant(),
            "LOOKING_AT" => ((bool)Invoke(matcher, "LookingAt")!).ToString().ToLowerInvariant(),
            "FIND" => AllMatches(matcher),
            "REGION" => Region(matcher, argument),
            "SPLIT" => Sequence((string[])InvokeStatic("RegexSplit", regex, input,
                int.Parse(argument, CultureInfo.InvariantCulture))!),
            "REPLACE_ALL" => (string)Invoke(matcher, "ReplaceAll", argument)!,
            "REPLACE_FIRST" => (string)Invoke(matcher, "ReplaceFirst", argument)!,
            "REPLACE_LAST" => ReplaceLast(matcher, input, argument),
            "REPLACE_ALL_MAPPED" => ReplaceAllMapped(matcher, input, argument),
            "REPLACE_FIRST_MAPPED" => ReplaceFirstMapped(matcher, input, argument),
            "REPLACE_LAST_MAPPED" => ReplaceLastMapped(matcher, input, argument),
            "APPEND" => Append(matcher, argument, input),
            _ => throw new ArgumentException("unknown operation: " + operation)
        };
    }

    private static Regex Compile(string pattern, int flags) =>
        (Regex)InvokeStatic("CompileRegex", pattern, flags)!;

    private static bool MatcherBoolean(Regex pattern, string input, string method) =>
        (bool)Invoke(InvokeStatic("RegexMatcher", pattern, input)!, method)!;

    private static string AllMatches(object matcher)
    {
        var matches = new List<string>();
        while ((bool)Invoke(matcher, "Find")!) matches.Add(Match(matcher));
        return Sequence(matches.ToArray());
    }

    private static string Region(object matcher, string argument)
    {
        var fields = argument.Split(',');
        Invoke(matcher, "Region", int.Parse(fields[0], CultureInfo.InvariantCulture),
            int.Parse(fields[1], CultureInfo.InvariantCulture));
        var method = fields[2] == "matches" ? "Matches" : fields[2] == "lookingAt" ? "LookingAt" : "Find";
        var success = (bool)Invoke(matcher, method)!;
        return success ? "true:" + Match(matcher) : "false";
    }

    private static string Append(object matcher, string replacement, string input)
    {
        var result = new StringBuilder();
        while ((bool)Invoke(matcher, "Find")!) Invoke(matcher, "AppendReplacement", result, replacement);
        Invoke(matcher, "AppendTail", result);
        return result.ToString();
    }

    private static bool FindLast(object matcher)
    {
        if (!(bool)Invoke(matcher, "Find")!) return false;
        object last;
        do
        {
            last = Invoke(matcher, "ToMatchResult")!;
        } while ((bool)Invoke(matcher, "Find")!);
        Invoke(matcher, "Region", (int)Invoke(last, "Start")!, (int)Invoke(last, "End")!);
        return (bool)Invoke(matcher, "LookingAt")!;
    }

    private static string ReplaceLast(object matcher, string input, string replacement)
    {
        if (!FindLast(matcher)) return input;
        var result = new StringBuilder();
        Invoke(matcher, "AppendReplacement", result, replacement);
        Invoke(matcher, "AppendTail", result);
        return result.ToString();
    }

    private static string MappedReplacement(object matcher, string template) =>
        (string)InvokeStatic("QuoteReplacement",
            template.Replace("{}", (string)Invoke(matcher, "Group")!, StringComparison.Ordinal))!;

    private static string ReplaceAllMapped(object matcher, string input, string template)
    {
        if (!(bool)Invoke(matcher, "Find")!) return input;
        var result = new StringBuilder();
        do
        {
            Invoke(matcher, "AppendReplacement", result, MappedReplacement(matcher, template));
        } while ((bool)Invoke(matcher, "Find")!);
        Invoke(matcher, "AppendTail", result);
        return result.ToString();
    }

    private static string ReplaceFirstMapped(object matcher, string input, string template)
    {
        if (!(bool)Invoke(matcher, "Find")!) return input;
        var result = new StringBuilder();
        Invoke(matcher, "AppendReplacement", result, MappedReplacement(matcher, template));
        Invoke(matcher, "AppendTail", result);
        return result.ToString();
    }

    private static string ReplaceLastMapped(object matcher, string input, string template)
    {
        if (!FindLast(matcher)) return input;
        var result = new StringBuilder();
        Invoke(matcher, "AppendReplacement", result, MappedReplacement(matcher, template));
        Invoke(matcher, "AppendTail", result);
        return result.ToString();
    }

    private static string Match(object matcher)
    {
        var count = (int)Invoke(matcher, "GroupCount")!;
        var groups = new List<string>();
        for (var group = 0; group <= count; group++)
        {
            var value = (string?)Invoke(matcher, "Group", group);
            groups.Add(Invoke(matcher, "Start", group) + "," + Invoke(matcher, "End", group) + "," +
                (value is null ? Null : Encode(value)));
        }
        return string.Join(";", groups);
    }

    private static string Sequence(string[] values) =>
        values.Length + ":" + string.Join(",", values.Select(Encode));

    private static object? InvokeStatic(string name, params object?[] arguments) => InvokeMethod(Compat, null, name, arguments);
    private static object? Invoke(object target, string name, params object?[] arguments) =>
        InvokeMethod(target.GetType(), target, name, arguments);

    private static object? InvokeMethod(Type type, object? target, string name, object?[] arguments)
    {
        var method = type.GetMethods(BindingFlags.Public | BindingFlags.NonPublic |
                                     (target is null ? BindingFlags.Static : BindingFlags.Instance))
            .Single(candidate => candidate.Name == name && candidate.GetParameters().Length == arguments.Length &&
                candidate.GetParameters().Zip(arguments).All(pair =>
                    pair.Second is null || pair.First.ParameterType.IsInstanceOfType(pair.Second) ||
                    pair.First.ParameterType.IsPrimitive && pair.Second.GetType().IsPrimitive));
        return method.Invoke(target, arguments);
    }

    private static Exception Unwrap(Exception error) =>
        error is TargetInvocationException { InnerException: { } inner } ? Unwrap(inner) : error;

    private static string Decode(string value) => Encoding.UTF8.GetString(Convert.FromBase64String(value));
    private static string Encode(string value)
    {
        var normalized = new StringBuilder(value.Length);
        for (var index = 0; index < value.Length; index++)
        {
            if (char.IsHighSurrogate(value[index]) && index + 1 < value.Length &&
                char.IsLowSurrogate(value[index + 1]))
            {
                normalized.Append(value[index]).Append(value[++index]);
            }
            else if (char.IsSurrogate(value[index]))
            {
                // Java's UTF-8 encoder replaces each unpaired UTF-16 unit with
                // '?'; normalize the transport only, while matcher indices and
                // split element counts still prove the underlying boundaries.
                normalized.Append('?');
            }
            else
            {
                normalized.Append(value[index]);
            }
        }
        return Convert.ToBase64String(Encoding.UTF8.GetBytes(normalized.ToString()));
    }
}
