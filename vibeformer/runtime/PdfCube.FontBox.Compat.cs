// Focused destination compatibility for FontBox geometry over SkiaSharp.
#nullable enable

using System;
using System.Collections.Generic;
using System.Formats.Asn1;
using System.IO;
using System.Linq;
using System.Numerics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Xml;
using Avalonia.Media.TextFormatting.Unicode;
using SkiaSharp;

namespace Vibeformer.Runtime;

#pragma warning disable CS0618 // SKPath mutation is the m150 bridge for Java GeneralPath.

internal static class PdfCubeImageIO
{
    internal static JavaImageInputStream CreateImageInputStream(object source) =>
        source switch
        {
            Stream stream => new JavaImageInputStream(stream),
            _ => throw new ArgumentException(
                "Image input must be a readable stream.",
                nameof(source))
        };

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
        return JavaCompat.Iterator(
            formatName.Equals("JPEG", StringComparison.OrdinalIgnoreCase) ||
            formatName.Equals("JPG", StringComparison.OrdinalIgnoreCase)
                ? new[] { new JavaImageReader("JPEG") }
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

public sealed class JavaX509CertificateHolder
{
    private readonly X509Certificate2 certificate;

    public JavaX509CertificateHolder(sbyte[] encoded)
    {
        ArgumentNullException.ThrowIfNull(encoded);
        certificate = X509CertificateLoader.LoadCertificate(
            MemoryMarshal.AsBytes(encoded.AsSpan()));
    }

    public string Issuer => certificate.Issuer;
    public string GetIssuer() => certificate.Issuer;
    internal X509Certificate2 Certificate => certificate;
}

public static class PdfCubeCrypto
{
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
            (AsymmetricAlgorithm?)certificate.GetDSAPublicKey() ??
            throw new CryptographicException(
                $"Unsupported certificate public-key algorithm `{certificate.GetKeyAlgorithm()}`.");
    }

    public static BigInteger GetSerialNumber(X509Certificate2 certificate)
    {
        ArgumentNullException.ThrowIfNull(certificate);
        return new BigInteger(
            Convert.FromHexString(certificate.SerialNumber),
            isUnsigned: true,
            isBigEndian: true);
    }
}

public static class JavaAsn1Encoding
{
    public const string DER = "DER";
}

public class JavaAsn1Primitive
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
        destination.Write(Encode());
    }
}

public sealed class JavaAsn1InputStream : IDisposable
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

public sealed class JavaAsn1ObjectIdentifier : JavaAsn1Primitive
{
    public JavaAsn1ObjectIdentifier(string id)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(id);
        Id = id;
    }

    public string Id { get; }
    public string GetId() => Id;
}

public sealed class JavaDerOctetString : JavaAsn1Primitive
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

public class JavaAsn1Set : JavaAsn1Primitive
{
    internal JavaAsn1Set(IReadOnlyList<JavaAsn1Primitive> values)
    {
        Values = values;
    }

    internal IReadOnlyList<JavaAsn1Primitive> Values { get; }
}

public sealed class JavaDerSet : JavaAsn1Set
{
    public JavaDerSet(JavaAsn1Primitive value)
        : base(new[] { value })
    {
    }
}

public sealed class JavaAlgorithmIdentifier : JavaAsn1Primitive
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

public sealed class JavaSubjectPublicKeyInfo
{
    internal JavaSubjectPublicKeyInfo(JavaAlgorithmIdentifier algorithm)
    {
        Algorithm = algorithm;
    }

    internal JavaAlgorithmIdentifier Algorithm { get; }
    public JavaAlgorithmIdentifier GetAlgorithm() => Algorithm;
}

public sealed class JavaTbsCertificate
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

public static class JavaPkcsObjectIdentifiers
{
    public static readonly JavaAsn1ObjectIdentifier RC2_CBC =
        new("1.2.840.113549.3.2");
    public static readonly JavaAsn1ObjectIdentifier Data =
        new("1.2.840.113549.1.7.1");
    public static readonly JavaAsn1ObjectIdentifier EnvelopedData =
        new("1.2.840.113549.1.7.3");
}

public sealed class JavaIssuerAndSerialNumber : JavaAsn1Primitive
{
    public JavaIssuerAndSerialNumber(string issuer, BigInteger serialNumber)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(issuer);
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

public sealed class JavaRecipientIdentifier : JavaAsn1Primitive
{
    public JavaRecipientIdentifier(JavaIssuerAndSerialNumber issuerAndSerial)
    {
        IssuerAndSerial = issuerAndSerial ??
            throw new ArgumentNullException(nameof(issuerAndSerial));
    }

    internal JavaIssuerAndSerialNumber IssuerAndSerial { get; }
}

public sealed class JavaKeyTransRecipientInfo : JavaAsn1Primitive
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

public sealed class JavaRecipientInfo : JavaAsn1Primitive
{
    public JavaRecipientInfo(JavaKeyTransRecipientInfo info)
    {
        Info = info ?? throw new ArgumentNullException(nameof(info));
    }

    internal JavaKeyTransRecipientInfo Info { get; }
}

public sealed class JavaEncryptedContentInfo : JavaAsn1Primitive
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

public sealed class JavaEnvelopedData : JavaAsn1Primitive
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

public sealed class JavaCmsContentInfo : JavaAsn1Primitive
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

public class JavaRecipientId
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
        PdfCubeCrypto.GetSerialNumber(holder.Certificate) == SerialNumber &&
        string.Equals(
            new X500DistinguishedName(holder.Certificate.IssuerName.RawData).Name,
            Issuer,
            StringComparison.OrdinalIgnoreCase);
}

public sealed class JavaKeyTransRecipientId : JavaRecipientId
{
    internal JavaKeyTransRecipientId(string issuer, BigInteger serialNumber)
        : base(issuer, serialNumber)
    {
    }

    public BigInteger GetSerialNumber() => SerialNumber;
    public string GetIssuer() => Issuer;
}

public sealed class JavaJceKeyTransEnvelopedRecipient
{
    public JavaJceKeyTransEnvelopedRecipient(AsymmetricAlgorithm privateKey)
    {
        PrivateKey = privateKey ??
            throw new ArgumentNullException(nameof(privateKey));
    }

    internal AsymmetricAlgorithm PrivateKey { get; }
}

public sealed class JavaRecipientInformation
{
    private readonly JavaKeyTransRecipientId recipientId;
    private readonly byte[] encryptedKey;
    private readonly byte[] encryptedContent;
    private readonly byte[] iv;

    internal JavaRecipientInformation(
        JavaKeyTransRecipientId recipientId,
        byte[] encryptedKey,
        byte[] encryptedContent,
        byte[] iv)
    {
        this.recipientId = recipientId;
        this.encryptedKey = encryptedKey;
        this.encryptedContent = encryptedContent;
        this.iv = iv;
    }

    public JavaRecipientId GetRid() => recipientId;

    public sbyte[] GetContent(JavaJceKeyTransEnvelopedRecipient recipient)
    {
        if (recipient.PrivateKey is not RSA rsa)
            throw new CryptographicException(
                "CMS key-transport decryption requires an RSA private key.");
        var contentKey = rsa.Decrypt(encryptedKey, RSAEncryptionPadding.Pkcs1);
        using var rc2 = RC2.Create();
        rc2.Key = contentKey;
        rc2.IV = iv;
        rc2.Mode = CipherMode.CBC;
        rc2.Padding = PaddingMode.PKCS7;
        using var decryptor = rc2.CreateDecryptor();
        return JavaCompat.ToSignedBytes(
            decryptor.TransformFinalBlock(
                encryptedContent, 0, encryptedContent.Length));
    }
}

public sealed class JavaRecipientInformationStore
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

public sealed class JavaCmsEnvelopedData
{
    private readonly JavaRecipientInformationStore recipientInfos;

    public JavaCmsEnvelopedData(sbyte[] encoded)
    {
        ArgumentNullException.ThrowIfNull(encoded);
        recipientInfos = new JavaRecipientInformationStore(
            Parse(JavaCompat.ToUnsignedBytes(encoded)));
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
        if (!string.Equals(
                algorithm,
                JavaPkcsObjectIdentifiers.RC2_CBC.Id,
                StringComparison.Ordinal))
            throw new CryptographicException(
                $"CMS content cipher `{algorithm}` is unsupported.");
        var parameters = contentAlgorithm.ReadSequence();
        if (parameters.HasData &&
            parameters.PeekTag().HasSameClassAndValue(Asn1Tag.Integer))
        {
            parameters.ReadInteger();
        }
        var iv = parameters.ReadOctetString();
        var encryptedContent = encryptedInfo.ReadOctetString(
            new Asn1Tag(TagClass.ContextSpecific, 0));

        return recipients
            .Select(recipient => new JavaRecipientInformation(
                new JavaKeyTransRecipientId(
                    recipient.Issuer,
                    recipient.SerialNumber),
                recipient.EncryptedKey,
                encryptedContent,
                iv))
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
            return Math.Clamp(density, 1, ushort.MaxValue);
        }
    }

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

    internal JavaImageReader(string formatName) => this.formatName = formatName;

    public bool CanReadRaster => true;

    public void SetInput(object source) => SetInput(source, false, false);

    public void SetInput(
        object source,
        bool seekForwardOnly,
        bool ignoreMetadata)
    {
        input = source as JavaImageInputStream ??
            throw new ArgumentException(
                "ImageReader input must be an ImageInputStream.",
                nameof(source));
        _ = seekForwardOnly;
        _ = ignoreMetadata;
    }

    public JavaImageReadParam GetDefaultReadParam() => new();

    public int GetWidth(int imageIndex)
    {
        RequireImageIndex(imageIndex);
        using var bitmap = Decode();
        return bitmap.Width;
    }

    public int GetHeight(int imageIndex)
    {
        RequireImageIndex(imageIndex);
        using var bitmap = Decode();
        return bitmap.Height;
    }

    public SKBitmap Read(int imageIndex, JavaImageReadParam? parameters)
    {
        RequireImageIndex(imageIndex);
        var bitmap = Decode();
        return ApplyReadParameters(bitmap, parameters);
    }

    public JavaRaster ReadRaster(
        int imageIndex,
        JavaImageReadParam? parameters) =>
        new(Read(imageIndex, parameters));

    public JavaImageMetadata GetImageMetadata(int imageIndex)
    {
        RequireImageIndex(imageIndex);
        return new JavaImageMetadata(RequireInput().Bytes);
    }

    public void Dispose()
    {
        input = null;
    }

    private SKBitmap Decode()
    {
        using var stream = new MemoryStream(
            RequireInput().Bytes,
            writable: false);
        return SKBitmap.Decode(stream) ??
            throw new IOException(
                $"Unable to decode {formatName} image data.");
    }

    private JavaImageInputStream RequireInput() =>
        input ?? throw new InvalidOperationException(
            "No image input has been assigned.");

    private static void RequireImageIndex(int imageIndex)
    {
        if (imageIndex != 0)
            throw new IndexOutOfRangeException(
                "This image reader exposes a single image.");
    }

    private static SKBitmap ApplyReadParameters(
        SKBitmap source,
        JavaImageReadParam? parameters)
    {
        if (parameters is null ||
            (!parameters.SourceRegion.HasValue &&
             parameters.SubsamplingX == 1 &&
             parameters.SubsamplingY == 1))
        {
            return source;
        }

        var sourceRegion = parameters.SourceRegion ??
            new SKRectI(0, 0, source.Width, source.Height);
        sourceRegion = new SKRectI(
            Math.Max(0, sourceRegion.Left),
            Math.Max(0, sourceRegion.Top),
            Math.Min(source.Width, sourceRegion.Right),
            Math.Min(source.Height, sourceRegion.Bottom));
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
            source.Dispose();
            throw new ArgumentException(
                "The source region and subsampling produce an empty image.");
        }

        var destination = new SKBitmap(
            new SKImageInfo(
                outputWidth,
                outputHeight,
                SKColorType.Bgra8888,
                source.AlphaType));
        for (var y = 0; y < outputHeight; y++)
        {
            for (var x = 0; x < outputWidth; x++)
            {
                destination.SetPixel(
                    x,
                    y,
                    source.GetPixel(
                        firstX + x * parameters.SubsamplingX,
                        firstY + y * parameters.SubsamplingY));
            }
        }
        source.Dispose();
        return destination;
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
            PdfCubeFontCompat.GetColorModel(imageType.Image).NumberOfColorComponents);
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
        : base(new JavaRaster(PdfCubeFontCompat.DATA_BUFFER_TYPE_BYTE, size, 1, 1))
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
        : base(new JavaRaster(PdfCubeFontCompat.DATA_BUFFER_TYPE_USHORT, size, 1, 1))
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

    public int[] GetData()
    {
        var values = new int[Size];
        for (var index = 0; index < values.Length; index++) values[index] = GetElement(index);
        return values;
    }
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
    private readonly bool packedBinary;

    internal JavaRaster(SKBitmap bitmap)
    {
        this.bitmap = bitmap;
        width = bitmap.Width;
        height = bitmap.Height;
        numberOfBands =
            bitmap.ColorType == SKColorType.Gray8 ? 1 :
            bitmap.AlphaType == SKAlphaType.Opaque ? 3 : 4;
        transferType = PdfCubeFontCompat.GetImageType(bitmap) switch
        {
            PdfCubeFontCompat.TYPE_INT_RGB or
            PdfCubeFontCompat.TYPE_INT_ARGB or
            PdfCubeFontCompat.TYPE_INT_BGR => PdfCubeFontCompat.DATA_BUFFER_TYPE_INT,
            _ => PdfCubeFontCompat.DATA_BUFFER_TYPE_BYTE
        };
        pixelStride = numberOfBands;
        scanlineStride = checked(width * pixelStride);
        bandOffsets = Enumerable.Range(0, numberOfBands).ToArray();
    }

    internal JavaRaster(int dataType, int width, int height, int bands)
    {
        if (dataType is not PdfCubeFontCompat.DATA_BUFFER_TYPE_BYTE and
            not PdfCubeFontCompat.DATA_BUFFER_TYPE_USHORT and
            not PdfCubeFontCompat.DATA_BUFFER_TYPE_INT)
            throw new ArgumentException("Unsupported raster data type.", nameof(dataType));
        if (width <= 0 || height <= 0 || bands <= 0)
            throw new ArgumentException("Raster dimensions and band count must be positive.");
        this.width = width;
        this.height = height;
        numberOfBands = bands;
        transferType = dataType;
        pixelStride = bands;
        scanlineStride = checked(width * pixelStride);
        bandOffsets = Enumerable.Range(0, bands).ToArray();
        var length = checked(width * height * bands);
        storage = dataType switch
        {
            PdfCubeFontCompat.DATA_BUFFER_TYPE_BYTE => new sbyte[length],
            PdfCubeFontCompat.DATA_BUFFER_TYPE_USHORT => new short[length],
            _ => new int[length]
        };
    }

    private JavaRaster(int width, int height, sbyte[] packedBinaryData)
    {
        this.width = width;
        this.height = height;
        numberOfBands = 1;
        transferType = PdfCubeFontCompat.DATA_BUFFER_TYPE_BYTE;
        pixelStride = 0;
        scanlineStride = checked((width + 7) / 8);
        bandOffsets = [0];
        storage = packedBinaryData;
        packedBinary = true;
    }

    internal static JavaRaster BinarySnapshot(SKBitmap bitmap)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        var scanlineStride = checked((bitmap.Width + 7) / 8);
        var data = new sbyte[checked(scanlineStride * bitmap.Height)];
        for (var y = 0; y < bitmap.Height; y++)
        {
            for (var x = 0; x < bitmap.Width; x++)
            {
                if (bitmap.GetPixel(x, y).Red < 128) continue;
                var index = y * scanlineStride + x / 8;
                data[index] = unchecked(
                    (sbyte)(unchecked((byte)data[index]) | 1 << (7 - x % 8)));
            }
        }
        return new JavaRaster(bitmap.Width, bitmap.Height, data);
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
    internal int StorageSize => storage?.Length ?? checked(width * height);

    public JavaRaster CreateCompatibleWritableRaster() =>
        packedBinary
            ? new JavaRaster(
                Width,
                Height,
                new sbyte[checked(((Width + 7) / 8) * Height)])
            : new(TransferType, Width, Height, NumberOfBands);

    public JavaDataBuffer GetDataBuffer() =>
        TransferType switch
        {
            PdfCubeFontCompat.DATA_BUFFER_TYPE_BYTE => new JavaDataBufferByte(this),
            PdfCubeFontCompat.DATA_BUFFER_TYPE_USHORT => new JavaDataBufferUShort(this),
            _ => new JavaDataBufferInt(this)
        };

    public int[] GetSamples(int x, int y, int width, int height, int band, int[]? samples)
    {
        ValidateRegion(x, y, width, height);
        if ((uint)band >= (uint)NumberOfBands) throw new IndexOutOfRangeException();
        samples ??= new int[checked(width * height)];
        if (samples.Length < width * height) throw new IndexOutOfRangeException();
        var offset = 0;
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

    public object GetDataElements(
        int x,
        int y,
        int width,
        int height,
        object? output)
    {
        ValidateRegion(x, y, width, height);
        if (bitmap is not null && TransferType == PdfCubeFontCompat.DATA_BUFFER_TYPE_INT)
        {
            var values = output as int[] ?? new int[checked(width * height)];
            if (values.Length < width * height) throw new IndexOutOfRangeException();
            var offset = 0;
            var imageType = PdfCubeFontCompat.GetImageType(bitmap);
            for (var row = y; row < y + height; row++)
            {
                for (var column = x; column < x + width; column++)
                {
                    var color = bitmap.GetPixel(column, row);
                    values[offset++] = imageType == PdfCubeFontCompat.TYPE_INT_BGR
                        ? color.Red | color.Green << 8 | color.Blue << 16
                        : unchecked((int)((imageType == PdfCubeFontCompat.TYPE_INT_ARGB
                                               ? (uint)color.Alpha << 24
                                               : 0) |
                                          (uint)color.Red << 16 |
                                          (uint)color.Green << 8 |
                                          color.Blue));
                }
            }
            return values;
        }

        var length = checked(width * height * NumberOfBands);
        if (TransferType == PdfCubeFontCompat.DATA_BUFFER_TYPE_USHORT)
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

        if (TransferType == PdfCubeFontCompat.DATA_BUFFER_TYPE_INT)
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
            TransferType == PdfCubeFontCompat.DATA_BUFFER_TYPE_INT &&
            values is int[] packed)
        {
            if (packed.Length == 0) throw new IndexOutOfRangeException();
            SetStorageElement(y * Width + x, packed[0]);
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
        for (var band = 0; band < NumberOfBands; band++)
        {
            SetComponent(x, y, band, values[band]);
        }
    }

    public int[] GetPixel(int x, int y, int[]? values)
    {
        ValidateRegion(x, y, 1, 1);
        values ??= new int[NumberOfBands];
        if (values.Length < NumberOfBands) throw new IndexOutOfRangeException();
        for (var band = 0; band < NumberOfBands; band++)
            values[band] = GetComponent(x, y, band);
        return values;
    }

    public float[] GetPixel(int x, int y, float[]? values)
    {
        ValidateRegion(x, y, 1, 1);
        values ??= new float[NumberOfBands];
        if (values.Length < NumberOfBands) throw new IndexOutOfRangeException();
        for (var band = 0; band < NumberOfBands; band++)
            values[band] = GetComponent(x, y, band);
        return values;
    }

    public void SetPixel(int x, int y, float[] values)
    {
        ArgumentNullException.ThrowIfNull(values);
        ValidateRegion(x, y, 1, 1);
        if (values.Length < NumberOfBands) throw new IndexOutOfRangeException();
        for (var band = 0; band < NumberOfBands; band++)
            SetComponent(x, y, band, (int)values[band]);
    }

    internal int GetStorageElement(int index)
    {
        if ((uint)index >= (uint)StorageSize) throw new IndexOutOfRangeException();
        if (storage is sbyte[] bytes) return unchecked((byte)bytes[index]);
        if (storage is short[] words) return unchecked((ushort)words[index]);
        if (storage is int[] integers) return integers[index];
        var color = bitmap!.GetPixel(index % width, index / width);
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
            bitmap!.SetPixel(x, y, new SKColor(gray, gray, gray));
            return;
        }
        bitmap!.SetPixel(
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
        if (packedBinary)
        {
            var index = y * scanlineStride + x / 8;
            return (unchecked((byte)((sbyte[])storage!)[index]) >> (7 - x % 8)) & 1;
        }
        return storage is not null
            ? GetStorageElement(StorageIndex(x, y, band))
            : Component(bitmap!.GetPixel(x, y), band);
    }

    private void SetComponent(int x, int y, int band, int value)
    {
        if ((uint)band >= (uint)NumberOfBands) throw new IndexOutOfRangeException();
        if (packedBinary)
        {
            var bytes = (sbyte[])storage!;
            var index = y * scanlineStride + x / 8;
            var mask = 1 << (7 - x % 8);
            var current = unchecked((byte)bytes[index]);
            bytes[index] = unchecked((sbyte)(
                value == 0 ? current & ~mask : current | mask));
            return;
        }
        if (storage is not null)
        {
            SetStoredValue(StorageIndex(x, y, band), value);
            return;
        }
        var color = bitmap!.GetPixel(x, y);
        bitmap.SetPixel(x, y, WithComponent(color, band, value));
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

    internal sbyte[] GetByteData()
    {
        if (storage is null) MaterializeStorage(PdfCubeFontCompat.DATA_BUFFER_TYPE_BYTE);
        return storage as sbyte[]
            ?? throw new InvalidOperationException("Raster is not byte-backed.");
    }

    internal short[] GetUShortData()
    {
        if (storage is null) MaterializeStorage(PdfCubeFontCompat.DATA_BUFFER_TYPE_USHORT);
        return storage as short[]
            ?? throw new InvalidOperationException("Raster is not unsigned-short-backed.");
    }

    private void MaterializeStorage(int dataType)
    {
        if (bitmap is null || TransferType != dataType)
            throw new InvalidOperationException("Raster storage type does not match.");
        storage = dataType == PdfCubeFontCompat.DATA_BUFFER_TYPE_BYTE
            ? new sbyte[checked(Width * Height * NumberOfBands)]
            : new short[checked(Width * Height * NumberOfBands)];
        for (var y = 0; y < Height; y++)
        {
            for (var x = 0; x < Width; x++)
            {
                var color = bitmap.GetPixel(x, y);
                for (var band = 0; band < NumberOfBands; band++)
                {
                    SetStoredValue((y * Width + x) * NumberOfBands + band, Component(color, band));
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
        return band switch
        {
            0 => new SKColor(component, color.Green, color.Blue, color.Alpha),
            1 => new SKColor(color.Red, component, color.Blue, color.Alpha),
            2 => new SKColor(color.Red, color.Green, component, color.Alpha),
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

public sealed class PdfCubeAffineTransformOp
{
    public const int TYPE_BILINEAR = 2;
    public const int TYPE_BICUBIC = 3;

    private readonly int interpolationType;

    public PdfCubeAffineTransformOp(SKMatrix transform, int interpolationType)
    {
        _ = transform;
        if (interpolationType is not TYPE_BILINEAR and not TYPE_BICUBIC)
            throw new ArgumentException("Unsupported image interpolation type.", nameof(interpolationType));
        this.interpolationType = interpolationType;
    }

    public SKBitmap Filter(SKBitmap source, SKBitmap destination)
    {
        ArgumentNullException.ThrowIfNull(source);
        ArgumentNullException.ThrowIfNull(destination);
        using var canvas = new SKCanvas(destination);
        using var paint = new SKPaint();
        var sampling =
            interpolationType == TYPE_BICUBIC
                ? new SKSamplingOptions(SKCubicResampler.Mitchell)
                : new SKSamplingOptions(SKFilterMode.Linear);
        canvas.DrawBitmap(
            source,
            new SKRect(0, 0, destination.Width, destination.Height),
            sampling,
            paint);
        return destination;
    }
}

public sealed class JavaColorConvertOp
{
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
        for (var y = 0; y < source.Height; y++)
        {
            for (var x = 0; x < source.Width; x++)
            {
                var color = source.GetPixel(x, y);
                destination.SetPixel(
                    x,
                    y,
                    new SKColor(
                        color.Red,
                        color.Green,
                        color.Blue,
                        destination.AlphaType == SKAlphaType.Opaque
                            ? byte.MaxValue
                            : color.Alpha));
            }
        }
        return destination;
    }
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
        PdfCubeRenderingHints? renderingHints)
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
        for (var y = 0; y < source.Height; y++)
        {
            for (var x = 0; x < source.Width; x++)
            {
                var color = source.GetPixel(x, y);
                destination.SetPixel(
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
        PdfCubeRenderingHints hints)
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
            ? PdfCubeTransparency.OPAQUE
            : Alpha == 0
                ? PdfCubeTransparency.BITMASK
                : PdfCubeTransparency.TRANSLUCENT;

    public bool Equals(JavaColor? other) =>
        other is not null && Value == other.Value;

    public override bool Equals(object? other) =>
        other is JavaColor color && Equals(color);

    public override int GetHashCode() => Value.GetHashCode();

    public static implicit operator SKColor(JavaColor color) => color.Value;
    public static implicit operator JavaColor(SKColor color) => new(color);

    private sealed class SolidPaintContext : JavaPaintContext
    {
        private readonly SKColor color;
        private readonly JavaColorModel colorModel =
            new(PdfCubeFontCompat.TYPE_INT_ARGB);

        internal SolidPaintContext(SKColor color) => this.color = color;

        public JavaColorModel GetColorModel() => colorModel;

        public JavaRaster GetRaster(int x, int y, int width, int height)
        {
            _ = x;
            _ = y;
            if (width <= 0 || height <= 0)
                throw new ArgumentException("Raster dimensions must be positive.");
            var bitmap = new SKBitmap(
                width,
                height,
                SKColorType.Bgra8888,
                color.Alpha == byte.MaxValue
                    ? SKAlphaType.Opaque
                    : SKAlphaType.Unpremul);
            bitmap.Erase(color);
            return new JavaRaster(bitmap);
        }

        public void Dispose()
        {
        }
    }
}

public interface JavaPaint
{
    JavaPaintContext CreateContext(
        JavaColorModel colorModel,
        SKRectI deviceBounds,
        SKRect userBounds,
        SKMatrix transform,
        PdfCubeRenderingHints hints);

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
        PdfCubeRenderingHints hints)
    {
        _ = colorModel;
        _ = deviceBounds;
        _ = userBounds;
        _ = hints;
        return new TexturePaintContext(Image, Anchor, transform);
    }

    public int GetTransparency() =>
        Image.AlphaType == SKAlphaType.Opaque
            ? PdfCubeTransparency.OPAQUE
            : PdfCubeTransparency.TRANSLUCENT;

    private sealed class TexturePaintContext : JavaPaintContext
    {
        private readonly SKBitmap image;
        private readonly SKRect anchor;
        private readonly SKMatrix deviceToUser;
        private readonly JavaColorModel colorModel;

        internal TexturePaintContext(
            SKBitmap image,
            SKRect anchor,
            SKMatrix userToDevice)
        {
            if (anchor.Width == 0 || anchor.Height == 0)
                throw new ArgumentException(
                    "Texture paint anchor dimensions cannot be zero.",
                    nameof(anchor));
            this.image = image;
            this.anchor = anchor;
            deviceToUser = PdfCubeFontCompat.CreateInverse(userToDevice);
            colorModel = PdfCubeFontCompat.GetColorModel(image);
        }

        public JavaColorModel GetColorModel() => colorModel;

        public JavaRaster GetRaster(int x, int y, int width, int height)
        {
            if (width <= 0 || height <= 0)
                throw new ArgumentException("Raster dimensions must be positive.");
            var bitmap = new SKBitmap(
                width,
                height,
                image.ColorType,
                image.AlphaType,
                image.ColorSpace);
            for (var row = 0; row < height; row++)
            {
                for (var column = 0; column < width; column++)
                {
                    var deviceX = x + column;
                    var deviceY = y + row;
                    var userX =
                        deviceToUser.ScaleX * deviceX +
                        deviceToUser.SkewX * deviceY +
                        deviceToUser.TransX;
                    var userY =
                        deviceToUser.SkewY * deviceX +
                        deviceToUser.ScaleY * deviceY +
                        deviceToUser.TransY;
                    var imageX = Mod(
                        (int)Math.Floor(
                            (userX - anchor.Left) / anchor.Width * image.Width),
                        image.Width);
                    var imageY = Mod(
                        (int)Math.Floor(
                            (userY - anchor.Top) / anchor.Height * image.Height),
                        image.Height);
                    bitmap.SetPixel(column, row, image.GetPixel(imageX, imageY));
                }
            }
            return new JavaRaster(bitmap);
        }

        public void Dispose()
        {
        }

        private static int Mod(int value, int modulus)
        {
            var result = value % modulus;
            return result < 0 ? result + modulus : result;
        }
    }
}

public sealed class PdfCubeRenderingHints : Dictionary<object, object>
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

    public PdfCubeRenderingHints(object? initialValues)
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
    public const int CLASS_DISPLAY = 1;
    public const int icSigDisplayClass = 1835955314;
    public const int icPerceptual = 0;
    public const int icSigHead = 1751474532;
    public const int icHdrDeviceClass = 12;
    public const int icHdrModel = 52;
    public const int icHdrRenderingIntent = 64;

    private readonly sbyte[] data;

    internal JavaIccProfile(sbyte[] data)
    {
        ArgumentNullException.ThrowIfNull(data);
        this.data = (sbyte[])data.Clone();
    }

    public sbyte[] GetData() => (sbyte[])data.Clone();

    public sbyte[] GetData(int tag)
    {
        if (tag != icSigHead)
            throw new ArgumentException(
                $"Unsupported ICC profile tag `{tag}`.",
                nameof(tag));
        if (data.Length < 128)
            throw new ArgumentException("ICC profile header is truncated.");
        return data[..128];
    }

    public int GetProfileClass()
    {
        if (data.Length < icHdrDeviceClass + sizeof(int))
            throw new ArgumentException("ICC profile header is truncated.");
        var signature =
            unchecked((byte)data[icHdrDeviceClass]) << 24 |
            unchecked((byte)data[icHdrDeviceClass + 1]) << 16 |
            unchecked((byte)data[icHdrDeviceClass + 2]) << 8 |
            unchecked((byte)data[icHdrDeviceClass + 3]);
        return signature == icSigDisplayClass ? CLASS_DISPLAY : 0;
    }

    public int GetColorSpaceType()
    {
        if (data.Length < 20)
            throw new ArgumentException("ICC profile header is truncated.");
        var signature = string.Create(
            4,
            data,
            static (characters, bytes) =>
            {
                for (var index = 0; index < characters.Length; index++)
                    characters[index] =
                        (char)unchecked((byte)bytes[index + 16]);
            });
        return signature switch
        {
            "XYZ " => 0,
            "Lab " => 1,
            "Luv " => 2,
            "YCbr" => 3,
            "Yxy " => 4,
            "RGB " => JavaColorSpace.TYPE_RGB,
            "GRAY" => JavaColorSpace.TYPE_GRAY,
            "HSV " => 7,
            "HLS " => 8,
            "CMYK" => JavaColorSpace.TYPE_CMYK,
            "CMY " => 11,
            _ when signature.EndsWith("CLR", StringComparison.Ordinal) &&
                   Uri.IsHexDigit(signature[0]) =>
                Convert.ToInt32(signature[0].ToString(), 16) + 10,
            _ => throw new ArgumentException(
                $"Unsupported ICC profile color-space signature `{signature}`.")
        };
    }

    public int NumberOfComponents
    {
        get
        {
            if (data.Length < 20) throw new InvalidOperationException("ICC profile header is truncated.");
            var signature = string.Create(
                4,
                data,
                static (characters, bytes) =>
                {
                    for (var index = 0; index < characters.Length; index++)
                        characters[index] = (char)unchecked((byte)bytes[index + 16]);
                });
            if (signature.Length == 4 &&
                signature[1] == 'C' &&
                signature[2] == 'L' &&
                signature[3] == 'R' &&
                Uri.IsHexDigit(signature[0]))
            {
                return Convert.ToInt32(signature[0].ToString(), 16);
            }
            return signature switch
            {
                "GRAY" => 1,
                "CMYK" => 4,
                "RGB " or "XYZ " or "Lab " or "Luv " or "YCbr" or "Yxy " or
                    "HSV " or "HLS " or "CMY " => 3,
                _ => throw new InvalidOperationException(
                    $"Unsupported ICC profile color-space signature '{signature}'.")
            };
        }
    }
}

public static class PdfCubeTransparency
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
    public object CreateStrokedShape(object shape) => shape;
}

public interface JavaComposite
{
    JavaCompositeContext CreateContext(
        JavaColorModel sourceColorModel,
        JavaColorModel destinationColorModel,
        PdfCubeRenderingHints? hints);
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

    public static JavaAlphaComposite GetInstance(int rule, float alpha)
    {
        if (rule != SRC_OVER)
            throw new ArgumentException("Only SRC_OVER alpha compositing is supported.", nameof(rule));
        if (!float.IsFinite(alpha) || alpha < 0 || alpha > 1)
            throw new ArgumentException("Alpha must be between zero and one.", nameof(alpha));
        return new JavaAlphaComposite(alpha);
    }

    public JavaCompositeContext CreateContext(
        JavaColorModel sourceColorModel,
        JavaColorModel destinationColorModel,
        PdfCubeRenderingHints? hints)
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

                    var sourceRgb = sourceColorModel.ColorSpace.ToRgb(sourceComponents);
                    var destinationRgb =
                        destinationColorModel.ColorSpace.ToRgb(destinationComponents);
                    var resultRgb = new float[3];
                    for (var component = 0; component < resultRgb.Length; component++)
                    {
                        resultRgb[component] = resultAlpha == 0
                            ? 0
                            : (sourceRgb[component] * sourceAlpha +
                               destinationRgb[component] * destinationAlpha *
                               (1f - sourceAlpha)) / resultAlpha;
                    }

                    var result = destinationColorModel.ColorSpace.FromRgb(resultRgb);
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
    private readonly int explicitDataType;
    private readonly int? explicitPixelSize;

    internal JavaColorModel(int imageType)
    {
        this.imageType = imageType;
        ColorSpace = imageType is PdfCubeFontCompat.TYPE_BYTE_GRAY or PdfCubeFontCompat.TYPE_BYTE_BINARY
            ? new JavaColorSpace(JavaColorSpace.CS_GRAY)
            : new JavaIccColorSpace(
                JavaColorSpace.CS_sRGB,
                PdfCubeFontCompat.GetIccProfile(JavaColorSpace.CS_sRGB));
    }

    internal JavaColorModel(
        JavaColorSpace colorSpace,
        bool hasAlpha,
        int dataType)
    {
        imageType = PdfCubeFontCompat.TYPE_CUSTOM;
        explicitColorSpace = colorSpace ??
            throw new ArgumentNullException(nameof(colorSpace));
        explicitAlpha = hasAlpha;
        explicitDataType = dataType;
        ColorSpace = colorSpace;
    }

    internal JavaColorModel(
        int pixelBits,
        int mapSize,
        sbyte[] red,
        sbyte[] green,
        sbyte[] blue)
    {
        if (pixelBits is <= 0 or > 16)
            throw new ArgumentOutOfRangeException(nameof(pixelBits));
        if (mapSize <= 0 ||
            red.Length < mapSize ||
            green.Length < mapSize ||
            blue.Length < mapSize)
        {
            throw new ArgumentException(
                "The indexed color arrays must contain every palette entry.");
        }

        imageType = PdfCubeFontCompat.TYPE_CUSTOM;
        explicitColorSpace = new JavaIccColorSpace(
            JavaColorSpace.CS_sRGB,
            PdfCubeFontCompat.GetIccProfile(JavaColorSpace.CS_sRGB));
        explicitAlpha = false;
        explicitDataType = PdfCubeFontCompat.DATA_BUFFER_TYPE_BYTE;
        explicitPixelSize = pixelBits;
        ColorSpace = explicitColorSpace;
        Palette = new SKColor[mapSize];
        for (var index = 0; index < mapSize; index++)
        {
            Palette[index] = new SKColor(
                unchecked((byte)red[index]),
                unchecked((byte)green[index]),
                unchecked((byte)blue[index]));
        }
    }

    internal SKColor[]? Palette { get; }

    public int PixelSize => imageType switch
    {
        PdfCubeFontCompat.TYPE_CUSTOM when explicitPixelSize.HasValue =>
            explicitPixelSize.Value,
        PdfCubeFontCompat.TYPE_CUSTOM =>
            NumberOfComponents * (explicitDataType ==
                PdfCubeFontCompat.DATA_BUFFER_TYPE_USHORT ? 16 : 8),
        PdfCubeFontCompat.TYPE_BYTE_BINARY => 1,
        PdfCubeFontCompat.TYPE_BYTE_GRAY => 8,
        PdfCubeFontCompat.TYPE_3BYTE_BGR => 24,
        _ => 32
    };

    public bool HasAlpha =>
        explicitAlpha ??
        imageType is PdfCubeFontCompat.TYPE_INT_ARGB or PdfCubeFontCompat.TYPE_4BYTE_ABGR;
    public int NumberOfComponents => NumberOfColorComponents + (HasAlpha ? 1 : 0);
    public int NumberOfColorComponents =>
        explicitColorSpace?.NumberOfComponents ??
        (imageType is PdfCubeFontCompat.TYPE_BYTE_GRAY or
            PdfCubeFontCompat.TYPE_BYTE_BINARY ? 1 : 3);
    public JavaColorSpace ColorSpace { get; }

    public JavaRaster CreateCompatibleWritableRaster(int width, int height)
    {
        if (Palette is not null)
        {
            return new JavaRaster(
                PdfCubeFontCompat.DATA_BUFFER_TYPE_BYTE,
                width,
                height,
                1);
        }
        var dataType = explicitColorSpace is not null
            ? explicitDataType
            : imageType is PdfCubeFontCompat.TYPE_INT_RGB or
                PdfCubeFontCompat.TYPE_INT_ARGB or
                PdfCubeFontCompat.TYPE_INT_BGR
                ? PdfCubeFontCompat.DATA_BUFFER_TYPE_INT
                : PdfCubeFontCompat.DATA_BUFFER_TYPE_BYTE;
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

        if (pixel is int[] packed && packed.Length == 1 &&
            imageType is PdfCubeFontCompat.TYPE_INT_RGB or
                PdfCubeFontCompat.TYPE_INT_ARGB or
                PdfCubeFontCompat.TYPE_INT_BGR)
        {
            var value = packed[0];
            if (imageType == PdfCubeFontCompat.TYPE_INT_BGR)
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
            return components;
        }

        for (var component = 0; component < NumberOfComponents; component++)
        {
            components[offset + component] = pixel switch
            {
                sbyte[] bytes when component < bytes.Length =>
                    unchecked((byte)bytes[component]) / 255f,
                short[] words when component < words.Length =>
                    unchecked((ushort)words[component]) / 65535f,
                int[] values when component < values.Length =>
                    Math.Clamp(values[component] / 255f, 0f, 1f),
                _ => throw new ArgumentException(
                    "Pixel storage does not match the color model.",
                    nameof(pixel))
            };
        }
        return components;
    }

    public object GetDataElements(float[] components, int offset, object? pixel)
    {
        ArgumentNullException.ThrowIfNull(components);
        if (offset < 0 || components.Length < offset + NumberOfComponents)
            throw new IndexOutOfRangeException();
        static int ByteValue(float value) =>
            (int)MathF.Round(Math.Clamp(value, 0f, 1f) * 255f);

        if (imageType is PdfCubeFontCompat.TYPE_INT_RGB or
            PdfCubeFontCompat.TYPE_INT_ARGB or
            PdfCubeFontCompat.TYPE_INT_BGR)
        {
            var red = ByteValue(components[offset]);
            var green = ByteValue(components[offset + 1]);
            var blue = ByteValue(components[offset + 2]);
            var alpha = HasAlpha
                ? ByteValue(components[offset + NumberOfColorComponents])
                : 0;
            var packed = pixel as int[] ?? new int[1];
            if (packed.Length == 0) throw new IndexOutOfRangeException();
            packed[0] = imageType == PdfCubeFontCompat.TYPE_INT_BGR
                ? red | green << 8 | blue << 16
                : unchecked((int)((uint)alpha << 24 |
                                  (uint)red << 16 |
                                  (uint)green << 8 |
                                  (uint)blue));
            return packed;
        }

        if (explicitDataType == PdfCubeFontCompat.DATA_BUFFER_TYPE_USHORT)
        {
            var words = pixel as short[] ?? new short[NumberOfComponents];
            if (words.Length < NumberOfComponents) throw new IndexOutOfRangeException();
            for (var component = 0; component < NumberOfComponents; component++)
                words[component] = unchecked((short)(ushort)MathF.Round(
                    Math.Clamp(components[offset + component], 0f, 1f) * 65535f));
            return words;
        }

        var bytes = pixel as sbyte[] ?? new sbyte[NumberOfComponents];
        if (bytes.Length < NumberOfComponents) throw new IndexOutOfRangeException();
        for (var component = 0; component < NumberOfComponents; component++)
            bytes[component] = unchecked((sbyte)ByteValue(components[offset + component]));
        return bytes;
    }

    public int GetRed(object pixel) => GetRgbComponent(pixel, 0);

    public int GetGreen(object pixel) => GetRgbComponent(pixel, 1);

    public int GetBlue(object pixel) => GetRgbComponent(pixel, 2);

    public int GetAlpha(object pixel)
    {
        var components = GetNormalizedComponents(pixel, null, 0);
        return HasAlpha
            ? (int)MathF.Round(
                Math.Clamp(components[NumberOfColorComponents], 0f, 1f) * 255f)
            : 255;
    }

    private int GetRgbComponent(object pixel, int component)
    {
        var components = GetNormalizedComponents(pixel, null, 0);
        var rgb = ColorSpace.ToRgb(components);
        return (int)MathF.Round(Math.Clamp(rgb[component], 0f, 1f) * 255f);
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

    internal JavaColorSpace(int kind) => Kind = kind;
    internal int Kind { get; }
    public int Type => Kind switch
    {
        CS_sRGB => TYPE_RGB,
        CS_GRAY => TYPE_GRAY,
        CS_CIEXYZ => 0,
        TYPE_CMYK => TYPE_CMYK,
        _ => throw new InvalidOperationException("Unknown color space.")
    };
    public int NumberOfComponents => Type switch
    {
        TYPE_GRAY => 1,
        TYPE_CMYK => 4,
        _ => 3
    };
    public bool IsSrgb => Kind == CS_sRGB;

    public virtual float[] ToRgb(float[] components)
    {
        ArgumentNullException.ThrowIfNull(components);
        if (components.Length < NumberOfComponents)
            throw new ArgumentException("The component array is too short.", nameof(components));
        static float Clamp(float value) => Math.Clamp(value, 0f, 1f);
        return Type switch
        {
            TYPE_GRAY => new[]
            {
                Clamp(components[0]),
                Clamp(components[0]),
                Clamp(components[0])
            },
            TYPE_CMYK => new[]
            {
                1f - Math.Min(1f, Clamp(components[0]) + Clamp(components[3])),
                1f - Math.Min(1f, Clamp(components[1]) + Clamp(components[3])),
                1f - Math.Min(1f, Clamp(components[2]) + Clamp(components[3]))
            },
            _ => new[]
            {
                Clamp(components[0]),
                Clamp(components[1]),
                Clamp(components[2])
            }
        };
    }

    public virtual float[] FromRgb(float[] rgb)
    {
        ArgumentNullException.ThrowIfNull(rgb);
        if (rgb.Length < 3)
            throw new ArgumentException("RGB input must have three components.", nameof(rgb));
        var red = Math.Clamp(rgb[0], 0f, 1f);
        var green = Math.Clamp(rgb[1], 0f, 1f);
        var blue = Math.Clamp(rgb[2], 0f, 1f);
        if (Type == TYPE_GRAY)
            return new[] { red * 0.2126f + green * 0.7152f + blue * 0.0722f };
        if (Type != TYPE_CMYK) return new[] { red, green, blue };
        var black = 1f - Math.Max(red, Math.Max(green, blue));
        if (black >= 1f) return new[] { 0f, 0f, 0f, 1f };
        var scale = 1f - black;
        return new[]
        {
            (1f - red - black) / scale,
            (1f - green - black) / scale,
            (1f - blue - black) / scale,
            black
        };
    }

    public virtual float[] ToCieXyz(float[] components)
    {
        var rgb = ToRgb(components);
        static float Linear(float component) =>
            component <= 0.04045f
                ? component / 12.92f
                : MathF.Pow((component + 0.055f) / 1.055f, 2.4f);
        var red = Linear(rgb[0]);
        var green = Linear(rgb[1]);
        var blue = Linear(rgb[2]);
        return new[]
        {
            red * 0.4124564f + green * 0.3575761f + blue * 0.1804375f,
            red * 0.2126729f + green * 0.7151522f + blue * 0.0721750f,
            red * 0.0193339f + green * 0.1191920f + blue * 0.9503041f
        };
    }

    public virtual float[] FromCieXyz(float[] xyz)
    {
        ArgumentNullException.ThrowIfNull(xyz);
        if (xyz.Length < 3)
            throw new ArgumentException("XYZ input must have three components.", nameof(xyz));
        var red = xyz[0] * 3.2404542f - xyz[1] * 1.5371385f - xyz[2] * 0.4985314f;
        var green = -xyz[0] * 0.9692660f + xyz[1] * 1.8760108f + xyz[2] * 0.0415560f;
        var blue = xyz[0] * 0.0556434f - xyz[1] * 0.2040259f + xyz[2] * 1.0572252f;
        static float Gamma(float component) =>
            Math.Clamp(component <= 0.0031308f
                ? 12.92f * component
                : 1.055f * MathF.Pow(component, 1f / 2.4f) - 0.055f, 0f, 1f);
        return FromRgb(new[] { Gamma(red), Gamma(green), Gamma(blue) });
    }

    public virtual float GetMinValue(int component)
    {
        ValidateComponent(component);
        return 0f;
    }

    public virtual float GetMaxValue(int component)
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

public sealed class JavaIccColorSpace : JavaColorSpace
{
    internal JavaIccColorSpace(int kind, JavaIccProfile profile)
        : base(kind) => Profile = profile;

    public JavaIccColorSpace(JavaIccProfile profile)
        : base((profile ?? throw new ArgumentNullException(nameof(profile)))
            .NumberOfComponents switch
            {
                1 => CS_GRAY,
                3 => CS_sRGB,
                4 => TYPE_CMYK,
                _ => throw new ArgumentException(
                    "Only Gray, RGB, and CMYK ICC profiles are supported.",
                    nameof(profile))
            })
    {
        Profile = profile;
    }

    public JavaIccProfile Profile { get; }

    public override float[] ToRgb(float[] components)
    {
        ArgumentNullException.ThrowIfNull(components);
        if (components.Length < NumberOfComponents)
            throw new ArgumentException(
                "The component array is shorter than the ICC color space.",
                nameof(components));
        static float Clamp(float value) => Math.Clamp(value, 0f, 1f);
        return NumberOfComponents switch
        {
            1 => new[]
            {
                Clamp(components[0]),
                Clamp(components[0]),
                Clamp(components[0])
            },
            3 => new[]
            {
                Clamp(components[0]),
                Clamp(components[1]),
                Clamp(components[2])
            },
            4 => new[]
            {
                1f - Math.Min(1f, Clamp(components[0]) + Clamp(components[3])),
                1f - Math.Min(1f, Clamp(components[1]) + Clamp(components[3])),
                1f - Math.Min(1f, Clamp(components[2]) + Clamp(components[3]))
            },
            _ => throw new InvalidOperationException(
                "Unsupported ICC component count.")
        };
    }

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
    public const int PAGE_EXISTS = 0;
    public const int NO_SUCH_PAGE = 1;

    int Print(PdfCubeGraphics2D graphics, JavaPageFormat pageFormat, int pageIndex);
}

public sealed class JavaPaper
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

    public void SetSize(double width, double height)
    {
        if (!double.IsFinite(width) || !double.IsFinite(height) ||
            width <= 0 || height <= 0)
            throw new ArgumentException("Paper dimensions must be finite and positive.");
        this.width = width;
        this.height = height;
    }

    public void SetImageableArea(double x, double y, double width, double height)
    {
        if (!double.IsFinite(x) || !double.IsFinite(y) ||
            !double.IsFinite(width) || !double.IsFinite(height) ||
            width < 0 || height < 0)
            throw new ArgumentException("The imageable paper area is invalid.");
        imageableX = x;
        imageableY = y;
        imageableWidth = width;
        imageableHeight = height;
    }

    internal JavaPaper Copy()
    {
        var copy = new JavaPaper();
        copy.SetSize(width, height);
        copy.SetImageableArea(
            imageableX, imageableY, imageableWidth, imageableHeight);
        return copy;
    }
}

public sealed class JavaPageFormat
{
    public const int LANDSCAPE = 0;
    public const int PORTRAIT = 1;
    public const int REVERSE_LANDSCAPE = 2;

    private JavaPaper paper = new();
    private int orientation = PORTRAIT;

    public void SetPaper(JavaPaper paper)
    {
        ArgumentNullException.ThrowIfNull(paper);
        this.paper = paper.Copy();
    }

    public void SetOrientation(int orientation)
    {
        if (orientation is not LANDSCAPE and not PORTRAIT and not REVERSE_LANDSCAPE)
            throw new ArgumentException("Unknown page orientation.", nameof(orientation));
        this.orientation = orientation;
    }

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
}

public class JavaBook
{
    private readonly List<(JavaPrintable Printable, JavaPageFormat Format)> pages = new();

    public virtual int GetNumberOfPages() => pages.Count;

    public virtual JavaPageFormat GetPageFormat(int pageIndex) =>
        pages[pageIndex].Format;

    public virtual JavaPrintable GetPrintable(int pageIndex) =>
        pages[pageIndex].Printable;

    public void Append(JavaPrintable printable, JavaPageFormat pageFormat)
    {
        ArgumentNullException.ThrowIfNull(printable);
        ArgumentNullException.ThrowIfNull(pageFormat);
        pages.Add((printable, pageFormat));
    }
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
    private readonly IReadOnlyDictionary<JavaAttributedCharacterAttribute, object> attributes;

    internal JavaAttributedCharacterIterator(
        string text,
        IReadOnlyDictionary<JavaAttributedCharacterAttribute, object> attributes)
    {
        Text = text;
        this.attributes =
            new Dictionary<JavaAttributedCharacterAttribute, object>(attributes);
    }

    public string Text { get; }

    public object? GetAttribute(JavaAttributedCharacterAttribute attribute)
    {
        ArgumentNullException.ThrowIfNull(attribute);
        return attributes.GetValueOrDefault(attribute);
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
}

public interface JavaBufferedImageOperation
{
}

public class PdfCubeGraphics2D : IDisposable
{
    private readonly SKBitmap? bitmap;
    private readonly SKCanvas? canvas;
    private SKSamplingOptions samplingOptions = SKSamplingOptions.Default;
    private JavaColor color = new(SKColors.Black);
    private JavaColor background = new(SKColors.Transparent);
    private JavaPaint paint;
    private JavaComposite composite = JavaAlphaComposite.GetInstance(
        JavaAlphaComposite.SRC_OVER,
        1f);
    private JavaStroke stroke = new JavaBasicStroke(1f);
    private JavaFont font = new();
    private PdfCubeRenderingHints renderingHints = new(null);
    private SKMatrix transform = SKMatrix.CreateIdentity();
    private object? clip;

    protected PdfCubeGraphics2D()
    {
        paint = color;
    }

    internal PdfCubeGraphics2D(SKBitmap bitmap)
    {
        this.bitmap = bitmap ?? throw new ArgumentNullException(nameof(bitmap));
        canvas = new SKCanvas(bitmap);
        paint = color;
    }

    public virtual PdfCubeGraphics2D Create()
    {
        var copy = bitmap is null
            ? new PdfCubeGraphics2D()
            : new PdfCubeGraphics2D(bitmap);
        copy.samplingOptions = samplingOptions;
        copy.color = color;
        copy.background = background;
        copy.paint = paint;
        copy.composite = composite;
        copy.stroke = stroke;
        copy.font = font;
        copy.renderingHints = new PdfCubeRenderingHints(renderingHints);
        copy.transform = transform;
        copy.clip = clip;
        return copy;
    }

    public virtual JavaGraphicsConfiguration GetDeviceConfiguration() =>
        new(32);

    public virtual void SetRenderingHint(object key, object value)
    {
        ArgumentNullException.ThrowIfNull(key);
        ArgumentNullException.ThrowIfNull(value);
        renderingHints[key] = value;
        if (ReferenceEquals(key, PdfCubeRenderingHints.KEY_INTERPOLATION))
        {
            samplingOptions =
                ReferenceEquals(value, PdfCubeRenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    ? new SKSamplingOptions(SKCubicResampler.Mitchell)
                    : new SKSamplingOptions(SKFilterMode.Linear);
        }
        else if (ReferenceEquals(key, PdfCubeRenderingHints.KEY_RENDERING) &&
                 ReferenceEquals(value, PdfCubeRenderingHints.VALUE_RENDER_QUALITY) &&
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
        AddRenderingHints(hints);
    }

    public virtual object? GetRenderingHint(object hintKey) =>
        renderingHints.GetValueOrDefault(hintKey);

    public virtual PdfCubeRenderingHints GetRenderingHints() =>
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
        using var imagePaint = new SKPaint();
        RequireCanvas().DrawBitmap(
            image,
            new SKRect(sourceX1, sourceY1, sourceX2, sourceY2),
            new SKRect(destinationX1, destinationY1, destinationX2, destinationY2),
            samplingOptions,
            imagePaint);
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
        _ = imageTransform;
        return DrawImage(image, 0, 0, observer);
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
        using var clearPaint = new SKPaint { BlendMode = SKBlendMode.Clear };
        RequireCanvas().DrawRect(new SKRect(x, y, x + width, y + height), clearPaint);
    }

    public virtual void ClipRect(int x, int y, int width, int height)
    {
        var rectangle = new SKRect(x, y, x + width, y + height);
        clip = new SKRectI(x, y, x + width, y + height);
        RequireCanvas().ClipRect(rectangle);
    }

    public virtual void SetClip(int x, int y, int width, int height) =>
        ClipRect(x, y, width, height);

    public virtual void SetClip(object clip)
    {
        this.clip = clip ?? throw new ArgumentNullException(nameof(clip));
        Clip(clip);
    }

    public virtual object GetClip() =>
        clip ?? new SKRectI(0, 0, bitmap?.Width ?? 0, bitmap?.Height ?? 0);

    public virtual SKRectI GetClipBounds() =>
        clip is SKRectI rectangle
            ? rectangle
            : new SKRectI(0, 0, bitmap?.Width ?? 0, bitmap?.Height ?? 0);

    public virtual void Clip(object shape)
    {
        switch (shape)
        {
            case SKRect rectangle:
                RequireCanvas().ClipRect(rectangle);
                break;
            case SKRectI rectangle:
                RequireCanvas().ClipRect(new SKRect(
                    rectangle.Left,
                    rectangle.Top,
                    rectangle.Right,
                    rectangle.Bottom));
                break;
            case SKPath path:
                RequireCanvas().ClipPath(path);
                break;
            default:
                throw new NotSupportedException(
                    $"Unsupported graphics clip shape '{shape.GetType().FullName}'.");
        }
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
        using var imagePaint = new SKPaint();
        RequireCanvas().DrawBitmap(
            bitmap,
            new SKRect(x, y, x + width, y + height),
            new SKRect(
                x + destinationX,
                y + destinationY,
                x + destinationX + width,
                y + destinationY + height),
            samplingOptions,
            imagePaint);
    }

    public virtual void DrawRect(int x, int y, int width, int height)
    {
        using var drawingPaint = CreateDrawingPaint(stroked: true);
        RequireCanvas().DrawRect(new SKRect(x, y, x + width, y + height), drawingPaint);
    }

    public virtual void FillRect(int x, int y, int width, int height)
    {
        using var drawingPaint = CreateDrawingPaint(stroked: false);
        RequireCanvas().DrawRect(new SKRect(x, y, x + width, y + height), drawingPaint);
    }

    public virtual void DrawLine(int x1, int y1, int x2, int y2)
    {
        using var drawingPaint = CreateDrawingPaint(stroked: true);
        RequireCanvas().DrawLine(x1, y1, x2, y2, drawingPaint);
    }

    public virtual void DrawOval(int x, int y, int width, int height)
    {
        using var drawingPaint = CreateDrawingPaint(stroked: true);
        RequireCanvas().DrawOval(new SKRect(x, y, x + width, y + height), drawingPaint);
    }

    public virtual void FillOval(int x, int y, int width, int height)
    {
        using var drawingPaint = CreateDrawingPaint(stroked: false);
        RequireCanvas().DrawOval(new SKRect(x, y, x + width, y + height), drawingPaint);
    }

    public virtual void DrawArc(
        int x,
        int y,
        int width,
        int height,
        int startAngle,
        int arcAngle)
    {
        using var drawingPaint = CreateDrawingPaint(stroked: true);
        RequireCanvas().DrawArc(
            new SKRect(x, y, x + width, y + height),
            startAngle,
            arcAngle,
            false,
            drawingPaint);
    }

    public virtual void FillArc(
        int x,
        int y,
        int width,
        int height,
        int startAngle,
        int arcAngle)
    {
        using var drawingPaint = CreateDrawingPaint(stroked: false);
        RequireCanvas().DrawArc(
            new SKRect(x, y, x + width, y + height),
            startAngle,
            arcAngle,
            true,
            drawingPaint);
    }

    public virtual void DrawRoundRect(
        int x,
        int y,
        int width,
        int height,
        int arcWidth,
        int arcHeight)
    {
        using var drawingPaint = CreateDrawingPaint(stroked: true);
        RequireCanvas().DrawRoundRect(
            new SKRect(x, y, x + width, y + height),
            arcWidth / 2f,
            arcHeight / 2f,
            drawingPaint);
    }

    public virtual void FillRoundRect(
        int x,
        int y,
        int width,
        int height,
        int arcWidth,
        int arcHeight)
    {
        using var drawingPaint = CreateDrawingPaint(stroked: false);
        RequireCanvas().DrawRoundRect(
            new SKRect(x, y, x + width, y + height),
            arcWidth / 2f,
            arcHeight / 2f,
            drawingPaint);
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
        _ = x;
        _ = y;
    }

    public virtual void DrawString(string text, int x, int y) =>
        DrawString(text, (float)x, y);

    public virtual void DrawString(string text, float x, float y)
    {
        ArgumentNullException.ThrowIfNull(text);
        using var textPaint = CreateDrawingPaint(stroked: false);
        using var skiaFont = new SKFont { Size = font.Size };
        RequireCanvas().DrawText(text, x, y, skiaFont, textPaint);
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
        _ = onStroke;
        var bounds = shape switch
        {
            SKRect candidate => candidate,
            SKRectI candidate => new SKRect(
                candidate.Left,
                candidate.Top,
                candidate.Right,
                candidate.Bottom),
            SKPath candidate => candidate.Bounds,
            _ => throw new NotSupportedException(
                $"Unsupported graphics hit-test shape '{shape.GetType().FullName}'.")
        };
        return bounds.IntersectsWith(new SKRect(
            rectangle.Left,
            rectangle.Top,
            rectangle.Right,
            rectangle.Bottom));
    }

    public virtual void Translate(int x, int y) => Translate((double)x, y);

    public virtual void Translate(double x, double y) =>
        PdfCubeFontCompat.TranslateInPlace(ref transform, x, y);

    public virtual void Rotate(double theta) =>
        PdfCubeFontCompat.RotateInPlace(ref transform, theta);

    public virtual void Rotate(double theta, double x, double y) =>
        PdfCubeFontCompat.RotateInPlace(ref transform, theta, x, y);

    public virtual void Scale(double x, double y) =>
        PdfCubeFontCompat.ScaleInPlace(ref transform, x, y);

    public virtual void Shear(double x, double y) =>
        PdfCubeFontCompat.ShearInPlace(ref transform, x, y);

    public virtual void Transform(SKMatrix transform) =>
        PdfCubeFontCompat.ConcatenateInPlace(ref this.transform, transform);

    public virtual void SetTransform(SKMatrix transform) => this.transform = transform;
    public virtual SKMatrix GetTransform() => transform;
    public virtual void SetPaintMode()
    {
    }
    public virtual void SetXORMode(JavaColor color) =>
        throw new NotSupportedException("XOR paint mode is not supported by Skia.");
    public virtual void SetXorMode(JavaColor color) => SetXORMode(color);

    public virtual void Dispose() => canvas?.Dispose();

    private void FillImageBackground(
        int x,
        int y,
        int width,
        int height,
        JavaColor backgroundColor)
    {
        var previous = color;
        SetColor(backgroundColor);
        FillRect(x, y, width, height);
        SetColor(previous);
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
        using var drawingPaint = CreateDrawingPaint(stroked: !fill);
        RequireCanvas().DrawPath(path, drawingPaint);
    }

    private void DrawShape(object shape, bool fill)
    {
        ArgumentNullException.ThrowIfNull(shape);
        using var drawingPaint = CreateDrawingPaint(stroked: !fill);
        switch (shape)
        {
            case SKPath path:
                RequireCanvas().DrawPath(path, drawingPaint);
                break;
            case SKRect rectangle:
                RequireCanvas().DrawRect(rectangle, drawingPaint);
                break;
            case SKRectI rectangle:
                RequireCanvas().DrawRect(
                    new SKRect(
                        rectangle.Left,
                        rectangle.Top,
                        rectangle.Right,
                        rectangle.Bottom),
                    drawingPaint);
                break;
            default:
                throw new NotSupportedException(
                    $"Unsupported graphics shape '{shape.GetType().FullName}'.");
        }
    }

    private SKPaint CreateDrawingPaint(bool stroked)
    {
        var basicStroke = stroke as JavaBasicStroke ?? new JavaBasicStroke(1f);
        return new SKPaint
        {
            Color = stroke is JavaBasicStroke ? color : new JavaColor(SKColors.Transparent),
            IsStroke = stroked,
            StrokeWidth = basicStroke.Width,
            StrokeCap = basicStroke.EndCap switch
            {
                JavaBasicStroke.CAP_BUTT => SKStrokeCap.Butt,
                JavaBasicStroke.CAP_ROUND => SKStrokeCap.Round,
                _ => SKStrokeCap.Square
            },
            StrokeJoin = basicStroke.LineJoin switch
            {
                JavaBasicStroke.JOIN_ROUND => SKStrokeJoin.Round,
                JavaBasicStroke.JOIN_BEVEL => SKStrokeJoin.Bevel,
                _ => SKStrokeJoin.Miter
            },
            StrokeMiter = basicStroke.MiterLimit,
            IsAntialias = true
        };
    }

    private SKCanvas RequireCanvas() =>
        canvas ?? throw new InvalidOperationException(
            "This graphics instance delegates all drawing.");
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

    public override int GetHashCode() => HashCode.Combine(IntX, IntY);

    private static int RoundCoordinate(double value) =>
        checked((int)Math.Floor(value + 0.5d));
}

public sealed class JavaPathIterator
{
    public const int WIND_EVEN_ODD = 0;
    public const int WIND_NON_ZERO = 1;
    public const int SEG_MOVETO = 0;
    public const int SEG_LINETO = 1;
    public const int SEG_QUADTO = 2;
    public const int SEG_CUBICTO = 3;
    public const int SEG_CLOSE = 4;

    private readonly List<(int Verb, double[] Coordinates)> segments = new();
    private int index;

    internal JavaPathIterator(SKPath path, object? transform)
    {
        ArgumentNullException.ThrowIfNull(path);
        Path = new SKPath(path);
        if (transform is SKMatrix matrix)
        {
            Path.Transform(matrix);
        }

        using var iterator = Path.CreateRawIterator();
        var points = new SKPoint[4];
        while (true)
        {
            var verb = iterator.Next(points);
            switch (verb)
            {
                case SKPathVerb.Move:
                    segments.Add((SEG_MOVETO, Coordinates(points[0])));
                    break;
                case SKPathVerb.Line:
                    segments.Add((SEG_LINETO, Coordinates(points[1])));
                    break;
                case SKPathVerb.Quad:
                    segments.Add((SEG_QUADTO, Coordinates(points[1], points[2])));
                    break;
                case SKPathVerb.Cubic:
                    segments.Add((SEG_CUBICTO, Coordinates(points[1], points[2], points[3])));
                    break;
                case SKPathVerb.Close:
                    segments.Add((SEG_CLOSE, Array.Empty<double>()));
                    break;
                case SKPathVerb.Done:
                    return;
                case SKPathVerb.Conic:
                    throw new NotSupportedException(
                        "A conic Skia segment cannot be represented by java.awt.geom.PathIterator.");
                default:
                    throw new NotSupportedException($"Unsupported Skia path verb `{verb}`.");
            }
        }
    }

    internal SKPath Path { get; }

    public bool IsDone() => index >= segments.Count;

    public void Next()
    {
        if (!IsDone()) index++;
    }

    public int CurrentSegment(double[] coordinates)
    {
        ArgumentNullException.ThrowIfNull(coordinates);
        if (IsDone()) throw new InvalidOperationException("Path iterator is exhausted.");
        var segment = segments[index];
        if (coordinates.Length < segment.Coordinates.Length)
        {
            throw new IndexOutOfRangeException("Path coordinate array is too small.");
        }
        segment.Coordinates.CopyTo(coordinates, 0);
        return segment.Verb;
    }

    public int CurrentSegment(float[] coordinates)
    {
        ArgumentNullException.ThrowIfNull(coordinates);
        if (IsDone()) throw new InvalidOperationException("Path iterator is exhausted.");
        var segment = segments[index];
        if (coordinates.Length < segment.Coordinates.Length)
        {
            throw new IndexOutOfRangeException("Path coordinate array is too small.");
        }
        for (var coordinate = 0; coordinate < segment.Coordinates.Length; coordinate++)
        {
            coordinates[coordinate] = (float)segment.Coordinates[coordinate];
        }
        return segment.Verb;
    }

    public int GetWindingRule() =>
        Path.FillType is SKPathFillType.EvenOdd or SKPathFillType.InverseEvenOdd
            ? WIND_EVEN_ODD
            : WIND_NON_ZERO;

    private static double[] Coordinates(params SKPoint[] points)
    {
        var result = new double[points.Length * 2];
        for (var index = 0; index < points.Length; index++)
        {
            result[index * 2] = points[index].X;
            result[index * 2 + 1] = points[index].Y;
        }
        return result;
    }
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
            var ratio = Math.Clamp(1 - flatness / radius, -1, 1);
            var angle = 2 * Math.Acos(ratio);
            if (angle > 0)
            {
                steps = Math.Max(4, (int)Math.Ceiling(2 * Math.PI / angle));
            }
        }

        using var path = new SKPath();
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
        return new JavaPathIterator(path, transform);
    }

    internal SKPath ToPath()
    {
        var path = new SKPath();
        path.AddOval(new SKRect(
            (float)x,
            (float)y,
            (float)(x + width),
            (float)(y + height)));
        return path;
    }
}

public sealed class JavaArea
{
    private readonly SKPath path;

    public JavaArea() => path = new SKPath();

    public JavaArea(object shape) => path = PdfCubeFontCompat.CreatePath(shape);

    public bool IsEmpty => path.IsEmpty;
    public SKRect Bounds => path.Bounds;
    internal SKPath Path => path;

    public void Intersect(JavaArea other)
    {
        ArgumentNullException.ThrowIfNull(other);
        using var result = new SKPath();
        if (!path.Op(other.path, SKPathOp.Intersect, result))
            throw new InvalidOperationException("Unable to intersect paths.");
        path.Reset();
        path.AddPath(result);
    }

    public void Reset() => path.Reset();

    public JavaPathIterator GetPathIterator(object? transform) =>
        new(path, transform);
}

internal static class PdfCubeFontCompat
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
        internal ImageMetadata(int type) => Type = type;
        internal int Type { get; }
    }

    private static readonly ConditionalWeakTable<SKBitmap, ImageMetadata> ImageMetadataByBitmap = new();
    private static readonly Lazy<JavaIccProfile> StandardSrgbProfile = new(CreateSrgbProfile);

    internal static SKBitmap ReadImage(FileInfo file)
    {
        ArgumentNullException.ThrowIfNull(file);
        var bitmap = SKBitmap.Decode(file.FullName)
            ?? throw new IOException($"Unsupported or invalid image: {file.FullName}");
        RegisterImageType(bitmap, InferImageType(bitmap));
        return bitmap;
    }

    internal static SKBitmap ReadImage(Stream stream)
    {
        ArgumentNullException.ThrowIfNull(stream);
        var bitmap = SKBitmap.Decode(stream)
            ?? throw new IOException("Unsupported or invalid image stream.");
        RegisterImageType(bitmap, InferImageType(bitmap));
        return bitmap;
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

        var destination = new SKBitmap(
            width,
            height,
            source.ColorType,
            source.AlphaType,
            source.ColorSpace);
        using var canvas = new SKCanvas(destination);
        using var paint = new SKPaint();
        var sampling = hints == SCALE_SMOOTH
            ? new SKSamplingOptions(SKCubicResampler.Mitchell)
            : SKSamplingOptions.Default;
        canvas.DrawBitmap(
            source,
            new SKRect(0, 0, width, height),
            sampling,
            paint);
        return destination;
    }

    internal static void SetImageIoUseCache(bool useCache)
    {
        // Skia decodes directly from the supplied stream and does not use
        // ImageIO's optional on-disk cache.
        _ = useCache;
    }

    internal static SKBitmap CreateBitmap(int width, int height, int type)
    {
        if (width <= 0 || height <= 0)
            throw new ArgumentException("Bitmap dimensions must be positive.");

        var colorType = type is TYPE_BYTE_GRAY or TYPE_BYTE_BINARY
            ? SKColorType.Gray8
            : SKColorType.Bgra8888;
        var alphaType = type is TYPE_INT_ARGB or TYPE_4BYTE_ABGR
            ? SKAlphaType.Unpremul
            : SKAlphaType.Opaque;
        var bitmap = new SKBitmap(new SKImageInfo(width, height, colorType, alphaType));
        RegisterImageType(bitmap, type);
        return bitmap;
    }

    internal static int GetImageType(SKBitmap bitmap)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        return ImageMetadataByBitmap.TryGetValue(bitmap, out var metadata)
            ? metadata.Type
            : InferImageType(bitmap);
    }

    internal static JavaRaster GetRaster(SKBitmap bitmap) => new(bitmap);

    internal static JavaRaster GetImageData(SKBitmap bitmap)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        return GetImageType(bitmap) == TYPE_BYTE_BINARY
            ? JavaRaster.BinarySnapshot(bitmap)
            : new JavaRaster(
                bitmap.Copy() ??
                throw new InvalidOperationException("Unable to copy image raster data."));
    }

    internal static int GetTransparency(SKBitmap bitmap) =>
        bitmap.AlphaType == SKAlphaType.Opaque
            ? PdfCubeTransparency.OPAQUE
            : PdfCubeTransparency.TRANSLUCENT;

    internal static JavaColorModel GetColorModel(SKBitmap bitmap) =>
        new(GetImageType(bitmap));

    internal static JavaSampleModel GetSampleModel(SKBitmap bitmap) =>
        GetImageType(bitmap) == TYPE_BYTE_BINARY
            ? new JavaMultiPixelPackedSampleModel(1)
            : new JavaSampleModel();

    internal static JavaRaster GetAlphaRaster(SKBitmap bitmap)
    {
        ArgumentNullException.ThrowIfNull(bitmap);
        var raster = new JavaRaster(
            DATA_BUFFER_TYPE_BYTE,
            bitmap.Width,
            bitmap.Height,
            1);
        for (var y = 0; y < bitmap.Height; y++)
        {
            for (var x = 0; x < bitmap.Width; x++)
            {
                raster.SetPixel(
                    x,
                    y,
                    new[] { (int)bitmap.GetPixel(x, y).Alpha });
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
        for (var y = 0; y < bitmap.Height; y++)
        {
            for (var x = 0; x < bitmap.Width; x++)
            {
                var pixel = raster.GetPixel(x, y, (int[]?)null);
                var color = pixel.Length switch
                {
                    1 => new SKColor(
                        unchecked((byte)pixel[0]),
                        unchecked((byte)pixel[0]),
                        unchecked((byte)pixel[0])),
                    2 => new SKColor(
                        unchecked((byte)pixel[0]),
                        unchecked((byte)pixel[0]),
                        unchecked((byte)pixel[0]),
                        unchecked((byte)pixel[1])),
                    3 => new SKColor(
                        unchecked((byte)pixel[0]),
                        unchecked((byte)pixel[1]),
                        unchecked((byte)pixel[2])),
                    _ => new SKColor(
                        unchecked((byte)pixel[0]),
                        unchecked((byte)pixel[1]),
                        unchecked((byte)pixel[2]),
                        unchecked((byte)pixel[3]))
                };
                bitmap.SetPixel(x, y, color);
            }
        }
    }

    internal static SKBitmap CreateImage(
        JavaColorModel colorModel,
        JavaRaster raster,
        bool isRasterPremultiplied,
        object? _)
    {
        ArgumentNullException.ThrowIfNull(colorModel);
        ArgumentNullException.ThrowIfNull(raster);
        var bitmap = new SKBitmap(
            new SKImageInfo(
                raster.Width,
                raster.Height,
                SKColorType.Bgra8888,
                colorModel.HasAlpha
                    ? isRasterPremultiplied
                        ? SKAlphaType.Premul
                        : SKAlphaType.Unpremul
                    : SKAlphaType.Opaque));
        if (colorModel.Palette is { } palette)
        {
            for (var y = 0; y < raster.Height; y++)
            {
                for (var x = 0; x < raster.Width; x++)
                {
                    var index = raster.GetPixel(x, y, (int[]?)null)[0];
                    if ((uint)index >= (uint)palette.Length)
                    {
                        throw new ArgumentException(
                            $"Palette index {index} is outside the color map.");
                    }
                    bitmap.SetPixel(x, y, palette[index]);
                }
            }
        }
        else
        {
            SetImageData(bitmap, raster);
        }
        return bitmap;
    }

    internal static int GetRgb(SKBitmap bitmap, int x, int y) =>
        ToArgb(bitmap.GetPixel(x, y));

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
        for (var row = 0; row < height; row++)
        {
            for (var column = 0; column < width; column++)
            {
                values[offset + row * scansize + column] =
                    ToArgb(bitmap.GetPixel(x + column, y + row));
            }
        }
        return values;
    }

    internal static void SetRgb(SKBitmap bitmap, int x, int y, int argb) =>
        bitmap.SetPixel(x, y, FromArgb(argb));

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

    internal static PdfCubeGraphics2D CreateGraphics(SKBitmap bitmap) => new(bitmap);

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

    private static JavaIccProfile CreateSrgbProfile()
    {
        using var skColorSpace = SKColorSpace.CreateSrgb();
        using var profile = skColorSpace.ToProfile();
        var bytes = new byte[checked((int)profile.Size)];
        Marshal.Copy(profile.Buffer, bytes, 0, bytes.Length);
        return new JavaIccProfile(ToSignedBytes(bytes));
    }

    internal static JavaColorSpace GetColorSpace(int colorSpace) =>
        colorSpace switch
        {
            JavaColorSpace.CS_sRGB =>
                new JavaIccColorSpace(
                    JavaColorSpace.CS_sRGB,
                    GetIccProfile(JavaColorSpace.CS_sRGB)),
            JavaColorSpace.CS_GRAY =>
                new JavaColorSpace(JavaColorSpace.CS_GRAY),
            JavaColorSpace.CS_CIEXYZ =>
                new JavaColorSpace(JavaColorSpace.CS_CIEXYZ),
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
        if (transparency is not PdfCubeTransparency.OPAQUE and
            not PdfCubeTransparency.BITMASK and
            not PdfCubeTransparency.TRANSLUCENT)
            throw new ArgumentException("Invalid transparency constant.");
        return new JavaColorModel(colorSpace, hasAlpha, dataType);
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
        var bytes = new byte[data.Length];
        for (var index = 0; index < data.Length; index++) bytes[index] = unchecked((byte)data[index]);
        using var colorSpace = SKColorSpace.CreateIcc(bytes)
            ?? throw new ArgumentException("Invalid ICC profile data.");
        return new JavaIccProfile(data);
    }

    private static sbyte[] ToSignedBytes(byte[] bytes)
    {
        var result = new sbyte[bytes.Length];
        for (var index = 0; index < bytes.Length; index++) result[index] = unchecked((sbyte)bytes[index]);
        return result;
    }

    private static void RegisterImageType(SKBitmap bitmap, int type)
    {
        ImageMetadataByBitmap.Remove(bitmap);
        ImageMetadataByBitmap.Add(bitmap, new ImageMetadata(type));
    }

    private static int InferImageType(SKBitmap bitmap) =>
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
        ArgumentOutOfRangeException.ThrowIfGreaterThan(
            checked(sourceOffset + checked(pointCount * 2)),
            source.Length);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(
            checked(destinationOffset + checked(pointCount * 2)),
            destination.Length);

        if (pointCount == 0) return;
        var values = ReferenceEquals(source, destination)
            ? source.AsSpan(sourceOffset, pointCount * 2).ToArray()
            : source;
        var readOffset = ReferenceEquals(source, destination) ? 0 : sourceOffset;
        for (var point = 0; point < pointCount; point++)
        {
            var x = values[readOffset + point * 2];
            var y = values[readOffset + point * 2 + 1];
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

    internal static JavaPoint2D? CurrentPoint(SKPath path) =>
        path.IsEmpty ? null : new JavaPoint2D(path.LastPoint.X, path.LastPoint.Y);

    internal static JavaPathIterator PathIterator(SKPath path, object? transform) =>
        new(path, transform);

    internal static JavaPathIterator ShapePathIterator(
        object shape,
        object? transform)
    {
        using var path = CreatePath(shape);
        return new JavaPathIterator(path, transform);
    }

    internal static void Close(SKPath path) => path.Close();

    internal static void SetWindingRule(SKPath path, int windingRule) =>
        path.FillType = windingRule == JavaPathIterator.WIND_EVEN_ODD
            ? SKPathFillType.EvenOdd
            : SKPathFillType.Winding;

    internal static void TransformPath(SKPath path, SKMatrix transform)
    {
        ArgumentNullException.ThrowIfNull(path);
        path.Transform(transform);
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
        using var path = CreatePath(shape);
        return path.Bounds;
    }

    internal static SKPath CreatePath(int windingRule, int initialCapacity)
    {
        if (initialCapacity < 0) throw new ArgumentException("Initial capacity cannot be negative.");
        var path = new SKPath();
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

    internal static void AddPath(SKPath path, JavaPathIterator addition) =>
        path.AddPath(addition.Path);

    internal static void AppendPath(SKPath path, object shape, bool connect)
    {
        using var addition = CreatePath(shape);
        path.AddPath(
            addition,
            connect ? SKPathAddMode.Extend : SKPathAddMode.Append);
    }

    internal static void AppendPath(
        SKPath path,
        JavaPathIterator addition,
        bool connect) =>
        path.AddPath(
            addition.Path,
            connect ? SKPathAddMode.Extend : SKPathAddMode.Append);

    internal static void MoveTo(SKPath path, double x, double y) =>
        path.MoveTo((float)x, (float)y);

    internal static void LineTo(SKPath path, double x, double y) =>
        path.LineTo((float)x, (float)y);

    internal static void QuadTo(SKPath path, double x1, double y1, double x2, double y2) =>
        path.QuadTo((float)x1, (float)y1, (float)x2, (float)y2);

    internal static void CurveTo(
        SKPath path,
        double x1,
        double y1,
        double x2,
        double y2,
        double x3,
        double y3) =>
        path.CubicTo((float)x1, (float)y1, (float)x2, (float)y2, (float)x3, (float)y3);
}

#pragma warning restore CS0618
