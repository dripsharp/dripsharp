// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Idiomatic .NET assembly module and embedded-resource loading for Brine's Pkl surface.
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

namespace DripSharp.Brine
{
    internal static class DotNetBytes
    {
        internal static byte[] Unsigned(sbyte[] values) =>
            values.Select(value => unchecked((byte)value)).ToArray();
        internal static byte[] Unsigned(byte[] values) => values;
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

namespace DripSharp.Brine.Runtime
{
    internal sealed class ReaderBaseAdapter : ReaderBase
    {
        private readonly Module.ModuleKey? moduleKey;
        private readonly Resource.ResourceReader? resourceReader;

        internal ReaderBaseAdapter(Module.ModuleKey reader) => moduleKey = reader;
        internal ReaderBaseAdapter(Resource.ResourceReader reader) => resourceReader = reader;

        private bool IsModule => moduleKey is not null;

        public bool HasHierarchicalUris() => IsModule
            ? moduleKey!.HasHierarchicalUris()
            : resourceReader!.HasHierarchicalUris();
        public bool IsGlobbable() => IsModule
            ? moduleKey!.IsGlobbable()
            : resourceReader!.IsGlobbable();
        public bool HasFragmentPaths() => IsModule
            ? moduleKey!.HasFragmentPaths()
            : resourceReader!.HasFragmentPaths();
        public bool HasElement(SecurityManager securityManager, Uri elementUri) => IsModule
            ? moduleKey!.HasElement(securityManager, elementUri)
            : resourceReader!.HasElement(securityManager, elementUri);
        public IReadOnlyList<Module.PathElement> ListElements(
            SecurityManager securityManager, Uri baseUri) => IsModule
                ? moduleKey!.ListElements(securityManager, baseUri)
                : resourceReader!.ListElements(securityManager, baseUri);
        public Uri ResolveUri(Uri baseUri, Uri uri) => IsModule
            ? moduleKey!.ResolveUri(baseUri, uri)
            : resourceReader!.ResolveUri(baseUri, uri);
    }
}

namespace DripSharp.Brine
{
    internal sealed partial record class CommandSpec
    {
        internal sealed partial record class Result
        {
            internal Result(byte[] outputBytes, IDictionary<string, FileOutput> outputFiles)
                : this(outputBytes.Select(value => unchecked((sbyte)value)).ToArray(), outputFiles)
            {
            }
        }
    }
}

namespace DripSharp.Brine.Util
{
    internal sealed partial class GlobResolver
    {
        internal static IDictionary<string, ResolvedGlobElement> ResolveGlob(
            SecurityManager securityManager, Module.ModuleKey reader,
            Module.ModuleKey? enclosingModuleKey, Uri? enclosingUri, string globPattern) =>
            ResolveGlob(securityManager, new Runtime.ReaderBaseAdapter(reader),
                enclosingModuleKey, enclosingUri, globPattern);

        internal static IDictionary<string, ResolvedGlobElement> ResolveGlob(
            SecurityManager securityManager, Resource.ResourceReader reader,
            Module.ModuleKey? enclosingModuleKey, Uri? enclosingUri, string globPattern) =>
            ResolveGlob(securityManager, new Runtime.ReaderBaseAdapter(reader),
                enclosingModuleKey, enclosingUri, globPattern);
    }

    internal sealed partial class IoUtils
    {
        internal static Uri Resolve(Module.ModuleKey reader, Uri baseUri, string uri) =>
            Resolve(reader, baseUri, CreateUri(uri));

        internal static Uri Resolve(Resource.ResourceReader reader, Uri baseUri, string uri) =>
            Resolve(reader, baseUri, CreateUri(uri));
    }
}

namespace DripSharp.Brine
{
    public sealed partial record class Platform
    {
        static Platform()
        {
            var assemblyVersion = typeof(Platform).Assembly.GetName().Version?.ToString() ?? "unknown";
            var runtime = global::System.Runtime.InteropServices.RuntimeInformation.FrameworkDescription;
            var osName = global::DripSharp.Runtime.JavaCompat.IsWindows() ? "Windows" :
                global::DripSharp.Runtime.JavaCompat.IsMacOS() ? "macOS" :
                global::DripSharp.Runtime.JavaCompat.IsLinux() ? "Linux" :
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

namespace DripSharp.Brine.Http
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

namespace DripSharp.Brine.Util.Json
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

namespace DripSharp.Brine.Http
{
    internal sealed partial class JdkHttpClient
    {
        static JdkHttpClient()
        {
            closeMethod = new global::DripSharp.Runtime.JavaMethodHandle(
                arguments =>
                {
                    ((Runtime.JavaHttpClient)arguments[0]!).Dispose();
                    return null;
                },
                new global::DripSharp.Runtime.JavaMethodType(
                    typeof(void), typeof(Runtime.JavaHttpClient)));
        }
    }
}

namespace DripSharp.Brine.Module
{
    public partial interface ModuleKey
    {
        public Uri Uri { get; }
        public bool Cached { get; }
        public bool Local { get; }
        public string? FileCachePath { get; }
        public bool HasHierarchicalUris();
        public bool IsGlobbable();
        public bool HasElement(SecurityManager securityManager, Uri elementUri);
        public IReadOnlyList<PathElement> ListElements(SecurityManager securityManager, Uri baseUri);
        public bool HasFragmentPaths();
        public Uri ResolveUri(Uri baseUri, Uri uri);
    }

    public partial interface ResolvedModuleKey
    {
        public ModuleKey Original { get; }
        public Uri Uri { get; }
        public string Source { get; }
    }

    public abstract partial class ModuleKeyFactory
    {
        public ModuleKey? TryCreate(Uri uri)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(uri);
            return Create(uri);
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
                DripSharp.Brine.DotNetCollections.ReadOnly(GetChildrenValues());
        }
    }

    public sealed partial class ProjectDependenciesManager
    {
        public IReadOnlyDictionary<string, DripSharp.Brine.Packages.Dependency> Dependencies =>
            DripSharp.Brine.DotNetCollections.ReadOnly(GetDependencies());
        public DripSharp.Brine.Project.DeclaredDependencies DeclaredDependencies =>
            GetDeclaredDependencies();
        public Uri ProjectBaseUri => GetProjectBaseUri();
        public Uri ProjectDependenciesFileUri => GetProjectDepsFileUri();
        public Uri ProjectFileUri => GetProjectFileUri();
    }

    internal sealed class AssemblyModuleKeyFactory : ModuleKeyFactory
    {
        private readonly DotNetLoading.AssemblyResourceIndex resources;
        private readonly string uriScheme;
        private bool disposed;

        public AssemblyModuleKeyFactory(
            Assembly assembly,
            string resourcePrefix = "",
            string uriScheme = "assembly")
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(assembly);
            this.uriScheme = DotNetLoading.ValidateScheme(uriScheme, nameof(uriScheme));
            resources = new DotNetLoading.AssemblyResourceIndex(assembly, resourcePrefix);
        }

        public override ModuleKey? Create(Uri uri)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfDisposed(disposed, this);
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(uri);
            if (!string.Equals(uri.Scheme, uriScheme, StringComparison.OrdinalIgnoreCase))
                return null;
            return new AssemblyModuleKey(uri, resources);
        }

        public override void Close() => disposed = true;
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
        public Uri Uri => GetUri();
        public bool Cached => IsCached();
        public bool Local => IsLocal();
        public string? FileCachePath => GetFileCacheLocation();
        public bool HasHierarchicalUris() => true;
        public bool IsGlobbable() => true;
        public bool IsCached() => true;
        public bool IsLocal() => true;
        public string? GetFileCacheLocation() => null;
        public bool HasFragmentPaths() => false;
        public Uri ResolveUri(Uri value) =>
            DripSharp.Brine.Util.IoUtils.Resolve(this, uri, value);
        public Uri ResolveUri(Uri baseUri, Uri value) =>
            DripSharp.Brine.Util.IoUtils.Resolve(this, baseUri, value);

        public bool HasElement(SecurityManager securityManager, Uri elementUri)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(securityManager);
            securityManager.CheckResolveModule(elementUri);
            return resources.HasElement(elementUri);
        }

        public IReadOnlyList<PathElement> ListElements(SecurityManager securityManager, Uri baseUri)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(securityManager);
            securityManager.CheckResolveModule(baseUri);
            return new List<PathElement>(resources.ListElements(baseUri));
        }

        public ResolvedModuleKey Resolve(SecurityManager securityManager)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(securityManager);
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
        public ModuleKey Original => GetOriginal();
        public Uri Uri => GetUri();
        public string LoadSource() =>
            global::DripSharp.Runtime.JavaCompat.NewString(
                bytes, global::System.Text.Encoding.UTF8);
        public string Source => LoadSource();
    }
}

namespace DripSharp.Brine.Resource
{
    public sealed partial record class Resource
    {
        internal Resource(Uri uri, sbyte[] bytes) : this(uri, DripSharp.Brine.DotNetBytes.Unsigned(bytes)) { }

        public string Text => GetText();
        public string Base64 => GetBase64();
    }

    public abstract partial class ResourceReader
    {
        public abstract bool HasHierarchicalUris();
        public abstract bool IsGlobbable();
        public object? TryRead(Uri uri)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(uri);
            return Read(uri);
        }

        public virtual bool HasElement(SecurityManager securityManager, Uri elementUri) =>
            throw new NotSupportedException();
        public virtual IReadOnlyList<Module.PathElement> ListElements(
            SecurityManager securityManager, Uri baseUri) =>
            throw new NotSupportedException();
        public virtual bool HasFragmentPaths() => false;
        public virtual Uri ResolveUri(Uri baseUri, Uri uri) =>
            DripSharp.Brine.Util.IoUtils.Resolve(this, baseUri, uri);
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
        /// <see cref="DripSharp.Brine.Module.ModuleKeyFactories.CreateAssembly"/>.
        /// </summary>
        public static ResourceReader CreateEmbeddedResources(
            Assembly assembly,
            string resourcePrefix = "",
            string uriScheme = "embedded") =>
            new EmbeddedResourceReader(assembly, resourcePrefix, uriScheme);
    }

    internal sealed class EmbeddedResourceReader : ResourceReader
    {
        private readonly DotNetLoading.AssemblyResourceIndex resources;
        private readonly string uriScheme;
        private bool disposed;

        public EmbeddedResourceReader(
            Assembly assembly,
            string resourcePrefix = "",
            string uriScheme = "embedded")
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(assembly);
            this.uriScheme = DotNetLoading.ValidateScheme(uriScheme, nameof(uriScheme));
            resources = new DotNetLoading.AssemblyResourceIndex(assembly, resourcePrefix);
        }

        public override string GetUriScheme() => uriScheme;
        public override bool HasHierarchicalUris() => true;
        public override bool IsGlobbable() => true;

        public override object? Read(Uri uri)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfDisposed(disposed, this);
            if (!string.Equals(uri.Scheme, uriScheme, StringComparison.OrdinalIgnoreCase) ||
                !resources.TryRead(uri, out var bytes))
                return null;
            return new Resource(uri, bytes);
        }

        public override bool HasElement(SecurityManager securityManager, Uri elementUri)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfDisposed(disposed, this);
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(securityManager);
            securityManager.CheckResolveResource(elementUri);
            return resources.HasElement(elementUri);
        }

        public override IReadOnlyList<Module.PathElement> ListElements(
            SecurityManager securityManager, Uri baseUri)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfDisposed(disposed, this);
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(securityManager);
            securityManager.CheckResolveResource(baseUri);
            return new List<Module.PathElement>(resources.ListElements(baseUri));
        }

        public override void Close() => disposed = true;
    }
}

namespace DripSharp.Brine.Http
{
    public abstract partial class HttpClient
    {
        public HttpResponseMessage Send(
            HttpRequestMessage request,
            HttpRequestChecker requestChecker)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(request);
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(requestChecker);
            return HttpClientCompatibility.SendMessage(this, request, requestChecker);
        }

        public byte[] GetBytes(
            HttpRequestMessage request,
            HttpRequestChecker requestChecker)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(request);
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(requestChecker);
            return HttpClientCompatibility.Send(
                this,
                new Runtime.JavaHttpRequest(request),
                response => global::DripSharp.Runtime.JavaCompat.ReadAsByteArrayAsync(
                    response.Content,
                    global::DripSharp.Runtime.JavaCancellation.CurrentToken).GetAwaiter().GetResult(),
                requestChecker).Body();
        }

        public Stream OpenRead(
            HttpRequestMessage request,
            HttpRequestChecker requestChecker)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(request);
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(requestChecker);
            return new MemoryStream(GetBytes(request, requestChecker), writable: false);
        }

        public abstract partial class Builder
        {
            public Builder AddCertificate(byte[] certificateBytes) =>
                AddCertificates(certificateBytes);

            public Builder AddCertificate(string path) => AddCertificates(path);
        }
    }

    internal static class HttpClientCompatibility
    {
        internal static Runtime.JavaHttpResponse<T> Send<T>(
            HttpClient client,
            Runtime.JavaHttpRequest request,
            Runtime.JavaHttpBodyHandler<T> responseBodyHandler,
            HttpClient.HttpRequestChecker requestChecker)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(client);
            var compatibilityMethod = client.GetType().GetMethods(
                    BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic)
                .SingleOrDefault(method =>
                    method.Name == "SendCompatibility" && method.IsGenericMethodDefinition &&
                    method.GetParameters().Length == 3);
            if (compatibilityMethod is not null)
            {
                try
                {
                    return (Runtime.JavaHttpResponse<T>)compatibilityMethod
                        .MakeGenericMethod(typeof(T))
                        .Invoke(client, new object[] { request, responseBodyHandler, requestChecker })!;
                }
                catch (TargetInvocationException error) when (error.InnerException is not null)
                {
                    System.Runtime.ExceptionServices.ExceptionDispatchInfo
                        .Capture(error.InnerException).Throw();
                    throw;
                }
            }

            var response = client.Send(request.Message, requestChecker);
            return new Runtime.JavaHttpResponse<T>(response, responseBodyHandler(response));
        }

        internal static HttpResponseMessage SendMessage(
            HttpClient client,
            HttpRequestMessage request,
            HttpClient.HttpRequestChecker requestChecker) =>
            Send(client, new Runtime.JavaHttpRequest(request), CloneResponse, requestChecker).Body();

        private static HttpResponseMessage CloneResponse(HttpResponseMessage response)
        {
            if (DripSharp.Brine.Util.HttpUtils.IsRedirectStatusCode((int)response.StatusCode))
                return response;

            var clone = new HttpResponseMessage(response.StatusCode)
            {
                ReasonPhrase = response.ReasonPhrase,
                RequestMessage = response.RequestMessage,
                Version = response.Version,
                Content = new ByteArrayContent(
                    global::DripSharp.Runtime.JavaCompat.ReadAsByteArrayAsync(
                    response.Content,
                    global::DripSharp.Runtime.JavaCancellation.CurrentToken).GetAwaiter().GetResult())
            };
            foreach (var header in response.Headers)
                clone.Headers.TryAddWithoutValidation(header.Key, header.Value);
            foreach (var header in response.Content.Headers)
                clone.Content.Headers.TryAddWithoutValidation(header.Key, header.Value);
            global::DripSharp.Runtime.JavaCompat.CopyTrailingHeaders(response, clone);
            return clone;
        }
    }
}

namespace DripSharp.Brine.Packages
{
    public sealed partial class Checksums
    {
        public string Sha256 => GetSha256();
    }

    public abstract partial class Dependency
    {
        public PackageUri PackageUri => GetPackageUri();
        public DripSharp.Brine.Version Version => GetVersion();

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
        public DripSharp.Brine.Version Version => GetVersion();
        public Checksums? Checksums => GetChecksums();
        public Uri MetadataRequestUri => GetMetadataRequestUri();
        public string DisplayName => GetDisplayName();
    }

    public sealed partial class PackageAssetUri
    {
        public Uri Uri => GetUri();
        public PackageUri PackageUri => GetPackageUri();
        public DripSharp.Brine.Version Version => GetVersion();
        public string AssetPath => GetAssetPath();
    }

    public sealed partial class DependencyMetadata
    {
        public string Name => GetName();
        public PackageUri PackageUri => packageUri;
        public DripSharp.Brine.Version Version => GetVersion();
        public Uri PackageArchiveUri => GetPackageZipUrl();
        public Checksums PackageArchiveChecksums => GetPackageZipChecksums();
        public IReadOnlyDictionary<string, Dependency.RemoteDependency> Dependencies =>
            DripSharp.Brine.DotNetCollections.ReadOnly(GetDependencies());
        public IReadOnlyList<DripSharp.Brine.PObject> Annotations =>
            DripSharp.Brine.DotNetCollections.ReadOnly(GetAnnotations());
        public IReadOnlyList<string>? Authors => GetAuthors() is { } values
            ? DripSharp.Brine.DotNetCollections.ReadOnly(values)
            : null;
    }

    public abstract partial class PackageResolver
    {
        public byte[] GetAssetBytes(
            PackageAssetUri uri, bool allowDirectories = false, Checksums? checksums = null) =>
            DripSharp.Brine.DotNetBytes.Unsigned(GetBytes(uri, allowDirectories, checksums));

        public IReadOnlyList<DripSharp.Brine.Module.PathElement> GetElements(
            PackageAssetUri uri, Checksums? checksums = null) =>
            ListElements(uri, checksums);
    }

    public sealed partial class PackageLoadError
    {
        public string ErrorName => GetMessageName();
        public IReadOnlyList<object>? ErrorArguments => GetArguments() is { } values
            ? Array.AsReadOnly(values)
            : null;
    }
}

namespace DripSharp.Brine.Project
{
    public sealed partial record class DeclaredDependencies
    {
        public IReadOnlyDictionary<string, DripSharp.Brine.Packages.Dependency.RemoteDependency>
            RemoteDependenciesReadOnly =>
                DripSharp.Brine.DotNetCollections.ReadOnly(GetRemoteDependencies());
        public IReadOnlyDictionary<string, DeclaredDependencies> LocalDependenciesReadOnly =>
            DripSharp.Brine.DotNetCollections.ReadOnly(GetLocalDependencies());
    }

    public sealed partial class Project
    {
        public Package? PackageMetadata => GetPackage();
        public DeclaredDependencies DeclaredDependencies => GetDependencies();
        public DripSharp.Brine.EvaluatorSettings.PklEvaluatorSettings EvaluatorConfiguration =>
            GetEvaluatorSettings();
        public DripSharp.Brine.EvaluatorSettings.PklEvaluatorSettings ResolvedEvaluatorConfiguration =>
            GetResolvedEvaluatorSettings();
        public Uri ProjectFileUri => GetProjectFileUri();
        public string ProjectDirectory => GetProjectDir();
        public IReadOnlyList<string> Tests =>
            DripSharp.Brine.DotNetCollections.ReadOnly(GetTests());
        public IReadOnlyDictionary<string, Project> LocalProjectDependencies =>
            DripSharp.Brine.DotNetCollections.ReadOnly(GetLocalProjectDependencies());
        public IReadOnlyList<DripSharp.Brine.PObject> Annotations =>
            DripSharp.Brine.DotNetCollections.ReadOnly(GetAnnotations());

        public partial class EvaluatorSettings
        {
            public IReadOnlyDictionary<string, string>? ExternalProperties =>
                GetExternalProperties() is { } values
                    ? DripSharp.Brine.DotNetCollections.ReadOnly(values)
                    : null;
            public IReadOnlyDictionary<string, string>? Environment =>
                GetEnv() is { } values ? DripSharp.Brine.DotNetCollections.ReadOnly(values) : null;
            public IReadOnlyList<Regex>? AllowedModules =>
                GetAllowedModules() is { } values
                    ? DripSharp.Brine.DotNetCollections.ReadOnly(values)
                    : null;
            public IReadOnlyList<Regex>? AllowedResources =>
                GetAllowedResources() is { } values
                    ? DripSharp.Brine.DotNetCollections.ReadOnly(values)
                    : null;
            public IReadOnlyList<string>? ModulePaths =>
                GetModulePath() is { } values
                    ? DripSharp.Brine.DotNetCollections.ReadOnly(values)
                    : null;
        }
    }
}

namespace DripSharp.Brine.EvaluatorSettings
{
    public sealed partial record class PklEvaluatorSettings
    {
        public IReadOnlyDictionary<string, string>? ExternalPropertiesReadOnly =>
            ExternalProperties is { } values ? DripSharp.Brine.DotNetCollections.ReadOnly(values) : null;
        public IReadOnlyDictionary<string, string>? Environment =>
            Env is { } values ? DripSharp.Brine.DotNetCollections.ReadOnly(values) : null;
        public IReadOnlyList<Regex>? AllowedModulesReadOnly =>
            AllowedModules is { } values ? DripSharp.Brine.DotNetCollections.ReadOnly(values) : null;
        public IReadOnlyList<Regex>? AllowedResourcesReadOnly =>
            AllowedResources is { } values ? DripSharp.Brine.DotNetCollections.ReadOnly(values) : null;
        public IReadOnlyList<string>? ModulePaths =>
            ModulePath is { } values ? DripSharp.Brine.DotNetCollections.ReadOnly(values) : null;
        public IReadOnlyDictionary<string, ExternalReader>? ExternalModuleReadersReadOnly =>
            ExternalModuleReaders is { } values
                ? DripSharp.Brine.DotNetCollections.ReadOnly(values)
                : null;
        public IReadOnlyDictionary<string, ExternalReader>? ExternalResourceReadersReadOnly =>
            ExternalResourceReaders is { } values
                ? DripSharp.Brine.DotNetCollections.ReadOnly(values)
                : null;
        public Http? HttpSettings => HttpValue;

        public sealed partial record class Http
        {
            public IReadOnlyDictionary<Uri, Uri>? RewritesReadOnly =>
                Rewrites is { } values ? DripSharp.Brine.DotNetCollections.ReadOnly(values) : null;
        }

        public sealed partial record class Proxy
        {
            public IReadOnlyList<string>? NoProxyReadOnly =>
                NoProxy is { } values ? DripSharp.Brine.DotNetCollections.ReadOnly(values) : null;
        }

        public sealed partial record class ExternalReader
        {
            public IReadOnlyList<string>? ArgumentsReadOnly =>
                Arguments is { } values ? DripSharp.Brine.DotNetCollections.ReadOnly(values) : null;
        }
    }
}

namespace DripSharp.Brine.Settings
{
    public sealed partial record class PklSettings
    {
        public Editor EditorSettings => GetEditor();
        public DripSharp.Brine.EvaluatorSettings.PklEvaluatorSettings.Http? HttpSettings => Http;
    }
}

namespace DripSharp.Brine.Externalreader
{
    public abstract partial class ExternalReaderProcess
    {
        public static ExternalReaderProcess Start(
            DripSharp.Brine.EvaluatorSettings.PklEvaluatorSettings.ExternalReader specification) =>
            Of(specification);
    }

    public abstract partial class ExternalResourceResolver
    {
        public object? TryRead(Uri uri)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfNull(uri);
            return Read(uri);
        }
    }
}

namespace DripSharp.Brine.Util
{
    internal sealed partial class IoUtils
    {
        internal static Uri Resolve(
            DripSharp.Brine.Module.ModuleKey reader, Uri baseUri, Uri importUri) =>
            ResolveProductReader(reader.HasFragmentPaths(), baseUri, importUri);

        internal static Uri Resolve(
            DripSharp.Brine.Resource.ResourceReader reader, Uri baseUri, Uri importUri) =>
            ResolveProductReader(reader.HasFragmentPaths(), baseUri, importUri);

        private static Uri ResolveProductReader(
            bool hasFragmentPaths, Uri baseUri, Uri importUri)
        {
            if (hasFragmentPaths && !importUri.IsAbsoluteUri &&
                global::DripSharp.Runtime.JavaCompat.UriPath(importUri) is not null)
            {
                var fragment = global::DripSharp.Runtime.JavaCompat.UriFragment(baseUri);
                var newFragment = Resolve(CreateUri(fragment), importUri);
                return global::DripSharp.Runtime.JavaCompat.ResolveUri(
                    StripFragment(baseUri), "#" + newFragment);
            }
            return Resolve(baseUri, importUri);
        }
    }
}

namespace DripSharp.Brine
{
    internal static class DotNetLoading
    {
        internal static string ValidateScheme(string value, string parameterName)
        {
            global::DripSharp.Runtime.JavaCompat.ThrowIfNullOrWhiteSpace(value, parameterName);
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
                yield return string.Join("/", parts.Take(parts.Length - 2)) + "/" +
                    parts[^2] + "." + parts[^1];
            }

            private static string UriPath(Uri uri)
            {
                global::DripSharp.Runtime.JavaCompat.ThrowIfNull(uri);
                if (!uri.IsAbsoluteUri)
                    throw new UriFormatException($"Expected an absolute resource URI, got `{uri}`.");
                if (global::DripSharp.Runtime.JavaCompat.UriPath(uri) is null)
                    throw new UriFormatException(
                        $"Resource URI `{uri}` is missing a `/` after its scheme.");
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
                var segments = path.Trim('/').Split(new[] { '/' }, StringSplitOptions.RemoveEmptyEntries);
                if (segments.Any(segment => segment is "." or ".."))
                    throw new UriFormatException("Resource paths cannot contain traversal segments.");
                return string.Join("/", segments);
            }
        }
    }
}
