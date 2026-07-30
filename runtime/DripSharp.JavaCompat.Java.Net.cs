// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Ordinary generated-product support for Java contracts with no direct .NET API.
// Each JDK-area source is copied unchanged into disposable projects; these files
// are not a second AST and contain no destination-product behavior.
#nullable enable

using System;
using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.IO.MemoryMappedFiles;
using System.Linq;
using System.Reflection;
using System.Resources;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.RegularExpressions;
using System.Numerics;
using System.Threading;
using System.Threading.Tasks;
using System.Xml;

namespace DripSharp.Runtime;

// JDK compatibility area: Java.Net

internal sealed class JavaUriSyntaxException : UriFormatException
{
    internal string InputText { get; }
    internal string Reason { get; }
    internal int Index { get; }

    internal JavaUriSyntaxException(string input, string reason, int index = -1)
        : base(index < 0 ? $"{reason}: {input}" : $"{reason} at index {index}: {input}")
    {
        InputText = input;
        Reason = reason;
        Index = index;
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaSslContext
{
    private static readonly JavaSslContext defaultContext = CreateDefault();
    private readonly string protocol;
    private JavaSocketFactory? socketFactory;
    private JavaSslServerSocketFactory? serverSocketFactory;

    private JavaSslContext(string protocol) => this.protocol = protocol;

    private static JavaSslContext CreateDefault()
    {
        var context = new JavaSslContext("TLS");
        context.socketFactory = JavaSocketFactory.Default;
        return context;
    }

    public static JavaSslContext GetDefault() => defaultContext;

    public static JavaSslContext GetInstance(string protocol)
    {
        ArgumentNullException.ThrowIfNull(protocol);
        if (!string.Equals(protocol, "TLS", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(protocol, "SSL", StringComparison.OrdinalIgnoreCase))
            throw new System.Security.Cryptography.CryptographicException(
                $"Unsupported SSLContext protocol: {protocol}");
        return new JavaSslContext(protocol);
    }

    internal void Init(object[]? keyManagers, object[]? trustManagers, object? secureRandom)
    {
        var serverCertificate = keyManagers?.OfType<JavaKeyManager>()
            .Select(manager => manager.ServerCertificate)
            .FirstOrDefault();
        var trustedRoots = trustManagers?.OfType<JavaTrustManager>()
            .Select(manager => manager.Certificates)
            .FirstOrDefault();
        var customTrustManager = trustManagers?.OfType<JavaX509TrustManager>().FirstOrDefault();
        socketFactory = new JavaSocketFactory(tls: true, trustedRoots, customTrustManager);
        serverSocketFactory = new JavaSslServerSocketFactory(serverCertificate);
    }

    internal JavaSocketFactory GetSocketFactory() => socketFactory ??
        throw new InvalidOperationException($"SSLContext {protocol} is not initialized.");

    internal JavaSslServerSocketFactory GetServerSocketFactory() => serverSocketFactory ??
        throw new InvalidOperationException($"SSLContext {protocol} is not initialized.");
}

internal sealed class JavaSocketFactory
{
    internal static readonly JavaSocketFactory Plain = new(false);
    internal static readonly JavaSocketFactory Default = new(true);
    private readonly bool tls;
    private readonly System.Security.Cryptography.X509Certificates.X509Certificate2Collection? trustedRoots;
    private readonly JavaX509TrustManager? customTrustManager;

    internal JavaSocketFactory(
        bool tls,
        System.Security.Cryptography.X509Certificates.X509Certificate2Collection? trustedRoots = null,
        JavaX509TrustManager? customTrustManager = null)
    {
        this.tls = tls;
        this.trustedRoots = trustedRoots;
        this.customTrustManager = customTrustManager;
    }

    internal System.Net.Sockets.Socket CreateSocket(string host, int port)
    {
        var socket = new System.Net.Sockets.Socket(
            System.Net.Sockets.SocketType.Stream,
            System.Net.Sockets.ProtocolType.Tcp);
        try
        {
            socket.Connect(host, port);
            Stream stream = new System.Net.Sockets.NetworkStream(socket, ownsSocket: false);
            if (tls)
            {
                var secure = new System.Net.Security.SslStream(
                    stream,
                    leaveInnerStreamOpen: false,
                    trustedRoots is null && customTrustManager is null
                        ? null
                        : ValidateRemoteCertificate);
                secure.AuthenticateAsClient(host);
                stream = secure;
            }
            JavaCompat.RegisterSocketStream(socket, stream);
            return socket;
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }

    internal System.Net.Sockets.Socket CreateSocket()
    {
        var socket = new System.Net.Sockets.Socket(
            System.Net.Sockets.SocketType.Stream,
            System.Net.Sockets.ProtocolType.Tcp);
        JavaCompat.RegisterPendingSocketFactory(socket, this);
        return socket;
    }

    internal Stream OpenStream(System.Net.Sockets.Socket socket)
    {
        var stream = new System.Net.Sockets.NetworkStream(socket, ownsSocket: false);
        if (!tls) return stream;
        var secure = new System.Net.Security.SslStream(
            stream,
            leaveInnerStreamOpen: false,
            trustedRoots is null && customTrustManager is null
                ? null
                : ValidateRemoteCertificate);
        var host = (socket.RemoteEndPoint as System.Net.IPEndPoint)?.Address.ToString() ??
            throw new InvalidOperationException("An unconnected SSL socket has no remote host.");
        secure.AuthenticateAsClient(host);
        return secure;
    }

    private bool ValidateRemoteCertificate(
        object sender,
        System.Security.Cryptography.X509Certificates.X509Certificate? certificate,
        System.Security.Cryptography.X509Certificates.X509Chain? chain,
        System.Net.Security.SslPolicyErrors errors)
    {
        if (certificate is null)
            return false;
        if (customTrustManager is not null)
        {
            var certificates = chain?.ChainElements
                .Cast<System.Security.Cryptography.X509Certificates.X509ChainElement>()
                .Select(element => element.Certificate)
                .ToArray() ?? new[] {
                    new System.Security.Cryptography.X509Certificates.X509Certificate2(certificate)
                };
            customTrustManager.CheckServerTrusted(certificates, certificate.GetKeyAlgorithm());
            return true;
        }
        if (trustedRoots is null ||
            (errors & System.Net.Security.SslPolicyErrors.RemoteCertificateNameMismatch) != 0)
            return false;
        using var candidate =
            new System.Security.Cryptography.X509Certificates.X509Certificate2(certificate);
        using var customChain = new System.Security.Cryptography.X509Certificates.X509Chain();
        customChain.ChainPolicy.TrustMode =
            System.Security.Cryptography.X509Certificates.X509ChainTrustMode.CustomRootTrust;
        customChain.ChainPolicy.CustomTrustStore.AddRange(trustedRoots);
        customChain.ChainPolicy.RevocationMode =
            System.Security.Cryptography.X509Certificates.X509RevocationMode.NoCheck;
        return customChain.Build(candidate);
    }

    internal System.Net.Sockets.Socket CreateSocket(System.Net.IPAddress address, int port)
    {
        var socket = new System.Net.Sockets.Socket(
            address.AddressFamily,
            System.Net.Sockets.SocketType.Stream,
            System.Net.Sockets.ProtocolType.Tcp);
        try
        {
            socket.Connect(address, port);
            JavaCompat.RegisterSocketStream(
                socket, new System.Net.Sockets.NetworkStream(socket, ownsSocket: false));
            return socket;
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }
}

internal sealed class JavaSslServerSocketFactory
{
    private readonly System.Security.Cryptography.X509Certificates.X509Certificate2? serverCertificate;

    internal JavaSslServerSocketFactory(
        System.Security.Cryptography.X509Certificates.X509Certificate2? serverCertificate) =>
        this.serverCertificate = serverCertificate;

    internal JavaServerSocket CreateServerSocket(int port) =>
        new(port, tls: true, serverCertificate: serverCertificate);
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaServerSocket : IDisposable
{
    private readonly System.Net.Sockets.TcpListener listener;
    private readonly bool tls;
    private readonly System.Security.Cryptography.X509Certificates.X509Certificate2? serverCertificate;
    private int closed;

    internal JavaServerSocket(int port) : this(port, tls: false, serverCertificate: null) { }

    internal JavaServerSocket(
        int port,
        bool tls,
        System.Security.Cryptography.X509Certificates.X509Certificate2? serverCertificate)
    {
        this.tls = tls;
        this.serverCertificate = serverCertificate;
        listener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Any, port);
        listener.Start();
    }

    internal System.Net.Sockets.Socket Accept()
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref closed) != 0, this);
        var socket = listener.AcceptSocket();
        if (!tls) return socket;
        try
        {
            if (serverCertificate is null)
                throw new InvalidOperationException(
                    "The SSLContext has no server certificate configured.");
            var secure = new System.Net.Security.SslStream(
                new System.Net.Sockets.NetworkStream(socket, ownsSocket: false),
                leaveInnerStreamOpen: false);
            secure.AuthenticateAsServer(serverCertificate);
            JavaCompat.RegisterSocketStream(socket, secure);
            return socket;
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }

    internal bool IsClosed() => Volatile.Read(ref closed) != 0;

    internal void Close()
    {
        if (Interlocked.Exchange(ref closed, 1) == 0) listener.Stop();
    }

    public void Dispose() => Close();
}


internal static partial class JavaCompat
{
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<
        System.Net.Sockets.Socket, Stream> SocketStreams = new();
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<
        System.Net.Sockets.Socket, JavaSocketFactory> PendingSocketFactories = new();
    private static readonly System.Net.Http.HttpClient UrlClient = new();
    private sealed class JavaUriText(string value)
    {
        internal string Value { get; } = value;
    }

    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Uri, object>
        SingleSlashFileUris = new();
    private static readonly System.Runtime.CompilerServices.ConditionalWeakTable<Uri, JavaUriText>
        OriginalUriTexts = new();
    private static void ValidateJavaUriText(string value)
    {
        for (var index = 0; index < value.Length; index++)
        {
            var current = value[index];
            if (current <= 0x20 || current == 0x7f || current == '^')
                throw new JavaUriSyntaxException(value, "Illegal character in path", index);
            if (current != '%') continue;
            if (index + 2 >= value.Length ||
                !Uri.IsHexDigit(value[index + 1]) || !Uri.IsHexDigit(value[index + 2]))
                throw new JavaUriSyntaxException(value, "Malformed escape pair", index);
            index += 2;
        }
    }

    internal static Uri CreateUri(string value)
    {
        ValidateJavaUriText(value);
        if (Regex.IsMatch(value, @"(?i)^file:[^/]"))
        {
            // System.Uri rejects Java's opaque `file:path` form before the
            // translated file reader can apply its purpose-built diagnostic.
            // Keep a valid CLR carrier while retaining the Java URI spelling
            // and opaque semantics through the compatibility accessors.
            var opaqueFile = new Uri("file:///" + value["file:".Length..], UriKind.Absolute);
            _ = OriginalUriTexts.GetValue(opaqueFile, _ => new JavaUriText(value));
            return opaqueFile;
        }
        if (Regex.IsMatch(value, @"(?i)^file:/[^/]"))
        {
            var singleSlash = new Uri("file:///" + value["file:/".Length..], UriKind.Absolute);
            _ = SingleSlashFileUris.GetValue(singleSlash, _ => new object());
            return singleSlash;
        }
        if (Regex.IsMatch(value, @"(?i)^file:///[a-z]:$"))
        {
            var driveOnly = new Uri(value + "/", UriKind.Absolute);
            _ = OriginalUriTexts.GetValue(driveOnly, _ => new JavaUriText(value));
            return driveOnly;
        }
        if (!value.StartsWith("file:", StringComparison.OrdinalIgnoreCase) &&
            Regex.IsMatch(value, @"^[A-Za-z][A-Za-z0-9+.-]*:///"))
        {
            // java.net.URI accepts an absolute hierarchical URI with an empty
            // authority (for example, `http:///path`). System.Uri rejects the
            // same spelling because HTTP requires a host. Preserve the Java
            // text and its empty authority on an otherwise valid CLR carrier;
            // the URI accessors below read the preserved spelling.
            var authority = value.IndexOf(":///", StringComparison.Ordinal);
            var carrier = new Uri(
                value[..(authority + 3)] + "dripsharp.invalid/" + value[(authority + 4)..],
                UriKind.Absolute);
            _ = OriginalUriTexts.GetValue(carrier, _ => new JavaUriText(value));
            return carrier;
        }
        if (Regex.IsMatch(value, @"(?i)(?:^|/)%2e(?:%2e)?(?:/|$)"))
        {
            var options = new UriCreationOptions
            {
                DangerousDisablePathAndQueryCanonicalization = true
            };
            return new Uri(value, in options);
        }
        return new Uri(value, UriKind.RelativeOrAbsolute);
    }
    internal static string NewString(char[] value) => new(value);
    internal static string NewString(char[] value, int offset, int count) => new(value, offset, count);
    internal static string NewString(int[] codePoints, int offset, int count) =>
        string.Concat(codePoints.Skip(offset).Take(count).Select(CodePointToString));
    internal static string NewString(sbyte[] value, Encoding encoding) =>
        DecodeJavaBytes(value.Select(item => unchecked((byte)item)).ToArray(), encoding);
    internal static string NewString(sbyte[] value, int offset, int count, Encoding encoding) =>
        DecodeJavaBytes(
            value.Skip(offset).Take(count).Select(item => unchecked((byte)item)).ToArray(),
            encoding);
    internal static string NewString(byte[] value, Encoding encoding) =>
        DecodeJavaBytes(value, encoding);
    internal static string NewString(object value) => StringValueOf(value);

    private static string DecodeJavaBytes(byte[] value, Encoding encoding)
    {
        if (!ReferenceEquals(encoding, JavaStandardCharsets.UTF16))
            return encoding.GetString(value);
        if (value.Length >= 2 && value[0] == 0xfe && value[1] == 0xff)
            return Encoding.BigEndianUnicode.GetString(value, 2, value.Length - 2);
        if (value.Length >= 2 && value[0] == 0xff && value[1] == 0xfe)
            return Encoding.Unicode.GetString(value, 2, value.Length - 2);
        return Encoding.BigEndianUnicode.GetString(value);
    }

    internal static Uri NewUri(string value) => CreateUri(value);
    internal static string UriToString(Uri value) =>
        OriginalUriTexts.TryGetValue(value, out var original)
            ? original.Value
            : SingleSlashFileUris.TryGetValue(value, out _) && value.IsAbsoluteUri && value.IsFile
            ? "file:" + value.AbsolutePath + value.Query + value.Fragment
            : value.IsAbsoluteUri && value.IsFile &&
              !value.OriginalString.StartsWith("file:", StringComparison.OrdinalIgnoreCase)
            // Idiomatic .NET callers commonly construct a file URI directly
            // from an absolute path. System.Uri keeps that bare path as its
            // OriginalString even though the URI's scheme is `file`; Java's
            // URI.toString() carrier must expose the scheme for allowlist and
            // other URI-pattern behavior. Explicit Java URI spellings were
            // handled by the preserved-text branches above.
            ? value.AbsoluteUri
            : value.OriginalString;

    internal static bool UriUsesSingleSlashFileSyntax(Uri value) =>
        SingleSlashFileUris.TryGetValue(value, out _);

    private static bool IsUriUnreserved(char value) =>
        value is >= 'a' and <= 'z' or >= 'A' and <= 'Z' or >= '0' and <= '9' or
            '-' or '.' or '_' or '~';

    private static string QuoteUriComponent(string value, string allowedPunctuation)
    {
        StringBuilder? result = null;
        for (var index = 0; index < value.Length; index++)
        {
            var current = value[index];
            if (IsUriUnreserved(current) || allowedPunctuation.Contains(current) ||
                (current > 0x7f && !char.IsControl(current) && !char.IsWhiteSpace(current)))
            {
                result?.Append(current);
                continue;
            }

            result ??= new StringBuilder(value.Length + 8).Append(value, 0, index);
            foreach (var octet in Encoding.UTF8.GetBytes(new[] { current }))
                result.Append('%').Append(octet.ToString("X2", CultureInfo.InvariantCulture));
        }
        return result?.ToString() ?? value;
    }

    internal static Uri NewUri(string? scheme, string? schemeSpecificPart, string? fragment)
    {
        var text = (scheme is null ? string.Empty : scheme + ":") +
                   QuoteUriComponent(schemeSpecificPart ?? string.Empty, ":/?[]@!$&'()*+,;=");
        if (fragment is not null)
            text += "#" + QuoteUriComponent(fragment, ":@/?!$&'()*+,;=");
        return CreateUri(text);
    }
    internal static UriFormatException NewUriSyntaxException(string input, string reason) =>
        new JavaUriSyntaxException(input, reason);
    internal static UriFormatException NewUriSyntaxException(string input, string reason, int index) =>
        new JavaUriSyntaxException(input, reason, index);
    internal static string UriSyntaxReason(UriFormatException error) =>
        error is JavaUriSyntaxException syntax ? syntax.Reason : error.Message;
    internal static int UriSyntaxIndex(UriFormatException error) =>
        error is JavaUriSyntaxException syntax ? syntax.Index : -1;
    internal static string UriSyntaxInput(UriFormatException error)
    {
        if (error is JavaUriSyntaxException syntax) return syntax.InputText;
        var separator = error.Message.LastIndexOf(": ", StringComparison.Ordinal);
        return separator >= 0 ? error.Message[(separator + 2)..] : error.Message;
    }
    internal static Uri NewUri(string? scheme, string? host, string? path, string? fragment)
        => NewUri(scheme, null, host, -1, path, null, fragment);

    internal static Uri NewUri(string? scheme, string? userInfo, string? host, int port,
        string? path, string? query, string? fragment)
    {
        if (host is null && (userInfo is not null || port != -1))
            throw new UriFormatException("User info and port require a URI host.");
        var text = scheme is null ? string.Empty : scheme + ":";
        if (host is not null)
        {
            text += "//";
            if (userInfo is not null)
                text += QuoteUriComponent(userInfo, ":!$&'()*+,;=") + "@";
            text += host;
            if (port != -1) text += ":" + port.ToString(CultureInfo.InvariantCulture);
        }
        text += QuoteUriComponent(path ?? string.Empty, ":@/!$&'()*+,;=");
        if (query is not null)
            text += "?" + QuoteUriComponent(query, ":@/?!$&'()*+,;=");
        if (fragment is not null)
            text += "#" + QuoteUriComponent(fragment, ":@/?!$&'()*+,;=");
        return CreateUri(text);
    }

    private static string UriTextBeforeFragment(Uri uri)
    {
        var text = OriginalUriTexts.TryGetValue(uri, out var original)
            ? original.Value
            : uri.OriginalString;
        var fragment = text.IndexOf('#');
        return fragment < 0 ? text : text[..fragment];
    }

    internal static string? UriScheme(Uri uri) => uri.IsAbsoluteUri ? uri.Scheme : null;

    private static string? DecodeUriComponent(string? value) =>
        value is null ? null : Uri.UnescapeDataString(value);

    internal static string? UriRawSchemeSpecificPart(Uri uri)
    {
        var text = UriTextBeforeFragment(uri);
        var colon = text.IndexOf(':');
        return colon < 0 ? text : text[(colon + 1)..];
    }

    internal static string? UriSchemeSpecificPart(Uri uri) =>
        DecodeUriComponent(UriRawSchemeSpecificPart(uri));

    internal static string? UriRawFragment(Uri uri)
    {
        var text = uri.OriginalString;
        var marker = text.IndexOf('#');
        return marker < 0 ? null : text[(marker + 1)..];
    }

    internal static string? UriFragment(Uri uri) => DecodeUriComponent(UriRawFragment(uri));

    internal static string? UriRawQuery(Uri uri)
    {
        if (UriIsOpaque(uri)) return null;
        var schemeSpecificPart = UriRawSchemeSpecificPart(uri) ?? string.Empty;
        var marker = schemeSpecificPart.IndexOf('?');
        return marker < 0 ? null : schemeSpecificPart[(marker + 1)..];
    }

    internal static string? UriQuery(Uri uri) => DecodeUriComponent(UriRawQuery(uri));

    internal static string? UriRawAuthority(Uri uri)
    {
        if (UriIsOpaque(uri)) return null;
        var schemeSpecificPart = UriRawSchemeSpecificPart(uri) ?? string.Empty;
        if (!schemeSpecificPart.StartsWith("//", StringComparison.Ordinal)) return null;
        var end = schemeSpecificPart.Length;
        var slash = schemeSpecificPart.IndexOf('/', 2);
        var query = schemeSpecificPart.IndexOf('?', 2);
        if (slash >= 0) end = Math.Min(end, slash);
        if (query >= 0) end = Math.Min(end, query);
        return end == 2 ? null : schemeSpecificPart[2..end];
    }

    internal static string? UriAuthority(Uri uri) => DecodeUriComponent(UriRawAuthority(uri));

    internal static string? UriHost(Uri uri)
    {
        if (OriginalUriTexts.TryGetValue(uri, out _) && UriRawAuthority(uri) is null)
            return null;
        return uri.IsAbsoluteUri && !string.IsNullOrEmpty(uri.Host) ? uri.Host : null;
    }

    internal static string? UriRawUserInfo(Uri uri)
    {
        var authority = UriRawAuthority(uri);
        if (authority is null) return null;
        var marker = authority.LastIndexOf('@');
        return marker < 0 ? null : authority[..marker];
    }

    internal static string? UriUserInfo(Uri uri) => DecodeUriComponent(UriRawUserInfo(uri));

    internal static int UriPort(Uri uri)
    {
        if (!uri.IsAbsoluteUri) return -1;
        var authority = UriRawAuthority(uri);
        if (authority is null) return -1;
        var userInfo = authority.LastIndexOf('@');
        if (userInfo >= 0) authority = authority[(userInfo + 1)..];
        var closeBracket = authority.LastIndexOf(']');
        var colon = authority.LastIndexOf(':');
        return colon > closeBracket && int.TryParse(authority[(colon + 1)..], out var port) ? port : -1;
    }

    internal static string? UriRawPath(Uri uri)
    {
        if (UriIsOpaque(uri)) return null;
        var schemeSpecificPart = UriRawSchemeSpecificPart(uri) ?? string.Empty;
        var query = schemeSpecificPart.IndexOf('?');
        var pathEnd = query < 0 ? schemeSpecificPart.Length : query;
        if (schemeSpecificPart.StartsWith("//", StringComparison.Ordinal))
        {
            var pathStart = schemeSpecificPart.IndexOf('/', 2);
            return pathStart < 0 || pathStart >= pathEnd
                ? string.Empty
                : schemeSpecificPart[pathStart..pathEnd];
        }
        return schemeSpecificPart[..pathEnd];
    }

    internal static string? UriPath(Uri uri) => DecodeUriComponent(UriRawPath(uri));

    internal static Uri ResolveUri(Uri basis, string value) => ResolveUri(basis, CreateUri(value));
    internal static Uri ResolveUri(Uri basis, Uri value)
    {
        if (value.IsAbsoluteUri) return value;
        // java.net.URI.resolve("") resolves to the base URI's containing
        // directory; System.Uri otherwise preserves the base file itself.
        if (value.OriginalString.Length == 0) value = CreateUri(".");
        if (OriginalUriTexts.TryGetValue(basis, out var originalBasis) &&
            Regex.IsMatch(originalBasis.Value, @"(?i)^file:///[a-z]:$") &&
            value.OriginalString == ".")
            return new Uri("file:///", UriKind.Absolute);
        // java.net.URI.resolve leaves a relative reference relative when the
        // base URI is opaque. System.Uri instead interprets it as a new opaque
        // scheme-specific part (for example, `repl:foo.config`).
        if (basis.IsAbsoluteUri && UriIsOpaque(basis)) return value;
        if (basis.IsAbsoluteUri) return new Uri(basis, value);
        var basisText = basis.OriginalString;
        var rooted = basisText.StartsWith("/", StringComparison.Ordinal);
        var dummyBasis = new Uri("https://dripsharp.invalid/" + basisText.TrimStart('/'));
        var resolved = new Uri(dummyBasis, value);
        var text = resolved.PathAndQuery + resolved.Fragment;
        if (!rooted) text = text.TrimStart('/');
        return new Uri(text, UriKind.Relative);
    }
    internal static Uri ResolveLocalDependencyUri(Uri basis, Uri value)
    {
        var resolved = ResolveUri(basis, value);
        if (resolved.IsAbsoluteUri && resolved.IsFile)
            _ = SingleSlashFileUris.GetValue(resolved, _ => new object());
        return resolved;
    }
    internal static Uri NormalizeUri(Uri uri) => uri;
    internal static Uri RelativizeUri(Uri basis, Uri value)
    {
        if (!basis.IsAbsoluteUri || !value.IsAbsoluteUri ||
            !string.Equals(UriScheme(basis), UriScheme(value), StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(UriRawAuthority(basis), UriRawAuthority(value), StringComparison.Ordinal))
            return value;
        var basePath = UriRawPath(basis) ?? string.Empty;
        var valuePath = UriRawPath(value) ?? string.Empty;
        if (!valuePath.StartsWith(basePath, StringComparison.Ordinal)) return value;
        var relative = valuePath[basePath.Length..];
        var query = UriRawQuery(value);
        var fragment = UriRawFragment(value);
        if (query is not null) relative += "?" + query;
        if (fragment is not null) relative += "#" + fragment;
        return CreateUri(relative);
    }
    internal static bool UriIsOpaque(Uri uri)
    {
        if (!uri.IsAbsoluteUri) return false;
        var original = OriginalUriTexts.TryGetValue(uri, out var preserved)
            ? preserved.Value
            : uri.OriginalString;
        var colon = original.IndexOf(':');
        return colon >= 0 && (colon + 1 == original.Length || original[colon + 1] != '/');
    }
    internal static string Encode(string value, Encoding encoding) => Uri.EscapeDataString(value);

    internal static void RegisterSocketStream(System.Net.Sockets.Socket socket, Stream stream) =>
        SocketStreams.Add(socket, stream);
    internal static void RegisterPendingSocketFactory(
        System.Net.Sockets.Socket socket,
        JavaSocketFactory factory) => PendingSocketFactories.Add(socket, factory);
    internal static System.Net.IPAddress InetSocketAddressAddress(
        System.Net.IPEndPoint endpoint) => endpoint.Address;
    internal static Stream SocketStream(System.Net.Sockets.Socket socket)
    {
        if (SocketStreams.TryGetValue(socket, out var stream)) return stream;
        if (!PendingSocketFactories.TryGetValue(socket, out var factory))
            return new System.Net.Sockets.NetworkStream(socket, ownsSocket: false);
        stream = factory.OpenStream(socket);
        PendingSocketFactories.Remove(socket);
        SocketStreams.Add(socket, stream);
        return stream;
    }
    internal static bool SocketIsClosed(System.Net.Sockets.Socket socket) =>
        socket.SafeHandle.IsClosed;
    internal static bool SocketIsConnected(System.Net.Sockets.Socket socket) => socket.Connected;
    internal static void SocketSetSoTimeout(System.Net.Sockets.Socket socket, int timeout) =>
        socket.ReceiveTimeout = timeout;
    internal static int CompareUri(Uri left, Uri right) => string.CompareOrdinal(left.OriginalString, right.OriginalString);
    internal static Stream OpenStream(Uri uri) =>
        uri.IsFile
            ? OpenFileRead(PathOfUri(uri))
            : new System.Net.Http.HttpClient().GetStreamAsync(uri).GetAwaiter().GetResult();
    internal static Stream OpenInputStream(string path, params object?[] _) => OpenFileRead(path);

    internal static long FileLength(string path) => File.Exists(path)
        ? new FileInfo(path).Length
        : 0L;

    internal static Stream OpenUrlStream(Uri uri)
    {
        ArgumentNullException.ThrowIfNull(uri);
        return uri.IsFile
            ? OpenFileRead(uri.LocalPath)
            : UrlClient.GetStreamAsync(uri).GetAwaiter().GetResult();
    }

    internal static string UrlDecode(string value, string encoding)
    {
        ArgumentNullException.ThrowIfNull(value);
        ArgumentNullException.ThrowIfNull(encoding);
        if (!string.Equals(encoding, "UTF-8", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(encoding, "UTF8", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException($"Unsupported URL decoder encoding: {encoding}", nameof(encoding));
        return System.Net.WebUtility.UrlDecode(value);
    }
    internal static string UrlEncode(string value, string encoding) =>
        UrlEncode(value, CharsetForName(encoding));
    internal static string UrlEncode(string value, Encoding encoding)
    {
        ArgumentNullException.ThrowIfNull(value);
        ArgumentNullException.ThrowIfNull(encoding);
        var result = new StringBuilder();
        for (var index = 0; index < value.Length; index++)
        {
            var character = value[index];
            if ((character is >= 'a' and <= 'z') ||
                (character is >= 'A' and <= 'Z') ||
                (character is >= '0' and <= '9') ||
                character is '-' or '_' or '.' or '*')
            {
                result.Append(character);
                continue;
            }
            if (character == ' ')
            {
                result.Append('+');
                continue;
            }
            var length = char.IsHighSurrogate(character) &&
                         index + 1 < value.Length &&
                         char.IsLowSurrogate(value[index + 1]) ? 2 : 1;
            foreach (var item in encoding.GetBytes(value.Substring(index, length)))
                result.Append('%').Append(item.ToString("X2", CultureInfo.InvariantCulture));
            index += length - 1;
        }
        return result.ToString();
    }
    internal static System.Net.IPAddress GetByName(string name) => System.Net.Dns.GetHostAddresses(name)[0];
    internal static sbyte[] GetAddressBytes(System.Net.IPAddress address) =>
        ToSignedBytes(address.GetAddressBytes());
    internal static bool GetBoolean(string name) => bool.TryParse(GetProperty(name), out var value) && value;
    internal static global::System.Net.IPEndPoint NewIpEndPoint(string host, int port) =>
        new(global::System.Net.Dns.GetHostAddresses(host)[0], port);
}
