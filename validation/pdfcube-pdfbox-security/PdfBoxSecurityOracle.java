import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Enumeration;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.encryption.PublicKeyProtectionPolicy;
import org.apache.pdfbox.pdmodel.encryption.PublicKeyRecipient;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSeedValue;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSeedValueTimeStamp;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.SignerInformationStore;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.util.Store;

public final class PdfBoxSecurityOracle {
  private static final String FIXTURE_OWNER_PASSWORD = "owner";
  private static final String FIXTURE_USER_PASSWORD = "user";
  private static final String GENERATED_OWNER_PASSWORD =
      "owner-0123456789-abcdefghijklmnopqrstuvwxyz";
  private static final String GENERATED_USER_PASSWORD =
      "user-0123456789-abcdefghijklmnopqrstuvwxyz";
  private static final Map<String, String> ROWS = new TreeMap<>();

  private static Path exchange;
  private static Path fixtures;

  private PdfBoxSecurityOracle() {}

  public static void main(String[] args) {
    try {
      if (args.length != 3) {
        throw new IllegalArgumentException(
            "Expected output trace, exchange directory, and fixture directory.");
      }
      Path output = Paths.get(args[0]).toAbsolutePath();
      exchange = Paths.get(args[1]).toAbsolutePath();
      fixtures = Paths.get(args[2]).toAbsolutePath();
      Files.createDirectories(output.getParent());
      Files.createDirectories(exchange);

      runStandardFixtures();
      runStandardRoundTrips();
      runPublicKeyFixtures();
      runPublicKeyRoundTrips();
      runCmsFailureRows("java");
      runSignatureModels();
      runExternalSigning();

      for (int bits : new int[] {40, 128, 256}) {
        addPublicKeyRoundTripRows(
            exchange.resolve("dotnet-public-" + bits + ".pdf"),
            "dotnet-" + bits);
      }
      for (Specification specification : specifications()) {
        addStandardRoundTripRows(
            exchange.resolve("dotnet-standard-" + specification.id() + ".pdf"),
            "dotnet-" + specification.id());
      }
      runCmsFailureRows("dotnet");
      validateSignedPdf(exchange.resolve("dotnet-signed.pdf"), "dotnet");

      List<String> lines = new ArrayList<>();
      for (Map.Entry<String, String> row : ROWS.entrySet()) {
        lines.add(row.getKey() + "\t" + row.getValue());
      }
      Files.write(output, lines, StandardCharsets.UTF_8);
    } catch (Throwable error) {
      error.printStackTrace(System.err);
      System.exit(1);
    }
  }

  private static void runStandardFixtures() throws Exception {
    for (int bits : new int[] {40, 128, 256}) {
      Path pdf = fixtures
          .resolve("pdfbox/src/test/resources/org/apache/pdfbox/encryption")
          .resolve("PasswordSample-" + bits + "bit.pdf");
      try (PDDocument owner = Loader.loadPDF(pdf.toFile(), FIXTURE_OWNER_PASSWORD);
          PDDocument user = Loader.loadPDF(pdf.toFile(), FIXTURE_USER_PASSWORD)) {
        add("standard-fixture", bits + "-owner",
            permission(owner.getCurrentAccessPermission()));
        add("standard-fixture", bits + "-user",
            permission(user.getCurrentAccessPermission()));
        add("standard-revision", Integer.toString(bits), encryption(owner));
      }
      add("standard-wrong-credentials", Integer.toString(bits),
          failure(() -> {
            try (PDDocument ignored = Loader.loadPDF(
                pdf.toFile(), "definitely-wrong")) {
              // Reaching this point is the failure observation.
            }
          }));
    }
  }

  private static void runStandardRoundTrips() throws Exception {
    for (Specification specification : specifications()) {
      Path path =
          exchange.resolve("java-standard-" + specification.id() + ".pdf");
      AccessPermission permission =
          restrictedPermission(specification.aes);
      try (PDDocument document = new PDDocument()) {
        document.addPage(new PDPage());
        document.getDocumentInformation().setTitle("security-roundtrip");
        StandardProtectionPolicy policy = new StandardProtectionPolicy(
            GENERATED_OWNER_PASSWORD,
            GENERATED_USER_PASSWORD,
            permission);
        policy.setEncryptionKeyLength(specification.bits);
        policy.setPreferAES(specification.aes);
        document.protect(policy);
        document.save(path.toFile());
      }
      addStandardRoundTripRows(path, "java-" + specification.id());
    }
  }

  private static void addStandardRoundTripRows(Path path, String id)
      throws Exception {
    try (PDDocument owner = Loader.loadPDF(
            path.toFile(), GENERATED_OWNER_PASSWORD);
        PDDocument user = Loader.loadPDF(
            path.toFile(), GENERATED_USER_PASSWORD)) {
      add("standard-roundtrip", id + "-owner",
          permission(owner.getCurrentAccessPermission()));
      add("standard-roundtrip", id + "-user",
          permission(user.getCurrentAccessPermission()));
      add("standard-roundtrip", id + "-dictionary", encryption(user));
      add("standard-roundtrip", id + "-content",
          "security-roundtrip".equals(
              user.getDocumentInformation().getTitle())
              ? "preserved"
              : "changed");
    }
    add("standard-wrong-credentials", "generated-" + id,
        failure(() -> {
          try (PDDocument ignored = Loader.loadPDF(
              path.toFile(), "definitely-wrong")) {
            // Reaching this point is the failure observation.
          }
        }));
  }

  private static void runPublicKeyFixtures() throws Exception {
    Object[][] specifications = {
        {"AESkeylength128.pdf", "PDFBOX-4421-keystore.pfx",
            "w!z%C*F-JaNdRgUk", "testnutzer"},
        {"AESkeylength256.pdf", "PDFBOX-4421-keystore.pfx",
            "w!z%C*F-JaNdRgUk", "testnutzer"},
        {"AES128ExposedMeta.pdf", "PDFBOX-5249.p12", "", "test"},
        {"AES256ExposedMeta.pdf", "PDFBOX-5249.p12", "", "test"}
    };
    Path root = encryptionFixtureRoot();
    for (Object[] specification : specifications) {
      String pdf = (String) specification[0];
      try (InputStream store = Files.newInputStream(
              root.resolve((String) specification[1]));
          PDDocument document = Loader.loadPDF(
              root.resolve(pdf).toFile(),
              (String) specification[2],
              store,
              (String) specification[3])) {
        add("public-key-fixture", pdf,
            encryption(document) + ";pages=" +
                document.getNumberOfPages());
      }
    }
  }

  private static void runPublicKeyRoundTrips() throws Exception {
    X509Certificate certificate;
    try (InputStream input =
        Files.newInputStream(encryptionFixtureRoot().resolve("test1.der"))) {
      certificate = (X509Certificate)
          CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
    for (int bits : new int[] {40, 128, 256}) {
      Path path = exchange.resolve("java-public-" + bits + ".pdf");
      try (PDDocument document = new PDDocument()) {
        document.addPage(new PDPage());
        document.getDocumentInformation().setTitle("public-roundtrip");
        PublicKeyRecipient recipient = new PublicKeyRecipient();
        recipient.setX509(certificate);
        recipient.setPermission(restrictedPermission(bits != 40));
        PublicKeyProtectionPolicy policy = new PublicKeyProtectionPolicy();
        policy.addRecipient(recipient);
        policy.setEncryptionKeyLength(bits);
        document.protect(policy);
        document.save(path.toFile());
      }
      addPublicKeyRoundTripRows(path, "java-" + bits);
    }
  }

  private static void addPublicKeyRoundTripRows(Path path, String id)
      throws Exception {
    Path root = encryptionFixtureRoot();
    try (InputStream store = Files.newInputStream(root.resolve("test1.pfx"));
        PDDocument document =
            Loader.loadPDF(path.toFile(), "test1", store, null)) {
      add("public-key-roundtrip", id,
          encryption(document) + ";" +
              permission(document.getCurrentAccessPermission()) +
              ";content=" +
              bool("public-roundtrip".equals(
                  document.getDocumentInformation().getTitle())));
    }
    add("certificate-selection-failure", id,
        failure(() -> {
          try (InputStream store =
                  Files.newInputStream(root.resolve("test2.pfx"));
              PDDocument ignored =
                  Loader.loadPDF(path.toFile(), "test2", store, null)) {
            // Reaching this point is the failure observation.
          }
        }));
  }

  private static void runSignatureModels() {
    PDSignature signature = new PDSignature();
    signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
    signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
    signature.setName("Signer");
    signature.setReason("Reason");
    signature.setLocation("Location");
    signature.setContactInfo("Contact");
    Calendar date = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
    date.clear();
    date.set(2024, Calendar.FEBRUARY, 3, 4, 5, 6);
    signature.setSignDate(date);
    signature.setByteRange(new int[] {0, 10, 20, 30});
    signature.setContents(new byte[] {1, 2, 3, 4});
    add("signature-dictionary", "roundtrip",
        String.join("|",
            signature.getFilter(),
            signature.getSubFilter(),
            signature.getName(),
            signature.getReason(),
            signature.getLocation(),
            signature.getContactInfo(),
            Long.toString(signature.getSignDate().getTimeInMillis()),
            join(signature.getByteRange()),
            Integer.toString(signature.getContents().length)));

    signature.setByteRange(new int[] {1, 2, 3});
    add("byte-range", "invalid-length", join(signature.getByteRange()));

    PDSeedValueTimeStamp timestamp = new PDSeedValueTimeStamp();
    timestamp.setURL("https://tsa.invalid");
    timestamp.setTimestampRequired(true);
    PDSeedValue seed = new PDSeedValue();
    seed.setTimeStamp(timestamp);
    seed.setDigestMethod(Arrays.asList("SHA256", "SHA512"));
    seed.setDigestMethodRequired(true);
    add("timestamp-model", "roundtrip",
        seed.getTimeStamp().getURL() + "|" +
            bool(seed.getTimeStamp().isTimestampRequired()) + "|" +
            bool(seed.isDigestMethodRequired()) + "|" +
            String.join(",", seed.getDigestMethod()));
    add("seed-value", "unsupported-digest",
        failure(() ->
            seed.setDigestMethod(Collections.singletonList("UNSUPPORTED"))));
  }

  private static void runExternalSigning() throws Exception {
    Path exampleRoot = fixtures.resolve(
        "examples/src/test/resources/org/apache/pdfbox/examples/signature");
    Path output = exchange.resolve("java-signed.pdf");
    SigningMaterial signing = loadSigningMaterial(
        exampleRoot.resolve("keystore.p12"), "123456");
    try (PDDocument document =
            Loader.loadPDF(exampleRoot.resolve("sign_me.pdf").toFile());
        OutputStream destination = Files.newOutputStream(output)) {
      PDSignature signature = new PDSignature();
      signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
      signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
      signature.setName("PdfCarton differential signer");
      signature.setReason("Security differential");
      Calendar date =
          new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
      date.clear();
      date.set(2024, Calendar.FEBRUARY, 3, 4, 5, 6);
      signature.setSignDate(date);
      document.addSignature(signature);
      ExternalSigningSupport external =
          document.saveIncrementalForExternalSigning(destination);
      byte[] content = readAll(external.getContent());
      external.setSignature(createCms(content, signing));
    }
    validateSignedPdf(output, "java");
  }

  private static byte[] createCms(
      byte[] content, SigningMaterial signing)
      throws Exception {
    CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
    ContentSigner contentSigner =
        new JcaContentSignerBuilder("SHA256withRSA")
            .build(signing.privateKey);
    generator.addSignerInfoGenerator(
        new JcaSignerInfoGeneratorBuilder(
            new JcaDigestCalculatorProviderBuilder().build())
            .build(contentSigner, signing.certificate));
    generator.addCertificates(
        new JcaCertStore(Collections.singletonList(signing.certificate)));
    return generator.generate(
        new CMSProcessableByteArray(content), false).getEncoded();
  }

  private static void validateSignedPdf(Path path, String id)
      throws Exception {
    byte[] bytes = Files.readAllBytes(path);
    try (PDDocument document = Loader.loadPDF(bytes)) {
      List<PDSignature> signatures = document.getSignatureDictionaries();
      PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm(null);
      List<PDField> fields =
          acroForm == null ? Collections.emptyList() : acroForm.getFields();
      COSArray rawFields = acroForm == null
          ? null
          : acroForm.getCOSObject().getCOSArray(COSName.FIELDS);
      add("external-signing", id + "-signature-discovery",
          "helpers=" + signatures.size() +
              ";fields=" + fields.size() +
              ";raw=" + (rawFields == null ? 0 : rawFields.size()));
      PDSignature current;
      if (!signatures.isEmpty()) {
        current = signatures.get(signatures.size() - 1);
      } else {
        COSDictionary field = rawFields == null
            ? null
            : (COSDictionary) rawFields.getObject(0);
        COSDictionary value =
            field == null ? null : field.getCOSDictionary(COSName.V);
        if (value == null) {
          throw new IllegalStateException(
              "The incremental signature dictionary is missing.");
        }
        current = new PDSignature(value);
      }
      byte[] signedContent = current.getSignedContent(bytes);
      byte[] contents = derObject(current.getContents(bytes));
      verifyCms(signedContent, contents);
      add("external-signing", id + "-byte-range",
          bool(validByteRange(current.getByteRange(), bytes.length)) +
              ";signatures=" + signatures.size());
      add("signature-validation", id, "valid");
      byte[] corrupt = contents.clone();
      corrupt[corrupt.length - 1] ^= 1;
      add("corrupt-signature", id,
          failure(() -> verifyCms(signedContent, corrupt)));
    }
  }

  private static void verifyCms(byte[] content, byte[] encoded)
      throws Exception {
    CMSSignedData data = new CMSSignedData(
        new CMSProcessableByteArray(content), encoded);
    SignerInformationStore signerInfos = data.getSignerInfos();
    if (signerInfos.size() != 1) {
      throw new CMSException("Expected one signer.");
    }
    Store<X509CertificateHolder> certificates = data.getCertificates();
    SignerInformation signer = signerInfos.getSigners().iterator().next();
    X509CertificateHolder holder = (X509CertificateHolder)
        certificates.getMatches(signer.getSID()).iterator().next();
    if (!signer.verify(
        new JcaSimpleSignerInfoVerifierBuilder().build(holder))) {
      throw new CMSException("Signature verification failed.");
    }
  }

  private static SigningMaterial loadSigningMaterial(
      Path path, String password)
      throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    char[] pin = password.toCharArray();
    try (InputStream input = Files.newInputStream(path)) {
      keyStore.load(input, pin);
    }
    Enumeration<String> aliases = keyStore.aliases();
    while (aliases.hasMoreElements()) {
      String alias = aliases.nextElement();
      Key key = keyStore.getKey(alias, pin);
      if (key instanceof PrivateKey) {
        return new SigningMaterial(
            (PrivateKey) key,
            (X509Certificate) keyStore.getCertificate(alias));
      }
    }
    throw new GeneralSecurityException(
        "Signing key material is missing.");
  }

  private static void runCmsFailureRows(String producer)
      throws Exception {
    Path source = exchange.resolve(producer + "-public-40.pdf");
    Path corrupt = exchange.resolve(producer + "-public-corrupt.pdf");
    Path unsupported =
        exchange.resolve(producer + "-public-unsupported.pdf");
    mutateRecipient(source, corrupt, RecipientMutation.CORRUPT_DER);
    mutateRecipient(
        source, unsupported, RecipientMutation.UNSUPPORTED_ALGORITHM);
    Path storePath = encryptionFixtureRoot().resolve("test1.pfx");
    add("corrupt-cms", producer,
        failure(() -> {
          try (InputStream store = Files.newInputStream(storePath);
              PDDocument ignored =
                  Loader.loadPDF(corrupt.toFile(), "test1", store, null)) {
            // Reaching this point is the failure observation.
          }
        }));
    add("unsupported-algorithm", producer,
        failure(() -> {
          try (InputStream store = Files.newInputStream(storePath);
              PDDocument ignored =
                  Loader.loadPDF(unsupported.toFile(), "test1", store, null)) {
            // Reaching this point is the failure observation.
          }
        }));
  }

  private static void mutateRecipient(
      Path source, Path destination, RecipientMutation mutation)
      throws Exception {
    byte[] bytes = Files.readAllBytes(source);
    byte[] prefix = "/Recipients [<".getBytes(StandardCharsets.US_ASCII);
    int start = indexOf(bytes, prefix, 0);
    if (start < 0) {
      throw new IllegalStateException(
          "Public-key recipient array is missing.");
    }
    start += prefix.length;
    if (mutation == RecipientMutation.CORRUPT_DER) {
      if (bytes[start] != '3' || bytes[start + 1] != '0') {
        throw new IllegalStateException(
            "CMS recipient is not a DER sequence.");
      }
      bytes[start + 1] = '1';
    } else {
      byte[] rc2Oid =
          "06082A864886F70D0302".getBytes(StandardCharsets.US_ASCII);
      int oid = indexOf(bytes, rc2Oid, start);
      if (oid < 0) {
        throw new IllegalStateException(
            "CMS RC2 algorithm identifier is missing.");
      }
      bytes[oid + rc2Oid.length - 1] = '3';
    }
    Files.write(destination, bytes);
  }

  private static int indexOf(byte[] bytes, byte[] pattern, int start) {
    outer:
    for (int index = start; index <= bytes.length - pattern.length; index++) {
      for (int part = 0; part < pattern.length; part++) {
        if (bytes[index + part] != pattern[part]) {
          continue outer;
        }
      }
      return index;
    }
    return -1;
  }

  private static AccessPermission restrictedPermission(boolean canPrint) {
    AccessPermission permission = new AccessPermission();
    permission.setCanAssembleDocument(false);
    permission.setCanExtractContent(false);
    permission.setCanExtractForAccessibility(true);
    permission.setCanFillInForm(false);
    permission.setCanModify(false);
    permission.setCanModifyAnnotations(false);
    permission.setCanPrint(canPrint);
    permission.setCanPrintFaithful(false);
    permission.setReadOnly();
    return permission;
  }

  private static String permission(AccessPermission permission) {
    return String.join(",",
        bool(permission.isOwnerPermission()),
        bool(permission.isReadOnly()),
        bool(permission.canAssembleDocument()),
        bool(permission.canExtractContent()),
        bool(permission.canExtractForAccessibility()),
        bool(permission.canFillInForm()),
        bool(permission.canModify()),
        bool(permission.canModifyAnnotations()),
        bool(permission.canPrint()),
        bool(permission.canPrintFaithful()));
  }

  private static String encryption(PDDocument document) throws IOException {
    return String.join(",",
        document.getEncryption().getFilter(),
        document.getEncryption().getSubFilter() == null
            ? "-"
            : document.getEncryption().getSubFilter(),
        Integer.toString(document.getEncryption().getVersion()),
        Integer.toString(document.getEncryption().getRevision()),
        Integer.toString(document.getEncryption().getLength()),
        Integer.toString(
            document.getEncryption().getSecurityHandler().getKeyLength()),
        bool(document.getEncryption().getSecurityHandler().isAES()),
        bool(document.getEncryption()
            .getSecurityHandler().isDecryptMetadata()));
  }

  private static String failure(ThrowingAction action) {
    try {
      action.run();
      return "none";
    } catch (Throwable error) {
      for (Throwable current = error;
          current != null;
          current = current.getCause()) {
        if (current instanceof InvalidPasswordException) {
          return "invalid-password";
        }
        if (current instanceof IllegalArgumentException) {
          return "invalid-argument";
        }
        if (current instanceof CMSException ||
            current instanceof GeneralSecurityException ||
            current instanceof OperatorCreationException) {
          return "cryptographic";
        }
        if (current instanceof IOException) {
          return "io";
        }
        if (current instanceof IllegalStateException) {
          return "invalid-state";
        }
      }
      return error.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }
  }

  private static boolean validByteRange(int[] range, int length) {
    return range.length == 4 &&
        range[0] == 0 &&
        range[1] > 0 &&
        range[2] > range[1] &&
        range[3] >= 0 &&
        (long) range[2] + range[3] == length;
  }

  private static byte[] derObject(byte[] padded)
      throws Exception {
    try (ASN1InputStream input = new ASN1InputStream(padded)) {
      ASN1Primitive value = input.readObject();
      if (value == null) {
        throw new GeneralSecurityException(
            "CMS value is not valid ASN.1.");
      }
      return value.getEncoded();
    }
  }

  private static byte[] readAll(InputStream input) throws IOException {
    try (InputStream source = input;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = source.read(buffer)) >= 0) {
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }

  private static String join(int[] values) {
    StringBuilder result = new StringBuilder();
    for (int index = 0; index < values.length; index++) {
      if (index > 0) {
        result.append(',');
      }
      result.append(values[index]);
    }
    return result.toString();
  }

  private static String bool(boolean value) {
    return value ? "true" : "false";
  }

  private static Path encryptionFixtureRoot() {
    return fixtures.resolve(
        "pdfbox/src/test/resources/org/apache/pdfbox/encryption");
  }

  private static List<Specification> specifications() {
    return Arrays.asList(
        new Specification(40, false),
        new Specification(128, false),
        new Specification(128, true),
        new Specification(256, true));
  }

  private static void add(String family, String id, Object value) {
    String key = family + "\t" + id;
    if (ROWS.putIfAbsent(key, String.valueOf(value)) != null) {
      throw new IllegalStateException("Duplicate trace key: " + key);
    }
  }

  private enum RecipientMutation {
    CORRUPT_DER,
    UNSUPPORTED_ALGORITHM
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }

  private static final class Specification {
    private final int bits;
    private final boolean aes;

    private Specification(int bits, boolean aes) {
      this.bits = bits;
      this.aes = aes;
    }

    private String id() {
      return bits + (aes ? "-aes" : "-rc4");
    }
  }

  private static final class SigningMaterial {
    private final PrivateKey privateKey;
    private final X509Certificate certificate;

    private SigningMaterial(
        PrivateKey privateKey, X509Certificate certificate) {
      this.privateKey = privateKey;
      this.certificate = certificate;
    }
  }
}
