using System;
using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Pkl.Core;
using Pkl.Core.Project;
using Pkl.Core.Util;
using PklHttpClient = Pkl.Core.Http.HttpClient;

static class Program
{
    const string ResultMagic = "VIBEFORMER_LANGUAGE_SNIPPET_PACKAGE_RESULTS_V1";
    const string WorkerMagic = "VIBEFORMER_LANGUAGE_SNIPPET_WORKER_V1";
    const int ExpectedCases = 940;
    const string ApprovedExclusion = "outside-epic-approved-exclusion";

    static readonly UTF8Encoding Utf8 = new(false);
    static readonly Regex LineNumber = new(@"(?m)^(( ║ )*)(\d+) \|");
    static readonly Regex LocationLineNumber = new(@"#L(\d+)");
    static readonly Regex ReflectedLineNumber = new(@"line = (\d+)");

    sealed record ContractCase(string Id, string Input, string ProductScope);
    sealed record CaseResult(
        string Id,
        string Status,
        string PayloadBase64,
        string LoggerBase64,
        string DiagnosticBase64);

    public static async Task<int> Main(string[] args)
    {
        try
        {
            if (args.Length > 0 && args[0] == "--worker")
                return RunWorker(args.Skip(1).ToArray());
            if (args.Length != 8)
                throw new ArgumentException(
                    "manifest, output, snippets, package build, assembly manifest, " +
                    "evaluation timeout, process timeout, and worker count are required");
            await RunController(args).ConfigureAwait(false);
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    static async Task RunController(string[] args)
    {
        string manifest = Path.GetFullPath(args[0]);
        string output = Path.GetFullPath(args[1]);
        string snippets = Path.GetFullPath(args[2]);
        string packageBuild = Path.GetFullPath(args[3]);
        string assemblyManifest = Path.GetFullPath(args[4]);
        int evaluationTimeoutMs = PositiveInt(args[5], "evaluation timeout");
        int processTimeoutMs = PositiveInt(args[6], "process timeout");
        int workerCount = PositiveInt(args[7], "worker count");
        Require(processTimeoutMs > evaluationTimeoutMs,
            "process timeout must exceed evaluator timeout");
        Require(File.Exists(Path.Combine(packageBuild, "keystore", "localhost.pem")),
            "test certificate is missing");
        Require(Directory.Exists(Path.Combine(packageBuild, "test-packages")),
            "test package service fixtures are missing");

        IReadOnlyList<ContractCase> cases = ReadCases(manifest, snippets);
        VerifyPackedAssemblies(assemblyManifest);
        using var server = new PackageServer(packageBuild);
        int port = server.Port;
        using var slots = new SemaphoreSlim(workerCount, workerCount);
        var tasks = cases.Select(async contractCase =>
        {
            if (contractCase.ProductScope == ApprovedExclusion)
                return new CaseResult(contractCase.Id, "APPROVED_EXCLUSION", "", "", "");
            await slots.WaitAsync().ConfigureAwait(false);
            try
            {
                return await RunCaseProcess(
                    contractCase, snippets, packageBuild, assemblyManifest, port,
                    evaluationTimeoutMs, processTimeoutMs).ConfigureAwait(false);
            }
            finally
            {
                slots.Release();
            }
        }).ToArray();
        CaseResult[] results = await Task.WhenAll(tasks).ConfigureAwait(false);
        WriteResults(output, results);
        Console.WriteLine(
            $"Package-only language-snippet runner completed {results.Length} manifest rows " +
            $"({results.Count(result => result.Status != "APPROVED_EXCLUSION")} executed)." );
    }

    static int RunWorker(string[] args)
    {
        if (args.Length != 8)
            throw new ArgumentException(
                "worker case id, input, snippets, projects, package build, assembly manifest, " +
                "test port, and evaluator timeout are required");
        string caseId = args[0];
        string input = Path.GetFullPath(args[1]);
        string snippets = Path.GetFullPath(args[2]);
        string projects = Path.GetFullPath(args[3]);
        string packageBuild = Path.GetFullPath(args[4]);
        string assemblyManifest = Path.GetFullPath(args[5]);
        int testPort = PositiveInt(args[6], "test port");
        int evaluationTimeoutMs = PositiveInt(args[7], "evaluation timeout");
        CaseResult result;
        try
        {
            VerifyPackedAssemblies(assemblyManifest);
            EnablePklTestMode();
            result = EvaluateCase(
                caseId, input, snippets, projects, packageBuild, testPort, evaluationTimeoutMs);
        }
        catch (Exception error)
        {
            result = Crash(caseId, NormalizeDiagnostic(error.ToString(), snippets));
        }
        Console.WriteLine(string.Join('\t', new[]
        {
            WorkerMagic, result.Id, result.Status, result.PayloadBase64,
            result.LoggerBase64, result.DiagnosticBase64
        }));
        return 0;
    }

    static CaseResult EvaluateCase(
        string caseId,
        string input,
        string snippets,
        string projects,
        string packageBuild,
        int testPort,
        int evaluationTimeoutMs)
    {
        Require(File.Exists(input), $"input does not exist: {input}");
        Require(IsUnder(Path.Combine(snippets, "input"), input),
            $"input escaped the pinned fixture layout: {input}");
        var environment = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["NAME1"] = "value1",
            ["NAME2"] = "value2",
            ["/foo/bar"] = "foobar",
            ["foo bar"] = "foo bar",
            ["file:///foo/bar"] = "file:///foo/bar"
        };
        var properties = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["name1"] = "value1",
            ["name2"] = "value2",
            ["/foo/bar"] = "foobar"
        };
        var logWriter = new StringWriter(CultureInfo.InvariantCulture);
        TimeSpan timeout = TimeSpan.FromMilliseconds(evaluationTimeoutMs);
        string fixtureModulePath = Path.GetFullPath(
            Path.Combine(snippets, "..", "..", "resources"));
        Require(Directory.Exists(fixtureModulePath),
            $"language-snippet module-path fixtures are missing: {fixtureModulePath}");
        using var fixtureModulePathResolver =
            new Pkl.Core.Module.ModulePathResolver(new[] { fixtureModulePath });
        EvaluatorBuilder builder = EvaluatorBuilder.Preconfigured()
            .SetLogger(Loggers.Writer(logWriter))
            .SetStackFrameTransformer(frame => frame)
            .SetEnvironmentVariables(environment)
            .SetExternalProperties(properties)
            .SetModuleCacheDir(null)
            .SetHttpClient(CreateTestHttpClient(packageBuild, testPort))
            .SetPowerAssertionsEnabled(true)
            .SetTimeout(timeout);
        var moduleKeyFactories = new List<Pkl.Core.Module.ModuleKeyFactory>
        {
            Pkl.Core.Module.ModuleKeyFactories.CreateModulePath(fixtureModulePathResolver)
        };
        moduleKeyFactories.AddRange(builder.GetModuleKeyFactories());
        builder.SetModuleKeyFactories(moduleKeyFactories);
        var resourceReaders = new List<Pkl.Core.Resource.ResourceReader>
        {
            Pkl.Core.Resource.ResourceReaders.ModulePath(fixtureModulePathResolver)
        };
        resourceReaders.AddRange(builder.GetResourceReaders());
        builder.SetResourceReaders(resourceReaders);

        string output;
        bool success;
        string status;
        string diagnostic = "";
        try
        {
            if (IsUnder(projects, input))
            {
                string? projectDirectory = FindProjectDirectory(Path.GetDirectoryName(input)!);
                if (projectDirectory is not null)
                {
                    Project project = Project.LoadFromPath(
                        Path.Combine(projectDirectory, "PklProject"),
                        SecurityManagers.DefaultManager,
                        timeout,
                        frame => frame,
                        new Dictionary<string, string>(),
                        true);
                    builder.UnsetSecurityManager().ApplyFromProject(project);
                }
            }
            using Evaluator evaluator = builder.Build();
            byte[] bytes = evaluator.EvaluateOutputBytes(ModuleSource.PathFromPath(input));
            output = DecodeOutput(bytes, input);
            output = StripLineNumbers(output);
            success = true;
        }
        catch (PklBugException error)
        {
            output = error.ToString();
            success = false;
        }
        catch (PklException error)
        {
            output = StripVersionCheck(StripLineNumbers(error.Message ?? ""));
            success = false;
            if (output.StartsWith("Evaluation timed out after ", StringComparison.Ordinal))
            {
                string normalizedTimeout = NormalizeFinal(output, snippets);
                return new CaseResult(
                    caseId, "TIMEOUT", Encode(normalizedTimeout), "", Encode(normalizedTimeout));
            }
        }

        string logger = NormalizeLineEndings(logWriter.ToString());
        status = success && string.IsNullOrWhiteSpace(logger) ? "SUCCESS" : "ERROR";
        string normalizedLogger = NormalizeFinal(logger, snippets);
        string payload = NormalizeFinal(output + logger, snippets);
        return new CaseResult(
            caseId, status, Encode(payload), Encode(normalizedLogger), Encode(diagnostic));
    }

    static async Task<CaseResult> RunCaseProcess(
        ContractCase contractCase,
        string snippets,
        string packageBuild,
        string assemblyManifest,
        int testPort,
        int evaluationTimeoutMs,
        int processTimeoutMs)
    {
        string projects = Path.Combine(snippets, "input", "projects");
        var start = new ProcessStartInfo
        {
            FileName = Environment.ProcessPath ?? throw new InvalidOperationException(
                "current process path is unavailable"),
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            WorkingDirectory = AppContext.BaseDirectory
        };
        if (Path.GetFileNameWithoutExtension(start.FileName)
            .Equals("dotnet", StringComparison.OrdinalIgnoreCase))
            start.ArgumentList.Add(Assembly.GetEntryAssembly()!.Location);
        foreach (string argument in new[]
        {
            "--worker", contractCase.Id, contractCase.Input, snippets, projects, packageBuild,
            assemblyManifest, testPort.ToString(CultureInfo.InvariantCulture),
            evaluationTimeoutMs.ToString(CultureInfo.InvariantCulture)
        }) start.ArgumentList.Add(argument);

        using var process = new Process { StartInfo = start };
        Require(process.Start(), $"could not start worker for {contractCase.Id}");
        Task<string> stdoutTask = process.StandardOutput.ReadToEndAsync();
        Task<string> stderrTask = process.StandardError.ReadToEndAsync();
        using var cancellation = new CancellationTokenSource(processTimeoutMs);
        try
        {
            await process.WaitForExitAsync(cancellation.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            try { process.Kill(entireProcessTree: true); }
            catch (InvalidOperationException) { }
            await process.WaitForExitAsync().ConfigureAwait(false);
            _ = await stdoutTask.ConfigureAwait(false);
            _ = await stderrTask.ConfigureAwait(false);
            string diagnostic = $"Process timeout after {processTimeoutMs} ms.";
            return new CaseResult(contractCase.Id, "TIMEOUT", "", "", Encode(diagnostic));
        }

        string stdout = NormalizeLineEndings(await stdoutTask.ConfigureAwait(false));
        string stderr = NormalizeLineEndings(await stderrTask.ConfigureAwait(false));
        string[] workerLines = stdout.Split('\n')
            .Where(line => line.StartsWith(WorkerMagic + "\t", StringComparison.Ordinal))
            .ToArray();
        string unexpectedStdout = string.Join("\n", stdout.Split('\n')
            .Where(line => line.Length > 0 &&
                           !line.StartsWith(WorkerMagic + "\t", StringComparison.Ordinal)));
        if (process.ExitCode != 0 || workerLines.Length != 1 ||
            unexpectedStdout.Length > 0 || !string.IsNullOrWhiteSpace(stderr))
        {
            string diagnostic = NormalizeDiagnostic(
                $"Worker exit {process.ExitCode}.\nstdout:\n{stdout}\nstderr:\n{stderr}", snippets);
            return Crash(contractCase.Id, diagnostic);
        }

        string[] fields = workerLines[0].Split('\t');
        if (fields.Length != 6 || fields[1] != contractCase.Id ||
            !new[] { "SUCCESS", "ERROR", "TIMEOUT", "CRASH" }.Contains(fields[2], StringComparer.Ordinal) ||
            !IsBase64(fields[3]) || !IsBase64(fields[4]) || !IsBase64(fields[5]))
        {
            return Crash(contractCase.Id,
                NormalizeDiagnostic("Malformed worker protocol:\n" + workerLines[0], snippets));
        }
        return new CaseResult(fields[1], fields[2], fields[3], fields[4], fields[5]);
    }

    static IReadOnlyList<ContractCase> ReadCases(string manifest, string snippets)
    {
        string[]? columns = null;
        var cases = new List<ContractCase>();
        string upstream = Path.GetFullPath(Path.Combine(snippets, "..", "..", "..", "..", ".."));
        foreach (string line in File.ReadLines(manifest, Utf8))
        {
            string[] fields = line.Split('\t');
            if (fields.Length == 0) continue;
            if (fields[0] == "columns")
            {
                Require(columns is null, "manifest repeats its columns row");
                columns = fields.Skip(1).ToArray();
            }
            else if (fields[0] == "case")
            {
                Require(columns is not null && fields.Length == columns.Length + 1,
                    "malformed manifest case row");
                string[] currentColumns = columns
                    ?? throw new InvalidDataException("manifest case appears before its columns row");
                var row = currentColumns.Select((name, index) => (name, value: fields[index + 1]))
                    .ToDictionary(item => item.name, item => item.value, StringComparer.Ordinal);
                string id = Required(row, "case-id");
                string input = Path.GetFullPath(Path.Combine(upstream, Required(row, "input-path")));
                string scope = Required(row, "product-scope");
                Require(File.Exists(input), $"manifest input does not exist: {input}");
                Require(scope is "in-scope" or "in-scope-mixed-excluded-surface" or
                    ApprovedExclusion, $"manifest case has invalid scope: {id}");
                cases.Add(new ContractCase(id, input, scope));
            }
        }
        Require(cases.Count == ExpectedCases,
            $"expected {ExpectedCases} manifest cases but found {cases.Count}");
        Require(cases.Select(item => item.Id).Distinct(StringComparer.Ordinal).Count() == ExpectedCases,
            "manifest contains duplicate case identities");
        Require(cases.Count(item => item.ProductScope != ApprovedExclusion) == 909,
            "manifest in-scope count drifted");
        return cases;
    }

    static string Required(IReadOnlyDictionary<string, string> row, string name) =>
        row.TryGetValue(name, out string? value) && value.Length > 0
            ? value
            : throw new InvalidDataException($"manifest omits {name}");

    static void WriteResults(string output, IReadOnlyList<CaseResult> results)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(output)!);
        string temporary = output + ".tmp";
        try
        {
            using (var writer = new StreamWriter(temporary, false, Utf8))
            {
                writer.WriteLine(ResultMagic);
                writer.WriteLine(
                    "columns\tcase-id\tstatus\tnormalized-payload-base64\tlogger-base64\tdiagnostic-base64");
                foreach (CaseResult result in results)
                    writer.WriteLine(string.Join('\t', new[]
                    {
                        "case", result.Id, result.Status, result.PayloadBase64,
                        result.LoggerBase64, result.DiagnosticBase64
                    }));
            }
            File.Move(temporary, output, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }

    static void VerifyPackedAssemblies(string manifest)
    {
        string baseDirectory = Path.GetFullPath(AppContext.BaseDirectory)
            .TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        string[] lines = File.ReadAllLines(manifest, Utf8);
        Require(lines.Length == 2, "packed assembly manifest must contain parser and core");
        foreach (string line in lines)
        {
            string[] fields = line.Split('\t');
            Require(fields.Length == 2 && fields[1].Length == 64,
                "malformed packed assembly manifest row");
            Assembly assembly = Assembly.Load(new AssemblyName(fields[0]));
            string location = Path.GetFullPath(assembly.Location);
            Require(location.StartsWith(baseDirectory, StringComparison.Ordinal),
                $"loaded assembly escaped isolated consumer output: {location}");
            using FileStream stream = File.OpenRead(location);
            string actual = Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
            Require(actual == fields[1],
                $"loaded {fields[0]} does not match its exact packed assembly");
        }
    }

    static void EnablePklTestMode()
    {
        Type compat = typeof(Evaluator).Assembly.GetType("Vibeformer.Runtime.JavaCompat", true)!;
        MethodInfo method = compat.GetMethod(
            "SetProperty", BindingFlags.Static | BindingFlags.NonPublic,
            binder: null, new[] { typeof(string), typeof(string) }, modifiers: null)
            ?? throw new MissingMethodException(compat.FullName, "SetProperty");
        _ = method.Invoke(null, new object[] { "org.pkl.testMode", "true" });
    }

    static PklHttpClient CreateTestHttpClient(string packageBuild, int testPort)
    {
        PklHttpClient.Builder builder = PklHttpClient.CreateBuilder();
        MethodInfo setTestPort = builder.GetType().GetMethod(
            "SetTestPort", BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
            binder: null, new[] { typeof(int) }, modifiers: null)
            ?? throw new MissingMethodException(builder.GetType().FullName, "SetTestPort");
        _ = setTestPort.Invoke(builder, new object[] { testPort });
        return builder
            .AddCertificates(Path.Combine(packageBuild, "keystore", "localhost.pem"))
            .BuildLazily();
    }

    static string? FindProjectDirectory(string start)
    {
        for (DirectoryInfo? current = new(start); current is not null; current = current.Parent)
            if (File.Exists(Path.Combine(current.FullName, "PklProject"))) return current.FullName;
        return null;
    }

    static string DecodeOutput(byte[] bytes, string input)
    {
        return Regex.IsMatch(input, @"[.]msgpack[.]yaml[.]pkl$")
            ? new MessagePackDebugRenderer(bytes).Render()
            : Utf8.GetString(bytes);
    }

    static string NormalizeFinal(string value, string snippets)
    {
        value = NormalizeLineEndings(value);
        string normalizedSnippets = Path.GetFullPath(snippets).Replace('\\', '/');
        string replacement = Path.GetPathRoot(normalizedSnippets) == "/"
            ? "/$snippetsDir"
            : "$snippetsDir";
        value = value.Replace(normalizedSnippets, replacement, StringComparison.Ordinal);
        Release release = Release.Current();
        value = value.Replace(
            release.DocumentationValue.Homepage, "https://$pklWebsite/", StringComparison.Ordinal);
        string commitish = release.Version.IsNormal()
            ? release.Version.ToString()
            : release.CommitId;
        value = value.Replace(
            $"https://github.com/apple/pkl/blob/{commitish}/stdlib/",
            "https://github.com/apple/pkl/blob/$commitId/stdlib/",
            StringComparison.Ordinal);
        return value;
    }

    static string NormalizeDiagnostic(string value, string snippets) =>
        NormalizeFinal(StripVersionCheck(StripLineNumbers(value)), snippets);

    static string StripLineNumbers(string value)
    {
        value = LineNumber.Replace(value, match =>
            match.Groups[1].Value + new string('x', match.Groups[3].Value.Length) + " |");
        value = LocationLineNumber.Replace(value, match =>
            "#L" + new string('X', match.Groups[1].Value.Length));
        return ReflectedLineNumber.Replace(value, match =>
            "line = " + new string('X', match.Groups[1].Value.Length));
    }

    static string StripVersionCheck(string value) => value.Replace(
        $"Pkl version is {Release.Current().Version}",
        "Pkl version is xxx",
        StringComparison.Ordinal);

    static string NormalizeLineEndings(string value) =>
        value.Replace("\r\n", "\n", StringComparison.Ordinal).Replace('\r', '\n');

    static CaseResult Crash(string id, string diagnostic) =>
        new(id, "CRASH", "", "", Encode(diagnostic));

    static string Encode(string value) => Convert.ToBase64String(Utf8.GetBytes(value));

    static bool IsBase64(string value)
    {
        try { _ = Convert.FromBase64String(value); return true; }
        catch (FormatException) { return false; }
    }

    static int PositiveInt(string value, string name) =>
        int.TryParse(value, NumberStyles.None, CultureInfo.InvariantCulture, out int parsed) && parsed > 0
            ? parsed
            : throw new ArgumentException($"{name} must be a positive integer");

    static bool IsUnder(string root, string path)
    {
        string normalizedRoot = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) +
            Path.DirectorySeparatorChar;
        string normalizedPath = Path.GetFullPath(path);
        return normalizedPath.StartsWith(normalizedRoot, StringComparison.Ordinal);
    }

    static void Require(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }

    sealed class PackageServer : IDisposable
    {
        readonly string packageBuild;
        readonly TcpListener listener = new(IPAddress.Loopback, 0);
        readonly CancellationTokenSource cancellation = new();
        readonly X509Certificate2 certificate;
        readonly ConcurrentBag<Task> connections = new();
        readonly Task acceptLoop;
        int disposed;

        internal PackageServer(string packageBuild)
        {
            this.packageBuild = packageBuild;
#pragma warning disable SYSLIB0057
            certificate = new X509Certificate2(
                Path.Combine(packageBuild, "keystore", "localhost.p12"),
                "password",
                X509KeyStorageFlags.Exportable);
#pragma warning restore SYSLIB0057
            listener.Start();
            acceptLoop = Task.Run(AcceptLoop);
        }

        internal int Port => ((IPEndPoint)listener.LocalEndpoint).Port;

        async Task AcceptLoop()
        {
            try
            {
                while (!cancellation.IsCancellationRequested)
                {
                    TcpClient client = await listener.AcceptTcpClientAsync(cancellation.Token)
                        .ConfigureAwait(false);
                    Task task = Task.Run(() => Handle(client));
                    connections.Add(task);
                }
            }
            catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { }
            catch (ObjectDisposedException) when (cancellation.IsCancellationRequested) { }
            catch (SocketException) when (cancellation.IsCancellationRequested) { }
        }

        void Handle(TcpClient client)
        {
            try
            {
                using (client)
                using (var ssl = new SslStream(client.GetStream()))
                {
                    ssl.AuthenticateAsServer(new SslServerAuthenticationOptions
                    {
                        ServerCertificate = certificate,
                        EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13
                    });
                    using var reader = new StreamReader(
                        ssl, Encoding.ASCII, false, 4096, leaveOpen: true);
                    string requestLine = reader.ReadLine() ?? throw new IOException("Missing request line.");
                    string[] request = requestLine.Split(' ', 3);
                    for (string? line = reader.ReadLine(); !string.IsNullOrEmpty(line); line = reader.ReadLine()) { }
                    if (request.Length < 2 || request[0] != "GET")
                    {
                        Send(ssl, 405, Array.Empty<byte>());
                        return;
                    }
                    string path = new Uri("https://localhost" + request[1]).AbsolutePath;
                    if (path.StartsWith("/HTTP301/", StringComparison.Ordinal))
                    {
                        Send(ssl, 301, Array.Empty<byte>(),
                            "Location: " + path[8..] + "\r\n");
                        return;
                    }
                    if (path.StartsWith("/HTTP307/", StringComparison.Ordinal))
                    {
                        Send(ssl, 307, Array.Empty<byte>(),
                            "Location: " + path[8..] + "\r\n");
                        return;
                    }
                    string relative = Uri.UnescapeDataString(path.TrimStart('/'));
                    string packageRoot = Path.GetFullPath(Path.Combine(packageBuild, "test-packages"));
                    string local = relative.EndsWith(".zip", StringComparison.Ordinal)
                        ? Path.Combine(packageRoot, relative)
                        : Path.Combine(packageRoot, relative, relative + ".json");
                    local = Path.GetFullPath(local);
                    if (!IsUnder(packageRoot, local) || !File.Exists(local))
                    {
                        Send(ssl, 404, Array.Empty<byte>());
                        return;
                    }
                    Send(ssl, 200, File.ReadAllBytes(local));
                }
            }
            catch (Exception) when (cancellation.IsCancellationRequested) { }
        }

        static void Send(Stream stream, int status, byte[] body, string extraHeaders = "")
        {
            string reason = status switch
            {
                200 => "OK",
                301 => "Moved Permanently",
                307 => "Temporary Redirect",
                404 => "Not Found",
                405 => "Method Not Allowed",
                _ => "Error"
            };
            byte[] headers = Encoding.ASCII.GetBytes(
                $"HTTP/1.1 {status} {reason}\r\n" + extraHeaders +
                $"Content-Length: {body.Length}\r\nConnection: close\r\n\r\n");
            stream.Write(headers);
            stream.Write(body);
            stream.Flush();
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref disposed, 1) != 0) return;
            cancellation.Cancel();
            listener.Stop();
            try { acceptLoop.GetAwaiter().GetResult(); }
            catch (Exception error) when (error is SocketException or ObjectDisposedException) { }
            try { Task.WaitAll(connections.ToArray(), TimeSpan.FromSeconds(5)); }
            catch (AggregateException error) when (error.InnerExceptions.All(
                inner => inner is SocketException or ObjectDisposedException or IOException)) { }
            cancellation.Dispose();
            certificate.Dispose();
        }
    }
}

sealed class MessagePackDebugRenderer
{
    readonly byte[] bytes;
    readonly StringBuilder output = new();
    readonly StringBuilder currentIndent = new();
    int offset;

    internal MessagePackDebugRenderer(byte[] bytes) => this.bytes = bytes;

    internal string Render()
    {
        RenderValue();
        return output.ToString().StartsWith("\n", StringComparison.Ordinal)
            ? output.ToString()[1..]
            : output.ToString();
    }

    void Newline() => output.Append('\n').Append(currentIndent);
    void IncrementIndent() => currentIndent.Append("  ");
    void DecrementIndent() => currentIndent.Length -= 2;

    void RenderKey()
    {
        byte code = Peek();
        if (IsString(code)) EmitString(ReadString());
        else if (IsMap(code) || IsArray(code))
        {
            output.Append("? ");
            IncrementIndent();
            RenderValue();
            DecrementIndent();
            Newline();
        }
        else RenderValue();
        output.Append(": ");
    }

    void RenderValue()
    {
        byte code = Peek();
        if (code <= 0x7f) { output.Append(ReadByte().ToString(CultureInfo.InvariantCulture)); return; }
        if (code >= 0xe0) { output.Append(unchecked((sbyte)ReadByte()).ToString(CultureInfo.InvariantCulture)); return; }
        if (IsString(code)) { EmitString(ReadString()); return; }
        if (IsArray(code))
        {
            int size = ReadArrayHeader();
            if (size == 0) { output.Append("[]"); return; }
            for (int index = 0; index < size; index++)
            {
                Newline();
                output.Append("- ");
                IncrementIndent();
                RenderValue();
                DecrementIndent();
            }
            return;
        }
        if (IsMap(code))
        {
            int size = ReadMapHeader();
            if (size == 0) { output.Append("{}"); return; }
            for (int index = 0; index < size; index++)
            {
                Newline();
                RenderKey();
                IncrementIndent();
                RenderValue();
                DecrementIndent();
            }
            return;
        }
        switch (ReadByte())
        {
            case 0xc0: output.Append("null"); return;
            case 0xc2: output.Append("false"); return;
            case 0xc3: output.Append("true"); return;
            case 0xc4: RenderBinary(ReadByte()); return;
            case 0xc5: RenderBinary(ReadUInt16()); return;
            case 0xc6: RenderBinary(CheckedLength(ReadUInt32())); return;
            case 0xca: output.Append(FormatFloat(ReadSingle())); return;
            case 0xcb: output.Append(FormatFloat(ReadDouble())); return;
            case 0xcc: output.Append(ReadByte().ToString(CultureInfo.InvariantCulture)); return;
            case 0xcd: output.Append(ReadUInt16().ToString(CultureInfo.InvariantCulture)); return;
            case 0xce: output.Append(ReadUInt32().ToString(CultureInfo.InvariantCulture)); return;
            case 0xcf: output.Append(ReadUInt64().ToString(CultureInfo.InvariantCulture)); return;
            case 0xd0: output.Append(unchecked((sbyte)ReadByte()).ToString(CultureInfo.InvariantCulture)); return;
            case 0xd1: output.Append(ReadInt16().ToString(CultureInfo.InvariantCulture)); return;
            case 0xd2: output.Append(ReadInt32().ToString(CultureInfo.InvariantCulture)); return;
            case 0xd3: output.Append(ReadInt64().ToString(CultureInfo.InvariantCulture)); return;
            case 0xc7 or 0xc8 or 0xc9 or 0xd4 or 0xd5 or 0xd6 or 0xd7 or 0xd8:
                throw new InvalidDataException("Unexpected MessagePack extension value.");
            default: throw new InvalidDataException($"Unsupported MessagePack code 0x{code:x2}.");
        }
    }

    void RenderBinary(int size)
    {
        output.Append("!!binary ");
        EmitString(Convert.ToBase64String(ReadBytes(size)));
    }

    string ReadString()
    {
        byte code = ReadByte();
        int size = code switch
        {
            >= 0xa0 and <= 0xbf => code & 0x1f,
            0xd9 => ReadByte(),
            0xda => ReadUInt16(),
            0xdb => CheckedLength(ReadUInt32()),
            _ => throw new InvalidDataException($"Expected MessagePack string, found 0x{code:x2}.")
        };
        return new UTF8Encoding(false, true).GetString(ReadBytes(size));
    }

    int ReadArrayHeader()
    {
        byte code = ReadByte();
        return code switch
        {
            >= 0x90 and <= 0x9f => code & 0x0f,
            0xdc => ReadUInt16(),
            0xdd => CheckedLength(ReadUInt32()),
            _ => throw new InvalidDataException($"Expected MessagePack array, found 0x{code:x2}.")
        };
    }

    int ReadMapHeader()
    {
        byte code = ReadByte();
        return code switch
        {
            >= 0x80 and <= 0x8f => code & 0x0f,
            0xde => ReadUInt16(),
            0xdf => CheckedLength(ReadUInt32()),
            _ => throw new InvalidDataException($"Expected MessagePack map, found 0x{code:x2}.")
        };
    }

    void EmitString(string value)
    {
        int newline = value.IndexOf('\n');
        if (newline < 0) EmitSingleLineString(value);
        else EmitMultiLineString(value, newline);
    }

    void EmitSingleLineString(string value)
    {
        output.Append('\'');
        output.Append(value.Replace("'", "''", StringComparison.Ordinal));
        output.Append('\'');
    }

    void EmitMultiLineString(string value, int newlineIndex)
    {
        currentIndent.Append("  ");
        output.Append('|');
        if (value[0] == ' ') output.Append('2');
        if (value[^1] == '\n')
        {
            if (value.Length == 1 || value[^2] == '\n') output.Append('+');
        }
        else output.Append('-');
        output.Append('\n');
        int start = 0;
        for (int index = newlineIndex; index < value.Length; index++)
        {
            if (value[index] != '\n') continue;
            if (index == start) output.Append('\n');
            else output.Append(currentIndent).Append(value, start, index - start + 1);
            start = index + 1;
        }
        if (start < value.Length) output.Append(currentIndent).Append(value, start, value.Length - start);
        currentIndent.Length -= 2;
    }

    static bool IsString(byte code) => code is >= 0xa0 and <= 0xbf or 0xd9 or 0xda or 0xdb;
    static bool IsArray(byte code) => code is >= 0x90 and <= 0x9f or 0xdc or 0xdd;
    static bool IsMap(byte code) => code is >= 0x80 and <= 0x8f or 0xde or 0xdf;

    byte Peek()
    {
        RequireAvailable(1);
        return bytes[offset];
    }

    byte ReadByte()
    {
        RequireAvailable(1);
        return bytes[offset++];
    }

    byte[] ReadBytes(int count)
    {
        RequireAvailable(count);
        byte[] value = bytes.AsSpan(offset, count).ToArray();
        offset += count;
        return value;
    }

    ushort ReadUInt16() { RequireAvailable(2); ushort value = BinaryPrimitives.ReadUInt16BigEndian(bytes.AsSpan(offset)); offset += 2; return value; }
    uint ReadUInt32() { RequireAvailable(4); uint value = BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(offset)); offset += 4; return value; }
    ulong ReadUInt64() { RequireAvailable(8); ulong value = BinaryPrimitives.ReadUInt64BigEndian(bytes.AsSpan(offset)); offset += 8; return value; }
    short ReadInt16() { RequireAvailable(2); short value = BinaryPrimitives.ReadInt16BigEndian(bytes.AsSpan(offset)); offset += 2; return value; }
    int ReadInt32() { RequireAvailable(4); int value = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(offset)); offset += 4; return value; }
    long ReadInt64() { RequireAvailable(8); long value = BinaryPrimitives.ReadInt64BigEndian(bytes.AsSpan(offset)); offset += 8; return value; }
    float ReadSingle() => BitConverter.Int32BitsToSingle(ReadInt32());
    double ReadDouble() => BitConverter.Int64BitsToDouble(ReadInt64());

    static int CheckedLength(uint value) => value <= int.MaxValue
        ? (int)value
        : throw new InvalidDataException("MessagePack value is too large.");

    static string FormatFloat(double value)
    {
        if (double.IsNaN(value)) return "NaN";
        if (double.IsPositiveInfinity(value)) return "Infinity";
        if (double.IsNegativeInfinity(value)) return "-Infinity";
        string rendered = value.ToString("R", CultureInfo.InvariantCulture);
        return rendered.IndexOfAny(new[] { '.', 'e', 'E' }) < 0 ? rendered + ".0" : rendered;
    }

    void RequireAvailable(int count)
    {
        if (count < 0 || offset > bytes.Length - count)
            throw new EndOfStreamException("MessagePack payload ended unexpectedly.");
    }
}
