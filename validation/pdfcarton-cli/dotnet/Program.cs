// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using System.Diagnostics;
using System.Globalization;
using System.Reflection;
using System.Text;
using System.Text.Json;

namespace PdfCli;

/// <summary>A contract error: its code decides the exit code.</summary>
internal sealed class CliException(string code, string message, Exception? cause = null)
    : Exception(message, cause)
{
    public string Code { get; } = code;
}

internal enum Arity { One, Many, None }

internal sealed record CommandSpec(Arity Arity, Dictionary<string, string> Options, string[] Required);

/// <summary>Parsed arguments, checked inputs and, for form-fill, the values file.</summary>
internal sealed class Context
{
    public required string Command { get; init; }
    public required Dictionary<string, object> Options { get; init; }
    public required List<string> Positional { get; init; }
    public SortedDictionary<string, object>? Values { get; set; }

    public string Input => Positional[0];
    public string? Password => Get<string>("--password");
    public bool Has(string option) => Options.ContainsKey(option);
    public T? Get<T>(string option) => Options.TryGetValue(option, out var v) ? (T)v : default;
}

/// <summary>
/// pdfcli over PdfCarton, implementing validation/pdfcarton-cli/contract.md.
/// </summary>
internal static class Program
{
    private static readonly Dictionary<string, int> ExitCodes = new()
    {
        ["usage"] = 2,
        ["io"] = 3,
        ["invalid-password"] = 4,
        ["owner-password-required"] = 4,
        ["load-failed"] = 5,
    };

    private static Dictionary<string, string> Opts(params string[] pairs)
    {
        var d = new Dictionary<string, string>();
        for (var i = 0; i < pairs.Length; i += 2) d[pairs[i]] = pairs[i + 1];
        return d;
    }

    internal static readonly Dictionary<string, CommandSpec> Commands = new()
    {
        ["info"] = new(Arity.One, Opts("--password", "string"), []),
        ["text"] = new(Arity.One, Opts("--password", "string", "--pages", "range", "--sort", "flag"), []),
        ["render"] = new(Arity.One, Opts("--password", "string", "--pages", "range", "--dpi", "dpi",
            "--image-type", "image-type", "--out-dir", "string", "--no-write", "flag"), []),
        ["images"] = new(Arity.One, Opts("--password", "string", "--pages", "range", "--out-dir", "string"), []),
        ["fonts"] = new(Arity.One, Opts("--password", "string", "--pages", "range"), []),
        ["outline"] = new(Arity.One, Opts("--password", "string"), []),
        ["annotations"] = new(Arity.One, Opts("--password", "string", "--pages", "range"), []),
        ["form-fields"] = new(Arity.One, Opts("--password", "string"), []),
        ["form-fill"] = new(Arity.One, Opts("--password", "string", "--values", "string", "--out", "string"),
            ["--values", "--out"]),
        ["attachments"] = new(Arity.One, Opts("--password", "string", "--out-dir", "string"), []),
        ["merge"] = new(Arity.Many, Opts("--out", "string"), ["--out"]),
        ["split"] = new(Arity.One, Opts("--password", "string", "--ranges", "ranges", "--out-dir", "string"),
            ["--ranges", "--out-dir"]),
        ["rotate"] = new(Arity.One, Opts("--password", "string", "--angle", "angle", "--pages", "range",
            "--out", "string"), ["--angle", "--out"]),
        ["encrypt"] = new(Arity.One, Opts("--password", "string", "--owner-password", "string",
            "--user-password", "string", "--key-length", "key-length", "--permissions", "permissions",
            "--out", "string"), ["--owner-password", "--out"]),
        ["decrypt"] = new(Arity.One, Opts("--password", "string", "--out", "string"), ["--out"]),
        ["create"] = new(Arity.None, Opts("--text", "string", "--out", "string"), ["--out"]),
        ["save"] = new(Arity.One, Opts("--password", "string", "--compress", "flag", "--out", "string"), ["--out"]),
    };

    private static readonly Dictionary<string, string> TimingOptions = Opts("--warmup", "int", "--repeat", "int");

    internal static readonly string[] ImageTypeNames = ["rgb", "argb", "gray", "binary"];

    internal static readonly string[] PermissionNames =
        ["print", "modify", "extract", "annotate", "fill-in", "extract-for-accessibility", "assemble", "print-faithful"];

    public static int Main(string[] args)
    {
        var (exit, output) = Run(args);
        using var stdout = Console.OpenStandardOutput();
        var bytes = new UTF8Encoding(false).GetBytes(output);
        stdout.Write(bytes, 0, bytes.Length);
        stdout.Flush();
        return exit;
    }

    /// <summary>Runs pdfcli argv and returns the exit code and the JSON line.</summary>
    internal static (int Exit, string Output) Run(string[] args)
    {
        var command = args.Length > 0 ? args[0] : null;
        var known = command is not null && Commands.ContainsKey(command);
        JObj document;
        int exit;
        try
        {
            if (!known)
                throw new CliException("usage", command is null ? "No command given" : $"Unknown command: {command}");
            var ctx = ParseArgs(command!, args[1..]);
            Prepare(ctx);
            var timed = ctx.Has("--warmup") || ctx.Has("--repeat");
            object? value;
            JObj? timing = null;
            try
            {
                if (timed)
                    (value, timing) = RunTimed(ctx, ctx.Has("--warmup") ? ctx.Get<int>("--warmup") : 0,
                        ctx.Has("--repeat") ? ctx.Get<int>("--repeat") : 1);
                else
                    value = Operations.Execute(ctx);
            }
            catch (CliException) { throw; }
            catch (Exception e) { throw new CliException("operation-failed", e.Message, e); }
            object? summary;
            try { summary = Operations.Summarize(ctx, value); }
            catch (CliException) { throw; }
            catch (Exception e) { throw new CliException("operation-failed", e.Message, e); }
            exit = 0;
            document = JObj.Of("ok", true, "command", command, "implementation", Implementation,
                "result", summary, "timing", timing);
        }
        catch (CliException e)
        {
            exit = ExitCodes.GetValueOrDefault(e.Code, 1);
            document = ErrorEnvelope(known ? command : null, e.Code, e);
        }
        catch (Exception e)
        {
            exit = 1;
            document = ErrorEnvelope(known ? command : null, "operation-failed", e);
        }
        return (exit, CanonicalJson.Write(document) + "\n");
    }

    private static JObj ErrorEnvelope(string? command, string code, Exception e)
    {
        var reported = e is CliException { InnerException: { } cause } ? cause : e;
        return JObj.Of("ok", false, "command", command, "implementation", Implementation,
            "error", JObj.Of("code", code, "message", e.Message, "exception", reported.GetType().FullName));
    }

    internal static readonly JObj Implementation = JObj.Of(
        "name", "pdfcarton-dotnet",
        "library", "PdfCarton",
        "libraryVersion", LibraryVersion(),
        "runtime", ".NET " + Environment.Version.ToString());

    private static string LibraryVersion()
    {
        var info = typeof(DripSharp.PdfCarton.Pdmodel.PDDocument).Assembly
            .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
        if (info is null) return DripSharp.PdfCarton.Util.Version.GetVersion();
        var plus = info.IndexOf('+');
        return plus >= 0 ? info[..plus] : info;
    }

    // ------------------------------------------------------------------
    // Option parsing

    private static bool AsciiDigits(string s) => s.Length > 0 && s.All(c => c is >= '0' and <= '9');

    private static int ParseInt(string option, string text)
    {
        if (!AsciiDigits(text)) throw new CliException("usage", $"{option} needs a non-negative integer: {text}");
        if (!int.TryParse(text, NumberStyles.None, CultureInfo.InvariantCulture, out var v))
            throw new CliException("usage", $"{option} is out of range: {text}");
        return v;
    }

    private static (int Start, int End) ParseRange(string option, string text)
    {
        int Page(string s)
        {
            var n = ParseInt(option, s);
            if (n < 1) throw new CliException("usage", $"{option} pages start at 1: {text}");
            return n;
        }
        if (AsciiDigits(text))
        {
            var n = Page(text);
            return (n, n);
        }
        var dash = text.IndexOf('-');
        var from = dash >= 0 ? text[..dash] : null;
        var to = dash >= 0 ? text[(dash + 1)..] : null;
        if (from is null || to is null || (from.Length > 0 && !AsciiDigits(from)) || (to.Length > 0 && !AsciiDigits(to)))
            throw new CliException("usage", $"{option} has an invalid page range: {text}");
        if (from.Length == 0 && to.Length == 0)
            throw new CliException("usage", $"{option} has an empty page range: {text}");
        var a = from.Length > 0 ? Page(from) : 1;
        var b = to.Length > 0 ? Page(to) : int.MaxValue;
        if (a > b) throw new CliException("usage", $"{option} range ends before it starts: {text}");
        return (a, b);
    }

    private static float ParseDpi(string option, string text)
    {
        var dot = text.IndexOf('.');
        var valid = dot < 0 ? AsciiDigits(text) : AsciiDigits(text[..dot]) && AsciiDigits(text[(dot + 1)..]);
        if (!valid) throw new CliException("usage", $"{option} needs a positive decimal number: {text}");
        var v = float.Parse(text, NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture);
        if (!(v > 0f) || !float.IsFinite(v))
            throw new CliException("usage", $"{option} needs a positive decimal number: {text}");
        return v;
    }

    private static int ParseAngle(string option, string text)
    {
        var digits = text.StartsWith('-') ? text[1..] : text;
        if (!AsciiDigits(digits)) throw new CliException("usage", $"{option} needs an integer: {text}");
        if (!int.TryParse(text, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var v))
            throw new CliException("usage", $"{option} is out of range: {text}");
        if (v % 90 != 0) throw new CliException("usage", $"{option} must be a multiple of 90: {text}");
        return v;
    }

    private static HashSet<string> ParsePermissions(string option, string text)
    {
        var names = text.Length == 0 ? [] : text.Split(',');
        foreach (var n in names)
            if (!PermissionNames.Contains(n))
                throw new CliException("usage", $"{option} has an unknown permission: {n}");
        return [.. names];
    }

    private static object ParseValue(string kind, string option, string text) => kind switch
    {
        "string" => text,
        "int" => ParseInt(option, text),
        "range" => ParseRange(option, text),
        "ranges" => text.Split(',').Select(r => ParseRange(option, r)).ToList(),
        "dpi" => ParseDpi(option, text),
        "angle" => ParseAngle(option, text),
        "image-type" => ImageTypeNames.Contains(text)
            ? text
            : throw new CliException("usage", $"{option} must be one of argb, binary, gray, rgb: {text}"),
        "key-length" => text is "40" or "128" or "256"
            ? int.Parse(text, CultureInfo.InvariantCulture)
            : throw new CliException("usage", $"{option} must be one of 128, 256, 40: {text}"),
        "permissions" => ParsePermissions(option, text),
        _ => throw new InvalidOperationException(kind),
    };

    internal static Context ParseArgs(string command, string[] args)
    {
        var spec = Commands[command];
        var parsed = new Dictionary<string, object>();
        var positional = new List<string>();
        var onlyPositional = false;
        for (var i = 0; i < args.Length; i++)
        {
            var arg = args[i];
            if (onlyPositional) { positional.Add(arg); continue; }
            if (arg == "--") { onlyPositional = true; continue; }
            if (!arg.StartsWith("--", StringComparison.Ordinal)) { positional.Add(arg); continue; }
            if (!spec.Options.TryGetValue(arg, out var kind) && !TimingOptions.TryGetValue(arg, out kind))
                throw new CliException("usage", $"Unknown option for {command}: {arg}");
            if (parsed.ContainsKey(arg)) throw new CliException("usage", $"Repeated option: {arg}");
            if (kind == "flag") { parsed[arg] = true; continue; }
            if (i + 1 >= args.Length) throw new CliException("usage", $"Option needs a value: {arg}");
            parsed[arg] = ParseValue(kind, arg, args[++i]);
        }
        switch (spec.Arity)
        {
            case Arity.One when positional.Count != 1:
                throw new CliException("usage", $"{command} needs exactly one input file");
            case Arity.Many when positional.Count == 0:
                throw new CliException("usage", $"{command} needs at least one input file");
            case Arity.None when positional.Count > 0:
                throw new CliException("usage", $"{command} takes no input file");
        }
        if (parsed.TryGetValue("--repeat", out var repeat) && (int)repeat < 1)
            throw new CliException("usage", "--repeat must be at least 1");
        foreach (var r in spec.Required)
            if (!parsed.ContainsKey(r)) throw new CliException("usage", $"{command} needs {r}");
        if (command == "render" && !parsed.ContainsKey("--no-write") && !parsed.ContainsKey("--out-dir"))
            throw new CliException("usage", "render needs --out-dir unless --no-write is given");
        if (command == "render" && parsed.ContainsKey("--no-write") && parsed.ContainsKey("--out-dir"))
            throw new CliException("usage", "render takes either --out-dir or --no-write");
        return new Context { Command = command, Options = parsed, Positional = positional };
    }

    // ------------------------------------------------------------------
    // Input and output checks (untimed)

    private static void CheckInput(string path)
    {
        if (!File.Exists(path)) throw new CliException("io", $"Cannot read input file: {path}");
        try
        {
            using var _ = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        }
        catch (Exception e)
        {
            throw new CliException("io", $"Cannot read input file: {path}", e);
        }
    }

    private static void Prepare(Context ctx)
    {
        foreach (var p in ctx.Positional) CheckInput(p);
        if (ctx.Get<string>("--out-dir") is { } dir)
        {
            try { Directory.CreateDirectory(dir); }
            catch (Exception e) { throw new CliException("io", $"Cannot create output directory: {dir}", e); }
            if (!Directory.Exists(dir)) throw new CliException("io", $"Cannot create output directory: {dir}");
        }
        if (ctx.Get<string>("--out") is { } output)
        {
            var parent = Path.GetDirectoryName(Path.GetFullPath(output));
            if (parent is null || !Directory.Exists(parent))
                throw new CliException("io", $"Output directory does not exist: {parent}");
        }
        if (ctx.Command == "form-fill") ctx.Values = ReadValues(ctx.Get<string>("--values")!);
    }

    private static SortedDictionary<string, object> ReadValues(string path)
    {
        byte[] data;
        try
        {
            if (!File.Exists(path)) throw new FileNotFoundException(path);
            data = File.ReadAllBytes(path);
        }
        catch (Exception e)
        {
            throw new CliException("io", $"Cannot read values file: {path}", e);
        }
        var start = data.Length >= 3 && data[0] == 0xEF && data[1] == 0xBB && data[2] == 0xBF ? 3 : 0;
        JsonDocument json;
        try
        {
            json = JsonDocument.Parse(data.AsMemory(start));
        }
        catch (Exception e)
        {
            throw new CliException("usage", $"Invalid values JSON: {e.Message}", e);
        }
        using (json)
        {
            if (json.RootElement.ValueKind != JsonValueKind.Object)
                throw new CliException("usage", "The values file must hold a JSON object");
            var values = new SortedDictionary<string, object>(StringComparer.Ordinal);
            foreach (var property in json.RootElement.EnumerateObject())
            {
                if (values.ContainsKey(property.Name))
                    throw new CliException("usage", $"Invalid values JSON: duplicate field {property.Name}");
                var v = property.Value;
                if (v.ValueKind == JsonValueKind.String)
                    values[property.Name] = v.GetString()!;
                else if (v.ValueKind == JsonValueKind.Array &&
                         v.EnumerateArray().All(item => item.ValueKind == JsonValueKind.String))
                    values[property.Name] = v.EnumerateArray().Select(item => item.GetString()!).ToList();
                else
                    throw new CliException("usage", $"Value of {property.Name} must be a string or an array of strings");
            }
            return values;
        }
    }

    // ------------------------------------------------------------------
    // Timing mode

    private static (object? Value, JObj Timing) RunTimed(Context ctx, int warmup, int repeat)
    {
        for (var i = 0; i < warmup; i++) Operations.Execute(ctx);
        object? last = null;
        var nanos = new long[repeat];
        var bytes = new long[repeat];
        var allThreads = new long[repeat];
        for (var i = 0; i < repeat; i++)
        {
            last = null;
            var g0 = GC.GetTotalAllocatedBytes();
            var a0 = GC.GetAllocatedBytesForCurrentThread();
            var t0 = Stopwatch.GetTimestamp();
            var value = Operations.Execute(ctx);
            var t1 = Stopwatch.GetTimestamp();
            var a1 = GC.GetAllocatedBytesForCurrentThread();
            var g1 = GC.GetTotalAllocatedBytes();
            last = value;
            nanos[i] = (long)((t1 - t0) * (1.0e9 / Stopwatch.Frequency));
            bytes[i] = a1 - a0;
            allThreads[i] = g1 - g0;
        }
        // The contract's counter is the executing thread's; the process-wide
        // counter goes to stderr so a multithreaded operation is visible.
        Console.Error.WriteLine("pdfcli: allocatedBytes counts GC.GetAllocatedBytesForCurrentThread(); " +
            "GC.GetTotalAllocatedBytes() (all threads, approximate) per iteration: " + string.Join(",", allThreads));
        var sortedNanos = nanos.Order().ToArray();
        var sortedBytes = bytes.Order().ToArray();
        var m = repeat / 2;
        double medianNanos = repeat % 2 == 1
            ? sortedNanos[m]
            : (sortedNanos[m - 1] + sortedNanos[m]) / 2.0;
        var medianBytes = repeat % 2 == 1
            ? sortedBytes[m]
            : sortedBytes[m - 1] + (sortedBytes[m] - sortedBytes[m - 1]) / 2;
        var timing = JObj.Of(
            "warmup", warmup,
            "repeat", repeat,
            "iterationsMs", nanos.Select(n => Millis.FromNanos(n)).ToList(),
            "medianMs", Millis.FromNanos(medianNanos),
            "allocatedBytes", bytes.ToList(),
            "medianAllocatedBytes", medianBytes);
        return (last, timing);
    }
}
