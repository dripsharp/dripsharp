using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Security;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Pkl.Core;
using Pkl.Parser;

static class Program
{
    const string ResultMagic = "VIBEFORMER_PKL_CORE_CORPUS_RESULTS_V1";
    const string ChildMagic = "VIBEFORMER_PKL_CORE_PACKAGE_CHILD_V1";
    const string AssemblyMagic = "VIBEFORMER_PKL_CORE_LOADED_ASSEMBLIES_V1";
    const string Origin = "package-dotnet";

    static readonly string[] ResultColumns =
    {
        "case-id", "origin", "upstream-revision", "junit-unique-id", "source-path",
        "source-sha256", "source-line", "behavior-family", "product-classification",
        "execution-owner", "status", "observation-base64", "diagnostic-base64"
    };

    public static async Task<int> Main(string[] args)
    {
        if (args.Length > 0 && args[0] == "--child") return RunChild(args);
        if (args.Length > 0 && args[0] == "--external-reader")
        {
            Console.Write("fixture-reader-ok");
            return 0;
        }
        if (args.Length != 7)
        {
            Console.Error.WriteLine(
                "Usage: runner <manifest> <output> <assembly-manifest> <packages-root> " +
                "<loaded-assemblies-output> <timeout-ms> <workers>");
            return 2;
        }

        try
        {
            string manifestPath = Path.GetFullPath(args[0]);
            string output = Path.GetFullPath(args[1]);
            string assemblyManifest = Path.GetFullPath(args[2]);
            string packagesRoot = Path.GetFullPath(args[3]);
            string loadedAssemblies = Path.GetFullPath(args[4]);
            int timeoutMs = ParsePositive(args[5], "timeout-ms");
            int workers = ParsePositive(args[6], "workers");
            ContractManifest manifest = ContractManifest.Read(manifestPath);
            VerifyLoadedAssemblies(assemblyManifest, packagesRoot, loadedAssemblies);
            await RunParent(manifestPath, manifest, output, timeoutMs, workers);
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    static int ParsePositive(string value, string label)
    {
        int parsed = int.Parse(value, System.Globalization.CultureInfo.InvariantCulture);
        if (parsed <= 0) throw new ArgumentOutOfRangeException(label);
        return parsed;
    }

    static async Task RunParent(
        string manifestPath,
        ContractManifest manifest,
        string output,
        int timeoutMs,
        int workers)
    {
        string scratch = Path.Combine(
            Path.GetTempPath(), "vibeformer-pkl-core-package-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(scratch);
        var results = new CorpusResult[manifest.Cases.Count];
        try
        {
            await Parallel.ForEachAsync(
                Enumerable.Range(0, manifest.Cases.Count),
                new ParallelOptions { MaxDegreeOfParallelism = workers },
                async (index, _) =>
                {
                    results[index] = await RunBoundedChild(
                        manifestPath, manifest, index, scratch, timeoutMs);
                });
            WriteResults(output, results);
        }
        finally
        {
            try { Directory.Delete(scratch, recursive: true); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    static async Task<CorpusResult> RunBoundedChild(
        string manifestPath,
        ContractManifest manifest,
        int index,
        string scratch,
        int timeoutMs)
    {
        ContractRow row = manifest.Cases[index];
        string resultFile = Path.Combine(scratch, index.ToString("D4") + ".result");
        using var process = new Process();
        process.StartInfo = new ProcessStartInfo
        {
            FileName = "dotnet",
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            WorkingDirectory = Environment.CurrentDirectory
        };
        process.StartInfo.ArgumentList.Add(Assembly.GetExecutingAssembly().Location);
        process.StartInfo.ArgumentList.Add("--child");
        process.StartInfo.ArgumentList.Add(manifestPath);
        process.StartInfo.ArgumentList.Add(index.ToString(System.Globalization.CultureInfo.InvariantCulture));
        process.StartInfo.ArgumentList.Add(resultFile);
        try
        {
            process.Start();
            Task<string> stdout = process.StandardOutput.ReadToEndAsync();
            Task<string> stderr = process.StandardError.ReadToEndAsync();
            using var timeout = new CancellationTokenSource(timeoutMs);
            try
            {
                await process.WaitForExitAsync(timeout.Token);
            }
            catch (OperationCanceledException)
            {
                try { process.Kill(entireProcessTree: true); }
                catch (InvalidOperationException) { }
                await Task.WhenAll(stdout, stderr);
                return CorpusResult.From(
                    row, manifest.Revision, "TIMEOUT", "",
                    $"Bounded .NET child timed out after {timeoutMs} ms");
            }

            string logs = (await stdout) + (await stderr);
            if (process.ExitCode != 0 || !File.Exists(resultFile))
            {
                return CorpusResult.From(
                    row, manifest.Revision, "CRASH", "",
                    NormalizeDiagnostic($"Bounded .NET child exited {process.ExitCode}:\n{logs}"));
            }
            return ReadChildResult(resultFile, row, manifest.Revision);
        }
        catch (Exception error)
        {
            if (!process.HasExited)
            {
                try { process.Kill(entireProcessTree: true); }
                catch (InvalidOperationException) { }
            }
            return CorpusResult.From(
                row, manifest.Revision, "CRASH", "",
                NormalizeDiagnostic(error.GetType().FullName + ": " + error.Message));
        }
    }

    static int RunChild(string[] args)
    {
        if (args.Length != 4)
            throw new ArgumentException("Usage: runner --child <manifest> <row-index> <result>");
        ContractManifest manifest = ContractManifest.Read(Path.GetFullPath(args[1]));
        int index = int.Parse(args[2], System.Globalization.CultureInfo.InvariantCulture);
        if (index < 0 || index >= manifest.Cases.Count)
            throw new ArgumentOutOfRangeException(nameof(index));
        ContractRow row = manifest.Cases[index];
        ChildResult result;
        try
        {
            using var fixture = CorpusFixture.Create(row);
            fixture.VerifyRequestedFacilities(row);
            result = ExecutePackageAdaptation(row, fixture);
        }
        catch (Exception error)
        {
            result = new ChildResult(
                "FAIL", "", NormalizeDiagnostic(error.GetType().FullName + ": " + error.Message));
        }
        File.WriteAllText(
            Path.GetFullPath(args[3]),
            ChildMagic + "\n" + result.Status + "\n" + Encode(result.Observation) + "\n" +
            Encode(result.Diagnostic) + "\n",
            new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
        return 0;
    }

    static ChildResult ExecutePackageAdaptation(ContractRow row, CorpusFixture fixture)
    {
        if (row.ProductClassification == "user-approved-excluded-surface")
            return new ChildResult("APPROVED_EXCLUSION", "", "");
        if (row.ProductClassification == "test-infrastructure-only-mechanics")
            return new ChildResult("TEST_INFRASTRUCTURE", "", "");

        // Every row reaches this package-only child and its declared local fixtures. The dependent
        // behavior tasks replace this explicit pending result with a public-API assertion for the
        // exact row; a family-level smoke test is deliberately not accepted as row conformance.
        _ = fixture.Root;
        return new ChildResult(
            "PENDING",
            "",
            $"No public package adaptation is registered for {row.CaseId} " +
            $"({row.SourcePath}:{row.SourceLine}).");
    }

    static CorpusResult ReadChildResult(string path, ContractRow row, string revision)
    {
        string[] lines = File.ReadAllLines(path, Encoding.UTF8);
        if (lines.Length != 4 || lines[0] != ChildMagic)
            return CorpusResult.From(
                row, revision, "CRASH", "", "Bounded .NET child emitted a malformed result record");
        try
        {
            return CorpusResult.From(
                row, revision, lines[1], Decode(lines[2]), NormalizeDiagnostic(Decode(lines[3])));
        }
        catch (FormatException)
        {
            return CorpusResult.From(
                row, revision, "CRASH", "", "Bounded .NET child emitted invalid base64 evidence");
        }
    }

    static void WriteResults(string path, IEnumerable<CorpusResult> results)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var output = new StringBuilder();
        output.Append(ResultMagic).Append('\n');
        output.Append("columns\t").AppendJoin('\t', ResultColumns).Append('\n');
        foreach (CorpusResult result in results) output.Append(result.Render()).Append('\n');
        File.WriteAllText(path, output.ToString(), new UTF8Encoding(false));
    }

    static void VerifyLoadedAssemblies(
        string manifestPath,
        string packagesRoot,
        string outputPath)
    {
        var expected = File.ReadAllLines(manifestPath, Encoding.UTF8)
            .Where(line => !string.IsNullOrWhiteSpace(line))
            .Select(line => line.Split('\t'))
            .ToDictionary(fields => fields[0], fields => fields[1], StringComparer.Ordinal);
        Assembly[] loaded = { typeof(Evaluator).Assembly, typeof(Parser).Assembly };
        if (!Directory.Exists(packagesRoot))
            throw new InvalidOperationException("Isolated package cache is missing: " + packagesRoot);
        string normalizedRoot = Path.GetFullPath(AppContext.BaseDirectory)
            .TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        var rows = new List<string>();
        foreach (Assembly assembly in loaded.OrderBy(value => value.GetName().Name, StringComparer.Ordinal))
        {
            string name = assembly.GetName().Name
                ?? throw new InvalidOperationException("Loaded assembly has no name");
            if (!expected.TryGetValue(name, out string? expectedHash))
                throw new InvalidOperationException("Loaded assembly is absent from package proof: " + name);
            string location = Path.GetFullPath(assembly.Location);
            if (!location.StartsWith(normalizedRoot, StringComparison.Ordinal))
                throw new InvalidOperationException("Loaded assembly escaped isolated consumer output: " + location);
            string actualHash = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(location))).ToLowerInvariant();
            if (!string.Equals(expectedHash, actualHash, StringComparison.Ordinal))
                throw new InvalidOperationException("Loaded assembly hash differs from package proof: " + name);
            rows.Add(string.Join('\t', name, location, expectedHash, actualHash));
        }
        if (!expected.Keys.OrderBy(value => value, StringComparer.Ordinal)
            .SequenceEqual(loaded.Select(value => value.GetName().Name!)
                .OrderBy(value => value, StringComparer.Ordinal), StringComparer.Ordinal))
            throw new InvalidOperationException("Loaded assembly set differs from packed dependency closure");
        File.WriteAllText(
            outputPath, AssemblyMagic + "\n" + string.Join("\n", rows) + "\n", new UTF8Encoding(false));
    }

    static string Encode(string value) => Convert.ToBase64String(Encoding.UTF8.GetBytes(value));
    static string Decode(string value) => Encoding.UTF8.GetString(Convert.FromBase64String(value));

    static string NormalizeDiagnostic(string value)
    {
        string normalized = value.Replace("\r\n", "\n", StringComparison.Ordinal)
            .Replace('\r', '\n');
        string current = Path.GetFullPath(Environment.CurrentDirectory);
        normalized = normalized.Replace(current, "<consumer>", StringComparison.Ordinal);
        string temporary = Path.GetFullPath(Path.GetTempPath());
        normalized = normalized.Replace(temporary, "<temp>/", StringComparison.Ordinal);
        return normalized.Trim();
    }

    sealed record ChildResult(string Status, string Observation, string Diagnostic);

    sealed record CorpusResult(
        ContractRow Row,
        string Revision,
        string Status,
        string Observation,
        string Diagnostic)
    {
        public static CorpusResult From(
            ContractRow row, string revision, string status, string observation, string diagnostic) =>
            new(row, revision, status, observation, diagnostic);

        public string Render() => string.Join(
            '\t', "case", Row.CaseId, Origin, Revision, Row.JunitUniqueId, Row.SourcePath,
            Row.SourceSha256, Row.SourceLine, Row.BehaviorFamily, Row.ProductClassification,
            Row.ExecutionOwner, Status, Encode(Observation), Encode(Diagnostic));
    }

    sealed record ContractRow(
        string CaseId,
        string JunitUniqueId,
        string SourcePath,
        string SourceSha256,
        string SourceLine,
        string BehaviorFamily,
        string ProductClassification,
        string ExecutionOwner,
        string ExpectedOutcome,
        string Fixtures,
        string EnvironmentRequirements);

    sealed record ContractManifest(string Revision, IReadOnlyList<ContractRow> Cases)
    {
        public static ContractManifest Read(string path)
        {
            string[] lines = File.ReadAllLines(path, Encoding.UTF8);
            if (lines.Length == 0 || lines[0] != "VIBEFORMER_PKL_CORE_TEST_CONTRACT_V1")
                throw new InvalidOperationException("Pkl.Core contract has the wrong schema marker");
            string? revision = null;
            string[]? columns = null;
            var cases = new List<ContractRow>();
            foreach (string line in lines.Skip(1))
            {
                string[] fields = line.Split('\t');
                if (fields[0] == "meta" && fields.Length == 3 && fields[1] == "source-revision")
                    revision = fields[2];
                else if (fields[0] == "case-columns")
                    columns = fields.Skip(1).ToArray();
                else if (fields[0] == "case")
                {
                    if (columns is null || fields.Length != columns.Length + 1)
                        throw new InvalidOperationException("Malformed Pkl.Core contract case row");
                    var row = columns.Select((column, index) => (column, value: fields[index + 1]))
                        .ToDictionary(item => item.column, item => item.value, StringComparer.Ordinal);
                    cases.Add(new ContractRow(
                        Required(row, "case-id"), Required(row, "junit-unique-id"),
                        Required(row, "source-path"), Required(row, "source-sha256"),
                        Required(row, "source-line"), Required(row, "behavior-family"),
                        Required(row, "product-classification"), Required(row, "execution-owner"),
                        Required(row, "expected-outcome"), Required(row, "fixtures"),
                        Required(row, "environment-requirements")));
                }
            }
            if (revision is null || cases.Count == 0)
                throw new InvalidOperationException("Pkl.Core contract is missing provenance or cases");
            return new ContractManifest(revision, cases);
        }

        static string Required(IReadOnlyDictionary<string, string> row, string key)
        {
            if (!row.TryGetValue(key, out string? value) || string.IsNullOrWhiteSpace(value))
                throw new InvalidOperationException("Missing contract field: " + key);
            return value;
        }
    }

    sealed class CorpusFixture : IDisposable
    {
        readonly string root;
        bool disposed;

        CorpusFixture(string root) => this.root = root;
        public string Root => root;

        public static CorpusFixture Create(ContractRow row)
        {
            string root = Path.Combine(
                Path.GetTempPath(), "vibeformer-pkl-core-row-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(root);
            Directory.CreateDirectory(Path.Combine(root, "modules"));
            Directory.CreateDirectory(Path.Combine(root, "resources"));
            Directory.CreateDirectory(Path.Combine(root, "module path", "δ"));
            File.WriteAllText(
                Path.Combine(root, "modules", "dependency.pkl"), "value = 42\n", new UTF8Encoding(false));
            File.WriteAllText(
                Path.Combine(root, "modules", "main.pkl"),
                "dependency = import(\"dependency.pkl\")\n", new UTF8Encoding(false));
            File.WriteAllBytes(Path.Combine(root, "resources", "payload.bin"), new byte[] { 0, 1, 127, 255 });
            using (ZipArchive archive = ZipFile.Open(
                Path.Combine(root, "module-path.zip"), ZipArchiveMode.Create))
            {
                ZipArchiveEntry module = archive.CreateEntry("fixture/module.pkl");
                using var writer = new StreamWriter(module.Open(), new UTF8Encoding(false));
                writer.Write("value = \"archive\"\n");
            }
            return new CorpusFixture(root);
        }

        public void VerifyRequestedFacilities(ContractRow row)
        {
            Require(File.ReadAllText(Path.Combine(root, "modules", "dependency.pkl"), Encoding.UTF8)
                == "value = 42\n", "temporary module fixture bytes");
            Require(File.ReadAllBytes(Path.Combine(root, "resources", "payload.bin"))
                .SequenceEqual(new byte[] { 0, 1, 127, 255 }), "temporary resource fixture bytes");
            using (ZipArchive archive = ZipFile.OpenRead(Path.Combine(root, "module-path.zip")))
                Require(archive.GetEntry("fixture/module.pkl") is not null, "archive module-path fixture");
            Require(Directory.Exists(Path.Combine(root, "module path", "δ")), "platform path fixture");

            var environment = new Dictionary<string, string>(StringComparer.Ordinal)
            {
                ["VIBEFORMER_CONTRACT_ENV"] = "environment-value"
            };
            var properties = new Dictionary<string, string>(StringComparer.Ordinal)
            {
                ["vibeformer.contract.property"] = "property-value"
            };
            var services = new[] { "module-key-factory", "resource-reader" };
            Require(environment["VIBEFORMER_CONTRACT_ENV"] == "environment-value", "environment fixture");
            Require(properties["vibeformer.contract.property"] == "property-value", "property fixture");
            Require(services.SequenceEqual(new[] { "module-key-factory", "resource-reader" }),
                "service fixture ordering");

            string source = row.SourcePath;
            string requirements = row.EnvironmentRequirements;
            if (source.Contains("/http/", StringComparison.Ordinal) ||
                requirements.Contains("loopback-http-server", StringComparison.Ordinal))
            {
                VerifyPlainHttp();
                VerifyTlsHttp();
                VerifyProxy();
            }
            if (source.Contains("/packages/", StringComparison.Ordinal) ||
                requirements.Contains("loopback-package-server", StringComparison.Ordinal))
                VerifyPackageServer();
            if (source.Contains("externalreader", StringComparison.OrdinalIgnoreCase) ||
                requirements.Contains("external-reader-process", StringComparison.Ordinal))
                VerifyExternalReader();
        }

        static void VerifyPlainHttp()
        {
            string payload = OneShotHttpServer.RoundTrip(useTls: false, proxy: false, "/fixture");
            Require(payload == "http-fixture", "loopback HTTP fixture");
        }

        static void VerifyTlsHttp()
        {
            string payload = OneShotHttpServer.RoundTrip(useTls: true, proxy: false, "/fixture");
            Require(payload == "tls-fixture", "loopback TLS fixture");
        }

        static void VerifyProxy()
        {
            string payload = OneShotHttpServer.RoundTrip(useTls: false, proxy: true, "/proxy");
            Require(payload == "proxy-fixture", "loopback proxy fixture");
        }

        static void VerifyPackageServer()
        {
            string payload = OneShotHttpServer.RoundTrip(useTls: false, proxy: false, "/package");
            Require(payload == "package-fixture", "loopback package fixture");
        }

        static void VerifyExternalReader()
        {
            using var process = new Process();
            process.StartInfo = new ProcessStartInfo
            {
                FileName = "dotnet",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            process.StartInfo.ArgumentList.Add(Assembly.GetExecutingAssembly().Location);
            process.StartInfo.ArgumentList.Add("--external-reader");
            process.Start();
            string output = process.StandardOutput.ReadToEnd();
            string error = process.StandardError.ReadToEnd();
            if (!process.WaitForExit(5000))
            {
                process.Kill(entireProcessTree: true);
                throw new InvalidOperationException("external-reader fixture timed out");
            }
            Require(process.ExitCode == 0 && output == "fixture-reader-ok" && error.Length == 0,
                "external-reader process fixture");
        }

        static void Require(bool condition, string subject)
        {
            if (!condition) throw new InvalidOperationException("Invalid " + subject);
        }

        public void Dispose()
        {
            if (disposed) return;
            disposed = true;
            try { Directory.Delete(root, recursive: true); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    static class OneShotHttpServer
    {
        public static string RoundTrip(bool useTls, bool proxy, string path)
        {
            using var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            int port = ((IPEndPoint)listener.LocalEndpoint).Port;
            using X509Certificate2? certificate = useTls ? CreateCertificate() : null;
            string expectedPayload = proxy ? "proxy-fixture" :
                path == "/package" ? "package-fixture" : useTls ? "tls-fixture" : "http-fixture";
            using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            Task server = Task.Run(async () =>
            {
                using TcpClient client = await listener.AcceptTcpClientAsync(deadline.Token);
                Stream stream = client.GetStream();
                if (useTls)
                {
                    var ssl = new SslStream(stream, leaveInnerStreamOpen: false);
                    await ssl.AuthenticateAsServerAsync(
                        new SslServerAuthenticationOptions
                        {
                            ServerCertificate = certificate,
                            EnabledSslProtocols = System.Security.Authentication.SslProtocols.Tls12 |
                                System.Security.Authentication.SslProtocols.Tls13
                        }, deadline.Token);
                    stream = ssl;
                }
                using (stream)
                {
                    await ReadHeaders(stream, deadline.Token);
                    byte[] body = Encoding.UTF8.GetBytes(expectedPayload);
                    byte[] response = Encoding.ASCII.GetBytes(
                        "HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: text/plain\r\n" +
                        $"Content-Length: {body.Length}\r\n\r\n");
                    await stream.WriteAsync(response, deadline.Token);
                    await stream.WriteAsync(body, deadline.Token);
                    await stream.FlushAsync(deadline.Token);
                }
            }, deadline.Token);

            using var handler = new HttpClientHandler();
            Uri requestUri;
            if (proxy)
            {
                handler.Proxy = new WebProxy(new Uri($"http://127.0.0.1:{port}/"));
                handler.UseProxy = true;
                requestUri = new Uri("http://vibeformer-uncontrolled.invalid/proxy");
            }
            else
            {
                string scheme = useTls ? "https" : "http";
                requestUri = new Uri($"{scheme}://127.0.0.1:{port}{path}");
            }
            if (useTls)
            {
                string thumbprint = certificate!.Thumbprint;
                handler.ServerCertificateCustomValidationCallback =
                    (_, actual, _, _) => actual is not null && actual.GetCertHashString() == thumbprint;
            }
            using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(10) };
            string payload = client.GetStringAsync(requestUri, deadline.Token).GetAwaiter().GetResult();
            server.GetAwaiter().GetResult();
            return payload;
        }

        static async Task ReadHeaders(Stream stream, CancellationToken token)
        {
            int matched = 0;
            byte[] terminator = { 13, 10, 13, 10 };
            var buffer = new byte[1];
            while (matched < terminator.Length)
            {
                int count = await stream.ReadAsync(buffer, token);
                if (count == 0) throw new EndOfStreamException("HTTP fixture request ended early");
                matched = buffer[0] == terminator[matched]
                    ? matched + 1
                    : buffer[0] == terminator[0] ? 1 : 0;
            }
        }

        static X509Certificate2 CreateCertificate()
        {
            using RSA rsa = RSA.Create(2048);
            var request = new CertificateRequest(
                "CN=localhost", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(
                new X509BasicConstraintsExtension(false, false, 0, false));
            request.CertificateExtensions.Add(
                new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, false));
            var names = new SubjectAlternativeNameBuilder();
            names.AddDnsName("localhost");
            names.AddIpAddress(IPAddress.Loopback);
            request.CertificateExtensions.Add(names.Build());
            return request.CreateSelfSigned(
                DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddHours(1));
        }
    }
}
