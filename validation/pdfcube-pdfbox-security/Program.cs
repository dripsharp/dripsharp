using System;
using System.Collections.Generic;
using System.Formats.Asn1;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.Pkcs;
using System.Security.Cryptography.X509Certificates;
using PdfCube.PdfBox;
using PdfCube.PdfBox.Cos;
using PdfCube.PdfBox.Pdmodel;
using PdfCube.PdfBox.Pdmodel.Encryption;
using PdfCube.PdfBox.Pdmodel.Interactive.Digitalsignature;

internal static class Program
{
    private const string FixtureOwnerPassword = "owner";
    private const string FixtureUserPassword = "user";
    private const string GeneratedOwnerPassword =
        "owner-0123456789-abcdefghijklmnopqrstuvwxyz";
    private const string GeneratedUserPassword =
        "user-0123456789-abcdefghijklmnopqrstuvwxyz";

    private static int Main(string[] args)
    {
        if (args.Length is < 3 or > 4)
        {
            Console.Error.WriteLine(
                "usage: <trace.tsv> <exchange-directory> <fixture-root> [--write-only]");
            return 2;
        }

        var trace = Path.GetFullPath(args[0]);
        var exchange = Path.GetFullPath(args[1]);
        var fixtures = Path.GetFullPath(args[2]);
        var writeOnly = args.Length == 4 &&
            string.Equals(args[3], "--write-only", StringComparison.Ordinal);
        Directory.CreateDirectory(Path.GetDirectoryName(trace)!);
        Directory.CreateDirectory(exchange);

        var rows = new SortedDictionary<string, string>(StringComparer.Ordinal);
        RunStandardFixtures(rows, fixtures);
        RunStandardRoundTrips(rows, exchange);
        RunPublicKeyFixtures(rows, fixtures);
        RunPublicKeyRoundTrip(rows, exchange, fixtures);
        RunCmsFailureRows(rows, exchange, fixtures, "dotnet");
        RunSignatureModels(rows);
        RunExternalSigning(rows, exchange, fixtures);
        if (!writeOnly)
            RunJavaExchange(rows, exchange, fixtures);

        File.WriteAllLines(
            trace,
            rows.Select(entry => entry.Key + "\t" + entry.Value));
        return 0;
    }

    private static void RunStandardFixtures(
        IDictionary<string, string> rows,
        string fixtures)
    {
        foreach (var bits in new[] { 40, 128, 256 })
        {
            var pdf = Path.Combine(
                fixtures,
                "pdfbox",
                "src",
                "test",
                "resources",
                "org",
                "apache",
                "pdfbox",
                "encryption",
                "PasswordSample-" + bits.ToString(CultureInfo.InvariantCulture) +
                "bit.pdf");
            using var owner = Load(pdf, FixtureOwnerPassword);
            using var user = Load(pdf, FixtureUserPassword);
            Add(
                rows,
                "standard-fixture",
                bits + "-owner",
                Permission(owner.GetCurrentAccessPermission()));
            Add(
                rows,
                "standard-fixture",
                bits + "-user",
                Permission(user.GetCurrentAccessPermission()));
            Add(
                rows,
                "standard-revision",
                bits.ToString(CultureInfo.InvariantCulture),
                Encryption(owner));
            Add(
                rows,
                "standard-wrong-credentials",
                bits.ToString(CultureInfo.InvariantCulture),
                Failure(() =>
                {
                    using var ignored = Load(pdf, "definitely-wrong");
                }));
        }
    }

    private static void RunStandardRoundTrips(
        IDictionary<string, string> rows,
        string exchange)
    {
        foreach (var specification in new[]
                 {
                     (Bits: 40, Aes: false),
                     (Bits: 128, Aes: false),
                     (Bits: 128, Aes: true),
                     (Bits: 256, Aes: true)
                 })
        {
            var id = specification.Bits.ToString(CultureInfo.InvariantCulture) +
                (specification.Aes ? "-aes" : "-rc4");
            var path = Path.Combine(exchange, "dotnet-standard-" + id + ".pdf");
            var permission = RestrictedPermission(canPrint: specification.Aes);
            using (var document = new PDDocument())
            {
                document.AddPage(new PDPage());
                document.GetDocumentInformation().SetTitle("security-roundtrip");
                var policy = new StandardProtectionPolicy(
                    GeneratedOwnerPassword,
                    GeneratedUserPassword,
                    permission);
                policy.SetEncryptionKeyLength(specification.Bits);
                policy.SetPreferAES(specification.Aes);
                document.Protect(policy);
                document.Save(path);
            }

            AddStandardRoundTripRows(rows, path, "dotnet-" + id);
        }
    }

    private static void RunPublicKeyFixtures(
        IDictionary<string, string> rows,
        string fixtures)
    {
        var root = EncryptionFixtureRoot(fixtures);
        foreach (var specification in new[]
                 {
                     (Pdf: "AESkeylength128.pdf", Store: "PDFBOX-4421-keystore.pfx",
                         Password: "w!z%C*F-JaNdRgUk", Alias: "testnutzer", Bits: 128),
                     (Pdf: "AESkeylength256.pdf", Store: "PDFBOX-4421-keystore.pfx",
                         Password: "w!z%C*F-JaNdRgUk", Alias: "testnutzer", Bits: 256),
                     (Pdf: "AES128ExposedMeta.pdf", Store: "PDFBOX-5249.p12",
                         Password: "", Alias: "test", Bits: 128),
                     (Pdf: "AES256ExposedMeta.pdf", Store: "PDFBOX-5249.p12",
                         Password: "", Alias: "test", Bits: 256)
                 })
        {
            using var store = File.OpenRead(Path.Combine(root, specification.Store));
            using var document = Loader.LoadPDF(
                new FileInfo(Path.Combine(root, specification.Pdf)),
                specification.Password,
                store,
                specification.Alias);
            Add(rows, "public-key-fixture", specification.Pdf,
                Encryption(document) + ";pages=" +
                document.GetNumberOfPages().ToString(CultureInfo.InvariantCulture));
        }
    }

    private static void RunPublicKeyRoundTrip(
        IDictionary<string, string> rows,
        string exchange,
        string fixtures)
    {
        var root = EncryptionFixtureRoot(fixtures);
        var certificate = X509CertificateLoader.LoadCertificateFromFile(
            Path.Combine(root, "test1.der"));
        var otherStore = Path.Combine(root, "test2.pfx");
        foreach (var bits in new[] { 40, 128, 256 })
        {
            var path = Path.Combine(
                exchange,
                "dotnet-public-" + bits.ToString(CultureInfo.InvariantCulture) +
                ".pdf");
            using (var document = new PDDocument())
            {
                document.AddPage(new PDPage());
                document.GetDocumentInformation().SetTitle("public-roundtrip");
                var recipient = new PublicKeyRecipient();
                recipient.SetX509(certificate);
                recipient.SetPermission(RestrictedPermission(canPrint: bits != 40));
                var policy = new PublicKeyProtectionPolicy();
                policy.AddRecipient(recipient);
                policy.SetEncryptionKeyLength(bits);
                document.Protect(policy);
                document.Save(path);
            }

            AddPublicKeyRoundTripRows(
                rows,
                path,
                "dotnet-" + bits.ToString(CultureInfo.InvariantCulture),
                root,
                otherStore);
        }
    }

    private static void RunSignatureModels(IDictionary<string, string> rows)
    {
        var signature = new PDSignature();
        signature.SetFilter(PDSignature.FilterAdobePpklite);
        signature.SetSubFilter(PDSignature.SubfilterAdbePkcs7Detached);
        signature.SetName("Signer");
        signature.SetReason("Reason");
        signature.SetLocation("Location");
        signature.SetContactInfo("Contact");
        signature.SetSignDate(
            new DateTimeOffset(2024, 2, 3, 4, 5, 6, TimeSpan.Zero));
        signature.SetByteRange(new[] { 0, 10, 20, 30 });
        signature.SetContents(new sbyte[] { 1, 2, 3, 4 });
        Add(rows, "signature-dictionary", "roundtrip",
            string.Join("|",
                signature.GetFilter(),
                signature.GetSubFilter(),
                signature.GetName(),
                signature.GetReason(),
                signature.GetLocation(),
                signature.GetContactInfo(),
                signature.GetSignDate().ToUnixTimeMilliseconds(),
                string.Join(",", signature.GetByteRange()),
                signature.GetContents().Length));

        signature.SetByteRange(new[] { 1, 2, 3 });
        Add(rows, "byte-range", "invalid-length",
            string.Join(",", signature.GetByteRange()));

        var timestamp = new PDSeedValueTimeStamp();
        timestamp.SetURL("https://tsa.invalid");
        timestamp.SetTimestampRequired(true);
        var seed = new PDSeedValue();
        seed.SetTimeStamp(timestamp);
        seed.SetDigestMethod(new List<string> { "SHA256", "SHA512" });
        seed.SetDigestMethodRequired(true);
        Add(rows, "timestamp-model", "roundtrip",
            seed.GetTimeStamp().GetURL() + "|" +
            B(seed.GetTimeStamp().IsTimestampRequired()) + "|" +
            B(seed.IsDigestMethodRequired()) + "|" +
            string.Join(",", seed.GetDigestMethod()));
        Add(rows, "seed-value", "unsupported-digest",
            Failure(() =>
                seed.SetDigestMethod(new List<string> { "UNSUPPORTED" })));
    }

    private static void RunExternalSigning(
        IDictionary<string, string> rows,
        string exchange,
        string fixtures)
    {
        var exampleRoot = Path.Combine(
            fixtures,
            "examples",
            "src",
            "test",
            "resources",
            "org",
            "apache",
            "pdfbox",
            "examples",
            "signature");
        var input = Path.Combine(exampleRoot, "sign_me.pdf");
        var output = Path.Combine(exchange, "dotnet-signed.pdf");
        var certificate = LoadSigningCertificate(
            Path.Combine(exampleRoot, "keystore.p12"),
            "123456");

        using (var document = Loader.LoadPDF(new FileInfo(input)))
        using (var destination = new FileStream(
                   output, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            var signature = new PDSignature();
            signature.SetFilter(PDSignature.FilterAdobePpklite);
            signature.SetSubFilter(PDSignature.SubfilterAdbePkcs7Detached);
            signature.SetName("PdfCube differential signer");
            signature.SetReason("Security differential");
            signature.SetSignDate(
                new DateTimeOffset(2024, 2, 3, 4, 5, 6, TimeSpan.Zero));
            document.AddSignature(signature);
            var signing = document.SaveIncrementalForExternalSigning(destination);
            using var content = signing.GetContent();
            var signedContent = ReadAll(content);
            var cms = new SignedCms(
                new ContentInfo(signedContent),
                detached: true);
            var signer = new CmsSigner(
                SubjectIdentifierType.IssuerAndSerialNumber,
                certificate)
            {
                DigestAlgorithm = new Oid("2.16.840.1.101.3.4.2.1")
            };
            cms.ComputeSignature(signer, silent: true);
            signing.SetSignature(Signed(cms.Encode()));
        }

        ValidateSignedPdf(rows, output, "dotnet");
    }

    private static void ValidateSignedPdf(
        IDictionary<string, string> rows,
        string path,
        string id)
    {
        var bytes = File.ReadAllBytes(path);
        using var signedDocument = Loader.LoadPDF(Signed(bytes));
        var signatures = signedDocument.GetSignatureDictionaries();
        var acroForm = signedDocument.GetDocumentCatalog().GetAcroForm(null);
        var fields = acroForm?.GetFields() ??
            Array.Empty<PdfCube.PdfBox.Pdmodel.Interactive.Form.PDField>();
        var rawFields = acroForm?.GetCOSObject().GetCOSArray(COSName.Fields);
        Add(rows, "external-signing", id + "-signature-discovery",
            "helpers=" + signatures.Count.ToString(CultureInfo.InvariantCulture) +
            ";fields=" + fields.Count.ToString(CultureInfo.InvariantCulture) +
            ";raw=" + (rawFields?.Size() ?? 0).ToString(CultureInfo.InvariantCulture));
        PDSignature current;
        if (signatures.Count > 0)
        {
            current = signatures[signatures.Count - 1];
        }
        else
        {
            var field = rawFields?.GetObject(0) as COSDictionary;
            var value = field?.GetCOSDictionary(COSName.V);
            current = value is null
                ? throw new InvalidOperationException(
                    "The incremental signature dictionary is missing: helpers=" +
                    signatures.Count.ToString(CultureInfo.InvariantCulture) +
                    ", fields=" +
                    fields.Count.ToString(CultureInfo.InvariantCulture) +
                    ", raw=" +
                    (rawFields?.Size() ?? 0).ToString(CultureInfo.InvariantCulture) +
                    ", first=" +
                    (rawFields?.GetObject(0)?.GetType().Name ?? "null") +
                    ", value=" +
                    (field?.GetDictionaryObject(COSName.V)?.GetType().Name ??
                     "null"))
                : new PDSignature(value);
        }
        var signedBytes = Unsigned(current.GetSignedContent(Signed(bytes)));
        var cmsBytes = DerObject(Unsigned(current.GetContents(Signed(bytes))));
        var validation = new SignedCms(
            new ContentInfo(signedBytes),
            detached: true);
        validation.Decode(cmsBytes);
        validation.CheckSignature(verifySignatureOnly: true);
        Add(rows, "external-signing", id + "-byte-range",
            B(ValidByteRange(current.GetByteRange(), bytes.Length)) +
            ";signatures=" +
            signatures.Count.ToString(CultureInfo.InvariantCulture));
        Add(rows, "signature-validation", id,
            validation.SignerInfos.Count == 1 ? "valid" : "unexpected-signers");

        var corrupt = (byte[])cmsBytes.Clone();
        corrupt[^1] ^= 1;
        Add(rows, "corrupt-signature", id,
            Failure(() =>
            {
                var invalid = new SignedCms(
                    new ContentInfo(signedBytes),
                    detached: true);
                invalid.Decode(corrupt);
                invalid.CheckSignature(verifySignatureOnly: true);
            }));
    }

    private static void RunJavaExchange(
        IDictionary<string, string> rows,
        string exchange,
        string fixtures)
    {
        foreach (var specification in new[]
                 {
                     (Bits: 40, Aes: false),
                     (Bits: 128, Aes: false),
                     (Bits: 128, Aes: true),
                     (Bits: 256, Aes: true)
                 })
        {
            var id = specification.Bits.ToString(CultureInfo.InvariantCulture) +
                (specification.Aes ? "-aes" : "-rc4");
            AddStandardRoundTripRows(
                rows,
                Path.Combine(exchange, "java-standard-" + id + ".pdf"),
                "java-" + id);
        }

        var root = EncryptionFixtureRoot(fixtures);
        foreach (var bits in new[] { 40, 128, 256 })
        {
            AddPublicKeyRoundTripRows(
                rows,
                Path.Combine(
                    exchange,
                    "java-public-" +
                    bits.ToString(CultureInfo.InvariantCulture) +
                    ".pdf"),
                "java-" + bits.ToString(CultureInfo.InvariantCulture),
                root,
                Path.Combine(root, "test2.pfx"));
        }

        RunCmsFailureRows(rows, exchange, fixtures, "java");
        ValidateSignedPdf(
            rows,
            Path.Combine(exchange, "java-signed.pdf"),
            "java");
    }

    private static void AddStandardRoundTripRows(
        IDictionary<string, string> rows,
        string path,
        string id)
    {
        using var owner = Load(path, GeneratedOwnerPassword);
        using var user = Load(path, GeneratedUserPassword);
        Add(rows, "standard-roundtrip", id + "-owner",
            Permission(owner.GetCurrentAccessPermission()));
        Add(rows, "standard-roundtrip", id + "-user",
            Permission(user.GetCurrentAccessPermission()));
        Add(rows, "standard-roundtrip", id + "-dictionary", Encryption(user));
        Add(rows, "standard-roundtrip", id + "-content",
            user.GetDocumentInformation().GetTitle() == "security-roundtrip"
                ? "preserved"
                : "changed");
        Add(rows, "standard-wrong-credentials", "generated-" + id,
            Failure(() =>
            {
                using var ignored = Load(path, "definitely-wrong");
            }));
    }

    private static void AddPublicKeyRoundTripRows(
        IDictionary<string, string> rows,
        string path,
        string id,
        string fixtureRoot,
        string otherStore)
    {
        using (var store = File.OpenRead(Path.Combine(fixtureRoot, "test1.pfx")))
        using (var document = Loader.LoadPDF(
                   new FileInfo(path), "test1", store, null))
        {
            Add(rows, "public-key-roundtrip", id,
                Encryption(document) + ";" +
                Permission(document.GetCurrentAccessPermission()) +
                ";content=" +
                B(document.GetDocumentInformation().GetTitle() ==
                  "public-roundtrip"));
        }

        Add(rows, "certificate-selection-failure", id,
            Failure(() =>
            {
                using var store = File.OpenRead(otherStore);
                using var ignored = Loader.LoadPDF(
                    new FileInfo(path), "test2", store, null);
            }));
    }

    private static void RunCmsFailureRows(
        IDictionary<string, string> rows,
        string exchange,
        string fixtures,
        string producer)
    {
        var source = Path.Combine(exchange, producer + "-public-40.pdf");
        var corrupt = Path.Combine(exchange, producer + "-public-corrupt.pdf");
        var unsupported =
            Path.Combine(exchange, producer + "-public-unsupported.pdf");
        MutateRecipient(source, corrupt, RecipientMutation.CorruptDer);
        MutateRecipient(source, unsupported, RecipientMutation.UnsupportedAlgorithm);
        var storePath = Path.Combine(
            EncryptionFixtureRoot(fixtures),
            "test1.pfx");
        Add(rows, "corrupt-cms", producer,
            Failure(() =>
            {
                using var store = File.OpenRead(storePath);
                using var ignored = Loader.LoadPDF(
                    new FileInfo(corrupt), "test1", store, null);
            }));
        Add(rows, "unsupported-algorithm", producer,
            Failure(() =>
            {
                using var store = File.OpenRead(storePath);
                using var ignored = Loader.LoadPDF(
                    new FileInfo(unsupported), "test1", store, null);
            }));
    }

    private enum RecipientMutation
    {
        CorruptDer,
        UnsupportedAlgorithm
    }

    private static void MutateRecipient(
        string source,
        string destination,
        RecipientMutation mutation)
    {
        var bytes = File.ReadAllBytes(source);
        var start = IndexOf(bytes, "/Recipients [<"u8.ToArray(), 0);
        if (start < 0)
            throw new InvalidOperationException(
                "Public-key recipient array is missing.");
        start += "/Recipients [<"u8.Length;
        if (mutation == RecipientMutation.CorruptDer)
        {
            if (bytes[start] != (byte)'3' || bytes[start + 1] != (byte)'0')
                throw new InvalidOperationException(
                    "CMS recipient is not a DER sequence.");
            bytes[start + 1] = (byte)'1';
        }
        else
        {
            var rc2Oid = "06082A864886F70D0302"u8.ToArray();
            var oid = IndexOf(bytes, rc2Oid, start);
            if (oid < 0)
                throw new InvalidOperationException(
                    "CMS RC2 algorithm identifier is missing.");
            bytes[oid + rc2Oid.Length - 1] = (byte)'3';
        }
        File.WriteAllBytes(destination, bytes);
    }

    private static int IndexOf(byte[] bytes, byte[] pattern, int start)
    {
        for (var index = start; index <= bytes.Length - pattern.Length; index++)
        {
            if (bytes.AsSpan(index, pattern.Length).SequenceEqual(pattern))
                return index;
        }
        return -1;
    }

    private static PDDocument Load(string path, string password) =>
        Loader.LoadPDF(new FileInfo(path), password);

    private static AccessPermission RestrictedPermission(bool canPrint)
    {
        var permission = new AccessPermission();
        permission.SetCanAssembleDocument(false);
        permission.SetCanExtractContent(false);
        permission.SetCanExtractForAccessibility(true);
        permission.SetCanFillInForm(false);
        permission.SetCanModify(false);
        permission.SetCanModifyAnnotations(false);
        permission.SetCanPrint(canPrint);
        permission.SetCanPrintFaithful(false);
        permission.SetReadOnly();
        return permission;
    }

    private static string Permission(AccessPermission permission) =>
        string.Join(",",
            B(permission.IsOwnerPermission()),
            B(permission.IsReadOnly()),
            B(permission.CanAssembleDocument()),
            B(permission.CanExtractContent()),
            B(permission.CanExtractForAccessibility()),
            B(permission.CanFillInForm()),
            B(permission.CanModify()),
            B(permission.CanModifyAnnotations()),
            B(permission.CanPrint()),
            B(permission.CanPrintFaithful()));

    private static string Encryption(PDDocument document)
    {
        var encryption = document.GetEncryption();
        var handler = encryption.GetSecurityHandler();
        return string.Join(",",
            encryption.GetFilter(),
            encryption.GetSubFilter() ?? "-",
            encryption.GetVersion(),
            encryption.GetRevision(),
            encryption.GetLength(),
            handler.GetKeyLength(),
            B(handler.IsAES()),
            B(handler.IsDecryptMetadata()));
    }

    private static string B(bool value) => value ? "true" : "false";

    private static string Failure(Action action)
    {
        try
        {
            action();
            return "none";
        }
        catch (InvalidPasswordException)
        {
            return "invalid-password";
        }
        catch (ArgumentException)
        {
            return "invalid-argument";
        }
        catch (CryptographicException)
        {
            return "cryptographic";
        }
        catch (IOException)
        {
            return "io";
        }
        catch (InvalidOperationException)
        {
            return "invalid-state";
        }
    }

    private static bool ValidByteRange(int[] range, int length) =>
        range.Length == 4 &&
        range[0] == 0 &&
        range[1] > 0 &&
        range[2] > range[1] &&
        range[3] >= 0 &&
        (long)range[2] + range[3] == length;

    private static byte[] ReadAll(Stream input)
    {
        using var output = new MemoryStream();
        input.CopyTo(output);
        return output.ToArray();
    }

    private static X509Certificate2 LoadSigningCertificate(
        string path,
        string password)
    {
        try
        {
            return X509CertificateLoader.LoadPkcs12FromFile(
                path,
                password,
                X509KeyStorageFlags.EphemeralKeySet |
                X509KeyStorageFlags.Exportable);
        }
        catch (PlatformNotSupportedException)
        {
            return X509CertificateLoader.LoadPkcs12FromFile(
                path,
                password,
                X509KeyStorageFlags.Exportable);
        }
    }

    private static byte[] DerObject(byte[] padded)
    {
        try
        {
            AsnDecoder.ReadEncodedValue(
                padded,
                AsnEncodingRules.BER,
                out _,
                out _,
                out var bytesConsumed);
            return padded.AsSpan(0, bytesConsumed).ToArray();
        }
        catch (AsnContentException error)
        {
            throw new CryptographicException(
                "CMS value is not valid ASN.1.",
                error);
        }
    }

    private static sbyte[] Signed(byte[] value) =>
        MemoryMarshal.Cast<byte, sbyte>(value.AsSpan()).ToArray();

    private static byte[] Unsigned(sbyte[] value) =>
        MemoryMarshal.Cast<sbyte, byte>(value.AsSpan()).ToArray();

    private static string EncryptionFixtureRoot(string fixtures) =>
        Path.Combine(
            fixtures,
            "pdfbox",
            "src",
            "test",
            "resources",
            "org",
            "apache",
            "pdfbox",
            "encryption");

    private static void Add(
        IDictionary<string, string> rows,
        string family,
        string id,
        object value)
    {
        var key = family + "\t" + id;
        if (!rows.TryAdd(key, Convert.ToString(
                value, CultureInfo.InvariantCulture) ?? string.Empty))
            throw new InvalidOperationException("Duplicate trace key: " + key);
    }
}
