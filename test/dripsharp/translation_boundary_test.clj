(ns dripsharp.translation-boundary-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dripsharp.paths :as paths]
            [dripsharp.process :as process])
  (:import [java.nio.file CopyOption Files OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(defn- source [relative]
  (slurp (str "src/dripsharp/" relative ".clj")))

(defn- generic-runtime-assets []
  (let [directory (paths/resolve-path (paths/workspace-root) "runtime")]
    (->> (.listFiles (.toFile directory))
         (filter #(.isFile %))
         (filter #(str/starts-with? (.getName %) "DripSharp."))
         (sort-by #(.getName %))
         vec)))

(defn- write-string! [^Path file value]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file value (make-array OpenOption 0))
  file)

(def ^:private configurable-java-compat-types
  [["JavaCloneable" "JavaCloneable"]
   ["JavaRoundingMode" "JavaRoundingMode"]
   ["JavaPriorityQueue" "JavaPriorityQueue<>"]
   ["JavaIdentityHashMap" "JavaIdentityHashMap<,>"]
   ["JavaSecretKey" "JavaSecretKey"]
   ["JavaSecurityProvider" "JavaSecurityProvider"]
   ["JavaAlgorithmParameters" "JavaAlgorithmParameters"]
   ["JavaAlgorithmParameterGenerator" "JavaAlgorithmParameterGenerator"]
   ["JavaKeyGenerator" "JavaKeyGenerator"]
   ["JavaSecretKeySpec" "JavaSecretKeySpec"]
   ["JavaIvParameterSpec" "JavaIvParameterSpec"]
   ["JavaCipher" "JavaCipher"]
   ["JavaCipherInputStream" "JavaCipherInputStream"]
   ["JavaNoSuchAlgorithmException" "JavaNoSuchAlgorithmException"]
   ["JavaNoSuchPaddingException" "JavaNoSuchPaddingException"]
   ["JavaUnrecoverableKeyException" "JavaUnrecoverableKeyException"]
   ["JavaReference" "JavaReference<>"]
   ["JavaSoftReference" "JavaSoftReference<>"]
   ["JavaWeakReference" "JavaWeakReference<>"]
   ["JavaBase64" "JavaBase64"]
   ["JavaBase64Encoder" "JavaBase64Encoder"]
   ["JavaBase64Decoder" "JavaBase64Decoder"]
   ["JavaByteArrayOutputStream" "JavaByteArrayOutputStream"]
   ["JavaCodingErrorAction" "JavaCodingErrorAction"]
   ["JavaDateTimeFormatter" "JavaDateTimeFormatter"]
   ["JavaParsePosition" "JavaParsePosition"]
   ["JavaSimpleDateFormat" "JavaSimpleDateFormat"]
   ["JavaRandom" "JavaRandom"]
   ["JavaCrc32" "JavaCrc32"]
   ["JavaMapEntry" "JavaMapEntry<,>"]
   ["JavaMapContract" "JavaMapContract<,>"]
   ["JavaDeque" "JavaDeque<>"]
   ["JavaWeakHashMap" "JavaWeakHashMap<,>"]
   ["JavaStack" "JavaStack<>"]
   ["JavaExecutorService" "JavaExecutorService"]
   ["JavaByteBuffer" "JavaByteBuffer"]
   ["JavaCharsetDecoder" "JavaCharsetDecoder"]
   ["JavaPath" "JavaPath"]
   ["JavaInputStream" "JavaInputStream"]
   ["JavaFilterInputStream" "JavaFilterInputStream"]
   ["JavaOutputStream" "JavaOutputStream"]
   ["JavaInflater" "JavaInflater"]
   ["JavaDeflater" "JavaDeflater"]
   ["JavaDeflaterOutputStream" "JavaDeflaterOutputStream"]
   ["JavaFilterOutputStream" "JavaFilterOutputStream"]
   ["JavaKeyStore" "JavaKeyStore"]
   ["JavaCertificateFactory" "JavaCertificateFactory"]
   ["JavaSslContext" "JavaSslContext"]
   ["JavaServerSocket" "JavaServerSocket"]
   ["IJavaOptional" "IJavaOptional"]
   ["JavaOptional" "JavaOptional<>"]
   ["JavaIterator" "JavaIterator<>"]
   ["JavaIterableContract" "JavaIterableContract<>"]
   ["JavaListContract" "JavaListContract<>"]
   ["JavaListIterator" "JavaListIterator<>"]
   ["JavaStream" "JavaStream<>"]])

(def ^:private generic-economic-map-fixture
  (str
   "#nullable enable\n"
   "namespace DripSharp.Runtime;\n\n"
   "internal sealed class FixtureCursor<K, V> : IJavaEconomicMapCursor<K, V>\n"
   "  where K : notnull\n"
   "{\n"
   "  private readonly global::System.Collections.Generic.IEnumerator<\n"
   "    global::System.Collections.Generic.KeyValuePair<K, V>> entries;\n"
   "  internal FixtureCursor(global::System.Collections.Generic.IEnumerator<\n"
   "    global::System.Collections.Generic.KeyValuePair<K, V>> entries) =>\n"
   "    this.entries = entries;\n"
   "  public bool Advance() => entries.MoveNext();\n"
   "  public K GetKey() => entries.Current.Key;\n"
   "  public V GetValue() => entries.Current.Value;\n"
   "}\n\n"
   "internal sealed class FixtureMap<K, V> : IJavaEconomicMap<K, V>\n"
   "  where K : notnull\n"
   "{\n"
   "  private readonly global::System.Collections.Generic.List<\n"
   "    global::System.Collections.Generic.KeyValuePair<K, V>> entries;\n"
   "  private readonly global::System.Collections.Generic.Dictionary<K, V> values = new();\n"
   "  internal FixtureMap(params global::System.Collections.Generic.KeyValuePair<K, V>[] entries)\n"
   "  {\n"
   "    this.entries = new(entries);\n"
   "    foreach (var entry in entries) values[entry.Key] = entry.Value;\n"
   "  }\n"
   "  public V? Get(K key) => values.TryGetValue(key, out var value) ? value : default;\n"
   "  public bool ContainsKey(K key) => values.ContainsKey(key);\n"
   "  public int Size() => entries.Count;\n"
   "  public IJavaEconomicMapCursor<K, V> GetEntries() => new FixtureCursor<K, V>(entries.GetEnumerator());\n"
   "}\n\n"
   "internal static class Program\n"
   "{\n"
   "  private static void Assert(bool condition, string message)\n"
   "  {\n"
   "    if (!condition) throw new global::System.Exception(message);\n"
   "  }\n\n"
   "  private static void AssertConfigurableVisibility()\n"
   "  {\n"
   "#if DRIPSHARP_INTERNAL_JAVA_COMPAT\n"
   "    const bool expectedPublic = false;\n"
   "#else\n"
   "    const bool expectedPublic = true;\n"
   "#endif\n"
   "    foreach (var type in new global::System.Type[]\n"
   "    {\n"
   (apply str
          (map (fn [[_ type]]
                 (str "      typeof(" type "),\n"))
               configurable-java-compat-types))
   "    })\n"
   "      Assert(type.IsPublic == expectedPublic,\n"
   "             $\"{type.Name} did not honor the configured visibility boundary\");\n"
   "  }\n\n"
   "  private static global::System.Collections.Generic.KeyValuePair<K, V> Entry<K, V>(K key, V value)\n"
   "    where K : notnull => new(key, value);\n"
   "  public static void Main()\n"
   "  {\n"
   "    AssertConfigurableVisibility();\n"
   "    var setValue = typeof(JavaMapEntry<,>).GetMethod(\"SetValue\");\n"
   "    Assert(setValue is { IsPublic: true }, \"JavaMapEntry.SetValue must be public\");\n"
   "    var liveMap = new global::System.Collections.Generic.Dictionary<string, string>\n"
   "      { [\"key\"] = \"before\" };\n"
   "    using var liveEntries = JavaCompat.MapEntrySet(\n"
   "      (global::System.Collections.Generic.IDictionary<string, string>)liveMap).GetEnumerator();\n"
   "    Assert(liveEntries.MoveNext(), \"map entry set did not expose its entry\");\n"
   "    var liveEntry = liveEntries.Current;\n"
   "    Assert(liveEntry.SetValue(\"after\") == \"before\" &&\n"
   "           liveMap[\"key\"] == \"after\" && liveEntry.Value == \"after\",\n"
   "           \"JavaMapEntry.SetValue did not replace and return the live value\");\n"
   "    liveMap[\"key\"] = \"external\";\n"
   "    Assert(liveEntry.Value == \"external\" &&\n"
   "           liveEntry.SetValue(\"final\") == \"external\" && liveMap[\"key\"] == \"final\",\n"
   "           \"JavaMapEntry stopped reflecting its source map\");\n"
   "    sbyte byteValue = -128;\n"
   "    Assert(JavaCompat.OrAssign(ref byteValue, 1) == -127 && byteValue == -127,\n"
   "           \"signed-byte OR assignment lost its narrowing contract\");\n"
   "    byteValue = 1;\n"
   "    Assert(JavaCompat.OrAssign(ref byteValue, 0x180) == -127 && byteValue == -127,\n"
   "           \"signed-byte OR assignment did not preserve low-byte semantics\");\n"
   "    var firstKey = new global::System.Uri(\"package://example.test/first@1\");\n"
   "    var secondKey = new global::System.Uri(\"package://example.test/second@1\");\n"
   "    var absentKey = new global::System.Uri(\"package://example.test/absent@1\");\n"
   "    var left = new FixtureMap<global::System.Uri, string?>(\n"
   "      Entry<global::System.Uri, string?>(firstKey, null),\n"
   "      Entry<global::System.Uri, string?>(secondKey, \"value\"));\n"
   "    var equal = new FixtureMap<global::System.Uri, string?>(\n"
   "      Entry<global::System.Uri, string?>(new global::System.Uri(firstKey.OriginalString), null),\n"
   "      Entry<global::System.Uri, string?>(new global::System.Uri(secondKey.OriginalString), \"value\"));\n"
   "    if (!JavaCompat.EconomicMapEquals(left, left) ||\n"
   "        !JavaCompat.EconomicMapEquals(left, equal))\n"
   "      throw new global::System.Exception(\"typed URI/null equality failed\");\n"
   "    var missingNullKey = new FixtureMap<global::System.Uri, string?>(\n"
   "      Entry<global::System.Uri, string?>(absentKey, null),\n"
   "      Entry<global::System.Uri, string?>(secondKey, \"value\"));\n"
   "    if (JavaCompat.EconomicMapEquals(left, missingNullKey))\n"
   "      throw new global::System.Exception(\"missing null-valued key compared equal\");\n"
   "    var cursor = left.GetEntries();\n"
   "    if (!cursor.Advance() || cursor.GetKey() != firstKey || cursor.GetValue() is not null ||\n"
   "        !cursor.Advance() || cursor.GetKey() != secondKey || cursor.GetValue() != \"value\" ||\n"
   "        cursor.Advance())\n"
   "      throw new global::System.Exception(\"cursor iteration contract failed\");\n"
   "    var hostless = JavaCompat.NewUri(\"http:///submit\");\n"
   "    if (JavaCompat.UriHost(hostless) is not null ||\n"
   "        JavaCompat.UriRawPath(hostless) != \"/submit\")\n"
   "      throw new global::System.Exception(\"hostless Java URI contract failed\");\n"
   "    var projectFile = JavaCompat.CreateUri(\"file:///tmp/PklProject\");\n"
   "    var projectBase = JavaCompat.ResolveUri(projectFile, \".\");\n"
   "    if (JavaCompat.UriAuthority(projectBase) is not null ||\n"
   "        JavaCompat.UriRawPath(projectBase) != \"/tmp/\")\n"
   "      throw new global::System.Exception(\"file project URI contract failed\");\n"
   "    var packageAsset = JavaCompat.CreateUri(\n"
   "      \"package://localhost:0/badImportsWithinPackage@1.0.0#/invalidPath.pkl\");\n"
   "    var packageBase = JavaCompat.NewUri(\n"
   "      JavaCompat.UriScheme(packageAsset),\n"
   "      JavaCompat.UriUserInfo(packageAsset),\n"
   "      JavaCompat.UriHost(packageAsset),\n"
   "      JavaCompat.UriPort(packageAsset),\n"
   "      JavaCompat.UriPath(packageAsset),\n"
   "      JavaCompat.UriQuery(packageAsset),\n"
   "      null);\n"
   "    var fragmentBase = JavaCompat.CreateUri(JavaCompat.UriFragment(packageAsset)!);\n"
   "    var fragmentTarget = JavaCompat.ResolveUri(\n"
   "      fragmentBase, JavaCompat.CreateUri(\"not/a/valid/path.pkl\"));\n"
   "    var packageTarget = JavaCompat.ResolveUri(\n"
   "      packageBase, \"#\" + JavaCompat.UriToString(fragmentTarget));\n"
   "    if (JavaCompat.UriToString(packageTarget) !=\n"
   "        \"package://localhost:0/badImportsWithinPackage@1.0.0#/not/a/valid/path.pkl\")\n"
   "      throw new global::System.Exception(\n"
   "        \"package fragment URI contract failed: \" + JavaCompat.UriToString(packageTarget));\n"
   "    var uriMap = JavaCompat.NewJavaDictionary<global::System.Uri, string>();\n"
   "    uriMap[packageAsset] = \"source\";\n"
   "    uriMap[packageTarget] = \"target\";\n"
   "    if (uriMap.Count != 2 || uriMap[packageAsset] != \"source\" ||\n"
   "        uriMap[packageTarget] != \"target\")\n"
   "      throw new global::System.Exception(\"Java URI map equality lost fragments\");\n"
   "    using var compressed = new global::System.IO.MemoryStream();\n"
   "    using (var compressor = new global::System.IO.Compression.ZLibStream(\n"
   "      compressed, global::System.IO.Compression.CompressionLevel.Optimal, true))\n"
   "      compressor.Write(global::System.Text.Encoding.UTF8.GetBytes(\"deflate-body\"));\n"
   "    using var decoded = new global::System.IO.MemoryStream();\n"
   "    using var inflater = new JavaInflaterOutputStream(decoded);\n"
   "    var encoded = compressed.ToArray();\n"
   "    inflater.Write(encoded, 0, encoded.Length);\n"
   "    inflater.Flush();\n"
   "    if (global::System.Text.Encoding.UTF8.GetString(decoded.ToArray()) != \"deflate-body\")\n"
   "      throw new global::System.Exception(\"inflater flush contract failed\");\n"
   "    var invalidBase64Rejected = false;\n"
   "    try { JavaBase64.GetDecoder().Decode(\"~\"); }\n"
   "    catch (global::System.ArgumentException error)\n"
   "    { invalidBase64Rejected = error.Message == \"Illegal base64 character 7e\"; }\n"
   "    if (!invalidBase64Rejected)\n"
   "      throw new global::System.Exception(\"invalid Base64 lost Java diagnostics\");\n"
   "    var invalidUtf8Rejected = false;\n"
   "    try\n"
   "    {\n"
   "      new JavaCharsetDecoder(global::System.Text.Encoding.UTF8).Decode(\n"
   "        JavaByteBuffer.wrap(new sbyte[] { unchecked((sbyte)0xc3), 0x28 }));\n"
   "    }\n"
   "    catch (global::System.Text.DecoderFallbackException)\n"
   "    { invalidUtf8Rejected = true; }\n"
   "    if (!invalidUtf8Rejected)\n"
   "      throw new global::System.Exception(\"Java charset decoder replaced malformed input\");\n"
   "    var fixedDecimal = new JavaDecimalFormat(\n"
   "      \"0.0000000\", global::System.Globalization.CultureInfo.InvariantCulture.NumberFormat);\n"
   "    if (fixedDecimal.Format(123456789.123456789d) != \"123456789.1234568\" ||\n"
   "        fixedDecimal.Format(-0.0d) != \"-0.0000000\")\n"
   "      throw new global::System.Exception(\"Java decimal formatting contract failed\");\n"
   "    if (JavaStrictMath.Sin(2.34d) != 0.7184647930691261d ||\n"
   "        JavaStrictMath.Cos(2.34d) != -0.695563326462902d ||\n"
   "        JavaStrictMath.Log10(2.34d) != 0.36921585741014284d ||\n"
   "        JavaStrictMath.Atan2(4.5d, -3.0d) != 2.1587989303424644d ||\n"
   "        JavaStrictMath.Atan2(4.0d, 3.5d) != 0.851966327173272d ||\n"
   "        JavaStrictMath.Pow(2.3d, -4.0d) != 0.03573457784956459d)\n"
   "      throw new global::System.Exception(\"Java StrictMath contract failed\");\n"
   "    var rendered = new global::System.Text.StringBuilder();\n"
   "    JavaCompat.StringBuilderAppendInvariant(rendered, true);\n"
   "    JavaCompat.StringBuilderAppendInvariant(rendered, 1.0d);\n"
   "    if (rendered.ToString() != \"true1.0\")\n"
   "      throw new global::System.Exception(\"StringBuilder append lost Java primitive rendering semantics\");\n"
   "    global::System.Console.Write(\"OK\");\n"
   "  }\n"
   "}\n"))

(defn- compile-and-run-generic-runtime! [assets internal-visibility?]
  (let [root (Files/createTempDirectory "dripsharp-generic-runtime"
                                        (make-array FileAttribute 0))
        runtime-output (:output (process/run! {:directory root
                                               :command ["dotnet" "--list-runtimes"]}))
        runtime-majors (keep (fn [line]
                               (when-let [[_ major]
                                          (re-find #"^Microsoft\.NETCore\.App (\d+)\."
                                                   line)]
                                 (parse-long major)))
                             (str/split-lines runtime-output))
        target-framework (str "net" (apply max runtime-majors) ".0")
        source-root (paths/resolve-path root "src")
        project (write-string!
                 (paths/resolve-path root "GenericRuntime.csproj")
                 (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
                      "  <PropertyGroup>\n"
                      "    <OutputType>Exe</OutputType>\n"
                      "    <TargetFramework>" target-framework "</TargetFramework>\n"
                      "    <Nullable>enable</Nullable>\n"
                      "    <ImplicitUsings>disable</ImplicitUsings>\n"
                      "    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>\n"
                      (when internal-visibility?
                        "    <DefineConstants>DRIPSHARP_INTERNAL_JAVA_COMPAT</DefineConstants>\n")
                      "    <EnableDefaultCompileItems>false</EnableDefaultCompileItems>\n"
                      "  </PropertyGroup>\n"
                      "  <ItemGroup><Compile Include=\"src/**/*.cs\" /></ItemGroup>\n"
                      "</Project>\n"))]
    (doseq [asset assets]
      (let [destination (paths/resolve-path source-root (.getName asset))]
        (Files/createDirectories (.getParent destination)
                                 (make-array FileAttribute 0))
        (Files/copy (.toPath asset) destination
                    (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    (write-string! (paths/resolve-path source-root "Program.cs")
                   generic-economic-map-fixture)
    (process/run! {:directory root
                   :command ["dotnet" "build" project "--nologo"
                             "--configuration" "Release" "--verbosity:quiet"
                             "-warnaserror"]})
    (str/trim
     (:output (process/run! {:directory root
                             :command ["dotnet" "run" "--project" project
                                       "--configuration" "Release" "--no-build"]})))))

(deftest reusable-translation-kernel-is-product-neutral
  (let [kernel (source "java_translate")
        frontend (source "spoon")]
    (testing "the reusable kernel does not depend on the Pkl rule bundle"
      (is (not (str/includes? kernel "dripsharp.pkl"))))
    (testing "Pkl source identities and destinations are absent from reusable layers"
      (doseq [content [kernel frontend]]
        (is (not (re-find #"(?i)org\\.pkl|Pkl\\.Core|Pkl\\.Parser" content)))))))

(deftest pdfcube-logging-adaptation-stays-in-the-pdfcube-destination
  (let [generic-rules [(source "java_library") (source "java_types")]
        pdfcube-rules (source "pdfcube/java_project")]
    (doseq [content generic-rules]
      (is (not (str/includes? content "org.apache.commons.logging")))
      (is (not (str/includes? content "Microsoft.Extensions.Logging"))))
    (is (str/includes? pdfcube-rules "org.apache.commons.logging"))
    (is (str/includes? pdfcube-rules "Microsoft.Extensions.Logging"))))

(deftest generic-runtime-is-independently-product-neutral
  (let [assets (generic-runtime-assets)]
    (is (seq assets))
    (doseq [asset assets
            :let [content (slurp asset)
                  label (.getName asset)]]
      (testing label
        (is (str/includes? content "namespace DripSharp.Runtime"))
        (is (not (re-find #"(?i)org\\.pkl|Pkl\\.(?:Core|Parser)" content)))
        (is (not (re-find #"(?i)#if[^\n]*PKL" content)))))
    (testing "the reusable runtime is public to independently generated products"
      (is (= "OK" (compile-and-run-generic-runtime! assets false))))
    (testing "Pkl products can keep the reusable runtime internal"
      (is (= "OK" (compile-and-run-generic-runtime! assets true))))))

(deftest product-bundles-select-java-compatibility-visibility-explicitly
  (let [runtime (slurp "runtime/DripSharp.JavaCompat.cs")
        guarded-types
        (map second
             (re-seq
              #"(?m)#if DRIPSHARP_INTERNAL_JAVA_COMPAT\ninternal\n#else\npublic\n#endif\n(?:(?:sealed|abstract|static)\s+)?(?:class|interface|enum)\s+([A-Za-z0-9_]+)"
              runtime))
        expected-types (map first configurable-java-compat-types)
        pkl-parser (slurp "config/pkl-parser.edn")
        pkl-core (slurp "config/pkl-core-value-model-destination.edn")
        rawhttp (slurp "config/rawhttp-core-destination.edn")]
    (is (= (sort expected-types) (sort guarded-types))
        (str "Unexpected configurable JavaCompat boundary: "
             (sort guarded-types)))
    (doseq [configuration [pkl-parser pkl-core]]
      (is (str/includes? configuration
                         ":define-constants [\"DRIPSHARP_INTERNAL_JAVA_COMPAT\"]")))
    (is (not (str/includes? pkl-parser "CS0436")))
    (is (str/includes?
         pkl-core
         ":no-warn [\"CS0108\" \"CS0109\" \"CS0414\" \"CS0436\"")
        "Pkl.Core alone suppresses the intentional duplicate internal JavaCompat types")
    (is (not (str/includes? rawhttp "DRIPSHARP_INTERNAL_JAVA_COMPAT")))))

(deftest pkl-rules-depend-inward-on-the-reusable-kernel
  (let [body-rules (source "pkl/java_body")
        project-rules (source "pkl/java_project")
        project-emission (source "java_project")]
    (is (str/includes? body-rules "(ns dripsharp.pkl.java-body"))
    (is (str/includes? project-rules "(ns dripsharp.pkl.java-project"))
    (is (str/includes? body-rules
                       "[dripsharp.java-library :as java-library]"))
    (is (str/includes? project-rules
                       "[dripsharp.java-library :as java-library]"))
    (is (str/includes? project-rules
                       "[dripsharp.java-project :as project-emission]"))
    (is (str/includes? project-emission
                       "[dripsharp.java-translate :as java]"))))

(deftest pkl-runtime-identities-stay-in-the-destination-bridge
  (let [generic-rules (source "java_library")
        body-rules (source "pkl/java_body")
        project-rules (source "pkl/java_project")
        bridge (slurp "runtime/Pkl.Core.RuntimeBridge.cs")]
    (is (str/includes? body-rules
                       "(pkl-runtime-call \"IsRrbTreeLeaf\""))
    (is (str/includes? body-rules "MapOfEntriesLoose"))
    (is (not (str/includes? generic-rules "Pkl.Core.Runtime.PklRuntimeBridge")))
    (is (str/includes? bridge "internal static bool IsRrbTreeLeaf(object? value)"))
    (is (str/includes? bridge "PClassInfoEquals<T>"))
    (is (str/includes? bridge "PClassInfoAsObject<T>"))
    (is (str/includes? bridge "MapOfEntriesLoose<K, V>"))
    (is (str/includes? bridge "RrbTree<TOuter>.MutRrbt<T>"))
    (is (str/includes? bridge "target.Append(value)"))
    (is (not (str/includes? bridge "target.Add(value)")))
    (is (str/includes? project-rules
                       "PklRuntimeBridge.PClassInfoEquals(this, obj)"))
    (is (str/includes? bridge "Pkl.Core.Util.Paguro"))))

(deftest java-uri-component-mappings-retain-decoded-and-raw-api-pairs
  (let [body-rules (source "java_library")
        mapping-rules (source "java_library_mappings")
        runtime (slurp "runtime/DripSharp.JavaCompat.cs")]
    (doseq [[java-method helper]
            [["getAuthority" "UriAuthority"]
             ["getFragment" "UriFragment"]
             ["getPath" "UriPath"]
             ["getQuery" "UriQuery"]
             ["getSchemeSpecificPart" "UriSchemeSpecificPart"]
             ["getUserInfo" "UriUserInfo"]
             ["getRawAuthority" "UriRawAuthority"]
             ["getRawFragment" "UriRawFragment"]
             ["getRawPath" "UriRawPath"]
             ["getRawQuery" "UriRawQuery"]
             ["getRawSchemeSpecificPart" "UriRawSchemeSpecificPart"]
             ["getRawUserInfo" "UriRawUserInfo"]]]
      (is (str/includes?
           mapping-rules (str "executable:java.net.URI#" java-method "()")))
      (is (str/includes? body-rules (str "\"" helper "\"")))
      (is (str/includes? runtime (str " " helper "(Uri uri)"))))
    (is (str/includes?
         runtime
         "UriSchemeSpecificPart(Uri uri) =>\n        DecodeUriComponent(UriRawSchemeSpecificPart(uri))"))
    (is (str/includes?
         runtime
         "UriFragment(Uri uri) => DecodeUriComponent(UriRawFragment(uri))"))
    (is (str/includes?
         runtime
         "UriQuery(Uri uri) => DecodeUriComponent(UriRawQuery(uri))"))
    (is (str/includes?
         runtime
         "UriPath(Uri uri) => DecodeUriComponent(UriRawPath(uri))"))
    (is (str/includes?
         runtime
         "value.IsAbsoluteUri && value.IsFile &&"))
    (is (str/includes?
         runtime
         "!value.OriginalString.StartsWith(\"file:\", StringComparison.OrdinalIgnoreCase)"))
    (is (str/includes?
         runtime
         "? value.AbsoluteUri"))))

(deftest translated-regex-carriers-retain-the-original-pattern
  (let [runtime (slurp "runtime/DripSharp.JavaCompat.cs")
        unicode-data (slurp "runtime/DripSharp.JavaRegexUnicodeData.cs")]
    (is (str/includes?
         runtime
         "private sealed class JavaRegex("))
    (is (str/includes? runtime "internal int Flags { get; } = flags"))
    (is (str/includes? runtime "internal string[] GroupNames { get; } = groupNames"))
    (is (str/includes?
         runtime
         "public override string ToString() => originalPattern"))
    (is (str/includes?
         runtime
         "var translator = new JavaRegexTranslator(pattern)"))
    (is (str/includes?
         runtime
         "var result = new JavaRegex(pattern, translated, options, effectiveFlags, groupNames, namedGroups)"))
    (is (str/includes? runtime "internal static string[] RegexSplit"))
    (is (str/includes? runtime "internal static string QuoteRegex"))
    (is (str/includes? runtime "JavaRegexUnicodeData.GzipBase64"))
    (is (str/includes? runtime "JavaGraphemeClusterPattern"))
    (is (str/includes? unicode-data "Regenerate with GenerateRegexUnicodeData.java"))))

(deftest java-map-entry-sets-retain-live-view-contracts
  (let [body-rules (source "java_library")
        mapping-rules (source "java_library_mappings")
        project-rules (source "pkl/java_project")
        runtime (slurp "runtime/DripSharp.JavaCompat.cs")]
    (is (str/includes?
         project-rules
         "\"java.util.Map$Entry\" [\"global::DripSharp.Runtime.JavaMapEntry\""))
    (is (and (str/includes? mapping-rules "entrySet()")
             (str/includes? body-rules "MapEntrySet")))
    (is (and (str/includes? mapping-rules "Iterator#remove()")
             (str/includes? body-rules "(raw \".Remove()\")")))
    (is (str/includes? mapping-rules
                       "java.util.Map$Entry#setValue(java.lang.Object)"))
    (is (str/includes? runtime
                       "internal sealed class JavaMapEntrySet<K, V>"))
    (is (str/includes? runtime
                       "public virtual V SetValue(V replacement)"))
    (is (str/includes? runtime
                       "internal static void IteratorRemove(IEnumerator iterator)"))
    (is (not (re-find #"(?i)org\\.pkl|Pkl\\.Core|Pkl\\.Parser"
                      (subs runtime
                            (.indexOf runtime "internal sealed class JavaMapEntry")
                            (.indexOf runtime "internal static class JavaCompat")))))))
