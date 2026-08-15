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

// JDK compatibility area: Java.Security

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaNoSuchAlgorithmException : CryptographicException
{
    public JavaNoSuchAlgorithmException(string message) : base(message) { }
    public JavaNoSuchAlgorithmException(string message, Exception cause)
        : base(message, cause) { }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaNoSuchPaddingException : CryptographicException
{
    public JavaNoSuchPaddingException(string message) : base(message) { }
    public JavaNoSuchPaddingException(string message, Exception cause)
        : base(message, cause) { }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaUnrecoverableKeyException : CryptographicException
{
    public JavaUnrecoverableKeyException(string message) : base(message) { }
    public JavaUnrecoverableKeyException(string message, Exception cause)
        : base(message, cause) { }
}

internal sealed class JavaNonClosingStream : Stream
{
    private readonly Stream inner;

    internal JavaNonClosingStream(Stream inner) => this.inner = inner;

    public override bool CanRead => inner.CanRead;
    public override bool CanSeek => inner.CanSeek;
    public override bool CanWrite => inner.CanWrite;
    public override long Length => inner.Length;
    public override long Position { get => inner.Position; set => inner.Position = value; }
    public override void Flush() => inner.Flush();
    public override int Read(byte[] buffer, int offset, int count) => inner.Read(buffer, offset, count);
    public override long Seek(long offset, SeekOrigin origin) => inner.Seek(offset, origin);
    public override void SetLength(long value) => inner.SetLength(value);
    public override void Write(byte[] buffer, int offset, int count) => inner.Write(buffer, offset, count);
    protected override void Dispose(bool disposing) { }
}

internal sealed class JavaMessageDigest
{
    private readonly IncrementalHash digest;

    private JavaMessageDigest(HashAlgorithmName algorithm) =>
        digest = IncrementalHash.CreateHash(algorithm);

    internal static JavaMessageDigest GetInstance(string algorithm)
    {
        var normalized = algorithm.Replace("-", string.Empty, StringComparison.Ordinal)
            .ToUpperInvariant();
        var name = normalized switch
        {
            "MD5" => HashAlgorithmName.MD5,
            "SHA1" => HashAlgorithmName.SHA1,
            "SHA256" => HashAlgorithmName.SHA256,
            "SHA384" => HashAlgorithmName.SHA384,
            "SHA512" => HashAlgorithmName.SHA512,
            _ => throw new JavaNoSuchAlgorithmException(
                $"Unsupported message digest `{algorithm}`")
        };
        return new JavaMessageDigest(name);
    }

    internal void Update(sbyte value) => digest.AppendData(new[] { unchecked((byte)value) });

    internal void Update(sbyte[] value) =>
        digest.AppendData(value.Select(item => unchecked((byte)item)).ToArray());

    internal void Update(sbyte[] value, int offset, int length) =>
        digest.AppendData(value.Skip(offset).Take(length)
            .Select(item => unchecked((byte)item)).ToArray());

    internal sbyte[] Digest() =>
        digest.GetHashAndReset().Select(item => unchecked((sbyte)item)).ToArray();

    internal sbyte[] Digest(sbyte[] value)
    {
        Update(value);
        return Digest();
    }

    internal static bool IsEqual(sbyte[] left, sbyte[] right) =>
        JavaCompat.FixedTimeEquals(left, right);
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
interface JavaSecretKey
{
    sbyte[] GetEncoded();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaSecurityProvider
{
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaAlgorithmParameters
{
    private readonly sbyte[] encoded;

    internal JavaAlgorithmParameters(sbyte[] encoded, sbyte[] iv)
    {
        this.encoded = (sbyte[])encoded.Clone();
        Iv = (sbyte[])iv.Clone();
    }

    internal sbyte[] Iv { get; }

    public sbyte[] GetEncoded(string format)
    {
        if (!string.Equals(format, "ASN.1", StringComparison.OrdinalIgnoreCase))
            throw new CryptographicException(
                $"Unsupported algorithm-parameter encoding `{format}`.");
        return (sbyte[])encoded.Clone();
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaAlgorithmParameterGenerator
{
    private readonly string algorithm;

    private JavaAlgorithmParameterGenerator(string algorithm)
    {
        this.algorithm = algorithm;
    }

    public static JavaAlgorithmParameterGenerator GetInstance(
        string algorithm,
        object _)
    {
        if (!string.Equals(
                algorithm, "1.2.840.113549.3.2", StringComparison.Ordinal) &&
            !string.Equals(algorithm, "RC2", StringComparison.OrdinalIgnoreCase))
            throw new JavaNoSuchAlgorithmException(
                $"Unsupported algorithm-parameter generator `{algorithm}`.");
        return new JavaAlgorithmParameterGenerator(algorithm);
    }

    public JavaAlgorithmParameters GenerateParameters()
    {
        _ = algorithm;
        var iv = new byte[8];
        JavaCompat.FillRandom(iv);
        var encoded = new byte[15];
        encoded[0] = 0x30;
        encoded[1] = 0x0d;
        encoded[2] = 0x02;
        encoded[3] = 0x01;
        encoded[4] = 58;
        encoded[5] = 0x04;
        encoded[6] = 0x08;
        Array.Copy(iv, 0, encoded, 7, iv.Length);
        return new JavaAlgorithmParameters(
            JavaCompat.ToSignedBytes(encoded),
            JavaCompat.ToSignedBytes(iv));
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaKeyGenerator
{
    private readonly string algorithm;
    private int keySize;

    private JavaKeyGenerator(string algorithm)
    {
        this.algorithm = algorithm;
        keySize = string.Equals(algorithm, "AES", StringComparison.OrdinalIgnoreCase)
            ? 256
            : 128;
    }

    public static JavaKeyGenerator GetInstance(string algorithm) =>
        new(ValidateAlgorithm(algorithm));

    public static JavaKeyGenerator GetInstance(
        string algorithm,
        object _) =>
        new(ValidateAlgorithm(algorithm));

    public void Init(int bits)
    {
        if (bits <= 0 || bits % 8 != 0)
            throw new CryptographicException($"Invalid key size `{bits}`.");
        keySize = bits;
    }

    public void Init(int bits, JavaRandom _)
    {
        Init(bits);
    }

    public JavaSecretKey GenerateKey()
    {
        var key = new byte[keySize / 8];
        JavaCompat.FillRandom(key);
        return new JavaSecretKeySpec(
            JavaCompat.ToSignedBytes(key),
            string.Equals(algorithm, "1.2.840.113549.3.2", StringComparison.Ordinal)
                ? "RC2"
                : algorithm);
    }

    private static string ValidateAlgorithm(string algorithm)
    {
        ArgumentException.ThrowIfNullOrEmpty(algorithm);
        if (!string.Equals(algorithm, "AES", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(algorithm, "RC2", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(algorithm, "1.2.840.113549.3.2", StringComparison.Ordinal))
            throw new JavaNoSuchAlgorithmException(
                $"Unsupported key-generator algorithm `{algorithm}`.");
        return algorithm;
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaSecretKeySpec : JavaSecretKey
{
    private readonly sbyte[] encoded;

    public JavaSecretKeySpec(sbyte[] encoded, string algorithm)
    {
        ArgumentNullException.ThrowIfNull(encoded);
        ArgumentException.ThrowIfNullOrEmpty(algorithm);
        this.encoded = (sbyte[])encoded.Clone();
        Algorithm = algorithm;
    }

    internal string Algorithm { get; }

    public sbyte[] GetEncoded() => (sbyte[])encoded.Clone();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaIvParameterSpec
{
    public JavaIvParameterSpec(sbyte[] iv)
    {
        ArgumentNullException.ThrowIfNull(iv);
        Iv = (sbyte[])iv.Clone();
    }

    internal sbyte[] Iv { get; }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaCipher : IDisposable
{
    public const int ENCRYPT_MODE = 1;
    public const int DECRYPT_MODE = 2;

    private readonly string transformation;
    private readonly List<byte> pending = new();
    private SymmetricAlgorithm? algorithm;
    private ICryptoTransform? transform;
    private RSA? rsa;
    private int asymmetricMode;
    private bool holdBackFinalBlock;

    private JavaCipher(string transformation)
    {
        ArgumentException.ThrowIfNullOrEmpty(transformation);
        this.transformation = transformation;
    }

    public static JavaCipher GetInstance(string transformation) =>
        new(ValidateTransformation(transformation));

    public static JavaCipher GetInstance(
        string transformation,
        object _) =>
        new(ValidateTransformation(transformation));

    public static int GetMaxAllowedKeyLength(string algorithm)
    {
        ArgumentException.ThrowIfNullOrEmpty(algorithm);
        return int.MaxValue;
    }

    private static string ValidateTransformation(string transformation)
    {
        ArgumentException.ThrowIfNullOrEmpty(transformation);
        if (string.Equals(
                transformation,
                "1.2.840.113549.3.2",
                StringComparison.Ordinal) ||
            string.Equals(
                transformation,
                "1.2.840.113549.1.1.1",
                StringComparison.Ordinal) ||
            string.Equals(transformation, "RC2", StringComparison.OrdinalIgnoreCase) ||
            string.Equals(transformation, "RSA", StringComparison.OrdinalIgnoreCase))
            return transformation;

        var parts = transformation.Split('/');
        if (parts.Length == 1)
            throw new JavaNoSuchAlgorithmException(
                $"Unsupported cipher algorithm `{transformation}`.");
        if (parts.Length != 3 ||
            (!string.Equals(parts[0], "AES", StringComparison.OrdinalIgnoreCase) &&
             !string.Equals(parts[0], "RSA", StringComparison.OrdinalIgnoreCase)))
            throw new JavaNoSuchAlgorithmException(
                $"Unsupported cipher transformation `{transformation}`.");
        if (!string.Equals(parts[1], "CBC", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(parts[1], "ECB", StringComparison.OrdinalIgnoreCase))
            throw new JavaNoSuchAlgorithmException(
                $"Unsupported cipher mode `{parts[1]}`.");
        if (!string.Equals(parts[2], "NoPadding", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(parts[2], "PKCS5Padding", StringComparison.OrdinalIgnoreCase))
            throw new JavaNoSuchPaddingException(
                $"Unsupported cipher padding `{parts[2]}`.");
        return transformation;
    }

    public void Init(int mode, object key) => Init(mode, key, (JavaIvParameterSpec?)null);

    public void Init(int mode, object key, JavaAlgorithmParameters parameters)
    {
        ArgumentNullException.ThrowIfNull(parameters);
        Init(mode, key, new JavaIvParameterSpec(parameters.Iv));
    }

    public void Init(int mode, object key, JavaIvParameterSpec? parameters)
    {
        DisposeTransform();
        if (key is RSA rsaKey)
        {
            if (!string.Equals(
                    transformation, "1.2.840.113549.1.1.1", StringComparison.Ordinal) &&
                !string.Equals(transformation, "RSA", StringComparison.OrdinalIgnoreCase) &&
                !transformation.StartsWith("RSA/", StringComparison.OrdinalIgnoreCase))
                throw new CryptographicException(
                    $"Unsupported asymmetric cipher transformation `{transformation}`.");
            rsa = rsaKey;
            asymmetricMode = mode;
            return;
        }
        if (key is not JavaSecretKeySpec keySpec)
            throw new CryptographicException("Cipher key must be a SecretKeySpec.");
        if (!string.Equals(keySpec.Algorithm, "AES", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(keySpec.Algorithm, "RC2", StringComparison.OrdinalIgnoreCase))
            throw new CryptographicException($"Unsupported cipher key algorithm `{keySpec.Algorithm}`.");

        var parts = transformation.Split('/');
        var rc2 = string.Equals(
            transformation, "1.2.840.113549.3.2", StringComparison.Ordinal) ||
            string.Equals(transformation, "RC2", StringComparison.OrdinalIgnoreCase);
        if (!rc2 &&
            (parts.Length != 3 ||
             !string.Equals(parts[0], "AES", StringComparison.OrdinalIgnoreCase)))
            throw new CryptographicException(
                $"Unsupported cipher transformation `{transformation}`.");

        SymmetricAlgorithm symmetric = rc2 ? RC2.Create() : Aes.Create();
        symmetric.Key = JavaCompat.ToUnsignedBytes(keySpec.GetEncoded());
        symmetric.Mode = (rc2 ? "CBC" : parts[1].ToUpperInvariant()) switch
        {
            "CBC" => CipherMode.CBC,
            "ECB" => CipherMode.ECB,
            _ => throw new CryptographicException(
                $"Unsupported cipher mode `{parts[1]}`.")
        };
        symmetric.Padding = (rc2 ? "PKCS5PADDING" : parts[2].ToUpperInvariant()) switch
        {
            "NOPADDING" => PaddingMode.None,
            "PKCS5PADDING" => PaddingMode.PKCS7,
            _ => throw new CryptographicException(
                $"Unsupported cipher padding `{parts[2]}`.")
        };
        if (symmetric.Mode != CipherMode.ECB)
        {
            if (parameters is null)
                throw new CryptographicException("CBC mode requires an initialization vector.");
            symmetric.IV = JavaCompat.ToUnsignedBytes(parameters.Iv);
        }

        algorithm = symmetric;
        holdBackFinalBlock =
            mode == DECRYPT_MODE && symmetric.Padding != PaddingMode.None;
        transform = mode switch
        {
            ENCRYPT_MODE => symmetric.CreateEncryptor(),
            DECRYPT_MODE => symmetric.CreateDecryptor(),
            _ => throw new CryptographicException($"Unsupported cipher mode constant `{mode}`.")
        };
    }

    public sbyte[]? Update(sbyte[] input, int offset, int length)
    {
        ArgumentNullException.ThrowIfNull(input);
        var current = RequireTransform();
        if (offset < 0 || length < 0 || offset > input.Length - length)
            throw new ArgumentOutOfRangeException(nameof(offset));
        var source = JavaCompat.ToUnsignedBytes(input);
        pending.AddRange(source.AsSpan(offset, length).ToArray());
        var completeBlocks = pending.Count / current.InputBlockSize;
        if (holdBackFinalBlock && completeBlocks > 0)
            completeBlocks--;
        var processLength = completeBlocks * current.InputBlockSize;
        if (processLength == 0)
            return null;
        source = pending.GetRange(0, processLength).ToArray();
        pending.RemoveRange(0, processLength);
        var destination = new byte[processLength + current.OutputBlockSize];
        var written = current.TransformBlock(
            source, 0, source.Length, destination, 0);
        return written == 0
            ? null
            : JavaCompat.ToSignedBytes(destination.AsSpan(0, written).ToArray());
    }

    public sbyte[] DoFinal() => DoFinal(Array.Empty<sbyte>());

    public sbyte[] DoFinal(sbyte[] input)
    {
        ArgumentNullException.ThrowIfNull(input);
        if (rsa is not null)
        {
            var asymmetricResult = asymmetricMode switch
            {
                ENCRYPT_MODE => rsa.Encrypt(
                    JavaCompat.ToUnsignedBytes(input), RSAEncryptionPadding.Pkcs1),
                DECRYPT_MODE => rsa.Decrypt(
                    JavaCompat.ToUnsignedBytes(input), RSAEncryptionPadding.Pkcs1),
                _ => throw new CryptographicException(
                    $"Unsupported cipher mode constant `{asymmetricMode}`.")
            };
            rsa = null;
            asymmetricMode = 0;
            return JavaCompat.ToSignedBytes(asymmetricResult);
        }
        pending.AddRange(JavaCompat.ToUnsignedBytes(input));
        var finalInput = pending.ToArray();
        pending.Clear();
        var finalResult = RequireTransform().TransformFinalBlock(
            finalInput, 0, finalInput.Length);
        DisposeTransform();
        return JavaCompat.ToSignedBytes(finalResult);
    }

    internal CryptoStream CreateInputStream(Stream input)
    {
        ArgumentNullException.ThrowIfNull(input);
        var current = transform ??
            throw new InvalidOperationException("Cipher has not been initialized.");
        transform = null;
        return new CryptoStream(new JavaNonClosingStream(input), current, CryptoStreamMode.Read);
    }

    private ICryptoTransform RequireTransform() =>
        transform ?? throw new InvalidOperationException("Cipher has not been initialized.");

    private void DisposeTransform()
    {
        transform?.Dispose();
        transform = null;
        algorithm?.Dispose();
        algorithm = null;
        pending.Clear();
        holdBackFinalBlock = false;
        rsa = null;
        asymmetricMode = 0;
    }

    public void Dispose() => DisposeTransform();
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaCipherInputStream : Stream
{
    private readonly JavaCipher cipher;
    private readonly CryptoStream stream;

    public JavaCipherInputStream(Stream input, JavaCipher cipher)
    {
        ArgumentNullException.ThrowIfNull(cipher);
        this.cipher = cipher;
        stream = cipher.CreateInputStream(input);
    }

    public override bool CanRead => stream.CanRead;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position
    {
        get => throw new NotSupportedException();
        set => throw new NotSupportedException();
    }

    public override void Flush() => stream.Flush();
    public override int Read(byte[] buffer, int offset, int count)
    {
        try
        {
            return stream.Read(buffer, offset, count);
        }
        catch (CryptographicException exception)
        {
            throw new IOException(null, exception);
        }
    }

    public override long Seek(long offset, SeekOrigin origin) =>
        throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) =>
        throw new NotSupportedException();

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            try
            {
                stream.Dispose();
            }
            catch (CryptographicException exception)
            {
                throw new IOException(null, exception);
            }
            finally
            {
                cipher.Dispose();
            }
        }
        base.Dispose(disposing);
    }
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaKeyStore
{
    private readonly System.Security.Cryptography.X509Certificates.X509Certificate2Collection certificates = new();

    private JavaKeyStore() { }

    public static string GetDefaultType() => "PKCS12";

    public static JavaKeyStore GetInstance(string type)
    {
        ArgumentNullException.ThrowIfNull(type);
        if (!string.Equals(type, "PKCS12", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(type, "PKCS#12", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(type, "PFX", StringComparison.OrdinalIgnoreCase))
            throw new System.Security.Cryptography.CryptographicException(
                $"Unsupported KeyStore type: {type}");
        return new JavaKeyStore();
    }

    public void Load(Stream input, char[]? password)
    {
        ArgumentNullException.ThrowIfNull(input);
        using var contents = new MemoryStream();
        input.CopyTo(contents);
        certificates.Clear();
        var flags = System.Security.Cryptography.X509Certificates.X509KeyStorageFlags.Exportable;
#if !NETSTANDARD2_0
        flags |= System.Security.Cryptography.X509Certificates.X509KeyStorageFlags.EphemeralKeySet;
#endif
#if NET9_0_OR_GREATER
        certificates.AddRange(
            System.Security.Cryptography.X509Certificates.X509CertificateLoader
                .LoadPkcs12Collection(
                    contents.ToArray(),
                    password is null ? null : new string(password),
                    flags,
                    System.Security.Cryptography.X509Certificates.Pkcs12LoaderLimits.Defaults));
#else
        certificates.Import(
            contents.ToArray(),
            password is null ? null : new string(password),
            flags);
#endif
    }

    public void Load(object? parameter)
    {
        if (parameter is not null)
            throw new NotSupportedException(
                "KeyStore LoadStoreParameter values are not supported.");
        certificates.Clear();
    }

    public void SetCertificateEntry(
        string alias,
        System.Security.Cryptography.X509Certificates.X509Certificate2 certificate)
    {
        ArgumentNullException.ThrowIfNull(alias);
        ArgumentNullException.ThrowIfNull(certificate);
        certificates.Add(certificate);
    }

    public int Size() => certificates.Count;

    public JavaIterator<string> Aliases() =>
        JavaCompat.Iterator(
            Enumerable.Range(0, certificates.Count)
                .Select(index => AliasFor(index, certificates[index])));

    public bool ContainsAlias(string? alias) =>
        TryFind(alias, out _);

    public System.Security.Cryptography.X509Certificates.X509Certificate2? GetCertificate(
        string? alias) =>
        TryFind(alias, out var certificate) ? certificate : null;

    public object? GetKey(string? alias, char[]? _)
    {
        if (!TryFind(alias, out var certificate) || certificate is null)
            return null;
        try
        {
            var key = (object?)certificate.GetRSAPrivateKey() ??
                certificate.GetECDsaPrivateKey();
#pragma warning disable CS0618, SYSLIB0028
            return key ?? certificate.PrivateKey;
#pragma warning restore CS0618, SYSLIB0028
        }
        catch (CryptographicException error)
        {
            throw new JavaUnrecoverableKeyException(error.Message, error);
        }
    }

    internal System.Security.Cryptography.X509Certificates.X509Certificate2Collection Certificates =>
        certificates;

    private bool TryFind(
        string? alias,
        out System.Security.Cryptography.X509Certificates.X509Certificate2? certificate)
    {
        for (var index = 0; index < certificates.Count; index++)
        {
            if (string.Equals(
                    AliasFor(index, certificates[index]),
                    alias,
                    StringComparison.Ordinal))
            {
                certificate = certificates[index];
                return true;
            }
        }
        certificate = null;
        return false;
    }

    private static string AliasFor(
        int index,
        System.Security.Cryptography.X509Certificates.X509Certificate2 certificate) =>
        string.IsNullOrWhiteSpace(certificate.FriendlyName)
            ? index.ToString(CultureInfo.InvariantCulture)
            : certificate.FriendlyName;
}

#if DRIPSHARP_INTERNAL_JAVA_COMPAT
internal
#else
public
#endif
sealed class JavaCertificateFactory
{
    private JavaCertificateFactory() { }

    public static JavaCertificateFactory GetInstance(string type)
    {
        ArgumentNullException.ThrowIfNull(type);
        if (!string.Equals(type, "X.509", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(type, "X509", StringComparison.OrdinalIgnoreCase))
            throw new System.Security.Cryptography.CryptographicException(
                $"Unsupported CertificateFactory type: {type}");
        return new JavaCertificateFactory();
    }

    public System.Security.Cryptography.X509Certificates.X509Certificate2 GenerateCertificate(Stream stream)
    {
        var certificates = GenerateCertificates(stream);
        if (certificates.Count == 0)
            throw new System.Security.Cryptography.CryptographicException(
                "The certificate input did not contain an X.509 certificate.");
        return certificates.First();
    }

    public ICollection<System.Security.Cryptography.X509Certificates.X509Certificate2>
        GenerateCertificates(Stream stream)
    {
        ArgumentNullException.ThrowIfNull(stream);
        using var bytes = new MemoryStream();
        stream.CopyTo(bytes);
        var encoded = bytes.ToArray();
        var result = new System.Security.Cryptography.X509Certificates.X509Certificate2Collection();
        if (encoded.Length == 0)
            return Array.Empty<System.Security.Cryptography.X509Certificates.X509Certificate2>();
        var text = Encoding.UTF8.GetString(encoded);
        if (text.Contains("-----BEGIN CERTIFICATE-----", StringComparison.Ordinal))
        {
            foreach (Match match in Regex.Matches(text,
                         "-----BEGIN CERTIFICATE-----\\s*(?<data>[A-Za-z0-9+/=\\s]+?)\\s*-----END CERTIFICATE-----",
                         RegexOptions.CultureInvariant))
            {
                var der = Convert.FromBase64String(Regex.Replace(match.Groups["data"].Value, @"\s", string.Empty));
                result.Add(LoadCertificate(der));
            }
        }
        else
        {
            result.Add(LoadCertificate(encoded));
        }
        return result.Cast<System.Security.Cryptography.X509Certificates.X509Certificate2>().ToList();
    }

    private static System.Security.Cryptography.X509Certificates.X509Certificate2 LoadCertificate(byte[] encoded)
    {
#if NET9_0_OR_GREATER
        return System.Security.Cryptography.X509Certificates.X509CertificateLoader.LoadCertificate(encoded);
#else
#pragma warning disable SYSLIB0057 // X509CertificateLoader is unavailable before .NET 9
        return new System.Security.Cryptography.X509Certificates.X509Certificate2(encoded);
#pragma warning restore SYSLIB0057
#endif
    }
}

internal sealed class JavaKeyManager
{
    internal JavaKeyManager(
        System.Security.Cryptography.X509Certificates.X509Certificate2 serverCertificate) =>
        ServerCertificate = serverCertificate;

    internal System.Security.Cryptography.X509Certificates.X509Certificate2 ServerCertificate { get; }
}

internal sealed class JavaKeyManagerFactory
{
    private JavaKeyManager? manager;

    private JavaKeyManagerFactory() { }

    internal static string GetDefaultAlgorithm() => "SunX509";

    internal static JavaKeyManagerFactory GetInstance(string algorithm)
    {
        ArgumentNullException.ThrowIfNull(algorithm);
        if (!string.Equals(algorithm, "SunX509", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(algorithm, "NewSunX509", StringComparison.OrdinalIgnoreCase))
            throw new System.Security.Cryptography.CryptographicException(
                $"Unsupported KeyManagerFactory algorithm: {algorithm}");
        return new JavaKeyManagerFactory();
    }

    internal void Init(JavaKeyStore keyStore, char[]? password)
    {
        ArgumentNullException.ThrowIfNull(keyStore);
        var certificate = keyStore.Certificates.Cast<
            System.Security.Cryptography.X509Certificates.X509Certificate2>()
            .FirstOrDefault(candidate => candidate.HasPrivateKey);
        manager = certificate is null
            ? throw new System.Security.Cryptography.CryptographicException(
                "The KeyStore contains no private key certificate.")
            : new JavaKeyManager(certificate);
    }

    internal object[] GetKeyManagers() => manager is null
        ? throw new InvalidOperationException("KeyManagerFactory is not initialized.")
        : new object[] { manager };
}

internal sealed class JavaTrustManager
{
    internal JavaTrustManager(
        System.Security.Cryptography.X509Certificates.X509Certificate2Collection certificates) =>
        Certificates = certificates;

    internal System.Security.Cryptography.X509Certificates.X509Certificate2Collection Certificates { get; }
}

internal interface JavaX509TrustManager
{
    System.Security.Cryptography.X509Certificates.X509Certificate2[] GetAcceptedIssuers();
    void CheckServerTrusted(
        System.Security.Cryptography.X509Certificates.X509Certificate2[] chain,
        string authType);
    void CheckClientTrusted(
        System.Security.Cryptography.X509Certificates.X509Certificate2[] chain,
        string authType);
}

internal sealed class JavaTrustManagerFactory
{
    private JavaTrustManager? manager;

    private JavaTrustManagerFactory() { }

    internal static string GetDefaultAlgorithm() => "PKIX";

    internal static JavaTrustManagerFactory GetInstance(string algorithm)
    {
        ArgumentNullException.ThrowIfNull(algorithm);
        if (!string.Equals(algorithm, "PKIX", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(algorithm, "SunX509", StringComparison.OrdinalIgnoreCase))
            throw new System.Security.Cryptography.CryptographicException(
                $"Unsupported TrustManagerFactory algorithm: {algorithm}");
        return new JavaTrustManagerFactory();
    }

    internal void Init(JavaKeyStore keyStore)
    {
        ArgumentNullException.ThrowIfNull(keyStore);
        var roots = new System.Security.Cryptography.X509Certificates.X509Certificate2Collection();
        roots.AddRange(keyStore.Certificates);
        manager = new JavaTrustManager(roots);
    }

    internal object[] GetTrustManagers() => manager is null
        ? throw new InvalidOperationException("TrustManagerFactory is not initialized.")
        : new object[] { manager };
}

internal static partial class JavaCompat
{
    internal static void FillRandom(byte[] values)
    {
        using var generator = RandomNumberGenerator.Create();
        generator.GetBytes(values);
    }

    internal static bool FixedTimeEquals(sbyte[] left, sbyte[] right)
    {
        if (left.Length != right.Length) return false;
        var difference = 0;
        for (var index = 0; index < left.Length; index++)
            difference |= left[index] ^ right[index];
        return difference == 0;
    }
}
