// Idiomatic .NET assembly module and embedded-resource loading for Pkl.Core.
// This is durable destination product code; translated Java output remains
// disposable. The shared index deliberately performs no Pkl evaluation.
#nullable enable

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Reflection;
using System.Text.RegularExpressions;

namespace Pkl.Core
{
    internal static class DotNetBytes
    {
        internal static byte[] Unsigned(sbyte[] values) =>
            values.Select(value => unchecked((byte)value)).ToArray();
    }

    public sealed partial class SecurityManagers
    {
        public static IReadOnlyList<Regex> DefaultAllowedModules =>
            DotNetCollections.ReadOnly(defaultAllowedModules);
        public static IReadOnlyList<Regex> DefaultAllowedResources =>
            DotNetCollections.ReadOnly(defaultAllowedResources);
        public static Func<Uri, int> DefaultTrustLevels => defaultTrustLevels;
        public static SecurityManager DefaultManager => defaultManager;

        public partial class StandardBuilder
        {
            public IReadOnlyList<Regex> AllowedModules =>
                DotNetCollections.ReadOnly(GetAllowedModules());
            public IReadOnlyList<Regex> AllowedResources =>
                DotNetCollections.ReadOnly(GetAllowedResources());
            public string? RootDirectory => GetRootDir();
        }
    }
}

namespace Pkl.Core
{
    public sealed partial record class Platform
    {
        static Platform()
        {
            var assemblyVersion = typeof(Platform).Assembly.GetName().Version?.ToString() ?? "unknown";
            var runtime = global::System.Runtime.InteropServices.RuntimeInformation.FrameworkDescription;
            var osName = global::System.OperatingSystem.IsWindows() ? "Windows" :
                global::System.OperatingSystem.IsMacOS() ? "macOS" :
                global::System.OperatingSystem.IsLinux() ? "Linux" :
                global::System.Runtime.InteropServices.RuntimeInformation.OSDescription;
            CURRENT = new Platform(
                new Language(assemblyVersion),
                new Runtime(".NET", runtime),
                new VirtualMachine(".NET", runtime),
                new OperatingSystem(osName, global::System.Environment.OSVersion.VersionString),
                new Processor(global::System.Runtime.InteropServices.RuntimeInformation.OSArchitecture.ToString()));
        }
    }
}

namespace Pkl.Core.Http
{
    internal sealed partial class RequestRewritingClient
    {
        static RequestRewritingClient()
        {
            var maxRedirects = 20;
            if (int.TryParse(AppContext.GetData("http.maxRedirects")?.ToString(),
                             out var configured))
                maxRedirects = Math.Max(0, configured);
            MAX_HTTP_REDIRECTS = maxRedirects;
        }
    }
}

namespace Pkl.Core.Util.Json
{
    internal static class JsonHandlerBridge
    {
        internal static JsonHandler<object, object> Erase<A, O>(JsonHandler<A, O> handler) =>
            new Adapter<A, O>(handler);

        private sealed class Adapter<A, O> : JsonHandler<object, object>
        {
            private readonly JsonHandler<A, O> inner;

            internal Adapter(JsonHandler<A, O> inner) => this.inner = inner;

            private void SyncParser() => inner.parser = parser;

            public override void StartNull() { SyncParser(); inner.StartNull(); }
            public override void EndNull() { SyncParser(); inner.EndNull(); }
            public override void StartBoolean() { SyncParser(); inner.StartBoolean(); }
            public override void EndBoolean(bool value) { SyncParser(); inner.EndBoolean(value); }
            public override void StartString() { SyncParser(); inner.StartString(); }
            public override void EndString(string value) { SyncParser(); inner.EndString(value); }
            public override void StartNumber() { SyncParser(); inner.StartNumber(); }
            public override void EndNumber(string value) { SyncParser(); inner.EndNumber(value); }
            public override object? StartArray() { SyncParser(); return inner.StartArray(); }
            public override void EndArray(object? array) { SyncParser(); inner.EndArray((A?)array); }
            public override void StartArrayValue(object? array) { SyncParser(); inner.StartArrayValue((A?)array); }
            public override void EndArrayValue(object? array) { SyncParser(); inner.EndArrayValue((A?)array); }
            public override object? StartObject() { SyncParser(); return inner.StartObject(); }
            public override void EndObject(object? value) { SyncParser(); inner.EndObject((O?)value); }
            public override void StartObjectName(object? value) { SyncParser(); inner.StartObjectName((O?)value); }
            public override void EndObjectName(object? value, string name) { SyncParser(); inner.EndObjectName((O?)value, name); }
            public override void StartObjectValue(object? value, string name) { SyncParser(); inner.StartObjectValue((O?)value, name); }
            public override void EndObjectValue(object? value, string name) { SyncParser(); inner.EndObjectValue((O?)value, name); }
        }
    }
}

namespace Pkl.Core.Http
{
    internal sealed partial class JdkHttpClient
    {
        static JdkHttpClient()
        {
            closeMethod = new Action<Runtime.JavaHttpClient>(client => client.Dispose());
        }
    }
}

namespace Pkl.Core.Module
{
    public partial interface ModuleKey
    {
        public Uri Uri => GetUri();
        public bool Cached => IsCached();
        public bool Local => IsLocal();
        public string? FileCachePath => GetFileCacheLocation();
    }

    public partial interface ResolvedModuleKey
    {
        public ModuleKey Original => GetOriginal();
        public Uri Uri => GetUri();
        public string Source => LoadSource();
    }

    public partial interface ModuleKeyFactory
    {
        public ModuleKey? TryCreate(Uri uri)
        {
            ArgumentNullException.ThrowIfNull(uri);
            var result = Create(uri);
            return result.IsPresent() ? result.Get() : null;
        }
    }

    public sealed partial class ModuleKeyFactories
    {
        public static ModuleKeyFactory StandardLibraryFactory => standardLibrary;
        public static ModuleKeyFactory FileFactory => file;
        public static ModuleKeyFactory HttpFactory => http;
        public static ModuleKeyFactory GenericUrlFactory => genericUrl;
        public static ModuleKeyFactory PackageFactory => pkg;
        public static ModuleKeyFactory ProjectPackageFactory => projectpackage;

        /// <summary>
        /// Creates a caller-owned factory that exposes manifest resources in
        /// <paramref name="assembly"/> as local, globbable Pkl modules.
        /// </summary>
        /// <remarks>
        /// Supply <paramref name="resourcePrefix"/> when the assembly uses the
        /// usual dotted manifest names. For example, prefix
        /// <c>Example.Modules</c> maps <c>Example.Modules.lib.main.pkl</c> to
        /// <c>assembly:/lib/main.pkl</c>. Explicit slash-based logical names are
        /// preserved as-is.
        /// </remarks>
        public static ModuleKeyFactory CreateAssembly(
            Assembly assembly,
            string resourcePrefix = "",
            string uriScheme = "assembly") =>
            new AssemblyModuleKeyFactory(assembly, resourcePrefix, uriScheme);
    }

    public sealed partial class ModuleKeys
    {
        public static ModuleKey CreatePackage(Uri uri) => Pkg(uri);
        public static ModuleKey CreateProjectPackage(Uri uri) => Projectpackage(uri);
        public static ModuleKey CreateCached(ModuleKey original, string source) =>
            Cached(original, source);
    }

    public sealed partial class ResolvedModuleKeys
    {
        public static ResolvedModuleKey CreateFile(
            ModuleKey original, Uri uri, string path, bool noFollowLinks = false) =>
            File(original, uri, path, noFollowLinks);
    }

    public partial class PathElement
    {
        public string Name => GetName();
        public bool Directory => IsDirectory();

        public sealed partial class TreePathElement
        {
            public IReadOnlyList<PathElement> Children =>
                Pkl.Core.DotNetCollections.ReadOnly(GetChildrenValues());
        }
    }

    public sealed partial class ProjectDependenciesManager
    {
        public IReadOnlyDictionary<string, Pkl.Core.Packages.Dependency> Dependencies =>
            Pkl.Core.DotNetCollections.ReadOnly(GetDependencies());
        public Pkl.Core.Project.DeclaredDependencies DeclaredDependencies =>
            GetDeclaredDependencies();
        public Uri ProjectBaseUri => GetProjectBaseUri();
        public Uri ProjectDependenciesFileUri => GetProjectDepsFileUri();
        public Uri ProjectFileUri => GetProjectFileUri();
    }

    public sealed class AssemblyModuleKeyFactory : ModuleKeyFactory
    {
        private readonly DotNetLoading.AssemblyResourceIndex resources;
        private readonly string uriScheme;
        private bool disposed;

        public AssemblyModuleKeyFactory(
            Assembly assembly,
            string resourcePrefix = "",
            string uriScheme = "assembly")
        {
            ArgumentNullException.ThrowIfNull(assembly);
            this.uriScheme = DotNetLoading.ValidateScheme(uriScheme, nameof(uriScheme));
            resources = new DotNetLoading.AssemblyResourceIndex(assembly, resourcePrefix);
        }

        public Runtime.JavaOptional<ModuleKey> Create(Uri uri)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            ArgumentNullException.ThrowIfNull(uri);
            if (!string.Equals(uri.Scheme, uriScheme, StringComparison.OrdinalIgnoreCase))
                return Runtime.JavaOptional<ModuleKey>.Empty();
            return Runtime.JavaOptional<ModuleKey>.Of(new AssemblyModuleKey(uri, resources));
        }

        public void Close() => disposed = true;
        public void Dispose() => Close();
    }

    internal sealed class AssemblyModuleKey : ModuleKey
    {
        private readonly Uri uri;
        private readonly DotNetLoading.AssemblyResourceIndex resources;

        internal AssemblyModuleKey(Uri uri, DotNetLoading.AssemblyResourceIndex resources)
        {
            this.uri = uri;
            this.resources = resources;
        }

        public Uri GetUri() => uri;
        public bool HasHierarchicalUris() => true;
        public bool IsGlobbable() => true;
        public bool IsLocal() => true;

        public bool HasElement(SecurityManager securityManager, Uri elementUri)
        {
            ArgumentNullException.ThrowIfNull(securityManager);
            securityManager.CheckResolveModule(elementUri);
            return resources.HasElement(elementUri);
        }

        public IList<PathElement> ListElements(SecurityManager securityManager, Uri baseUri)
        {
            ArgumentNullException.ThrowIfNull(securityManager);
            securityManager.CheckResolveModule(baseUri);
            return resources.ListElements(baseUri);
        }

        public ResolvedModuleKey Resolve(SecurityManager securityManager)
        {
            ArgumentNullException.ThrowIfNull(securityManager);
            securityManager.CheckResolveModule(uri);
            if (!resources.TryRead(uri, out var bytes))
                throw new FileNotFoundException($"Cannot find assembly module `{uri}`.");
            return new AssemblyResolvedModuleKey(this, uri, bytes);
        }
    }

    internal sealed class AssemblyResolvedModuleKey : ResolvedModuleKey
    {
        private readonly ModuleKey original;
        private readonly Uri uri;
        private readonly sbyte[] bytes;

        internal AssemblyResolvedModuleKey(ModuleKey original, Uri uri, byte[] bytes)
        {
            this.original = original;
            this.uri = uri;
            this.bytes = bytes.Select(value => unchecked((sbyte)value)).ToArray();
        }

        public ModuleKey GetOriginal() => original;
        public Uri GetUri() => uri;
        public string LoadSource() =>
            global::Vibeformer.Runtime.JavaCompat.NewString(
                bytes, global::System.Text.Encoding.UTF8);
    }
}

namespace Pkl.Core.Resource
{
    public sealed partial record class Resource
    {
        internal Resource(Uri uri, sbyte[] bytes) : this(uri, Pkl.Core.DotNetBytes.Unsigned(bytes)) { }

        public string Text => GetText();
        public string Base64 => GetBase64();
    }

    public partial interface ResourceReader
    {
        public object? TryRead(Uri uri)
        {
            ArgumentNullException.ThrowIfNull(uri);
            var result = Read(uri);
            return result.IsPresent() ? result.Get() : null;
        }
    }

    public sealed partial class ResourceReaders
    {
        public static ResourceReader CreateFile() => File();
        public static ResourceReader CreateHttp() => Http();
        public static ResourceReader CreateHttps() => Https();
        public static ResourceReader CreatePackage() => Pkg();
        public static ResourceReader CreateProjectPackage() => Projectpackage();

        /// <summary>
        /// Creates a caller-owned reader for manifest resources in a .NET
        /// assembly. The resource-prefix mapping matches
        /// <see cref="Pkl.Core.Module.ModuleKeyFactories.CreateAssembly"/>.
        /// </summary>
        public static ResourceReader CreateEmbeddedResources(
            Assembly assembly,
            string resourcePrefix = "",
            string uriScheme = "embedded") =>
            new EmbeddedResourceReader(assembly, resourcePrefix, uriScheme);
    }

    public sealed class EmbeddedResourceReader : ResourceReader
    {
        private readonly DotNetLoading.AssemblyResourceIndex resources;
        private readonly string uriScheme;
        private bool disposed;

        public EmbeddedResourceReader(
            Assembly assembly,
            string resourcePrefix = "",
            string uriScheme = "embedded")
        {
            ArgumentNullException.ThrowIfNull(assembly);
            this.uriScheme = DotNetLoading.ValidateScheme(uriScheme, nameof(uriScheme));
            resources = new DotNetLoading.AssemblyResourceIndex(assembly, resourcePrefix);
        }

        public string GetUriScheme() => uriScheme;
        public bool HasHierarchicalUris() => true;
        public bool IsGlobbable() => true;

        public Runtime.JavaOptional<object> Read(Uri uri)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            if (!string.Equals(uri.Scheme, uriScheme, StringComparison.OrdinalIgnoreCase) ||
                !resources.TryRead(uri, out var bytes))
                return Runtime.JavaOptional<object>.Empty();
            return Runtime.JavaOptional<object>.Of(new Resource(uri, bytes));
        }

        public bool HasElement(SecurityManager securityManager, Uri elementUri)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            ArgumentNullException.ThrowIfNull(securityManager);
            securityManager.CheckResolveResource(elementUri);
            return resources.HasElement(elementUri);
        }

        public IList<Module.PathElement> ListElements(
            SecurityManager securityManager, Uri baseUri)
        {
            ObjectDisposedException.ThrowIf(disposed, this);
            ArgumentNullException.ThrowIfNull(securityManager);
            securityManager.CheckResolveResource(baseUri);
            return resources.ListElements(baseUri);
        }

        public void Close() => disposed = true;
        public void Dispose() => Close();
    }
}

namespace Pkl.Core.Http
{
    public partial interface HttpClient
    {
        public byte[] Send(
            HttpRequestMessage request,
            HttpRequestChecker requestChecker)
        {
            ArgumentNullException.ThrowIfNull(request);
            ArgumentNullException.ThrowIfNull(requestChecker);
            return Send(
                new Runtime.JavaHttpRequest(request),
                response => response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult(),
                requestChecker).Body();
        }

        public Stream OpenRead(
            HttpRequestMessage request,
            HttpRequestChecker requestChecker)
        {
            ArgumentNullException.ThrowIfNull(request);
            ArgumentNullException.ThrowIfNull(requestChecker);
            return Send(
                new Runtime.JavaHttpRequest(request),
                Runtime.JavaHttpBodyHandlers.OfInputStream(),
                requestChecker).Body();
        }

        public partial interface Builder
        {
            public Builder AddCertificate(byte[] certificateBytes) =>
                AddCertificates(certificateBytes);

            public Builder AddCertificate(string path) => AddCertificates(path);
        }
    }
}

namespace Pkl.Core.Packages
{
    public sealed partial class Checksums
    {
        public string Sha256 => GetSha256();
    }

    public abstract partial class Dependency
    {
        public PackageUri PackageUri => GetPackageUri();
        public Pkl.Core.Version Version => GetVersion();

        public sealed partial class LocalDependency
        {
            public string Path => GetPath();
        }

        public sealed partial class RemoteDependency
        {
            public Checksums? Checksums => GetChecksums();
        }
    }

    public sealed partial class PackageUri
    {
        public Uri Uri => GetUri();
        public Pkl.Core.Version Version => GetVersion();
        public Checksums? Checksums => GetChecksums();
        public Uri MetadataRequestUri => GetMetadataRequestUri();
        public string DisplayName => GetDisplayName();
    }

    public sealed partial class PackageAssetUri
    {
        public Uri Uri => GetUri();
        public PackageUri PackageUri => GetPackageUri();
        public Pkl.Core.Version Version => GetVersion();
        public string AssetPath => GetAssetPath();
    }

    public sealed partial class DependencyMetadata
    {
        public string Name => GetName();
        public PackageUri PackageUri => packageUri;
        public Pkl.Core.Version Version => GetVersion();
        public Uri PackageArchiveUri => GetPackageZipUrl();
        public Checksums PackageArchiveChecksums => GetPackageZipChecksums();
        public IReadOnlyDictionary<string, Dependency.RemoteDependency> Dependencies =>
            Pkl.Core.DotNetCollections.ReadOnly(GetDependencies());
        public IReadOnlyList<Pkl.Core.PObject> Annotations =>
            Pkl.Core.DotNetCollections.ReadOnly(GetAnnotations());
        public IReadOnlyList<string>? Authors => GetAuthors() is { } values
            ? Pkl.Core.DotNetCollections.ReadOnly(values)
            : null;
    }

    public partial interface PackageResolver
    {
        public byte[] GetAssetBytes(
            PackageAssetUri uri, bool allowDirectories = false, Checksums? checksums = null) =>
            Pkl.Core.DotNetBytes.Unsigned(GetBytes(uri, allowDirectories, checksums));

        public IReadOnlyList<Pkl.Core.Module.PathElement> GetElements(
            PackageAssetUri uri, Checksums? checksums = null) =>
            Pkl.Core.DotNetCollections.ReadOnly(ListElements(uri, checksums));
    }

    public sealed partial class PackageLoadError
    {
        public string ErrorName => GetMessageName();
        public IReadOnlyList<object>? ErrorArguments => GetArguments() is { } values
            ? Array.AsReadOnly(values)
            : null;
    }
}

namespace Pkl.Core.Project
{
    public sealed partial record class DeclaredDependencies
    {
        public IReadOnlyDictionary<string, Pkl.Core.Packages.Dependency.RemoteDependency>
            RemoteDependenciesReadOnly =>
                Pkl.Core.DotNetCollections.ReadOnly(GetRemoteDependencies());
        public IReadOnlyDictionary<string, DeclaredDependencies> LocalDependenciesReadOnly =>
            Pkl.Core.DotNetCollections.ReadOnly(GetLocalDependencies());
    }

    public sealed partial class Project
    {
        public Package? PackageMetadata => GetPackage();
        public DeclaredDependencies DeclaredDependencies => GetDependencies();
        public Pkl.Core.EvaluatorSettings.PklEvaluatorSettings EvaluatorConfiguration =>
            GetEvaluatorSettings();
        public Pkl.Core.EvaluatorSettings.PklEvaluatorSettings ResolvedEvaluatorConfiguration =>
            GetResolvedEvaluatorSettings();
        public Uri ProjectFileUri => GetProjectFileUri();
        public string ProjectDirectory => GetProjectDir();
        public IReadOnlyList<string> Tests =>
            Pkl.Core.DotNetCollections.ReadOnly(GetTests());
        public IReadOnlyDictionary<string, Project> LocalProjectDependencies =>
            Pkl.Core.DotNetCollections.ReadOnly(GetLocalProjectDependencies());
        public IReadOnlyList<Pkl.Core.PObject> Annotations =>
            Pkl.Core.DotNetCollections.ReadOnly(GetAnnotations());

        public partial class EvaluatorSettings
        {
            public IReadOnlyDictionary<string, string>? ExternalProperties =>
                GetExternalProperties() is { } values
                    ? Pkl.Core.DotNetCollections.ReadOnly(values)
                    : null;
            public IReadOnlyDictionary<string, string>? Environment =>
                GetEnv() is { } values ? Pkl.Core.DotNetCollections.ReadOnly(values) : null;
            public IReadOnlyList<Regex>? AllowedModules =>
                GetAllowedModules() is { } values
                    ? Pkl.Core.DotNetCollections.ReadOnly(values)
                    : null;
            public IReadOnlyList<Regex>? AllowedResources =>
                GetAllowedResources() is { } values
                    ? Pkl.Core.DotNetCollections.ReadOnly(values)
                    : null;
            public IReadOnlyList<string>? ModulePaths =>
                GetModulePath() is { } values
                    ? Pkl.Core.DotNetCollections.ReadOnly(values)
                    : null;
        }
    }
}

namespace Pkl.Core.EvaluatorSettings
{
    public sealed partial record class PklEvaluatorSettings
    {
        public IReadOnlyDictionary<string, string>? ExternalPropertiesReadOnly =>
            ExternalProperties is { } values ? Pkl.Core.DotNetCollections.ReadOnly(values) : null;
        public IReadOnlyDictionary<string, string>? Environment =>
            Env is { } values ? Pkl.Core.DotNetCollections.ReadOnly(values) : null;
        public IReadOnlyList<Regex>? AllowedModulesReadOnly =>
            AllowedModules is { } values ? Pkl.Core.DotNetCollections.ReadOnly(values) : null;
        public IReadOnlyList<Regex>? AllowedResourcesReadOnly =>
            AllowedResources is { } values ? Pkl.Core.DotNetCollections.ReadOnly(values) : null;
        public IReadOnlyList<string>? ModulePaths =>
            ModulePath is { } values ? Pkl.Core.DotNetCollections.ReadOnly(values) : null;
        public IReadOnlyDictionary<string, ExternalReader>? ExternalModuleReadersReadOnly =>
            ExternalModuleReaders is { } values
                ? Pkl.Core.DotNetCollections.ReadOnly(values)
                : null;
        public IReadOnlyDictionary<string, ExternalReader>? ExternalResourceReadersReadOnly =>
            ExternalResourceReaders is { } values
                ? Pkl.Core.DotNetCollections.ReadOnly(values)
                : null;
        public Http? HttpSettings => HttpValue;

        public sealed partial record class Http
        {
            public IReadOnlyDictionary<Uri, Uri>? RewritesReadOnly =>
                Rewrites is { } values ? Pkl.Core.DotNetCollections.ReadOnly(values) : null;
        }

        public sealed partial record class Proxy
        {
            public IReadOnlyList<string>? NoProxyReadOnly =>
                NoProxy is { } values ? Pkl.Core.DotNetCollections.ReadOnly(values) : null;
        }

        public sealed partial record class ExternalReader
        {
            public IReadOnlyList<string>? ArgumentsReadOnly =>
                Arguments is { } values ? Pkl.Core.DotNetCollections.ReadOnly(values) : null;
        }
    }
}

namespace Pkl.Core.Settings
{
    public sealed partial record class PklSettings
    {
        public Editor EditorSettings => GetEditor();
        public Pkl.Core.EvaluatorSettings.PklEvaluatorSettings.Http? HttpSettings => Http;
    }
}

namespace Pkl.Core.Externalreader
{
    public partial interface ExternalReaderProcess
    {
        public static ExternalReaderProcess Start(
            Pkl.Core.EvaluatorSettings.PklEvaluatorSettings.ExternalReader specification) =>
            Of(specification);
    }

    public partial interface ExternalResourceResolver
    {
        public object? TryRead(Uri uri)
        {
            ArgumentNullException.ThrowIfNull(uri);
            var result = Read(uri);
            return result.IsPresent() ? result.Get() : null;
        }
    }
}

namespace Pkl.Core
{
    internal static class DotNetLoading
    {
        internal static string ValidateScheme(string value, string parameterName)
        {
            ArgumentException.ThrowIfNullOrWhiteSpace(value, parameterName);
            if (!Uri.CheckSchemeName(value))
                throw new ArgumentException($"Invalid URI scheme `{value}`.", parameterName);
            return value;
        }

        internal sealed class AssemblyResourceIndex
        {
            private readonly Assembly assembly;
            private readonly Dictionary<string, string> resources =
                new(StringComparer.Ordinal);

            internal AssemblyResourceIndex(Assembly assembly, string resourcePrefix)
            {
                this.assembly = assembly;
                resourcePrefix ??= "";
                foreach (var resourceName in assembly.GetManifestResourceNames()
                             .OrderBy(value => value, StringComparer.Ordinal))
                {
                    var relative = RemovePrefix(resourceName, resourcePrefix);
                    if (relative is null) continue;
                    foreach (var candidate in ResourcePaths(relative)) Add(candidate, resourceName);
                }
            }

            internal bool TryRead(Uri uri, out byte[] bytes)
            {
                var path = UriPath(uri);
                if (!resources.TryGetValue(path, out var resourceName))
                {
                    bytes = Array.Empty<byte>();
                    return false;
                }
                using var stream = assembly.GetManifestResourceStream(resourceName) ??
                    throw new FileNotFoundException(
                        $"Manifest resource `{resourceName}` disappeared from `{assembly.FullName}`.");
                using var buffer = new MemoryStream();
                stream.CopyTo(buffer);
                bytes = buffer.ToArray();
                return true;
            }

            internal bool HasElement(Uri uri)
            {
                var path = UriPath(uri);
                if (resources.ContainsKey(path)) return true;
                var prefix = path.Length == 0 ? "" : path + "/";
                return resources.Keys.Any(candidate =>
                    candidate.StartsWith(prefix, StringComparison.Ordinal));
            }

            internal IList<Module.PathElement> ListElements(Uri baseUri)
            {
                var path = UriPath(baseUri);
                var prefix = path.Length == 0 ? "" : path + "/";
                var children = new Dictionary<string, bool>(StringComparer.Ordinal);
                foreach (var candidate in resources.Keys)
                {
                    if (!candidate.StartsWith(prefix, StringComparison.Ordinal)) continue;
                    var remainder = candidate[prefix.Length..];
                    if (remainder.Length == 0) continue;
                    var separator = remainder.IndexOf('/');
                    var name = separator < 0 ? remainder : remainder[..separator];
                    var directory = separator >= 0;
                    children[name] = children.TryGetValue(name, out var prior)
                        ? prior || directory
                        : directory;
                }
                return children.OrderBy(entry => entry.Key, StringComparer.Ordinal)
                    .Select(entry => new Module.PathElement(entry.Key, entry.Value))
                    .ToList();
            }

            private void Add(string candidate, string resourceName)
            {
                var path = NormalizeResourcePath(candidate);
                if (path.Length == 0) return;
                if (resources.TryGetValue(path, out var prior) &&
                    !string.Equals(prior, resourceName, StringComparison.Ordinal))
                    throw new InvalidOperationException(
                        $"Assembly resources `{prior}` and `{resourceName}` both map to `{path}`.");
                resources[path] = resourceName;
            }

            private static string? RemovePrefix(string resourceName, string prefix)
            {
                if (prefix.Length == 0) return resourceName;
                if (string.Equals(resourceName, prefix, StringComparison.Ordinal)) return "";
                foreach (var separator in new[] { '.', '/', '\\' })
                {
                    var expected = prefix.TrimEnd('.', '/', '\\') + separator;
                    if (resourceName.StartsWith(expected, StringComparison.Ordinal))
                        return resourceName[expected.Length..];
                }
                return null;
            }

            private static IEnumerable<string> ResourcePaths(string relative)
            {
                relative = relative.Replace('\\', '/').TrimStart('/');
                yield return relative;
                if (relative.Contains('/')) yield break;
                var parts = relative.Split('.');
                if (parts.Length < 3) yield break;
                yield return string.Join('/', parts[..^2]) + "/" +
                    parts[^2] + "." + parts[^1];
            }

            private static string UriPath(Uri uri)
            {
                ArgumentNullException.ThrowIfNull(uri);
                if (!uri.IsAbsoluteUri)
                    throw new UriFormatException($"Expected an absolute resource URI, got `{uri}`.");
                var escaped = uri.GetComponents(UriComponents.Path, UriFormat.UriEscaped);
                string decoded;
                try { decoded = Uri.UnescapeDataString(escaped); }
                catch (Exception error) when (error is ArgumentException or UriFormatException)
                {
                    throw new UriFormatException($"Invalid escaped resource path in `{uri}`.", error);
                }
                return NormalizeResourcePath(decoded);
            }

            private static string NormalizeResourcePath(string path)
            {
                if (path.IndexOf('\0') >= 0 || path.IndexOf('\\') >= 0)
                    throw new UriFormatException("Resource paths cannot contain NUL or backslash characters.");
                var segments = path.Trim('/').Split('/', StringSplitOptions.RemoveEmptyEntries);
                if (segments.Any(segment => segment is "." or ".."))
                    throw new UriFormatException("Resource paths cannot contain traversal segments.");
                return string.Join('/', segments);
            }
        }
    }
}
