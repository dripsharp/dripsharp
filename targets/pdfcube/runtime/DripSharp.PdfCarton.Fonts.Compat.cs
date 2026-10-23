// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Focused destination compatibility for FontBox geometry over SkiaSharp.
#nullable enable

using System;
using System.Collections.Generic;
using System.Formats.Asn1;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Numerics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
#if DRIPSHARP_PDFBOX_CRYPTO
using System.Security.Cryptography.Pkcs;
#endif
using System.Security.Cryptography.X509Certificates;
using System.Threading;
using System.Xml;
using SkiaSharp;

namespace DripSharp.Runtime;

#pragma warning disable CS0618 // SKPath mutation is the m150 bridge for Java GeneralPath.

internal static class PdfCartonImageIO
{
    internal static JavaImageInputStream CreateImageInputStream(object source) =>
        source switch
        {
            Stream stream => new JavaImageInputStream(stream),
            JavaFile file => OpenImageInputStream(file.OpenFileInfo()),
            FileInfo file => OpenImageInputStream(file),
            _ => throw new ArgumentException(
                "Image input must be a readable stream or file.",
                nameof(source))
        };

    private static JavaImageInputStream OpenImageInputStream(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        using var stream = file.OpenRead();
        return new JavaImageInputStream(stream);
    }

    internal static JavaImageOutputStream CreateImageOutputStream(object destination) =>
        destination switch
        {
            Stream stream => new JavaImageOutputStream(stream),
            _ => throw new ArgumentException(
                "Image output must be a writable stream.",
                nameof(destination))
        };

    internal static JavaIterator<JavaImageReader> GetImageReadersByFormatName(
        string formatName)
    {
        ArgumentNullException.ThrowIfNull(formatName);
        var supported =
            formatName.Equals("JPEG", StringComparison.OrdinalIgnoreCase) ||
            formatName.Equals("JPG", StringComparison.OrdinalIgnoreCase) ||
            PdfCartonImageCodecs.Supports(formatName);
        return JavaCompat.Iterator(
            supported
                ? new[] { new JavaImageReader(formatName) }
                : Array.Empty<JavaImageReader>());
    }

    internal static JavaIterator<JavaImageWriter> GetImageWritersBySuffix(
        string suffix)
    {
        ArgumentNullException.ThrowIfNull(suffix);
        return JavaCompat.Iterator(
            suffix.Equals("JPEG", StringComparison.OrdinalIgnoreCase) ||
            suffix.Equals("JPG", StringComparison.OrdinalIgnoreCase)
                ? new[] { new JavaImageWriter("JPEG") }
                : Array.Empty<JavaImageWriter>());
    }
}

internal sealed class JavaX509CertificateHolder
{
    private readonly X509Certificate2 certificate;

    public JavaX509CertificateHolder(sbyte[] encoded)
    {
        ArgumentNullException.ThrowIfNull(encoded);
        certificate = new X509Certificate2(
            MemoryMarshal.AsBytes(encoded.AsSpan()).ToArray());
    }

    public string Issuer => certificate.Issuer;
    public string GetIssuer() => certificate.Issuer;
    internal X509Certificate2 Certificate => certificate;
}

internal static class PdfCartonCrypto
{
    public static string GetDefaultKeyStoreType() => "PKCS12";

    public static X509Certificate2Collection CreateKeyStore(string type)
    {
        JavaCompat.ThrowIfNullOrWhiteSpace(type, nameof(type));
        if (!string.Equals(type, "PKCS12", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(type, "PKCS#12", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(type, "PFX", StringComparison.OrdinalIgnoreCase))
            throw new CryptographicException($"Unsupported KeyStore type: {type}");
        return new X509Certificate2Collection();
    }

    public static void LoadKeyStore(
        X509Certificate2Collection certificates,
        Stream input,
        char[]? password)
    {
        ArgumentNullException.ThrowIfNull(certificates);
        ArgumentNullException.ThrowIfNull(input);
        using var contents = new MemoryStream();
        input.CopyTo(contents);
        certificates.Clear();
        var encoded = contents.ToArray();
        var textPassword = password is null ? null : new string(password);
        try
        {
            certificates.Import(
                encoded,
                textPassword,
                X509KeyStorageFlags.Exportable);
        }
        finally
        {
            Array.Clear(encoded, 0, encoded.Length);
        }
    }

    public static int KeyStoreSize(X509Certificate2Collection certificates) =>
        certificates?.Count ??
        throw new ArgumentNullException(nameof(certificates));

    public static JavaIterator<string> KeyStoreAliases(
        X509Certificate2Collection certificates)
    {
        ArgumentNullException.ThrowIfNull(certificates);
        return JavaCompat.Iterator(
            Enumerable.Range(0, certificates.Count)
                .Select(index => KeyStoreAlias(index, certificates[index])));
    }

    public static bool KeyStoreContainsAlias(
        X509Certificate2Collection certificates,
        string? alias) =>
        FindKeyStoreCertificate(certificates, alias) is not null;

    public static X509Certificate2? KeyStoreGetCertificate(
        X509Certificate2Collection certificates,
        string? alias) =>
        FindKeyStoreCertificate(certificates, alias);

    public static object? KeyStoreGetKey(
        X509Certificate2Collection certificates,
        string? alias,
        char[]? _)
    {
        var certificate = FindKeyStoreCertificate(certificates, alias);
        if (certificate is null)
            return null;
        try
        {
            return (object?)certificate.GetRSAPrivateKey() ??
                (object?)certificate.GetECDsaPrivateKey() ??
                GetDsaKey(certificate, "GetDSAPrivateKey");
        }
        catch (CryptographicException error)
        {
            throw new JavaUnrecoverableKeyException(error.Message, error);
        }
    }

    public static sbyte[] GetEncoded(X509Certificate2 certificate)
    {
        ArgumentNullException.ThrowIfNull(certificate);
        return JavaCompat.ToSignedBytes(certificate.RawData);
    }

    public static sbyte[] GetTbsCertificate(X509Certificate2 certificate)
    {
        ArgumentNullException.ThrowIfNull(certificate);
        var certificateReader = new AsnReader(
            certificate.RawData, AsnEncodingRules.DER);
        var sequence = certificateReader.ReadSequence();
        var tbsCertificate = sequence.ReadEncodedValue().ToArray();
        return JavaCompat.ToSignedBytes(tbsCertificate);
    }

    public static AsymmetricAlgorithm GetPublicKey(X509Certificate2 certificate)
    {
        ArgumentNullException.ThrowIfNull(certificate);
        return (AsymmetricAlgorithm?)certificate.GetRSAPublicKey() ??
            (AsymmetricAlgorithm?)certificate.GetECDsaPublicKey() ??
            GetDsaKey(certificate, "GetDSAPublicKey") ??
            throw new CryptographicException(
                $"Unsupported certificate public-key algorithm `{certificate.GetKeyAlgorithm()}`.");
    }

    public static BigInteger GetSerialNumber(X509Certificate2 certificate)
    {
        ArgumentNullException.ThrowIfNull(certificate);
        var littleEndian = certificate.GetSerialNumber();
        Array.Resize(ref littleEndian, littleEndian.Length + 1);
        return new BigInteger(littleEndian);
    }

    private static AsymmetricAlgorithm? GetDsaKey(
        X509Certificate2 certificate,
        string methodName)
    {
        var extensions = Type.GetType(
            "System.Security.Cryptography.X509Certificates.DSACertificateExtensions, " +
            "System.Security.Cryptography.X509Certificates",
            throwOnError: false);
        var method = extensions?.GetMethod(
            methodName,
            global::System.Reflection.BindingFlags.Public |
            global::System.Reflection.BindingFlags.Static,
            binder: null,
            types: new[] { typeof(X509Certificate2) },
            modifiers: null);
        return method?.Invoke(null, new object[] { certificate }) as AsymmetricAlgorithm;
    }

    private static X509Certificate2? FindKeyStoreCertificate(
        X509Certificate2Collection certificates,
        string? alias)
    {
        ArgumentNullException.ThrowIfNull(certificates);
        for (var index = 0; index < certificates.Count; index++)
        {
            if (string.Equals(
                    KeyStoreAlias(index, certificates[index]),
                    alias,
                    StringComparison.Ordinal))
                return certificates[index];
        }
        return null;
    }

    private static string KeyStoreAlias(
        int index,
        X509Certificate2 certificate) =>
        string.IsNullOrWhiteSpace(certificate.FriendlyName)
            ? index.ToString(System.Globalization.CultureInfo.InvariantCulture)
            : certificate.FriendlyName;
}

internal static class JavaAsn1Encoding
{
    public const string DER = "DER";
}

internal class JavaAsn1Primitive
{
    private readonly byte[]? encoded;

    protected JavaAsn1Primitive()
    {
    }

    internal JavaAsn1Primitive(byte[] encoded)
    {
        ArgumentNullException.ThrowIfNull(encoded);
        this.encoded = (byte[])encoded.Clone();
    }

    internal virtual byte[] Encode() =>
        encoded is null
            ? throw new CryptographicException(
                $"{GetType().Name} does not have a standalone DER encoding.")
            : (byte[])encoded.Clone();

    public void EncodeTo(Stream destination, string encoding)
    {
        ArgumentNullException.ThrowIfNull(destination);
        if (!string.Equals(encoding, JavaAsn1Encoding.DER, StringComparison.Ordinal))
            throw new CryptographicException(
                $"Unsupported ASN.1 encoding `{encoding}`.");
        var bytes = Encode();
        destination.Write(bytes, 0, bytes.Length);
    }
}

internal sealed class JavaAsn1InputStream : IDisposable
{
    private readonly byte[] encoded;

    public JavaAsn1InputStream(sbyte[] encoded)
    {
        ArgumentNullException.ThrowIfNull(encoded);
        this.encoded = JavaCompat.ToUnsignedBytes(encoded);
    }

    public JavaAsn1Primitive ReadObject() => new(encoded);

    public void Dispose()
    {
    }
}

internal sealed class JavaAsn1ObjectIdentifier : JavaAsn1Primitive
{
    public JavaAsn1ObjectIdentifier(string id)
    {
        JavaCompat.ThrowIfNullOrWhiteSpace(id, nameof(id));
        Id = id;
    }

    public string Id { get; }
    public string GetId() => Id;
}

internal sealed class JavaDerOctetString : JavaAsn1Primitive
{
    public JavaDerOctetString(sbyte[] contents)
    {
        ArgumentNullException.ThrowIfNull(contents);
        Contents = JavaCompat.ToUnsignedBytes(contents);
    }

    internal byte[] Contents { get; }

    internal override byte[] Encode()
    {
        var writer = new AsnWriter(AsnEncodingRules.DER);
        writer.WriteOctetString(Contents);
        return writer.Encode();
    }
}

internal class JavaAsn1Set : JavaAsn1Primitive
{
    internal JavaAsn1Set(IReadOnlyList<JavaAsn1Primitive> values)
    {
        Values = values;
    }

    internal IReadOnlyList<JavaAsn1Primitive> Values { get; }
}

internal sealed class JavaDerSet : JavaAsn1Set
{
    public JavaDerSet(JavaAsn1Primitive value)
        : base(new[] { value })
    {
    }
}

internal sealed class JavaAlgorithmIdentifier : JavaAsn1Primitive
{
    public JavaAlgorithmIdentifier(
        JavaAsn1ObjectIdentifier algorithm,
        JavaAsn1Primitive parameters)
    {
        Algorithm = algorithm ?? throw new ArgumentNullException(nameof(algorithm));
        Parameters = parameters;
    }

    internal JavaAlgorithmIdentifier(
        JavaAsn1ObjectIdentifier algorithm,
        JavaAsn1Primitive? parameters,
        bool _)
    {
        Algorithm = algorithm;
        Parameters = parameters;
    }

    internal JavaAsn1ObjectIdentifier Algorithm { get; }
    internal JavaAsn1Primitive? Parameters { get; }
    public JavaAsn1ObjectIdentifier GetAlgorithm() => Algorithm;

    internal void Write(AsnWriter writer)
    {
        writer.PushSequence();
        writer.WriteObjectIdentifier(Algorithm.Id);
        if (Parameters is null)
        {
            writer.WriteNull();
        }
        else
        {
            writer.WriteEncodedValue(Parameters.Encode());
        }
        writer.PopSequence();
    }
}

internal sealed class JavaSubjectPublicKeyInfo
{
    internal JavaSubjectPublicKeyInfo(JavaAlgorithmIdentifier algorithm)
    {
        Algorithm = algorithm;
    }

    internal JavaAlgorithmIdentifier Algorithm { get; }
    public JavaAlgorithmIdentifier GetAlgorithm() => Algorithm;
}

internal sealed class JavaTbsCertificate
{
    private JavaTbsCertificate(
        string issuer,
        BigInteger serialNumber,
        JavaAlgorithmIdentifier subjectPublicKeyAlgorithm)
    {
        Issuer = issuer;
        SerialNumber = serialNumber;
        SubjectPublicKeyInfo =
            new JavaSubjectPublicKeyInfo(subjectPublicKeyAlgorithm);
    }

    private string Issuer { get; }
    private BigInteger SerialNumber { get; }
    private JavaSubjectPublicKeyInfo SubjectPublicKeyInfo { get; }

    public static JavaTbsCertificate GetInstance(object value)
    {
        if (value is not JavaAsn1Primitive primitive)
            throw new CryptographicException(
                "TBSCertificate input must be an ASN.1 primitive.");
        var reader = new AsnReader(primitive.Encode(), AsnEncodingRules.DER);
        var tbs = reader.ReadSequence();
        if (tbs.HasData &&
            tbs.PeekTag().HasSameClassAndValue(
                new Asn1Tag(TagClass.ContextSpecific, 0, isConstructed: true)))
        {
            tbs.ReadEncodedValue();
        }
        var serialNumber = tbs.ReadInteger();
        tbs.ReadEncodedValue();
        var issuerBytes = tbs.ReadEncodedValue().ToArray();
        tbs.ReadEncodedValue();
        tbs.ReadEncodedValue();
        var subjectPublicKeyInfo = tbs.ReadSequence();
        var algorithm = ReadAlgorithmIdentifier(subjectPublicKeyInfo);
        var issuer = new X500DistinguishedName(issuerBytes).Name;
        return new JavaTbsCertificate(issuer, serialNumber, algorithm);
    }

    public JavaSubjectPublicKeyInfo GetSubjectPublicKeyInfo() =>
        SubjectPublicKeyInfo;

    public string GetIssuer() => Issuer;
    public BigInteger GetSerialNumber() => SerialNumber;

    private static JavaAlgorithmIdentifier ReadAlgorithmIdentifier(
        AsnReader subjectPublicKeyInfo)
    {
        var identifier = subjectPublicKeyInfo.ReadSequence();
        var oid = new JavaAsn1ObjectIdentifier(identifier.ReadObjectIdentifier());
        JavaAsn1Primitive? parameters = identifier.HasData
            ? new JavaAsn1Primitive(identifier.ReadEncodedValue().ToArray())
            : null;
        return new JavaAlgorithmIdentifier(oid, parameters, true);
    }
}

internal static class JavaPkcsObjectIdentifiers
{
    public static readonly JavaAsn1ObjectIdentifier RC2_CBC =
        new("1.2.840.113549.3.2");
    public static readonly JavaAsn1ObjectIdentifier Data =
        new("1.2.840.113549.1.7.1");
    public static readonly JavaAsn1ObjectIdentifier EnvelopedData =
        new("1.2.840.113549.1.7.3");
}

internal sealed class JavaIssuerAndSerialNumber : JavaAsn1Primitive
{
    public JavaIssuerAndSerialNumber(string issuer, BigInteger serialNumber)
    {
        JavaCompat.ThrowIfNullOrWhiteSpace(issuer, nameof(issuer));
        Issuer = issuer;
        SerialNumber = serialNumber;
    }

    internal string Issuer { get; }
    internal BigInteger SerialNumber { get; }

    internal void Write(AsnWriter writer)
    {
        writer.PushSequence();
        writer.WriteEncodedValue(new X500DistinguishedName(Issuer).RawData);
        writer.WriteInteger(SerialNumber);
        writer.PopSequence();
    }
}

internal sealed class JavaRecipientIdentifier : JavaAsn1Primitive
{
    public JavaRecipientIdentifier(JavaIssuerAndSerialNumber issuerAndSerial)
    {
        IssuerAndSerial = issuerAndSerial ??
            throw new ArgumentNullException(nameof(issuerAndSerial));
    }

    internal JavaIssuerAndSerialNumber IssuerAndSerial { get; }
}

internal sealed class JavaKeyTransRecipientInfo : JavaAsn1Primitive
{
    public JavaKeyTransRecipientInfo(
        JavaRecipientIdentifier recipientIdentifier,
        JavaAlgorithmIdentifier keyEncryptionAlgorithm,
        JavaDerOctetString encryptedKey)
    {
        RecipientIdentifier = recipientIdentifier ??
            throw new ArgumentNullException(nameof(recipientIdentifier));
        KeyEncryptionAlgorithm = keyEncryptionAlgorithm ??
            throw new ArgumentNullException(nameof(keyEncryptionAlgorithm));
        EncryptedKey = encryptedKey ??
            throw new ArgumentNullException(nameof(encryptedKey));
    }

    internal JavaRecipientIdentifier RecipientIdentifier { get; }
    internal JavaAlgorithmIdentifier KeyEncryptionAlgorithm { get; }
    internal JavaDerOctetString EncryptedKey { get; }

    internal void Write(AsnWriter writer)
    {
        writer.PushSequence();
        writer.WriteInteger(0);
        RecipientIdentifier.IssuerAndSerial.Write(writer);
        KeyEncryptionAlgorithm.Write(writer);
        writer.WriteOctetString(EncryptedKey.Contents);
        writer.PopSequence();
    }
}

internal sealed class JavaRecipientInfo : JavaAsn1Primitive
{
    public JavaRecipientInfo(JavaKeyTransRecipientInfo info)
    {
        Info = info ?? throw new ArgumentNullException(nameof(info));
    }

    internal JavaKeyTransRecipientInfo Info { get; }
}

internal sealed class JavaEncryptedContentInfo : JavaAsn1Primitive
{
    public JavaEncryptedContentInfo(
        JavaAsn1ObjectIdentifier contentType,
        JavaAlgorithmIdentifier contentEncryptionAlgorithm,
        JavaDerOctetString encryptedContent)
    {
        ContentType = contentType ??
            throw new ArgumentNullException(nameof(contentType));
        ContentEncryptionAlgorithm = contentEncryptionAlgorithm ??
            throw new ArgumentNullException(nameof(contentEncryptionAlgorithm));
        EncryptedContent = encryptedContent ??
            throw new ArgumentNullException(nameof(encryptedContent));
    }

    internal JavaAsn1ObjectIdentifier ContentType { get; }
    internal JavaAlgorithmIdentifier ContentEncryptionAlgorithm { get; }
    internal JavaDerOctetString EncryptedContent { get; }

    internal void Write(AsnWriter writer)
    {
        writer.PushSequence();
        writer.WriteObjectIdentifier(ContentType.Id);
        ContentEncryptionAlgorithm.Write(writer);
        writer.WriteOctetString(
            EncryptedContent.Contents,
            new Asn1Tag(TagClass.ContextSpecific, 0));
        writer.PopSequence();
    }
}

internal sealed class JavaEnvelopedData : JavaAsn1Primitive
{
    public JavaEnvelopedData(
        object? _,
        JavaAsn1Set recipientInfos,
        JavaEncryptedContentInfo encryptedContentInfo,
        JavaAsn1Set? __)
    {
        RecipientInfos = recipientInfos ??
            throw new ArgumentNullException(nameof(recipientInfos));
        EncryptedContentInfo = encryptedContentInfo ??
            throw new ArgumentNullException(nameof(encryptedContentInfo));
    }

    internal JavaAsn1Set RecipientInfos { get; }
    internal JavaEncryptedContentInfo EncryptedContentInfo { get; }

    internal void Write(AsnWriter writer)
    {
        writer.PushSequence();
        writer.WriteInteger(0);
        writer.PushSetOf();
        foreach (var recipient in RecipientInfos.Values)
        {
            if (recipient is not JavaRecipientInfo recipientInfo)
                throw new CryptographicException(
                    "CMS recipient set contains a non-recipient value.");
            recipientInfo.Info.Write(writer);
        }
        writer.PopSetOf();
        EncryptedContentInfo.Write(writer);
        writer.PopSequence();
    }
}

internal sealed class JavaCmsContentInfo : JavaAsn1Primitive
{
    public JavaCmsContentInfo(
        JavaAsn1ObjectIdentifier contentType,
        JavaAsn1Primitive content)
    {
        ContentType = contentType ??
            throw new ArgumentNullException(nameof(contentType));
        Content = content ??
            throw new ArgumentNullException(nameof(content));
    }

    private JavaAsn1ObjectIdentifier ContentType { get; }
    private JavaAsn1Primitive Content { get; }

    public JavaAsn1Primitive ToAsn1Primitive()
    {
        if (Content is not JavaEnvelopedData enveloped)
            throw new CryptographicException(
                "CMS ContentInfo currently requires EnvelopedData content.");
        var writer = new AsnWriter(AsnEncodingRules.DER);
        writer.PushSequence();
        writer.WriteObjectIdentifier(ContentType.Id);
        var contentTag =
            new Asn1Tag(TagClass.ContextSpecific, 0, isConstructed: true);
        writer.PushSequence(contentTag);
        enveloped.Write(writer);
        writer.PopSequence(contentTag);
        writer.PopSequence();
        return new JavaAsn1Primitive(writer.Encode());
    }
}

internal class JavaRecipientId
{
    internal JavaRecipientId(string issuer, BigInteger serialNumber)
    {
        Issuer = issuer;
        SerialNumber = serialNumber;
    }

    protected string Issuer { get; }
    protected BigInteger SerialNumber { get; }

    public virtual bool Match(object value) =>
        value is JavaX509CertificateHolder holder &&
        PdfCartonCrypto.GetSerialNumber(holder.Certificate) == SerialNumber &&
        string.Equals(
            new X500DistinguishedName(holder.Certificate.IssuerName.RawData).Name,
            Issuer,
            StringComparison.OrdinalIgnoreCase);
}

internal sealed class JavaKeyTransRecipientId : JavaRecipientId
{
    internal JavaKeyTransRecipientId(string issuer, BigInteger serialNumber)
        : base(issuer, serialNumber)
    {
    }

    public BigInteger GetSerialNumber() => SerialNumber;
    public string GetIssuer() => Issuer;
}

internal sealed class JavaJceKeyTransEnvelopedRecipient
{
    public JavaJceKeyTransEnvelopedRecipient(AsymmetricAlgorithm privateKey)
    {
        PrivateKey = privateKey ??
            throw new ArgumentNullException(nameof(privateKey));
    }

    internal AsymmetricAlgorithm PrivateKey { get; }
}

internal sealed class JavaRecipientInformation
{
    private readonly JavaKeyTransRecipientId recipientId;
    private readonly byte[] encryptedKey;
    private readonly byte[] encryptedContent;
    private readonly byte[] iv;
    private readonly string contentAlgorithm;

    internal JavaRecipientInformation(
        JavaKeyTransRecipientId recipientId,
        byte[] encryptedKey,
        byte[] encryptedContent,
        byte[] iv,
        string contentAlgorithm)
    {
        this.recipientId = recipientId;
        this.encryptedKey = encryptedKey;
        this.encryptedContent = encryptedContent;
        this.iv = iv;
        this.contentAlgorithm = contentAlgorithm;
    }

    public JavaRecipientId GetRid() => recipientId;

    public sbyte[] GetContent(JavaJceKeyTransEnvelopedRecipient recipient)
    {
        if (recipient.PrivateKey is not RSA rsa)
            throw new CryptographicException(
                "CMS key-transport decryption requires an RSA private key.");
        var contentKey = rsa.Decrypt(encryptedKey, RSAEncryptionPadding.Pkcs1);
        using SymmetricAlgorithm algorithm = contentAlgorithm switch
        {
            "1.2.840.113549.3.2" => RC2.Create(),
            "2.16.840.1.101.3.4.1.2" or
            "2.16.840.1.101.3.4.1.22" or
            "2.16.840.1.101.3.4.1.42" => Aes.Create(),
            _ => throw new CryptographicException(
                $"CMS content cipher `{contentAlgorithm}` is unsupported.")
        };
        algorithm.Key = contentKey;
        algorithm.IV = iv;
        algorithm.Mode = CipherMode.CBC;
        algorithm.Padding = PaddingMode.PKCS7;
        using var decryptor = algorithm.CreateDecryptor();
        return JavaCompat.ToSignedBytes(
            decryptor.TransformFinalBlock(
                encryptedContent, 0, encryptedContent.Length));
    }
}

internal sealed class JavaRecipientInformationStore
{
    private readonly IReadOnlyCollection<JavaRecipientInformation> recipients;

    internal JavaRecipientInformationStore(
        IReadOnlyCollection<JavaRecipientInformation> recipients)
    {
        this.recipients = recipients;
    }

    public ICollection<JavaRecipientInformation> GetRecipients() =>
        recipients.ToList();
}

internal sealed class JavaCmsEnvelopedData
{
    private readonly JavaRecipientInformationStore recipientInfos;

    public JavaCmsEnvelopedData(sbyte[] encoded)
    {
        ArgumentNullException.ThrowIfNull(encoded);
        var unsigned = JavaCompat.ToUnsignedBytes(encoded);
#if DRIPSHARP_PDFBOX_CRYPTO
        var cms = new EnvelopedCms();
        cms.Decode(unsigned);
        if (cms.RecipientInfos.Count == 0)
            throw new CryptographicException(
                "CMS EnvelopedData contains no recipients.");
#endif
        recipientInfos = new JavaRecipientInformationStore(
            Parse(unsigned));
    }

    public JavaRecipientInformationStore GetRecipientInfos() => recipientInfos;

    private static IReadOnlyCollection<JavaRecipientInformation> Parse(
        byte[] encoded)
    {
        var root = new AsnReader(encoded, AsnEncodingRules.BER);
        var contentInfo = root.ReadSequence();
        var contentType = contentInfo.ReadObjectIdentifier();
        if (!string.Equals(
                contentType,
                JavaPkcsObjectIdentifiers.EnvelopedData.Id,
                StringComparison.Ordinal))
            throw new CryptographicException(
                $"CMS content type `{contentType}` is not EnvelopedData.");
        var explicitContent = contentInfo.ReadSequence(
            new Asn1Tag(TagClass.ContextSpecific, 0, isConstructed: true));
        var enveloped = explicitContent.ReadSequence();
        enveloped.ReadInteger();
        if (enveloped.HasData &&
            enveloped.PeekTag().HasSameClassAndValue(
                new Asn1Tag(TagClass.ContextSpecific, 0, isConstructed: true)))
        {
            enveloped.ReadEncodedValue();
        }

        var recipientSet = enveloped.ReadSetOf(skipSortOrderValidation: true);
        var recipients = new List<ParsedRecipient>();
        while (recipientSet.HasData)
        {
            recipients.Add(ParseRecipient(recipientSet.ReadEncodedValue()));
        }

        var encryptedInfo = enveloped.ReadSequence();
        var encryptedContentType = encryptedInfo.ReadObjectIdentifier();
        if (!string.Equals(
                encryptedContentType,
                JavaPkcsObjectIdentifiers.Data.Id,
                StringComparison.Ordinal))
            throw new CryptographicException(
                $"CMS encrypted content type `{encryptedContentType}` is unsupported.");
        var contentAlgorithm = encryptedInfo.ReadSequence();
        var algorithm = contentAlgorithm.ReadObjectIdentifier();
        var supported =
            string.Equals(
                algorithm,
                JavaPkcsObjectIdentifiers.RC2_CBC.Id,
                StringComparison.Ordinal) ||
            string.Equals(
                algorithm,
                "2.16.840.1.101.3.4.1.2",
                StringComparison.Ordinal) ||
            string.Equals(
                algorithm,
                "2.16.840.1.101.3.4.1.22",
                StringComparison.Ordinal) ||
            string.Equals(
                algorithm,
                "2.16.840.1.101.3.4.1.42",
                StringComparison.Ordinal);
        if (!supported)
            throw new CryptographicException(
                $"CMS content cipher `{algorithm}` is unsupported.");
        byte[] iv;
        if (string.Equals(
                algorithm,
                JavaPkcsObjectIdentifiers.RC2_CBC.Id,
                StringComparison.Ordinal))
        {
            var parameters = contentAlgorithm.ReadSequence();
            if (parameters.HasData &&
                parameters.PeekTag().HasSameClassAndValue(Asn1Tag.Integer))
            {
                parameters.ReadInteger();
            }
            iv = parameters.ReadOctetString();
        }
        else
        {
            iv = contentAlgorithm.ReadOctetString();
        }
        var encryptedContent = encryptedInfo.ReadOctetString(
            new Asn1Tag(TagClass.ContextSpecific, 0));

        return recipients
            .Select(recipient => new JavaRecipientInformation(
                new JavaKeyTransRecipientId(
                    recipient.Issuer,
                recipient.SerialNumber),
                recipient.EncryptedKey,
                encryptedContent,
                iv,
                algorithm))
            .ToArray();
    }

    private static ParsedRecipient ParseRecipient(ReadOnlyMemory<byte> encoded)
    {
        var reader = new AsnReader(encoded, AsnEncodingRules.BER);
        var keyTransport = reader.ReadSequence();
        keyTransport.ReadInteger();
        var issuerAndSerial = keyTransport.ReadSequence();
        var issuerBytes = issuerAndSerial.ReadEncodedValue().ToArray();
        var issuer = new X500DistinguishedName(issuerBytes).Name;
        var serial = issuerAndSerial.ReadInteger();
        var keyAlgorithm = keyTransport.ReadSequence();
        var keyAlgorithmOid = keyAlgorithm.ReadObjectIdentifier();
        if (!string.Equals(
                keyAlgorithmOid,
                "1.2.840.113549.1.1.1",
                StringComparison.Ordinal))
            throw new CryptographicException(
                $"CMS key-transport algorithm `{keyAlgorithmOid}` is unsupported.");
        if (keyAlgorithm.HasData)
        {
            keyAlgorithm.ReadEncodedValue();
        }
        var encryptedKey = keyTransport.ReadOctetString();
        return new ParsedRecipient(
            issuer,
            serial,
            encryptedKey);
    }

    private sealed record ParsedRecipient(
        string Issuer,
        BigInteger SerialNumber,
        byte[] EncryptedKey);
}

public sealed class JavaImageInputStream : IDisposable
{
    private readonly byte[] data;
    private long bitPosition;

    public JavaImageInputStream(Stream source)
    {
        ArgumentNullException.ThrowIfNull(source);
        using var buffer = new MemoryStream();
        source.CopyTo(buffer);
        data = buffer.ToArray();
    }

    public int BitOffset => (int)(bitPosition & 7);
    public long StreamPosition => (bitPosition + 7) / 8;
    internal byte[] Bytes => data;

    public int Read()
    {
        AlignToByte();
        if (bitPosition / 8 >= data.LongLength) return -1;
        var result = data[bitPosition / 8];
        bitPosition += 8;
        return result;
    }

    public int Read(sbyte[] destination)
    {
        ArgumentNullException.ThrowIfNull(destination);
        AlignToByte();
        var available = (int)Math.Min(destination.LongLength, data.LongLength - bitPosition / 8);
        if (available <= 0) return -1;
        for (var index = 0; index < available; index++)
        {
            destination[index] = unchecked((sbyte)data[bitPosition / 8 + index]);
        }
        bitPosition += available * 8L;
        return available;
    }

    public long ReadBits(int numberOfBits)
    {
        if (numberOfBits is < 0 or > 64) throw new ArgumentException("Bit count must be between 0 and 64.");
        if (bitPosition + numberOfBits > data.LongLength * 8) throw new EndOfStreamException();
        ulong value = 0;
        for (var index = 0; index < numberOfBits; index++)
        {
            var sourceByte = data[bitPosition >> 3];
            var bit = (sourceByte >> (7 - (int)(bitPosition & 7))) & 1;
            value = value << 1 | (uint)bit;
            bitPosition++;
        }
        return unchecked((long)value);
    }

    public int ReadUnsignedShort()
    {
        var high = Read();
        var low = Read();
        if (low < 0) throw new EndOfStreamException();
        return high << 8 | low;
    }

    public void Seek(long position)
    {
        if (position < 0 || position > data.LongLength) throw new IOException("Invalid image stream position.");
        bitPosition = position * 8;
    }

    public void Dispose()
    {
    }

    private void AlignToByte()
    {
        if ((bitPosition & 7) != 0) bitPosition = (bitPosition + 7) & ~7L;
    }
}

public sealed class JavaImageReadParam
{
    internal int SubsamplingX { get; private set; } = 1;
    internal int SubsamplingY { get; private set; } = 1;
    internal int SubsamplingOffsetX { get; private set; }
    internal int SubsamplingOffsetY { get; private set; }
    internal SKRectI? SourceRegion { get; private set; }

    public void SetSourceSubsampling(
        int subsamplingX,
        int subsamplingY,
        int subsamplingOffsetX,
        int subsamplingOffsetY)
    {
        if (subsamplingX <= 0 || subsamplingY <= 0)
            throw new ArgumentException("Subsampling periods must be positive.");
        if (subsamplingOffsetX < 0 ||
            subsamplingOffsetX >= subsamplingX ||
            subsamplingOffsetY < 0 ||
            subsamplingOffsetY >= subsamplingY)
        {
            throw new ArgumentException(
                "Subsampling offsets must lie within their periods.");
        }
        SubsamplingX = subsamplingX;
        SubsamplingY = subsamplingY;
        SubsamplingOffsetX = subsamplingOffsetX;
        SubsamplingOffsetY = subsamplingOffsetY;
    }

    public void SetSourceRegion(SKRectI sourceRegion)
    {
        if (sourceRegion == default)
        {
            SourceRegion = null;
            return;
        }
        if (sourceRegion.Width <= 0 || sourceRegion.Height <= 0)
            throw new ArgumentException("The image source region must be non-empty.");
        SourceRegion = sourceRegion;
    }
}

public sealed class JavaImageMetadata
{
    private readonly int componentCount;
    private readonly int? adobeTransform;
    private XmlElement? standardRoot;
    private XmlElement? jpegRoot;

    internal JavaImageMetadata(byte[] encoded)
    {
        ArgumentNullException.ThrowIfNull(encoded);
        (componentCount, adobeTransform) = ReadJpegMetadata(encoded);
    }

    internal JavaImageMetadata(int componentCount)
    {
        if (componentCount <= 0)
            throw new ArgumentOutOfRangeException(nameof(componentCount));
        this.componentCount = componentCount;
    }

    public XmlNode GetAsTree(string formatName)
    {
        ArgumentNullException.ThrowIfNull(formatName);
        if (formatName.Equals("javax_imageio_1.0", StringComparison.Ordinal))
        {
            if (standardRoot is not null) return standardRoot;
            var document = new XmlDocument();
            var root = document.CreateElement("javax_imageio_1.0");
            document.AppendChild(root);
            var chroma = document.CreateElement("Chroma");
            root.AppendChild(chroma);
            var channels = document.CreateElement("NumChannels");
            channels.SetAttribute(
                "value",
                componentCount.ToString(System.Globalization.CultureInfo.InvariantCulture));
            chroma.AppendChild(channels);
            standardRoot = root;
            return standardRoot;
        }
        if (formatName.Equals(
                "javax_imageio_jpeg_image_1.0",
                StringComparison.Ordinal))
        {
            if (jpegRoot is not null) return jpegRoot;
            var document = new XmlDocument();
            var root = document.CreateElement("javax_imageio_jpeg_image_1.0");
            document.AppendChild(root);
            var jpegVariety = document.CreateElement("JPEGvariety");
            root.AppendChild(jpegVariety);
            var app0Jfif = document.CreateElement("app0JFIF");
            app0Jfif.SetAttribute("resUnits", "0");
            app0Jfif.SetAttribute("Xdensity", "1");
            app0Jfif.SetAttribute("Ydensity", "1");
            jpegVariety.AppendChild(app0Jfif);
            var markerSequence = document.CreateElement("markerSequence");
            root.AppendChild(markerSequence);
            var startOfFrame = document.CreateElement("sof");
            startOfFrame.SetAttribute(
                "numFrameComponents",
                componentCount.ToString(System.Globalization.CultureInfo.InvariantCulture));
            markerSequence.AppendChild(startOfFrame);
            if (adobeTransform.HasValue)
            {
                var app14Adobe = document.CreateElement("app14Adobe");
                app14Adobe.SetAttribute(
                    "transform",
                    adobeTransform.Value.ToString(
                        System.Globalization.CultureInfo.InvariantCulture));
                markerSequence.AppendChild(app14Adobe);
            }
            jpegRoot = root;
            return jpegRoot;
        }
        throw new ArgumentException(
            $"Unsupported image metadata format `{formatName}`.",
            nameof(formatName));
    }

    internal int? JpegDensity
    {
        get
        {
            var root = GetAsTree("javax_imageio_jpeg_image_1.0");
            if (root is not XmlElement element) return null;
            var node = element.GetElementsByTagName("app0JFIF")
                .OfType<XmlElement>()
                .FirstOrDefault();
            if (node is null ||
                !string.Equals(node.GetAttribute("resUnits"), "1", StringComparison.Ordinal) ||
                !int.TryParse(
                    node.GetAttribute("Xdensity"),
                    System.Globalization.NumberStyles.Integer,
                    System.Globalization.CultureInfo.InvariantCulture,
                    out var density))
            {
                return null;
            }
            return JavaCompat.Clamp(density, 1, ushort.MaxValue);
        }
    }

    internal int ComponentCount => componentCount;
    internal int? AdobeTransform => adobeTransform;

    private static (int Components, int? AdobeTransform) ReadJpegMetadata(
        byte[] encoded)
    {
        var components = 0;
        int? transform = null;
        for (var offset = 0; offset + 3 < encoded.Length;)
        {
            if (encoded[offset] != 0xff)
            {
                offset++;
                continue;
            }
            while (offset < encoded.Length && encoded[offset] == 0xff) offset++;
            if (offset >= encoded.Length) break;
            var marker = encoded[offset++];
            if (marker is 0xd8 or 0xd9 || marker is >= 0xd0 and <= 0xd7)
                continue;
            if (offset + 1 >= encoded.Length) break;
            var length = encoded[offset] << 8 | encoded[offset + 1];
            if (length < 2 || offset + length > encoded.Length) break;
            var payload = offset + 2;
            if (IsStartOfFrame(marker) && length >= 8)
                components = encoded[payload + 5];
            if (marker == 0xee &&
                length >= 14 &&
                encoded.AsSpan(payload, 5).SequenceEqual("Adobe"u8))
            {
                transform = encoded[payload + 11];
            }
            offset += length;
        }
        return (components, transform);
    }

    private static bool IsStartOfFrame(byte marker) =>
        marker is >= 0xc0 and <= 0xcf &&
        marker is not 0xc4 and not 0xc8 and not 0xcc;
}

public sealed class JavaImageReader : IDisposable
{
    private readonly string formatName;
    private JavaImageInputStream? input;
    // The size of image sizeIndex of the current input, when known (not -1).
    private int sizeIndex = -1;
    private SKSizeI imageSize;

    internal JavaImageReader(string formatName) => this.formatName = formatName;

    public bool CanReadRaster => true;

    public void SetInput(object? source) => SetInput(source, false, false);

    public void SetInput(
        object? source,
        bool seekForwardOnly,
        bool ignoreMetadata)
    {
        sizeIndex = -1;
        if (source is null)
        {
            input = null;
            return;
        }
        input = source as JavaImageInputStream ??
            throw new ArgumentException(
                "ImageReader input must be an ImageInputStream.",
                nameof(source));
        _ = seekForwardOnly;
        _ = ignoreMetadata;
    }

    public JavaImageReadParam GetDefaultReadParam() => new();

    public int GetWidth(int imageIndex) => GetSize(imageIndex).Width;

    public int GetHeight(int imageIndex) => GetSize(imageIndex).Height;

    // ImageIO reads an image's size from its header. Skia's codec reads the
    // header alone for the formats it decodes (JPEG among them), and the JPX
    // adapter reads the codestream's main header; JBIG2 and TIFF have no
    // header-only read, so their size is that of the last decode of the
    // current input, or a decode. A read remembers its image's full size.
    private SKSizeI GetSize(int imageIndex)
    {
        RequireImageIndex(imageIndex);
        if (imageIndex == sizeIndex) return imageSize;
        var size = imageIndex == 0 && !IsTiff ? ReadHeaderSize() : null;
        if (size is null)
        {
            using var bitmap = Decode(imageIndex);
            return imageSize;
        }
        sizeIndex = imageIndex;
        imageSize = size.Value;
        return imageSize;
    }

    private SKSizeI? ReadHeaderSize()
    {
        var encoded = RequireInput().Bytes;
        if (PdfCartonImageCodecs.Supports(formatName))
            return PdfCartonImageCodecs.ReadSize(formatName, encoded);
        using var stream = new MemoryStream(encoded, writable: false);
        using var codec = SKCodec.Create(stream) ??
            throw new IOException($"Unable to decode {formatName} image data.");
        return new SKSizeI(codec.Info.Width, codec.Info.Height);
    }

    public SKBitmap Read(int imageIndex, JavaImageReadParam? parameters)
    {
        var bitmap = ReadDeferred(imageIndex, parameters);
        try
        {
            PdfCartonFontCompat.EnsureNativePixels(bitmap);
        }
        catch
        {
            bitmap.Dispose();
            throw;
        }
        return bitmap;
    }

    // Read without rendering a JBIG2 or JPX image's native pixels, for callers
    // that read its raster or draw it through compat graphics (JBIG2Filter
    // and JPXFilter, via java_project.clj).
    internal SKBitmap ReadDeferred(int imageIndex, JavaImageReadParam? parameters)
    {
        RequireImageIndex(imageIndex);
        var bitmap = Decode(imageIndex);
        return ApplyReadParameters(bitmap, parameters);
    }

    // ImageIO's readRaster returns a four-component JPEG's stored samples,
    // CMYK or YCCK, without colour conversion; DCTFilter interprets them.
    public JavaRaster ReadRaster(
        int imageIndex,
        JavaImageReadParam? parameters)
    {
        RequireImageIndex(imageIndex);
        if (imageIndex == 0 &&
            formatName.Equals("JPEG", StringComparison.OrdinalIgnoreCase) &&
            new JavaImageMetadata(RequireInput().Bytes).ComponentCount == 4 &&
            PdfCartonImageCodecs.ReadJpegSamples(RequireInput().Bytes) is { } samples)
        {
            decodesOnCurrentThread++;
            sizeIndex = 0;
            imageSize = new SKSizeI(samples.Width, samples.Height);
            return ApplyReadParameters(samples, parameters);
        }
        // Skia decodes other four-component JPEGs to RGB only, so their K is
        // lost; ImageIO's libjpeg rejects most of them (arithmetic coding,
        // 12-bit samples).
        using var image = ReadDeferred(imageIndex, parameters);
        var raster = PdfCartonFontCompat.GetImageData(image);
        if (!formatName.Equals("JPEG", StringComparison.OrdinalIgnoreCase))
            return raster;
        var metadata = new JavaImageMetadata(RequireInput().Bytes);
        return metadata.ComponentCount == 4 && raster.NumberOfBands == 3
            ? CreateFourChannelJpegRaster(raster, metadata.AdobeTransform)
            : raster;
    }

    private static JavaRaster CreateFourChannelJpegRaster(
        JavaRaster rgb,
        int? adobeTransform)
    {
        var result = new JavaRaster(
            PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE,
            rgb.Width,
            rgb.Height,
            4);
        var source = new int[3];
        var destination = new int[4];
        for (var y = 0; y < rgb.Height; y++)
        {
            for (var x = 0; x < rgb.Width; x++)
            {
                rgb.GetPixel(x, y, source);
                var red = source[0];
                var green = source[1];
                var blue = source[2];
                if (adobeTransform is 1 or 2)
                {
                    destination[0] = JavaCompat.Clamp(
                        (int)Math.Round(0.299 * red + 0.587 * green + 0.114 * blue),
                        0,
                        255);
                    destination[1] = JavaCompat.Clamp(
                        (int)Math.Round(128 - 0.168736 * red - 0.331264 * green + 0.5 * blue),
                        0,
                        255);
                    destination[2] = JavaCompat.Clamp(
                        (int)Math.Round(128 + 0.5 * red - 0.418688 * green - 0.081312 * blue),
                        0,
                        255);
                    destination[3] = 0;
                }
                else
                {
                    destination[0] = 255 - red;
                    destination[1] = 255 - green;
                    destination[2] = 255 - blue;
                    destination[3] = 0;
                }
                result.SetPixel(x, y, destination);
            }
        }
        return result;
    }

    public JavaImageMetadata GetImageMetadata(int imageIndex)
    {
        RequireImageIndex(imageIndex);
        if (formatName.Equals("JPEG", StringComparison.OrdinalIgnoreCase))
            return new JavaImageMetadata(RequireInput().Bytes);
        using var bitmap = Decode(imageIndex);
        return new JavaImageMetadata(
            PdfCartonFontCompat.GetColorModel(bitmap).NumberOfComponents);
    }

    public void Dispose()
    {
        input = null;
        sizeIndex = -1;
    }

    // Pixel decodes run on this thread, for tests.
    [ThreadStatic] private static int decodesOnCurrentThread;

    internal static int DecodesOnCurrentThread => decodesOnCurrentThread;

    private bool IsTiff =>
        formatName.Equals("TIFF", StringComparison.OrdinalIgnoreCase) ||
        formatName.Equals("TIF", StringComparison.OrdinalIgnoreCase);

    private SKBitmap Decode(int imageIndex)
    {
        decodesOnCurrentThread++;
        var bitmap = DecodePixels(imageIndex);
        sizeIndex = imageIndex;
        imageSize = new SKSizeI(bitmap.Width, bitmap.Height);
        return bitmap;
    }

    private SKBitmap DecodePixels(int imageIndex)
    {
        if (IsTiff)
        {
            return PdfCartonFontCompat.DecodeImage(
                RequireInput().Bytes,
                imageIndex);
        }
        if (imageIndex != 0)
            throw new IndexOutOfRangeException(
                "This image reader exposes a single image.");
        if (PdfCartonImageCodecs.Supports(formatName))
            return PdfCartonImageCodecs.Decode(formatName, RequireInput().Bytes);
        using var stream = new MemoryStream(
            RequireInput().Bytes,
            writable: false);
        var bitmap = PdfCartonNativeBitmap.DecodeStream(stream) ??
            throw new IOException(
                $"Unable to decode {formatName} image data.");
        PdfCartonFontCompat.RegisterDecodedImageType(
            bitmap,
            string.Equals(formatName, "JPEG", StringComparison.OrdinalIgnoreCase) &&
                bitmap.ColorType != SKColorType.Gray8
                ? PdfCartonFontCompat.TYPE_3BYTE_BGR
                : PdfCartonFontCompat.InferImageType(bitmap),
            RequireInput().Bytes);
        return bitmap;
    }

    private JavaImageInputStream RequireInput() =>
        input ?? throw new InvalidOperationException(
            "No image input has been assigned.");

    private static void RequireImageIndex(int imageIndex)
    {
        if (imageIndex < 0)
            throw new IndexOutOfRangeException(
                "Image index must not be negative.");
    }

    // Takes ownership of source: returns it when the parameters select every
    // pixel unchanged (ImageIO reads a full-bounds region with 1x1
    // subsampling without a copy), otherwise disposes it and returns the
    // selected pixels in an image of the source's type and colour type.
    internal static SKBitmap ApplyReadParameters(
        SKBitmap source,
        JavaImageReadParam? parameters)
    {
        if (parameters is null) return source;
        int firstX, firstY, outputWidth, outputHeight, span;
        try
        {
            (firstX, firstY, outputWidth, outputHeight, span) =
                Selection(source.Width, source.Height, parameters);
        }
        catch
        {
            source.Dispose();
            throw;
        }
        // Output pixel (x, y) reads source pixel (firstX + x * periodX,
        // firstY + y * periodY), so a source-sized output is the source.
        if (outputWidth == source.Width && outputHeight == source.Height)
            return source;

        var periodX = parameters.SubsamplingX;
        var periodY = parameters.SubsamplingY;
        if (PdfCartonFontCompat.HasManagedImageData(source))
        {
            var colorModel = PdfCartonFontCompat.GetColorModel(source);
            var sourceRaster = PdfCartonFontCompat.GetRaster(source);
            var destinationRaster =
                colorModel.CreateCompatibleWritableRaster(outputWidth, outputHeight);
            var bands = sourceRaster.NumberOfBands;
            var row = new int[checked(span * bands)];
            for (var y = 0; y < outputHeight; y++)
            {
                sourceRaster.GetPixels(firstX, firstY + y * periodY, span, 1, row);
                // Selected pixels move left in place, never over unread ones.
                for (var x = 1; periodX != 1 && x < outputWidth; x++)
                    Array.Copy(row, x * periodX * bands, row, x * bands, bands);
                destinationRaster.SetPixels(0, y, outputWidth, 1, row);
            }
            // Like the source, the copy is read through its raster and
            // rendered only if drawn.
            var managedDestination = PdfCartonFontCompat.CreateDeferredImage(
                colorModel,
                destinationRaster,
                isRasterPremultiplied: false,
                null);
            source.Dispose();
            return managedDestination;
        }

        // Native pixels are copied as bytes, so the copy keeps the source's
        // colour type: a Gray8 image stays a one-band image.
        var info = source.Info;
        var destination = new PdfCartonNativeBitmap(
            info.WithSize(outputWidth, outputHeight));
        var imageType = PdfCartonFontCompat.GetImageType(source);
        var pixelBytes = info.BytesPerPixel;
        var input = source.GetPixelSpan();
        var output = destination.GetPixelSpan();
        for (var y = 0; y < outputHeight; y++)
        {
            var inputRow = input.Slice(
                (firstY + y * periodY) * source.RowBytes + firstX * pixelBytes,
                span * pixelBytes);
            var outputRow = output.Slice(
                y * destination.RowBytes,
                outputWidth * pixelBytes);
            if (periodX == 1)
            {
                inputRow.Slice(0, outputRow.Length).CopyTo(outputRow);
                continue;
            }
            for (var x = 0; x < outputWidth; x++)
            {
                inputRow.Slice(x * periodX * pixelBytes, pixelBytes)
                    .CopyTo(outputRow.Slice(x * pixelBytes, pixelBytes));
            }
        }
        destination.NotifyPixelsChanged();
        PdfCartonFontCompat.RegisterImageType(destination, imageType);
        source.Dispose();
        return destination;
    }

    // The samples a read selects from a raster of interleaved byte samples:
    // the source itself when that is every pixel, as for an image.
    private static JavaRaster ApplyReadParameters(
        JavaRaster source,
        JavaImageReadParam? parameters)
    {
        if (parameters is null) return source;
        var (firstX, firstY, outputWidth, outputHeight, _) =
            Selection(source.Width, source.Height, parameters);
        if (outputWidth == source.Width && outputHeight == source.Height)
            return source;
        var bands = source.NumberOfBands;
        var destination = new JavaRaster(
            PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE,
            outputWidth,
            outputHeight,
            bands);
        var input = source.GetByteData();
        var output = destination.GetByteData();
        var periodX = parameters.SubsamplingX;
        for (var y = 0; y < outputHeight; y++)
        {
            var inputRow = ((firstY + y * parameters.SubsamplingY) * source.Width + firstX) * bands;
            var outputRow = y * outputWidth * bands;
            if (periodX == 1)
            {
                Array.Copy(input, inputRow, output, outputRow, outputWidth * bands);
                continue;
            }
            for (var x = 0; x < outputWidth; x++)
            {
                Array.Copy(
                    input,
                    inputRow + x * periodX * bands,
                    output,
                    outputRow + x * bands,
                    bands);
            }
        }
        return destination;
    }

    // ImageIO's source region, clipped to the image, and subsampling: output
    // pixel (x, y) is source pixel (FirstX + x * periodX, FirstY + y *
    // periodY), and source columns FirstX .. FirstX + Span - 1 hold every
    // selected column.
    private static (int FirstX, int FirstY, int Width, int Height, int Span) Selection(
        int width,
        int height,
        JavaImageReadParam parameters)
    {
        var sourceRegion = parameters.SourceRegion ?? new SKRectI(0, 0, width, height);
        sourceRegion = new SKRectI(
            Math.Max(0, sourceRegion.Left),
            Math.Max(0, sourceRegion.Top),
            Math.Min(width, sourceRegion.Right),
            Math.Min(height, sourceRegion.Bottom));
        var firstX = sourceRegion.Left + parameters.SubsamplingOffsetX;
        var firstY = sourceRegion.Top + parameters.SubsamplingOffsetY;
        var outputWidth = Math.Max(
            0,
            (sourceRegion.Right - firstX + parameters.SubsamplingX - 1) /
            parameters.SubsamplingX);
        var outputHeight = Math.Max(
            0,
            (sourceRegion.Bottom - firstY + parameters.SubsamplingY - 1) /
            parameters.SubsamplingY);
        if (outputWidth == 0 || outputHeight == 0)
        {
            throw new ArgumentException(
                "The source region and subsampling produce an empty image.");
        }
        return (firstX, firstY, outputWidth, outputHeight, sourceRegion.Right - firstX);
    }
}

public sealed class JavaImageWriteParam
{
    public const int MODE_EXPLICIT = 2;

    internal int CompressionMode { get; private set; }
    internal float CompressionQuality { get; private set; } = 0.75f;

    public void SetCompressionMode(int mode)
    {
        if (mode != MODE_EXPLICIT)
            throw new ArgumentException(
                "Only explicit JPEG compression is supported.",
                nameof(mode));
        CompressionMode = mode;
    }

    public void SetCompressionQuality(float quality)
    {
        if (quality is < 0 or > 1 || float.IsNaN(quality))
            throw new ArgumentException(
                "Compression quality must be between zero and one.",
                nameof(quality));
        if (CompressionMode != MODE_EXPLICIT)
            throw new InvalidOperationException(
                "Compression mode must be explicit before setting quality.");
        CompressionQuality = quality;
    }
}

public sealed class JavaImageTypeSpecifier
{
    public JavaImageTypeSpecifier(SKBitmap renderedImage)
    {
        Image = renderedImage ??
            throw new ArgumentNullException(nameof(renderedImage));
    }

    internal SKBitmap Image { get; }
}

public sealed class JavaIioImage
{
    public JavaIioImage(
        SKBitmap renderedImage,
        object? thumbnails,
        JavaImageMetadata? metadata)
    {
        RenderedImage = renderedImage ??
            throw new ArgumentNullException(nameof(renderedImage));
        _ = thumbnails;
        _ = metadata;
    }

    internal SKBitmap RenderedImage { get; }
}

public sealed class JavaImageWriter : IDisposable
{
    private readonly string formatName;
    private JavaImageOutputStream? output;

    internal JavaImageWriter(string formatName) => this.formatName = formatName;

    public JavaImageWriteParam GetDefaultWriteParam() => new();

    public void SetOutput(object destination)
    {
        output = destination as JavaImageOutputStream ??
            throw new ArgumentException(
                "ImageWriter output must be an ImageOutputStream.",
                nameof(destination));
    }

    public JavaImageMetadata GetDefaultImageMetadata(
        JavaImageTypeSpecifier imageType,
        JavaImageWriteParam parameters)
    {
        ArgumentNullException.ThrowIfNull(imageType);
        ArgumentNullException.ThrowIfNull(parameters);
        return new JavaImageMetadata(
            PdfCartonFontCompat.GetColorModel(imageType.Image).NumberOfColorComponents);
    }

    public void Write(
        JavaImageMetadata metadata,
        JavaIioImage image,
        JavaImageWriteParam parameters)
    {
        ArgumentNullException.ThrowIfNull(metadata);
        ArgumentNullException.ThrowIfNull(image);
        ArgumentNullException.ThrowIfNull(parameters);
        if (!formatName.Equals("JPEG", StringComparison.OrdinalIgnoreCase))
            throw new IOException($"Unsupported image output format `{formatName}`.");
        var destination = output ??
            throw new InvalidOperationException("No image output has been assigned.");
        var quality = (int)Math.Round(
            parameters.CompressionQuality * 100,
            MidpointRounding.AwayFromZero);
        using var encoded = new MemoryStream();
        PdfCartonBitmapView.Publish(image.RenderedImage);
        if (!image.RenderedImage.Encode(
                encoded,
                SKEncodedImageFormat.Jpeg,
                quality))
        {
            throw new IOException("Skia failed to encode the JPEG image.");
        }
        var bytes = encoded.ToArray();
        if (metadata.JpegDensity is { } density)
            SetJfifDensity(bytes, density);
        destination.Destination.Write(bytes, 0, bytes.Length);
    }

    public void Dispose()
    {
        output = null;
    }

    private static void SetJfifDensity(byte[] encoded, int density)
    {
        for (var offset = 0; offset + 15 < encoded.Length; offset++)
        {
            if (encoded[offset] != 0xff || encoded[offset + 1] != 0xe0)
                continue;
            var payload = offset + 4;
            if (!encoded.AsSpan(payload, 5).SequenceEqual("JFIF\0"u8))
                continue;
            encoded[payload + 7] = 1;
            encoded[payload + 8] = (byte)(density >> 8);
            encoded[payload + 9] = (byte)density;
            encoded[payload + 10] = (byte)(density >> 8);
            encoded[payload + 11] = (byte)density;
            return;
        }
    }
}

public sealed class JavaImageOutputStream : IDisposable
{
    private readonly Stream destination;
    private int pendingByte;
    private int pendingBits;

    public JavaImageOutputStream(Stream destination)
    {
        ArgumentNullException.ThrowIfNull(destination);
        this.destination = destination;
    }

    public int BitOffset => pendingBits;
    internal Stream Destination => destination;

    public void WriteBits(long value, int numberOfBits)
    {
        if (numberOfBits is < 0 or > 64) throw new ArgumentException("Bit count must be between 0 and 64.");
        var unsigned = unchecked((ulong)value);
        for (var bitIndex = numberOfBits - 1; bitIndex >= 0; bitIndex--)
        {
            pendingByte = pendingByte << 1 | (int)(unsigned >> bitIndex & 1);
            pendingBits++;
            if (pendingBits == 8) WritePendingByte();
        }
    }

    public void Flush()
    {
        if (pendingBits != 0)
        {
            pendingByte <<= 8 - pendingBits;
            WritePendingByte();
        }
        destination.Flush();
    }

    public void Dispose() => Flush();

    private void WritePendingByte()
    {
        destination.WriteByte(unchecked((byte)pendingByte));
        pendingByte = 0;
        pendingBits = 0;
    }
}

public class JavaDataBuffer
{
    protected internal readonly JavaRaster Raster;

    protected internal JavaDataBuffer(JavaRaster raster) => Raster = raster;

    public int Size => Raster.StorageSize;
    public int DataType => Raster.TransferType;

    public int GetElement(int index) => Raster.GetStorageElement(index);

    public void SetElement(int index, int value) => Raster.SetStorageElement(index, value);
}

public sealed class JavaDataBufferByte : JavaDataBuffer
{
    public JavaDataBufferByte(int size)
        : base(new JavaRaster(PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE, size, 1, 1))
    {
    }

    internal JavaDataBufferByte(JavaRaster raster) : base(raster)
    {
    }

    public sbyte[] GetData() => Raster.GetByteData();
}

public sealed class JavaDataBufferUShort : JavaDataBuffer
{
    public JavaDataBufferUShort(int size)
        : base(new JavaRaster(PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT, size, 1, 1))
    {
    }

    internal JavaDataBufferUShort(JavaRaster raster) : base(raster)
    {
    }

    public short[] GetData() => Raster.GetUShortData();
}

public sealed class JavaDataBufferInt : JavaDataBuffer
{
    internal JavaDataBufferInt(JavaRaster raster) : base(raster)
    {
    }

    public int[] GetData() => Raster.GetIntData();
}

// A scoped view keeps the bitmap alive and invalidates Skia's cached images once
// after a write batch. Never retain the span across a bitmap resize or disposal.
internal ref struct PdfCartonPixels
{
    private readonly SKBitmap bitmap;
    private readonly Span<byte> bytes;
    private readonly SKImageInfo info;
    private readonly int rowBytes;
    private bool changed;

    internal PdfCartonPixels(SKBitmap bitmap)
    {
        this.bitmap = bitmap;
        info = bitmap.Info;
        rowBytes = bitmap.RowBytes;
        bytes = bitmap.GetPixelSpan();
        changed = false;
    }

    internal SKColor Get(int x, int y)
    {
        Validate(x, y);
        var offset = y * rowBytes + x * info.BytesPerPixel;
        if (info.ColorType == SKColorType.Gray8)
            return new SKColor(bytes[offset], bytes[offset], bytes[offset]);
        if (info.ColorType is not SKColorType.Bgra8888 and not SKColorType.Rgba8888)
            return bitmap.GetPixel(x, y);
        var red = bytes[offset + (info.ColorType == SKColorType.Bgra8888 ? 2 : 0)];
        var green = bytes[offset + 1];
        var blue = bytes[offset + (info.ColorType == SKColorType.Bgra8888 ? 0 : 2)];
        var alpha = info.AlphaType == SKAlphaType.Opaque ? (byte)255 : bytes[offset + 3];
        if (info.AlphaType == SKAlphaType.Premul && alpha != 255)
        {
            red = Unpremultiply(red, alpha);
            green = Unpremultiply(green, alpha);
            blue = Unpremultiply(blue, alpha);
        }
        return new SKColor(red, green, blue, alpha);
    }

    internal void Set(int x, int y, SKColor color)
    {
        Validate(x, y);
        var offset = y * rowBytes + x * info.BytesPerPixel;
        if (info.ColorType == SKColorType.Gray8 && color.Red == color.Green && color.Red == color.Blue)
            bytes[offset] = color.Red;
        else if (info.ColorType is SKColorType.Bgra8888 or SKColorType.Rgba8888)
        {
            var red = color.Red;
            var green = color.Green;
            var blue = color.Blue;
            var alpha = info.AlphaType == SKAlphaType.Opaque ? (byte)255 : color.Alpha;
            if (info.AlphaType == SKAlphaType.Premul)
            {
                red = Premultiply(red, alpha);
                green = Premultiply(green, alpha);
                blue = Premultiply(blue, alpha);
            }
            bytes[offset + (info.ColorType == SKColorType.Bgra8888 ? 2 : 0)] = red;
            bytes[offset + 1] = green;
            bytes[offset + (info.ColorType == SKColorType.Bgra8888 ? 0 : 2)] = blue;
            bytes[offset + 3] = alpha;
        }
        else
        {
            // Let Skia convert uncommon pixel formats; this does not create a canvas.
            using var pixels = bitmap.PeekPixels();
            if (!pixels.Erase(color, new SKRectI(x, y, x + 1, y + 1)))
                throw new InvalidOperationException("Unable to write bitmap pixel.");
        }
        changed = true;
    }

    // Copies every pixel's components, exactly as Get returns them, into
    // interleaved byte samples of a width x height raster: band b of pixel
    // (x, y) lands at (y * width + x) * bands + bandOffsets[b]. Bands are gray,
    // or red, green, blue and alpha. False for formats Get converts.
    internal bool TryCopyComponents(int width, int height, Span<sbyte> samples, int[] bandOffsets)
    {
        var bands = bandOffsets.Length;
        if (width != info.Width || height != info.Height ||
            samples.Length < checked(width * height * bands))
            return false;
        if (info.ColorType == SKColorType.Gray8)
        {
            if (bands != 1) return false;
            for (var y = 0; y < height; y++)
            {
                var input = bytes.Slice(y * rowBytes, width);
                var output = samples.Slice(y * width);
                for (var x = 0; x < width; x++)
                    output[x + bandOffsets[0]] = unchecked((sbyte)input[x]);
            }
            return true;
        }
        if (info.ColorType is not SKColorType.Bgra8888 and not SKColorType.Rgba8888 ||
            info.AlphaType == SKAlphaType.Premul ||
            bands is not 3 and not 4)
            return false;
        var redIndex = info.ColorType == SKColorType.Bgra8888 ? 2 : 0;
        var blueIndex = 2 - redIndex;
        var opaque = info.AlphaType == SKAlphaType.Opaque;
        int red = bandOffsets[0], green = bandOffsets[1], blue = bandOffsets[2];
        var alpha = bands == 4 ? bandOffsets[3] : -1;
        for (var y = 0; y < height; y++)
        {
            var input = bytes.Slice(y * rowBytes, width * 4);
            var output = samples.Slice(y * width * bands, width * bands);
            for (int x = 0, source = 0, target = 0; x < width; x++, source += 4, target += bands)
            {
                output[target + red] = unchecked((sbyte)input[source + redIndex]);
                output[target + green] = unchecked((sbyte)input[source + 1]);
                output[target + blue] = unchecked((sbyte)input[source + blueIndex]);
                if (alpha >= 0)
                    output[target + alpha] = opaque ? (sbyte)-1 : unchecked((sbyte)input[source + 3]);
            }
        }
        return true;
    }

    // Writes one full row, storing exactly what Set stores for each color.
    internal void SetRow(int y, ReadOnlySpan<SKColor> colors)
    {
        if ((uint)y >= (uint)info.Height || colors.Length != info.Width)
            throw new ArgumentOutOfRangeException();
        var offset = y * rowBytes;
        if (info.ColorType == SKColorType.Bgra8888 &&
            info.AlphaType != SKAlphaType.Premul &&
            BitConverter.IsLittleEndian)
        {
            // SKColor is 0xAARRGGBB, so its little-endian bytes are BGRA.
            var output = MemoryMarshal.Cast<byte, uint>(bytes.Slice(offset, colors.Length * 4));
            var input = MemoryMarshal.Cast<SKColor, uint>(colors);
            changed = true;
            if (info.AlphaType != SKAlphaType.Opaque)
            {
                input.CopyTo(output);
                return;
            }
            for (var x = 0; x < input.Length; x++) output[x] = input[x] | 0xff000000;
            return;
        }
        if (info.ColorType == SKColorType.Gray8)
        {
            for (var x = 0; x < colors.Length; x++)
            {
                var color = colors[x];
                if (color.Red == color.Green && color.Red == color.Blue)
                {
                    bytes[offset + x] = color.Red;
                    changed = true;
                }
                else
                    Set(x, y, color);
            }
            return;
        }
        for (var x = 0; x < colors.Length; x++) Set(x, y, colors[x]);
    }

    // The caller validates the region and sample array before entering these bulk
    // paths. Other formats and alpha modes retain the per-pixel conversion above.
    internal bool TryGetPixels(int x, int y, int width, int height, int[] pixels)
    {
        if (info.ColorType != SKColorType.Bgra8888 ||
            info.AlphaType != SKAlphaType.Unpremul || !BitConverter.IsLittleEndian)
            return false;
        if (width == 0 || height == 0) return true;
        // A retained JavaRaster can outlive a bitmap reallocation. Let the
        // original per-pixel path preserve its bounds and partial-write behavior.
        if (x > info.Width - width || y > info.Height - height) return false;

        var sampleCount = checked(width * 4);
        for (var row = 0; row < height; row++)
        {
            var input = bytes.Slice((y + row) * rowBytes + x * 4, sampleCount);
            var output = pixels.AsSpan(row * sampleCount, sampleCount);
            var offset = 0;
            if (Vector.IsHardwareAccelerated)
            {
                for (; offset <= sampleCount - Vector<byte>.Count; offset += Vector<byte>.Count)
                {
                    var rgba = SwapRedBlue(Unsafe.ReadUnaligned<Vector<uint>>(ref input[offset]));
                    Vector.Widen(Vector.AsVectorByte(rgba),
                        out Vector<ushort> low, out Vector<ushort> high);
                    Vector.Widen(low, out Vector<uint> first, out Vector<uint> second);
                    Vector.Widen(high, out Vector<uint> third, out Vector<uint> fourth);
                    Unsafe.WriteUnaligned(ref Unsafe.As<int, byte>(ref output[offset]), first);
                    Unsafe.WriteUnaligned(ref Unsafe.As<int, byte>(ref output[offset + Vector<uint>.Count]), second);
                    Unsafe.WriteUnaligned(ref Unsafe.As<int, byte>(ref output[offset + Vector<uint>.Count * 2]), third);
                    Unsafe.WriteUnaligned(ref Unsafe.As<int, byte>(ref output[offset + Vector<uint>.Count * 3]), fourth);
                }
            }
            for (; offset < sampleCount; offset += 4)
            {
                var pixel = Unsafe.ReadUnaligned<uint>(ref input[offset]);
                output[offset] = (int)((pixel >> 16) & 255);
                output[offset + 1] = (int)((pixel >> 8) & 255);
                output[offset + 2] = (int)(pixel & 255);
                output[offset + 3] = (int)(pixel >> 24);
            }
        }
        return true;
    }

    internal bool TrySetPixels(int x, int y, int width, int height, int[] pixels)
    {
        if (info.ColorType != SKColorType.Bgra8888 ||
            info.AlphaType != SKAlphaType.Unpremul || !BitConverter.IsLittleEndian)
            return false;
        if (width == 0 || height == 0) return true;
        if (x > info.Width - width || y > info.Height - height) return false;

        var sampleCount = checked(width * 4);
        var byteMask = new Vector<uint>(255);
        for (var row = 0; row < height; row++)
        {
            var input = pixels.AsSpan(row * sampleCount, sampleCount);
            var output = bytes.Slice((y + row) * rowBytes + x * 4, sampleCount);
            // Mark before writing so Dispose also invalidates Skia after any
            // partially completed write, just as the per-pixel path does.
            changed = true;
            var offset = 0;
            if (Vector.IsHardwareAccelerated)
            {
                for (; offset <= sampleCount - Vector<byte>.Count; offset += Vector<byte>.Count)
                {
                    // Mask before narrowing to preserve unchecked byte conversion
                    // for negative samples and values greater than 255.
                    var first = Unsafe.ReadUnaligned<Vector<uint>>(
                        ref Unsafe.As<int, byte>(ref input[offset])) & byteMask;
                    var second = Unsafe.ReadUnaligned<Vector<uint>>(
                        ref Unsafe.As<int, byte>(ref input[offset + Vector<uint>.Count])) & byteMask;
                    var third = Unsafe.ReadUnaligned<Vector<uint>>(
                        ref Unsafe.As<int, byte>(ref input[offset + Vector<uint>.Count * 2])) & byteMask;
                    var fourth = Unsafe.ReadUnaligned<Vector<uint>>(
                        ref Unsafe.As<int, byte>(ref input[offset + Vector<uint>.Count * 3])) & byteMask;
                    var packed = Vector.Narrow(Vector.Narrow(first, second), Vector.Narrow(third, fourth));
                    Unsafe.WriteUnaligned(ref output[offset], SwapRedBlue(Vector.AsVectorUInt32(packed)));
                }
            }
            for (; offset < sampleCount; offset += 4)
            {
                var pixel = (uint)unchecked((byte)input[offset + 2]) |
                    ((uint)unchecked((byte)input[offset + 1]) << 8) |
                    ((uint)unchecked((byte)input[offset]) << 16) |
                    ((uint)unchecked((byte)input[offset + 3]) << 24);
                Unsafe.WriteUnaligned(ref output[offset], pixel);
            }
        }
        return true;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private static Vector<uint> SwapRedBlue(Vector<uint> pixel)
    {
        // netstandard2.0 Vector<T> has no shift or byte-shuffle API. Multiply
        // moves blue left; exact float scaling moves masked red right. Every
        // masked red value is below 2^24 and exactly representable, and scaling
        // by 2^-16 produces an exact integer, so conversion cannot round.
        var red = Vector.AsVectorInt32(pixel & new Vector<uint>(0x00ff0000));
        var redToBlue = Vector.AsVectorUInt32(Vector.ConvertToInt32(
            Vector.ConvertToSingle(red) * new Vector<float>(1f / 65536f)));
        var blueToRed = (pixel & new Vector<uint>(255)) * new Vector<uint>(65536);
        return (pixel & new Vector<uint>(0xff00ff00)) | blueToRed | redToBlue;
    }

    private void Validate(int x, int y)
    {
        if ((uint)x >= (uint)info.Width || (uint)y >= (uint)info.Height)
            throw new ArgumentOutOfRangeException();
    }

    internal static byte Premultiply(byte value, byte alpha) =>
        (byte)((value * alpha + 127) / 255);

    internal static byte Unpremultiply(byte value, byte alpha)
    {
        if (alpha == 0) return 0;
        // Match SkUnPreMultiply's 24-bit scale and rounding, including ties.
        var scale = ((255UL << 24) + (uint)alpha / 2) / alpha;
        return (byte)Math.Min(255UL, (scale * value + (1UL << 23)) >> 24);
    }

    public void Dispose()
    {
        if (changed) bitmap.NotifyPixelsChanged();
        GC.KeepAlive(bitmap);
    }

    internal static SKColor Read(SKBitmap bitmap, int x, int y)
    {
        using var pixels = new PdfCartonPixels(bitmap);
        return pixels.Get(x, y);
    }

    internal static void Write(SKBitmap bitmap, int x, int y, SKColor color)
    {
        using var pixels = new PdfCartonPixels(bitmap);
        pixels.Set(x, y, color);
    }
}

// A raster's cached view of a bitmap's native pixels. Format, row stride and
// pixel address are read once, so per-pixel reads and writes make no native
// call. Writes leave Skia's pixel generation alone and mark the bitmap
// instead; Publish, which compat draws and encodes call before Skia reads a
// bitmap, notifies Skia once for any number of writes. The address is valid
// until the bitmap is disposed (checked on every access) or its pixels are
// reallocated. Compat code never reallocates a bitmap and live views are
// internal; the only one that outlives a compat call is the raster GetRaster
// retains, which GetRaster checks (IsReallocated) before returning it. A
// reallocated view is replaced there and refuses any later access.
internal sealed class PdfCartonBitmapView
{
    private sealed class PendingWrites
    {
        internal int Dirty;
        // Set when a raster's exposed sample array is the bitmap's storage
        // (PdfCartonFontCompat.ExposeSamples): renders the array's writes.
        internal volatile Action? Refresh;
    }

    private static readonly ConditionalWeakTable<SKBitmap, PendingWrites> PendingByBitmap = new();
    private static readonly ConditionalWeakTable<SKBitmap, PendingWrites>.CreateValueCallback
        CreatePendingWrites = _ => new PendingWrites();

    private readonly SKBitmap bitmap;
    private readonly IntPtr pixels;
    private readonly int width;
    private readonly int height;
    private readonly int rowBytes;
    private readonly int bytesPerPixel;
    private readonly SKColorType colorType;
    private readonly SKAlphaType alphaType;
    private readonly PendingWrites pending;
    private volatile bool reallocated;

    internal PdfCartonBitmapView(SKBitmap bitmap)
    {
        this.bitmap = bitmap;
        var info = bitmap.Info;
        width = info.Width;
        height = info.Height;
        bytesPerPixel = info.BytesPerPixel;
        colorType = info.ColorType;
        alphaType = info.AlphaType;
        rowBytes = bitmap.RowBytes;
        pixels = bitmap.GetPixels();
        pending = PendingByBitmap.GetValue(bitmap, CreatePendingWrites);
        GC.KeepAlive(bitmap);
    }

    internal SKAlphaType AlphaType => alphaType;

    // True when the live bitmap's pixels or layout no longer match this view,
    // which from then on refuses access instead of touching released pixels.
    // A disposed bitmap is not reallocated: accessing it throws instead.
    internal bool IsReallocated()
    {
        if (reallocated) return true;
        if (bitmap.Handle == IntPtr.Zero) return false;
        var info = bitmap.Info;
        var changed = info.Width != width || info.Height != height ||
                          info.ColorType != colorType || info.AlphaType != alphaType ||
                          bitmap.RowBytes != rowBytes || bitmap.GetPixels() != pixels;
        GC.KeepAlive(bitmap);
        if (changed) reallocated = true;
        return changed;
    }

    internal SKColor Get(int x, int y)
    {
        var offset = Offset(x, y);
        SKColor color;
        if (colorType == SKColorType.Gray8)
        {
            var gray = Marshal.ReadByte(pixels, offset);
            color = new SKColor(gray, gray, gray);
        }
        else if (colorType is SKColorType.Bgra8888 or SKColorType.Rgba8888)
        {
            var value = ReadPixel(offset);
            var red = unchecked((byte)(colorType == SKColorType.Bgra8888 ? value >> 16 : value));
            var green = unchecked((byte)(value >> 8));
            var blue = unchecked((byte)(colorType == SKColorType.Bgra8888 ? value : value >> 16));
            var alpha = alphaType == SKAlphaType.Opaque ? byte.MaxValue : unchecked((byte)(value >> 24));
            if (alphaType == SKAlphaType.Premul && alpha != byte.MaxValue)
            {
                red = PdfCartonPixels.Unpremultiply(red, alpha);
                green = PdfCartonPixels.Unpremultiply(green, alpha);
                blue = PdfCartonPixels.Unpremultiply(blue, alpha);
            }
            color = new SKColor(red, green, blue, alpha);
        }
        else
        {
            color = bitmap.GetPixel(x, y);
        }
        GC.KeepAlive(bitmap);
        return color;
    }

    internal void Set(int x, int y, SKColor color)
    {
        var offset = Offset(x, y);
        // Mark first, as PdfCartonPixels does, so a failed write still publishes.
        if (Volatile.Read(ref pending.Dirty) == 0) Volatile.Write(ref pending.Dirty, 1);
        if (colorType == SKColorType.Gray8 && color.Red == color.Green && color.Red == color.Blue)
        {
            Marshal.WriteByte(pixels, offset, color.Red);
        }
        else if (colorType is SKColorType.Bgra8888 or SKColorType.Rgba8888)
        {
            var red = color.Red;
            var green = color.Green;
            var blue = color.Blue;
            var alpha = alphaType == SKAlphaType.Opaque ? byte.MaxValue : color.Alpha;
            if (alphaType == SKAlphaType.Premul)
            {
                red = PdfCartonPixels.Premultiply(red, alpha);
                green = PdfCartonPixels.Premultiply(green, alpha);
                blue = PdfCartonPixels.Premultiply(blue, alpha);
            }
            var (low, high) = colorType == SKColorType.Bgra8888 ? (blue, red) : (red, blue);
            WritePixel(offset, (uint)low | (uint)green << 8 | (uint)high << 16 | (uint)alpha << 24);
        }
        else
        {
            // Let Skia convert uncommon pixel formats; this does not create a canvas.
            using var pixmap = bitmap.PeekPixels();
            if (!pixmap.Erase(color, new SKRectI(x, y, x + 1, y + 1)))
                throw new InvalidOperationException("Unable to write bitmap pixel.");
        }
        GC.KeepAlive(bitmap);
    }

    // Bumps Skia's pixel generation once if a view wrote since the last call.
    // Writes into an int-backed bitmap's exposed pixel array are not tracked,
    // so such a bitmap is published on every call. Writes into an exposed
    // byte sample array are rendered into the native pixels first.
    internal static void Publish(SKBitmap bitmap)
    {
        var tracked = PendingByBitmap.TryGetValue(bitmap, out var pending);
        if (tracked) pending!.Refresh?.Invoke();
        var written = tracked && Interlocked.Exchange(ref pending!.Dirty, 0) != 0;
        if (written || bitmap is PdfCartonNativeBitmap { IntPixelsExposed: true })
            bitmap.NotifyPixelsChanged();
    }

    internal static void SetRefresh(SKBitmap bitmap, Action refresh) =>
        PendingByBitmap.GetValue(bitmap, CreatePendingWrites).Refresh = refresh;

    private int Offset(int x, int y)
    {
        if (bitmap.Handle == IntPtr.Zero) throw new ObjectDisposedException(nameof(SKBitmap));
        if (reallocated)
            throw new InvalidOperationException(
                "The bitmap's pixels were reallocated after this raster was created.");
        if ((uint)x >= (uint)width || (uint)y >= (uint)height)
            throw new ArgumentOutOfRangeException();
        return y * rowBytes + x * bytesPerPixel;
    }

    // Memory order B, G, R, A for BGRA: the low byte is the first in memory.
    private uint ReadPixel(int offset)
    {
        var value = unchecked((uint)Marshal.ReadInt32(pixels, offset));
        return BitConverter.IsLittleEndian ? value : System.Buffers.Binary.BinaryPrimitives.ReverseEndianness(value);
    }

    private void WritePixel(int offset, uint value) =>
        Marshal.WriteInt32(pixels, offset, unchecked((int)(BitConverter.IsLittleEndian
            ? value
            : System.Buffers.Binary.BinaryPrimitives.ReverseEndianness(value))));
}

public sealed class JavaRaster
{
    private readonly SKBitmap? bitmap;
    private Array? storage;
    private readonly int width;
    private readonly int height;
    private readonly int numberOfBands;
    private readonly int transferType;
    private readonly int pixelStride;
    private readonly int scanlineStride;
    private readonly int[] bandOffsets;
    private readonly int packedPixelBits;
    private readonly int[]? bitMasks;
    private readonly int[]? bitShifts;
    // A bitmap view's image type and native pixel view, fixed when the view
    // is created, so per-pixel accessors make no native or table lookup.
    private readonly int imageType;
    private readonly PdfCartonBitmapView? view;

    internal JavaRaster(SKBitmap bitmap)
    {
        this.bitmap = bitmap;
        width = bitmap.Width;
        height = bitmap.Height;
        numberOfBands =
            bitmap.ColorType == SKColorType.Gray8 ? 1 :
            bitmap.AlphaType == SKAlphaType.Opaque ? 3 : 4;
        imageType = PdfCartonFontCompat.GetImageType(bitmap);
        view = new PdfCartonBitmapView(bitmap);
        transferType = imageType switch
        {
            PdfCartonFontCompat.TYPE_INT_RGB or
            PdfCartonFontCompat.TYPE_INT_ARGB or
            PdfCartonFontCompat.TYPE_INT_BGR => PdfCartonFontCompat.DATA_BUFFER_TYPE_INT,
            PdfCartonFontCompat.TYPE_CUSTOM when
                bitmap.ColorType == SKColorType.Bgra8888 =>
                PdfCartonFontCompat.DATA_BUFFER_TYPE_INT,
            _ => PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE
        };
        pixelStride = numberOfBands;
        scanlineStride = checked(width * pixelStride);
        // Java's PixelInterleavedSampleModel layouts: B,G,R and A,B,G,R bytes.
        bandOffsets = imageType == PdfCartonFontCompat.TYPE_3BYTE_BGR ? [2, 1, 0]
            : imageType == PdfCartonFontCompat.TYPE_4BYTE_ABGR && numberOfBands == 4 ? [3, 2, 1, 0]
            : Enumerable.Range(0, numberOfBands).ToArray();
    }

    internal JavaRaster(int dataType, int width, int height, int bands)
        : this(dataType, width, height, bands, Enumerable.Range(0, bands).ToArray())
    {
    }

    private JavaRaster(
        int dataType,
        int width,
        int height,
        int bands,
        int[] bandOffsets)
    {
        if (dataType is not PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE and
            not PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT and
            not PdfCartonFontCompat.DATA_BUFFER_TYPE_INT)
            throw new ArgumentException("Unsupported raster data type.", nameof(dataType));
        if (width <= 0 || height <= 0 || bands <= 0)
            throw new ArgumentException("Raster dimensions and band count must be positive.");
        this.width = width;
        this.height = height;
        numberOfBands = bands;
        transferType = dataType;
        pixelStride = bands;
        scanlineStride = checked(width * pixelStride);
        this.bandOffsets = (int[])bandOffsets.Clone();
        var length = checked(width * height * bands);
        storage = dataType switch
        {
            PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE => new sbyte[length],
            PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT => new short[length],
            _ => new int[length]
        };
    }

    private JavaRaster(
        int width,
        int height,
        int packedPixelBits,
        sbyte[] packedData)
    {
        if (packedPixelBits is not 1 and not 2 and not 4)
            throw new ArgumentOutOfRangeException(nameof(packedPixelBits));
        this.width = width;
        this.height = height;
        numberOfBands = 1;
        transferType = PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE;
        pixelStride = 0;
        scanlineStride = checked((width * packedPixelBits + 7) / 8);
        bandOffsets = [0];
        if (packedData.Length < checked(scanlineStride * height))
            throw new ArgumentException("Packed raster storage is truncated.", nameof(packedData));
        storage = packedData;
        this.packedPixelBits = packedPixelBits;
    }

    internal static JavaRaster Packed(int width, int height, int pixelBits) =>
        new(
            width,
            height,
            pixelBits,
            new sbyte[checked((width * pixelBits + 7) / 8 * height)]);

    // Wraps rows of (width * pixelBits + 7) / 8 bytes without copying them.
    internal static JavaRaster Packed(int width, int height, int pixelBits, sbyte[] data) =>
        new(width, height, pixelBits, data);

    // Java's SinglePixelPackedSampleModel, as DirectColorModel's compatible
    // raster: one int per pixel, each band selected by its bit mask. Data
    // elements are the packed ints; pixels and samples are the band values.
    private JavaRaster(int width, int height, int[] bitMasks, int[] packedData)
    {
        if (width <= 0 || height <= 0 || bitMasks.Length == 0)
            throw new ArgumentException("Raster dimensions and band count must be positive.");
        this.width = width;
        this.height = height;
        numberOfBands = bitMasks.Length;
        transferType = PdfCartonFontCompat.DATA_BUFFER_TYPE_INT;
        pixelStride = 1;
        scanlineStride = width;
        bandOffsets = new int[numberOfBands];
        this.bitMasks = (int[])bitMasks.Clone();
        bitShifts = new int[numberOfBands];
        for (var band = 0; band < numberOfBands; band++)
            bitShifts[band] = JavaCompat.TrailingZeroCount(unchecked((uint)bitMasks[band]));
        if (packedData.Length < checked(width * height))
            throw new ArgumentException("Packed raster storage is truncated.", nameof(packedData));
        storage = packedData;
    }

    internal static JavaRaster SinglePixelPacked(int width, int height, int[] bitMasks) =>
        new(width, height, bitMasks, new int[checked(width * height)]);

    internal bool IsSinglePixelPacked => bitMasks is not null;

    // Sets every packed pixel to one value, as Arrays.fill on the int data.
    internal void FillStorage(int value)
    {
        if (bitMasks is null || storage is not int[] packed)
            throw new InvalidOperationException("Only single-pixel-packed int rasters can be filled.");
        packed.AsSpan().Fill(value);
    }

    internal bool HasBitMasks(ReadOnlySpan<int> masks) =>
        bitMasks is not null && masks.SequenceEqual(bitMasks);

    internal static JavaRaster BinarySnapshot(SKBitmap bitmap)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        var scanlineStride = checked((bitmap.Width + 7) / 8);
        var data = new sbyte[checked(scanlineStride * bitmap.Height)];
        using var access = new PdfCartonPixels(bitmap);
        for (var y = 0; y < bitmap.Height; y++)
        {
            for (var x = 0; x < bitmap.Width; x++)
            {
                if (access.Get(x, y).Red < 128) continue;
                var index = y * scanlineStride + x / 8;
                data[index] = unchecked(
                    (sbyte)(unchecked((byte)data[index]) | 1 << (7 - x % 8)));
            }
        }
        return new JavaRaster(bitmap.Width, bitmap.Height, 1, data);
    }

    internal JavaRaster(
        JavaDataBuffer buffer,
        int width,
        int height,
        int scanlineStride,
        int pixelStride,
        int[] bandOffsets)
    {
        ArgumentNullException.ThrowIfNull(buffer);
        ArgumentNullException.ThrowIfNull(bandOffsets);
        if (width <= 0 || height <= 0 ||
            scanlineStride <= 0 || pixelStride <= 0 ||
            bandOffsets.Length == 0 || bandOffsets.Any(offset => offset < 0))
            throw new ArgumentException("Invalid interleaved raster layout.");
        storage = buffer.Raster.storage ??
            throw new ArgumentException(
                "Interleaved raster construction requires an array-backed data buffer.",
                nameof(buffer));
        transferType = buffer.DataType;
        this.width = width;
        this.height = height;
        this.scanlineStride = scanlineStride;
        this.pixelStride = pixelStride;
        this.bandOffsets = (int[])bandOffsets.Clone();
        numberOfBands = bandOffsets.Length;
        var maximumIndex = checked(
            (height - 1) * scanlineStride +
            (width - 1) * pixelStride +
            bandOffsets.Max());
        if (maximumIndex >= storage.Length)
            throw new ArgumentException(
                "The data buffer is too small for the interleaved raster layout.",
                nameof(buffer));
    }

    public int Width => width;
    public int Height => height;
    public int MinX => 0;
    public int MinY => 0;
    public int NumberOfBands => numberOfBands;
    public int TransferType => transferType;
    internal int PackedPixelBits => packedPixelBits;
    internal int StorageSize => storage?.Length ?? checked(width * height);
    // A managed sample array that no bitmap view shadows.
    internal bool HasArrayStorage => storage is not null && bitmap is null;
    // True while this raster reads and writes the bitmap's native pixels.
    internal bool IsLiveViewOf(SKBitmap image) =>
        storage is null && ReferenceEquals(bitmap, image);
    // False for a live view whose bitmap's pixels were reallocated.
    internal bool HasCurrentPixels => storage is not null || view?.IsReallocated() != true;

    public JavaRaster CreateCompatibleWritableRaster() =>
        packedPixelBits != 0
            ? Packed(Width, Height, packedPixelBits)
            : bitMasks is not null
            ? SinglePixelPacked(Width, Height, bitMasks)
            : new(
                TransferType,
                Width,
                Height,
                NumberOfBands,
                bandOffsets);

    internal JavaRaster DeepCopy()
    {
        if (packedPixelBits != 0)
        {
            return new JavaRaster(
                Width,
                Height,
                packedPixelBits,
                (sbyte[])storage!.Clone());
        }
        if (bitMasks is not null)
            return new JavaRaster(Width, Height, bitMasks, (int[])storage!.Clone());
        var copy = new JavaRaster(
            TransferType,
            Width,
            Height,
            NumberOfBands,
            bandOffsets);
        if (bitmap is not null && storage is null && copy.storage is sbyte[] samples)
        {
            using var access = new PdfCartonPixels(bitmap);
            if (access.TryCopyComponents(Width, Height, samples, bandOffsets))
                return copy;
        }
        var row = new int[checked(Width * NumberOfBands)];
        for (var y = 0; y < Height; y++)
            copy.SetPixels(0, y, Width, 1, GetPixels(0, y, Width, 1, row));
        return copy;
    }

    internal JavaRaster CopyRegion(int x, int y, int width, int height)
    {
        ValidateRegion(x, y, width, height);
        if (bitmap is not null && storage is null &&
            imageType is PdfCartonFontCompat.TYPE_INT_RGB or
                PdfCartonFontCompat.TYPE_INT_ARGB or PdfCartonFontCompat.TYPE_INT_BGR)
        {
            // An INT bitmap's data elements are packed pixels, so its copy is a
            // packed raster holding the same elements: the image's colour
            // model decodes element zero of each one (BlendComposite's dstIn).
            var packed = SinglePixelPacked(width, height, BitmapPixelMasks());
            var data = (int[])packed.storage!;
            var elements = new int[width];
            for (var line = 0; line < height; line++)
            {
                GetDataElements(x, y + line, width, 1, elements);
                Array.Copy(elements, 0, data, line * width, width);
            }
            return packed;
        }
        var copy = packedPixelBits != 0
            ? Packed(width, height, packedPixelBits)
            : bitMasks is not null
            ? SinglePixelPacked(width, height, bitMasks)
            : new JavaRaster(TransferType, width, height, NumberOfBands, bandOffsets);
        var row = new int[checked(width * NumberOfBands)];
        for (var offset = 0; offset < height; offset++)
            copy.SetPixels(0, offset, width, 1, GetPixels(x, y + offset, width, 1, row));
        return copy;
    }

    // Band masks of the packed pixels an INT bitmap view's data elements hold.
    private int[] BitmapPixelMasks() =>
        imageType == PdfCartonFontCompat.TYPE_INT_BGR ? [0xff, 0xff00, 0xff0000]
        : NumberOfBands == 4 ? [0xff0000, 0xff00, 0xff, unchecked((int)0xff000000)]
        : [0xff0000, 0xff00, 0xff];

    public JavaDataBuffer GetDataBuffer() =>
        TransferType switch
        {
            PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE => new JavaDataBufferByte(this),
            PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT => new JavaDataBufferUShort(this),
            _ => new JavaDataBufferInt(this)
        };

    public int[] GetSamples(int x, int y, int width, int height, int band, int[]? samples)
    {
        ValidateRegion(x, y, width, height);
        if ((uint)band >= (uint)NumberOfBands) throw new IndexOutOfRangeException();
        samples ??= new int[checked(width * height)];
        if (samples.Length < width * height) throw new IndexOutOfRangeException();
        var offset = 0;
        if (bitmap is not null && storage is null)
        {
            using var access = new PdfCartonPixels(bitmap);
            for (var row = y; row < y + height; row++)
                for (var column = x; column < x + width; column++)
                    samples[offset++] = Component(access.Get(column, row), band);
            return samples;
        }
        for (var row = y; row < y + height; row++)
        {
            for (var column = x; column < x + width; column++)
            {
                samples[offset++] = GetComponent(column, row, band);
            }
        }
        return samples;
    }

    public void SetSamples(int x, int y, int width, int height, int band, int[] samples)
    {
        ArgumentNullException.ThrowIfNull(samples);
        ValidateRegion(x, y, width, height);
        if ((uint)band >= (uint)NumberOfBands || samples.Length < width * height)
            throw new IndexOutOfRangeException();
        var offset = 0;
        if (bitmap is not null && storage is null)
        {
            using var access = new PdfCartonPixels(bitmap);
            for (var row = y; row < y + height; row++)
                for (var column = x; column < x + width; column++)
                    access.Set(column, row, WithComponent(access.Get(column, row), band, samples[offset++]));
            return;
        }
        for (var row = y; row < y + height; row++)
        {
            for (var column = x; column < x + width; column++)
            {
                SetComponent(column, row, band, samples[offset++]);
            }
        }
    }

    public int[] GetPixels(int x, int y, int width, int height, int[]? pixels)
    {
        ValidateRegion(x, y, width, height);
        var length = checked(width * height * NumberOfBands);
        pixels ??= new int[length];
        if (pixels.Length < length) throw new IndexOutOfRangeException();
        var offset = 0;
        if (bitmap is not null && storage is null)
        {
            using var access = new PdfCartonPixels(bitmap);
            if (NumberOfBands == 4 && access.TryGetPixels(x, y, width, height, pixels))
                return pixels;
            for (var row = y; row < y + height; row++)
                for (var column = x; column < x + width; column++)
                {
                    var color = access.Get(column, row);
                    for (var band = 0; band < NumberOfBands; band++)
                        pixels[offset++] = Component(color, band);
                }
            return pixels;
        }
        for (var row = y; row < y + height; row++)
        {
            for (var column = x; column < x + width; column++)
            {
                for (var band = 0; band < NumberOfBands; band++)
                {
                    pixels[offset++] = GetComponent(column, row, band);
                }
            }
        }
        return pixels;
    }

    // Managed samples back this raster: an array-backed raster or a bitmap
    // view whose storage GetByteData/GetUShortData materialized.
    internal bool HasSampleStorage => storage is not null;

    // Reads row y's samples from managed storage, band-interleaved exactly as
    // GetPixels returns them. Callers check HasSampleStorage.
    internal void ReadRowSamples(int y, int[] samples)
    {
        if ((uint)y >= (uint)height || samples.Length < width * numberOfBands)
            throw new IndexOutOfRangeException();
        var rowStart = y * scanlineStride;
        if (packedPixelBits != 0)
        {
            var packed = (sbyte[])storage!;
            var mask = (1 << packedPixelBits) - 1;
            for (var x = 0; x < width; x++)
            {
                var bitOffset = x * packedPixelBits;
                samples[x] = unchecked((byte)packed[rowStart + (bitOffset >> 3)]) >>
                    (8 - packedPixelBits - (bitOffset & 7)) & mask;
            }
            return;
        }
        var offset = 0;
        if (bitMasks is not null)
        {
            var pixels = (int[])storage!;
            for (var x = 0; x < width; x++)
            {
                var pixel = unchecked((uint)pixels[rowStart + x]);
                for (var band = 0; band < numberOfBands; band++)
                    samples[offset++] = unchecked((int)((pixel & (uint)bitMasks[band]) >> bitShifts![band]));
            }
            return;
        }
        switch (storage)
        {
            case sbyte[] bytes:
                for (var x = 0; x < width; x++, rowStart += pixelStride)
                    for (var band = 0; band < numberOfBands; band++)
                        samples[offset++] = unchecked((byte)bytes[rowStart + bandOffsets[band]]);
                return;
            case short[] words:
                for (var x = 0; x < width; x++, rowStart += pixelStride)
                    for (var band = 0; band < numberOfBands; band++)
                        samples[offset++] = unchecked((ushort)words[rowStart + bandOffsets[band]]);
                return;
            case int[] integers:
                for (var x = 0; x < width; x++, rowStart += pixelStride)
                    for (var band = 0; band < numberOfBands; band++)
                        samples[offset++] = integers[rowStart + bandOffsets[band]];
                return;
            default:
                throw new InvalidOperationException("Raster does not use array-backed storage.");
        }
    }

    public object GetDataElements(
        int x,
        int y,
        int width,
        int height,
        object? output)
    {
        ValidateRegion(x, y, width, height);
        if (bitmap is not null && TransferType == PdfCartonFontCompat.DATA_BUFFER_TYPE_INT)
        {
            var values = output as int[] ?? new int[checked(width * height)];
            if (values.Length < width * height) throw new IndexOutOfRangeException();
            var offset = 0;
            var pixels = view!;
            var bgr = imageType == PdfCartonFontCompat.TYPE_INT_BGR;
            var alphaMask = imageType == PdfCartonFontCompat.TYPE_INT_ARGB ||
                            pixels.AlphaType != SKAlphaType.Opaque
                ? 0xff000000u
                : 0u;
            for (var row = y; row < y + height; row++)
            {
                for (var column = x; column < x + width; column++)
                {
                    var color = pixels.Get(column, row);
                    values[offset++] = bgr
                        ? color.Red | color.Green << 8 | color.Blue << 16
                        : unchecked((int)(((uint)color.Alpha << 24 & alphaMask) |
                                          (uint)color.Red << 16 |
                                          (uint)color.Green << 8 |
                                          color.Blue));
                }
            }
            return values;
        }

        if (bitMasks is not null)
        {
            var packed = output as int[] ?? new int[checked(width * height)];
            if (packed.Length < width * height) throw new IndexOutOfRangeException();
            var data = (int[])storage!;
            for (var row = 0; row < height; row++)
                Array.Copy(data, (y + row) * scanlineStride + x, packed, row * width, width);
            return packed;
        }

        var length = checked(width * height * NumberOfBands);
        if (TransferType == PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT)
        {
            var words = output as short[] ?? new short[length];
            if (words.Length < length) throw new IndexOutOfRangeException();
            var wordOffset = 0;
            for (var row = y; row < y + height; row++)
            {
                for (var column = x; column < x + width; column++)
                {
                    for (var band = 0; band < NumberOfBands; band++)
                    {
                        words[wordOffset++] = unchecked((short)GetComponent(column, row, band));
                    }
                }
            }
            return words;
        }

        if (TransferType == PdfCartonFontCompat.DATA_BUFFER_TYPE_INT)
        {
            var values = output as int[] ?? new int[length];
            if (values.Length < length) throw new IndexOutOfRangeException();
            var valueOffset = 0;
            for (var row = y; row < y + height; row++)
            {
                for (var column = x; column < x + width; column++)
                {
                    for (var band = 0; band < NumberOfBands; band++)
                    {
                        values[valueOffset++] = GetComponent(column, row, band);
                    }
                }
            }
            return values;
        }

        var bytes = output as sbyte[] ?? new sbyte[length];
        if (bytes.Length < length) throw new IndexOutOfRangeException();
        var byteOffset = 0;
        if (view is not null && storage is null)
        {
            for (var row = y; row < y + height; row++)
            {
                for (var column = x; column < x + width; column++)
                {
                    var color = view.Get(column, row);
                    for (var band = 0; band < NumberOfBands; band++)
                        bytes[byteOffset++] = unchecked((sbyte)Component(color, band));
                }
            }
            return bytes;
        }
        for (var row = y; row < y + height; row++)
        {
            for (var column = x; column < x + width; column++)
            {
                for (var band = 0; band < NumberOfBands; band++)
                {
                    bytes[byteOffset++] = unchecked((sbyte)GetComponent(column, row, band));
                }
            }
        }
        return bytes;
    }

    public object GetDataElements(int x, int y, object? output) =>
        GetDataElements(x, y, 1, 1, output);

    public void SetPixels(int x, int y, int width, int height, int[] pixels)
    {
        ArgumentNullException.ThrowIfNull(pixels);
        ValidateRegion(x, y, width, height);
        if (pixels.Length < width * height * NumberOfBands) throw new IndexOutOfRangeException();
        var offset = 0;
        if (bitmap is not null && storage is null)
        {
            using var access = new PdfCartonPixels(bitmap);
            if (NumberOfBands == 4 && access.TrySetPixels(x, y, width, height, pixels))
                return;
            for (var row = y; row < y + height; row++)
                for (var column = x; column < x + width; column++)
                {
                    access.Set(column, row, PixelColor(pixels, offset));
                    offset += NumberOfBands;
                }
            return;
        }
        for (var row = y; row < y + height; row++)
        {
            for (var column = x; column < x + width; column++)
            {
                for (var band = 0; band < NumberOfBands; band++)
                {
                    SetComponent(column, row, band, pixels[offset++]);
                }
            }
        }
    }

    public void SetDataElements(int x, int y, object values)
    {
        ArgumentNullException.ThrowIfNull(values);
        ValidateRegion(x, y, 1, 1);
        if (bitmap is not null &&
            TransferType == PdfCartonFontCompat.DATA_BUFFER_TYPE_INT &&
            values is int[] packed)
        {
            if (packed.Length == 0) throw new IndexOutOfRangeException();
            if (imageType == PdfCartonFontCompat.TYPE_INT_BGR)
            {
                // The element GetDataElements packs: 0x00BBGGRR.
                var element = packed[0];
                view!.Set(x, y, new SKColor(
                    unchecked((byte)element),
                    unchecked((byte)(element >> 8)),
                    unchecked((byte)(element >> 16))));
                return;
            }
            SetStorageElement(y * Width + x, packed[0]);
            return;
        }

        if (bitmap is not null && storage is null)
        {
            var length = values switch
            {
                sbyte[] bytes => bytes.Length,
                short[] words => words.Length,
                int[] integers => integers.Length,
                _ => 0
            };
            if (length < NumberOfBands)
                throw new ArgumentException("Raster data elements do not match its transfer type.");
            byte Value(int band) => values switch
            {
                sbyte[] bytes => unchecked((byte)bytes[band]),
                short[] words => unchecked((byte)words[band]),
                int[] integers => unchecked((byte)integers[band]),
                _ => throw new ArgumentException("Unsupported raster data elements.")
            };
            var red = Value(0);
            view!.Set(x, y, NumberOfBands == 1
                ? new SKColor(red, red, red)
                : new SKColor(red, Value(1), Value(2), NumberOfBands == 4 ? Value(3) : (byte)255));
            return;
        }

        if (bitMasks is not null)
        {
            if (values is not int[] packedValues)
                throw new ArgumentException("Raster data elements do not match its transfer type.");
            if (packedValues.Length == 0) throw new IndexOutOfRangeException();
            ((int[])storage!)[y * scanlineStride + x] = packedValues[0];
            return;
        }

        switch (values)
        {
            case sbyte[] bytes when bytes.Length >= NumberOfBands:
                for (var band = 0; band < NumberOfBands; band++)
                    SetComponent(x, y, band, unchecked((byte)bytes[band]));
                return;
            case short[] words when words.Length >= NumberOfBands:
                for (var band = 0; band < NumberOfBands; band++)
                    SetComponent(x, y, band, unchecked((ushort)words[band]));
                return;
            case int[] integers when integers.Length >= NumberOfBands:
                for (var band = 0; band < NumberOfBands; band++)
                    SetComponent(x, y, band, integers[band]);
                return;
            default:
                throw new ArgumentException("Raster data elements do not match its transfer type.");
        }
    }

    public void SetPixel(int x, int y, int[] values)
    {
        ArgumentNullException.ThrowIfNull(values);
        ValidateRegion(x, y, 1, 1);
        if (values.Length < NumberOfBands) throw new IndexOutOfRangeException();
        if (bitmap is not null && storage is null)
        {
            view!.Set(x, y, PixelColor(values, 0));
            return;
        }
        for (var band = 0; band < NumberOfBands; band++)
            SetComponent(x, y, band, values[band]);
    }

    private SKColor PixelColor(int[] values, int offset) =>
        NumberOfBands == 1
            ? new SKColor(unchecked((byte)values[offset]), unchecked((byte)values[offset]), unchecked((byte)values[offset]))
            : new SKColor(unchecked((byte)values[offset]), unchecked((byte)values[offset + 1]),
                unchecked((byte)values[offset + 2]), NumberOfBands == 4 ? unchecked((byte)values[offset + 3]) : (byte)255);

    public int[] GetPixel(int x, int y, int[]? values)
    {
        ValidateRegion(x, y, 1, 1);
        values ??= new int[NumberOfBands];
        if (values.Length < NumberOfBands) throw new IndexOutOfRangeException();
        if (bitmap is not null && storage is null)
        {
            var color = view!.Get(x, y);
            for (var band = 0; band < NumberOfBands; band++)
                values[band] = Component(color, band);
        }
        else
            for (var band = 0; band < NumberOfBands; band++)
                values[band] = GetComponent(x, y, band);
        return values;
    }

    public float[] GetPixel(int x, int y, float[]? values)
    {
        ValidateRegion(x, y, 1, 1);
        values ??= new float[NumberOfBands];
        if (values.Length < NumberOfBands) throw new IndexOutOfRangeException();
        if (bitmap is not null && storage is null)
        {
            var color = view!.Get(x, y);
            for (var band = 0; band < NumberOfBands; band++)
                values[band] = Component(color, band);
        }
        else
            for (var band = 0; band < NumberOfBands; band++)
                values[band] = GetComponent(x, y, band);
        return values;
    }

    public void SetPixel(int x, int y, float[] values)
    {
        ArgumentNullException.ThrowIfNull(values);
        ValidateRegion(x, y, 1, 1);
        if (values.Length < NumberOfBands) throw new IndexOutOfRangeException();
        if (bitmap is not null && storage is null)
        {
            var red = unchecked((byte)(int)values[0]);
            view!.Set(x, y, NumberOfBands == 1
                ? new SKColor(red, red, red)
                : new SKColor(red, unchecked((byte)(int)values[1]), unchecked((byte)(int)values[2]),
                    NumberOfBands == 4 ? unchecked((byte)(int)values[3]) : (byte)255));
            return;
        }
        for (var band = 0; band < NumberOfBands; band++)
            SetComponent(x, y, band, (int)values[band]);
    }

    internal int GetStorageElement(int index)
    {
        if ((uint)index >= (uint)StorageSize) throw new IndexOutOfRangeException();
        if (storage is sbyte[] bytes) return unchecked((byte)bytes[index]);
        if (storage is short[] words) return unchecked((ushort)words[index]);
        if (storage is int[] integers) return integers[index];
        var color = view!.Get(index % width, index / width);
        return NumberOfBands == 1
            ? color.Red
            : unchecked((int)((uint)color.Alpha << 24 |
                              (uint)color.Red << 16 |
                              (uint)color.Green << 8 |
                              color.Blue));
    }

    internal void SetStorageElement(int index, int value)
    {
        if ((uint)index >= (uint)StorageSize) throw new IndexOutOfRangeException();
        if (storage is not null)
        {
            SetStoredValue(index, value);
            return;
        }

        var x = index % width;
        var y = index / width;
        if (NumberOfBands == 1)
        {
            var gray = unchecked((byte)value);
            view!.Set(x, y, new SKColor(gray, gray, gray));
            return;
        }
        view!.Set(
            x,
            y,
            new SKColor(
                unchecked((byte)(value >> 16)),
                unchecked((byte)(value >> 8)),
                unchecked((byte)value),
                unchecked((byte)(value >> 24))));
    }

    private int GetComponent(int x, int y, int band)
    {
        if ((uint)band >= (uint)NumberOfBands) throw new IndexOutOfRangeException();
        if (packedPixelBits != 0)
        {
            var bitOffset = x * packedPixelBits;
            var index = y * scanlineStride + bitOffset / 8;
            var shift = 8 - packedPixelBits - bitOffset % 8;
            var mask = (1 << packedPixelBits) - 1;
            return unchecked((byte)((sbyte[])storage!)[index]) >> shift & mask;
        }
        if (bitMasks is not null)
        {
            return unchecked((int)(
                ((uint)((int[])storage!)[y * scanlineStride + x] & (uint)bitMasks[band]) >>
                bitShifts![band]));
        }
        return storage is not null
            ? GetStorageElement(StorageIndex(x, y, band))
            : Component(view!.Get(x, y), band);
    }

    private void SetComponent(int x, int y, int band, int value)
    {
        if ((uint)band >= (uint)NumberOfBands) throw new IndexOutOfRangeException();
        if (packedPixelBits != 0)
        {
            var bytes = (sbyte[])storage!;
            var bitOffset = x * packedPixelBits;
            var index = y * scanlineStride + bitOffset / 8;
            var shift = 8 - packedPixelBits - bitOffset % 8;
            var componentMask = (1 << packedPixelBits) - 1;
            var mask = componentMask << shift;
            var current = unchecked((byte)bytes[index]);
            bytes[index] = unchecked(
                (sbyte)(current & ~mask | (value & componentMask) << shift));
            return;
        }
        if (bitMasks is not null)
        {
            var data = (int[])storage!;
            var index = y * scanlineStride + x;
            var bandMask = bitMasks[band];
            data[index] = data[index] & ~bandMask | value << bitShifts![band] & bandMask;
            return;
        }
        if (storage is not null)
        {
            SetStoredValue(StorageIndex(x, y, band), value);
            return;
        }
        view!.Set(x, y, WithComponent(view.Get(x, y), band, value));
    }

    private int StorageIndex(int x, int y, int band) =>
        checked(y * scanlineStride + x * pixelStride + bandOffsets[band]);

    private void SetStoredValue(int index, int value)
    {
        switch (storage)
        {
            case sbyte[] bytes:
                bytes[index] = unchecked((sbyte)value);
                break;
            case short[] words:
                words[index] = unchecked((short)value);
                break;
            case int[] integers:
                integers[index] = value;
                break;
            default:
                throw new InvalidOperationException("Raster does not use array-backed storage.");
        }
    }

    // DataBufferByte.getData(). A bitmap view's samples move into a managed
    // array once; for an image's retained raster that array becomes the
    // image's storage, as in Java (PdfCartonFontCompat.ExposeSamples).
    internal sbyte[] GetByteData()
    {
        if (storage is null)
        {
            MaterializeStorage(PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE);
            if (storage is sbyte[] samples) PdfCartonFontCompat.ExposeSamples(bitmap!, this, samples);
        }
        return storage as sbyte[]
            ?? throw new InvalidOperationException("Raster is not byte-backed.");
    }

    // DataBufferInt.getData(): the int storage itself. An array-backed raster
    // returns its array, and a view of an int-backed bitmap (Java's
    // TYPE_INT_RGB and TYPE_INT_ARGB images) the bitmap's pixels. Any other
    // bitmap's pixels are not packed ints Java code can address, so its data
    // elements are read once, in bulk, into a detached array.
    internal int[] GetIntData()
    {
        if (storage is int[] integers) return integers;
        if (storage is not null || bitmap is null ||
            TransferType != PdfCartonFontCompat.DATA_BUFFER_TYPE_INT)
            throw new InvalidOperationException("Raster is not int-backed.");
        if (bitmap.Handle == IntPtr.Zero) throw new ObjectDisposedException(nameof(SKBitmap));
        if (view!.IsReallocated())
            throw new InvalidOperationException(
                "The bitmap's pixels were reallocated after this raster was created.");
        if (bitmap is PdfCartonNativeBitmap intBacked &&
            intBacked.CurrentIntPixels() is { } pixels)
        {
            intBacked.ExposeIntPixels();
            return pixels;
        }
        var elements = new int[StorageSize];
        using var access = new PdfCartonPixels(bitmap);
        var index = 0;
        for (var y = 0; y < Height; y++)
        {
            for (var x = 0; x < Width; x++)
            {
                var color = access.Get(x, y);
                elements[index++] = NumberOfBands == 1
                    ? color.Red
                    : unchecked((int)((uint)color.Alpha << 24 | (uint)color.Red << 16 |
                                      (uint)color.Green << 8 | color.Blue));
            }
        }
        return elements;
    }

    internal short[] GetUShortData()
    {
        if (storage is null) MaterializeStorage(PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT);
        return storage as short[]
            ?? throw new InvalidOperationException("Raster is not unsigned-short-backed.");
    }

    private void MaterializeStorage(int dataType)
    {
        if (bitmap is null || TransferType != dataType)
            throw new InvalidOperationException("Raster storage type does not match.");
        storage = dataType == PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE
            ? new sbyte[checked(Width * Height * NumberOfBands)]
            : new short[checked(Width * Height * NumberOfBands)];
        using var access = new PdfCartonPixels(bitmap);
        if (storage is sbyte[] samples &&
            access.TryCopyComponents(Width, Height, samples, bandOffsets))
            return;
        for (var y = 0; y < Height; y++)
        {
            for (var x = 0; x < Width; x++)
            {
                var color = access.Get(x, y);
                for (var band = 0; band < NumberOfBands; band++)
                {
                    SetStoredValue(StorageIndex(x, y, band), Component(color, band));
                }
            }
        }
    }

    private int Component(SKColor color, int band) =>
        NumberOfBands == 1 ? color.Red :
        band switch
        {
            0 => color.Red,
            1 => color.Green,
            2 => color.Blue,
            3 => color.Alpha,
            _ => throw new IndexOutOfRangeException()
        };

    private SKColor WithComponent(SKColor color, int band, int value)
    {
        var component = unchecked((byte)value);
        if (NumberOfBands == 1)
        {
            return new SKColor(component, component, component);
        }
        var alpha = NumberOfBands == 3 ? byte.MaxValue : color.Alpha;
        return band switch
        {
            0 => new SKColor(component, color.Green, color.Blue, alpha),
            1 => new SKColor(color.Red, component, color.Blue, alpha),
            2 => new SKColor(color.Red, color.Green, component, alpha),
            3 => new SKColor(color.Red, color.Green, color.Blue, component),
            _ => throw new IndexOutOfRangeException()
        };
    }

    private void ValidateRegion(int x, int y, int width, int height)
    {
        if (x < 0 || y < 0 || width < 0 || height < 0 ||
            x > Width - width || y > Height - height)
        {
            throw new IndexOutOfRangeException();
        }
    }
}

public sealed class PdfCartonAffineTransformOp
{
    public const int TYPE_BILINEAR = 2;
    public const int TYPE_BICUBIC = 3;

    private readonly SKMatrix transform;
    private readonly int interpolationType;

    public PdfCartonAffineTransformOp(SKMatrix transform, int interpolationType)
    {
        if (interpolationType is not TYPE_BILINEAR and not TYPE_BICUBIC)
            throw new ArgumentException("Unsupported image interpolation type.", nameof(interpolationType));
        this.transform = transform;
        this.interpolationType = interpolationType;
    }

    public SKBitmap Filter(SKBitmap source, SKBitmap destination)
    {
        ArgumentNullException.ThrowIfNull(source);
        ArgumentNullException.ThrowIfNull(destination);
        PdfCartonBitmapView.Publish(source);
        using var canvas = new SKCanvas(destination);
        canvas.Clear(SKColors.Transparent);
        canvas.SetMatrix(transform);
        using var paint = new SKPaint();
        var sampling =
            interpolationType == TYPE_BICUBIC
                ? new SKSamplingOptions(SKCubicResampler.Mitchell)
                : new SKSamplingOptions(SKFilterMode.Linear);
        using var image = PdfCartonFontCompat.DrawableImage(source, destination);
        canvas.DrawImage(image!, 0, 0, sampling, paint);
        return destination;
    }
}

public sealed class JavaColorConvertOp
{
    private readonly JavaColorSpace? destinationColorSpace;
    // The constructor's spaces, which Java converts every colour through
    // between the source and destination images' spaces.
    private readonly JavaColorSpace[] spaces = Array.Empty<JavaColorSpace>();
    // Java's op holds ICC profiles when it was given only ICC spaces (all
    // but public-override subclasses) and a list of spaces otherwise.
    private readonly bool hasProfiles;
    private readonly bool hasCustomSpaces;

    public JavaColorConvertOp()
    {
    }

    public JavaColorConvertOp(
        JavaColorSpace sourceColorSpace,
        JavaColorSpace destinationColorSpace)
    {
        ArgumentNullException.ThrowIfNull(sourceColorSpace);
        this.destinationColorSpace = destinationColorSpace ??
            throw new ArgumentNullException(nameof(destinationColorSpace));
        spaces = [sourceColorSpace, destinationColorSpace];
        hasCustomSpaces = sourceColorSpace.OverridesArrayConversions ||
            destinationColorSpace.OverridesArrayConversions;
        hasProfiles = !hasCustomSpaces;
    }

    public JavaColorConvertOp(JavaColorSpace destinationColorSpace)
    {
        this.destinationColorSpace = destinationColorSpace ??
            throw new ArgumentNullException(nameof(destinationColorSpace));
        spaces = [destinationColorSpace];
        hasCustomSpaces = destinationColorSpace.OverridesArrayConversions;
        hasProfiles = !hasCustomSpaces;
    }

    public SKBitmap Filter(SKBitmap source, SKBitmap? destination)
    {
        ArgumentNullException.ThrowIfNull(source);
        if (destination is not null) return Convert(source, destination);

        var colorSpace = destinationColorSpace ??
            PdfCartonFontCompat.GetColorModel(source).ColorSpace;
        var colorModel = new JavaColorModel(
            colorSpace,
            false,
            PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE);
        var raster = colorModel.CreateCompatibleWritableRaster(
            source.Width,
            source.Height);
        // Convert ends with SetImageData, which renders the native pixels, so
        // the new image skips CreateImage's eager render. It is disposed if
        // the conversion fails because it never reaches the caller.
        var created = PdfCartonFontCompat.CreateDeferredImage(
            colorModel,
            raster,
            false,
            null);
        try
        {
            return Convert(source, created);
        }
        catch
        {
            created.Dispose();
            throw;
        }
    }

    private SKBitmap Convert(SKBitmap source, SKBitmap destination)
    {
        if (source.Width != destination.Width ||
            source.Height != destination.Height)
        {
            throw new ArgumentException(
                "Source and destination image dimensions must match.");
        }
        var sourceColorModel = PdfCartonFontCompat.GetColorModel(source);
        var destinationColorModel = PdfCartonFontCompat.GetColorModel(destination);
        var sourceRaster = PdfCartonFontCompat.GetRaster(source);
        var destinationRaster = PdfCartonFontCompat.GetRaster(destination);
        var width = source.Width;
        var sourceCount = sourceColorModel.NumberOfComponents;
        var sourceAlphaIndex = sourceColorModel.NumberOfColorComponents;
        var sourceColorSpace = sourceColorModel.ColorSpace;
        var destinationColorSpace = destinationColorModel.ColorSpace;
        var destinationColorCount = destinationColorModel.NumberOfColorComponents;
        var destinationHasAlpha = destinationColorModel.HasAlpha;
        // Java converts through the constructor's spaces in turn. An op of
        // ICC spaces runs one profile transform from the source's profile
        // to the destination's, leaving out a first or last space with the
        // profile that image already has; an op given a public-override
        // space passes each colour through every space's fromCIEXYZ and
        // toCIEXYZ.
        var start = 0;
        var stop = spaces.Length;
        if (hasProfiles && SameProfile(sourceColorSpace, spaces[0])) start++;
        if (hasProfiles && stop > start && SameProfile(destinationColorSpace, spaces[stop - 1])) stop--;
        // With no space left between two images sharing one profile, Java
        // draws the source onto the destination as Graphics2D.drawImage
        // does: through 8-bit sRGB (clipping a wide gamut), compositing the
        // source's alpha over the destination's pixels.
        if (start >= stop && !hasCustomSpaces && SameProfile(sourceColorSpace, destinationColorSpace))
            return DrawOver(destination, sourceColorModel, sourceRaster, destinationColorModel, destinationRaster);
        // Java renders relative colorimetric when the source's profile is an
        // output profile and perceptually otherwise, whatever the op's
        // profiles are (perceptually when the source is a public-override
        // space); an op given a public-override space, or one without
        // profiles converting such a space, goes through toCIEXYZ and
        // fromCIEXYZ, which are colorimetric.
        var throughCieXyz = hasCustomSpaces ||
            !hasProfiles && (sourceColorSpace.OverridesArrayConversions ||
                destinationColorSpace.OverridesArrayConversions);
        var colorimetric = throughCieXyz || sourceColorSpace is JavaIccColorSpace icc &&
            !icc.IsSrgb && icc.Profile.GetProfileClass() == JavaIccProfile.CLASS_OUTPUT;
        // Each pixel is read once into a reused row buffer and converted once
        // through the D50 PCS, as Java's profile-to-profile transform does,
        // without allocating.
        var sourceRow = new float[checked(width * sourceCount)];
        int[]? sourceSamples = null;
        object? sourcePixel = null;
        object? destinationPixel = null;
        var destinationComponents =
            new float[destinationColorModel.NumberOfComponents];
        Span<float> xyz = stackalloc float[3];
        Span<float> converted = stackalloc float[JavaColorSpace.MaximumComponents];
        Span<float> intermediate = stackalloc float[JavaColorSpace.MaximumComponents];
        // When the destination raster is the bitmap's own live pixel view under
        // its image-type model, the converted components go straight to the
        // native pixels: the per-pixel raster writes and the final re-render
        // would store exactly these bytes.
        var direct = PdfCartonFontCompat.RendersAsStored(
            destination,
            destinationColorModel,
            destinationRaster);
        var output = direct ? new PdfCartonPixels(destination) : default;
        try
        {
            for (var y = 0; y < source.Height; y++)
            {
                sourceColorModel.GetNormalizedRow(
                    sourceRaster,
                    y,
                    sourceRow,
                    ref sourceSamples,
                    ref sourcePixel);
                for (var x = 0; x < width; x++)
                {
                    var sourceComponents =
                        new ReadOnlySpan<float>(sourceRow, x * sourceCount, sourceCount);
                    ToXyz(sourceColorSpace, sourceComponents, xyz, colorimetric, throughCieXyz);
                    for (var index = start; index < stop; index++)
                    {
                        var space = spaces[index];
                        var count = FromXyz(space, xyz, intermediate, colorimetric, throughCieXyz);
                        ToXyz(space, intermediate.Slice(0, count), xyz, colorimetric, throughCieXyz);
                    }
                    var convertedCount = FromXyz(
                        destinationColorSpace, xyz, converted, colorimetric, throughCieXyz);
                    if (convertedCount < destinationColorCount)
                        throw new ArgumentException(
                            "The converted color has fewer components than the destination.");
                    var alpha = !destinationHasAlpha
                        ? 1f
                        : sourceColorModel.HasAlpha
                            ? sourceComponents[sourceAlphaIndex]
                            : 1f;
                    if (direct)
                    {
                        var first = PdfCartonFontCompat.ComponentByte(converted[0]);
                        output.Set(
                            x,
                            y,
                            destinationColorCount == 1
                                ? new SKColor(first, first, first)
                                : new SKColor(
                                    first,
                                    PdfCartonFontCompat.ComponentByte(converted[1]),
                                    PdfCartonFontCompat.ComponentByte(converted[2]),
                                    destinationHasAlpha
                                        ? PdfCartonFontCompat.ComponentByte(alpha)
                                        : byte.MaxValue));
                        continue;
                    }
                    converted.Slice(0, destinationColorCount)
                        .CopyTo(destinationComponents);
                    if (destinationHasAlpha)
                        destinationComponents[destinationColorCount] = alpha;
                    destinationPixel = destinationColorModel.GetDataElements(
                        destinationComponents,
                        0,
                        destinationPixel);
                    destinationRaster.SetDataElements(x, y, destinationPixel);
                }
            }
        }
        finally
        {
            if (direct) output.Dispose();
        }
        PdfCartonFontCompat.SetImageData(destination, destinationRaster);
        return destination;
    }

    // Every destination pixel is stored again, through the colour model as
    // Java2D's drawing loops store a colour, from the source-over composite
    // of both rasters' current pixels as those loops read them.
    private static SKBitmap DrawOver(
        SKBitmap destination,
        JavaColorModel sourceColorModel,
        JavaRaster sourceRaster,
        JavaColorModel destinationColorModel,
        JavaRaster destinationRaster)
    {
        int[]? sourceSamples = null, destinationSamples = null;
        object? sourcePixel = null, destinationPixel = null, element = null;
        float[]? components = null;
        for (var y = 0; y < destinationRaster.Height; y++)
        {
            for (var x = 0; x < destinationRaster.Width; x++)
            {
                var top = sourceColorModel.RenderedColorAt(sourceRaster, x, y, ref sourceSamples, ref sourcePixel);
                var under = destinationColorModel.RenderedColorAt(
                    destinationRaster, x, y, ref destinationSamples, ref destinationPixel);
                var sourceAlpha = top.Alpha / 255f;
                var underAlpha = under.Alpha / 255f * (1f - sourceAlpha);
                var alpha = sourceAlpha + underAlpha;
                byte Channel(byte over, byte below) => alpha <= 0f ? (byte)0 :
                    PdfCartonFontCompat.ComponentByte((over * sourceAlpha + below * underAlpha) / 255f / alpha);
                var drawn = new SKColor(
                    Channel(top.Red, under.Red),
                    Channel(top.Green, under.Green),
                    Channel(top.Blue, under.Blue),
                    PdfCartonFontCompat.ComponentByte(alpha));
                element = destinationColorModel.GetDrawnDataElements(
                    unchecked((int)(uint)drawn), ref components, element);
                destinationRaster.SetDataElements(x, y, element);
            }
        }
        PdfCartonFontCompat.SetImageData(destination, destinationRaster);
        return destination;
    }

    // An op converting through CIE XYZ uses every space's public toCIEXYZ
    // and fromCIEXYZ, which quantize through 16-bit samples as Java's do;
    // a profile transform converts each space's PCS values unquantized.
    private static void ToXyz(
        JavaColorSpace space, ReadOnlySpan<float> components, Span<float> xyz,
        bool colorimetric, bool throughCieXyz)
    {
        if (!throughCieXyz || space.OverridesArrayConversions)
            space.ConvertToXyz(components, xyz, colorimetric);
        else
            space.ToCieXyz(components.ToArray()).AsSpan(0, 3).CopyTo(xyz);
    }

    private static int FromXyz(
        JavaColorSpace space, ReadOnlySpan<float> xyz, Span<float> output,
        bool colorimetric, bool throughCieXyz)
    {
        if (!throughCieXyz || space.OverridesArrayConversions)
            return space.ConvertFromXyz(xyz, output, colorimetric);
        var result = space.FromCieXyz(xyz.ToArray());
        result.AsSpan().CopyTo(output);
        return result.Length;
    }

    // Java compares the profiles of an image's ICC space and the op's by
    // identity, not by content.
    private static bool SameProfile(JavaColorSpace image, JavaColorSpace space) =>
        !image.OverridesArrayConversions &&
        (ReferenceEquals(image, space) ||
            image is JavaIccColorSpace imageIcc && space is JavaIccColorSpace spaceIcc &&
            ReferenceEquals(imageIcc.Profile, spaceIcc.Profile));
}

public sealed class JavaLookupTable
{
    private readonly sbyte[] values;

    public JavaLookupTable(int offset, sbyte[] values)
    {
        if (offset < 0) throw new ArgumentOutOfRangeException(nameof(offset));
        ArgumentNullException.ThrowIfNull(values);
        if (values.Length == 0)
            throw new ArgumentException("Lookup table cannot be empty.", nameof(values));
        Offset = offset;
        this.values = (sbyte[])values.Clone();
    }

    private int Offset { get; }

    internal byte Lookup(byte value)
    {
        var index = value - Offset;
        if ((uint)index >= (uint)values.Length)
            throw new ArgumentException(
                $"Image sample {value} is outside the lookup table.");
        return unchecked((byte)values[index]);
    }
}

public sealed class JavaLookupOp
{
    private readonly JavaLookupTable table;

    public JavaLookupOp(
        JavaLookupTable table,
        PdfCartonRenderingHints? renderingHints)
    {
        this.table = table ??
            throw new ArgumentNullException(nameof(table));
        _ = renderingHints;
    }

    public SKBitmap Filter(SKBitmap source, SKBitmap destination)
    {
        ArgumentNullException.ThrowIfNull(source);
        ArgumentNullException.ThrowIfNull(destination);
        if (source.Width != destination.Width ||
            source.Height != destination.Height)
        {
            throw new ArgumentException(
                "Source and destination image dimensions must match.");
        }
        using var input = new PdfCartonPixels(source);
        using var output = new PdfCartonPixels(destination);
        for (var y = 0; y < source.Height; y++)
        {
            for (var x = 0; x < source.Width; x++)
            {
                var color = input.Get(x, y);
                output.Set(
                    x,
                    y,
                    new SKColor(
                        table.Lookup(color.Red),
                        table.Lookup(color.Green),
                        table.Lookup(color.Blue),
                        color.Alpha));
            }
        }
        return destination;
    }
}

public class JavaSampleModel
{
    internal JavaSampleModel()
    {
    }
}

public sealed class JavaMultiPixelPackedSampleModel : JavaSampleModel
{
    internal JavaMultiPixelPackedSampleModel(int pixelBitStride) =>
        PixelBitStride = pixelBitStride;

    public int PixelBitStride { get; }
}

public sealed class JavaColor : JavaPaint, IEquatable<JavaColor>
{
    internal JavaColor(SKColor value) => Value = value;

    internal SKColor Value { get; }
    public byte Red => Value.Red;
    public byte Green => Value.Green;
    public byte Blue => Value.Blue;
    public byte Alpha => Value.Alpha;

    public static JavaColor White { get; } = new(SKColors.White);
    public static JavaColor Gray { get; } = new(SKColors.Gray);

    public JavaPaintContext CreateContext(
        JavaColorModel colorModel,
        SKRectI deviceBounds,
        SKRect userBounds,
        SKMatrix transform,
        PdfCartonRenderingHints hints)
    {
        _ = colorModel;
        _ = deviceBounds;
        _ = userBounds;
        _ = transform;
        _ = hints;
        return new SolidPaintContext(Value);
    }

    public int GetTransparency() =>
        Alpha == byte.MaxValue
            ? PdfCartonTransparency.OPAQUE
            : Alpha == 0
                ? PdfCartonTransparency.BITMASK
                : PdfCartonTransparency.TRANSLUCENT;

    public bool Equals(JavaColor? other) =>
        other is not null && Value == other.Value;

    public override bool Equals(object? other) =>
        other is JavaColor color && Equals(color);

    public override int GetHashCode() => Value.GetHashCode();

    public static implicit operator SKColor(JavaColor color) => color.Value;
    public static implicit operator JavaColor(SKColor color) => new(color);

    // Java's ColorPaintContext: a raster of the default ARGB model holding
    // the colour's ARGB value, so the pixels and the model agree on alpha,
    // opaque colours included.
    private sealed class SolidPaintContext : JavaPaintContext
    {
        private readonly int argb;
        private readonly JavaColorModel colorModel =
            new(PdfCartonFontCompat.TYPE_INT_ARGB);

        internal SolidPaintContext(SKColor color) => argb = unchecked((int)(uint)color);

        public JavaColorModel GetColorModel() => colorModel;

        public JavaRaster GetRaster(int x, int y, int width, int height)
        {
            _ = x;
            _ = y;
            if (width <= 0 || height <= 0)
                throw new ArgumentException("Raster dimensions must be positive.");
            var raster = colorModel.CreateCompatibleWritableRaster(width, height);
            raster.FillStorage(argb);
            return raster;
        }

        public void Dispose() { }
    }
}

public interface JavaPaint
{
    JavaPaintContext CreateContext(
        JavaColorModel colorModel,
        SKRectI deviceBounds,
        SKRect userBounds,
        SKMatrix transform,
        PdfCartonRenderingHints hints);

    int GetTransparency();
}

public sealed class JavaTexturePaint : JavaPaint
{
    public JavaTexturePaint(SKBitmap image, SKRect anchor)
    {
        ArgumentNullException.ThrowIfNull(image);
        Image = image;
        Anchor = anchor;
    }

    public SKBitmap Image { get; }
    public SKRect Anchor { get; }

    public JavaPaintContext CreateContext(
        JavaColorModel colorModel,
        SKRectI deviceBounds,
        SKRect userBounds,
        SKMatrix transform,
        PdfCartonRenderingHints hints)
    {
        _ = colorModel;
        _ = deviceBounds;
        _ = userBounds;
        return new TexturePaintContext(Image, Anchor, transform, hints);
    }

    public int GetTransparency() =>
        Image.AlphaType == SKAlphaType.Opaque
            ? PdfCartonTransparency.OPAQUE
            : PdfCartonTransparency.TRANSLUCENT;

    // java.awt.TexturePaintContext drawn by Skia: PdfCartonGraphics2D fills
    // with its shader, and GetRaster (custom paints such as SoftMask that
    // wrap it) draws that shader into an INT_ARGB raster. No pixel is
    // sampled in managed code.
    internal sealed class TexturePaintContext : JavaPaintContext
    {
        private readonly List<SKBitmap> rasters = new();
        private readonly SKBitmap image;
        private readonly SKMatrix deviceFromImage;
        private readonly SKSamplingOptions sampling;
        private readonly JavaColorModel colorModel =
            new(PdfCartonFontCompat.TYPE_INT_ARGB);

        internal TexturePaintContext(
            SKBitmap image,
            SKRect anchor,
            SKMatrix userToDevice,
            PdfCartonRenderingHints? hints)
        {
            if (anchor.Width == 0 || anchor.Height == 0)
                throw new ArgumentException(
                    "Texture paint anchor dimensions cannot be zero.",
                    nameof(anchor));
            this.image = image;
            // As Java: bilinear unless the interpolation hint is nearest
            // neighbour or, without one, the rendering hint is not quality.
            object? interpolation = null;
            hints?.TryGetValue(PdfCartonRenderingHints.KEY_INTERPOLATION, out interpolation);
            var filter = interpolation is null
                ? ReferenceEquals(
                    hints?.GetValueOrDefault(PdfCartonRenderingHints.KEY_RENDERING),
                    PdfCartonRenderingHints.VALUE_RENDER_QUALITY)
                : !ReferenceEquals(
                    interpolation,
                    PdfCartonRenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            sampling = new SKSamplingOptions(filter ? SKFilterMode.Linear : SKFilterMode.Nearest);
            // Java maps the device pixel's corner to image position u and
            // takes texel floor(u), or blends floor(u) and the next texel by
            // frac(u). Skia maps pixel centres and samples texel centres, so
            // shift by half a device pixel and, when filtering, half a texel.
            var matrix = SKMatrix.Concat(
                SKMatrix.CreateTranslation(0.5f, 0.5f),
                SKMatrix.Concat(
                    userToDevice,
                    SKMatrix.CreateScaleTranslation(
                        anchor.Width / image.Width,
                        anchor.Height / image.Height,
                        anchor.Left,
                        anchor.Top)));
            deviceFromImage = filter
                ? SKMatrix.Concat(matrix, SKMatrix.CreateTranslation(-0.5f, -0.5f))
                : matrix;
        }

        public JavaColorModel GetColorModel() => colorModel;

        // The repeating image shader for a canvas whose matrix is userToDevice;
        // null when that matrix cannot be inverted (nothing is drawn).
        // rasterTarget is the bitmap the shader is drawn on, as for DrawableImage.
        internal SKShader? CreateShader(SKMatrix userToDevice, SKBitmap? rasterTarget)
        {
            if (!userToDevice.TryInvert(out var deviceToUser)) return null;
            PdfCartonFontCompat.EnsureNativePixels(image);
            using var view = PdfCartonFontCompat.DrawableImage(image, rasterTarget);
            return view?.ToShader(
                SKShaderTileMode.Repeat,
                SKShaderTileMode.Repeat,
                sampling,
                SKMatrix.Concat(deviceToUser, deviceFromImage)) ?? SKShader.CreateEmpty();
        }

        public JavaRaster GetRaster(int x, int y, int width, int height)
        {
            if (width <= 0 || height <= 0)
                throw new ArgumentException("Raster dimensions must be positive.");
            var bitmap = PdfCartonFontCompat.CreateNativeBitmap(
                width,
                height,
                PdfCartonFontCompat.TYPE_INT_ARGB);
            rasters.Add(bitmap);
            using (var shader = CreateShader(SKMatrix.CreateTranslation(x, y), bitmap))
            {
                if (shader is not null)
                {
                    using var canvas = new SKCanvas(bitmap);
                    using var paint = new SKPaint { Shader = shader, BlendMode = SKBlendMode.Src };
                    canvas.DrawPaint(paint);
                }
            }
            return PdfCartonFontCompat.GetRaster(bitmap);
        }

        public void Dispose()
        {
            foreach (var raster in rasters) raster.Dispose();
            rasters.Clear();
        }
    }
}

public sealed class PdfCartonRenderingHints : Dictionary<object, object>
{
    public static readonly object KEY_INTERPOLATION = new();
    public static readonly object VALUE_INTERPOLATION_NEAREST_NEIGHBOR = new();
    public static readonly object VALUE_INTERPOLATION_BILINEAR = new();
    public static readonly object VALUE_INTERPOLATION_BICUBIC = new();
    public static readonly object KEY_RENDERING = new();
    public static readonly object VALUE_RENDER_DEFAULT = new();
    public static readonly object VALUE_RENDER_QUALITY = new();
    public static readonly object KEY_ANTIALIASING = new();
    public static readonly object VALUE_ANTIALIAS_OFF = new();
    public static readonly object VALUE_ANTIALIAS_ON = new();
    public static readonly object KEY_STROKE_CONTROL = new();
    public static readonly object VALUE_STROKE_DEFAULT = new();
    public static readonly object VALUE_STROKE_NORMALIZE = new();
    public static readonly object VALUE_STROKE_PURE = new();

    public PdfCartonRenderingHints(object? initialValues)
    {
        if (initialValues is IEnumerable<KeyValuePair<object, object>> values)
        {
            foreach (var pair in values)
                this[pair.Key] = pair.Value;
        }
    }

    public object? Put(object key, object value)
    {
        TryGetValue(key, out var previous);
        this[key] = value;
        return previous;
    }
}

public sealed class JavaIccProfile
{
    public const int CLASS_INPUT = 0;
    public const int CLASS_DISPLAY = 1;
    public const int CLASS_OUTPUT = 2;
    public const int CLASS_DEVICELINK = 3;
    public const int CLASS_COLORSPACECONVERSION = 4;
    public const int CLASS_ABSTRACT = 5;
    public const int CLASS_NAMEDCOLOR = 6;
    public const int icSigDisplayClass = 1835955314;
    public const int icPerceptual = 0;
    public const int icSigHead = 1751474532;
    public const int icHdrDeviceClass = 12;
    public const int icHdrModel = 52;
    public const int icHdrRenderingIntent = 64;

    private readonly PdfCartonIccProfileData profile;

    internal JavaIccProfile(sbyte[] data)
    {
        profile = new PdfCartonIccProfileData(data);
    }

    public sbyte[] GetData() => profile.GetData();

    public sbyte[] GetData(int tag) =>
        tag == icSigHead ? profile.GetHeader() : profile.GetTag(tag);

    public int GetProfileClass() => profile.DeviceClassSignature switch
    {
        0x73636e72 => CLASS_INPUT, // scnr
        0x6d6e7472 => CLASS_DISPLAY, // mntr
        0x70727472 => CLASS_OUTPUT, // prtr
        0x6c696e6b => CLASS_DEVICELINK, // link
        0x73706163 => CLASS_COLORSPACECONVERSION, // spac
        0x61627374 => CLASS_ABSTRACT, // abst
        0x6e6d636c => CLASS_NAMEDCOLOR, // nmcl
        _ => throw new ArgumentException("ICC profile has an unknown device class.")
    };

    public int GetColorSpaceType() => profile.ColorSpaceType;

    public int NumberOfComponents => profile.NumberOfComponents;
    public int GetMajorVersion() => profile.MajorVersion;
    public int GetMinorVersion() => profile.MinorVersion;
    internal float[] ToRgb(float[] components) => profile.ToRgb(components);
    internal float[] FromRgb(float[] rgb) => profile.FromRgb(rgb);
    internal void ToRgb(ReadOnlySpan<float> components, Span<float> rgb) =>
        profile.ToRgb(components, rgb);
    internal int FromRgb(ReadOnlySpan<float> rgb, Span<float> components) =>
        profile.FromRgb(rgb, components);
    internal PdfCartonIccProfileData Data => profile;
}

public static class PdfCartonTransparency
{
    public const int OPAQUE = 1;
    public const int BITMASK = 2;
    public const int TRANSLUCENT = 3;
}

public interface JavaPaintContext : IDisposable
{
    JavaColorModel GetColorModel();
    JavaRaster GetRaster(int x, int y, int width, int height);
}

public interface JavaStroke
{
    object CreateStrokedShape(object shape);
}

internal sealed class JavaStrokeAdapter : JavaStroke
{
    private readonly Func<object, object> createStrokedShape;

    internal JavaStrokeAdapter(Func<object, object> createStrokedShape) =>
        this.createStrokedShape = createStrokedShape ??
            throw new ArgumentNullException(nameof(createStrokedShape));

    public object CreateStrokedShape(object shape) => createStrokedShape(shape);
}

public sealed class JavaBasicStroke : JavaStroke
{
    public const int CAP_BUTT = 0;
    public const int CAP_ROUND = 1;
    public const int CAP_SQUARE = 2;
    public const int JOIN_MITER = 0;
    public const int JOIN_ROUND = 1;
    public const int JOIN_BEVEL = 2;

    public JavaBasicStroke(float width)
        : this(width, CAP_SQUARE, JOIN_MITER, 10f, null, 0f)
    {
    }

    public JavaBasicStroke(
        float width,
        int endCap,
        int lineJoin,
        float miterLimit,
        float[]? dashArray,
        float dashPhase)
    {
        if (width < 0) throw new ArgumentException("Line width cannot be negative.");
        if (endCap is < CAP_BUTT or > CAP_SQUARE)
            throw new ArgumentException("Invalid line cap.");
        if (lineJoin is < JOIN_MITER or > JOIN_BEVEL)
            throw new ArgumentException("Invalid line join.");
        if (lineJoin == JOIN_MITER && miterLimit < 1)
            throw new ArgumentException("Miter limit must be at least one.");
        if (dashPhase < 0)
            throw new ArgumentException("Dash phase cannot be negative.");
        if (dashArray is { Length: 0 })
            throw new ArgumentException("Dash array cannot be empty.");
        if (dashArray is not null &&
            (dashArray.Any(value => value < 0) || dashArray.All(value => value == 0)))
        {
            throw new ArgumentException(
                "Dash elements must be non-negative and at least one must be positive.");
        }

        Width = width;
        EndCap = endCap;
        LineJoin = lineJoin;
        MiterLimit = miterLimit;
        DashArray = dashArray is null ? null : (float[])dashArray.Clone();
        DashPhase = dashPhase;
    }

    public float Width { get; }
    public int EndCap { get; }
    public int LineJoin { get; }
    public float MiterLimit { get; }
    public float[]? DashArray { get; }
    public float DashPhase { get; }
    public object CreateStrokedShape(object shape)
    {
        using var path = PdfCartonFontCompat.BorrowPath(shape);
        using var paint = CreateSkiaPaint();
        return paint.GetFillPath(path.Path) ?? new SKPath();
    }

    internal SKPaint CreateSkiaPaint()
    {
        var paint = new SKPaint
        {
            IsStroke = true,
            StrokeWidth = Width,
            StrokeCap = EndCap switch
            {
                CAP_BUTT => SKStrokeCap.Butt,
                CAP_ROUND => SKStrokeCap.Round,
                _ => SKStrokeCap.Square
            },
            StrokeJoin = LineJoin switch
            {
                JOIN_ROUND => SKStrokeJoin.Round,
                JOIN_BEVEL => SKStrokeJoin.Bevel,
                _ => SKStrokeJoin.Miter
            },
            StrokeMiter = MiterLimit
        };
        if (DashArray is { Length: > 0 })
        {
            var intervals = DashArray.Length % 2 == 0
                ? (float[])DashArray.Clone()
                : DashArray.Concat(DashArray).ToArray();
            using var pathEffect =
                SKPathEffect.CreateDash(intervals, DashPhase);
            paint.PathEffect = pathEffect;
        }
        return paint;
    }
}

public interface JavaComposite
{
    JavaCompositeContext CreateContext(
        JavaColorModel sourceColorModel,
        JavaColorModel destinationColorModel,
        PdfCartonRenderingHints? hints);
}

public interface JavaCompositeContext : IDisposable
{
    void Compose(JavaRaster source, JavaRaster destinationIn, JavaRaster destinationOut);
}

public sealed class JavaAlphaComposite : JavaComposite
{
    public const int SRC_OVER = 3;

    private readonly float alpha;

    private JavaAlphaComposite(float alpha) => this.alpha = alpha;

    internal float Alpha => alpha;

    public static JavaAlphaComposite GetInstance(int rule, float alpha)
    {
        if (rule != SRC_OVER)
            throw new ArgumentException("Only SRC_OVER alpha compositing is supported.", nameof(rule));
        if (!JavaCompat.IsFinite(alpha) || alpha < 0 || alpha > 1)
            throw new ArgumentException("Alpha must be between zero and one.", nameof(alpha));
        return new JavaAlphaComposite(alpha);
    }

    public JavaCompositeContext CreateContext(
        JavaColorModel sourceColorModel,
        JavaColorModel destinationColorModel,
        PdfCartonRenderingHints? hints)
    {
        _ = hints;
        return new SourceOverContext(sourceColorModel, destinationColorModel, alpha);
    }

    private sealed class SourceOverContext : JavaCompositeContext
    {
        private readonly JavaColorModel sourceColorModel;
        private readonly JavaColorModel destinationColorModel;
        private readonly float constantAlpha;

        internal SourceOverContext(
            JavaColorModel sourceColorModel,
            JavaColorModel destinationColorModel,
            float constantAlpha)
        {
            this.sourceColorModel = sourceColorModel;
            this.destinationColorModel = destinationColorModel;
            this.constantAlpha = constantAlpha;
        }

        public void Compose(
            JavaRaster source,
            JavaRaster destinationIn,
            JavaRaster destinationOut)
        {
            var width = Math.Min(source.Width, Math.Min(destinationIn.Width, destinationOut.Width));
            var height = Math.Min(source.Height, Math.Min(destinationIn.Height, destinationOut.Height));
            object? sourcePixel = null;
            object? destinationPixel = null;
            float[]? sourceComponents = null;
            float[]? destinationComponents = null;

            for (var y = 0; y < height; y++)
            {
                for (var x = 0; x < width; x++)
                {
                    sourcePixel = source.GetDataElements(x, y, 1, 1, sourcePixel);
                    destinationPixel =
                        destinationIn.GetDataElements(x, y, 1, 1, destinationPixel);
                    sourceComponents = sourceColorModel.GetNormalizedComponents(
                        sourcePixel, sourceComponents, 0);
                    destinationComponents = destinationColorModel.GetNormalizedComponents(
                        destinationPixel, destinationComponents, 0);

                    var sourceAlpha = (sourceColorModel.HasAlpha
                        ? sourceComponents[sourceColorModel.NumberOfColorComponents]
                        : 1f) * constantAlpha;
                    var destinationAlpha = destinationColorModel.HasAlpha
                        ? destinationComponents[destinationColorModel.NumberOfColorComponents]
                        : 1f;
                    var resultAlpha =
                        sourceAlpha + destinationAlpha * (1f - sourceAlpha);

                    var sourceRgb = sourceColorModel.DrawingSpace.ToRgb(sourceComponents);
                    var destinationRgb =
                        destinationColorModel.DrawingSpace.ToRgb(destinationComponents);
                    var resultRgb = new float[3];
                    for (var component = 0; component < resultRgb.Length; component++)
                    {
                        resultRgb[component] = resultAlpha == 0
                            ? 0
                            : (sourceRgb[component] * sourceAlpha +
                               destinationRgb[component] * destinationAlpha *
                               (1f - sourceAlpha)) / resultAlpha;
                    }

                    var result = destinationColorModel.DrawingSpace.FromRgb(resultRgb);
                    Array.Copy(
                        result,
                        destinationComponents,
                        Math.Min(result.Length, destinationColorModel.NumberOfColorComponents));
                    if (destinationColorModel.HasAlpha)
                        destinationComponents[destinationColorModel.NumberOfColorComponents] =
                            resultAlpha;
                    destinationPixel = destinationColorModel.GetDataElements(
                        destinationComponents, 0, destinationPixel);
                    destinationOut.SetDataElements(x, y, destinationPixel);
                }
            }
        }

        public void Dispose()
        {
        }
    }
}

public class JavaColorModel
{
    private readonly int imageType;
    private readonly JavaColorSpace? explicitColorSpace;
    private readonly bool? explicitAlpha;
    private readonly int? explicitTransparency;
    private readonly int explicitDataType;
    private readonly int? explicitPixelSize;
    private readonly int[]? explicitComponentBits;
    private readonly int[]? explicitMasks;

    internal JavaColorModel(int imageType, int? transparency = null)
    {
        this.imageType = imageType;
        explicitTransparency = transparency;
        ColorSpace = PdfCartonFontCompat.GetColorSpace(
            imageType == PdfCartonFontCompat.TYPE_BYTE_GRAY
                ? JavaColorSpace.CS_GRAY
                : JavaColorSpace.CS_sRGB);
        if (imageType == PdfCartonFontCompat.TYPE_BYTE_BINARY)
            Palette = [SKColors.Black, SKColors.White];
    }

    internal JavaColorModel(
        JavaColorSpace colorSpace,
        bool hasAlpha,
        int dataType)
        : this(colorSpace, hasAlpha, dataType, null, null)
    {
    }

    internal JavaColorModel(
        JavaColorSpace colorSpace,
        bool hasAlpha,
        int dataType,
        int[]? componentBits)
        : this(colorSpace, hasAlpha, dataType, componentBits, null)
    {
    }

    internal JavaColorModel(
        JavaColorSpace colorSpace,
        bool hasAlpha,
        int dataType,
        int[]? componentBits,
        int? transparency)
    {
        imageType = PdfCartonFontCompat.TYPE_CUSTOM;
        explicitColorSpace = colorSpace ??
            throw new ArgumentNullException(nameof(colorSpace));
        explicitAlpha = hasAlpha;
        explicitTransparency = transparency;
        explicitDataType = dataType;
        if (componentBits is not null)
        {
            if (componentBits.Length !=
                    colorSpace.NumberOfComponents + (hasAlpha ? 1 : 0) ||
                componentBits.Any(bits => bits is < 1 or > 31))
            {
                throw new ArgumentException(
                    "Component bit depths must describe every color and alpha component.",
                    nameof(componentBits));
            }
            explicitComponentBits = (int[])componentBits.Clone();
        }
        ColorSpace = colorSpace;
    }

    internal JavaColorModel(
        JavaColorSpace colorSpace,
        bool hasAlpha,
        int dataType,
        int[] masks,
        bool packed)
        : this(colorSpace, hasAlpha, dataType, null, null)
    {
        ArgumentNullException.ThrowIfNull(masks);
        if (!packed || masks.Length != NumberOfComponents ||
            masks.Any(mask => mask == 0))
        {
            throw new ArgumentException(
                "Packed masks must describe every color and alpha component.",
                nameof(masks));
        }
        explicitMasks = (int[])masks.Clone();
    }

    internal JavaColorModel(
        int pixelBits,
        int mapSize,
        sbyte[] red,
        sbyte[] green,
        sbyte[] blue)
        : this(pixelBits, mapSize, red, green, blue, null)
    {
    }

    // Java's IndexColorModel: pixels of more than eight bits are stored as
    // unsigned shorts. With alpha, the model is OPAQUE when every entry is
    // opaque, BITMASK when every entry is opaque or fully transparent, and
    // TRANSLUCENT otherwise; it has alpha unless it is OPAQUE.
    internal JavaColorModel(
        int pixelBits,
        int mapSize,
        sbyte[] red,
        sbyte[] green,
        sbyte[] blue,
        sbyte[]? alpha)
    {
        if (pixelBits is <= 0 or > 16)
            throw new ArgumentOutOfRangeException(nameof(pixelBits));
        if (mapSize <= 0 ||
            red.Length < mapSize ||
            green.Length < mapSize ||
            blue.Length < mapSize ||
            alpha is not null && alpha.Length < mapSize)
        {
            throw new ArgumentException(
                "The indexed color arrays must contain every palette entry.");
        }

        imageType = PdfCartonFontCompat.TYPE_CUSTOM;
        explicitColorSpace = PdfCartonFontCompat.GetColorSpace(JavaColorSpace.CS_sRGB);
        explicitDataType = pixelBits > 8
            ? PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT
            : PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE;
        explicitPixelSize = pixelBits;
        ColorSpace = explicitColorSpace;
        Palette = new SKColor[mapSize];
        var transparency = PdfCartonTransparency.OPAQUE;
        for (var index = 0; index < mapSize; index++)
        {
            var entryAlpha = alpha is null ? byte.MaxValue : unchecked((byte)alpha[index]);
            if (entryAlpha is not byte.MaxValue)
                transparency = entryAlpha is 0 && transparency != PdfCartonTransparency.TRANSLUCENT
                    ? PdfCartonTransparency.BITMASK
                    : PdfCartonTransparency.TRANSLUCENT;
            Palette[index] = new SKColor(
                unchecked((byte)red[index]),
                unchecked((byte)green[index]),
                unchecked((byte)blue[index]),
                entryAlpha);
        }
        explicitAlpha = transparency != PdfCartonTransparency.OPAQUE;
        explicitTransparency = transparency;
    }

    internal SKColor[]? Palette { get; }
    internal int RasterBandCount =>
        Palette is not null || explicitMasks is not null
            ? 1
            : NumberOfComponents;

    public int PixelSize => imageType switch
    {
        PdfCartonFontCompat.TYPE_CUSTOM when explicitPixelSize.HasValue =>
            explicitPixelSize.Value,
        PdfCartonFontCompat.TYPE_CUSTOM when explicitComponentBits is not null =>
            explicitComponentBits.Sum(),
        PdfCartonFontCompat.TYPE_CUSTOM =>
            NumberOfComponents * (explicitDataType ==
                PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT ? 16 : 8),
        PdfCartonFontCompat.TYPE_BYTE_BINARY => 1,
        PdfCartonFontCompat.TYPE_BYTE_GRAY => 8,
        PdfCartonFontCompat.TYPE_3BYTE_BGR => 24,
        _ => 32
    };

    public bool HasAlpha =>
        explicitAlpha ??
        explicitTransparency is PdfCartonTransparency.BITMASK or
            PdfCartonTransparency.TRANSLUCENT ||
        imageType is PdfCartonFontCompat.TYPE_INT_ARGB or PdfCartonFontCompat.TYPE_4BYTE_ABGR;
    public int Transparency => explicitTransparency ??
        (HasAlpha
            ? PdfCartonTransparency.TRANSLUCENT
            : PdfCartonTransparency.OPAQUE);
    public int NumberOfComponents => NumberOfColorComponents + (HasAlpha ? 1 : 0);
    public int NumberOfColorComponents =>
        explicitColorSpace?.NumberOfComponents ??
        (imageType == PdfCartonFontCompat.TYPE_BYTE_GRAY ? 1 : 3);
    public JavaColorSpace ColorSpace { get; }
    // The space Java2D's drawing loops treat this model's pixels as in: the
    // native pixels are rendered from samples through it, and drawn pixels
    // become samples through it. ColorSpace, except that the loops store and
    // draw a TYPE_BYTE_GRAY sample as sRGB-encoded gray, though its
    // ColorSpace (getRGB, setRGB, getDataElements) is the linear CS_GRAY.
    internal JavaColorSpace DrawingSpace =>
        IsImageTypeModel && imageType == PdfCartonFontCompat.TYPE_BYTE_GRAY
            ? PdfCartonFontCompat.EncodedGrayColorSpace
            : ColorSpace;
    internal int ImageType => imageType;
    // The model GetRaster/GetColorModel derive from a bitmap's image type.
    internal bool IsImageTypeModel =>
        explicitColorSpace is null && Palette is null && explicitMasks is null;
    private bool IsIntImageType =>
        imageType is PdfCartonFontCompat.TYPE_INT_RGB or
            PdfCartonFontCompat.TYPE_INT_ARGB or
            PdfCartonFontCompat.TYPE_INT_BGR;

    private double[]? componentMaxima;

    // Writes the normalized components of raster row y into components
    // (Width * NumberOfComponents values), producing exactly what
    // GetNormalizedComponents returns for each pixel. The scratch arguments
    // are reused across rows so a full-image pass allocates nothing per pixel.
    internal void GetNormalizedRow(
        JavaRaster raster,
        int y,
        float[] components,
        ref int[]? samples,
        ref object? pixel)
    {
        var width = raster.Width;
        var count = NumberOfComponents;
        if (components.Length < checked(width * count))
            throw new IndexOutOfRangeException();
        if (ReadsComponentSamples(raster))
        {
            var length = width * count;
            if (samples is null || samples.Length < length) samples = new int[length];
            raster.GetPixels(0, y, width, 1, samples);
            var maxima = componentMaxima ??= ComponentMaxima();
            var clampSamples = raster.TransferType == PdfCartonFontCompat.DATA_BUFFER_TYPE_INT;
            for (var index = 0; index < length; index++)
            {
                var value = samples[index] / maxima[index % count];
                components[index] = clampSamples
                    ? (float)JavaCompat.Clamp(value, 0d, 1d)
                    : (float)value;
            }
            return;
        }
        if (ReadsForeignPackedSamples(raster))
        {
            // Another INT layout's packed raster: its bands are still red,
            // green, blue (and alpha), which Java's setData copies as samples.
            var bands = raster.NumberOfBands;
            if (samples is null || samples.Length < width * bands) samples = new int[width * bands];
            raster.ReadRowSamples(y, samples);
            for (var x = 0; x < width; x++)
                for (var component = 0; component < count; component++)
                    components[x * count + component] = samples[x * bands + component] / 255f;
            return;
        }
        for (var x = 0; x < width; x++)
        {
            pixel = raster.GetDataElements(x, y, 1, 1, pixel);
            GetNormalizedComponents(pixel, components, x * count);
        }
    }

    // INT image types decode a data element as one packed int, so their
    // component (non-packed) rasters are read as samples.
    private bool ReadsComponentSamples(JavaRaster raster) =>
        Palette is null && explicitMasks is null &&
        imageType != PdfCartonFontCompat.TYPE_BYTE_BINARY &&
        raster.HasArrayStorage &&
        raster.PackedPixelBits == 0 &&
        !raster.IsSinglePixelPacked &&
        raster.NumberOfBands == NumberOfComponents;

    // Another INT layout's packed raster: its bands are still red, green,
    // blue (and alpha), which Java's setData copies as samples.
    private bool ReadsForeignPackedSamples(JavaRaster raster) =>
        IsIntImageType && raster.IsSinglePixelPacked &&
        raster.NumberOfBands >= NumberOfComponents &&
        !raster.HasBitMasks(PackedMasks(imageType).AsSpan(0, NumberOfComponents));

    // The color RenderRaster stores for the raster's pixel (x, y): the
    // components GetNormalizedRow reads for it, converted as RenderedColor
    // converts them. The scratch arguments are reused across calls, so a
    // caller that keeps them allocates nothing per pixel.
    internal SKColor RenderedColorAt(
        JavaRaster raster,
        int x,
        int y,
        ref int[]? samples,
        ref object? pixel)
    {
        var count = NumberOfComponents;
        Span<float> components = stackalloc float[MaximumPixelComponents];
        var pixelComponents = components.Slice(0, count);
        if (ReadsComponentSamples(raster))
        {
            if (samples is null || samples.Length < count) samples = new int[MaximumPixelComponents];
            raster.GetPixel(x, y, samples);
            var maxima = componentMaxima ??= ComponentMaxima();
            var clampSamples = raster.TransferType == PdfCartonFontCompat.DATA_BUFFER_TYPE_INT;
            for (var index = 0; index < count; index++)
            {
                var value = samples[index] / maxima[index];
                pixelComponents[index] = clampSamples
                    ? (float)JavaCompat.Clamp(value, 0d, 1d)
                    : (float)value;
            }
        }
        else if (ReadsForeignPackedSamples(raster))
        {
            var bands = raster.NumberOfBands;
            if (samples is null || samples.Length < bands) samples = new int[Math.Max(bands, MaximumPixelComponents)];
            raster.GetPixel(x, y, samples);
            for (var component = 0; component < count; component++)
                pixelComponents[component] = samples[component] / 255f;
        }
        else
        {
            pixel = raster.GetDataElements(x, y, pixel);
            WriteNormalizedComponents(pixel, pixelComponents);
        }
        Span<float> rgb = stackalloc float[3];
        DrawingSpace.ConvertToRgb(pixelComponents, rgb);
        return new SKColor(
            PdfCartonFontCompat.ComponentByte(rgb[0]),
            PdfCartonFontCompat.ComponentByte(rgb[1]),
            PdfCartonFontCompat.ComponentByte(rgb[2]),
            HasAlpha
                ? PdfCartonFontCompat.ComponentByte(pixelComponents[NumberOfColorComponents])
                : byte.MaxValue);
    }

    // Java's ColorModel.getRGB(Object): getAlpha, getRed, getGreen and
    // getBlue packed as 0xAARRGGBB, from one conversion of the pixel.
    internal int GetRgb(object pixel)
    {
        Span<float> components = stackalloc float[MaximumPixelComponents];
        var count = NumberOfComponents;
        var pixelComponents = components.Slice(0, count);
        WriteNormalizedComponents(pixel, pixelComponents);
        Span<float> rgb = stackalloc float[3];
        ColorSpace.ConvertToRgb(pixelComponents, rgb);
        static uint Channel(float value) =>
            (uint)(int)JavaCompat.Round(JavaCompat.Clamp(value, 0f, 1f) * 255f);
        var alpha = HasAlpha ? Channel(pixelComponents[NumberOfColorComponents]) : 255u;
        return unchecked((int)(alpha << 24 | Channel(rgb[0]) << 16 | Channel(rgb[1]) << 8 |
                               Channel(rgb[2])));
    }

    // Java's ColorModel.getDataElements(int rgb, Object pixel): the color
    // converted from sRGB into this model's space, its alpha appended when
    // the model has one. The scratch arguments are reused across calls.
    internal object GetDataElements(int argb, ref float[]? components, object? pixel) =>
        GetDataElements(argb, ColorSpace, ref components, pixel);

    // The data elements Java2D's drawing loops store for a drawn colour.
    internal object GetDrawnDataElements(int argb, ref float[]? components, object? pixel) =>
        GetDataElements(argb, DrawingSpace, ref components, pixel);

    private object GetDataElements(
        int argb,
        JavaColorSpace colorSpace,
        ref float[]? components,
        object? pixel)
    {
        Span<float> rgb = stackalloc float[3];
        rgb[0] = unchecked((byte)(argb >> 16)) / 255f;
        rgb[1] = unchecked((byte)(argb >> 8)) / 255f;
        rgb[2] = unchecked((byte)argb) / 255f;
        if (components is null || components.Length < MaximumPixelComponents)
            components = new float[MaximumPixelComponents];
        var colorCount = NumberOfColorComponents;
        Span<float> converted = stackalloc float[MaximumPixelComponents];
        if (colorSpace.ConvertFromRgb(rgb, converted) < colorCount)
            throw new ArgumentException("The color space returned fewer components than the color model has.");
        converted.Slice(0, colorCount).CopyTo(components);
        if (HasAlpha)
            components[colorCount] = unchecked((byte)(argb >> 24)) / 255f;
        return GetDataElements(components, 0, pixel);
    }

    private double[] ComponentMaxima()
    {
        var maxima = new double[NumberOfComponents];
        for (var component = 0; component < maxima.Length; component++)
        {
            maxima[component] = explicitComponentBits is null
                ? explicitDataType == PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT
                    ? 65535d
                    : 255d
                : Math.Pow(2d, explicitComponentBits[component]) - 1d;
        }
        return maxima;
    }

    public JavaRaster CreateCompatibleWritableRaster(int width, int height)
    {
        if (imageType == PdfCartonFontCompat.TYPE_BYTE_BINARY)
            return JavaRaster.Packed(width, height, 1);
        if (Palette is not null)
        {
            if (explicitPixelSize is 1 or 2 or 4)
                return JavaRaster.Packed(width, height, explicitPixelSize.Value);
            return new JavaRaster(
                explicitPixelSize > 8
                    ? PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT
                    : PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE,
                width,
                height,
                1);
        }
        if (explicitColorSpace is null && explicitMasks is null &&
            imageType is PdfCartonFontCompat.TYPE_INT_RGB or
                PdfCartonFontCompat.TYPE_INT_ARGB or
                PdfCartonFontCompat.TYPE_INT_BGR)
        {
            // Java's DirectColorModel packs a pixel into one int, in the
            // layout GetDataElements and GetNormalizedComponents use.
            return JavaRaster.SinglePixelPacked(
                width,
                height,
                PackedMasks(imageType).AsSpan(0, NumberOfComponents).ToArray());
        }
        var dataType = explicitColorSpace is not null
            ? explicitDataType
            : PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE;
        return new JavaRaster(dataType, width, height, NumberOfComponents);
    }

    public float[] GetNormalizedComponents(
        object pixel,
        float[]? components,
        int offset)
    {
        ArgumentNullException.ThrowIfNull(pixel);
        if (offset < 0) throw new ArgumentOutOfRangeException(nameof(offset));
        components ??= new float[offset + NumberOfComponents];
        if (components.Length < offset + NumberOfComponents)
            throw new IndexOutOfRangeException();
        WriteNormalizedComponents(pixel, components.AsSpan(offset, NumberOfComponents));
        return components;
    }

    // Color spaces have at most fifteen components, plus alpha.
    private const int MaximumPixelComponents = JavaColorSpace.MaximumComponents + 1;

    // Writes GetNormalizedComponents' values for pixel into components, which
    // holds exactly NumberOfComponents values.
    private void WriteNormalizedComponents(object pixel, Span<float> components)
    {
        ArgumentNullException.ThrowIfNull(pixel);
        const int offset = 0;
        if (Palette is not null)
        {
            var paletteIndex = pixel switch
            {
                sbyte[] bytes when bytes.Length > 0 => unchecked((byte)bytes[0]),
                short[] words when words.Length > 0 => unchecked((ushort)words[0]),
                int[] values when values.Length > 0 => values[0],
                _ => throw new ArgumentException(
                    "Indexed pixel storage does not contain an index.",
                    nameof(pixel))
            };
            if ((uint)paletteIndex >= (uint)Palette.Length)
                throw new ArgumentException(
                    $"Palette index {paletteIndex} is outside the color map.",
                    nameof(pixel));
            var color = Palette[paletteIndex];
            components[offset] = color.Red / 255f;
            components[offset + 1] = color.Green / 255f;
            components[offset + 2] = color.Blue / 255f;
            if (HasAlpha)
                components[offset + NumberOfColorComponents] = color.Alpha / 255f;
            return;
        }


        if (explicitMasks is not null &&
            pixel is int[] masked && masked.Length > 0)
        {
            var packedValue = unchecked((uint)masked[0]);
            for (var component = 0; component < explicitMasks.Length; component++)
            {
                var mask = unchecked((uint)explicitMasks[component]);
                var shift = JavaCompat.TrailingZeroCount(mask);
                var maximum = mask >> shift;
                components[offset + component] =
                    ((packedValue & mask) >> shift) / (float)maximum;
            }
            return;
        }

        // Like Java's DirectColorModel, the packed pixel is element zero of
        // any int[] (callers reuse larger buffers).
        if (pixel is int[] packed && packed.Length > 0 && IsIntImageType)
        {
            var value = packed[0];
            if (imageType == PdfCartonFontCompat.TYPE_INT_BGR)
            {
                components[offset] = (value & 0xff) / 255f;
                components[offset + 1] = ((value >> 8) & 0xff) / 255f;
                components[offset + 2] = ((value >> 16) & 0xff) / 255f;
            }
            else
            {
                components[offset] = ((value >> 16) & 0xff) / 255f;
                components[offset + 1] = ((value >> 8) & 0xff) / 255f;
                components[offset + 2] = (value & 0xff) / 255f;
            }
            if (HasAlpha)
                components[offset + NumberOfColorComponents] =
                    ((value >> 24) & 0xff) / 255f;
            return;
        }

        for (var component = 0; component < NumberOfComponents; component++)
        {
            var maximum = imageType == PdfCartonFontCompat.TYPE_BYTE_BINARY
                ? 1d
                : explicitComponentBits is null
                ? explicitDataType == PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT
                    ? 65535d
                    : 255d
                : Math.Pow(2d, explicitComponentBits[component]) - 1d;
            components[offset + component] = pixel switch
            {
                sbyte[] bytes when component < bytes.Length =>
                    (float)(unchecked((byte)bytes[component]) / maximum),
                short[] words when component < words.Length =>
                    (float)(unchecked((ushort)words[component]) / maximum),
                int[] values when component < values.Length =>
                    (float)JavaCompat.Clamp(values[component] / maximum, 0d, 1d),
                _ => throw new ArgumentException(
                    "Pixel storage does not match the color model.",
                    nameof(pixel))
            };
        }
    }

    public object GetDataElements(float[] components, int offset, object? pixel)
    {
        ArgumentNullException.ThrowIfNull(components);
        if (offset < 0 || components.Length < offset + NumberOfComponents)
            throw new IndexOutOfRangeException();
        static int ByteValue(float value) =>
            (int)JavaCompat.Round(JavaCompat.Clamp(value, 0f, 1f) * 255f);

        if (Palette is not null)
        {
            var red = ByteValue(components[offset]);
            var green = ByteValue(components[offset + 1]);
            var blue = ByteValue(components[offset + 2]);
            var closestIndex = 0;
            var closestDistance = long.MaxValue;
            for (var index = 0; index < Palette.Length; index++)
            {
                var candidate = Palette[index];
                var redDistance = red - candidate.Red;
                var greenDistance = green - candidate.Green;
                var blueDistance = blue - candidate.Blue;
                var distance =
                    (long)redDistance * redDistance +
                    (long)greenDistance * greenDistance +
                    (long)blueDistance * blueDistance;
                if (distance >= closestDistance) continue;
                closestDistance = distance;
                closestIndex = index;
            }
            if (explicitPixelSize > 8)
            {
                var wide = pixel as short[] ?? new short[1];
                if (wide.Length == 0) throw new IndexOutOfRangeException();
                wide[0] = unchecked((short)closestIndex);
                return wide;
            }
            var indexed = pixel as sbyte[] ?? new sbyte[1];
            if (indexed.Length == 0) throw new IndexOutOfRangeException();
            indexed[0] = unchecked((sbyte)closestIndex);
            return indexed;
        }


        if (explicitMasks is not null)
        {
            uint packedValue = 0;
            for (var component = 0; component < explicitMasks.Length; component++)
            {
                var mask = unchecked((uint)explicitMasks[component]);
                var shift = JavaCompat.TrailingZeroCount(mask);
                var maximum = mask >> shift;
                var value = (uint)JavaCompat.Round(
                    JavaCompat.Clamp(components[offset + component], 0f, 1f) * maximum);
                packedValue |= value << shift & mask;
            }
            var masked = pixel as int[] ?? new int[1];
            if (masked.Length == 0) throw new IndexOutOfRangeException();
            masked[0] = unchecked((int)packedValue);
            return masked;
        }

        if (imageType is PdfCartonFontCompat.TYPE_INT_RGB or
            PdfCartonFontCompat.TYPE_INT_ARGB or
            PdfCartonFontCompat.TYPE_INT_BGR)
        {
            var red = ByteValue(components[offset]);
            var green = ByteValue(components[offset + 1]);
            var blue = ByteValue(components[offset + 2]);
            var alpha = HasAlpha
                ? ByteValue(components[offset + NumberOfColorComponents])
                : 0;
            var packed = pixel as int[] ?? new int[1];
            if (packed.Length == 0) throw new IndexOutOfRangeException();
            packed[0] = imageType == PdfCartonFontCompat.TYPE_INT_BGR
                ? red | green << 8 | blue << 16
                : unchecked((int)((uint)alpha << 24 |
                                  (uint)red << 16 |
                                  (uint)green << 8 |
                                  (uint)blue));
            return packed;
        }

        if (explicitDataType == PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT)
        {
            var words = pixel as short[] ?? new short[NumberOfComponents];
            if (words.Length < NumberOfComponents) throw new IndexOutOfRangeException();
            for (var component = 0; component < NumberOfComponents; component++)
            {
                var maximum = explicitComponentBits is null
                    ? 65535d
                    : Math.Pow(2d, explicitComponentBits[component]) - 1d;
                words[component] = unchecked((short)(ushort)JavaCompat.Round(
                    (float)(JavaCompat.Clamp(
                        components[offset + component],
                        0f,
                        1f) * maximum)));
            }
            return words;
        }

        if (explicitDataType == PdfCartonFontCompat.DATA_BUFFER_TYPE_INT)
        {
            var values = pixel as int[] ?? new int[NumberOfComponents];
            if (values.Length < NumberOfComponents) throw new IndexOutOfRangeException();
            for (var component = 0; component < NumberOfComponents; component++)
            {
                var maximum = explicitComponentBits is null
                    ? 255d
                    : Math.Pow(2d, explicitComponentBits[component]) - 1d;
                values[component] = checked((int)Math.Round(
                    JavaCompat.Clamp(
                        components[offset + component],
                        0f,
                        1f) * maximum));
            }
            return values;
        }

        var bytes = pixel as sbyte[] ?? new sbyte[NumberOfComponents];
        if (bytes.Length < NumberOfComponents) throw new IndexOutOfRangeException();
        for (var component = 0; component < NumberOfComponents; component++)
        {
            var maximum = imageType == PdfCartonFontCompat.TYPE_BYTE_BINARY
                ? 1f
                : explicitComponentBits is null
                ? 255f
                : (float)(Math.Pow(2d, explicitComponentBits[component]) - 1d);
            bytes[component] = unchecked((sbyte)(int)JavaCompat.Round(
                JavaCompat.Clamp(components[offset + component], 0f, 1f) * maximum));
        }
        return bytes;
    }

    public int GetRed(object pixel) => GetRgbComponent(pixel, 0);

    public int GetGreen(object pixel) => GetRgbComponent(pixel, 1);

    public int GetBlue(object pixel) => GetRgbComponent(pixel, 2);

    // The getters convert through stack buffers, so per-pixel callers such as
    // SoftMask allocate nothing (a public array override of the color space
    // still sees, and allocates, the arrays it is declared with).
    public int GetAlpha(object pixel)
    {
        Span<float> components = stackalloc float[MaximumPixelComponents];
        var count = NumberOfComponents;
        WriteNormalizedComponents(pixel, components.Slice(0, count));
        return HasAlpha
            ? (int)JavaCompat.Round(
                JavaCompat.Clamp(components[NumberOfColorComponents], 0f, 1f) * 255f)
            : 255;
    }

    private int GetRgbComponent(object pixel, int component)
    {
        Span<float> components = stackalloc float[MaximumPixelComponents];
        var count = NumberOfComponents;
        WriteNormalizedComponents(pixel, components.Slice(0, count));
        Span<float> rgb = stackalloc float[3];
        ColorSpace.ConvertToRgb(components.Slice(0, count), rgb);
        return (int)JavaCompat.Round(JavaCompat.Clamp(rgb[component], 0f, 1f) * 255f);
    }

    // The color RenderRaster stores for a data element: one ToRgb of its
    // normalized components, each channel rounded to a byte.
    internal SKColor RenderedColor(object pixel)
    {
        Span<float> components = stackalloc float[MaximumPixelComponents];
        var count = NumberOfComponents;
        var pixelComponents = components.Slice(0, count);
        WriteNormalizedComponents(pixel, pixelComponents);
        Span<float> rgb = stackalloc float[3];
        DrawingSpace.ConvertToRgb(pixelComponents, rgb);
        return new SKColor(
            PdfCartonFontCompat.ComponentByte(rgb[0]),
            PdfCartonFontCompat.ComponentByte(rgb[1]),
            PdfCartonFontCompat.ComponentByte(rgb[2]),
            HasAlpha
                ? PdfCartonFontCompat.ComponentByte(pixelComponents[NumberOfColorComponents])
                : byte.MaxValue);
    }

    // A converter from raster sample rows to rendered colors, or null when the
    // rendered color of a pixel is not a function of a single sample (index or
    // gray) or of each band separately (sRGB/gray components, packed ints).
    // Its tables hold RenderedColor results, so output is unchanged.
    internal RasterRowConverter? CreateRowConverter(JavaRaster raster)
    {
        if (!raster.HasSampleStorage || explicitMasks is not null ||
            DrawingSpace.OverridesArrayConversions)
            return null;
        var count = NumberOfComponents;
        var bands = raster.NumberOfBands;
        if (raster.IsSinglePixelPacked)
        {
            // An INT image type reads a packed raster's red, green, blue (and
            // alpha) bands: decoded from its own layout, or copied as samples
            // from another (GetNormalizedRow).
            if (explicitColorSpace is not null || Palette is not null || !IsIntImageType ||
                SeparableColorCount(DrawingSpace) != 3 || bands < count)
                return null;
            var colors = new SKColor[256];
            var packed = new int[1];
            for (var value = 0; value < colors.Length; value++)
            {
                packed[0] = value * 0x01010101;
                colors[value] = RenderedColor(packed);
            }
            return new RasterRowConverter(RasterRowConverter.RgbBands, colors, bands, HasAlpha);
        }

        var transferType = raster.TransferType;
        var domain = raster.PackedPixelBits != 0
            ? 1 << raster.PackedPixelBits
            : transferType == PdfCartonFontCompat.DATA_BUFFER_TYPE_BYTE
            ? 256
            : transferType == PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT &&
                (long)raster.Width * raster.Height >= 65536
            ? 65536
            : 0;
        if (domain == 0) return null;
        // The data element GetDataElements returns for a pixel whose bands
        // all hold sample (reused: RenderedColor does not retain it).
        var words = transferType == PdfCartonFontCompat.DATA_BUFFER_TYPE_USHORT
            ? new short[bands]
            : null;
        var bytes = new sbyte[bands];
        object Element(int sample)
        {
            if (words is not null)
            {
                words.AsSpan().Fill(unchecked((short)sample));
                return words;
            }
            bytes.AsSpan().Fill(unchecked((sbyte)sample));
            return bytes;
        }

        if (bands == 1 && (Palette is not null || count == 1))
        {
            // Indices past the palette keep the per-pixel conversion, which
            // reports them exactly as before.
            var colors = new SKColor[Palette is null ? domain : Math.Min(domain, Palette.Length)];
            for (var sample = 0; sample < colors.Length; sample++)
                colors[sample] = RenderedColor(Element(sample));
            return new RasterRowConverter(this, colors, Element);
        }

        var colorCount = SeparableColorCount(DrawingSpace);
        if (Palette is not null || bands != count ||
            colorCount == 0 || colorCount != NumberOfColorComponents)
            return null;
        // Every band carries the same sample, so each table entry holds each
        // band's own rendered channel.
        var table = new SKColor[domain];
        for (var sample = 0; sample < domain; sample++)
            table[sample] = RenderedColor(Element(sample));
        return new RasterRowConverter(
            colorCount == 1 ? RasterRowConverter.GrayBands : RasterRowConverter.RgbBands,
            table,
            bands,
            HasAlpha);
    }

    // The bit masks of an INT image type's packed pixel, alpha last.
    private static int[] PackedMasks(int imageType) =>
        imageType == PdfCartonFontCompat.TYPE_INT_BGR
            ? [0xff, 0xff00, 0xff0000, unchecked((int)0xff000000)]
            : [0xff0000, 0xff00, 0xff, unchecked((int)0xff000000)];

    // 3 or 1 when JavaColorSpace.ToRgb clamps each RGB component on its own
    // or repeats the gray one; 0 for any other conversion.
    private static int SeparableColorCount(JavaColorSpace colorSpace)
    {
        if (colorSpace is JavaIccColorSpace { IsSrgb: false }) return 0;
        return colorSpace.Type switch
        {
            JavaColorSpace.TYPE_GRAY when colorSpace.NumberOfComponents == 1 => 1,
            JavaColorSpace.TYPE_RGB when colorSpace.NumberOfComponents == 3 => 3,
            _ => 0
        };
    }
}

// Converts rows of raster samples (as JavaRaster.ReadRowSamples returns them)
// to the colors RenderRaster stores, through tables built by
// JavaColorModel.CreateRowConverter.
internal sealed class RasterRowConverter
{
    internal const int Indexed = 0;
    internal const int GrayBands = 1;
    internal const int RgbBands = 2;

    private readonly int mode;
    private readonly SKColor[] table;
    private readonly int bands;
    private readonly bool hasAlpha;
    private readonly JavaColorModel? colorModel;
    private readonly Func<int, object>? element;

    // Band tables: entry s holds each channel rendered from sample s in that
    // channel's band (gray from band 0); alpha comes from the last band.
    internal RasterRowConverter(int mode, SKColor[] table, int bands, bool hasAlpha)
    {
        this.mode = mode;
        this.table = table;
        this.bands = bands;
        this.hasAlpha = hasAlpha;
    }

    internal RasterRowConverter(JavaColorModel colorModel, SKColor[] table, Func<int, object> element)
    {
        mode = Indexed;
        this.table = table;
        bands = 1;
        this.colorModel = colorModel;
        this.element = element;
    }

    internal void Convert(int[] samples, Span<SKColor> row)
    {
        switch (mode)
        {
            case Indexed:
                for (var x = 0; x < row.Length; x++)
                {
                    var sample = samples[x];
                    row[x] = (uint)sample < (uint)table.Length
                        ? table[sample]
                        : colorModel!.RenderedColor(element!(sample));
                }
                return;
            case GrayBands:
                for (int x = 0, offset = 0; x < row.Length; x++, offset += bands)
                {
                    var gray = table[samples[offset]].Red;
                    row[x] = new SKColor(
                        gray,
                        gray,
                        gray,
                        hasAlpha ? table[samples[offset + 1]].Alpha : byte.MaxValue);
                }
                return;
            default:
                for (int x = 0, offset = 0; x < row.Length; x++, offset += bands)
                {
                    row[x] = new SKColor(
                        table[samples[offset]].Red,
                        table[samples[offset + 1]].Green,
                        table[samples[offset + 2]].Blue,
                        hasAlpha ? table[samples[offset + 3]].Alpha : byte.MaxValue);
                }
                return;
        }
    }
}

public class JavaColorSpace
{
    public const int CS_sRGB = 1000;
    public const int CS_CIEXYZ = 1001;
    public const int CS_GRAY = 1003;
    public const int TYPE_RGB = 5;
    public const int TYPE_GRAY = 6;
    public const int TYPE_CMYK = 9;

    private readonly int type;
    private readonly int numberOfComponents;

    internal JavaColorSpace(int kind)
        : this(
            kind,
            kind switch
            {
                CS_sRGB => TYPE_RGB,
                CS_GRAY => TYPE_GRAY,
                CS_CIEXYZ => 0,
                TYPE_CMYK => TYPE_CMYK,
                _ => throw new InvalidOperationException("Unknown color space.")
            },
            kind switch
            {
                CS_GRAY => 1,
                TYPE_CMYK => 4,
                _ => 3
            })
    {
    }

    protected internal JavaColorSpace(int kind, int type, int numberOfComponents)
    {
        if (numberOfComponents is < 1 or > 15)
            throw new ArgumentOutOfRangeException(nameof(numberOfComponents));
        Kind = kind;
        this.type = type;
        this.numberOfComponents = numberOfComponents;
    }

    internal int Kind { get; }
    public int Type => type;
    public int NumberOfComponents => numberOfComponents;
    public bool IsSrgb => Kind == CS_sRGB;

    // The array overloads are the Java-shaped API and delegate to the span
    // overloads, which hold each space's conversion. Per-pixel callers use the
    // span overloads, which write into caller-owned buffers and allocate nothing.
    public virtual float[] ToRgb(float[] components)
    {
        ArgumentNullException.ThrowIfNull(components);
        var rgb = new float[3];
        ToRgb(components, rgb);
        return rgb;
    }

    public virtual float[] FromRgb(float[] rgb)
    {
        ArgumentNullException.ThrowIfNull(rgb);
        Span<float> buffer = stackalloc float[MaximumComponents];
        var count = FromRgb(rgb, buffer);
        return buffer.Slice(0, count).ToArray();
    }

    // Color spaces have at most fifteen components; FromRgb span callers pass
    // an output buffer of at least this length.
    internal const int MaximumComponents = PdfCartonIccProfileData.MaximumComponents;

    private int arrayOverrides = -1;

    // A subclass outside this assembly can only override the public array
    // methods. Per-pixel callers use these entry points, which take the
    // allocation-free span path unless such an override must be honored.
    internal void ConvertToRgb(ReadOnlySpan<float> components, Span<float> rgb)
    {
        if (!OverridesArrayConversions)
        {
            ToRgb(components, rgb);
            return;
        }
        var result = ToRgb(components.ToArray());
        if (result.Length < 3)
            throw new ArgumentException("The color space returned fewer than three RGB components.");
        result.AsSpan(0, 3).CopyTo(rgb);
    }

    internal int ConvertFromRgb(ReadOnlySpan<float> rgb, Span<float> output)
    {
        if (!OverridesArrayConversions) return FromRgb(rgb, output);
        var result = FromRgb(rgb.ToArray());
        result.AsSpan().CopyTo(output);
        return result.Length;
    }

    internal bool OverridesArrayConversions
    {
        get
        {
            if (arrayOverrides < 0)
            {
                var type = GetType();
                var overrides = false;
                foreach (var name in new[] { nameof(ToRgb), nameof(FromRgb), nameof(ToCieXyz), nameof(FromCieXyz) })
                    overrides |= type.GetMethod(name, new[] { typeof(float[]) })!.DeclaringType !=
                        typeof(JavaColorSpace);
                arrayOverrides = overrides ? 1 : 0;
            }
            return arrayOverrides == 1;
        }
    }

    // CS_CIEXYZ holds D50-relative XYZ (the ICC PCS), as Java's does, with
    // components from 0 to 1 + 32767/32768.
    private const float AlmostTwo = 1f + 32767f / 32768f;

    // Java's ICC_ColorSpace hands LittleCMS each component as the 16-bit
    // sample (short)((value - min) * 65535 / (max - min) + 0.5f), read back
    // unsigned, so values outside the range wrap around rather than clamp.
    private static float Sample16(float value, float min, float max)
    {
        var scaled = (value - min) * (65535f / (max - min)) + 0.5f;
        var narrowed = float.IsNaN(scaled) ? 0
            : scaled >= int.MaxValue ? int.MaxValue
            : scaled <= int.MinValue ? int.MinValue
            : (int)scaled;
        return (narrowed & 0xffff) / 65535f * (max - min) + min;
    }

    // A LittleCMS 16-bit result read back as Java does (saturated).
    private static float Result16(float value, float min, float max) =>
        (float)Math.Floor(JavaCompat.Clamp((value - min) / (max - min), 0f, 1f) * 65535f + 0.5f) /
        65535f * (max - min) + min;

    private static float CieXyzSample(float value) => Sample16(value, 0f, AlmostTwo);

    // CS_GRAY is Java's linear gray. These are ComponentColorModel's
    // conversions for it: sRGB8ToLinearRGB16 (Math.round to 16 bits) and
    // linearRGB8TosRGB8, in Java's float arithmetic.
    private static float Linear16(float srgb)
    {
        var linear = srgb <= 0.04045f
            ? srgb / 12.92f
            : (float)Math.Pow((srgb + 0.055f) / 1.055f, 2.4);
        return (float)Math.Floor(linear * 65535f + 0.5f);
    }

    private static float SrgbFromLinear(float linear) =>
        linear <= 0.0031308f
            ? linear * 12.92f
            : 1.055f * (float)Math.Pow(linear, 1f / 2.4f) - 0.055f;

    // JavaColorModel.SeparableColorCount relies on the gray and RGB cases
    // converting each component on its own.
    internal virtual void ToRgb(ReadOnlySpan<float> components, Span<float> rgb)
    {
        if (components.Length < NumberOfComponents)
            throw new ArgumentException("The component array is too short.", nameof(components));
        if (Kind == CS_CIEXYZ)
        {
            PdfCartonIccProfileData.XyzD50ToSrgb(
                CieXyzSample(components[0]),
                CieXyzSample(components[1]),
                CieXyzSample(components[2]),
                rgb);
            return;
        }
        static float Clamp(float value) => JavaCompat.Clamp(value, 0f, 1f);
        if (Kind == CS_GRAY)
        {
            rgb[0] = rgb[1] = rgb[2] = SrgbFromLinear(Clamp(components[0]));
            return;
        }
        switch (Type)
        {
            case TYPE_GRAY:
                var gray = Clamp(components[0]);
                rgb[0] = gray;
                rgb[1] = gray;
                rgb[2] = gray;
                return;
            case TYPE_CMYK:
                var black = Clamp(components[3]);
                rgb[0] = 1f - Math.Min(1f, Clamp(components[0]) + black);
                rgb[1] = 1f - Math.Min(1f, Clamp(components[1]) + black);
                rgb[2] = 1f - Math.Min(1f, Clamp(components[2]) + black);
                return;
            default:
                rgb[0] = Clamp(components[0]);
                rgb[1] = Clamp(components.Length > 1 ? components[1] : components[0]);
                rgb[2] = Clamp(components.Length > 2 ? components[2] : components[0]);
                return;
        }
    }

    // Writes the converted components into output (at least
    // MaximumComponents long) and returns how many were written.
    internal virtual int FromRgb(ReadOnlySpan<float> rgb, Span<float> output)
    {
        if (rgb.Length < 3)
            throw new ArgumentException("RGB input must have three components.", nameof(rgb));
        if (Kind == CS_CIEXYZ)
        {
            PdfCartonIccProfileData.SrgbToXyzD50(rgb, out output[0], out output[1], out output[2]);
            return 3;
        }
        var red = JavaCompat.Clamp(rgb[0], 0f, 1f);
        var green = JavaCompat.Clamp(rgb[1], 0f, 1f);
        var blue = JavaCompat.Clamp(rgb[2], 0f, 1f);
        if (Kind == CS_GRAY)
        {
            // ComponentColorModel.getDataElements for linear gray.
            output[0] = (0.2125f * Linear16(red) + 0.7154f * Linear16(green) +
                         0.0721f * Linear16(blue)) / 65535f;
            return 1;
        }
        if (Type == TYPE_GRAY)
        {
            // Java2D's ByteGray loops: (77 R + 150 G + 29 B + 128) / 256.
            output[0] = (red * 77f + green * 150f + blue * 29f) / 256f;
            return 1;
        }
        if (Type != TYPE_CMYK)
        {
            var count = NumberOfComponents;
            output.Slice(0, count).Clear();
            if (count > 0) output[0] = red;
            if (count > 1) output[1] = green;
            if (count > 2) output[2] = blue;
            return count;
        }
        var black = 1f - Math.Max(red, Math.Max(green, blue));
        if (black >= 1f)
        {
            output[0] = 0f;
            output[1] = 0f;
            output[2] = 0f;
            output[3] = 1f;
            return 4;
        }
        var scale = 1f - black;
        output[0] = (1f - red - black) / scale;
        output[1] = (1f - green - black) / scale;
        output[2] = (1f - blue - black) / scale;
        output[3] = black;
        return 4;
    }

    // The colour's D50-relative XYZ (the ICC PCS), unquantized. Spaces with no
    // profile of their own convert through sRGB; colorimetric makes an ICC
    // profile use its relative colorimetric transform, as Java's toCIEXYZ and
    // fromCIEXYZ do, rather than its perceptual one.
    internal virtual void ToXyz(ReadOnlySpan<float> components, Span<float> xyz, bool colorimetric = false)
    {
        if (components.Length < NumberOfComponents)
            throw new ArgumentException("The component array is too short.", nameof(components));
        if (Kind == CS_CIEXYZ)
        {
            components.Slice(0, 3).CopyTo(xyz);
            return;
        }
        if (Kind == CS_GRAY)
        {
            // LittleCMS's gray-to-PCS: the linear gray times the D50 white.
            var gray = JavaCompat.Clamp(components[0], 0f, 1f);
            xyz[0] = gray * 0.9642f;
            xyz[1] = gray;
            xyz[2] = gray * 0.8249f;
            return;
        }
        Span<float> rgb = stackalloc float[3];
        ConvertToRgb(components, rgb);
        PdfCartonIccProfileData.SrgbToXyzD50(rgb, out xyz[0], out xyz[1], out xyz[2]);
    }

    // Writes the components of a D50-relative XYZ colour into output (at
    // least MaximumComponents long) and returns how many were written.
    internal virtual int FromXyz(ReadOnlySpan<float> xyz, Span<float> output, bool colorimetric = false)
    {
        if (xyz.Length < 3)
            throw new ArgumentException("XYZ input must have three components.", nameof(xyz));
        if (Kind == CS_CIEXYZ)
        {
            xyz.Slice(0, 3).CopyTo(output);
            return 3;
        }
        if (Kind == CS_GRAY)
        {
            // LittleCMS's PCS-to-gray keeps Y.
            output[0] = JavaCompat.Clamp(xyz[1], 0f, 1f);
            return 1;
        }
        Span<float> rgb = stackalloc float[3];
        PdfCartonIccProfileData.XyzD50ToSrgb(xyz[0], xyz[1], xyz[2], rgb);
        return ConvertFromRgb(rgb, output);
    }

    internal void ConvertToXyz(ReadOnlySpan<float> components, Span<float> xyz, bool colorimetric)
    {
        if (!OverridesArrayConversions)
        {
            ToXyz(components, xyz, colorimetric);
            return;
        }
        var result = ToCieXyz(components.ToArray());
        if (result.Length < 3)
            throw new ArgumentException("The color space returned fewer than three XYZ components.");
        result.AsSpan(0, 3).CopyTo(xyz);
    }

    internal int ConvertFromXyz(ReadOnlySpan<float> xyz, Span<float> output, bool colorimetric)
    {
        if (!OverridesArrayConversions) return FromXyz(xyz, output, colorimetric);
        var result = FromCieXyz(xyz.ToArray());
        result.AsSpan().CopyTo(output);
        return result.Length;
    }

    // Java's toCIEXYZ and fromCIEXYZ pass the colour and the XYZ result
    // through LittleCMS's 16-bit samples; this keeps that quantization.
    public virtual float[] ToCieXyz(float[] components)
    {
        ArgumentNullException.ThrowIfNull(components);
        if (components.Length < NumberOfComponents)
            throw new ArgumentException("The component array is too short.", nameof(components));
        Span<float> samples = stackalloc float[NumberOfComponents];
        for (var index = 0; index < samples.Length; index++)
            samples[index] = Sample16(components[index], GetMinValue(index), GetMaxValue(index));
        var xyz = new float[3];
        ToXyz(samples, xyz, colorimetric: true);
        for (var index = 0; index < 3; index++)
            xyz[index] = Result16(xyz[index], 0f, AlmostTwo);
        return xyz;
    }

    public virtual float[] FromCieXyz(float[] xyz)
    {
        ArgumentNullException.ThrowIfNull(xyz);
        if (xyz.Length < 3)
            throw new ArgumentException("XYZ input must have three components.", nameof(xyz));
        Span<float> samples = stackalloc float[3];
        for (var index = 0; index < 3; index++)
            samples[index] = CieXyzSample(xyz[index]);
        Span<float> output = stackalloc float[MaximumComponents];
        var result = new float[FromXyz(samples, output, colorimetric: true)];
        for (var index = 0; index < result.Length; index++)
            result[index] = Result16(output[index], GetMinValue(index), GetMaxValue(index));
        return result;
    }

    public virtual float GetMinValue(int component)
    {
        ValidateComponent(component);
        return 0f;
    }

    public virtual float GetMaxValue(int component)
    {
        ValidateComponent(component);
        return Kind == CS_CIEXYZ ? AlmostTwo : 1f;
    }

    private void ValidateComponent(int component)
    {
        if (component < 0 || component >= NumberOfComponents)
            throw new ArgumentOutOfRangeException(nameof(component));
    }
}

public sealed class JavaIccColorSpace : JavaColorSpace
{
    internal JavaIccColorSpace(int kind, JavaIccProfile profile)
        : base(
            kind,
            (profile ?? throw new ArgumentNullException(nameof(profile)))
                .GetColorSpaceType(),
            profile.NumberOfComponents) => Profile = profile;

    public JavaIccColorSpace(JavaIccProfile profile)
        : base(
            profile is not null &&
                PdfCartonFontCompat.IsStandardSrgbProfile(profile)
                ? CS_sRGB
                : 0,
            (profile ?? throw new ArgumentNullException(nameof(profile)))
                .GetColorSpaceType(),
            profile.NumberOfComponents)
    {
        Profile = profile;
    }

    public JavaIccProfile Profile { get; }

    internal override void ToRgb(ReadOnlySpan<float> components, Span<float> rgb)
    {
        if (IsSrgb) base.ToRgb(components, rgb);
        else Profile.ToRgb(components, rgb);
    }

    internal override int FromRgb(ReadOnlySpan<float> rgb, Span<float> output) =>
        IsSrgb ? base.FromRgb(rgb, output) : Profile.FromRgb(rgb, output);

    internal override void ToXyz(ReadOnlySpan<float> components, Span<float> xyz, bool colorimetric = false)
    {
        if (IsSrgb) base.ToXyz(components, xyz);
        else Profile.Data.ToXyzD50(components, out xyz[0], out xyz[1], out xyz[2], colorimetric);
    }

    internal override int FromXyz(ReadOnlySpan<float> xyz, Span<float> output, bool colorimetric = false) =>
        IsSrgb
            ? base.FromXyz(xyz, output)
            : Profile.Data.FromXyzD50(xyz[0], xyz[1], xyz[2], output, colorimetric);

    public override float GetMinValue(int component)
    {
        ValidateComponent(component);
        return 0f;
    }

    public override float GetMaxValue(int component)
    {
        ValidateComponent(component);
        return 1f;
    }

    private void ValidateComponent(int component)
    {
        if (component < 0 || component >= NumberOfComponents)
            throw new ArgumentOutOfRangeException(nameof(component));
    }
}

public sealed class JavaDisplayMode
{
    internal JavaDisplayMode(int bitDepth)
    {
        BitDepth = bitDepth;
    }

    private int BitDepth { get; }
    public int GetBitDepth() => BitDepth;
}

public sealed class JavaGraphicsDevice
{
    public const int TYPE_RASTER_SCREEN = 0;
    public const int TYPE_PRINTER = 1;
    public const int TYPE_IMAGE_BUFFER = 2;

    private readonly JavaDisplayMode displayMode;

    internal JavaGraphicsDevice(int bitDepth)
    {
        displayMode = new JavaDisplayMode(bitDepth);
    }

    public JavaDisplayMode GetDisplayMode() => displayMode;
    public new int GetType() => TYPE_RASTER_SCREEN;
}

public sealed class JavaGraphicsConfiguration
{
    private readonly JavaGraphicsDevice device;

    internal JavaGraphicsConfiguration(int bitDepth)
    {
        device = new JavaGraphicsDevice(bitDepth);
    }

    public JavaGraphicsDevice GetDevice() => device;
}

public interface JavaPrintable
{
    int Print(PdfCartonGraphics2D graphics, JavaPageFormat pageFormat, int pageIndex);
}

public interface JavaPageable
{
    int GetNumberOfPages();
    JavaPageFormat GetPageFormat(int pageIndex);
    JavaPrintable GetPrintable(int pageIndex);
}

public static class JavaPrintConstants
{
    public const int PAGE_EXISTS = 0;
    public const int NO_SUCH_PAGE = 1;
    public const int UNKNOWN_NUMBER_OF_PAGES = -1;
}

public class JavaPaper : ICloneable
{
    private double width = 612;
    private double height = 792;
    private double imageableX = 72;
    private double imageableY = 72;
    private double imageableWidth = 468;
    private double imageableHeight = 648;

    public double Width => width;
    public double Height => height;
    public double ImageableX => imageableX;
    public double ImageableY => imageableY;
    public double ImageableWidth => imageableWidth;
    public double ImageableHeight => imageableHeight;

    public double GetWidth() => width;
    public double GetHeight() => height;
    public double GetImageableX() => imageableX;
    public double GetImageableY() => imageableY;
    public double GetImageableWidth() => imageableWidth;
    public double GetImageableHeight() => imageableHeight;

    public void SetSize(double width, double height)
    {
        this.width = width;
        this.height = height;
    }

    public void SetImageableArea(double x, double y, double width, double height)
    {
        imageableX = x;
        imageableY = y;
        imageableWidth = width;
        imageableHeight = height;
    }

    public object Clone() => Copy();

    internal JavaPaper Copy()
    {
        var copy = new JavaPaper();
        copy.SetSize(width, height);
        copy.SetImageableArea(
            imageableX, imageableY, imageableWidth, imageableHeight);
        return copy;
    }
}

public class JavaPageFormat : ICloneable
{
    public const int LANDSCAPE = 0;
    public const int PORTRAIT = 1;
    public const int REVERSE_LANDSCAPE = 2;

    private JavaPaper paper = new();
    private int orientation = PORTRAIT;

    public void SetPaper(JavaPaper paper)
    {
        if (paper is null)
            throw new NullReferenceException();
        this.paper = paper.Copy();
    }

    public void SetOrientation(int orientation)
    {
        if (orientation is not LANDSCAPE and not PORTRAIT and not REVERSE_LANDSCAPE)
            throw new ArgumentException("Unknown page orientation.", nameof(orientation));
        this.orientation = orientation;
    }

    public JavaPaper GetPaper() => paper.Copy();
    public int GetOrientation() => orientation;

    public double GetWidth() =>
        orientation == PORTRAIT ? paper.Width : paper.Height;

    public double GetHeight() =>
        orientation == PORTRAIT ? paper.Height : paper.Width;

    public double GetImageableWidth() =>
        orientation == PORTRAIT ? paper.ImageableWidth : paper.ImageableHeight;

    public double GetImageableHeight() =>
        orientation == PORTRAIT ? paper.ImageableHeight : paper.ImageableWidth;

    public double GetImageableX() =>
        orientation switch
        {
            LANDSCAPE =>
                paper.Height - paper.ImageableY - paper.ImageableHeight,
            REVERSE_LANDSCAPE => paper.ImageableY,
            _ => paper.ImageableX
        };

    public double GetImageableY() =>
        orientation switch
        {
            LANDSCAPE => paper.ImageableX,
            REVERSE_LANDSCAPE =>
                paper.Width - paper.ImageableX - paper.ImageableWidth,
            _ => paper.ImageableY
        };

    public double[] GetMatrix() =>
        orientation switch
        {
            LANDSCAPE =>
                [0d, -1d, 1d, 0d, 0d, paper.Height],
            PORTRAIT =>
                [1d, 0d, 0d, 1d, 0d, 0d],
            REVERSE_LANDSCAPE =>
                [0d, 1d, -1d, 0d, paper.Width, 0d],
            _ => throw new ArgumentException()
        };

    public object Clone()
    {
        var copy = new JavaPageFormat();
        copy.paper = paper.Copy();
        copy.orientation = orientation;
        return copy;
    }
}

public class JavaBook : JavaPageable
{
    private sealed record BookPage(
        JavaPrintable Printable,
        JavaPageFormat Format);

    private readonly List<BookPage> pages = new();

    public virtual int GetNumberOfPages() => pages.Count;

    public virtual JavaPageFormat GetPageFormat(int pageIndex) =>
        pages[pageIndex].Format;

    public virtual JavaPrintable GetPrintable(int pageIndex) =>
        pages[pageIndex].Printable;

    public void SetPage(
        int pageIndex,
        JavaPrintable printable,
        JavaPageFormat pageFormat)
    {
        if (printable is null)
            throw new NullReferenceException("painter is null");
        if (pageFormat is null)
            throw new NullReferenceException("page is null");
        pages[pageIndex] = new BookPage(printable, pageFormat);
    }

    public void Append(JavaPrintable printable, JavaPageFormat pageFormat)
    {
        pages.Add(NewPage(printable, pageFormat));
    }

    public void Append(
        JavaPrintable printable,
        JavaPageFormat pageFormat,
        int pageCount)
    {
        var page = NewPage(printable, pageFormat);
        var originalCount = pages.Count;
        var newCount = unchecked(originalCount + pageCount);
        if (newCount < 0)
            throw new IndexOutOfRangeException();
        if (newCount < originalCount)
        {
            pages.RemoveRange(newCount, originalCount - newCount);
            return;
        }
        for (var index = originalCount; index < newCount; index++)
            pages.Add(page);
    }

    private static BookPage NewPage(
        JavaPrintable printable,
        JavaPageFormat pageFormat) =>
        printable is null || pageFormat is null
            ? throw new NullReferenceException()
            : new BookPage(printable, pageFormat);
}

public class JavaAttributedCharacterAttribute
{
    public JavaAttributedCharacterAttribute(string name)
    {
        ArgumentException.ThrowIfNullOrEmpty(name);
        Name = name;
    }

    public string Name { get; }

    public override string ToString() => $"{GetType().FullName}({Name})";
}

public sealed class JavaAttributedString
{
    private readonly string text;
    private readonly Dictionary<JavaAttributedCharacterAttribute, object> attributes = new();

    public JavaAttributedString(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        this.text = text;
    }

    public void AddAttribute(JavaAttributedCharacterAttribute attribute, object value)
    {
        ArgumentNullException.ThrowIfNull(attribute);
        ArgumentNullException.ThrowIfNull(value);
        attributes[attribute] = value;
    }

    public JavaAttributedCharacterIterator GetIterator() =>
        new(text, attributes);
}

public sealed class JavaAttributedCharacterIterator
{
    private readonly Dictionary<JavaAttributedCharacterAttribute, object> attributes;

    internal JavaAttributedCharacterIterator(
        string text,
        IReadOnlyDictionary<JavaAttributedCharacterAttribute, object> attributes)
    {
        Text = text;
        this.attributes = new Dictionary<JavaAttributedCharacterAttribute, object>();
        foreach (var entry in attributes)
            this.attributes.Add(entry.Key, entry.Value);
    }

    public string Text { get; }

    public object? GetAttribute(JavaAttributedCharacterAttribute attribute)
    {
        ArgumentNullException.ThrowIfNull(attribute);
        return attributes.TryGetValue(attribute, out var value) ? value : null;
    }
}

public sealed class JavaLineBreakIterator
{
    public const int DONE = -1;

    private readonly List<int> boundaries = new();
    private int boundaryIndex;

    public void SetText(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        boundaries.Clear();
        boundaries.Add(0);

        for (var index = 0; index < text.Length; index++)
        {
            var character = text[index];
            var nextIndex = index + 1;
            if (character == '\r' && nextIndex < text.Length && text[nextIndex] == '\n')
            {
                nextIndex++;
                index++;
            }

            if (char.IsWhiteSpace(character) ||
                character is '-' or '\u00ad' or '\u200b')
            {
                while (nextIndex < text.Length &&
                       char.IsWhiteSpace(text[nextIndex]) &&
                       text[nextIndex] is not '\r' and not '\n' and not '\u2028' and not '\u2029')
                {
                    nextIndex++;
                    index++;
                }
                AddBoundary(nextIndex);
            }
        }

        AddBoundary(text.Length);
        boundaryIndex = 0;
    }

    public int First()
    {
        boundaryIndex = 0;
        return boundaries.Count == 0 ? 0 : boundaries[0];
    }

    public int Next()
    {
        if (boundaryIndex + 1 >= boundaries.Count)
        {
            boundaryIndex = boundaries.Count;
            return DONE;
        }
        return boundaries[++boundaryIndex];
    }

    private void AddBoundary(int boundary)
    {
        if (boundaries.Count == 0 || boundaries[^1] != boundary)
        {
            boundaries.Add(boundary);
        }
    }
}

public sealed class JavaFont
{
    public JavaFont(float size = 12f) => Size = size;
    public float Size { get; }
}

public sealed class JavaFontMetrics
{
    internal JavaFontMetrics(JavaFont font) => Font = font;
    public JavaFont Font { get; }
}

public sealed class JavaFontRenderContext
{
}

public sealed class JavaGlyphVector
{
    public JavaGlyphVector(ushort[] glyphs, SKPoint[] positions, JavaFont font)
    {
        ArgumentNullException.ThrowIfNull(glyphs);
        ArgumentNullException.ThrowIfNull(positions);
        ArgumentNullException.ThrowIfNull(font);
        if (glyphs.Length != positions.Length)
            throw new ArgumentException(
                "Each glyph must have a corresponding position.",
                nameof(positions));
        Glyphs = (ushort[])glyphs.Clone();
        Positions = (SKPoint[])positions.Clone();
        Font = font;
    }

    internal ushort[] Glyphs { get; }
    internal SKPoint[] Positions { get; }
    internal JavaFont Font { get; }
}

public interface JavaBufferedImageOperation
{
}

public class PdfCartonGraphics2D : IDisposable
{
    private readonly SKBitmap? bitmap;
    private readonly SKCanvas? canvas;
    private readonly bool ownsCanvas;
    // The bitmap's retained raster is its storage (a CreateImage image, or
    // one whose byte samples getData exposed, possibly after this instance was
    // created): native drawing is reported to it (BeginDrawing) and custom
    // composites go through it.
    private bool TracksNativeDrawing =>
        bitmap is not null && PdfCartonFontCompat.TracksNativeDrawing(bitmap);
    private readonly SKRectI initialDeviceClip;
    private int restoreCount;
    private bool disposed;
    private SKSamplingOptions samplingOptions = SKSamplingOptions.Default;
    private JavaColor color = new(SKColors.Black);
    private JavaColor background = new(SKColors.Transparent);
    private JavaPaint paint;
    private JavaComposite composite = JavaAlphaComposite.GetInstance(
        JavaAlphaComposite.SRC_OVER,
        1f);
    private JavaStroke stroke = new JavaBasicStroke(1f);
    private JavaFont font = new();
    private PdfCartonRenderingHints renderingHints = new(null);
    private SKMatrix transform = SKMatrix.CreateIdentity();
    // Keep clips in device coordinates so later transforms do not move them.
    private SKPath? clipPath;
    // The clip's device rectangle while Java 2D would keep it as a rectangle
    // (PdfCartonAliasedFill.ClipRectangle), else null.
    private (double Left, double Top, double Right, double Bottom)? clipRectangle;
    // The device pixels the clip keeps; canvases clip to it without
    // antialiasing.
    private SKPath? clipRegion;

    protected PdfCartonGraphics2D()
    {
        paint = color;
        initialDeviceClip = SKRectI.Empty;
    }

    public PdfCartonGraphics2D(SKBitmap bitmap)
        : this(
            new SKCanvas(
                bitmap ?? throw new ArgumentNullException(nameof(bitmap))),
            bitmap,
            ownsCanvas: true)
    {
    }

    public PdfCartonGraphics2D(SKCanvas canvas)
        : this(
            canvas ?? throw new ArgumentNullException(nameof(canvas)),
            null,
            ownsCanvas: false)
    {
    }

    public PdfCartonGraphics2D(SKSurface surface)
        : this(
            (surface ?? throw new ArgumentNullException(nameof(surface))).Canvas)
    {
    }

    private PdfCartonGraphics2D(
        SKCanvas canvas,
        SKBitmap? bitmap,
        bool ownsCanvas)
    {
        this.bitmap = bitmap;
        this.canvas = canvas;
        this.ownsCanvas = ownsCanvas;
        initialDeviceClip = canvas.DeviceClipBounds;
        restoreCount = canvas.Save();
        transform = canvas.TotalMatrix;
        paint = color;
    }

    public virtual PdfCartonGraphics2D Create()
    {
        ThrowIfDisposed();
        var copy = bitmap is null
            ? canvas is null
                ? new PdfCartonGraphics2D()
                : new PdfCartonGraphics2D(canvas, null, ownsCanvas: false)
            : new PdfCartonGraphics2D(canvas!, bitmap, ownsCanvas: false);
        copy.samplingOptions = samplingOptions;
        copy.color = color;
        copy.background = background;
        copy.paint = paint;
        copy.composite = composite;
        copy.stroke = stroke;
        copy.font = font;
        copy.renderingHints = new PdfCartonRenderingHints(renderingHints);
        copy.transform = transform;
        copy.clipPath = clipPath is null ? null : new SKPath(clipPath);
        copy.clipRectangle = clipRectangle;
        copy.clipRegion = clipRegion is null ? null : new SKPath(clipRegion);
        return copy;
    }

    // A BufferedImage's device reports the bit depth of its image type
    // (black-box probes of Java 25: 1 for TYPE_BYTE_BINARY, 8 for
    // TYPE_BYTE_GRAY, 24 for TYPE_INT_RGB and TYPE_3BYTE_BGR, 32 for
    // TYPE_INT_ARGB), so PDFRenderer draws a BINARY page aliased.
    public virtual JavaGraphicsConfiguration GetDeviceConfiguration() =>
        new(bitmap is null ? 32 : PdfCartonFontCompat.GetImageType(bitmap) switch
        {
            PdfCartonFontCompat.TYPE_BYTE_BINARY => 1,
            PdfCartonFontCompat.TYPE_BYTE_GRAY => 8,
            PdfCartonFontCompat.TYPE_INT_RGB or PdfCartonFontCompat.TYPE_INT_BGR or
                PdfCartonFontCompat.TYPE_3BYTE_BGR => 24,
            _ => 32
        });

    public virtual void SetRenderingHint(object key, object value)
    {
        ArgumentNullException.ThrowIfNull(key);
        ArgumentNullException.ThrowIfNull(value);
        renderingHints[key] = value;
        if (ReferenceEquals(key, PdfCartonRenderingHints.KEY_INTERPOLATION))
        {
            samplingOptions =
                ReferenceEquals(value, PdfCartonRenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    ? new SKSamplingOptions(SKCubicResampler.Mitchell)
                    : ReferenceEquals(
                        value,
                        PdfCartonRenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        ? new SKSamplingOptions(SKFilterMode.Linear)
                        : SKSamplingOptions.Default;
        }
        else if (ReferenceEquals(key, PdfCartonRenderingHints.KEY_RENDERING) &&
                 ReferenceEquals(value, PdfCartonRenderingHints.VALUE_RENDER_QUALITY) &&
                 samplingOptions == SKSamplingOptions.Default)
        {
            samplingOptions = new SKSamplingOptions(SKCubicResampler.Mitchell);
        }
    }

    public virtual void AddRenderingHints(IDictionary<object, object> hints)
    {
        ArgumentNullException.ThrowIfNull(hints);
        foreach (var pair in hints)
            SetRenderingHint(pair.Key, pair.Value);
    }

    public virtual void SetRenderingHints(IDictionary<object, object> hints)
    {
        ArgumentNullException.ThrowIfNull(hints);
        renderingHints.Clear();
        samplingOptions = SKSamplingOptions.Default;
        AddRenderingHints(hints);
    }

    public virtual object? GetRenderingHint(object hintKey) =>
        renderingHints.GetValueOrDefault(hintKey);

    public virtual PdfCartonRenderingHints GetRenderingHints() =>
        new(renderingHints);

    public virtual bool DrawImage(
        SKBitmap image,
        int destinationX1,
        int destinationY1,
        int destinationX2,
        int destinationY2,
        int sourceX1,
        int sourceY1,
        int sourceX2,
        int sourceY2,
        object? observer)
    {
        ArgumentNullException.ThrowIfNull(image);
        _ = observer;
        PdfCartonFontCompat.EnsureNativePixels(image);
        RenderImageLayer(
            image,
            (layer, view, imagePaint) => layer.DrawImage(
                view,
                new SKRect(sourceX1, sourceY1, sourceX2, sourceY2),
                new SKRect(
                    destinationX1,
                    destinationY1,
                    destinationX2,
                    destinationY2),
                samplingOptions,
                imagePaint),
            new SKRect(Math.Min(destinationX1, destinationX2), Math.Min(destinationY1, destinationY2),
                Math.Max(destinationX1, destinationX2), Math.Max(destinationY1, destinationY2)));
        return true;
    }

    public virtual bool DrawImage(
        SKBitmap image,
        int x,
        int y,
        object? observer) =>
        DrawImage(image, x, y, x + image.Width, y + image.Height,
            0, 0, image.Width, image.Height, observer);

    public virtual bool DrawImage(
        SKBitmap image,
        int x,
        int y,
        JavaColor backgroundColor,
        object? observer)
    {
        FillImageBackground(x, y, image.Width, image.Height, backgroundColor);
        return DrawImage(image, x, y, observer);
    }

    public virtual bool DrawImage(
        SKBitmap image,
        int x,
        int y,
        int width,
        int height,
        object? observer) =>
        DrawImage(image, x, y, x + width, y + height,
            0, 0, image.Width, image.Height, observer);

    public virtual bool DrawImage(
        SKBitmap image,
        int x,
        int y,
        int width,
        int height,
        JavaColor backgroundColor,
        object? observer)
    {
        FillImageBackground(x, y, width, height, backgroundColor);
        return DrawImage(image, x, y, width, height, observer);
    }

    public virtual bool DrawImage(
        SKBitmap image,
        int destinationX1,
        int destinationY1,
        int destinationX2,
        int destinationY2,
        int sourceX1,
        int sourceY1,
        int sourceX2,
        int sourceY2,
        JavaColor backgroundColor,
        object? observer)
    {
        FillImageBackground(
            destinationX1,
            destinationY1,
            destinationX2 - destinationX1,
            destinationY2 - destinationY1,
            backgroundColor);
        return DrawImage(
            image,
            destinationX1,
            destinationY1,
            destinationX2,
            destinationY2,
            sourceX1,
            sourceY1,
            sourceX2,
            sourceY2,
            observer);
    }

    public virtual void DrawBufferedImage(
        SKBitmap image,
        JavaBufferedImageOperation operation,
        int x,
        int y)
    {
        _ = operation;
        DrawImage(image, x, y, null);
    }

    public virtual void DrawImage(
        SKBitmap image,
        JavaBufferedImageOperation operation,
        int x,
        int y) =>
        DrawBufferedImage(image, operation, x, y);

    public virtual bool DrawImage(SKBitmap image, SKMatrix imageTransform, object? observer)
    {
        ArgumentNullException.ThrowIfNull(image);
        _ = observer;
        PdfCartonFontCompat.EnsureNativePixels(image);
        if (PdfCartonFontCompat.IsDefaultMatrix(imageTransform))
        {
            imageTransform = SKMatrix.Identity;
        }
        RenderImageLayer(
            image,
            (layer, view, imagePaint) =>
            {
                var restore = layer.Save();
                layer.Concat(imageTransform);
                layer.DrawImage(
                    view,
                    0,
                    0,
                    samplingOptions,
                    imagePaint);
                layer.RestoreToCount(restore);
            },
            imageTransform.MapRect(new SKRect(0, 0, image.Width, image.Height)));
        return true;
    }

    public virtual void DrawRenderableImage(SKBitmap image, SKMatrix imageTransform) =>
        DrawImage(image, imageTransform, null);

    public virtual void DrawRenderedImage(SKBitmap image, SKMatrix imageTransform) =>
        DrawImage(image, imageTransform, null);

    public virtual void SetColor(SKColor color) => SetColor(new JavaColor(color));

    public virtual void SetColor(JavaColor color)
    {
        this.color = color ?? throw new ArgumentNullException(nameof(color));
        paint = color;
    }

    public virtual JavaColor GetColor() => color;
    public virtual void SetBackground(JavaColor color) =>
        background = color ?? throw new ArgumentNullException(nameof(color));
    public virtual JavaColor GetBackground() => background;
    public virtual void SetPaint(JavaPaint paint) =>
        this.paint = paint ?? throw new ArgumentNullException(nameof(paint));
    public virtual JavaPaint GetPaint() => paint;
    public virtual void SetComposite(JavaComposite composite) =>
        this.composite = composite ?? throw new ArgumentNullException(nameof(composite));
    public virtual JavaComposite GetComposite() => composite;

    public virtual void SetStroke(JavaStroke stroke)
    {
        this.stroke = stroke ?? throw new ArgumentNullException(nameof(stroke));
    }

    public virtual JavaStroke GetStroke() => stroke;
    public virtual void SetFont(JavaFont font) =>
        this.font = font ?? throw new ArgumentNullException(nameof(font));
    public virtual JavaFont GetFont() => font;
    public virtual JavaFontMetrics GetFontMetrics(JavaFont font) => new(font);
    public virtual JavaFontRenderContext GetFontRenderContext() => new();

    public virtual void ClearRect(int x, int y, int width, int height)
    {
        using var clearPaint = new SKPaint
        {
            Color = background,
            BlendMode = SKBlendMode.Src
        };
        ApplyDestinationLuma(clearPaint);
        var rectangle = new SKRect(x, y, x + width, y + height);
        using (BeginDrawing(rectangle))
            RequireCanvas().DrawRect(rectangle, clearPaint);
        QuantizeBinaryDestination(DrawingBounds(rectangle, stroked: false));
    }

    public virtual void ClipRect(int x, int y, int width, int height) =>
        IntersectClip(new SKRectI(x, y, x + width, y + height));

    public virtual void SetClip(int x, int y, int width, int height) =>
        SetClip(new SKRectI(x, y, x + width, y + height));

    public virtual void SetClip(object? clip)
    {
        ClearClip();
        if (clip is not null)
        {
            IntersectClip(clip);
            return;
        }
        ResetCanvasState();
    }

    // As in Java 2D, a rectangular clip comes back as a Rectangle2D while the
    // transform keeps it axis-aligned, and any other clip as a path.
    public virtual object? GetClip()
    {
        if (clipPath is null || !transform.TryInvert(out var inverse)) return null;
        if (clipRectangle is not null && PdfCartonAliasedFill.IsAxisAligned(inverse))
            return inverse.MapRect(clipPath.Bounds);
        var result = new SKPath(clipPath);
        result.Transform(inverse);
        return result;
    }

    public virtual SKRectI GetClipBounds()
    {
        var userClip = GetClip();
        if (userClip is SKRect rectangle)
        {
            return new SKRectI(
                (int)Math.Floor(rectangle.Left),
                (int)Math.Floor(rectangle.Top),
                (int)Math.Ceiling(rectangle.Right),
                (int)Math.Ceiling(rectangle.Bottom));
        }
        using var path = userClip as SKPath;
        return path is null ? CanvasClipBounds() : PdfCartonFontCompat.PathBounds(path);
    }

    public virtual void Clip(object shape)
    {
        ArgumentNullException.ThrowIfNull(shape);
        IntersectClip(shape);
    }

    public virtual void CopyArea(
        int x,
        int y,
        int width,
        int height,
        int destinationX,
        int destinationY)
    {
        if (bitmap is null)
            throw new InvalidOperationException("This graphics instance has no bitmap.");
        PdfCartonBitmapView.Publish(bitmap);
        var activeCanvas = RequireCanvas();
        // Under an integer translation, snapshot the source area plus the
        // pixels around it that sampling can reach. Otherwise snapshot the
        // whole bitmap: filtering at other offsets rounds by the image origin.
        var area = new SKRectI(0, 0, bitmap.Width, bitmap.Height);
        var matrix = activeCanvas.TotalMatrix;
        if (width > 0 && height > 0 && matrix is
                { ScaleX: 1, ScaleY: 1, SkewX: 0, SkewY: 0, Persp0: 0, Persp1: 0, Persp2: 1 } &&
            matrix.TransX == Math.Floor(matrix.TransX) && matrix.TransY == Math.Floor(matrix.TransY))
        {
            area = new SKRectI((int)Math.Max(0L, x - 3L), (int)Math.Max(0L, y - 3L),
                (int)Math.Min(area.Right, (long)x + width + 3), (int)Math.Min(area.Bottom, (long)y + height + 3));
            if (area.Width <= 0 || area.Height <= 0) area = new SKRectI(0, 0, 1, 1);
        }
        using var snapshot = new SKBitmap(bitmap.Info.WithSize(area.Width, area.Height));
        using (var pixels = bitmap.PeekPixels())
            if (!pixels.ReadPixels(snapshot.Info, snapshot.GetPixels(), snapshot.RowBytes, area.Left, area.Top))
                throw new InvalidOperationException("Unable to copy the graphics bitmap.");
        using var imagePaint = new SKPaint();
        var source = new SKRect(x, y, x + width, y + height);
        source.Offset(-area.Left, -area.Top);
        var destination = new SKRect(
            x + destinationX,
            y + destinationY,
            x + destinationX + width,
            y + destinationY + height);
        using (BeginDrawing(destination))
        using (var image = PdfCartonFontCompat.DrawableImage(snapshot, bitmap))
        {
            activeCanvas.DrawImage(
                image!,
                source,
                destination,
                samplingOptions,
                imagePaint);
        }
    }

    public virtual void DrawRect(int x, int y, int width, int height)
        => DrawShape(new SKRect(x, y, x + width, y + height), fill: false);

    public virtual void FillRect(int x, int y, int width, int height)
        => DrawShape(new SKRect(x, y, x + width, y + height), fill: true);

    public virtual void DrawLine(int x1, int y1, int x2, int y2)
    {
        using var path = new SKPath();
        path.MoveTo(x1, y1);
        path.LineTo(x2, y2);
        DrawShape(path, fill: false);
    }

    public virtual void DrawOval(int x, int y, int width, int height)
        => DrawShape(new JavaEllipse(x, y, width, height), fill: false);

    public virtual void FillOval(int x, int y, int width, int height)
        => DrawShape(new JavaEllipse(x, y, width, height), fill: true);

    // Java draws drawArc as an Arc2D.OPEN shape and fillArc as Arc2D.PIE.
    public virtual void DrawArc(
        int x,
        int y,
        int width,
        int height,
        int startAngle,
        int arcAngle)
    {
        using var path = ArcPath(x, y, width, height, startAngle, arcAngle, pie: false);
        DrawShape(path, fill: false);
    }

    public virtual void FillArc(
        int x,
        int y,
        int width,
        int height,
        int startAngle,
        int arcAngle)
    {
        using var path = ArcPath(x, y, width, height, startAngle, arcAngle, pie: true);
        DrawShape(path, fill: true);
    }

    // As java.awt.geom.Arc2D documents, angles are degrees counter-clockwise
    // on screen, scaled to the frame so that 45 degrees lies on the line to
    // its upper-right corner, and an extent is limited to one turn. Java
    // splits the arc into ceil(|extent| / 90) equal cubic Bezier segments with
    // the usual control distance 4/3 tan(segment / 4); a pie closes through
    // the centre. A frame with a negative size has no path.
    private static SKPath ArcPath(
        double x,
        double y,
        double width,
        double height,
        double startAngle,
        double arcAngle,
        bool pie)
    {
        var path = new SKPath();
        if (width < 0 || height < 0) return path;
        var radiusX = width / 2;
        var radiusY = height / 2;
        var centerX = x + radiusX;
        var centerY = y + radiusY;
        var extent = Math.Max(-360, Math.Min(360, arcAngle));
        var segments = (int)Math.Ceiling(Math.Abs(extent) / 90);
        var start = startAngle * Math.PI / 180;
        var step = segments == 0 ? 0 : extent * Math.PI / 180 / segments;
        var control = 4.0 / 3 * Math.Tan(step / 4);
        var cos = Math.Cos(start);
        var sin = Math.Sin(start);
        path.MoveTo((float)(centerX + radiusX * cos), (float)(centerY - radiusY * sin));
        for (var segment = 1; segment <= segments; segment++)
        {
            var angle = start + segment * step;
            var nextCos = Math.Cos(angle);
            var nextSin = Math.Sin(angle);
            path.CubicTo(
                (float)(centerX + radiusX * (cos - control * sin)),
                (float)(centerY - radiusY * (sin + control * cos)),
                (float)(centerX + radiusX * (nextCos + control * nextSin)),
                (float)(centerY - radiusY * (nextSin - control * nextCos)),
                (float)(centerX + radiusX * nextCos),
                (float)(centerY - radiusY * nextSin));
            cos = nextCos;
            sin = nextSin;
        }

        if (pie)
        {
            path.LineTo((float)centerX, (float)centerY);
            path.Close();
        }

        return path;
    }

    public virtual void DrawRoundRect(
        int x,
        int y,
        int width,
        int height,
        int arcWidth,
        int arcHeight)
    {
        using var path = JavaCurves.RoundRectangle(x, y, width, height, arcWidth, arcHeight);
        DrawShape(path, fill: false);
    }

    public virtual void FillRoundRect(
        int x,
        int y,
        int width,
        int height,
        int arcWidth,
        int arcHeight)
    {
        using var path = JavaCurves.RoundRectangle(x, y, width, height, arcWidth, arcHeight);
        DrawShape(path, fill: true);
    }

    public virtual void DrawPolygon(int[] xPoints, int[] yPoints, int pointCount) =>
        DrawCoordinates(xPoints, yPoints, pointCount, close: true, fill: false);

    public virtual void DrawPolyline(int[] xPoints, int[] yPoints, int pointCount) =>
        DrawCoordinates(xPoints, yPoints, pointCount, close: false, fill: false);

    public virtual void FillPolygon(int[] xPoints, int[] yPoints, int pointCount) =>
        DrawCoordinates(xPoints, yPoints, pointCount, close: true, fill: true);

    public virtual void Draw(object shape) => DrawShape(shape, fill: false);
    public virtual void Fill(object shape) => DrawShape(shape, fill: true);
    public virtual void DrawGlyphVector(JavaGlyphVector glyphs, float x, float y)
    {
        ArgumentNullException.ThrowIfNull(glyphs);
        using var skiaFont = new SKFont { Size = glyphs.Font.Size };
        using var path = new SKPath();
        for (var index = 0; index < glyphs.Glyphs.Length; index++)
        {
            using var glyphPath = skiaFont.GetGlyphPath(glyphs.Glyphs[index]);
            if (glyphPath is null)
                continue;
            path.AddPath(
                glyphPath,
                x + glyphs.Positions[index].X,
                y + glyphs.Positions[index].Y);
        }
        DrawShape(path, fill: true);
    }

    public virtual void DrawString(string text, int x, int y) =>
        DrawString(text, (float)x, y);

    public virtual void DrawString(string text, float x, float y)
    {
        ArgumentNullException.ThrowIfNull(text);
        using var skiaFont = new SKFont { Size = font.Size };
        using var textPath = skiaFont.GetTextPath(text, new SKPoint(x, y));
        DrawShape(textPath, fill: true);
    }

    public virtual void DrawString(
        JavaAttributedCharacterIterator iterator,
        int x,
        int y) =>
        DrawString(iterator, (float)x, y);

    public virtual void DrawString(
        JavaAttributedCharacterIterator iterator,
        float x,
        float y)
    {
        ArgumentNullException.ThrowIfNull(iterator);
        DrawString(iterator.Text, x, y);
    }

    public virtual bool Hit(SKRectI rectangle, object shape, bool onStroke)
    {
        ArgumentNullException.ThrowIfNull(shape);
        var hitShape = onStroke ? stroke.CreateStrokedShape(shape) : shape;
        using var path = PdfCartonFontCompat.CreatePath(hitShape);
        path.Transform(transform);
        var bounds = path.Bounds;
        return bounds.IntersectsWith(new SKRect(
            rectangle.Left,
            rectangle.Top,
            rectangle.Right,
            rectangle.Bottom));
    }

    public virtual void Translate(int x, int y) => Translate((double)x, y);

    public virtual void Translate(double x, double y)
    {
        PdfCartonFontCompat.TranslateInPlace(ref transform, x, y);
        canvas?.SetMatrix(transform);
    }

    public virtual void Rotate(double theta)
    {
        PdfCartonFontCompat.RotateInPlace(ref transform, theta);
        canvas?.SetMatrix(transform);
    }

    public virtual void Rotate(double theta, double x, double y)
    {
        PdfCartonFontCompat.RotateInPlace(ref transform, theta, x, y);
        canvas?.SetMatrix(transform);
    }

    public virtual void Scale(double x, double y)
    {
        PdfCartonFontCompat.ScaleInPlace(ref transform, x, y);
        canvas?.SetMatrix(transform);
    }

    public virtual void Shear(double x, double y)
    {
        PdfCartonFontCompat.ShearInPlace(ref transform, x, y);
        canvas?.SetMatrix(transform);
    }

    public virtual void Transform(SKMatrix transform)
    {
        PdfCartonFontCompat.ConcatenateInPlace(ref this.transform, transform);
        canvas?.SetMatrix(this.transform);
    }

    public virtual void SetTransform(SKMatrix transform)
    {
        this.transform = transform;
        canvas?.SetMatrix(this.transform);
    }
    public virtual SKMatrix GetTransform() => transform;
    public virtual void SetPaintMode()
    {
    }
    public virtual void SetXORMode(JavaColor color) =>
        throw new NotSupportedException("XOR paint mode is not supported by Skia.");
    public virtual void SetXorMode(JavaColor color) => SetXORMode(color);

    public virtual void Dispose()
    {
        if (disposed)
            return;
        disposed = true;
        ClearClip();
        if (canvas is null)
            return;
        canvas.RestoreToCount(restoreCount);
        if (ownsCanvas)
            canvas.Dispose();
    }

    private void FillImageBackground(
        int x,
        int y,
        int width,
        int height,
        JavaColor backgroundColor)
    {
        var previous = color;
        var previousPaint = paint;
        SetColor(backgroundColor);
        FillRect(x, y, width, height);
        color = previous;
        paint = previousPaint;
    }

    private void DrawCoordinates(
        int[] xPoints,
        int[] yPoints,
        int pointCount,
        bool close,
        bool fill)
    {
        ArgumentNullException.ThrowIfNull(xPoints);
        ArgumentNullException.ThrowIfNull(yPoints);
        if (pointCount < 0 ||
            pointCount > xPoints.Length ||
            pointCount > yPoints.Length)
            throw new ArgumentOutOfRangeException(nameof(pointCount));
        if (pointCount == 0)
            return;
        using var path = new SKPath();
        path.MoveTo(xPoints[0], yPoints[0]);
        for (var index = 1; index < pointCount; index++)
            path.LineTo(xPoints[index], yPoints[index]);
        if (close)
            path.Close();
        DrawShape(path, fill);
    }

    private void DrawShape(object shape, bool fill)
    {
        ArgumentNullException.ThrowIfNull(shape);
        if (!fill && stroke is not JavaBasicStroke)
        {
            var strokedShape = stroke.CreateStrokedShape(shape);
            DrawShape(strokedShape, fill: true);
            return;
        }

        if (fill && !IsAntialiasEnabled())
        {
            FillAliased(shape);
            return;
        }

        // A Java paint sees the bounds of the shape as given (probed), even
        // when the drawn geometry is moved or outlined below.
        SKRect? paintBounds = !fill && paint is not JavaColor ? PdfCartonFontCompat.ShapeBounds(shape) : null;
        // Java 2D moves an antialiased stroke's path first (PdfCartonStrokeNormalization).
        using var normalized = !fill && NormalizesStrokes() ? NormalizedStroke(shape) : null;
        if (normalized is not null)
            shape = normalized;
        // On a bitmap, an antialiased fill of a path, or of a BasicStroke's
        // outline, takes Java 2D's coverage (PdfCartonAntialiasedFill). An
        // axis-aligned Rectangle2D keeps Skia's exact coverage, as in Java,
        // and a zero-width stroke stays a Skia hairline.
        if (bitmap is not null && IsAntialiasEnabled() &&
            (fill ? shape is not (SKRect or SKRectI) : stroke is JavaBasicStroke { Width: > 0 }) &&
            FillAntialiased(shape, stroked: !fill, paintBounds))
            return;

        if (paint is JavaColor)
        {
            if (shape is SKRect rectangle)
            {
                RenderLayer(
                    (layer, _, drawingPaint) =>
                        layer.DrawRect(rectangle, drawingPaint),
                    stroked: !fill,
                    imagePaint: false, userBounds: rectangle);
            }
            else if (shape is SKRectI integerRectangle)
            {
                var convertedRectangle = new SKRect(
                    integerRectangle.Left,
                    integerRectangle.Top,
                    integerRectangle.Right,
                    integerRectangle.Bottom);
                RenderLayer(
                    (layer, _, drawingPaint) =>
                        layer.DrawRect(convertedRectangle, drawingPaint),
                    stroked: !fill,
                    imagePaint: false, userBounds: convertedRectangle);
            }
            else
            {
                // A shape that is already a path is drawn in place.
                using var lease = PdfCartonFontCompat.BorrowPath(shape);
                var path = lease.Path;
                RenderLayer(
                    (layer, _, drawingPaint) =>
                        layer.DrawPath(path, drawingPaint),
                    stroked: !fill,
                    imagePaint: false, userBounds: PathBounds(path));
            }
        }
        else
        {
            using var lease = PdfCartonFontCompat.BorrowPath(shape);
            RenderPaintLayer(lease.Path, stroked: !fill, paintBounds: paintBounds);
        }
    }

    // Java 2D chooses the pixels of an aliased fill by its own rules (see
    // PdfCartonAliasedFill), so the shape is mapped to device space, moved
    // onto Skia's pixel-centre sampling, and drawn without a canvas matrix.
    private void FillAliased(object shape)
    {
        var copiesOpaqueColor = paint is JavaColor { Alpha: 255 } &&
            composite is JavaAlphaComposite { Alpha: 1f };
        var thinStroke = stroke is JavaBasicStroke basicStroke &&
            PdfCartonAliasedFill.IsThin(basicStroke.Width, transform);
        using var devicePath = PdfCartonAliasedFill.DevicePath(
            shape, transform, copiesOpaqueColor, thinStroke, DrawingBounds(null, stroked: false),
            out var rectilinear);
        if (paint is JavaColor)
        {
            RenderLayer(
                (layer, _, drawingPaint) =>
                    PdfCartonAliasedFill.Draw(layer, devicePath, drawingPaint, rectilinear),
                stroked: false, imagePaint: false, PathBounds(devicePath), deviceSpace: true);
        }
        else
        {
            using var lease = PdfCartonFontCompat.BorrowPath(shape);
            RenderPaintLayer(lease.Path, stroked: false, devicePath, rectilinear);
        }
    }

    private SKPaint CreateDrawingPaint(bool stroked, SKShader? shader = null)
    {
        var basicStroke = stroke as JavaBasicStroke ?? new JavaBasicStroke(1f);
        var result = basicStroke.CreateSkiaPaint();
        result.Color = shader is not null ? SKColors.White
            : paint is JavaColor paintColor ? paintColor : color;
        result.Shader = shader;
        result.IsStroke = stroked;
        result.IsAntialias = IsAntialiasEnabled();
        // Java 2D draws an aliased BasicStroke at most one device pixel wide
        // (PdfCartonAliasedFill.IsThin) exactly as a zero-width one, dashes
        // included (black-box probes of Java 25 on random paths, transforms,
        // caps and joins); Skia would let it drop out between pixel centres.
        if (stroked && bitmap is not null && !result.IsAntialias &&
            PdfCartonAliasedFill.IsThin(basicStroke.Width, transform))
            result.StrokeWidth = 0;
        return result;
    }

    private SKPaint CreateImagePaint()
    {
        var result = new SKPaint
        {
            Color = SKColors.White,
            IsAntialias = IsAntialiasEnabled()
        };
        return result;
    }

    private void RenderImageLayer(SKBitmap image, Action<SKCanvas, SKImage, SKPaint> draw, SKRect userBounds) =>
        RenderLayer((target, targetBitmap, imagePaint) =>
        {
            using var view = PdfCartonFontCompat.DrawableImage(image, targetBitmap);
            draw(target, view!, imagePaint);
        }, stroked: false, imagePaint: true, userBounds);

    private static SKRect? PathBounds(SKPath path) =>
        path.FillType is SKPathFillType.InverseWinding or SKPathFillType.InverseEvenOdd
            ? null : path.Bounds;

    // deviceSpace: the bounds are already in device coordinates.
    private SKRectI DrawingBounds(SKRect? userBounds, bool stroked, bool deviceSpace = false)
    {
        var clip = RequireCanvas().DeviceClipBounds;
        if (bitmap is not null)
            clip = SKRectI.Intersect(clip, new SKRectI(0, 0, bitmap.Width, bitmap.Height));
        if (userBounds is null || clip.IsEmpty) return clip;
        var bounds = userBounds.Value;
        if (stroked)
        {
            using var drawingPaint = CreateDrawingPaint(true);
            // A diagonal square cap extends up to sqrt(2) times the half-width on each axis.
            var capScale = drawingPaint.StrokeCap == SKStrokeCap.Square ? (float)Math.Sqrt(2) : 1f;
            var outset = drawingPaint.StrokeWidth * Math.Max(capScale, drawingPaint.StrokeMiter) / 2f;
            bounds.Inflate(outset, outset);
        }
        if (!deviceSpace) bounds = transform.MapRect(bounds);
        bounds.Inflate(2f, 2f); // antialiasing and image sampling at the edge
        if (float.IsNaN(bounds.Left) || float.IsNaN(bounds.Top) ||
            float.IsNaN(bounds.Right) || float.IsNaN(bounds.Bottom)) return clip;
        var left = Math.Max(clip.Left, Math.Floor(bounds.Left));
        var top = Math.Max(clip.Top, Math.Floor(bounds.Top));
        var right = Math.Min(clip.Right, Math.Ceiling(bounds.Right));
        var bottom = Math.Min(clip.Bottom, Math.Ceiling(bounds.Bottom));
        return left >= right || top >= bottom ? SKRectI.Empty
            : new SKRectI((int)left, (int)top, (int)right, (int)bottom);
    }

    // draw gets the canvas, its bitmap (null if not known to be raster) and the paint.
    // With deviceSpace, draw and userBounds use device coordinates: the canvas
    // matrix is only the offset of the layer, if any.
    private void RenderLayer(
        Action<SKCanvas, SKBitmap?, SKPaint> draw,
        bool stroked,
        bool imagePaint,
        SKRect? userBounds,
        SKShader? shader = null,
        bool deviceSpace = false)
    {
        ArgumentNullException.ThrowIfNull(draw);
        ThrowIfDisposed();
        var bounds = DrawingBounds(userBounds, stroked, deviceSpace);
        if (bounds.IsEmpty) return;
        if (composite is JavaAlphaComposite)
        {
            using var directPaint = imagePaint
                ? CreateImagePaint()
                : CreateDrawingPaint(stroked, shader);
            ApplyFallbackComposite(directPaint);
            if ((imagePaint || shader is not null) && IsBinaryDestination())
            {
                DrawSampledIntoBinary(bounds, deviceSpace, draw, directPaint);
                return;
            }
            ApplyDestinationLuma(directPaint);
            using (BeginDrawing(bounds))
            {
                var target = RequireCanvas();
                if (deviceSpace) target.ResetMatrix();
                draw(target, bitmap, directPaint);
                if (deviceSpace) target.SetMatrix(transform);
            }
            QuantizeBinaryDestination(bounds);
            return;
        }
        if (bitmap is null)
        {
            throw new InvalidOperationException(
                "Custom Java composites require a bitmap-backed CPU canvas.");
        }

        using var source = PdfCartonFontCompat.CreateNativeBitmap(
            bounds.Width,
            bounds.Height,
            PdfCartonFontCompat.TYPE_INT_ARGB);
        source.Erase(SKColors.Transparent);
        using (var layer = new SKCanvas(source))
        {
            ConfigureLayerCanvas(layer, bounds, deviceSpace);
            using var sourcePaint = imagePaint
                ? CreateImagePaint()
                : CreateDrawingPaint(stroked, shader);
            draw(layer, source, sourcePaint);
        }
        CompositeLayer(source, bounds);
    }

    // devicePath, when given, is the aliased device-space shape to draw
    // instead of shape (see PdfCartonAliasedFill.Draw).
    // coverage, when given, is an antialiased device-space mask to draw
    // instead of shape (see PdfCartonAntialiasedFill.Draw).
    private void RenderPaintLayer(
        SKPath shape, bool stroked, SKPath? devicePath = null, bool rectilinear = false,
        SKRect? paintBounds = null, (SKBitmap Mask, SKRectI Bounds)? coverage = null)
    {
        var deviceSpace = devicePath is not null || coverage is not null;
        var bounds = coverage is { Bounds: var maskBounds }
            ? DrawingBounds(new SKRect(maskBounds.Left, maskBounds.Top, maskBounds.Right, maskBounds.Bottom),
                stroked, deviceSpace: true)
            : devicePath is not null
            ? DrawingBounds(PathBounds(devicePath), stroked, deviceSpace: true)
            : DrawingBounds(PathBounds(shape), stroked);
        if (bounds.IsEmpty) return;
        var deviceBounds = bounds;
        using var context = paint.CreateContext(
            bitmap is null
                ? new JavaColorModel(PdfCartonFontCompat.TYPE_INT_ARGB)
                : PdfCartonFontCompat.GetColorModel(bitmap),
            deviceBounds,
            paintBounds ?? shape.Bounds,
            transform,
            renderingHints);
        // Texture paints, TilingPaint's among them, fill with a Skia shader.
        if (context is JavaTexturePaint.TexturePaintContext texture)
        {
            using var shader = texture.CreateShader(
                deviceSpace ? SKMatrix.Identity : transform, bitmap);
            if (shader is not null && coverage is ({ } textureMask, var textureBounds))
                RenderLayer((layer, _, drawingPaint) =>
                        PdfCartonAntialiasedFill.Draw(layer, textureMask, textureBounds, drawingPaint),
                    stroked, imagePaint: false,
                    new SKRect(textureBounds.Left, textureBounds.Top, textureBounds.Right, textureBounds.Bottom),
                    shader, deviceSpace: true);
            else if (shader is not null && devicePath is not null)
                RenderLayer((layer, _, drawingPaint) =>
                        PdfCartonAliasedFill.Draw(layer, devicePath, drawingPaint, rectilinear),
                    stroked, imagePaint: false, PathBounds(devicePath), shader, deviceSpace: true);
            else if (shader is not null)
                RenderLayer((layer, _, drawingPaint) => layer.DrawPath(shape, drawingPaint),
                    stroked, imagePaint: false, PathBounds(shape), shader);
            return;
        }
        if (bitmap is null)
        {
            throw new InvalidOperationException(
                "Non-solid Java paints require a bitmap-backed CPU canvas.");
        }

        using var source = PdfCartonFontCompat.CreateNativeBitmap(
            bounds.Width,
            bounds.Height,
            PdfCartonFontCompat.TYPE_INT_ARGB);
        source.Erase(SKColors.Transparent);
        {
            var raster = context.GetRaster(
                deviceBounds.Left,
                deviceBounds.Top,
                deviceBounds.Width,
                deviceBounds.Height);
            using var paintBitmap = PdfCartonFontCompat.CreateImage(
                context.GetColorModel(),
                raster,
                isRasterPremultiplied: false,
                null);
            using var sourceCanvas = new SKCanvas(source);
            using var replacePaint = new SKPaint { BlendMode = SKBlendMode.Src };
            using var paintImage = PdfCartonFontCompat.DrawableImage(paintBitmap, source);
            sourceCanvas.DrawImage(paintImage!, 0, 0, replacePaint);
        }

        using var mask = PdfCartonFontCompat.CreateNativeBitmap(
            bounds.Width,
            bounds.Height,
            PdfCartonFontCompat.TYPE_INT_ARGB);
        mask.Erase(SKColors.Transparent);
        using (var maskCanvas = new SKCanvas(mask))
        {
            ConfigureLayerCanvas(maskCanvas, bounds, deviceSpace);
            using var maskPaint = CreateDrawingPaint(stroked);
            maskPaint.Color = SKColors.White;
            if (coverage is ({ } coverageMask, var coverageBounds))
                PdfCartonAntialiasedFill.Draw(maskCanvas, coverageMask, coverageBounds, maskPaint);
            else if (devicePath is not null)
                PdfCartonAliasedFill.Draw(maskCanvas, devicePath, maskPaint, rectilinear);
            else
                maskCanvas.DrawPath(shape, maskPaint);
        }
        using (var sourceCanvas = new SKCanvas(source))
        using (var maskPaint = new SKPaint { BlendMode = SKBlendMode.DstIn })
        using (var maskImage = PdfCartonFontCompat.DrawableImage(mask, source))
        {
            sourceCanvas.DrawImage(maskImage!, 0, 0, maskPaint);
        }
        if (composite is JavaAlphaComposite)
            DrawAlphaLayer(source, bounds);
        else
            CompositeLayer(source, bounds);
    }

    private void DrawAlphaLayer(SKBitmap source, SKRectI bounds)
    {
        var activeCanvas = RequireCanvas();
        var restore = activeCanvas.Save();
        activeCanvas.ResetMatrix();
        using var drawingPaint = CreateImagePaint();
        ApplyFallbackComposite(drawingPaint);
        if (IsBinaryDestination())
        {
            // The layer holds a Java paint's raster, which Java 2D maps like an image.
            activeCanvas.RestoreToCount(restore);
            DrawSampledIntoBinary(bounds, deviceSpace: true, (target, targetBitmap, paint) =>
            {
                using var layerImage = PdfCartonFontCompat.DrawableImage(source, targetBitmap);
                target.DrawImage(layerImage!, bounds.Left, bounds.Top, paint);
            }, drawingPaint);
            return;
        }
        ApplyDestinationLuma(drawingPaint);
        using (BeginDrawing(bounds))
        using (var image = PdfCartonFontCompat.DrawableImage(source, bitmap))
            activeCanvas.DrawImage(image!, bounds.Left, bounds.Top, drawingPaint);
        activeCanvas.RestoreToCount(restore);
    }

    private bool IsBinaryDestination() =>
        bitmap is { ColorType: SKColorType.Gray8 } &&
        PdfCartonFontCompat.GetImageType(bitmap) == PdfCartonFontCompat.TYPE_BYTE_BINARY;

    // Java 2D composites a sampled colour (an image's, a texture's or a
    // gradient's) with a TYPE_BYTE_BINARY destination in RGB, rounding, and
    // only then picks black or white by PdfCartonFontCompat.IsJavaBinarySampledBlack
    // (black-box probes of Java 25: 20,000 random translucent ARGB pixels
    // drawn over black and over white all match). So the drawing goes to an
    // RGB copy of the destination's bounds, which is then mapped back.
    private void DrawSampledIntoBinary(
        SKRectI bounds, bool deviceSpace, Action<SKCanvas, SKBitmap?, SKPaint> draw, SKPaint paint)
    {
        var destination = bitmap!;
        using var copy = PdfCartonFontCompat.CreateNativeBitmap(
            bounds.Width, bounds.Height, PdfCartonFontCompat.TYPE_INT_RGB);
        using (var copyCanvas = new SKCanvas(copy))
        {
            using (var replacePaint = new SKPaint { BlendMode = SKBlendMode.Src })
                copyCanvas.DrawBitmap(
                    destination,
                    new SKRect(bounds.Left, bounds.Top, bounds.Right, bounds.Bottom),
                    new SKRect(0, 0, bounds.Width, bounds.Height),
                    replacePaint);
            ConfigureLayerCanvas(copyCanvas, bounds, deviceSpace);
            draw(copyCanvas, copy, paint);
        }
        using var source = new PdfCartonPixels(copy);
        using var target = new PdfCartonPixels(destination);
        for (var y = bounds.Top; y < bounds.Bottom; y++)
        {
            for (var x = bounds.Left; x < bounds.Right; x++)
            {
                var value = PdfCartonFontCompat.IsJavaBinarySampledBlack(
                    source.Get(x - bounds.Left, y - bounds.Top))
                    ? byte.MinValue
                    : byte.MaxValue;
                target.Set(x, y, new SKColor(value, value, value));
            }
        }
    }

    private void CompositeLayer(SKBitmap source, SKRectI bounds)
    {
        var destination = bitmap ??
            throw new InvalidOperationException(
                "Composite rendering requires a bitmap-backed canvas.");
        var sourceColorModel = PdfCartonFontCompat.GetColorModel(source);
        var destinationColorModel = PdfCartonFontCompat.GetColorModel(destination);
        var sourceRaster = PdfCartonFontCompat.GetRaster(source);
        if (TracksNativeDrawing)
        {
            // As in Java, compose into the image's own raster, in its colour
            // model, and render only the composed pixels. The output starts
            // as the destination, which Java passes as both rasters.
            var retained = PdfCartonFontCompat.GetDetachedRaster(destination)!;
            var detachedInput = retained.CopyRegion(bounds.Left, bounds.Top, bounds.Width, bounds.Height);
            var detachedOutput = retained.CopyRegion(bounds.Left, bounds.Top, bounds.Width, bounds.Height);
            using (var context = composite.CreateContext(
                       sourceColorModel,
                       destinationColorModel,
                       renderingHints))
            {
                context.Compose(sourceRaster, detachedInput, detachedOutput);
            }
            PdfCartonFontCompat.SetDetachedRasterRegion(
                destination,
                bounds.Left,
                bounds.Top,
                detachedOutput);
            return;
        }
        var destinationRaster = PdfCartonFontCompat.GetRaster(destination)
            .CopyRegion(bounds.Left, bounds.Top, bounds.Width, bounds.Height);
        using var output = PdfCartonFontCompat.CreateNativeBitmap(
            bounds.Width,
            bounds.Height,
            PdfCartonFontCompat.GetImageType(destination));
        var outputRaster = PdfCartonFontCompat.GetRaster(output);
        using (var context = composite.CreateContext(
                   sourceColorModel,
                   destinationColorModel,
                   renderingHints))
        {
            context.Compose(sourceRaster, destinationRaster, outputRaster);
        }
        // Copy only the affected pixels, preserving straight-alpha values without
        // sending the result through another draw/premultiplication round trip.
        using var outputPixels = output.PeekPixels();
        var destinationInfo = destination.Info;
        var offset = checked(bounds.Top * destination.RowBytes + bounds.Left * destinationInfo.BytesPerPixel);
        if (!outputPixels.ReadPixels(destinationInfo.WithSize(bounds.Width, bounds.Height),
                IntPtr.Add(destination.GetPixels(), offset), destination.RowBytes))
            throw new InvalidOperationException("Unable to copy composite pixels to the destination.");
        destination.NotifyPixelsChanged();
        GC.KeepAlive(destination);
    }

    private void ConfigureLayerCanvas(SKCanvas layer, SKRectI bounds, bool deviceSpace = false)
    {
        layer.ResetMatrix();
        layer.Translate(-bounds.Left, -bounds.Top);
        layer.ClipRect(new SKRect(
            initialDeviceClip.Left,
            initialDeviceClip.Top,
            initialDeviceClip.Right,
            initialDeviceClip.Bottom));
        if (clipRegion is not null)
            layer.ClipPath(clipRegion, SKClipOperation.Intersect, antialias: false);
        if (!deviceSpace) layer.Concat(transform);
    }

    // Java 2D never antialiases a clip, so canvases clip to whole pixels.
    private void ApplyUserClip(SKCanvas target)
    {
        if (clipRegion is not null)
        {
            var matrix = target.TotalMatrix;
            target.ResetMatrix();
            target.ClipPath(clipRegion, SKClipOperation.Intersect, antialias: false);
            target.SetMatrix(matrix);
        }
    }

    private void ClearClip()
    {
        clipPath?.Dispose();
        clipPath = null;
        clipRectangle = null;
        clipRegion?.Dispose();
        clipRegion = null;
    }

    // Intersects the clip with a user-space shape, following black-box
    // probes of Java 25's Graphics2D.clip on BufferedImages:
    // - Without a clip, the shape becomes the clip as setClip would set it.
    // - A rectangular clip intersected with a Rectangle2D the transform keeps
    //   axis-aligned stays a rectangle.
    // - Such a Rectangle2D that holds the whole clip leaves it as it is.
    // - Otherwise the device shapes are intersected. A rectangular result
    //   becomes the rectangle of the device pixels it touches, [floor(lo),
    //   ceil(hi)) on each axis; any other result is rasterised once (see
    //   PdfCartonAliasedFill.ClipRegion).
    private void IntersectClip(object shape)
    {
        var rectangle = PdfCartonAliasedFill.TryDeviceRectangle(
            shape, transform, out var left, out var top, out var right, out var bottom);
        if (clipPath is null)
        {
            if (rectangle)
            {
                SetClipRectangle(left, top, right, bottom);
                return;
            }
            var devicePath = PdfCartonFontCompat.CreatePath(shape);
            devicePath.Transform(transform);
            SetClipShape(devicePath, PdfCartonAliasedFill.ClipRegion(shape, transform, RegionBounds()));
            return;
        }
        if (rectangle && clipRectangle is { } current)
        {
            SetClipRectangle(
                Math.Max(left, current.Left), Math.Max(top, current.Top),
                Math.Min(right, current.Right), Math.Min(bottom, current.Bottom));
            return;
        }
        // Java has no inverse fills, but an SKPath clip may: its bounds are
        // those of what it leaves out.
        var clipBounds = clipPath.Bounds;
        if (rectangle && !IsInverse(clipPath) && left <= clipBounds.Left && top <= clipBounds.Top &&
            right >= clipBounds.Right && bottom >= clipBounds.Bottom)
        {
            return;
        }
        using var lease = PdfCartonFontCompat.BorrowPath(shape);
        using var deviceAddition = new SKPath(lease.Path);
        deviceAddition.Transform(transform);
        var intersection = new SKPath();
        if (!clipPath.Op(deviceAddition, SKPathOp.Intersect, intersection))
        {
            intersection.Dispose();
            throw new InvalidOperationException("Unable to intersect graphics clips.");
        }
        if (!IsInverse(intersection) && intersection.IsRect)
        {
            var touched = intersection.GetRect();
            intersection.Dispose();
            SetClipRectangle(
                Math.Floor(touched.Left), Math.Floor(touched.Top),
                Math.Ceiling(touched.Right), Math.Ceiling(touched.Bottom));
            return;
        }
        SetClipShape(intersection, PdfCartonAliasedFill.ClipRegion(intersection, SKMatrix.Identity, RegionBounds()));

        static bool IsInverse(SKPath path) =>
            path.FillType is SKPathFillType.InverseWinding or SKPathFillType.InverseEvenOdd;
    }

    // Makes the device rectangle the clip; an empty one keeps nothing.
    private void SetClipRectangle(double left, double top, double right, double bottom)
    {
        if (!(left < right && top < bottom)) left = top = right = bottom = 0;
        var devicePath = new SKPath();
        devicePath.AddRect(new SKRect((float)left, (float)top, (float)right, (float)bottom));
        SetClipShape(devicePath, PdfCartonAliasedFill.ClipRectangle(left, top, right, bottom, RegionBounds()));
        clipRectangle = (left, top, right, bottom);
    }

    // Makes the device path, whose pixels are region, the clip; both are
    // the graphics' own from now on.
    private void SetClipShape(SKPath devicePath, SKPath region)
    {
        ClearClip();
        clipPath = devicePath;
        clipRegion = region;
        ResetCanvasState();
        ApplyUserClip(RequireCanvas());
    }

    // The device area a clip region has to describe.
    private SKRect RegionBounds()
    {
        var area = initialDeviceClip;
        if (bitmap is not null) area = SKRectI.Intersect(area, new SKRectI(0, 0, bitmap.Width, bitmap.Height));
        return new SKRect(area.Left, area.Top, area.Right, area.Bottom);
    }

    // Only a bitmap has device pixels to move to; a canvas given directly
    // (an SVG or PDF document canvas, say) keeps the exact geometry.
    private bool NormalizesStrokes() =>
        bitmap is not null &&
        IsAntialiasEnabled() &&
        !ReferenceEquals(
            renderingHints.GetValueOrDefault(PdfCartonRenderingHints.KEY_STROKE_CONTROL),
            PdfCartonRenderingHints.VALUE_STROKE_PURE);

    private SKPath? NormalizedStroke(object shape)
    {
        using var lease = PdfCartonFontCompat.BorrowPath(shape);
        return PdfCartonStrokeNormalization.Normalize(lease.Path, transform);
    }

    // Fills shape, or the outline of the current BasicStroke along it, with
    // Java 2D's antialiased coverage; false (nothing drawn) for a perspective
    // transform or an inverse fill.
    private bool FillAntialiased(object shape, bool stroked, SKRect? paintBounds)
    {
        if (transform.Persp0 != 0 || transform.Persp1 != 0 || transform.Persp2 != 1)
            return false;
        using var lease = PdfCartonFontCompat.BorrowPath(shape);
        if (lease.Path.FillType is SKPathFillType.InverseWinding or SKPathFillType.InverseEvenOdd)
            return false;
        using var outline = stroked ? StrokeOutline(lease.Path) : null;
        using var devicePath = new SKPath(outline ?? lease.Path);
        devicePath.Transform(transform);
        var bounds = DrawingBounds(devicePath.Bounds, stroked: false, deviceSpace: true);
        using var mask = PdfCartonAntialiasedFill.Mask(devicePath, bounds);
        if (mask is null) return true;
        var coverage = (mask, bounds);
        var deviceBounds = new SKRect(bounds.Left, bounds.Top, bounds.Right, bounds.Bottom);
        if (paint is JavaColor)
            RenderLayer(
                (layer, _, drawingPaint) => PdfCartonAntialiasedFill.Draw(layer, mask, bounds, drawingPaint),
                stroked: false, imagePaint: false, deviceBounds, deviceSpace: true);
        else
            RenderPaintLayer(outline ?? lease.Path, stroked: false, paintBounds: paintBounds, coverage: coverage);
        return true;
    }

    // The outline of the current BasicStroke along path, in user space.
    // Dashes are expanded only within the visible area, as Skia's own
    // stroking does.
    private SKPath StrokeOutline(SKPath path)
    {
        var basicStroke = (JavaBasicStroke)stroke;
        double a = transform.ScaleX, b = transform.SkewY, c = transform.SkewX, d = transform.ScaleY;
        var scale = Math.Max(Math.Sqrt(a * a + b * b), Math.Sqrt(c * c + d * d));
        // Skia's offset curves at 8 times the device resolution came closer
        // to Java's on random curved strokes (total alpha difference 10639
        // -> 6928 over 60 cases) than at the device resolution.
        var resolution = (float)Math.Max(1, scale) * 8;
        var outline = new SKPath();
        var clip = DrawingBounds(null, stroked: false);
        if (clip.IsEmpty) return outline;
        using var strokePaint = basicStroke.CreateSkiaPaint();
        bool stroked;
        if (transform.TryInvert(out var inverse))
        {
            var cull = inverse.MapRect(new SKRect(clip.Left, clip.Top, clip.Right, clip.Bottom));
            var margin = (float)(basicStroke.Width * Math.Max(basicStroke.MiterLimit, 2) + 1 / scale);
            cull.Inflate(margin, margin);
            stroked = strokePaint.GetFillPath(path, outline, cull, resolution);
        }
        else
        {
            stroked = strokePaint.GetFillPath(path, outline, resolution);
        }
        if (!stroked) outline.Reset();
        return outline;
    }

    // A BufferedImage's Java 2D graphics draws aliased unless KEY_ANTIALIASING
    // is VALUE_ANTIALIAS_ON (black-box probes of Java 25: fills and strokes
    // with no hint match VALUE_ANTIALIAS_OFF pixel for pixel). A canvas given
    // directly (an SVG or PDF document canvas, say) has no Java counterpart
    // and keeps its exact, antialiased geometry unless told otherwise.
    private bool IsAntialiasEnabled() =>
        renderingHints.GetValueOrDefault(PdfCartonRenderingHints.KEY_ANTIALIASING) is var hint &&
        (ReferenceEquals(hint, PdfCartonRenderingHints.VALUE_ANTIALIAS_ON) ||
         (bitmap is null && !ReferenceEquals(hint, PdfCartonRenderingHints.VALUE_ANTIALIAS_OFF)));

    private void ApplyFallbackComposite(SKPaint drawingPaint)
    {
        if (composite is JavaAlphaComposite alphaComposite)
        {
            drawingPaint.Color = drawingPaint.Color.WithAlpha(
                checked((byte)Math.Round(
                    drawingPaint.Color.Alpha * alphaComposite.Alpha,
                    MidpointRounding.AwayFromZero)));
            return;
        }
        if (bitmap is null)
        {
            throw new InvalidOperationException(
                "Custom Java composites require a bitmap-backed CPU canvas.");
        }
    }

    // A colour drawn into TYPE_BYTE_GRAY becomes Java2D's luma of it. Into
    // TYPE_BYTE_BINARY (stored as Gray8 and quantized at 128 after each
    // draw) a solid colour, the background included, goes by the same luma:
    // black exactly when (77 R + 150 G + 29 B + 128) >> 8 < 128 (black-box
    // probes of Java 25: 0 of 140,608 colours differ, translucent grays over
    // black and white included). Sampled colours go by DrawSampledIntoBinary.
    private void ApplyDestinationLuma(SKPaint drawingPaint)
    {
        if (bitmap is not { ColorType: SKColorType.Gray8 }) return;
        var imageType = PdfCartonFontCompat.GetImageType(bitmap);
        if (imageType is PdfCartonFontCompat.TYPE_BYTE_GRAY or PdfCartonFontCompat.TYPE_BYTE_BINARY)
            drawingPaint.ColorFilter = PdfCartonFontCompat.JavaGrayLumaFilter;
    }

    private void QuantizeBinaryDestination(SKRectI bounds)
    {
        if (bitmap is null ||
            PdfCartonFontCompat.GetImageType(bitmap) !=
                PdfCartonFontCompat.TYPE_BYTE_BINARY)
        {
            return;
        }
        using var access = new PdfCartonPixels(bitmap);
        for (var y = bounds.Top; y < bounds.Bottom; y++)
        {
            for (var x = bounds.Left; x < bounds.Right; x++)
            {
                var value = access.Get(x, y).Red < 128
                    ? byte.MinValue
                    : byte.MaxValue;
                access.Set(x, y, new SKColor(value, value, value));
            }
        }
    }

    // Brackets native drawing into device bounds of the bitmap (disposed
    // after the draw); null unless the bitmap is a CreateImage image.
    private IDisposable? BeginDrawing(SKRectI bounds) =>
        TracksNativeDrawing ? PdfCartonFontCompat.BeginNativeDrawing(bitmap!, bounds) : null;

    // The same for a user-space rectangle under the current transform.
    private IDisposable? BeginDrawing(SKRect userBounds) =>
        TracksNativeDrawing ? BeginDrawing(DrawingBounds(userBounds, stroked: false)) : null;

    private SKCanvas RequireCanvas() =>
        !disposed
            ? canvas ?? throw new InvalidOperationException(
                "This graphics instance delegates all drawing.")
            : throw new ObjectDisposedException(nameof(PdfCartonGraphics2D));

    private void ResetCanvasState()
    {
        var activeCanvas = RequireCanvas();
        activeCanvas.RestoreToCount(restoreCount);
        restoreCount = activeCanvas.Save();
        activeCanvas.SetMatrix(transform);
    }

    private SKRectI CanvasClipBounds()
    {
        var activeCanvas = RequireCanvas();
        return activeCanvas.DeviceClipBounds;
    }

    private void ThrowIfDisposed()
    {
        if (disposed)
            throw new ObjectDisposedException(nameof(PdfCartonGraphics2D));
    }

}

public class JavaPoint2D
{
    public JavaPoint2D(double x, double y)
    {
        X = x;
        Y = y;
    }

    public virtual double X { get; protected set; }
    public virtual double Y { get; protected set; }

    public virtual void SetLocation(JavaPoint2D point)
    {
        ArgumentNullException.ThrowIfNull(point);
        X = point.X;
        Y = point.Y;
    }

    public virtual void SetLocation(double x, double y)
    {
        X = x;
        Y = y;
    }

    public double Distance(JavaPoint2D point)
    {
        ArgumentNullException.ThrowIfNull(point);
        var dx = X - point.X;
        var dy = Y - point.Y;
        return Math.Sqrt(dx * dx + dy * dy);
    }
}

public class JavaPoint : JavaPoint2D, IEquatable<JavaPoint>
{
    public JavaPoint(int x, int y)
        : base(x, y)
    {
        IntX = x;
        IntY = y;
    }

    public int IntX { get; set; }
    public int IntY { get; set; }
    public override double X
    {
        get => IntX;
        protected set => IntX = RoundCoordinate(value);
    }
    public override double Y
    {
        get => IntY;
        protected set => IntY = RoundCoordinate(value);
    }

    public override void SetLocation(JavaPoint2D point)
    {
        ArgumentNullException.ThrowIfNull(point);
        SetLocation(point.X, point.Y);
    }

    public override void SetLocation(double x, double y)
    {
        IntX = RoundCoordinate(x);
        IntY = RoundCoordinate(y);
    }

    public bool Equals(JavaPoint? other) =>
        other is not null && IntX == other.IntX && IntY == other.IntY;

    public override bool Equals(object? other) =>
        other is JavaPoint point && Equals(point);

    public override int GetHashCode() => unchecked(IntX * 397 ^ IntY);

    private static int RoundCoordinate(double value) =>
        checked((int)Math.Floor(value + 0.5d));
}

// A lazy java.awt.geom.PathIterator over a Skia path, as upstream's are: it
// reads the path's segments in place, one at a time, and transforms each
// as it is read. A path lent by its owner is never copied; a path made for
// the iterator alone is disposed with it, once it is exhausted at the latest.
public sealed class JavaPathIterator : IDisposable
{
    public const int WIND_EVEN_ODD = 0;
    public const int WIND_NON_ZERO = 1;
    public const int SEG_MOVETO = 0;
    public const int SEG_LINETO = 1;
    public const int SEG_QUADTO = 2;
    public const int SEG_CUBICTO = 3;
    public const int SEG_CLOSE = 4;

    private readonly SKPath source;
    private readonly bool ownsSource;
    private readonly SKMatrix transform;
    private readonly bool transformed;
    private readonly int windingRule;
    private readonly int count;
    private readonly SKPoint[] points = new SKPoint[4];
    private SKPath.RawIterator? iterator;
    private SKPathVerb verb;
    private bool loaded;
    private int remaining;

    internal JavaPathIterator(SKPath path, object? transform, bool ownsPath = false)
    {
        ArgumentNullException.ThrowIfNull(path);
        source = path;
        ownsSource = ownsPath;
        if ((path.SegmentMasks & SKPathSegmentMask.Conic) != 0)
        {
            Dispose();
            throw new NotSupportedException(
                "A conic Skia segment cannot be represented by java.awt.geom.PathIterator.");
        }
        if (transform is SKMatrix matrix && !PdfCartonFontCompat.IsDefaultMatrix(matrix))
        {
            this.transform = matrix;
            transformed = true;
        }
        windingRule = path.FillType is SKPathFillType.EvenOdd or SKPathFillType.InverseEvenOdd
            ? WIND_EVEN_ODD
            : WIND_NON_ZERO;
        count = remaining = path.VerbCount;
        if (remaining == 0) Dispose();
    }

    public bool IsDone() => remaining <= 0;

    public void Next()
    {
        if (IsDone()) return;
        Load();
        loaded = false;
        if (--remaining == 0) Dispose();
    }

    public int CurrentSegment(double[] coordinates)
    {
        ArgumentNullException.ThrowIfNull(coordinates);
        var (first, length) = Segment(coordinates.Length);
        for (var point = 0; point < length; point++)
        {
            coordinates[point * 2] = points[first + point].X;
            coordinates[point * 2 + 1] = points[first + point].Y;
        }
        return SegmentType();
    }

    public int CurrentSegment(float[] coordinates)
    {
        ArgumentNullException.ThrowIfNull(coordinates);
        var (first, length) = Segment(coordinates.Length);
        for (var point = 0; point < length; point++)
        {
            coordinates[point * 2] = points[first + point].X;
            coordinates[point * 2 + 1] = points[first + point].Y;
        }
        return SegmentType();
    }

    public int GetWindingRule() => windingRule;

    public void Dispose()
    {
        remaining = 0;
        iterator?.Dispose();
        iterator = null;
        if (ownsSource) source.Dispose();
    }

    // Path2D.append(PathIterator, connect): the segments not yet iterated.
    internal void AppendTo(SKPath path, bool connect)
    {
        ArgumentNullException.ThrowIfNull(path);
        if (IsDone()) return;
        if (remaining == count)
        {
            // Skia ignores a path appended to itself, so that one is copied.
            using var self = ReferenceEquals(path, source) ? new SKPath(source) : null;
            var mode = connect ? SKPathAddMode.Extend : SKPathAddMode.Append;
            if (transformed) path.AddPath(self ?? source, in transform, mode);
            else path.AddPath(self ?? source, mode);
            Dispose();
            return;
        }
        // The rest continues the destination's current subpath; connect
        // turns only a leading move into a line. It is read before any is
        // appended, as the destination may be the source.
        var rest = new List<(SKPathVerb Verb, SKPoint A, SKPoint B, SKPoint C)>(remaining);
        for (; !IsDone(); Next())
        {
            Load();
            rest.Add(verb == SKPathVerb.Move
                ? (verb, points[0], default, default)
                : (verb, points[1], points[2], points[3]));
        }
        foreach (var (segment, a, b, c) in rest)
        {
            switch (segment)
            {
                case SKPathVerb.Move when connect && !path.IsEmpty:
                    if (path.LastPoint != a) path.LineTo(a);
                    break;
                case SKPathVerb.Move: path.MoveTo(a); break;
                case SKPathVerb.Line: path.LineTo(a); break;
                case SKPathVerb.Quad: path.QuadTo(a, b); break;
                case SKPathVerb.Cubic: path.CubicTo(a, b, c); break;
                default: path.Close(); break;
            }
            connect = false;
        }
    }

    private void Load()
    {
        if (loaded) return;
        iterator ??= source.CreateRawIterator();
        verb = iterator.Next(points);
        if (transformed) transform.MapPoints(points, points);
        loaded = true;
    }

    private (int First, int Length) Segment(int capacity)
    {
        if (IsDone()) throw new InvalidOperationException("Path iterator is exhausted.");
        Load();
        var segment = verb switch
        {
            SKPathVerb.Move => (0, 1),
            SKPathVerb.Line => (1, 1),
            SKPathVerb.Quad => (1, 2),
            SKPathVerb.Cubic => (1, 3),
            SKPathVerb.Close => (0, 0),
            _ => throw new NotSupportedException($"Unsupported Skia path verb `{verb}`.")
        };
        if (capacity < segment.Item2 * 2)
            throw new IndexOutOfRangeException("Path coordinate array is too small.");
        return segment;
    }

    private int SegmentType() => verb switch
    {
        SKPathVerb.Move => SEG_MOVETO,
        SKPathVerb.Line => SEG_LINETO,
        SKPathVerb.Quad => SEG_QUADTO,
        SKPathVerb.Cubic => SEG_CUBICTO,
        _ => SEG_CLOSE
    };
}

public sealed class JavaEllipse
{
    private readonly double x;
    private readonly double y;
    private readonly double width;
    private readonly double height;

    public JavaEllipse(double x, double y, double width, double height)
    {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public double Left => x;
    public double Top => y;
    public double Width => width;
    public double Height => height;
    public double Right => x + width;
    public double Bottom => y + height;
    public double CenterX => x + width / 2;
    public double CenterY => y + height / 2;
    public bool IsEmpty => width <= 0 || height <= 0;

    internal JavaPathIterator GetPathIterator(object? transform, double flatness)
    {
        if (flatness < 0) throw new ArgumentException("Flatness must be non-negative.");
        var radius = Math.Max(Math.Abs(width), Math.Abs(height)) / 2;
        var steps = 4;
        if (radius > 0 && flatness > 0)
        {
            var ratio = JavaCompat.Clamp(1 - flatness / radius, -1, 1);
            var angle = 2 * Math.Acos(ratio);
            if (angle > 0)
            {
                steps = Math.Max(4, (int)Math.Ceiling(2 * Math.PI / angle));
            }
        }

        var path = new SKPath();
        for (var step = 0; step < steps; step++)
        {
            var angle = step * 2 * Math.PI / steps;
            var pointX = x + width / 2 + width / 2 * Math.Cos(angle);
            var pointY = y + height / 2 + height / 2 * Math.Sin(angle);
            if (step == 0)
            {
                path.MoveTo((float)pointX, (float)pointY);
            }
            else
            {
                path.LineTo((float)pointX, (float)pointY);
            }
        }
        path.Close();
        return new JavaPathIterator(path, transform, ownsPath: true);
    }

    // The path Java's Ellipse2D iterates (read back from Java 2D's
    // PathIterator): four cubic quarters from the right end, downward on
    // screen first, with control points 0.5522847498307933 of a radius from
    // each end. A negative size has no path.
    internal SKPath ToPath()
    {
        var path = new SKPath();
        if (width < 0 || height < 0) return path;
        double rx = width / 2, ry = height / 2, cx = x + rx, cy = y + ry, right = x + width, bottom = y + height;
        double kx = rx * JavaCurves.Control, ky = ry * JavaCurves.Control;
        path.MoveTo((float)right, (float)cy);
        JavaCurves.CubicTo(path, right, cy + ky, cx + kx, bottom, cx, bottom);
        JavaCurves.CubicTo(path, cx - kx, bottom, x, cy + ky, x, cy);
        JavaCurves.CubicTo(path, x, cy - ky, cx - kx, y, cx, y);
        JavaCurves.CubicTo(path, cx + kx, y, right, cy - ky, right, cy);
        path.Close();
        return path;
    }
}

// Paths of Java 2D's curved shapes, as their PathIterators report them.
internal static class JavaCurves
{
    // The distance of a quarter ellipse's control points from its ends, in radii.
    internal const double Control = 0.5522847498307933;

    internal static void CubicTo(SKPath path, double x1, double y1, double x2, double y2, double x3, double y3) =>
        path.CubicTo((float)x1, (float)y1, (float)x2, (float)y2, (float)x3, (float)y3);

    // RoundRectangle2D: arcs of min(width, |arcWidth|) by min(height,
    // |arcHeight|), from the top of the left side, downward first; a negative
    // size has no path.
    internal static SKPath RoundRectangle(double x, double y, double width, double height, double arcWidth, double arcHeight)
    {
        var path = new SKPath();
        if (width < 0 || height < 0) return path;
        var rx = Math.Min(width, Math.Abs(arcWidth)) / 2;
        var ry = Math.Min(height, Math.Abs(arcHeight)) / 2;
        double right = x + width, bottom = y + height, kx = rx * Control, ky = ry * Control;
        path.MoveTo((float)x, (float)(y + ry));
        path.LineTo((float)x, (float)(bottom - ry));
        CubicTo(path, x, bottom - ry + ky, x + rx - kx, bottom, x + rx, bottom);
        path.LineTo((float)(right - rx), (float)bottom);
        CubicTo(path, right - rx + kx, bottom, right, bottom - ry + ky, right, bottom - ry);
        path.LineTo((float)right, (float)(y + ry));
        CubicTo(path, right, y + ry - ky, right - rx + kx, y, right - rx, y);
        path.LineTo((float)(x + rx), (float)y);
        CubicTo(path, x + rx - kx, y, x, y + ry - ky, x, y + ry);
        path.Close();
        return path;
    }
}

// Java 2D moves an antialiased BasicStroke's path before stroking it unless
// KEY_STROKE_CONTROL is VALUE_STROKE_PURE (PDFBox never sets it). The rule was
// inferred from black-box Java 2D probes (OpenJDK 25 drawing on BufferedImages
// with antialiasing on, read back as alpha masks): a stroke's edges, measured
// to 1/255 pixel while its line was shifted in 1/16 pixel steps, stay put for
// a whole pixel and then jump by one, the line sitting on the pixel centre.
// Rendering a candidate device path with VALUE_STROKE_PURE gave Java's
// normalised masks exactly on random polylines, quadratics, cubics, closed
// and reopened subpaths, dashes, all caps and joins, widths 0 to 2.7 and
// random rotated, sheared and scaled transforms:
// - Every device end point v moves to floor(v) + 1/2 on each axis.
// - A cubic's first control point moves as far as its start did and its
//   second as far as its end did; a quadratic's moves by the mean of the two.
// - The stroke itself (width, dashes, caps, joins) still applies in user
//   space, to the moved path.
// Antialiased fills are never moved (the same probes gave identical masks for
// both stroke-control values). Aliased drawing follows PdfCartonAliasedFill.
internal static class PdfCartonStrokeNormalization
{
    // The path moved as above, in user space again, or null when the
    // transform cannot be inverted.
    internal static SKPath? Normalize(SKPath path, SKMatrix transform)
    {
        double a = transform.ScaleX, c = transform.SkewX, e = transform.TransX;
        double b = transform.SkewY, d = transform.ScaleY, f = transform.TransY;
        var determinant = a * d - b * c;
        if (transform.Persp0 != 0 || transform.Persp1 != 0 || determinant == 0 ||
            double.IsNaN(determinant) || double.IsInfinity(determinant))
            return null;
        double ia = d / determinant, ib = -b / determinant, ic = -c / determinant, id = a / determinant;
        var result = new SKPath { FillType = path.FillType };
        var points = new SKPoint[4];
        // How far the current point and the subpath's start moved.
        (double X, double Y) moved = default, startMoved = default;
        using var iterator = path.CreateRawIterator();
        SKPathVerb verb;
        while ((verb = iterator.Next(points)) != SKPathVerb.Done)
        {
            switch (verb)
            {
                case SKPathVerb.Move:
                    result.MoveTo(End(points[0], out moved));
                    startMoved = moved;
                    break;
                case SKPathVerb.Line:
                    result.LineTo(End(points[1], out moved));
                    break;
                case SKPathVerb.Quad:
                    Quad(points[1], points[2]);
                    break;
                case SKPathVerb.Conic:
                    // Java has no conics; a Skia-made arc is followed as quadratics.
                    var quads = SKPath.ConvertConicToQuads(
                        points[0], points[1], points[2], iterator.ConicWeight(), 2);
                    for (var index = 1; index + 1 < quads.Length; index += 2)
                        Quad(quads[index], quads[index + 1]);
                    break;
                case SKPathVerb.Cubic:
                    var start = moved;
                    var end = End(points[3], out moved);
                    result.CubicTo(Shift(points[1], start), Shift(points[2], moved), end);
                    break;
                case SKPathVerb.Close:
                    result.Close();
                    moved = startMoved;
                    break;
            }
        }
        return result;

        void Quad(SKPoint control, SKPoint endPoint)
        {
            var start = moved;
            var end = End(endPoint, out moved);
            result.QuadTo(Shift(control, ((start.X + moved.X) / 2, (start.Y + moved.Y) / 2)), end);
        }

        // The end point moved to its device pixel's centre, in user space.
        SKPoint End(SKPoint point, out (double X, double Y) shift)
        {
            var (x, y) = Device(point);
            shift = (Math.Floor(x) + 0.5 - x, Math.Floor(y) + 0.5 - y);
            return User(x + shift.X, y + shift.Y);
        }

        SKPoint Shift(SKPoint point, (double X, double Y) shift)
        {
            var (x, y) = Device(point);
            return User(x + shift.X, y + shift.Y);
        }

        (double X, double Y) Device(SKPoint point) =>
            (a * point.X + c * point.Y + e, b * point.X + d * point.Y + f);

        SKPoint User(double x, double y)
        {
            x -= e;
            y -= f;
            return new SKPoint((float)(ia * x + ic * y), (float)(ib * x + id * y));
        }
    }
}

// The pixels Java 2D covers when it fills a shape without antialiasing. The
// rules were inferred from black-box Java 2D probes (OpenJDK 25 filling
// shapes on BufferedImages while edges, transforms, strokes, colours and
// composites varied) and checked against Java on random polygons:
// - A rectangle (Rectangle2D) under a transform that keeps it axis-aligned
//   covers device pixels [floor(lo + b), floor(hi + b)) on each axis, where
//   b = 1/4 when an opaque colour is drawn with an opaque SRC_OVER composite
//   and 0 otherwise.
// - Other shapes, drawn with that opaque colour and composite while the
//   current BasicStroke is at most one device pixel wide, cover the pixels
//   whose top-left corner lies inside, after device coordinates are truncated
//   to 1/1024 pixel.
// - Otherwise each device point v moves to floor(v + 1/4) + 1/4 and the shape
//   covers the pixels whose centre lies inside.
// - Curves are first cut into lines by Java's own flattening, which differs
//   between the two sampling rules (FlattenCorner, FlattenCentre).
// A sample on a left or top edge is inside and one on a right or bottom edge
// is not. Skia's aliased rasterizer takes pixel centres in (lo, hi] instead
// and snaps sloped edges to 1/64 pixel, so only shapes made of horizontal and
// vertical lines go to it directly; the scanline spans of any other shape are
// computed here and Skia fills them as integer rectangles. Samples exactly on,
// or within about 1/300 pixel of, a sloped edge can still differ from Java,
// whose edge arithmetic is not exact.
internal static class PdfCartonAliasedFill
{
    // Whether a BasicStroke of this width is at most one device pixel wide
    // under the transform's largest scale (its largest singular value). The
    // allowance covers the float rounding of the transform's entries.
    internal static bool IsThin(float width, SKMatrix transform)
    {
        double a = transform.ScaleX, b = transform.SkewX, c = transform.SkewY, d = transform.ScaleY;
        var sum = a * a + b * b + c * c + d * d;
        var determinant = a * d - b * c;
        var largest = (sum + Math.Sqrt(Math.Max(0, sum * sum - 4 * determinant * determinant))) / 2;
        return (double)width * width * largest <= 1 + 1e-7;
    }

    // Whether the transform maps axis-aligned rectangles to axis-aligned
    // rectangles: it scales and translates, possibly swapping the axes.
    internal static bool IsAxisAligned(SKMatrix transform) =>
        transform.Persp0 == 0 && transform.Persp1 == 0 &&
        ((transform.SkewX == 0 && transform.SkewY == 0) || (transform.ScaleX == 0 && transform.ScaleY == 0));

    // Java 2D clips (Graphics2D.setClip, clip and clipRect) are never
    // antialiased. Black-box probes of Java 25 (setClip or clip on
    // BufferedImages, then a whole-image fill, while shapes, transforms and
    // rendering hints varied) show two rules:
    // - A Rectangle2D that the transform keeps axis-aligned keeps the device
    //   pixels whose centre lies in [lo, hi) on each axis; one with no area
    //   keeps none. Intersected with another such rectangle it stays one.
    // - Any other shape keeps exactly the pixels its aliased translucent fill
    //   covers (the centre rule above, with points moved to floor(v + 1/4) +
    //   1/4), which matched Java on 700 random polygons and curves under
    //   random transforms, antialiasing on or off.
    // Graphics2D.clip's intersections are described at
    // PdfCartonGraphics2D.IntersectClip.

    // The device rectangle of a Rectangle2D (SKRect) or Rectangle (SKRectI)
    // that the transform keeps axis-aligned, or an empty one when it has no
    // area. False for any other shape or transform.
    internal static bool TryDeviceRectangle(
        object shape, SKMatrix transform, out double left, out double top, out double right, out double bottom)
    {
        left = top = right = bottom = 0;
        if (shape is not (SKRect or SKRectI)) return false;
        var rectangle = shape is SKRect r ? r : (SKRect)(SKRectI)shape;
        if (!(rectangle.Width > 0 && rectangle.Height > 0)) return true;
        if (!IsAxisAligned(transform)) return false;
        var (x0, y0) = Map(transform, rectangle.Left, rectangle.Top);
        var (x1, y1) = Map(transform, rectangle.Right, rectangle.Bottom);
        left = Math.Min(x0, x1);
        top = Math.Min(y0, y1);
        right = Math.Max(x0, x1);
        bottom = Math.Max(y0, y1);
        return true;
    }

    // The pixels a rectangular clip keeps (see TryDeviceRectangle), within bounds.
    internal static SKPath ClipRectangle(double left, double top, double right, double bottom, SKRect bounds)
    {
        var result = new SKPath();
        var x0 = Math.Max(bounds.Left, Math.Ceiling(left - 0.5));
        var y0 = Math.Max(bounds.Top, Math.Ceiling(top - 0.5));
        var x1 = Math.Min(bounds.Right, Math.Ceiling(right - 0.5));
        var y1 = Math.Min(bounds.Bottom, Math.Ceiling(bottom - 0.5));
        if (x0 < x1 && y0 < y1) result.AddRect(new SKRect((float)x0, (float)y0, (float)x1, (float)y1));
        return result;
    }

    // The pixels a clip to any other shape keeps, within bounds, as a path
    // whose aliased Skia coverage is exactly those pixels.
    internal static SKPath ClipRegion(object shape, SKMatrix transform, SKRect bounds)
    {
        using var devicePath = DevicePath(
            shape, transform, copiesOpaqueColor: false, thinStroke: false, bounds, out var rectilinear);
        // Rectilinear points lie at a quarter past a pixel boundary, never on
        // a pixel centre, so Skia's own sampling agrees with Java's.
        return rectilinear ? new SKPath(devicePath) : Spans(devicePath, bounds);
    }

    // The shape in device space, placed for Draw. Rectilinear device paths
    // (horizontal and vertical lines only) are drawn directly. Any other
    // shape comes back as lines, its curves flattened as Java 2D flattens
    // them (FlattenCurve); parts of a curve wholly outside cull, the device
    // clip, become chords, which cross the visible rows the same way.
    internal static SKPath DevicePath(
        object shape, SKMatrix transform, bool copiesOpaqueColor, bool thinStroke, SKRect cull,
        out bool rectilinear)
    {
        rectilinear = true;
        if (IsAxisAligned(transform) && shape is SKRect or SKRectI)
        {
            var rectangle = shape is SKRect r ? r : (SKRect)(SKRectI)shape;
            var result = new SKPath();
            if (!(rectangle.Width > 0 && rectangle.Height > 0)) return result;
            var bias = copiesOpaqueColor ? 0.25 : 0;
            var (x0, y0) = Map(transform, rectangle.Left, rectangle.Top);
            var (x1, y1) = Map(transform, rectangle.Right, rectangle.Bottom);
            result.AddRect(new SKRect(
                (float)Math.Floor(Math.Min(x0, x1) + bias), (float)Math.Floor(Math.Min(y0, y1) + bias),
                (float)Math.Floor(Math.Max(x0, x1) + bias), (float)Math.Floor(Math.Max(y0, y1) + bias)));
            return result;
        }

        // Points snap to Java's grid first: to 1/1024 pixel at corners, or
        // else to the pixel at a quarter offset, where a curve's control
        // points move with its ends (a quadratic's by their mean). Only then
        // is rectilinearity decided.
        var corner = copiesOpaqueColor && thinStroke;
        var straight = true;
        using var lease = PdfCartonFontCompat.BorrowPath(shape);
        var verbs = new List<(SKPathVerb Verb, int First)>();
        var snapped = new List<(double X, double Y)>();
        using (var iterator = lease.Path.CreateRawIterator())
        {
            var points = new SKPoint[4];
            SKPathVerb verb;
            var start = 0;
            // How far snapping moved the current point and the subpath's start.
            (double X, double Y) moved = default, startMoved = default;
            while ((verb = iterator.Next(points)) != SKPathVerb.Done)
            {
                switch (verb)
                {
                    case SKPathVerb.Move:
                        CheckImplicitClose(start);
                        start = snapped.Count;
                        verbs.Add((verb, snapped.Count));
                        moved = startMoved = AddEnd(points[0]);
                        break;
                    case SKPathVerb.Line:
                        var previous = snapped.Count > 0 ? snapped[snapped.Count - 1] : default;
                        verbs.Add((verb, snapped.Count));
                        moved = AddEnd(points[1]);
                        CheckLine(previous, snapped[snapped.Count - 1]);
                        break;
                    case SKPathVerb.Quad:
                        AddCurve([points[1]], points[2]);
                        break;
                    case SKPathVerb.Conic:
                        // Java has no conics; a Skia-made arc is followed as quadratics.
                        var quads = SKPath.ConvertConicToQuads(
                            points[0], points[1], points[2], iterator.ConicWeight(), 2);
                        for (var index = 1; index + 1 < quads.Length; index += 2)
                            AddCurve([quads[index]], quads[index + 1]);
                        break;
                    case SKPathVerb.Cubic:
                        AddCurve([points[1], points[2]], points[3]);
                        break;
                    case SKPathVerb.Close:
                        if (snapped.Count > start) CheckLine(snapped[snapped.Count - 1], snapped[start]);
                        verbs.Add((verb, snapped.Count));
                        moved = startMoved;
                        break;
                }
            }
            CheckImplicitClose(start);

            (double X, double Y) AddEnd(SKPoint point)
            {
                var (x, y) = Map(transform, point.X, point.Y);
                var (snappedX, snappedY) = Snap(x, y);
                snapped.Add((snappedX, snappedY));
                return (snappedX - x, snappedY - y);
            }

            void AddCurve(SKPoint[] controls, SKPoint end)
            {
                straight = false;
                verbs.Add((controls.Length == 1 ? SKPathVerb.Quad : SKPathVerb.Cubic, snapped.Count));
                var (endX, endY) = Map(transform, end.X, end.Y);
                var endSnapped = Snap(endX, endY);
                (double X, double Y) endMoved = (endSnapped.X - endX, endSnapped.Y - endY);
                for (var index = 0; index < controls.Length; index++)
                {
                    var (x, y) = Map(transform, controls[index].X, controls[index].Y);
                    var shift = controls.Length == 1 ? ((moved.X + endMoved.X) / 2, (moved.Y + endMoved.Y) / 2)
                        : index == 0 ? moved : endMoved;
                    snapped.Add(corner ? Snap(x, y) : (x + shift.Item1, y + shift.Item2));
                }
                snapped.Add(endSnapped);
                moved = endMoved;
            }
        }

        (double X, double Y) Snap(double x, double y) => corner
            ? (Math.Floor(x * 1024) / 1024, Math.Floor(y * 1024) / 1024)
            : (Math.Floor(x + 0.25) + 0.25, Math.Floor(y + 0.25) + 0.25);

        void CheckLine((double X, double Y) from, (double X, double Y) to)
        {
            if (from.X != to.X && from.Y != to.Y) straight = false;
        }

        // A fill closes an open subpath with a line back to its start.
        void CheckImplicitClose(int subpathStart)
        {
            if (snapped.Count > subpathStart) CheckLine(snapped[subpathStart], snapped[snapped.Count - 1]);
        }

        // The result samples pixel centres. Skia covers pixel i when its
        // centre lies in (lo, hi], so for it a corner coordinate t = j / 1024
        // becomes the multiple of 1/64 s = (ceil(64 t) + 31) / 64, which holds
        // that exactly when t <= i < t' for the other edge (and rounds to
        // itself at 1/64). The span computation takes s = t + 1/2.
        var direct = rectilinear = straight;
        var offset = corner ? 0.5 : 0;
        var device = new SKPath { FillType = lease.Path.FillType };
        var flattened = new List<(double X, double Y)>();
        (double X, double Y) current = default, subpathStart = default;
        foreach (var (verb, first) in verbs)
        {
            switch (verb)
            {
                case SKPathVerb.Move:
                    device.MoveTo(Point(snapped[first]));
                    current = subpathStart = snapped[first];
                    break;
                case SKPathVerb.Line:
                    device.LineTo(Point(snapped[first]));
                    current = snapped[first];
                    break;
                case SKPathVerb.Quad or SKPathVerb.Cubic:
                    var curve = new (double X, double Y)[verb == SKPathVerb.Quad ? 3 : 4];
                    curve[0] = current;
                    for (var index = 1; index < curve.Length; index++) curve[index] = snapped[first + index - 1];
                    flattened.Clear();
                    FlattenCurve(curve, corner, cull, flattened);
                    foreach (var point in flattened) device.LineTo(Point(point));
                    current = curve[curve.Length - 1];
                    break;
                case SKPathVerb.Close:
                    device.Close();
                    current = subpathStart;
                    break;
            }
        }
        return device;

        SKPoint Point((double X, double Y) point) => corner && direct
            ? new SKPoint((float)((Math.Ceiling(point.X * 64) + 31) / 64), (float)((Math.Ceiling(point.Y * 64) + 31) / 64))
            : new SKPoint((float)(point.X + offset), (float)(point.Y + offset));
    }

    // Draws a DevicePath on a canvas whose matrix maps device coordinates.
    internal static void Draw(SKCanvas canvas, SKPath devicePath, SKPaint paint, bool rectilinear)
    {
        if (rectilinear)
        {
            canvas.DrawPath(devicePath, paint);
            return;
        }
        using var spans = Spans(devicePath, canvas.LocalClipBounds);
        canvas.DrawPath(spans, paint);
    }

    // The pixels whose centres lie inside the path (lines only, as
    // DevicePath makes it), left and top edges included, as one integer
    // rectangle per run, within the clip.
    private static SKPath Spans(SKPath path, SKRect clip)
    {
        var edges = new List<(double X0, double Y0, double X1, double Y1, int Winding)>();
        Edges(path, edges);
        var inverse = path.FillType is SKPathFillType.InverseWinding or SKPathFillType.InverseEvenOdd;
        var evenOdd = path.FillType is SKPathFillType.EvenOdd or SKPathFillType.InverseEvenOdd;
        var bounds = path.Bounds;
        var clipLeft = (int)Math.Floor(clip.Left);
        var clipRight = (int)Math.Ceiling(clip.Right);
        var firstRow = (int)Math.Floor(inverse ? clip.Top : Math.Max(clip.Top, bounds.Top - 1));
        var lastRow = (int)Math.Ceiling(inverse ? clip.Bottom : Math.Min(clip.Bottom, bounds.Bottom + 1));
        // Edges by first row, then an active list per row.
        edges.Sort((a, b) => a.Y0.CompareTo(b.Y0));
        var active = new List<int>();
        var crossings = new List<(double X, int Winding)>();
        var next = 0;
        var result = new SKPath();
        for (var row = firstRow; row < lastRow; row++)
        {
            var y = row + 0.5;
            while (next < edges.Count && edges[next].Y0 <= y) active.Add(next++);
            crossings.Clear();
            for (var index = active.Count - 1; index >= 0; index--)
            {
                var edge = edges[active[index]];
                if (edge.Y1 <= y)
                {
                    active.RemoveAt(index);
                    continue;
                }
                crossings.Add((edge.X0 + (y - edge.Y0) * (edge.X1 - edge.X0) / (edge.Y1 - edge.Y0), edge.Winding));
            }
            crossings.Sort((a, b) => a.X.CompareTo(b.X));
            // Pixel i is inside when its centre i + 1/2 lies at or after the
            // crossing that enters and before the one that leaves.
            var winding = 0;
            var runStart = inverse ? (double?)clipLeft + 0.5 : null;
            foreach (var (x, edgeWinding) in crossings)
            {
                var wasInside = (evenOdd ? winding & 1 : winding) != 0;
                winding += edgeWinding;
                var isInside = (evenOdd ? winding & 1 : winding) != 0;
                if (isInside == wasInside) continue;
                if (isInside != inverse)
                {
                    runStart = x;
                }
                else if (runStart is double start)
                {
                    AddRun(start, x);
                    runStart = null;
                }
            }
            if (runStart is double open) AddRun(open, clipRight + 0.5);

            void AddRun(double from, double to)
            {
                var left = Math.Max(clipLeft, Math.Ceiling(from - 0.5));
                var right = Math.Min(clipRight, Math.Ceiling(to - 0.5));
                if (left < right) result.AddRect(new SKRect((float)left, row, (float)right, row + 1));
            }
        }
        return result;
    }

    // The outline of a path of lines as edges from top to bottom with their
    // winding direction; an open subpath is closed.
    private static void Edges(SKPath path, List<(double X0, double Y0, double X1, double Y1, int Winding)> edges)
    {
        using var iterator = path.CreateRawIterator();
        var points = new SKPoint[4];
        (double X, double Y) start = default, last = default;
        var open = false;
        SKPathVerb verb;
        while ((verb = iterator.Next(points)) != SKPathVerb.Done)
        {
            switch (verb)
            {
                case SKPathVerb.Move:
                    if (open) Line(last, start);
                    start = last = (points[0].X, points[0].Y);
                    open = true;
                    break;
                case SKPathVerb.Line:
                    Line(last, (points[1].X, points[1].Y));
                    break;
                case SKPathVerb.Close:
                    Line(last, start);
                    last = start;
                    open = false;
                    break;
            }
        }
        if (open) Line(last, start);

        void Line((double X, double Y) from, (double X, double Y) to)
        {
            last = to;
            if (from.Y == to.Y) return;
            edges.Add(from.Y < to.Y
                ? (from.X, from.Y, to.X, to.Y, 1)
                : (to.X, to.Y, from.X, from.Y, -1));
        }
    }

    // Java 2D's flattening of one snapped device-space curve (a quadratic or
    // a cubic, given by its control points), appended to output as the
    // points after its start. Inferred from black-box probes of Java 2D's
    // masks (see FlattenCorner and FlattenCentre).
    private static void FlattenCurve(
        (double X, double Y)[] curve, bool corner, SKRect cull, List<(double X, double Y)> output)
    {
        foreach (var (x, y) in curve)
        {
            if (double.IsNaN(x) || double.IsInfinity(x) || double.IsNaN(y) || double.IsInfinity(y))
            {
                output.Add(curve[curve.Length - 1]);
                return;
            }
        }
        if (!corner)
        {
            FlattenCentre(curve, cull, output, 0);
            return;
        }
        var cuts = new List<double> { 0, 1 };
        AddExtrema(curve, point => point.X, cuts);
        AddExtrema(curve, point => point.Y, cuts);
        cuts.Sort();
        for (var index = 1; index < cuts.Count; index++)
        {
            double from = cuts[index - 1], to = cuts[index];
            if (to <= from) continue;
            var piece = to < 1 ? Split(curve, to, left: true) : curve;
            FlattenCorner(from > 0 ? Split(piece, from / to, left: false) : piece, cull, output, 0);
        }
    }

    // Corner sampling, measured by fitting each row's edge position (found
    // to 1/256 pixel by sliding the curve in 1/256 pixel steps) on 1200
    // random curves: Java cuts a curve at its x and y extrema, halves each
    // monotone piece while its control points span more than 256 pixels on
    // an axis, then steps through the piece starting at 1/8 of it (cubic) or
    // 1/4 (quadratic). Before each step it halves the step while the second
    // difference of the next points, h^2 B''(u + h), exceeds 4 pixels on an
    // axis, or a cubic's third difference h^3 B''' exceeds 2. The step never
    // grows again.
    private static void FlattenCorner(
        (double X, double Y)[] curve, SKRect cull, List<(double X, double Y)> output, int depth)
    {
        if (Outside(curve, cull))
        {
            output.Add(curve[curve.Length - 1]);
            return;
        }
        double minX = double.MaxValue, maxX = double.MinValue, minY = double.MaxValue, maxY = double.MinValue;
        foreach (var (x, y) in curve)
        {
            minX = Math.Min(minX, x); maxX = Math.Max(maxX, x);
            minY = Math.Min(minY, y); maxY = Math.Max(maxY, y);
        }
        if (depth < 40 && Math.Max(maxX - minX, maxY - minY) > 256)
        {
            FlattenCorner(Split(curve, 0.5, left: true), cull, output, depth + 1);
            FlattenCorner(Split(curve, 0.5, left: false), cull, output, depth + 1);
            return;
        }
        const int unit = 1 << 20;
        var cubic = curve.Length == 4;
        var level = cubic ? 3 : 2;
        var position = 0;
        while (position < unit)
        {
            var u = (double)position / unit;
            while (level < 16 && TooCoarse(1.0 / (1 << level))) level++;
            position = Math.Min(unit, position + (unit >> level));
            output.Add(position == unit ? curve[curve.Length - 1] : Evaluate(curve, (double)position / unit));

            bool TooCoarse(double h)
            {
                var (x, y) = SecondDerivative(curve, u + h);
                if (Math.Max(Math.Abs(x), Math.Abs(y)) * h * h > 4) return true;
                if (!cubic) return false;
                var (nextX, nextY) = SecondDerivative(curve, u + 2 * h);
                return Math.Max(Math.Abs(nextX - x), Math.Abs(nextY - y)) * h * h > 2;
            }
        }
    }

    // Centre sampling, measured by comparing whole masks of large and random
    // curves against candidate subdivisions: Java halves the curve, without
    // cutting it at its extrema, until every inner control point lies
    // within one pixel of the chord between the ends.
    private static void FlattenCentre(
        (double X, double Y)[] curve, SKRect cull, List<(double X, double Y)> output, int depth)
    {
        var flat = true;
        for (var index = 1; index < curve.Length - 1 && flat; index++)
            flat = SegmentDistance(curve[index], curve[0], curve[curve.Length - 1]) <= 1;
        if (flat || depth >= 24 || Outside(curve, cull))
        {
            output.Add(curve[curve.Length - 1]);
            return;
        }
        FlattenCentre(Split(curve, 0.5, left: true), cull, output, depth + 1);
        FlattenCentre(Split(curve, 0.5, left: false), cull, output, depth + 1);
    }

    // Whether the control points, and so the curve, lie wholly beyond one
    // side of the clip; the piece and its chord then cross no visible row.
    private static bool Outside((double X, double Y)[] curve, SKRect cull)
    {
        bool left = true, right = true, above = true, below = true;
        foreach (var (x, y) in curve)
        {
            left &= x < cull.Left - 1;
            right &= x > cull.Right + 1;
            above &= y < cull.Top - 1;
            below &= y > cull.Bottom + 1;
        }
        return left || right || above || below;
    }

    private static void AddExtrema(
        (double X, double Y)[] curve, Func<(double X, double Y), double> axis, List<double> cuts)
    {
        double p0 = axis(curve[0]), p1 = axis(curve[1]), p2 = axis(curve[2]);
        if (curve.Length == 3)
        {
            var denominator = p0 - 2 * p1 + p2;
            if (denominator != 0) AddCut((p0 - p1) / denominator);
            return;
        }
        // The derivative is a(1-t)^2 + 2b(1-t)t + ct^2 in the differences.
        double a = p1 - p0, b = p2 - p1, c = axis(curve[3]) - p2;
        double quadratic = a - 2 * b + c, linear = 2 * (b - a);
        if (Math.Abs(quadratic) < 1e-12)
        {
            if (Math.Abs(linear) > 1e-12) AddCut(-a / linear);
            return;
        }
        var discriminant = linear * linear - 4 * quadratic * a;
        if (discriminant < 0) return;
        var root = Math.Sqrt(discriminant);
        AddCut((-linear - root) / (2 * quadratic));
        AddCut((-linear + root) / (2 * quadratic));

        void AddCut(double t)
        {
            if (t > 0 && t < 1) cuts.Add(t);
        }
    }

    // One side of the curve split at t (de Casteljau).
    private static (double X, double Y)[] Split((double X, double Y)[] curve, double t, bool left)
    {
        var count = curve.Length;
        var work = ((double X, double Y)[])curve.Clone();
        var result = new (double X, double Y)[count];
        for (var level = 0; level < count; level++)
        {
            if (left) result[level] = work[0];
            else result[count - 1 - level] = work[count - 1 - level];
            for (var index = 0; index < count - 1 - level; index++)
                work[index] = (work[index].X + (work[index + 1].X - work[index].X) * t,
                    work[index].Y + (work[index + 1].Y - work[index].Y) * t);
        }
        return result;
    }

    private static (double X, double Y) Evaluate((double X, double Y)[] curve, double t)
    {
        var work = ((double X, double Y)[])curve.Clone();
        for (var count = work.Length - 1; count > 0; count--)
            for (var index = 0; index < count; index++)
                work[index] = (work[index].X + (work[index + 1].X - work[index].X) * t,
                    work[index].Y + (work[index + 1].Y - work[index].Y) * t);
        return work[0];
    }

    private static (double X, double Y) SecondDerivative((double X, double Y)[] curve, double t)
    {
        if (curve.Length == 3)
            return (2 * (curve[0].X - 2 * curve[1].X + curve[2].X), 2 * (curve[0].Y - 2 * curve[1].Y + curve[2].Y));
        double ax = curve[0].X - 2 * curve[1].X + curve[2].X, ay = curve[0].Y - 2 * curve[1].Y + curve[2].Y;
        double bx = curve[1].X - 2 * curve[2].X + curve[3].X, by = curve[1].Y - 2 * curve[2].Y + curve[3].Y;
        return (6 * ((1 - t) * ax + t * bx), 6 * ((1 - t) * ay + t * by));
    }

    private static double SegmentDistance((double X, double Y) point, (double X, double Y) a, (double X, double Y) b)
    {
        double dx = b.X - a.X, dy = b.Y - a.Y;
        var length = dx * dx + dy * dy;
        var t = length == 0 ? 0 : Math.Max(0, Math.Min(1, ((point.X - a.X) * dx + (point.Y - a.Y) * dy) / length));
        double px = a.X + t * dx - point.X, py = a.Y + t * dy - point.Y;
        return Math.Sqrt(px * px + py * py);
    }

    private static (double X, double Y) Map(SKMatrix m, float x, float y) =>
        ((double)m.ScaleX * x + (double)m.SkewX * y + m.TransX,
         (double)m.SkewY * x + (double)m.ScaleY * y + m.TransY);
}

// The coverage Java 2D gives an antialiased fill of a path (any shape but an
// axis-aligned Rectangle2D, whose coverage is its exact area as in Skia), and
// so of an antialiased BasicStroke's outline. Inferred from black-box probes
// of OpenJDK 25 filling paths with opaque black on TYPE_INT_ARGB images and
// reading the alpha back; no JDK source was read:
// - Sliding a horizontal edge down a pixel in 1/256 pixel steps changes the
//   alpha in 8 steps, at offsets 1/16 + k/8: each pixel row is sampled on 8
//   subscanlines at y = row + (2k + 1)/16, and a subscanline is inside an
//   edge from its top (included) to its bottom (excluded).
// - Sliding a vertical edge in 1/2048 pixel steps changes the alpha by one
//   every 1/256 pixel, at offsets (k + 1/2)/256 (a half rounds down): along
//   a subscanline the inside spans start and end at the crossing x rounded
//   to 1/256 pixel, and each pixel gets the exact length of span it holds.
// - With T the sum of a pixel's span lengths over its 8 subscanlines in
//   1/256 pixel (0..2048), the alpha is (255 T + 512) / 2048 rounded down:
//   fitted on edges giving 1..8 subscanlines and every 1/256 position.
// This matched Java exactly, every pixel, on 400 random self-intersecting
// polygons (non-zero winding). Curves are cut into lines first: a quadratic
// into n equal parameter steps, n the least power of two with
// 4 (|x0 - 2x1 + x2| + |y0 - 2y1 + y2|) <= n^2 (fitted on 320 random
// quadratics, all exact). A cubic is stepped adaptively, halving the step
// while the second difference ahead exceeds a pixel (summed over the axes)
// and doubling it while that falls below a quarter (the third difference
// bounded too); this matched about half of 100 random cubics exactly and the
// rest within a few pixels.
internal static class PdfCartonAntialiasedFill
{
    private const int Subscanlines = 8;

    [ThreadStatic] private static int[]? cover;
    [ThreadStatic] private static int[]? delta;
    [ThreadStatic] private static byte[]? row;
    [ThreadStatic] private static Edge[]? edges;
    [ThreadStatic] private static Crossing[]? crossings;
    [ThreadStatic] private static int[]? active;

    private struct Edge
    {
        public double X0, Y0, X1, Y1, Slope;
        public int Winding;
    }

    private struct Crossing
    {
        public double X;
        public int Winding;
    }

    // The Java 2D coverage of a device-space path within bounds, as an
    // Alpha8 bitmap placed at bounds' top-left corner, or null when the
    // path covers no pixel there. Inverse fill types are not handled.
    internal static SKBitmap? Mask(SKPath devicePath, SKRectI bounds)
    {
        if (bounds.IsEmpty) return null;
        int top = bounds.Top, bottom = bounds.Bottom, left = bounds.Left, right = bounds.Right;
        var count = Flatten(devicePath, top, bottom);
        if (count == 0) return null;
        var evenOdd = devicePath.FillType == SKPathFillType.EvenOdd;
        var list = edges!;
        Array.Sort(list, 0, count, EdgeOrder.Instance);

        var width = right - left;
        var height = bottom - top;
        cover = Grow(cover, width + 1);
        delta = Grow(delta, width + 1);
        row = Grow(row, width);
        active = Grow(active, count);
        crossings = Grow(crossings, count);
        Array.Clear(cover, 0, width + 1);
        Array.Clear(delta, 0, width + 1);
        var mask = new SKBitmap(new SKImageInfo(width, height, SKColorType.Alpha8, SKAlphaType.Premul));
        mask.Erase(SKColors.Transparent);
        var pixels = mask.GetPixels();
        var rowBytes = mask.RowBytes;
        var any = false;
        var next = 0;
        var activeCount = 0;
        double minimum = left * 256.0, maximum = right * 256.0;
        for (var y = top; y < bottom; y++)
        {
            for (var sub = 0; sub < Subscanlines; sub++)
            {
                var sample = y + (2 * sub + 1) / (2.0 * Subscanlines);
                while (next < count && list[next].Y0 <= sample)
                    active[activeCount++] = next++;
                var crossingCount = 0;
                var kept = 0;
                for (var index = 0; index < activeCount; index++)
                {
                    ref var edge = ref list[active[index]];
                    if (edge.Y1 <= sample) continue;
                    active[kept++] = active[index];
                    crossings[crossingCount++] = new Crossing
                    {
                        X = edge.X0 + (sample - edge.Y0) * edge.Slope,
                        Winding = edge.Winding
                    };
                }
                activeCount = kept;
                if (crossingCount < 2) continue;
                SortCrossings(crossings, crossingCount);
                var winding = 0;
                var start = 0.0;
                for (var index = 0; index < crossingCount; index++)
                {
                    var wasInside = evenOdd ? (winding & 1) != 0 : winding != 0;
                    winding += crossings[index].Winding;
                    var inside = evenOdd ? (winding & 1) != 0 : winding != 0;
                    if (inside == wasInside) continue;
                    if (inside)
                    {
                        start = crossings[index].X;
                        continue;
                    }
                    var from = Quantize(start, minimum, maximum) - left * 256;
                    var to = Quantize(crossings[index].X, minimum, maximum) - left * 256;
                    if (to <= from) continue;
                    any = true;
                    int first = from >> 8, last = to >> 8;
                    if (first == last)
                    {
                        cover[first] += to - from;
                        continue;
                    }
                    cover[first] += 256 - (from & 255);
                    delta[first + 1] += 256;
                    delta[last] -= 256;
                    cover[last] += to & 255;
                }
            }
            var run = 0;
            for (var x = 0; x < width; x++)
            {
                run += delta[x];
                var total = run + cover[x];
                row[x] = (byte)((total * 255 + 512) >> 11);
            }
            Array.Clear(cover, 0, width + 1);
            Array.Clear(delta, 0, width + 1);
            Marshal.Copy(row, 0, pixels + (y - top) * rowBytes, width);
            if (next >= count && activeCount == 0) break;
        }
        if (!any)
        {
            mask.Dispose();
            return null;
        }
        mask.NotifyPixelsChanged();
        return mask;
    }

    // Draws a Mask placed at bounds' top-left corner on a canvas whose matrix
    // maps device coordinates (an offset at most): its alpha is the coverage
    // of the paint's colour or shader.
    internal static void Draw(SKCanvas canvas, SKBitmap mask, SKRectI bounds, SKPaint paint)
    {
        using var pixmap = mask.PeekPixels();
        using var image = SKImage.FromPixels(pixmap);
        canvas.DrawImage(image, bounds.Left, bounds.Top, paint);
    }

    // The crossing x in 1/256 pixel, a half rounding down, kept within the mask.
    private static int Quantize(double x, double minimum, double maximum)
    {
        var scaled = Math.Ceiling(x * 256 - 0.5);
        return (int)(scaled < minimum ? minimum : scaled > maximum ? maximum : scaled);
    }

    // Insertion sort for the few crossings of most rows; a path with many
    // edges on one row (a long zigzag, say) is sorted in n log n.
    private static void SortCrossings(Crossing[] values, int count)
    {
        if (count > 32)
        {
            Array.Sort(values, 0, count, CrossingOrder.Instance);
            return;
        }
        for (var index = 1; index < count; index++)
        {
            var value = values[index];
            var position = index - 1;
            while (position >= 0 && values[position].X > value.X)
            {
                values[position + 1] = values[position];
                position--;
            }
            values[position + 1] = value;
        }
    }

    private static T[] Grow<T>(T[]? array, int length) =>
        array is not null && array.Length >= length ? array : new T[Math.Max(length, (array?.Length ?? 16) * 2)];

    private sealed class CrossingOrder : IComparer<Crossing>
    {
        internal static readonly CrossingOrder Instance = new();
        public int Compare(Crossing a, Crossing b) => a.X.CompareTo(b.X);
    }

    private sealed class EdgeOrder : IComparer<Edge>
    {
        internal static readonly EdgeOrder Instance = new();
        public int Compare(Edge a, Edge b) => a.Y0.CompareTo(b.Y0);
    }

    // The path's outline as lines (subpaths closed), keeping the edges that
    // reach the sample rows of [top, bottom); returns how many are in edges.
    private static int Flatten(SKPath path, int top, int bottom)
    {
        edges ??= new Edge[64];
        var count = 0;
        var points = new SKPoint[4];
        double startX = 0, startY = 0, x = 0, y = 0;
        var open = false;
        using var iterator = path.CreateRawIterator();
        SKPathVerb verb;
        while ((verb = iterator.Next(points)) != SKPathVerb.Done)
        {
            switch (verb)
            {
                case SKPathVerb.Move:
                    if (open) Line(startX, startY);
                    startX = x = points[0].X;
                    startY = y = points[0].Y;
                    open = true;
                    break;
                case SKPathVerb.Line:
                    Line(points[1].X, points[1].Y);
                    break;
                case SKPathVerb.Quad:
                    Quad(points[1].X, points[1].Y, points[2].X, points[2].Y);
                    break;
                case SKPathVerb.Conic:
                    // Java has no conics; a Skia-made arc is followed as quadratics.
                    var quads = SKPath.ConvertConicToQuads(points[0], points[1], points[2], iterator.ConicWeight(), 2);
                    for (var index = 1; index + 1 < quads.Length; index += 2)
                        Quad(quads[index].X, quads[index].Y, quads[index + 1].X, quads[index + 1].Y);
                    break;
                case SKPathVerb.Cubic:
                    Cubic(points[1].X, points[1].Y, points[2].X, points[2].Y, points[3].X, points[3].Y);
                    break;
                case SKPathVerb.Close:
                    Line(startX, startY);
                    open = false;
                    break;
            }
        }
        if (open) Line(startX, startY);
        return count;

        void Line(double toX, double toY)
        {
            double fromX = x, fromY = y;
            x = toX;
            y = toY;
            if (fromY == toY || double.IsNaN(fromX + fromY + toX + toY) ||
                double.IsInfinity(fromX + fromY + toX + toY))
                return;
            var winding = 1;
            if (fromY > toY)
            {
                (fromX, fromY, toX, toY) = (toX, toY, fromX, fromY);
                winding = -1;
            }
            if (toY <= top || fromY >= bottom) return;
            if (count == edges!.Length) Array.Resize(ref edges, count * 2);
            edges[count++] = new Edge
            {
                X0 = fromX, Y0 = fromY, X1 = toX, Y1 = toY,
                Slope = (toX - fromX) / (toY - fromY), Winding = winding
            };
        }

        void Quad(double x1, double y1, double x2, double y2)
        {
            double x0 = x, y0 = y;
            var size = Math.Abs(x0 - 2 * x1 + x2) + Math.Abs(y0 - 2 * y1 + y2);
            var steps = 1;
            while (steps < 1024 && 4 * size > (double)steps * steps) steps *= 2;
            for (var index = 1; index < steps; index++)
            {
                var t = (double)index / steps;
                var u = 1 - t;
                Line(u * u * x0 + 2 * u * t * x1 + t * t * x2, u * u * y0 + 2 * u * t * y1 + t * t * y2);
            }
            Line(x2, y2);
        }

        void Cubic(double x1, double y1, double x2, double y2, double x3, double y3)
        {
            double x0 = x, y0 = y;
            if (double.IsNaN(x0 + y0 + x1 + y1 + x2 + y2 + x3 + y3) ||
                double.IsInfinity(x0 + y0 + x1 + y1 + x2 + y2 + x3 + y3))
            {
                Line(x3, y3);
                return;
            }
            const int unit = 1 << 16;
            // The third difference, 6 h^3 |P3 - 3P2 + 3P1 - P0|, also stays
            // within 8 px, so that a step across an inflection, whose second
            // difference can vanish, stays short. That bound left the fit on
            // random cubics unchanged.
            var third = 6 * (Math.Abs(x3 - 3 * x2 + 3 * x1 - x0) + Math.Abs(y3 - 3 * y2 + 3 * y1 - y0));
            var level = 2;
            var position = 0;
            while (position < unit)
            {
                var u = (double)position / unit;
                while (level < 16 && (SecondDifference(u, 1.0 / (1 << level)) > 1 || ThirdDifference(level) > 8))
                    level++;
                while (level > 0 && position % (unit >> (level - 1)) == 0 &&
                       SecondDifference(u, 1.0 / (1 << (level - 1))) < 0.25 && ThirdDifference(level - 1) <= 8)
                    level--;
                position = Math.Min(unit, position + (unit >> level));
                if (position == unit)
                {
                    Line(x3, y3);
                }
                else
                {
                    var (px, py) = Point((double)position / unit);
                    Line(px, py);
                }
            }

            double ThirdDifference(int stepLevel)
            {
                var h = 1.0 / (1 << stepLevel);
                return third * h * h * h;
            }

            double SecondDifference(double u, double h)
            {
                var (ax, ay) = Point(u);
                var (bx, by) = Point(u + h);
                var (cx, cy) = Point(u + 2 * h);
                return Math.Abs(ax - 2 * bx + cx) + Math.Abs(ay - 2 * by + cy);
            }

            (double X, double Y) Point(double t)
            {
                var s = 1 - t;
                double a = s * s * s, b = 3 * s * s * t, c = 3 * s * t * t, d = t * t * t;
                return (a * x0 + b * x1 + c * x2 + d * x3, a * y0 + b * y1 + c * y2 + d * y3);
            }
        }
    }
}

// Owns its path: Dispose releases it at once instead of at finalization.
// A Lend area reads a borrowed path in place until its first change.
public sealed class JavaArea : IDisposable
{
    private SKPath path;
    private bool ownsPath = true;

    public JavaArea() => path = new SKPath();

    public JavaArea(object shape) => path = PdfCartonFontCompat.CreatePath(shape);

    private JavaArea(SKPath path, bool ownsPath) => (this.path, this.ownsPath) = (path, ownsPath);

    // new Area(path) for a path left unchanged while the area is in use.
    internal static JavaArea Lend(SKPath path) =>
        new(path ?? throw new ArgumentNullException(nameof(path)), ownsPath: false);

    public bool IsEmpty => path.IsEmpty;
    public SKRect Bounds => path.Bounds;
    internal SKPath Path => path;

    public void Intersect(JavaArea other)
    {
        ArgumentNullException.ThrowIfNull(other);
        IntersectInto(other.path, ownsPath ? path : new SKPath());
    }

    // intersect(new Area(shape)) for a path made for this call alone
    // (Graphics2D.getClip's copy): it receives the intersection and becomes
    // this area's path. Its maker may still dispose it (that is idempotent).
    internal void IntersectTemporary(SKPath shape) =>
        IntersectInto(shape ?? throw new ArgumentNullException(nameof(shape)), shape);

    // The result may be written over an operand. Skia intersects small paths
    // and makes its even-odd result the nonzero one an Area has, with its
    // holes reversed; larger ones are clipped (PdfCartonAreaIntersection).
    private void IntersectInto(SKPath other, SKPath result)
    {
        if (!PdfCartonAreaIntersection.Intersect(path, other, result))
        {
            if (result != path && result != other) result.Dispose();
            throw new InvalidOperationException("Unable to intersect paths.");
        }
        if (result == path) return;
        if (ownsPath) path.Dispose();
        (path, ownsPath) = (result, true);
    }

    public void Reset()
    {
        if (ownsPath) path.Reset();
        else (path, ownsPath) = (new SKPath(), true);
    }

    public void Dispose()
    {
        if (ownsPath) path.Dispose();
    }

    public JavaPathIterator GetPathIterator(object? transform) =>
        new(path, transform);

    public override bool Equals(object? value)
    {
        if (ReferenceEquals(this, value)) return true;
        if (value is not JavaArea other) return false;
        using var difference = new SKPath();
        return path.Op(other.path, SKPathOp.Xor, difference) && difference.IsEmpty;
    }

    public override int GetHashCode() => Bounds.GetHashCode();
}

// A java.awt.geom.GeneralPath made by translated code. SkiaSharp m150 keeps
// an SKPath's mutations in a builder that any read of the path flushes, and
// the next mutation then copies the whole path into a new builder. PageDrawer
// and Type1CharString ask for the current point before every segment, so the
// compat helpers keep Path2D.getCurrentPoint() here instead of reading the
// path, which keeps building an n-segment path O(n). Only this assembly's
// helpers keep it; after a change they do not follow (transform, append) it
// is Unknown and read from the path once. A path handed to callers, who can
// change it through SKPath itself, is Lent and always read.
internal sealed class PdfCartonGeneralPath : SKPath
{
    internal PdfCartonGeneralPath() { }

    internal PdfCartonGeneralPath(SKPath source) : base(source) { }

    internal PathCursor Cursor;
    internal bool Lent;
    // The current point, and the start of the current subpath, to which
    // closePath returns it.
    internal SKPoint Current;
    internal SKPoint SubpathStart;
}

internal enum PathCursor : byte { Empty, Open, Closed, Unknown }

// Native-bitmap lifetime. Java leaves BufferedImages to the garbage collector,
// but SKBitmap pixels are native memory the GC cannot see. Two parts:
// - Backstop: every compat-allocated bitmap is a PdfCartonNativeBitmap, which
//   reports its pixel bytes as GC memory pressure and removes exactly that
//   amount once, when it is disposed or finalized, so undisposed bitmaps still
//   pace collections. Int-backed bitmaps (CreateIntBacked) hold their pixels
//   in a managed array, which the GC already sees.
// - Ownership: a translated intermediate that never escapes its method is
//   disposed deterministically. The translator's owned-bitmap statement
//   rewrites (src/dripsharp/pdfcube/java_project.clj) make it a `using` local
//   or Own it in a PdfCartonDisposalScope, and CreateDeferredImage skips the
//   native render for images that are only read through their raster.
internal sealed class PdfCartonNativeBitmap : SKBitmap
{
    // Frees the pin once Skia releases the pixels, which may outlive the
    // bitmap while a canvas or image still shares them.
    private sealed class PinnedPixels(int[] pixels)
    {
        private GCHandle handle = GCHandle.Alloc(pixels, GCHandleType.Pinned);
        private int released;

        internal IntPtr Address => handle.AddrOfPinnedObject();

        internal void Release()
        {
            if (Interlocked.Exchange(ref released, 1) == 0) handle.Free();
        }
    }

    private static readonly SKBitmapReleaseDelegate ReleasePinnedPixels =
        (_, context) => ((PinnedPixels)context).Release();

    private static long liveCount;
    private static long outstandingBytes;
    private readonly long pixelBytes;
    private readonly long pressure;
    private int registered;
    private volatile bool intPixelsExposed;
    // Cleared on disposal, so a retained disposed wrapper keeps no pixels.
    private int[]? intPixels;
    private IntPtr intPixelsAddress;

    internal PdfCartonNativeBitmap(SKImageInfo info)
        : base(info)
    {
        pixelBytes = (long)RowBytes * Height;
        pressure = pixelBytes;
        if (pressure > 0) GC.AddMemoryPressure(pressure);
        Register();
    }

    private PdfCartonNativeBitmap(int[] pixels)
    {
        intPixels = pixels;
        pixelBytes = (long)pixels.Length * sizeof(int);
        Register();
    }

    // Java's TYPE_INT_RGB and TYPE_INT_ARGB images store one packed
    // 0xAARRGGBB int per pixel, which is the memory of a little-endian
    // BGRA8888 pixel. Such a bitmap keeps its pixels in a pinned managed
    // array, so DataBufferInt.getData() returns the storage itself, as in Java,
    // and Skia, raster views and the array all see each other's writes. The
    // array is managed memory, so it adds no GC pressure.
    internal static PdfCartonNativeBitmap CreateIntBacked(SKImageInfo info)
    {
        if (info.ColorType != SKColorType.Bgra8888 || info.AlphaType == SKAlphaType.Premul ||
            !BitConverter.IsLittleEndian)
            throw new ArgumentException("Int-backed bitmaps hold little-endian straight BGRA pixels.",
                nameof(info));
        var pixels = new int[checked(info.Width * info.Height)];
        var bitmap = new PdfCartonNativeBitmap(pixels);
        var pinned = new PinnedPixels(pixels);
        bitmap.intPixelsAddress = pinned.Address;
        // Skia calls the release delegate on failure too.
        if (!bitmap.InstallPixels(info, pinned.Address, info.RowBytes, ReleasePinnedPixels, pinned))
        {
            bitmap.Dispose();
            throw new InvalidOperationException("Unable to install managed bitmap pixels.");
        }
        return bitmap;
    }

    // The pinned array holding this bitmap's pixels: null unless the bitmap is
    // int-backed, undisposed and still uses the installed pixels (SKBitmap
    // methods such as TryAllocPixels can replace them).
    internal int[]? CurrentIntPixels()
    {
        var pixels = Volatile.Read(ref intPixels);
        if (pixels is null || Handle == IntPtr.Zero) return null;
        var info = Info;
        var current = GetPixels() == intPixelsAddress &&
                      info.ColorType == SKColorType.Bgra8888 &&
                      info.AlphaType != SKAlphaType.Premul &&
                      RowBytes == checked(info.Width * 4) &&
                      (long)info.Width * info.Height == pixels.Length;
        GC.KeepAlive(this);
        return current ? pixels : null;
    }

    // True once the int pixels were handed out: writes into them bypass the
    // raster views' change tracking, so every Publish then notifies Skia.
    internal bool IntPixelsExposed => intPixelsExposed;

    internal void ExposeIntPixels() => intPixelsExposed = true;

    private void Register()
    {
        Interlocked.Add(ref outstandingBytes, pixelBytes);
        Interlocked.Increment(ref liveCount);
        registered = 1;
    }

    // Compat bitmaps not yet disposed or finalized, and their pixel bytes.
    internal static long LiveCount => Interlocked.Read(ref liveCount);
    internal static long OutstandingBytes => Interlocked.Read(ref outstandingBytes);

    // SKBitmap.Decode(Stream), allocating the pixels as a compat bitmap.
    internal static SKBitmap? DecodeStream(Stream stream)
    {
        using var codec = SKCodec.Create(stream);
        if (codec is null) return null;
        var info = codec.Info;
        if (info.AlphaType == SKAlphaType.Unpremul)
            info.AlphaType = SKAlphaType.Premul;
        var bitmap = new PdfCartonNativeBitmap(info);
        var result = codec.GetPixels(info, bitmap.GetPixels(out _));
        if (result is SKCodecResult.Success or SKCodecResult.IncompleteInput)
            return bitmap;
        bitmap.Dispose();
        return null;
    }

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        // Skia's release callback unpins the array once no native owner
        // shares the pixels; the wrapper itself no longer references them.
        Volatile.Write(ref intPixels, null);
        if (Interlocked.Exchange(ref registered, 0) != 1) return;
        if (pressure > 0) GC.RemoveMemoryPressure(pressure);
        Interlocked.Add(ref outstandingBytes, -pixelBytes);
        Interlocked.Decrement(ref liveCount);
    }
}

// Owns the native resources (bitmaps, graphics contexts) of one translated
// method that do not escape it. Dispose releases every owned resource not
// handed back with Release, newest first, on success and failure alike.
internal sealed class PdfCartonDisposalScope : IDisposable
{
    private List<IDisposable>? owned;

    // Null resources are ignored, so optional results can be owned as is.
    internal T? Own<T>(T? resource)
        where T : class, IDisposable
    {
        if (resource is not null) (owned ??= new List<IDisposable>()).Add(resource);
        return resource;
    }

    // Transfers ownership of `resource` to the caller (the method's result).
    internal T? Release<T>(T? resource)
        where T : class, IDisposable
    {
        if (resource is not null)
            owned?.RemoveAll(candidate => ReferenceEquals(candidate, resource));
        return resource;
    }

    public void Dispose()
    {
        var resources = owned;
        owned = null;
        if (resources is null) return;
        for (var index = resources.Count - 1; index >= 0; index--)
            resources[index].Dispose();
    }

    // A resource that runs `action` once when disposed, so an owned side
    // effect (clearing a field that references an owned bitmap) is undone on
    // every exit unless it is Release-d.
    internal static IDisposable Deferred(Action action) => new DeferredAction(action);

    private sealed class DeferredAction : IDisposable
    {
        private Action? action;

        internal DeferredAction(Action action) => this.action = action;

        public void Dispose() => Interlocked.Exchange(ref action, null)?.Invoke();
    }
}

internal static class PdfCartonFontCompat
{
    internal const int SCALE_SMOOTH = 4;
    internal const int TYPE_TRANSLATION = 1;
    internal const int TYPE_UNIFORM_SCALE = 2;
    internal const int TYPE_GENERAL_SCALE = 4;
    internal const int TYPE_MASK_SCALE = TYPE_UNIFORM_SCALE | TYPE_GENERAL_SCALE;
    internal const int TYPE_GENERAL_ROTATION = 16;
    internal const int TYPE_GENERAL_TRANSFORM = 32;
    internal const int TYPE_FLIP = 64;
    internal const int TYPE_CUSTOM = 0;
    internal const int TYPE_INT_RGB = 1;
    internal const int TYPE_INT_ARGB = 2;
    internal const int TYPE_INT_BGR = 4;
    internal const int TYPE_3BYTE_BGR = 5;
    internal const int TYPE_4BYTE_ABGR = 6;
    internal const int TYPE_BYTE_GRAY = 10;
    internal const int TYPE_BYTE_BINARY = 12;
    internal const int DATA_BUFFER_TYPE_BYTE = 0;
    internal const int DATA_BUFFER_TYPE_USHORT = 1;
    internal const int DATA_BUFFER_TYPE_SHORT = 2;
    internal const int DATA_BUFFER_TYPE_INT = 3;

    private sealed class ImageMetadata
    {
        internal ImageMetadata(
            int type,
            JavaColorModel? colorModel = null,
            JavaRaster? raster = null,
            JavaSampleModel? sampleModel = null,
            int? transparency = null)
        {
            Type = type;
            ColorModel = colorModel;
            Raster = raster;
            SampleModel = sampleModel;
            Transparency = transparency;
        }

        internal int Type { get; }
        internal JavaColorModel? ColorModel { get; }
        internal JavaRaster? Raster { get; }
        internal JavaSampleModel? SampleModel { get; }
        internal int? Transparency { get; }
        // Set by CreateDeferredImage: the native pixels have not been rendered
        // from Raster yet. Read and cleared under a lock on this instance.
        internal bool NativePending { get; set; }
        // Set by CreateDeferredImage and ExposeSamples: Raster is managed
        // storage the native pixels are rendered from, not a view of them, so
        // native drawing must be copied back into it (BeginNativeDrawing).
        internal bool DetachedRaster { get; set; }
        // A detached raster's view of the native pixels it renders to,
        // created on first use under a lock on this instance.
        internal PdfCartonBitmapView? NativeView;
        // Set by ExposeSamples: Raster's exposed sample array, and a copy of
        // the samples the native pixels show (MarkRendered keeps it current).
        // Used under a lock on this instance.
        internal sbyte[]? ExposedSamples;
        internal sbyte[]? RenderedSamples;
        // Without ColorModel or Transparency (a decoded image, whose decoder
        // colour model is unknown), the transparency classified from the
        // pixels on first use; 0 until then. Written under a lock on this
        // instance.
        internal int PixelTransparency;
    }

    // Bitmap -> metadata with atomic replacement. netstandard2.0's
    // ConditionalWeakTable has no AddOrUpdate, and Remove-then-Add races: a
    // concurrent Add throws and readers briefly see no entry.
    private sealed class ImageMetadataTable
    {
        private sealed class Entry
        {
            internal ImageMetadata? Metadata;
        }

        private static readonly ConditionalWeakTable<SKBitmap, Entry>.CreateValueCallback CreateEntry =
            _ => new Entry();
        private readonly ConditionalWeakTable<SKBitmap, Entry> entries = new();

        internal bool TryGetValue(SKBitmap bitmap, out ImageMetadata metadata)
        {
            metadata = entries.TryGetValue(bitmap, out var entry)
                ? Volatile.Read(ref entry.Metadata)!
                : null!;
            return metadata is not null;
        }

        internal void Set(SKBitmap bitmap, ImageMetadata metadata) =>
            Volatile.Write(ref entries.GetValue(bitmap, CreateEntry).Metadata, metadata);

        // Installs metadata only if the bitmap's entry is still expected.
        internal bool TryReplace(SKBitmap bitmap, ImageMetadata? expected, ImageMetadata metadata) =>
            ReferenceEquals(
                Interlocked.CompareExchange(
                    ref entries.GetValue(bitmap, CreateEntry).Metadata,
                    metadata,
                    expected),
                expected);
    }

    private static readonly ImageMetadataTable ImageMetadataByBitmap = new();
    private static readonly Lazy<JavaIccProfile> StandardSrgbProfile = new(CreateSrgbProfile);
    // Apache PDFBox's bundled sRGB.icc, gzip-compressed for a compact authored
    // runtime representation. Upstream SHA-256:
    // bfb1c597bf5bf922bca57de556b972695e2ff60de305b43dc98c91f4d4154497.
    private const string StandardSrgbProfileGzipBase64 = """
        H4sIAAAAAAACA+2ZV1BUWRrHz72dEw3dTZOhyUmihAYk5yQ5igp0N5kWmhwURQZHYAQRkaQIIgo44OgQZBQVUQyIggIq6jQyCCjj4CgmVJbGB7dqq/Zpd1+2/w/3/uo7p+537q1TdX9VBwAZfAIrMQXWByCRm8rzdbZjBIeEMjD3ARaQABFQADqClZLk6efkD1YjmAv+Je/GASS439MRjOedJcUUf9AxPDrr8ujNZMtm8O9DZCdy2QBAtFWOY3NSWKu8c5Vj2IlsQX1OwBmpSakAwN6rTOOtLnCV2QKO/MaZAo7+xiVrc/x97Vf5KABYYvQa408JOHKNKT0CZsXwEgGQHlidr8JK4q0+X1rQS/HbGtYiKngfRjSHy+FFpHLYDPCfzj/1QqWsfnzwX8r/qo9g73yjN5ZrewKiV32vbasAgPkKAETZ95rKYQDIuwHo6vteizwOQHcZAJJPWWm89G815NraAR6QAQ1IAXmgDDSADjAEpsAC2ABH4Aa8gD8IAVsAC8SARMADGSAX7AKFoBiUgYOgBtSDJtAC2sEZ0A3Og8vgGrgF7oIxMAn4YAa8BIvgHViGIAgDkSAqJAUpQKqQNmQIMSEryBHygHyhECgcioa4UBqUC+2GiqFyqAZqgFqgX6Bz0GXoBjQCPYSmoHnob+gTjICJMA2Wg9VgPZgJ28LusD+8GY6Gk+FsuADeB1fBjfApuAu+DN+Cx2A+/BJeQgAEAUFHKCJ0EEyEPcILEYqIQvAQOxBFiEpEI6Id0YsYRNxD8BELiI9INJKKZCB1kBZIF2QAkoVMRu5AliBrkCeRXcgB5D3kFHIR+RVFQsmitFHmKFdUMCoalYEqRFWimlGdqKuoMdQM6h0ajaaj1dGmaBd0CDoOnYMuQR9Gd6AvoUfQ0+glDAYjhdHGWGK8MBGYVEwhphpzCnMRM4qZwXzAErAKWEOsEzYUy8XmYyuxrdg+7Ch2FruME8Wp4sxxXjg2LgtXimvC9eLu4GZwy3gxvDreEu+Pj8Pvwlfh2/FX8Y/xbwgEghLBjOBDiCXsJFQRThOuE6YIH4kUohbRnhhGTCPuI54gXiI+JL4hkUhqJBtSKCmVtI/UQrpCekr6IEIV0RVxFWGL5InUinSJjIq8IuPIqmRb8hZyNrmSfJZ8h7wgihNVE7UXjRDdIVorek50QnRJjCpmIOYllihWItYqdkNsjoKhqFEcKWxKAeUY5QplmoqgKlPtqSzqbmoT9Sp1hoamqdNcaXG0YtrPtGHaojhF3Eg8UDxTvFb8gjifjqCr0V3pCfRS+hn6OP2ThJyErQRHYq9Eu8SoxHtJGUkbSY5kkWSH5JjkJymGlKNUvNR+qW6pJ9JIaS1pH+kM6SPSV6UXZGgyFjIsmSKZMzKPZGFZLVlf2RzZY7JDskty8nLOckly1XJX5Bbk6fI28nHyFfJ98vMKVAUrhViFCoWLCi8Y4gxbRgKjijHAWFSUVXRRTFNsUBxWXFZSVwpQylfqUHqijFdmKkcpVyj3Ky+qKKh4quSqtKk8UsWpMlVjVA+pDqq+V1NXC1Lbo9atNqcuqe6qnq3epv5Yg6RhrZGs0ahxXxOtydSM1zyseVcL1jLWitGq1bqjDWubaMdqH9YeWYdaZ7aOu65x3YQOUcdWJ12nTWdKl67roZuv2637Sk9FL1Rvv96g3ld9Y/0E/Sb9SQOKgZtBvkGvwd+GWoYsw1rD++tJ653W563vWf/aSNuIY3TE6IEx1djTeI9xv/EXE1MTnkm7ybypimm4aZ3pBJPG9GaWMK+boczszPLMzpt9NDcxTzU/Y/6XhY5FvEWrxdwG9Q2cDU0bpi2VLCMsGyz5VgyrcKujVnxrResI60brZzbKNmybZptZW03bONtTtq/s9O14dp127+3N7bfbX3JAODg7FDkMO1IcAxxrHJ86KTlFO7U5LTobO+c4X3JBubi77HeZcJVzZbm2uC66mbptdxtwJ7r7ude4P/PQ8uB59HrCnm6eBzwfb1TdyN3Y7QW8XL0OeD3xVvdO9v7NB+3j7VPr89zXwDfXd9CP6rfVr9Xvnb+df6n/ZIBGQFpAfyA5MCywJfB9kENQeRA/WC94e/CtEOmQ2JCeUExoYGhz6NImx00HN82EGYcVho1vVt+cufnGFuktCVsubCVvjdh6NhwVHhTeGv45wiuiMWIp0jWyLnKRZc86xHrJtmFXsOc5lpxyzmyUZVR51Fy0ZfSB6PkY65jKmIVY+9ia2NdxLnH1ce/jveJPxK8kBCV0JGITwxPPcSnceO7ANvltmdtGkrSTCpP4yebJB5MXee685hQoZXNKTypt9Sc9lKaR9kPaVLpVem36h4zAjLOZYpnczKEsray9WbPZTtnHc5A5rJz+XMXcXblT2223N+yAdkTu6M9TzivIm9npvPPkLvyu+F238/Xzy/Pf7g7a3VsgV7CzYPoH5x/aCkUKeYUTeyz21P+I/DH2x+G96/dW7/1axC66WaxfXFn8uYRVcvMng5+qflrZF7VvuNSk9EgZuoxbNr7fev/JcrHy7PLpA54HuioYFUUVbw9uPXij0qiy/hD+UNohfpVHVU+1SnVZ9eeamJqxWrvajjrZur117w+zD48esTnSXi9XX1z/6Wjs0QcNzg1djWqNlcfQx9KPPW8KbBo8zjze0izdXNz85QT3BP+k78mBFtOWllbZ1tI2uC2tbf5U2Km7Pzv83NOu097QQe8oPg1Op51+8Uv4L+Nn3M/0n2Webf9V9de6TmpnURfUldW12B3Tze8J6Rk553auv9eit/M33d9OnFc8X3tB/EJpH76voG/lYvbFpUtJlxYuR1+e7t/aP3kl+Mr9AZ+B4avuV69fc7p2ZdB28OJ1y+vnb5jfOHeTebP7lsmtriHjoc7bxrc7h02Gu+6Y3um5a3a3d2TDSN+o9ejlew73rt13vX9rbOPYyHjA+IOJsAn+A/aDuYcJD18/Sn+0PLnzMepx0RPRJ5VPZZ82/q75ewffhH9hymFq6Jnfs8lp1vTLP1L++DxT8Jz0vHJWYbZlznDu/LzT/N0Xm17MvEx6ubxQ+KfYn3WvNF79+pfNX0OLwYszr3mvV/4ueSP15sRbo7f9S95LT98lvlt+X/RB6sPJj8yPg5+CPs0uZ3zGfK76ovml96v718criSsrQhcQuoDQBYQuIHQBoQsIXUDoAkIXELqA0AWELiB0AaELCF3g/9gF1s5xVoMQXI5NAOCfA4DHbQCqawBQiwKAHJbKyUwVjHK3MVjbkrJ4sdExqesYaSkcRhSPw0nIAvh/AHXlhbUKGwAA
        """;

    internal static SKBitmap ReadImage(JavaFile file) => ReadImage(file.OpenFileInfo());

    internal static SKBitmap ReadImage(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        try
        {
            return DecodeImage(File.ReadAllBytes(file.FullName));
        }
        catch (IOException error)
        {
            throw new IOException($"Unsupported or invalid image: {file.FullName}", error);
        }
    }

    internal static SKBitmap ReadImage(Stream stream)
    {
        ArgumentNullException.ThrowIfNull(stream);
        using var encoded = new MemoryStream();
        stream.CopyTo(encoded);
        return DecodeImage(encoded.ToArray());
    }

    internal static SKBitmap DecodeImage(byte[] encoded, int imageIndex = 0)
    {
        if (imageIndex < 0)
            throw new IndexOutOfRangeException(
                "Image index must not be negative.");
        var sixteenBitPng = DecodeSixteenBitPng(encoded);
        if (sixteenBitPng is not null)
        {
            if (imageIndex == 0) return sixteenBitPng;
            sixteenBitPng.Dispose();
            throw new IndexOutOfRangeException(
                "This image stream exposes a single image.");
        }
        SKBitmap? bitmap;
        if (IsTiff(encoded))
        {
            bitmap = DecodeTiff(encoded, imageIndex);
        }
        else
        {
            if (imageIndex != 0)
                throw new IndexOutOfRangeException(
                    "This image stream exposes a single image.");
            try
            {
                bitmap = DecodeSkia(encoded);
            }
            catch (ArgumentNullException)
            {
                bitmap = null;
            }
        }
        if (bitmap is null) throw new IOException("Unsupported or invalid image stream.");
        if (!IsTiff(encoded))
        {
            RegisterDecodedImageType(
                bitmap,
                IsGif(encoded)
                    ? InferGifImageType(encoded)
                    : InferImageType(bitmap),
                encoded);
        }
        return bitmap;
    }

    // Java's PNG reader gives a PNG with an alpha channel (colour type 4 or
    // 6) a TRANSLUCENT colour model whatever its alpha samples; other decoded
    // images are classified from their pixels on first use.
    internal static void RegisterDecodedImageType(SKBitmap bitmap, int type, byte[] encoded)
    {
        ReadOnlySpan<byte> png = [137, 80, 78, 71, 13, 10, 26, 10];
        if (encoded.Length > 25 && encoded.AsSpan(0, png.Length).SequenceEqual(png) &&
            encoded[25] is 4 or 6 && bitmap.AlphaType != SKAlphaType.Opaque)
        {
            ImageMetadataByBitmap.Set(
                bitmap,
                new ImageMetadata(type, transparency: PdfCartonTransparency.TRANSLUCENT));
            return;
        }
        RegisterImageType(bitmap, type);
    }

    private static SKBitmap? DecodeSixteenBitPng(byte[] encoded)
    {
        ReadOnlySpan<byte> signature =
            [137, 80, 78, 71, 13, 10, 26, 10];
        if (encoded.Length < 33 ||
            !encoded.AsSpan(0, signature.Length).SequenceEqual(signature))
        {
            return null;
        }

        static int ReadInt32(byte[] bytes, int offset) => checked((int)(
            (uint)bytes[offset] << 24 |
            (uint)bytes[offset + 1] << 16 |
            (uint)bytes[offset + 2] << 8 |
            bytes[offset + 3]));

        var width = 0;
        var height = 0;
        var componentCount = 0;
        using var compressed = new MemoryStream();
        for (var offset = 8; offset + 12 <= encoded.Length;)
        {
            var length = ReadInt32(encoded, offset);
            var dataOffset = checked(offset + 8);
            var nextOffset = checked(dataOffset + length + 4);
            if (length < 0 || nextOffset > encoded.Length)
                throw new IOException("Invalid PNG chunk length.");
            var chunkType = encoded.AsSpan(offset + 4, 4);
            if (chunkType.SequenceEqual("IHDR"u8))
            {
                if (length != 13)
                    throw new IOException("Invalid PNG image header.");
                width = ReadInt32(encoded, dataOffset);
                height = ReadInt32(encoded, dataOffset + 4);
                if (encoded[dataOffset + 8] != 16)
                    return null;
                componentCount = encoded[dataOffset + 9] switch
                {
                    2 => 3,
                    6 => 4,
                    _ => 0
                };
                if (componentCount == 0)
                    return null;
                if (encoded[dataOffset + 10] != 0 ||
                    encoded[dataOffset + 11] != 0 ||
                    encoded[dataOffset + 12] != 0)
                {
                    return null;
                }
            }
            else if (chunkType.SequenceEqual("IDAT"u8))
            {
                compressed.Write(encoded, dataOffset, length);
            }
            else if (chunkType.SequenceEqual("IEND"u8))
            {
                break;
            }
            offset = nextOffset;
        }
        if (width <= 0 || height <= 0 ||
            componentCount == 0 || compressed.Length == 0)
        {
            throw new IOException("Incomplete 16-bit PNG image.");
        }

        var compressedBytes = compressed.ToArray();
        if (compressedBytes.Length < 6 ||
            (compressedBytes[0] & 0x0f) != 8 ||
            ((compressedBytes[0] << 8) + compressedBytes[1]) % 31 != 0 ||
            (compressedBytes[1] & 0x20) != 0)
        {
            throw new IOException("Invalid PNG zlib stream.");
        }
        using var inflated = new MemoryStream();
        using (var deflateSource = new MemoryStream(
                   compressedBytes,
                   2,
                   compressedBytes.Length - 6,
                   writable: false))
        using (var zlib = new DeflateStream(
                   deflateSource,
                   CompressionMode.Decompress,
                   leaveOpen: false))
        {
            zlib.CopyTo(inflated);
        }
        var filtered = inflated.ToArray();
        var expectedAdler =
            (uint)compressedBytes[^4] << 24 |
            (uint)compressedBytes[^3] << 16 |
            (uint)compressedBytes[^2] << 8 |
            compressedBytes[^1];
        if (Adler32(filtered) != expectedAdler)
            throw new IOException("PNG zlib checksum does not match its image data.");
        var bytesPerPixel = checked(componentCount * 2);
        var rowLength = checked(width * bytesPerPixel);
        if (filtered.Length != checked((rowLength + 1) * height))
            throw new IOException("Unexpected 16-bit PNG scanline length.");

        var raster = new JavaRaster(
            DATA_BUFFER_TYPE_USHORT,
            width,
            height,
            componentCount);
        var previous = new byte[rowLength];
        var current = new byte[rowLength];
        var sourceOffset = 0;
        var pixel = new short[componentCount];
        for (var y = 0; y < height; y++)
        {
            var filter = filtered[sourceOffset++];
            Array.Copy(filtered, sourceOffset, current, 0, rowLength);
            sourceOffset += rowLength;
            UnfilterPngRow(current, previous, bytesPerPixel, filter);
            for (var x = 0; x < width; x++)
            {
                var pixelOffset = x * bytesPerPixel;
                for (var component = 0; component < componentCount; component++)
                {
                    var componentOffset = pixelOffset + component * 2;
                    pixel[component] = unchecked((short)(ushort)(
                        current[componentOffset] << 8 |
                        current[componentOffset + 1]));
                }
                raster.SetDataElements(x, y, pixel);
            }
            (previous, current) = (current, previous);
        }

        var hasAlpha = componentCount == 4;
        var colorModel = new JavaColorModel(
            GetColorSpace(JavaColorSpace.CS_sRGB),
            hasAlpha,
            DATA_BUFFER_TYPE_USHORT,
            Enumerable.Repeat(16, componentCount).ToArray(),
            hasAlpha
                ? PdfCartonTransparency.TRANSLUCENT
                : PdfCartonTransparency.OPAQUE);
        return CreateImage(colorModel, raster, false, null);
    }

    private static uint Adler32(byte[] value)
    {
        const uint modulus = 65521;
        uint first = 1;
        uint second = 0;
        foreach (var item in value)
        {
            first = (first + item) % modulus;
            second = (second + first) % modulus;
        }
        return second << 16 | first;
    }

    private static void UnfilterPngRow(
        byte[] current,
        byte[] previous,
        int bytesPerPixel,
        byte filter)
    {
        for (var index = 0; index < current.Length; index++)
        {
            var left = index >= bytesPerPixel
                ? current[index - bytesPerPixel]
                : 0;
            var above = previous[index];
            var upperLeft = index >= bytesPerPixel
                ? previous[index - bytesPerPixel]
                : 0;
            var predictor = filter switch
            {
                0 => 0,
                1 => left,
                2 => above,
                3 => (left + above) / 2,
                4 => PngPaeth(left, above, upperLeft),
                _ => throw new IOException("Unsupported PNG scanline filter.")
            };
            current[index] = unchecked((byte)(current[index] + predictor));
        }
    }

    private static int PngPaeth(int left, int above, int upperLeft)
    {
        var estimate = left + above - upperLeft;
        var leftDistance = Math.Abs(estimate - left);
        var aboveDistance = Math.Abs(estimate - above);
        var upperLeftDistance = Math.Abs(estimate - upperLeft);
        return leftDistance <= aboveDistance && leftDistance <= upperLeftDistance
            ? left
            : aboveDistance <= upperLeftDistance ? above : upperLeft;
    }

    private static bool IsGif(byte[] encoded) =>
        encoded.Length >= 13 &&
        encoded[0] == (byte)'G' &&
        encoded[1] == (byte)'I' &&
        encoded[2] == (byte)'F' &&
        encoded[3] == (byte)'8' &&
        encoded[5] == (byte)'a';

    private static int InferGifImageType(byte[] encoded)
    {
        var packedFields = encoded[10];
        var hasGlobalColorTable = (packedFields & 0x80) != 0;
        var paletteSize = hasGlobalColorTable
            ? 1 << ((packedFields & 0x07) + 1)
            : 0;
        return paletteSize == 2 ? TYPE_BYTE_BINARY : TYPE_CUSTOM;
    }

    private static SKBitmap? DecodeSkia(byte[] encoded)
    {
        using var data = SKData.CreateCopy(encoded);
        using var codec = SKCodec.Create(data);
        if (codec is null) return null;
        var source = codec.Info;
        var info = new SKImageInfo(
            source.Width,
            source.Height,
            source.ColorType == SKColorType.Gray8
                ? SKColorType.Gray8
                : SKColorType.Bgra8888,
            source.AlphaType == SKAlphaType.Opaque
                ? SKAlphaType.Opaque
                : SKAlphaType.Unpremul,
            source.ColorSpace);
        var bitmap = new PdfCartonNativeBitmap(info);
        var result = codec.GetPixels(info, bitmap.GetPixels());
        if (result == SKCodecResult.Success || result == SKCodecResult.IncompleteInput)
            return bitmap;
        bitmap.Dispose();
        return null;
    }

    private static bool IsTiff(byte[] encoded) =>
        encoded.Length >= 4 &&
        ((encoded[0] == (byte)'I' && encoded[1] == (byte)'I' &&
          encoded[2] == 42 && encoded[3] == 0) ||
         (encoded[0] == (byte)'M' && encoded[1] == (byte)'M' &&
          encoded[2] == 0 && encoded[3] == 42));

    private static SKBitmap DecodeTiff(byte[] encoded, int imageIndex)
    {
        var littleEndian = encoded[0] == (byte)'I';
        ushort U16(int offset)
        {
            if ((uint)offset > encoded.Length - 2) throw new IOException("Truncated TIFF field.");
            return littleEndian
                ? (ushort)(encoded[offset] | encoded[offset + 1] << 8)
                : (ushort)(encoded[offset] << 8 | encoded[offset + 1]);
        }
        uint U32(int offset)
        {
            if ((uint)offset > encoded.Length - 4) throw new IOException("Truncated TIFF field.");
            return littleEndian
                ? (uint)(encoded[offset] | encoded[offset + 1] << 8 |
                         encoded[offset + 2] << 16 | encoded[offset + 3] << 24)
                : (uint)(encoded[offset] << 24 | encoded[offset + 1] << 16 |
                         encoded[offset + 2] << 8 | encoded[offset + 3]);
        }

        var ifdOffset = checked((int)U32(4));
        for (var currentIndex = 0; currentIndex < imageIndex; currentIndex++)
        {
            var currentEntryCount = U16(ifdOffset);
            var nextOffsetLocation = checked(ifdOffset + 2 + currentEntryCount * 12);
            var nextIfdOffset = U32(nextOffsetLocation);
            if (nextIfdOffset == 0)
                throw new IndexOutOfRangeException(
                    $"TIFF image index {imageIndex} does not exist.");
            ifdOffset = checked((int)nextIfdOffset);
        }
        var entryCount = U16(ifdOffset);
        var tags = new Dictionary<ushort, uint[]>();
        for (var index = 0; index < entryCount; index++)
        {
            var entry = checked(ifdOffset + 2 + index * 12);
            var tag = U16(entry);
            var type = U16(entry + 2);
            var count = checked((int)U32(entry + 4));
            var elementSize = type switch { 1 => 1, 3 => 2, 4 => 4, _ => 0 };
            if (elementSize == 0) continue;
            var byteCount = checked(count * elementSize);
            var valueOffset = byteCount <= 4 ? entry + 8 : checked((int)U32(entry + 8));
            var values = new uint[count];
            for (var valueIndex = 0; valueIndex < count; valueIndex++)
            {
                var offset = checked(valueOffset + valueIndex * elementSize);
                values[valueIndex] = type switch
                {
                    1 => offset < encoded.Length
                        ? encoded[offset]
                        : throw new IOException("Truncated TIFF byte field."),
                    3 => U16(offset),
                    _ => U32(offset)
                };
            }
            tags[tag] = values;
        }

        uint Required(ushort tag) =>
            tags.TryGetValue(tag, out var values) && values.Length != 0
                ? values[0]
                : throw new IOException($"Required TIFF tag {tag} is missing.");
        uint Optional(ushort tag, uint fallback) =>
            tags.TryGetValue(tag, out var values) && values.Length != 0
                ? values[0]
                : fallback;

        var width = checked((int)Required(256));
        var height = checked((int)Required(257));
        var bits = tags.TryGetValue(258, out var bitValues) ? bitValues : new uint[] { 1 };
        var compression = Optional(259, 1);
        var photometric = Optional(262, 1);
        var fillOrder = Optional(266, 1);
        var samples = checked((int)Optional(277, 1));
        var rowsPerStrip = checked((int)Optional(278, (uint)height));
        var planar = Optional(284, 1);
        var predictor = Optional(317, 1);
        var t4Options = Optional(292, 0);
        var t6Options = Optional(293, 0);
        if (width <= 0 || height <= 0 || rowsPerStrip <= 0 ||
            planar != 1 || bits.Length != samples ||
            bits.Any(value => value != bits[0]) ||
            bits[0] is not 1 and not 8 ||
            samples is not 1 and not 3 and not 4 ||
            compression is not 1 and not 2 and not 3 and not 4 and not 5 ||
            fillOrder is not 1 and not 2 ||
            (compression is 2 or 3 or 4 && (bits[0] != 1 || samples != 1)))
        {
            throw new IOException("Unsupported TIFF pixel layout.");
        }
        if (!tags.TryGetValue(273, out var stripOffsets) ||
            !tags.TryGetValue(279, out var stripByteCounts) ||
            stripOffsets.Length != stripByteCounts.Length)
        {
            throw new IOException("Invalid TIFF strip table.");
        }

        var rowBytes = checked((width * samples * (int)bits[0] + 7) / 8);
        var pixels = new byte[checked(rowBytes * height)];
        var destinationOffset = 0;
        for (var strip = 0; strip < stripOffsets.Length; strip++)
        {
            var offset = checked((int)stripOffsets[strip]);
            var count = checked((int)stripByteCounts[strip]);
            if (offset < 0 || count < 0 || offset > encoded.Length - count)
                throw new IOException("Truncated TIFF strip.");
            var packed = encoded.AsSpan(offset, count).ToArray();
            var rows = Math.Min(rowsPerStrip, height - strip * rowsPerStrip);
            var required = checked(rows * rowBytes);
            if (fillOrder == 2)
            {
                for (var byteIndex = 0; byteIndex < packed.Length; byteIndex++)
                    packed[byteIndex] = ReverseBits(packed[byteIndex]);
            }
            var unpacked = compression switch
            {
                2 or 3 or 4 => DecodeTiffCcitt(
                    packed,
                    width,
                    rows,
                    checked((int)compression),
                    compression == 3 ? t4Options : t6Options),
                5 => DecodeTiffLzw(packed),
                _ => packed
            };
            if (unpacked.Length < required) throw new IOException("Truncated TIFF pixel strip.");
            Buffer.BlockCopy(unpacked, 0, pixels, destinationOffset, required);
            destinationOffset += required;
        }
        if (destinationOffset < pixels.Length) throw new IOException("Incomplete TIFF image.");

        if (predictor == 2 && bits[0] == 8)
        {
            for (var y = 0; y < height; y++)
            {
                var row = y * rowBytes;
                for (var x = samples; x < rowBytes; x++)
                    pixels[row + x] = unchecked((byte)(pixels[row + x] + pixels[row + x - samples]));
            }
        }
        else if (predictor != 1)
        {
            throw new IOException("Unsupported TIFF predictor.");
        }

        var imageType = bits[0] == 1
            ? TYPE_BYTE_BINARY
            : samples == 1 ? TYPE_BYTE_GRAY : samples == 4 ? TYPE_4BYTE_ABGR : TYPE_3BYTE_BGR;
        var result = CreateBitmap(width, height, imageType);
        using var output = new PdfCartonPixels(result);
        for (var y = 0; y < height; y++)
        {
            for (var x = 0; x < width; x++)
            {
                byte red;
                byte green;
                byte blue;
                byte alpha = 255;
                if (bits[0] == 1)
                {
                    var set = (pixels[y * rowBytes + x / 8] & (1 << (7 - x % 8))) != 0;
                    var value = (byte)((set ^ (photometric == 0)) ? 255 : 0);
                    red = green = blue = value;
                }
                else
                {
                    var pixel = y * rowBytes + x * samples;
                    if (samples == 1)
                    {
                        var value = pixels[pixel];
                        if (photometric == 0) value = (byte)(255 - value);
                        red = green = blue = value;
                    }
                    else
                    {
                        red = pixels[pixel];
                        green = pixels[pixel + 1];
                        blue = pixels[pixel + 2];
                        if (samples == 4) alpha = pixels[pixel + 3];
                    }
                }
                output.Set(x, y, new SKColor(red, green, blue, alpha));
            }
        }
        return result;
    }

    private static byte ReverseBits(byte value)
    {
        value = (byte)((value >> 4) | (value << 4));
        value = (byte)(((value & 0xcc) >> 2) | ((value & 0x33) << 2));
        return (byte)(((value & 0xaa) >> 1) | ((value & 0x55) << 1));
    }

    private static byte[] DecodeTiffCcitt(
        byte[] encoded,
        int width,
        int rows,
        int compression,
        uint options)
    {
#if DRIPSHARP_PDFBOX_CRYPTO
        var rowBytes = checked((width + 7) / 8);
        var result = new byte[checked(rowBytes * rows)];
        using var source = new MemoryStream(encoded, writable: false);
        using var decoder =
            new DripSharp.PdfCarton.Filter.CCITTFaxDecoderStream(
                source,
                width,
                compression,
                options,
                byteAligned: false);
        var offset = 0;
        while (offset < result.Length)
        {
            var read = decoder.Read(result, offset, result.Length - offset);
            if (read <= 0)
                throw new IOException("Truncated TIFF CCITT strip.");
            offset += read;
        }
        return result;
#else
        _ = encoded;
        _ = width;
        _ = rows;
        _ = compression;
        _ = options;
        throw new IOException(
            "CCITT-compressed TIFF images require the PdfCarton PDF assembly.");
#endif
    }

    private static byte[] DecodeTiffLzw(byte[] encoded)
    {
        var dictionary = new byte[4096][];
        var nextCode = 258;
        var codeWidth = 9;
        void Reset()
        {
            Array.Clear(dictionary, 0, dictionary.Length);
            for (var value = 0; value < 256; value++) dictionary[value] = [(byte)value];
            nextCode = 258;
            codeWidth = 9;
        }
        var bitOffset = 0;
        int ReadCode()
        {
            if (bitOffset + codeWidth > encoded.Length * 8) return -1;
            var code = 0;
            for (var bit = 0; bit < codeWidth; bit++, bitOffset++)
                code = code << 1 | (encoded[bitOffset / 8] >> (7 - bitOffset % 8) & 1);
            return code;
        }
        static byte[] Extend(byte[] prefix, byte value)
        {
            var result = new byte[prefix.Length + 1];
            Buffer.BlockCopy(prefix, 0, result, 0, prefix.Length);
            result[^1] = value;
            return result;
        }

        Reset();
        using var output = new MemoryStream();
        byte[]? previous = null;
        while (true)
        {
            var code = ReadCode();
            if (code < 0 || code == 257) break;
            if (code == 256)
            {
                Reset();
                previous = null;
                continue;
            }
            byte[] entry;
            if (code < nextCode && dictionary[code] is not null)
                entry = dictionary[code];
            else if (code == nextCode && previous is not null)
                entry = Extend(previous, previous[0]);
            else
                throw new IOException("Invalid TIFF LZW code.");
            output.Write(entry, 0, entry.Length);
            if (previous is not null && nextCode < dictionary.Length)
            {
                dictionary[nextCode++] = Extend(previous, entry[0]);
                if (codeWidth < 12 && nextCode == (1 << codeWidth) - 1) codeWidth++;
            }
            previous = entry;
        }
        return output.ToArray();
    }

    internal static SKBitmap ScaleImage(
        SKBitmap source,
        int width,
        int height,
        int hints)
    {
        ArgumentNullException.ThrowIfNull(source);
        if (width < 0 && height < 0)
            throw new ArgumentException(
                "At least one scaled image dimension must be positive.");
        if (width < 0)
            width = Math.Max(1, (int)Math.Round(
                source.Width * (double)height / source.Height));
        if (height < 0)
            height = Math.Max(1, (int)Math.Round(
                source.Height * (double)width / source.Width));
        if (width == 0 || height == 0)
            throw new ArgumentException("Scaled image dimensions cannot be zero.");

        var destination = new PdfCartonNativeBitmap(new SKImageInfo(
            width,
            height,
            source.ColorType,
            source.AlphaType,
            source.ColorSpace));
        PdfCartonBitmapView.Publish(source);
        using var canvas = new SKCanvas(destination);
        using var paint = new SKPaint();
        var sampling = hints == SCALE_SMOOTH
            ? new SKSamplingOptions(SKCubicResampler.Mitchell)
            : SKSamplingOptions.Default;
        using var image = DrawableImage(source, destination);
        canvas.DrawImage(image!, new SKRect(0, 0, width, height), sampling, paint);
        return destination;
    }

    internal static void SetImageIoUseCache(bool useCache)
    {
        // Skia decodes directly from the supplied stream and does not use
        // ImageIO's optional on-disk cache.
        _ = useCache;
    }

    // new BufferedImage(width, height, type). TYPE_INT_RGB and TYPE_INT_ARGB
    // images keep their pixels in the int array DataBufferInt.getData()
    // returns, as in Java.
    internal static SKBitmap CreateBitmap(int width, int height, int type) =>
        CreateBitmap(width, height, type, intBacked: true);

    // A compat-internal intermediate whose storage no Java code reads.
    internal static SKBitmap CreateNativeBitmap(int width, int height, int type) =>
        CreateBitmap(width, height, type, intBacked: false);

    private static SKBitmap CreateBitmap(int width, int height, int type, bool intBacked)
    {
        if (width <= 0 || height <= 0)
            throw new ArgumentException("Bitmap dimensions must be positive.");

        var colorType = type is TYPE_BYTE_GRAY or TYPE_BYTE_BINARY
            ? SKColorType.Gray8
            : SKColorType.Bgra8888;
        var alphaType = type is TYPE_INT_ARGB or TYPE_4BYTE_ABGR
            ? SKAlphaType.Unpremul
            : SKAlphaType.Opaque;
        var info = new SKImageInfo(width, height, colorType, alphaType);
        var bitmap = intBacked && type is (TYPE_INT_RGB or TYPE_INT_ARGB) && BitConverter.IsLittleEndian
            ? PdfCartonNativeBitmap.CreateIntBacked(info)
            : new PdfCartonNativeBitmap(info);
        bitmap.Erase(
            alphaType == SKAlphaType.Opaque
                ? SKColors.Black
                : SKColors.Transparent);
        // As in Java, the type fixes the colour model and so the
        // transparency: no later call reads the pixels to classify it.
        ImageMetadataByBitmap.Set(bitmap, new ImageMetadata(type, new JavaColorModel(type)));
        return bitmap;
    }

    internal static int GetImageType(SKBitmap bitmap)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        return ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata)
            ? metadata.Type
            : InferImageType(bitmap);
    }

    internal static JavaRaster GetRaster(SKBitmap bitmap)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        while (true)
        {
            ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata);
            // A retained view of pixels since reallocated is replaced.
            if (metadata?.Raster is { } retained && retained.HasCurrentPixels)
                return retained;
            var imageType = metadata?.Type ?? InferImageType(bitmap);
            var raster = imageType == TYPE_BYTE_BINARY
                ? JavaRaster.BinarySnapshot(bitmap)
                : new JavaRaster(bitmap);
            if (imageType is TYPE_BYTE_BINARY or TYPE_CUSTOM)
                return raster;
            var colorModel = metadata?.ColorModel ?? new JavaColorModel(
                imageType,
                metadata?.Transparency ?? GetTransparency(bitmap));
            var updated = new ImageMetadata(
                imageType,
                colorModel,
                raster,
                metadata?.SampleModel,
                metadata?.Transparency);
            // On a lost race, use the type or raster another thread installed.
            if (ImageMetadataByBitmap.TryReplace(bitmap, metadata, updated))
                return raster;
        }
    }

    internal static bool HasManagedImageData(SKBitmap bitmap)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        return ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata) &&
               metadata.Raster is not null &&
               metadata.ColorModel is not null;
    }

    internal static JavaRaster GetImageData(SKBitmap bitmap)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        if (ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata) &&
            metadata.Raster is not null)
        {
            return metadata.Raster.DeepCopy();
        }
        return GetImageType(bitmap) == TYPE_BYTE_BINARY
            ? JavaRaster.BinarySnapshot(bitmap)
            : new JavaRaster(bitmap).DeepCopy();
    }

    // BufferedImage.getTransparency(): the colour model's transparency, so
    // pixels are never read for an image with a colour model, which
    // CreateBitmap gives every new BufferedImage (Java's TYPE_INT_ARGB and
    // TYPE_4BYTE_ABGR are TRANSLUCENT whatever they hold), nor for a foreign
    // bitmap, whose inferred type is TYPE_INT_ARGB. Only a decoded image
    // registered without a colour model, as a GIF or paletted PNG, whose
    // Java IndexColorModel is BITMASK when every alpha is 0 or 255, is
    // classified from its pixels, once.
    internal static int GetTransparency(SKBitmap bitmap)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata);
        if (metadata?.Transparency is { } transparency) return transparency;
        if (metadata?.ColorModel is { } colorModel) return colorModel.Transparency;
        if (bitmap.AlphaType == SKAlphaType.Opaque)
            return PdfCartonTransparency.OPAQUE;
        return metadata is null
            ? PdfCartonTransparency.TRANSLUCENT
            : PixelTransparency(bitmap, metadata);
    }

    private static int PixelTransparency(SKBitmap bitmap, ImageMetadata metadata)
    {
        var known = Volatile.Read(ref metadata.PixelTransparency);
        if (known != 0) return known;
        lock (metadata)
        {
            known = metadata.PixelTransparency;
            if (known != 0) return known;
            var classified = PdfCartonTransparency.BITMASK;
            using (var access = new PdfCartonPixels(bitmap))
            {
                for (var y = 0; y < bitmap.Height && classified == PdfCartonTransparency.BITMASK; y++)
                    for (var x = 0; x < bitmap.Width; x++)
                        if (access.Get(x, y).Alpha is not 0 and not byte.MaxValue)
                        {
                            classified = PdfCartonTransparency.TRANSLUCENT;
                            break;
                        }
            }
            Volatile.Write(ref metadata.PixelTransparency, classified);
            return classified;
        }
    }

    internal static JavaColorModel GetColorModel(SKBitmap bitmap)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        return ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata) &&
               metadata.ColorModel is not null
            ? metadata.ColorModel
            : new JavaColorModel(
                GetImageType(bitmap),
                metadata?.Transparency ?? GetTransparency(bitmap));
    }

    internal static JavaSampleModel GetSampleModel(SKBitmap bitmap)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        if (ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata) &&
            metadata.SampleModel is not null)
        {
            return metadata.SampleModel;
        }
        return GetImageType(bitmap) == TYPE_BYTE_BINARY
            ? new JavaMultiPixelPackedSampleModel(1)
            : new JavaSampleModel();
    }

    internal static JavaRaster? GetAlphaRaster(SKBitmap bitmap)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        // An indexed model's alpha is in its colour map, not in a band, so
        // like Java it has no alpha raster.
        var colorModel = GetColorModel(bitmap);
        if (!colorModel.HasAlpha || colorModel.Palette is not null) return null;
        EnsureNativePixels(bitmap);
        var raster = new JavaRaster(
            DATA_BUFFER_TYPE_BYTE,
            bitmap.Width,
            bitmap.Height,
            1);
        if (bitmap.ColorType is SKColorType.Bgra8888 or SKColorType.Rgba8888)
        {
            // Alpha is the fourth byte of every pixel, premultiplied or not.
            var alpha = raster.GetByteData();
            var pixels = bitmap.GetPixelSpan();
            var rowBytes = bitmap.RowBytes;
            var opaque = bitmap.AlphaType == SKAlphaType.Opaque;
            for (var y = 0; y < bitmap.Height; y++)
            {
                var input = pixels.Slice(y * rowBytes, bitmap.Width * 4);
                var output = alpha.AsSpan(y * bitmap.Width, bitmap.Width);
                for (var x = 0; x < output.Length; x++)
                    output[x] = unchecked((sbyte)(opaque ? byte.MaxValue : input[x * 4 + 3]));
            }
            GC.KeepAlive(bitmap);
            return raster;
        }
        using var access = new PdfCartonPixels(bitmap);
        var sample = new int[1];
        for (var y = 0; y < bitmap.Height; y++)
        {
            for (var x = 0; x < bitmap.Width; x++)
            {
                sample[0] = access.Get(x, y).Alpha;
                raster.SetPixel(x, y, sample);
            }
        }
        return raster;
    }

    internal static void SetImageData(SKBitmap bitmap, JavaRaster raster)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        ArgumentNullException.ThrowIfNull(raster);
        if (bitmap.Width != raster.Width || bitmap.Height != raster.Height)
            throw new ArgumentException(
                "Raster dimensions must match the destination image.",
                nameof(raster));
        if (ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata) &&
            metadata.Raster is not null &&
            metadata.ColorModel is not null)
        {
            var retainedRaster = metadata.Raster;
            if (!ReferenceEquals(retainedRaster, raster))
            {
                if (retainedRaster.NumberOfBands != raster.NumberOfBands)
                    throw new ArgumentException(
                        "Raster band count must match the destination image.",
                        nameof(raster));
                var row = new int[checked(bitmap.Width * raster.NumberOfBands)];
                for (var y = 0; y < bitmap.Height; y++)
                    retainedRaster.SetPixels(0, y, bitmap.Width, 1,
                        raster.GetPixels(0, y, bitmap.Width, 1, row));
            }
            RenderRetainedRaster(bitmap, metadata);
            return;
        }
        var colorModel = new JavaColorModel(GetImageType(bitmap));
        RenderRaster(bitmap, colorModel, raster);
    }

    // Re-renders a bitmap's retained raster. A live view of the bitmap's own
    // pixels under its image-type model already holds the rendered result.
    private static void RenderRetainedRaster(SKBitmap bitmap, ImageMetadata metadata)
    {
        lock (metadata)
        {
            if (!RendersAsStored(bitmap, metadata.ColorModel!, metadata.Raster!))
                RenderRaster(bitmap, metadata.ColorModel!, metadata.Raster!);
            metadata.NativePending = false;
            metadata.ExposedSamples?.CopyTo(metadata.RenderedSamples!, 0);
        }
    }

    // True when raster is the bitmap's live pixel view and model is the image-
    // type model of a format whose round trip through it is exact, so
    // rendering the raster would rewrite every native pixel unchanged.
    internal static bool RendersAsStored(
        SKBitmap bitmap,
        JavaColorModel colorModel,
        JavaRaster raster)
    {
        if (!raster.IsLiveViewOf(bitmap) || !colorModel.IsImageTypeModel) return false;
        if (bitmap.ColorType == SKColorType.Gray8)
            return colorModel.ImageType == TYPE_BYTE_GRAY && !colorModel.HasAlpha;
        if (bitmap.ColorType != SKColorType.Bgra8888) return false;
        return colorModel.ImageType switch
        {
            TYPE_INT_RGB or TYPE_3BYTE_BGR =>
                bitmap.AlphaType == SKAlphaType.Opaque && !colorModel.HasAlpha,
            TYPE_INT_ARGB or TYPE_4BYTE_ABGR =>
                bitmap.AlphaType == SKAlphaType.Unpremul && colorModel.HasAlpha,
            _ => false
        };
    }

    // Renders every pixel through the color model once: components are read a
    // row at a time into reused buffers and converted with one ToRgb per pixel.
    private static void RenderRaster(
        SKBitmap bitmap,
        JavaColorModel colorModel,
        JavaRaster raster)
    {
        if (TryRenderSampleRows(bitmap, colorModel, raster)) return;
        var width = bitmap.Width;
        var count = colorModel.NumberOfComponents;
        var alphaIndex = colorModel.NumberOfColorComponents;
        var hasAlpha = colorModel.HasAlpha;
        var colorSpace = colorModel.DrawingSpace;
        var components = new float[checked(width * count)];
        int[]? samples = null;
        object? pixel = null;
        Span<float> rgb = stackalloc float[3];
        using var output = new PdfCartonPixels(bitmap);
        for (var y = 0; y < bitmap.Height; y++)
        {
            colorModel.GetNormalizedRow(raster, y, components, ref samples, ref pixel);
            for (var x = 0; x < width; x++)
            {
                var pixelComponents = new ReadOnlySpan<float>(components, x * count, count);
                colorSpace.ConvertToRgb(pixelComponents, rgb);
                output.Set(
                    x,
                    y,
                    new SKColor(
                        ComponentByte(rgb[0]),
                        ComponentByte(rgb[1]),
                        ComponentByte(rgb[2]),
                        hasAlpha ? ComponentByte(pixelComponents[alphaIndex]) : byte.MaxValue));
            }
        }
    }

    // Bulk rows for managed samples whose rendered colors come from per-sample
    // tables (indexed, gray, sRGB and packed INT models): no per-pixel color
    // conversion, and whole rows written to the native pixels at once.
    private static bool TryRenderSampleRows(
        SKBitmap bitmap,
        JavaColorModel colorModel,
        JavaRaster raster)
    {
        if (raster.Width != bitmap.Width || raster.Height != bitmap.Height) return false;
        var converter = colorModel.CreateRowConverter(raster);
        if (converter is null) return false;
        var samples = new int[checked(raster.Width * raster.NumberOfBands)];
        var row = new SKColor[raster.Width];
        using var output = new PdfCartonPixels(bitmap);
        for (var y = 0; y < raster.Height; y++)
        {
            raster.ReadRowSamples(y, samples);
            converter.Convert(samples, row);
            output.SetRow(y, row);
        }
        return true;
    }

    internal static byte ComponentByte(float value) =>
        unchecked((byte)(int)JavaCompat.Round(JavaCompat.Clamp(value, 0f, 1f) * 255f));

    internal static SKBitmap CreateImage(
        JavaColorModel colorModel,
        JavaRaster raster,
        bool isRasterPremultiplied,
        object? properties)
    {
        var bitmap = CreateDeferredImage(
            colorModel,
            raster,
            isRasterPremultiplied,
            properties);
        try
        {
            EnsureNativePixels(bitmap);
        }
        catch
        {
            bitmap.Dispose();
            throw;
        }
        return bitmap;
    }

    // CreateImage without the eager native render, for an image the caller
    // owns and consumes through its retained raster. The native pixels are
    // rendered on first draw (EnsureNativePixels). Only pass such an image to
    // compat paths that read its raster or call EnsureNativePixels.
    internal static SKBitmap CreateDeferredImage(
        JavaColorModel colorModel,
        JavaRaster raster,
        bool isRasterPremultiplied,
        object? _)
    {
        ArgumentNullException.ThrowIfNull(colorModel);
        ArgumentNullException.ThrowIfNull(raster);
        var expectedBands = colorModel.RasterBandCount;
        if (raster.NumberOfBands != expectedBands)
            throw new ArgumentException(
                "Raster band count does not match the color model.",
                nameof(raster));
        var bitmap = new PdfCartonNativeBitmap(
            new SKImageInfo(
                raster.Width,
                raster.Height,
                SKColorType.Bgra8888,
                colorModel.HasAlpha
                    ? isRasterPremultiplied
                        ? SKAlphaType.Premul
                        : SKAlphaType.Unpremul
                    : SKAlphaType.Opaque));
        var sampleModel = raster.PackedPixelBits == 0
            ? new JavaSampleModel()
            : new JavaMultiPixelPackedSampleModel(raster.PackedPixelBits);
        ImageMetadataByBitmap.Set(
            bitmap,
            new ImageMetadata(TYPE_CUSTOM, colorModel, raster, sampleModel)
            {
                NativePending = true,
                DetachedRaster = true
            });
        return bitmap;
    }

    // True when bitmap is a CreateImage image, or one whose samples getData
    // exposed, whose retained raster is its Java storage: drawing surfaces
    // bracket native drawing into it with BeginNativeDrawing and compose
    // custom composites into the raster.
    internal static bool TracksNativeDrawing(SKBitmap bitmap) =>
        ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata) && metadata.DetachedRaster;

    // DataBufferByte.getData() moved the samples of raster, bitmap's retained
    // view, into samples. In Java that array is the image's storage, so from
    // now on it is, as a CreateImage raster is (DetachedRaster): native
    // drawing is copied into it, and every Publish renders the samples
    // written through the array since the last one (RenderChangedSamples).
    internal static void ExposeSamples(SKBitmap bitmap, JavaRaster raster, sbyte[] samples)
    {
        if (!ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata) ||
            !ReferenceEquals(metadata.Raster, raster) || metadata.ColorModel is null)
            return;
        lock (metadata)
        {
            metadata.ExposedSamples = samples;
            metadata.RenderedSamples = (sbyte[])samples.Clone();
            metadata.DetachedRaster = true;
        }
        PdfCartonBitmapView.SetRefresh(bitmap, () => RenderChangedSamples(bitmap, raster));
    }

    // Records that native pixel (x, y) shows its exposed samples, after a
    // compat write rendered them. Called under a lock on metadata.
    private static void MarkRendered(ImageMetadata metadata, int x, int y)
    {
        if (metadata.RenderedSamples is not { } rendered) return;
        var bands = metadata.Raster!.NumberOfBands;
        var start = (y * metadata.Raster.Width + x) * bands;
        Array.Copy(metadata.ExposedSamples!, start, rendered, start, bands);
    }

    // Renders, and only renders, the pixels whose samples differ from those
    // the native pixels show, so native drawing the samples never saw
    // survives. Equal rows cost one vectorized comparison, and the writes go
    // through a cached native view: no per-pixel native call.
    private static void RenderChangedSamples(SKBitmap bitmap, JavaRaster raster)
    {
        if (bitmap.Handle == IntPtr.Zero ||
            !ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata) ||
            !ReferenceEquals(metadata.Raster, raster))
            return;
        lock (metadata)
        {
            if (metadata.ExposedSamples is not { } samples || metadata.RenderedSamples is not { } published)
                return;
            var colorModel = metadata.ColorModel!;
            var bands = raster.NumberOfBands;
            var rowLength = raster.Width * bands;
            PdfCartonBitmapView? view = null;
            int[]? pixelSamples = null;
            object? pixel = null;
            for (var y = 0; y < raster.Height; y++)
            {
                var row = samples.AsSpan(y * rowLength, rowLength);
                var shown = published.AsSpan(y * rowLength, rowLength);
                if (row.SequenceEqual(shown)) continue;
                view ??= NativeView(bitmap, metadata);
                for (int x = 0, start = 0; x < raster.Width; x++, start += bands)
                {
                    var sample = row.Slice(start, bands);
                    if (sample.SequenceEqual(shown.Slice(start, bands))) continue;
                    sample.CopyTo(shown.Slice(start, bands));
                    view.Set(x, y, colorModel.RenderedColorAt(raster, x, y, ref pixelSamples, ref pixel));
                }
            }
        }
    }

    // Starts a native (Skia) draw into bounds of a CreateImage image. In Java
    // the raster is the image's only storage, so disposing the result copies
    // every pixel the draw changed into the retained raster, converted into
    // its colour model as SetRgb converts a colour, and renders those pixels
    // back from the raster as SetRgb renders them. Pixels the draw left
    // alone keep their samples, including raster writes not yet rendered.
    // Null for other images. The native pixels are rendered first, so a
    // later deferred render cannot overwrite the drawing.
    internal static IDisposable? BeginNativeDrawing(SKBitmap bitmap, SKRectI bounds)
    {
        if (!ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata) ||
            !metadata.DetachedRaster)
            return null;
        bounds.Intersect(new SKRectI(0, 0, bitmap.Width, bitmap.Height));
        if (bounds.IsEmpty) return null;
        EnsureNativePixels(bitmap);
        lock (metadata)
        {
            var view = NativeView(bitmap, metadata);
            var before = new uint[checked(bounds.Width * bounds.Height)];
            var index = 0;
            for (var y = bounds.Top; y < bounds.Bottom; y++)
                for (var x = bounds.Left; x < bounds.Right; x++)
                    before[index++] = (uint)view.Get(x, y);
            return new NativeDrawing(bitmap, metadata, bounds, before);
        }
    }

    private sealed class NativeDrawing(
        SKBitmap bitmap,
        ImageMetadata metadata,
        SKRectI bounds,
        uint[] before) : IDisposable
    {
        private int disposed;

        public void Dispose()
        {
            if (Interlocked.Exchange(ref disposed, 1) != 0) return;
            lock (metadata)
            {
                var raster = metadata.Raster!;
                var colorModel = metadata.ColorModel!;
                var view = NativeView(bitmap, metadata);
                float[]? components = null;
                object? element = null;
                int[]? samples = null;
                object? pixel = null;
                var index = 0;
                for (var y = bounds.Top; y < bounds.Bottom; y++)
                {
                    for (var x = bounds.Left; x < bounds.Right; x++)
                    {
                        var drawn = view.Get(x, y);
                        if ((uint)drawn == before[index++]) continue;
                        element = colorModel.GetDrawnDataElements(ToArgb(drawn), ref components, element);
                        raster.SetDataElements(x, y, element);
                        // The native pixel shows the stored sample, as Java
                        // draws the image from its raster: a colour drawn
                        // into a gray image is gray.
                        view.Set(x, y, colorModel.RenderedColorAt(raster, x, y, ref samples, ref pixel));
                        MarkRendered(metadata, x, y);
                    }
                }
            }
        }
    }

    // A detached raster's view of its bitmap's native pixels, replaced when
    // the bitmap's pixels were reallocated. Called under a lock on metadata.
    private static PdfCartonBitmapView NativeView(SKBitmap bitmap, ImageMetadata metadata)
    {
        var view = metadata.NativeView;
        if (view is null || view.IsReallocated())
            metadata.NativeView = view = new PdfCartonBitmapView(bitmap);
        return view;
    }

    // A CreateImage image's retained raster; null for other images. Custom
    // composites read and write the image through it (SetDetachedRasterRegion),
    // as Java composes into the image's raster.
    internal static JavaRaster? GetDetachedRaster(SKBitmap bitmap) =>
        ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata) && metadata.DetachedRaster
            ? metadata.Raster
            : null;

    // Copies region's samples into a CreateImage image's retained raster at
    // (x, y) and renders those pixels, and only those, natively.
    internal static void SetDetachedRasterRegion(SKBitmap bitmap, int x, int y, JavaRaster region)
    {
        if (!ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata) ||
            !metadata.DetachedRaster)
            throw new InvalidOperationException("The image has no retained raster.");
        lock (metadata)
        {
            var raster = metadata.Raster!;
            var colorModel = metadata.ColorModel!;
            var width = region.Width;
            var row = new int[checked(width * region.NumberOfBands)];
            for (var line = 0; line < region.Height; line++)
                raster.SetPixels(x, y + line, width, 1, region.GetPixels(0, line, width, 1, row));
            if (metadata.NativePending) return;
            var view = NativeView(bitmap, metadata);
            int[]? samples = null;
            object? pixel = null;
            for (var line = y; line < y + region.Height; line++)
                for (var column = x; column < x + width; column++)
                {
                    view.Set(column, line, colorModel.RenderedColorAt(raster, column, line, ref samples, ref pixel));
                    MarkRendered(metadata, column, line);
                }
        }
    }

    internal static bool IsNativePending(SKBitmap bitmap)
    {
        if (!ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata)) return false;
        lock (metadata) return metadata.NativePending;
    }

    // Renders a deferred image's native pixels from its retained raster once,
    // and publishes raster writes to Skia. Compat draws call it first.
    internal static void EnsureNativePixels(SKBitmap bitmap)
    {
        PdfCartonBitmapView.Publish(bitmap);
        if (!ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata)) return;
        lock (metadata)
        {
            if (!metadata.NativePending) return;
            RenderRaster(bitmap, metadata.ColorModel!, metadata.Raster!);
            metadata.NativePending = false;
        }
    }

    private static readonly SKImageRasterReleaseDelegate KeepSourceAlive = (_, _) => { };

    // SKCanvas.DrawBitmap and SKBitmap.ToShader go through an image that
    // copies all of a mutable bitmap's pixels, however little is drawn. A
    // raster canvas reads them only while it draws, so for one this image
    // wraps the live pixels instead (and keeps source alive while it lives).
    // rasterTarget is the canvas's bitmap: null for a canvas that may record
    // or defer, which, like a source sharing the target's pixels, gets the copy.
    internal static SKImage? DrawableImage(SKBitmap source, SKBitmap? rasterTarget)
    {
        if (rasterTarget is not null && !SharesPixels(source, rasterTarget))
        {
            using var pixmap = source.PeekPixels();
            if (pixmap is not null && SKImage.FromPixels(pixmap, KeepSourceAlive, source) is { } view)
                return view;
        }
        return SKImage.FromBitmap(source);
    }

    private static bool SharesPixels(SKBitmap first, SKBitmap second)
    {
        long start = (long)first.GetPixels(), other = (long)second.GetPixels();
        var shares = ReferenceEquals(first, second) || start != 0 && other != 0 &&
                     start < other + second.ByteCount && other < start + first.ByteCount;
        GC.KeepAlive(first);
        GC.KeepAlive(second);
        return shares;
    }

    // Per-thread scratch for one-pixel GetRgb/SetRgb, which Java allocates
    // per call. Data-element buffers hold the most bands or components any
    // raster or color model has; callers read and write only their prefix.
    private static class RgbScratch
    {
        private const int ElementLength = JavaColorSpace.MaximumComponents + 1;

        [ThreadStatic] private static sbyte[]? bytes;
        [ThreadStatic] private static short[]? words;
        [ThreadStatic] private static int[]? ints;
        [ThreadStatic] internal static float[]? Components;
        [ThreadStatic] internal static int[]? Samples;

        // A data-element buffer of the raster's transfer type; a color model
        // whose elements have another type allocates its own, as without one.
        internal static object Element(JavaRaster raster) =>
            raster.TransferType switch
            {
                DATA_BUFFER_TYPE_USHORT => words ??= new short[ElementLength],
                DATA_BUFFER_TYPE_INT => ints ??= new int[ElementLength],
                _ => bytes ??= new sbyte[ElementLength]
            };
    }

    internal static int GetRgb(SKBitmap bitmap, int x, int y)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        if (ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata) &&
            metadata.Raster is not null &&
            metadata.ColorModel is not null)
        {
            var raster = metadata.Raster;
            return metadata.ColorModel.GetRgb(raster.GetDataElements(x, y, RgbScratch.Element(raster)));
        }
        var color = PdfCartonPixels.Read(bitmap, x, y);
        return IsByteGray(bitmap) ? ByteGrayRgb(color.Red) : ToArgb(color);
    }

    // A TYPE_BYTE_GRAY bitmap whose native pixels are its samples.
    private static bool IsByteGray(SKBitmap bitmap) =>
        bitmap.ColorType == SKColorType.Gray8 && GetImageType(bitmap) == TYPE_BYTE_GRAY;

    // getRGB of a TYPE_BYTE_GRAY sample: Java's linear CS_GRAY colour model.
    private static int ByteGrayRgb(byte sample)
    {
        Span<float> gray = stackalloc float[1];
        Span<float> rgb = stackalloc float[3];
        gray[0] = sample / 255f;
        GrayColorSpace.ToRgb(gray, rgb);
        return unchecked((int)0xff000000) | ComponentByte(rgb[0]) * 0x010101;
    }

    internal static int[] GetRgb(
        SKBitmap bitmap,
        int x,
        int y,
        int width,
        int height,
        int[]? values,
        int offset,
        int scansize)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        values ??= new int[checked(offset + (height - 1) * scansize + width)];
        if (ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata) &&
            metadata.Raster is not null &&
            metadata.ColorModel is not null)
        {
            // Like Java's getRGB, one data-element buffer serves every pixel.
            var raster = metadata.Raster;
            var colorModel = metadata.ColorModel;
            object? pixel = null;
            for (var row = 0; row < height; row++)
            {
                for (var column = 0; column < width; column++)
                {
                    pixel = raster.GetDataElements(x + column, y + row, pixel);
                    values[offset + row * scansize + column] = colorModel.GetRgb(pixel);
                }
            }
            return values;
        }
        for (var row = 0; row < height; row++)
        {
            for (var column = 0; column < width; column++)
            {
                values[offset + row * scansize + column] =
                    GetRgb(bitmap, x + column, y + row);
            }
        }
        return values;
    }

    internal static void SetRgb(SKBitmap bitmap, int x, int y, int argb)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        if (ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata) &&
            metadata.Raster is not null &&
            metadata.ColorModel is not null)
        {
            // Java: raster.setDataElements(x, y, colorModel.getDataElements(rgb, null)).
            var colorModel = metadata.ColorModel;
            var raster = metadata.Raster;
            var scratch = RgbScratch.Element(raster);
            var pixel = colorModel.GetDataElements(argb, ref RgbScratch.Components, scratch);
            object? element = scratch;
            if (!metadata.DetachedRaster)
            {
                // A live view of the bitmap's own pixels writes the native
                // pixel itself; otherwise (an INT_BGR view, or a view whose
                // samples were materialized) render the one written pixel.
                raster.SetDataElements(x, y, pixel);
                if (!RendersAsStored(bitmap, colorModel, raster))
                    PdfCartonPixels.Write(bitmap, x, y,
                        colorModel.RenderedColorAt(raster, x, y, ref RgbScratch.Samples, ref element));
                return;
            }
            lock (metadata)
            {
                raster.SetDataElements(x, y, pixel);
                // A deferred image renders the whole raster on first draw.
                if (metadata.NativePending) return;
                NativeView(bitmap, metadata).Set(
                    x, y, colorModel.RenderedColorAt(raster, x, y, ref RgbScratch.Samples, ref element));
                MarkRendered(metadata, x, y);
            }
            return;
        }
        if (IsByteGray(bitmap))
        {
            // Java's linear CS_GRAY getDataElements.
            Span<float> rgb = stackalloc float[3];
            Span<float> gray = stackalloc float[JavaColorSpace.MaximumComponents];
            rgb[0] = unchecked((byte)(argb >> 16)) / 255f;
            rgb[1] = unchecked((byte)(argb >> 8)) / 255f;
            rgb[2] = unchecked((byte)argb) / 255f;
            GrayColorSpace.FromRgb(rgb, gray);
            var sample = ComponentByte(gray[0]);
            PdfCartonPixels.Write(bitmap, x, y, new SKColor(sample, sample, sample));
            return;
        }
        var color = FromArgb(argb);
        if (ImageMetadataByBitmap.TryGetValue(bitmap, out var imageMetadata) &&
            imageMetadata.Transparency == PdfCartonTransparency.BITMASK)
        {
            color = color.WithAlpha(
                color.Alpha < 128 ? byte.MinValue : byte.MaxValue);
        }
        PdfCartonPixels.Write(bitmap, x, y, color);
    }

    internal static SKBitmap CreateCompatibleImage(
        int width,
        int height,
        int transparency)
    {
        if (transparency is not PdfCartonTransparency.OPAQUE and
            not PdfCartonTransparency.BITMASK and
            not PdfCartonTransparency.TRANSLUCENT)
        {
            throw new ArgumentException(
                "Invalid transparency constant.",
                nameof(transparency));
        }
        var bitmap = new PdfCartonNativeBitmap(new SKImageInfo(
            width,
            height,
            SKColorType.Bgra8888,
            transparency == PdfCartonTransparency.OPAQUE
                ? SKAlphaType.Opaque
                : SKAlphaType.Unpremul));
        ImageMetadataByBitmap.Set(
            bitmap,
            new ImageMetadata(
                transparency == PdfCartonTransparency.BITMASK
                    ? TYPE_CUSTOM
                    : transparency == PdfCartonTransparency.OPAQUE
                        ? TYPE_INT_RGB
                        : TYPE_INT_ARGB,
                colorModel: transparency == PdfCartonTransparency.BITMASK
                    ? new JavaColorModel(
                        GetColorSpace(JavaColorSpace.CS_sRGB),
                        true,
                        DATA_BUFFER_TYPE_BYTE,
                        null,
                        transparency)
                    : null,
                transparency: transparency));
        return bitmap;
    }

    private static int ToArgb(SKColor color) =>
        unchecked((int)((uint)color.Alpha << 24 |
                        (uint)color.Red << 16 |
                        (uint)color.Green << 8 |
                        color.Blue));

    private static SKColor FromArgb(int argb) =>
        new(
            unchecked((byte)(argb >> 16)),
            unchecked((byte)(argb >> 8)),
            unchecked((byte)argb),
            unchecked((byte)(argb >> 24)));

    internal static PdfCartonGraphics2D CreateGraphics(SKBitmap bitmap) => new(bitmap);

    internal static JavaRaster CreateBandedRaster(
        int dataType,
        int width,
        int height,
        int bands,
        JavaPoint origin)
    {
        if (origin.IntX != 0 || origin.IntY != 0)
            throw new ArgumentException("Only zero-origin banded rasters are supported.");
        return new JavaRaster(dataType, width, height, bands);
    }

    internal static JavaRaster CreateInterleavedRaster(
        int dataType,
        int width,
        int height,
        int bands,
        JavaPoint origin) =>
        CreateBandedRaster(dataType, width, height, bands, origin);

    internal static JavaRaster CreateInterleavedRaster(
        JavaDataBuffer buffer,
        int width,
        int height,
        int scanlineStride,
        int pixelStride,
        int[] bandOffsets,
        JavaPoint origin)
    {
        if (origin.IntX != 0 || origin.IntY != 0)
            throw new ArgumentException(
                "Only zero-origin interleaved rasters are supported.");
        return new JavaRaster(
            buffer,
            width,
            height,
            scanlineStride,
            pixelStride,
            bandOffsets);
    }

    internal static JavaIccProfile GetIccProfile(int colorSpace)
    {
        if (colorSpace != JavaColorSpace.CS_sRGB)
            throw new ArgumentException("Only the standard sRGB ICC profile is available by identifier.");
        return StandardSrgbProfile.Value;
    }

    internal static bool IsStandardSrgbProfile(JavaIccProfile profile)
    {
        ArgumentNullException.ThrowIfNull(profile);
        return profile.GetData().AsSpan().SequenceEqual(
            StandardSrgbProfile.Value.GetData());
    }

    private static JavaIccProfile CreateSrgbProfile()
    {
        using var compressed = new MemoryStream(
            Convert.FromBase64String(StandardSrgbProfileGzipBase64),
            writable: false);
        using var gzip = new GZipStream(
            compressed,
            System.IO.Compression.CompressionMode.Decompress);
        using var profile = new MemoryStream();
        gzip.CopyTo(profile);
        return new JavaIccProfile(ToSignedBytes(profile.ToArray()));
    }

    // As Java's ColorSpace.getInstance, each standard space is one shared
    // instance. Colour spaces compare by identity, so BlendComposite (and any
    // other srcColorSpace.equals(dstColorSpace) check) sees the default colour
    // models' spaces as equal and skips the CIE XYZ conversion.
    private static readonly Lazy<JavaColorSpace> SrgbColorSpace = new(() =>
        new JavaIccColorSpace(
            JavaColorSpace.CS_sRGB,
            GetIccProfile(JavaColorSpace.CS_sRGB)));
    private static readonly JavaColorSpace GrayColorSpace =
        new(JavaColorSpace.CS_GRAY);
    // A gray space whose component is the sRGB value itself, converted from
    // colours with Java2D's ByteGray luma.
    internal static readonly JavaColorSpace EncodedGrayColorSpace =
        new(0, JavaColorSpace.TYPE_GRAY, 1);
    // Java2D's ByteGray luma for Skia, which stores a colour drawn into
    // Gray8 as its BT.709 luma: the gray this filter hands it is its own luma.
    internal static readonly SKColorFilter JavaGrayLumaFilter = SKColorFilter.CreateColorMatrix(
    [
        77f / 256f, 150f / 256f, 29f / 256f, 0f, 0f,
        77f / 256f, 150f / 256f, 29f / 256f, 0f, 0f,
        77f / 256f, 150f / 256f, 29f / 256f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ]);

    // Java 2D maps a sampled colour (r, g, b) drawn into TYPE_BYTE_BINARY to
    // black exactly when (r >> 3) + (g >> 3) + (b >> 3) <= 46 (black-box
    // probes of Java 25 over all 5-bit cells, for drawImage at any scale,
    // TexturePaint and GradientPaint).
    internal static bool IsJavaBinarySampledBlack(SKColor color) =>
        (color.Red >> 3) + (color.Green >> 3) + (color.Blue >> 3) <= 46;

    private static readonly JavaColorSpace CieXyzColorSpace =
        new(JavaColorSpace.CS_CIEXYZ);

    internal static JavaColorSpace GetColorSpace(int colorSpace) =>
        colorSpace switch
        {
            JavaColorSpace.CS_sRGB => SrgbColorSpace.Value,
            JavaColorSpace.CS_GRAY => GrayColorSpace,
            JavaColorSpace.CS_CIEXYZ => CieXyzColorSpace,
            _ => throw new ArgumentException(
                $"Unsupported standard color-space identifier `{colorSpace}`.",
                nameof(colorSpace))
        };

    internal static JavaColorModel ComponentColorModel(
        JavaColorSpace colorSpace,
        bool hasAlpha,
        bool isAlphaPremultiplied,
        int transparency,
        int dataType)
    {
        ArgumentNullException.ThrowIfNull(colorSpace);
        if (isAlphaPremultiplied && !hasAlpha)
            throw new ArgumentException(
                "A premultiplied component color model requires alpha.");
        if (transparency is not PdfCartonTransparency.OPAQUE and
            not PdfCartonTransparency.BITMASK and
            not PdfCartonTransparency.TRANSLUCENT)
            throw new ArgumentException("Invalid transparency constant.");
        return new JavaColorModel(
            colorSpace,
            hasAlpha,
            dataType,
            null,
            transparency);
    }

    internal static JavaColorModel IndexColorModel(
        int pixelBits,
        int mapSize,
        sbyte[] red,
        sbyte[] green,
        sbyte[] blue) =>
        new(pixelBits, mapSize, red, green, blue);

    internal static JavaIccProfile GetIccProfile(Stream stream)
    {
        ArgumentNullException.ThrowIfNull(stream);
        using var buffer = new MemoryStream();
        stream.CopyTo(buffer);
        return GetIccProfile(ToSignedBytes(buffer.ToArray()));
    }

    internal static JavaIccProfile GetIccProfile(sbyte[] data)
    {
        ArgumentNullException.ThrowIfNull(data);
        try
        {
            return new JavaIccProfile(data);
        }
        catch (ArgumentException exception)
        {
            throw new ArgumentException("Invalid ICC Profile Data", exception);
        }
    }

    private static sbyte[] ToSignedBytes(byte[] bytes)
    {
        var result = new sbyte[bytes.Length];
        for (var index = 0; index < bytes.Length; index++) result[index] = unchecked((sbyte)bytes[index]);
        return result;
    }

    // The type of a decoded bitmap, whose colour model (and so transparency)
    // GetTransparency then classifies from its pixels once.
    internal static void RegisterImageType(SKBitmap bitmap, int type)
    {
        ImageMetadataByBitmap.Set(bitmap, new ImageMetadata(type));
    }

    internal static int InferImageType(SKBitmap bitmap) =>
        bitmap.ColorType == SKColorType.Gray8
            ? TYPE_BYTE_GRAY
            : bitmap.AlphaType == SKAlphaType.Opaque ? TYPE_INT_RGB : TYPE_INT_ARGB;

    internal static SKMatrix Identity() => SKMatrix.CreateIdentity();

    internal static SKMatrix AffineTransform(
        double m00,
        double m10,
        double m01,
        double m11,
        double m02,
        double m12) =>
        new()
        {
            ScaleX = (float)m00,
            SkewY = (float)m10,
            SkewX = (float)m01,
            ScaleY = (float)m11,
            TransX = (float)m02,
            TransY = (float)m12,
            Persp0 = 0,
            Persp1 = 0,
            Persp2 = 1
        };

    internal static SKMatrix Translation(double x, double y) =>
        SKMatrix.CreateTranslation((float)x, (float)y);

    internal static SKMatrix Scale(double x, double y) =>
        SKMatrix.CreateScale((float)x, (float)y);

    internal static SKRect Rectangle(double x, double y, double width, double height) =>
        new((float)x, (float)y, (float)(x + width), (float)(y + height));

    internal static bool RectangleContains(SKRect rectangle, double x, double y) =>
        !rectangle.IsEmpty &&
        x >= rectangle.Left &&
        y >= rectangle.Top &&
        x < rectangle.Right &&
        y < rectangle.Bottom;

    internal static void AddPoint(ref SKRect rectangle, JavaPoint2D point)
    {
        ArgumentNullException.ThrowIfNull(point);
        rectangle = new SKRect(
            Math.Min(rectangle.Left, (float)point.X),
            Math.Min(rectangle.Top, (float)point.Y),
            Math.Max(rectangle.Right, (float)point.X),
            Math.Max(rectangle.Bottom, (float)point.Y));
    }

    internal static void IntersectRectangles(
        SKRect first,
        SKRect second,
        ref SKRect destination)
    {
        destination = new SKRect(
            Math.Max(first.Left, second.Left),
            Math.Max(first.Top, second.Top),
            Math.Min(first.Right, second.Right),
            Math.Min(first.Bottom, second.Bottom));
    }

    internal static SKRectI RectangleI(int width, int height) =>
        new(0, 0, width, height);

    internal static SKRectI RectangleI(int x, int y, int width, int height) =>
        new(x, y, checked(x + width), checked(y + height));

    internal static void ScaleInPlace(ref SKMatrix matrix, double x, double y)
    {
        matrix.ScaleX *= (float)x;
        matrix.SkewY *= (float)x;
        matrix.SkewX *= (float)y;
        matrix.ScaleY *= (float)y;
    }

    internal static void TranslateInPlace(ref SKMatrix matrix, double x, double y)
    {
        matrix.TransX += matrix.ScaleX * (float)x + matrix.SkewX * (float)y;
        matrix.TransY += matrix.SkewY * (float)x + matrix.ScaleY * (float)y;
    }

    internal static void QuadrantRotateInPlace(ref SKMatrix matrix, int quadrants)
    {
        var normalized = ((quadrants % 4) + 4) % 4;
        var rotation = normalized switch
        {
            0 => Identity(),
            1 => AffineTransform(0, 1, -1, 0, 0, 0),
            2 => AffineTransform(-1, 0, 0, -1, 0, 0),
            _ => AffineTransform(0, -1, 1, 0, 0, 0)
        };
        ConcatenateInPlace(ref matrix, rotation);
    }

    internal static void RotateInPlace(ref SKMatrix matrix, double radians)
    {
        var cosine = (float)Math.Cos(radians);
        var sine = (float)Math.Sin(radians);
        var scaleX = matrix.ScaleX;
        var skewX = matrix.SkewX;
        var skewY = matrix.SkewY;
        var scaleY = matrix.ScaleY;
        matrix.ScaleX = scaleX * cosine + skewX * sine;
        matrix.SkewX = -scaleX * sine + skewX * cosine;
        matrix.SkewY = skewY * cosine + scaleY * sine;
        matrix.ScaleY = -skewY * sine + scaleY * cosine;
    }

    internal static void RotateInPlace(
        ref SKMatrix matrix,
        double radians,
        double anchorX,
        double anchorY)
    {
        TranslateInPlace(ref matrix, anchorX, anchorY);
        RotateInPlace(ref matrix, radians);
        TranslateInPlace(ref matrix, -anchorX, -anchorY);
    }

    internal static void ShearInPlace(ref SKMatrix matrix, double x, double y)
    {
        var scaleX = matrix.ScaleX;
        var skewX = matrix.SkewX;
        var skewY = matrix.SkewY;
        var scaleY = matrix.ScaleY;
        matrix.ScaleX = scaleX + skewX * (float)y;
        matrix.SkewX = scaleX * (float)x + skewX;
        matrix.SkewY = skewY + scaleY * (float)y;
        matrix.ScaleY = skewY * (float)x + scaleY;
    }

    internal static void ConcatenateInPlace(ref SKMatrix matrix, SKMatrix transform) =>
        matrix = Multiply(matrix, transform);

    internal static void PreConcatenateInPlace(ref SKMatrix matrix, SKMatrix transform) =>
        matrix = Multiply(transform, matrix);

    internal static SKMatrix CreateInverse(SKMatrix matrix)
    {
        var determinant = matrix.ScaleX * matrix.ScaleY - matrix.SkewX * matrix.SkewY;
        if (Math.Abs(determinant) <= float.Epsilon)
        {
            throw new InvalidOperationException("Transform is not invertible.");
        }
        var inverseDeterminant = 1f / determinant;
        return AffineTransform(
            matrix.ScaleY * inverseDeterminant,
            -matrix.SkewY * inverseDeterminant,
            -matrix.SkewX * inverseDeterminant,
            matrix.ScaleX * inverseDeterminant,
            (matrix.SkewX * matrix.TransY - matrix.ScaleY * matrix.TransX) *
                inverseDeterminant,
            (matrix.SkewY * matrix.TransX - matrix.ScaleX * matrix.TransY) *
                inverseDeterminant);
    }

    private static SKMatrix Multiply(SKMatrix left, SKMatrix right) =>
        AffineTransform(
            left.ScaleX * right.ScaleX + left.SkewX * right.SkewY,
            left.SkewY * right.ScaleX + left.ScaleY * right.SkewY,
            left.ScaleX * right.SkewX + left.SkewX * right.ScaleY,
            left.SkewY * right.SkewX + left.ScaleY * right.ScaleY,
            left.ScaleX * right.TransX + left.SkewX * right.TransY + left.TransX,
            left.SkewY * right.TransX + left.ScaleY * right.TransY + left.TransY);

    internal static JavaColor ColorFromRgb(int rgb) =>
        new(new SKColor(unchecked((uint)rgb) | 0xff000000u));

    internal static JavaColor ColorFromComponents(int red, int green, int blue) =>
        ColorFromComponents(red, green, blue, 255);

    internal static JavaColor ColorFromComponents(int red, int green, int blue, int alpha)
    {
        if ((uint)red > 255 || (uint)green > 255 || (uint)blue > 255 || (uint)alpha > 255)
        {
            throw new ArgumentException("Color components must be between 0 and 255.");
        }
        return new JavaColor(
            new SKColor((byte)red, (byte)green, (byte)blue, (byte)alpha));
    }

    internal static JavaColor ColorFromFractions(float red, float green, float blue)
    {
        static int Component(float value)
        {
            if (value is < 0 or > 1)
            {
                throw new ArgumentException("Color components must be between 0 and 1.");
            }
            return (int)(value * 255 + 0.5f);
        }

        return ColorFromComponents(Component(red), Component(green), Component(blue));
    }

    internal static float[] GetRgbColorComponents(JavaColor color, float[]? components)
    {
        components ??= new float[3];
        if (components.Length < 3) throw new ArgumentException("RGB component array is too small.");
        components[0] = color.Red / 255f;
        components[1] = color.Green / 255f;
        components[2] = color.Blue / 255f;
        return components;
    }

    internal static sbyte CharacterDirectionality(int codePoint)
    {
        var bidiClass = BidiUnicodeData.GetBiDiClass(checked((uint)codePoint));
        return bidiClass switch
        {
            BidiClass.LeftToRight => 0,
            BidiClass.RightToLeft => 1,
            BidiClass.ArabicLetter => 2,
            _ => -1
        };
    }

    internal static double Determinant(SKMatrix matrix) =>
        matrix.ScaleX * (matrix.ScaleY * matrix.Persp2 - matrix.TransY * matrix.Persp1)
        - matrix.SkewX * (matrix.SkewY * matrix.Persp2 - matrix.TransY * matrix.Persp0)
        + matrix.TransX * (matrix.SkewY * matrix.Persp1 - matrix.ScaleY * matrix.Persp0);

    internal static void GetMatrix(SKMatrix matrix, double[] values)
    {
        ArgumentNullException.ThrowIfNull(values);
        if (values.Length < 4)
        {
            throw new IndexOutOfRangeException("AffineTransform matrix array must have at least four elements.");
        }
        values[0] = matrix.ScaleX;
        values[1] = matrix.SkewY;
        values[2] = matrix.SkewX;
        values[3] = matrix.ScaleY;
        if (values.Length >= 6)
        {
            values[4] = matrix.TransX;
            values[5] = matrix.TransY;
        }
    }

    internal static bool IsIdentity(SKMatrix matrix) =>
        matrix.ScaleX == 1 &&
        matrix.SkewX == 0 &&
        matrix.TransX == 0 &&
        matrix.SkewY == 0 &&
        matrix.ScaleY == 1 &&
        matrix.TransY == 0 &&
        matrix.Persp0 == 0 &&
        matrix.Persp1 == 0 &&
        matrix.Persp2 == 1;

    internal static bool IsDefaultMatrix(SKMatrix matrix) =>
        matrix.ScaleX == 0 &&
        matrix.SkewX == 0 &&
        matrix.TransX == 0 &&
        matrix.SkewY == 0 &&
        matrix.ScaleY == 0 &&
        matrix.TransY == 0 &&
        matrix.Persp0 == 0 &&
        matrix.Persp1 == 0 &&
        matrix.Persp2 == 0;

    internal static int GetTransformType(SKMatrix matrix)
    {
        if (matrix.Persp0 != 0 || matrix.Persp1 != 0 || matrix.Persp2 != 1)
            return TYPE_GENERAL_TRANSFORM;

        var type = 0;
        if (matrix.TransX != 0 || matrix.TransY != 0)
            type |= TYPE_TRANSLATION;

        var determinant =
            matrix.ScaleX * matrix.ScaleY - matrix.SkewX * matrix.SkewY;
        if (determinant < 0)
            type |= TYPE_FLIP;

        if (matrix.SkewX != 0 || matrix.SkewY != 0)
        {
            type |= TYPE_GENERAL_ROTATION;
        }
        else
        {
            var scaleX = Math.Abs(matrix.ScaleX);
            var scaleY = Math.Abs(matrix.ScaleY);
            if (scaleX != 1 || scaleY != 1)
                type |= scaleX == scaleY ? TYPE_UNIFORM_SCALE : TYPE_GENERAL_SCALE;
        }
        return type;
    }

    internal static double ShapeCenterX(object shape) =>
        shape switch
        {
            SKRect rectangle => rectangle.MidX,
            JavaEllipse ellipse => ellipse.CenterX,
            _ => throw new ArgumentException(
                $"Unsupported rectangular shape `{shape.GetType().FullName}`.",
                nameof(shape))
        };

    internal static double ShapeCenterY(object shape) =>
        shape switch
        {
            SKRect rectangle => rectangle.MidY,
            JavaEllipse ellipse => ellipse.CenterY,
            _ => throw new ArgumentException(
                $"Unsupported rectangular shape `{shape.GetType().FullName}`.",
                nameof(shape))
        };

    internal static void TransformPoints(
        SKMatrix matrix,
        float[] source,
        int sourceOffset,
        float[] destination,
        int destinationOffset,
        int pointCount)
    {
        ArgumentNullException.ThrowIfNull(source);
        ArgumentNullException.ThrowIfNull(destination);
        ArgumentOutOfRangeException.ThrowIfNegative(sourceOffset);
        ArgumentOutOfRangeException.ThrowIfNegative(destinationOffset);
        ArgumentOutOfRangeException.ThrowIfNegative(pointCount);
        var sourceEnd = checked(sourceOffset + checked(pointCount * 2));
        if (sourceEnd > source.Length)
            throw new ArgumentOutOfRangeException(nameof(sourceOffset));
        var destinationEnd = checked(destinationOffset + checked(pointCount * 2));
        if (destinationEnd > destination.Length)
            throw new ArgumentOutOfRangeException(nameof(destinationOffset));

        // In place like Java's AffineTransform.transform: a destination starting
        // inside the source is moved there first, then one forward pass.
        if (ReferenceEquals(source, destination) &&
            destinationOffset > sourceOffset && destinationOffset < sourceEnd)
        {
            Array.Copy(source, sourceOffset, destination, destinationOffset, pointCount * 2);
            sourceOffset = destinationOffset;
        }
        for (var point = 0; point < pointCount; point++)
        {
            var x = source[sourceOffset + point * 2];
            var y = source[sourceOffset + point * 2 + 1];
            destination[destinationOffset + point * 2] =
                matrix.ScaleX * x + matrix.SkewX * y + matrix.TransX;
            destination[destinationOffset + point * 2 + 1] =
                matrix.SkewY * x + matrix.ScaleY * y + matrix.TransY;
        }
    }

    internal static JavaPoint2D TransformPoint(
        SKMatrix matrix,
        JavaPoint2D source,
        JavaPoint2D? destination)
    {
        ArgumentNullException.ThrowIfNull(source);
        var x = matrix.ScaleX * source.X + matrix.SkewX * source.Y + matrix.TransX;
        var y = matrix.SkewY * source.X + matrix.ScaleY * source.Y + matrix.TransY;
        destination ??= new JavaPoint2D(x, y);
        destination.SetLocation(x, y);
        return destination;
    }

    // Path2D.getCurrentPoint: null for an empty path, else the last point,
    // or after a close the start of the closed subpath.
    internal static JavaPoint2D? CurrentPoint(SKPath path)
    {
        var general = path as PdfCartonGeneralPath;
        if (general is null || general.Lent || general.Cursor == PathCursor.Unknown)
        {
            var cursor = ReadCursor(path, out var current, out var start);
            if (general is null || general.Lent)
                return cursor == PathCursor.Empty ? null : new JavaPoint2D(current.X, current.Y);
            Track(general, cursor, current, start);
        }
        return general.Cursor == PathCursor.Empty
            ? null
            : new JavaPoint2D(general.Current.X, general.Current.Y);
    }

    // The cursor read from the path itself, one pass over its verbs.
    private static PathCursor ReadCursor(SKPath path, out SKPoint current, out SKPoint start)
    {
        current = start = default;
        if (path.IsEmpty) return PathCursor.Empty;
        var last = SKPathVerb.Done;
        Span<SKPoint> points = stackalloc SKPoint[4];
        using (var iterator = path.CreateRawIterator())
        {
            for (var verb = iterator.Next(points); verb != SKPathVerb.Done; verb = iterator.Next(points))
            {
                if (verb == SKPathVerb.Move) start = points[0];
                last = verb;
            }
        }
        if (last == SKPathVerb.Close)
        {
            current = start;
            return PathCursor.Closed;
        }
        current = path.LastPoint;
        return PathCursor.Open;
    }

    private static void Track(PdfCartonGeneralPath path, PathCursor cursor, SKPoint current, SKPoint start)
    {
        path.Cursor = cursor;
        path.Current = current;
        path.SubpathStart = start;
    }

    // After a line, quad or cubic segment ending at (x, y). Skia starts a
    // segment after a close at the closed subpath's start, as Path2D does;
    // a segment on an empty path (Path2D throws) is read back from the path.
    private static void TrackSegment(SKPath path, float x, float y)
    {
        if (path is not PdfCartonGeneralPath general || general.Cursor == PathCursor.Unknown) return;
        if (general.Cursor == PathCursor.Empty)
        {
            general.Cursor = PathCursor.Unknown;
            return;
        }
        general.Cursor = PathCursor.Open;
        general.Current = new SKPoint(x, y);
    }

    private static void ForgetCursor(SKPath path)
    {
        if (path is PdfCartonGeneralPath general) general.Cursor = PathCursor.Unknown;
    }

    // A path handed to code outside the compat helpers (PageDrawer.getLinePath),
    // which may change it natively: its current point is read from then on.
    internal static SKPath LendPath(SKPath path)
    {
        if (path is PdfCartonGeneralPath general) general.Lent = true;
        return path;
    }

    internal static JavaPathIterator PathIterator(SKPath path, object? transform) =>
        new(path, transform);

    // Shape.getPathIterator: paths and areas are iterated in place.
    internal static JavaPathIterator ShapePathIterator(
        object shape,
        object? transform)
    {
        var lease = BorrowPath(shape);
        return new JavaPathIterator(lease.Path, transform, ownsPath: lease.Owned);
    }

    internal static void Close(SKPath path)
    {
        path.Close();
        if (path is PdfCartonGeneralPath { Cursor: PathCursor.Open or PathCursor.Closed } general)
        {
            general.Cursor = PathCursor.Closed;
            general.Current = general.SubpathStart;
        }
    }

    // Path2D.reset.
    internal static void ResetPath(SKPath path)
    {
        path.Reset();
        if (path is PdfCartonGeneralPath general) general.Cursor = PathCursor.Empty;
    }

    // Path2D.clone: a GeneralPath that keeps its source's cursor.
    internal static SKPath ClonePath(SKPath path)
    {
        var clone = new PdfCartonGeneralPath(path) { Cursor = PathCursor.Unknown };
        if (path is PdfCartonGeneralPath { Lent: false } general)
            Track(clone, general.Cursor, general.Current, general.SubpathStart);
        return clone;
    }

    internal static void SetWindingRule(SKPath path, int windingRule) =>
        path.FillType = windingRule == JavaPathIterator.WIND_EVEN_ODD
            ? SKPathFillType.EvenOdd
            : SKPathFillType.Winding;

    internal static void TransformPath(SKPath path, SKMatrix transform)
    {
        ArgumentNullException.ThrowIfNull(path);
        path.Transform(transform);
        ForgetCursor(path);
    }

    internal static object CreateTransformedShape(
        SKMatrix transform,
        object shape)
    {
        var path = CreatePath(shape);
        path.Transform(transform);
        return path;
    }

    internal static SKRect ShapeBounds(object shape)
    {
        using var lease = BorrowPath(shape);
        return lease.Path.Bounds;
    }

    // createTransformedShape(shape).getBounds2D() without keeping the shape.
    internal static SKRect TransformedShapeBounds(SKMatrix transform, object shape)
    {
        using var path = (SKPath)CreateTransformedShape(transform, shape);
        return path.Bounds;
    }

    // The shape as a path to read, not keep: an SKPath or an area's path is
    // lent as is, any other shape is converted into a path the lease owns.
    internal static PathLease BorrowPath(object shape) =>
        shape is SKPath path ? new PathLease(path, false)
        : shape is JavaArea area ? new PathLease(area.Path, false)
        : new PathLease(CreatePath(shape), true);

    internal readonly struct PathLease : IDisposable
    {
        internal PathLease(SKPath path, bool owned)
        {
            Path = path;
            Owned = owned;
        }

        internal SKPath Path { get; }
        internal bool Owned { get; }

        public void Dispose()
        {
            if (Owned) Path.Dispose();
        }
    }

    // new GeneralPath().
    internal static SKPath CreatePath() => new PdfCartonGeneralPath();

    internal static SKPath CreatePath(int windingRule, int initialCapacity)
    {
        if (initialCapacity < 0) throw new ArgumentException("Initial capacity cannot be negative.");
        var path = new PdfCartonGeneralPath();
        SetWindingRule(path, windingRule);
        return path;
    }

    internal static SKPath CreatePath(object shape)
    {
        ArgumentNullException.ThrowIfNull(shape);
        if (shape is SKPath path) return new SKPath(path);
        if (shape is JavaArea area) return new SKPath(area.Path);
        if (shape is JavaEllipse ellipse) return ellipse.ToPath();
        if (shape is SKRect rectangle)
        {
            var result = new SKPath();
            result.AddRect(rectangle);
            return result;
        }
        if (shape is SKRectI integerRectangle)
        {
            var result = new SKPath();
            result.AddRect(new SKRect(
                integerRectangle.Left,
                integerRectangle.Top,
                integerRectangle.Right,
                integerRectangle.Bottom));
            return result;
        }
        throw new ArgumentException("Unsupported Java shape.", nameof(shape));
    }

    internal static SKRectI PathBounds(SKPath path)
    {
        var bounds = path.Bounds;
        return new SKRectI(
            (int)Math.Floor(bounds.Left),
            (int)Math.Floor(bounds.Top),
            (int)Math.Ceiling(bounds.Right),
            (int)Math.Ceiling(bounds.Bottom));
    }

    internal static void AddPath(SKPath path, JavaPathIterator addition)
    {
        addition.AppendTo(path, connect: false);
        ForgetCursor(path);
    }

    internal static void AppendPath(SKPath path, object shape, bool connect)
    {
        using var lease = BorrowPath(shape);
        // Skia drops a path appended to itself, so that one is copied.
        using var self = ReferenceEquals(lease.Path, path) ? new SKPath(path) : null;
        path.AddPath(
            self ?? lease.Path,
            connect ? SKPathAddMode.Extend : SKPathAddMode.Append);
        ForgetCursor(path);
    }

    internal static void AppendPath(
        SKPath path,
        JavaPathIterator addition,
        bool connect)
    {
        addition.AppendTo(path, connect);
        ForgetCursor(path);
    }

    internal static void MoveTo(SKPath path, double x, double y)
    {
        var point = new SKPoint((float)x, (float)y);
        path.MoveTo(point);
        if (path is PdfCartonGeneralPath general)
            Track(general, PathCursor.Open, point, point);
    }

    internal static void LineTo(SKPath path, double x, double y)
    {
        path.LineTo((float)x, (float)y);
        TrackSegment(path, (float)x, (float)y);
    }

    internal static void QuadTo(SKPath path, double x1, double y1, double x2, double y2)
    {
        path.QuadTo((float)x1, (float)y1, (float)x2, (float)y2);
        TrackSegment(path, (float)x2, (float)y2);
    }

    internal static void CurveTo(
        SKPath path,
        double x1,
        double y1,
        double x2,
        double y2,
        double x3,
        double y3)
    {
        path.CubicTo((float)x1, (float)y1, (float)x2, (float)y2, (float)x3, (float)y3);
        TrackSegment(path, (float)x3, (float)y3);
    }
}

#pragma warning restore CS0618
