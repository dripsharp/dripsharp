// Idiomatic .NET assembly module and embedded-resource loading for Pkl.Core.
// This is durable destination product code; translated Java output remains
// disposable. The shared index deliberately performs no Pkl evaluation.
#nullable enable

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;

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
    public sealed partial class ModuleKeyFactories
    {
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
    public sealed partial class ResourceReaders
    {
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
            return Runtime.JavaOptional<object>.Of(new Resource(
                uri, bytes.Select(value => unchecked((sbyte)value)).ToArray()));
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
