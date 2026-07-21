(ns vibeformer.translation-boundary-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vibeformer.paths :as paths]
            [vibeformer.process :as process])
  (:import [java.nio.file CopyOption Files OpenOption Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(defn- source [relative]
  (slurp (str "src/vibeformer/" relative ".clj")))

(defn- generic-runtime-assets []
  (let [directory (paths/resolve-path (paths/workspace-root) "vibeformer" "runtime")]
    (->> (.listFiles (.toFile directory))
         (filter #(.isFile %))
         (filter #(str/starts-with? (.getName %) "Vibeformer."))
         (sort-by #(.getName %))
         vec)))

(defn- write-string! [^Path file value]
  (Files/createDirectories (.getParent file) (make-array FileAttribute 0))
  (Files/writeString file value (make-array OpenOption 0))
  file)

(def ^:private generic-economic-map-fixture
  (str
   "#nullable enable\n"
   "namespace Vibeformer.Runtime;\n\n"
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
   "  private static global::System.Collections.Generic.KeyValuePair<K, V> Entry<K, V>(K key, V value)\n"
   "    where K : notnull => new(key, value);\n"
   "  public static void Main()\n"
   "  {\n"
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
   "    global::System.Console.Write(\"OK\");\n"
   "  }\n"
   "}\n"))

(defn- compile-and-run-generic-runtime! [assets]
  (let [root (Files/createTempDirectory "vibeformer-generic-runtime"
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
      (is (not (str/includes? kernel "vibeformer.pkl"))))
    (testing "Pkl source identities and destinations are absent from reusable layers"
      (doseq [content [kernel frontend]]
        (is (not (re-find #"(?i)org\\.pkl|Pkl\\.Core|Pkl\\.Parser" content)))))))

(deftest generic-runtime-is-independently-product-neutral
  (let [assets (generic-runtime-assets)]
    (is (seq assets))
    (doseq [asset assets
            :let [content (slurp asset)
                  label (.getName asset)]]
      (testing label
        (is (str/includes? content "namespace Vibeformer.Runtime"))
        (is (not (re-find #"(?i)org\\.pkl|Pkl\\.(?:Core|Parser)" content)))
        (is (not (re-find #"(?i)#if[^\n]*PKL" content)))))
    (is (= "OK" (compile-and-run-generic-runtime! assets)))))

(deftest pkl-rules-depend-inward-on-the-reusable-kernel
  (let [body-rules (source "pkl/java_body")
        project-rules (source "pkl/java_project")
        project-emission (source "java_project")]
    (is (str/includes? body-rules "(ns vibeformer.pkl.java-body"))
    (is (str/includes? project-rules "(ns vibeformer.pkl.java-project"))
    (is (str/includes? body-rules "[vibeformer.java-translate :as java]"))
    (is (str/includes? project-rules
                       "[vibeformer.java-project :as project-emission]"))
    (is (str/includes? project-emission
                       "[vibeformer.java-translate :as java]"))))

(deftest pkl-runtime-identities-stay-in-the-destination-bridge
  (let [body-rules (source "pkl/java_body")
        project-rules (source "pkl/java_project")
        bridge (slurp "runtime/Pkl.Core.RuntimeBridge.cs")]
    (is (str/includes? body-rules
                       "Pkl.Core.Runtime.PklRuntimeBridge.IsRrbTreeLeaf"))
    (is (str/includes? body-rules
                       "result-generic-arguments-pkl-runtime-call services element \"MapOfEntriesLoose\""))
    (is (str/includes? bridge "internal static bool IsRrbTreeLeaf(object? value)"))
    (is (str/includes? bridge "PClassInfoEquals<T>"))
    (is (str/includes? bridge "MapOfEntriesLoose<K, V>"))
    (is (str/includes? project-rules
                       "PklRuntimeBridge.PClassInfoEquals(this, obj)"))
    (is (str/includes? bridge "Pkl.Core.Util.Paguro"))))

(deftest java-uri-component-mappings-retain-decoded-and-raw-api-pairs
  (let [body-rules (source "pkl/java_body")
        runtime (slurp "runtime/Vibeformer.JavaCompat.cs")]
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
           body-rules
           (str "executable:java.net.URI#" java-method "()\" (compat-call \""
                helper "\" [target])")))
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
  (let [runtime (slurp "runtime/Vibeformer.JavaCompat.cs")
        unicode-data (slurp "runtime/Vibeformer.JavaRegexUnicodeData.cs")]
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
  (let [body-rules (source "pkl/java_body")
        project-rules (source "pkl/java_project")
        runtime (slurp "runtime/Vibeformer.JavaCompat.cs")]
    (is (str/includes?
         project-rules
         "\"java.util.Map$Entry\" [\"global::Vibeformer.Runtime.JavaMapEntry\""))
    (doseq [[java-method helper]
            [["entrySet()" "MapEntrySet"]
             ["Iterator#remove()" "IteratorRemove"]]]
      (is (and (str/includes? body-rules java-method)
               (str/includes? body-rules helper))))
    (is (str/includes? body-rules
                       "java.util.Map$Entry#setValue(java.lang.Object)"))
    (is (str/includes? runtime
                       "internal sealed class JavaMapEntrySet<K, V>"))
    (is (str/includes? runtime
                       "public V SetValue(V replacement)"))
    (is (str/includes? runtime
                       "internal static void IteratorRemove(IEnumerator iterator)"))
    (is (not (re-find #"(?i)org\\.pkl|Pkl\\.Core|Pkl\\.Parser"
                      (subs runtime
                            (.indexOf runtime "internal sealed class JavaMapEntry")
                            (.indexOf runtime "internal static class JavaCompat")))))))
