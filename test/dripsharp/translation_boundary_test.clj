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

(def ^:private java-compat-area-files
  ["DripSharp.JavaCompat.Java.IO.cs"
   "DripSharp.JavaCompat.Java.Lang.cs"
   "DripSharp.JavaCompat.Java.Math.cs"
   "DripSharp.JavaCompat.Java.Net.cs"
   "DripSharp.JavaCompat.Java.Nio.cs"
   "DripSharp.JavaCompat.Java.Sql.cs"
   "DripSharp.JavaCompat.Java.Security.cs"
   "DripSharp.JavaCompat.Java.Text.cs"
   "DripSharp.JavaCompat.Java.Time.cs"
   "DripSharp.JavaCompat.Java.Util.cs"
   "DripSharp.JavaCompat.Java.Util.Concurrent.cs"
   "DripSharp.JavaCompat.Java.Util.Regex.cs"
   "DripSharp.JavaCompat.Java.Xml.cs"])

(defn- java-compat-runtime []
  (->> java-compat-area-files
       (map #(slurp (str "runtime/" %)))
       (str/join "\n")))

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
   ["JavaLinkedHashMap" "JavaLinkedHashMap<,>"]
   ["JavaStack" "JavaStack<>"]
   ["JavaLogLevel" "JavaLogLevel"]
   ["JavaLogger" "JavaLogger"]
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
   ["JavaDatabaseMetaData" "JavaDatabaseMetaData"]
   ["JavaResultSet" "JavaResultSet"]
   ["JavaResultSetMetaData" "JavaResultSetMetaData"]
   ["JavaSqlDate" "JavaSqlDate"]
   ["JavaSqlTime" "JavaSqlTime"]
   ["JavaSqlTimestamp" "JavaSqlTimestamp"]
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
   "    if (JavaCompat.NumberIntValue(-47.5f) != -47 ||\n"
   "        JavaCompat.NumberIntValue(double.NaN) != 0 ||\n"
   "        JavaCompat.NumberIntValue(double.PositiveInfinity) != int.MaxValue)\n"
   "      throw new global::System.Exception(\"Number.intValue lost Java narrowing semantics\");\n"
   "    var parent = JavaCompat.PathParent(global::System.IO.Path.Combine(\"root\", \"leaf\"));\n"
   "    if (parent is null || parent.ToString() != \"root\")\n"
   "      throw new global::System.Exception(\"Path.getParent lost the JavaPath wrapper\");\n"
   "    var fixedList = JavaCompat.AsList(1, 2, 1);\n"
   "    fixedList[1] = 3;\n"
   "    if (fixedList[1] != 3)\n"
   "      throw new global::System.Exception(\"Arrays.asList did not preserve settable fixed-size semantics\");\n"
   "    var duplicateValues = new global::System.Collections.Generic.List<int> { 1, 2, 1, 3 };\n"
   "    if (!JavaCompat.RemoveAll(duplicateValues, new[] { 1 }) ||\n"
   "        duplicateValues.Count != 2 || duplicateValues[0] != 2 || duplicateValues[1] != 3)\n"
   "      throw new global::System.Exception(\"Collection.removeAll retained a duplicate match\");\n"
   "    var nullKeyMap = JavaCompat.NewJavaDictionary<string, string>();\n"
   "    JavaCompat.MapPut(nullKeyMap, null!, \"value\");\n"
   "    if (JavaCompat.MapGet(nullKeyMap, null) != \"value\")\n"
   "      throw new global::System.Exception(\"HashMap rejected its Java null-key contract\");\n"
   "    var unmodifiable = JavaCompat.UnmodifiableMap(JavaCompat.NewJavaDictionary<string, string>());\n"
   "    var clearRejected = false;\n"
   "    try { unmodifiable.Clear(); }\n"
   "    catch (global::System.NotSupportedException) { clearRejected = true; }\n"
   "    if (!clearRejected)\n"
   "      throw new global::System.Exception(\"UnmodifiableMap allowed clear on an empty map\");\n"
   "    var removeRejected = false;\n"
   "    try { unmodifiable.Remove(\"absent\"); }\n"
   "    catch (global::System.NotSupportedException) { removeRejected = true; }\n"
   "    if (!removeRejected)\n"
   "      throw new global::System.Exception(\"UnmodifiableMap allowed removal of an absent key\");\n"
   "    var putAllRejected = false;\n"
   "    try\n"
   "    {\n"
   "      JavaCompat.MapPutAll(unmodifiable,\n"
   "        global::System.Array.Empty<global::System.Collections.Generic.KeyValuePair<string, string>>());\n"
   "    }\n"
   "    catch (global::System.NotSupportedException) { putAllRejected = true; }\n"
   "    if (!putAllRejected)\n"
   "      throw new global::System.Exception(\"UnmodifiableMap allowed putAll from an empty map\");\n"
   "    var utcCalendar = JavaCompat.CalendarInstance(global::System.TimeZoneInfo.Utc);\n"
   "    var dateFormat = new JavaSimpleDateFormat(\n"
   "      \"H:m M/d/yy\", global::System.Globalization.CultureInfo.GetCultureInfo(\"en\"));\n"
   "    dateFormat.SetCalendar(utcCalendar);\n"
   "    var parsePosition = new JavaParsePosition(0);\n"
   "    var parsedCalendar = dateFormat.Parse(\"9:47 5/12/2002\", parsePosition);\n"
   "    if (parsedCalendar is null || parsedCalendar.Value.Year != 2002 ||\n"
   "        parsedCalendar.Value.Offset != global::System.TimeSpan.Zero)\n"
   "      throw new global::System.Exception(\"SimpleDateFormat lost Java four-digit yy or calendar timezone semantics\");\n"
   "    var windowYear = global::System.DateTime.Now.Year - 79;\n"
   "    parsePosition = new JavaParsePosition(0);\n"
   "    parsedCalendar = dateFormat.Parse($\"0:00 1/1/{windowYear % 100:D2}\", parsePosition);\n"
   "    if (parsedCalendar is null || parsedCalendar.Value.Year != windowYear)\n"
   "      throw new global::System.Exception(\"SimpleDateFormat lost Java's rolling two-digit-year window\");\n"
   "    var weekdayFormat = new JavaSimpleDateFormat(\n"
   "      \"EEEE, MMM dd, yy\", global::System.Globalization.CultureInfo.GetCultureInfo(\"en\"));\n"
   "    weekdayFormat.SetCalendar(utcCalendar);\n"
   "    var weekdayPosition = new JavaParsePosition(0);\n"
   "    var weekdayCalendar = weekdayFormat.Parse(\"Friday, January 11, 2115\", weekdayPosition);\n"
   "    if (weekdayCalendar is null || weekdayCalendar.Value.Year != 2115 ||\n"
   "        weekdayCalendar.Value.Month != 1 || weekdayCalendar.Value.Day != 11)\n"
   "      throw new global::System.Exception(\"SimpleDateFormat incorrectly validated a parsed weekday\");\n"
   "    weekdayPosition = new JavaParsePosition(0);\n"
   "    weekdayCalendar = weekdayFormat.Parse(\" Wed, January 11, 2215\", weekdayPosition);\n"
   "    if (weekdayCalendar is null || weekdayCalendar.Value.Year != 2215)\n"
   "      throw new global::System.Exception(\"SimpleDateFormat rejected an abbreviated weekday\");\n"
   "    var variableWidthFormat = new JavaSimpleDateFormat(\n"
   "      \"EEEE, dd MMM yy hh:mm:ss a\", global::System.Globalization.CultureInfo.GetCultureInfo(\"en\"));\n"
   "    variableWidthFormat.SetCalendar(utcCalendar);\n"
   "    var variableWidthPosition = new JavaParsePosition(0);\n"
   "    var variableWidthCalendar = variableWidthFormat.Parse(\"Tuesday, 6 Jul 1971 5:22:1 PM\", variableWidthPosition);\n"
   "    if (variableWidthCalendar is null || variableWidthCalendar.Value.Year != 1971 ||\n"
   "        variableWidthCalendar.Value.Hour != 17 || variableWidthCalendar.Value.Second != 1)\n"
   "      throw new global::System.Exception(\"SimpleDateFormat rejected Java variable-width numeric fields\");\n"
   "    var inlineZoneFormat = new JavaSimpleDateFormat(\n"
   "      \"EEEE MMM dd HH:mm:ss z yy\", global::System.Globalization.CultureInfo.GetCultureInfo(\"en\"));\n"
   "    inlineZoneFormat.SetCalendar(utcCalendar);\n"
   "    var inlineZonePosition = new JavaParsePosition(0);\n"
   "    var inlineZoneCalendar = inlineZoneFormat.Parse(\"Friday July 6 17:22:1 GMT+08:00 1979\", inlineZonePosition);\n"
   "    if (inlineZoneCalendar is null || inlineZoneCalendar.Value.Year != 1979 ||\n"
   "        inlineZoneCalendar.Value.Offset != global::System.TimeSpan.FromHours(8))\n"
   "      throw new global::System.Exception(\"SimpleDateFormat rejected an inline GMT offset\");\n"
   "    var newYork = JavaCompat.GetTimeZone(\"America/New_York\");\n"
   "    if (JavaCompat.TimeZoneId(JavaCompat.GetTimeZone(\"not/a-real-time-zone\")) != \"GMT\")\n"
   "      throw new global::System.Exception(\"TimeZone.getTimeZone lost Java's unknown-zone GMT sentinel\");\n"
   "    var winter = JavaCompat.CalendarSet(JavaCompat.CalendarInstance(newYork),\n"
   "      2014, 1, 28, 3, 14, 15);\n"
   "    var summer = JavaCompat.CalendarSet(JavaCompat.CalendarInstance(newYork),\n"
   "      2013, 7, 28, 3, 14, 15);\n"
   "    if (JavaCompat.CalendarGet(winter, 15) != -5 * 60 * 60 * 1000 ||\n"
   "        JavaCompat.CalendarGet(winter, 16) != 0 ||\n"
   "        JavaCompat.CalendarGet(summer, 15) != -5 * 60 * 60 * 1000 ||\n"
   "        JavaCompat.CalendarGet(summer, 16) != 1 * 60 * 60 * 1000)\n"
   "      throw new global::System.Exception(\"Calendar lost raw-offset or historical DST semantics\");\n"
   "    var mutableFixedZone = JavaCompat.NewSimpleTimeZone(0, \"GMT\");\n"
   "    JavaCompat.TimeZoneSetRawOffset(mutableFixedZone, 60 * 60 * 1000);\n"
   "    var fixedCalendar = JavaCompat.CalendarSetTimeZone(utcCalendar, mutableFixedZone);\n"
   "    fixedCalendar = JavaCompat.CalendarAdd(fixedCalendar, 12, -60);\n"
   "    if (fixedCalendar.Offset != global::System.TimeSpan.FromHours(1) ||\n"
   "        JavaCompat.CalendarGet(fixedCalendar, 15) != 60 * 60 * 1000)\n"
   "      throw new global::System.Exception(\"Calendar lost a mutable fixed raw offset\");\n"
   "    var xml = new global::System.Xml.XmlDocument();\n"
   "    xml.LoadXml(\"<root xmlns:stRef='urn:test'><stRef:instanceID /></root>\");\n"
   "    var xmlElement = xml.DocumentElement!.FirstChild!;\n"
   "    if (JavaCompat.StringValueOf(xmlElement) != \"[stRef:instanceID: null]\")\n"
   "      throw new global::System.Exception(\"DOM node lost its Java diagnostic string\");\n"
   "    var xmlSettings = new global::System.Xml.XmlWriterSettings\n"
   "    {\n"
   "      Encoding = new global::System.Text.UTF8Encoding(false),\n"
   "      OmitXmlDeclaration = true\n"
   "    };\n"
   "    using var xmlOutput = new global::System.IO.MemoryStream();\n"
   "    JavaCompat.XmlTransform(xmlSettings, xml, xmlOutput);\n"
   "    var serializedXml = global::System.Text.Encoding.UTF8.GetString(xmlOutput.ToArray());\n"
   "    if (!serializedXml.Contains(\"<stRef:instanceID/>\", global::System.StringComparison.Ordinal) ||\n"
   "        serializedXml.Contains(\" />\", global::System.StringComparison.Ordinal))\n"
   "      throw new global::System.Exception(\"Transformer retained .NET empty-element spacing\");\n"
   "    if (JavaCompat.ClassName(typeof(JavaPath), \"DripSharp.Runtime\", \"java.nio.file\") !=\n"
   "        \"java.nio.file.JavaPath\")\n"
   "      throw new global::System.Exception(\"Class.getName lost the configured source package\");\n"
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

(defn- compile-and-run-netstandard-rune-probe! []
  (let [root (Files/createTempDirectory "dripsharp-netstandard-rune"
                                        (make-array FileAttribute 0))
        library-root (paths/resolve-path root "library")
        consumer-root (paths/resolve-path root "consumer")
        library-project
        (write-string!
         (paths/resolve-path library-root "RuntimeProbe.csproj")
         (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
              "  <PropertyGroup>\n"
              "    <TargetFramework>netstandard2.0</TargetFramework>\n"
              "    <LangVersion>latest</LangVersion>\n"
              "    <Nullable>enable</Nullable>\n"
              "    <ImplicitUsings>disable</ImplicitUsings>\n"
              "    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>\n"
              "  </PropertyGroup>\n"
              "</Project>\n"))
        _ (write-string!
           (paths/resolve-path library-root "DripSharp.JavaCompat.NetStandard.cs")
           (slurp "runtime/DripSharp.JavaCompat.NetStandard.cs"))
        _ (write-string!
           (paths/resolve-path library-root "RuneProbe.cs")
           (str "using System.Linq;\n"
                "namespace DripSharp.Runtime\n"
                "{\n"
                "    public static class RuneProbe\n"
                "    {\n"
                "        public static string Convert()\n"
                "        {\n"
                "            var scalar = new Rune(0x1f642).ToString();\n"
                "            var sequence = string.Concat(\"A\\U0001f642\".EnumerateRunes()\n"
                "                .Select(rune => rune.ToString()));\n"
                "            return scalar + \"|\" + sequence;\n"
                "        }\n"
                "    }\n"
                "}\n"))
        consumer-project
        (write-string!
         (paths/resolve-path consumer-root "Consumer.csproj")
         (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n"
              "  <PropertyGroup>\n"
              "    <OutputType>Exe</OutputType>\n"
              "    <TargetFramework>net10.0</TargetFramework>\n"
              "    <Nullable>enable</Nullable>\n"
              "    <ImplicitUsings>disable</ImplicitUsings>\n"
              "    <TreatWarningsAsErrors>true</TreatWarningsAsErrors>\n"
              "  </PropertyGroup>\n"
              "  <ItemGroup>\n"
              "    <ProjectReference Include=\"../library/RuntimeProbe.csproj\" />\n"
              "  </ItemGroup>\n"
              "</Project>\n"))
        _ (write-string!
           (paths/resolve-path consumer-root "Program.cs")
           (str "using System;\n"
                "using DripSharp.Runtime;\n"
                "Console.Write(RuneProbe.Convert());\n"))
        build (process/run! {:directory root
                             :command ["dotnet" "build" consumer-project
                                       "--nologo" "--configuration" "Release"
                                       "--verbosity:quiet" "-warnaserror"]})
        run (process/run! {:directory root
                           :command ["dotnet" "run" "--project" consumer-project
                                     "--configuration" "Release" "--no-build"]})]
    {:build build :run run :output (str/trim (:output run))}))

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
    (is (= (sort java-compat-area-files)
           (->> assets
                (map #(.getName %))
                (filter #(str/starts-with? % "DripSharp.JavaCompat."))
                sort)))
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

(deftest netstandard-runes-convert-to-scalar-and-sequence-text
  (let [{:keys [build run output]} (compile-and-run-netstandard-rune-probe!)]
    (is (zero? (:exit build)) (:output build))
    (is (zero? (:exit run)) (:output run))
    (is (= "🙂|A🙂" output))))

(deftest product-bundles-select-java-compatibility-visibility-explicitly
  (let [runtime (java-compat-runtime)
        guarded-types
        (map second
             (re-seq
              #"(?m)#if DRIPSHARP_INTERNAL_JAVA_COMPAT\ninternal\n#else\npublic\n#endif\n(?:(?:sealed|abstract|static)\s+)?(?:class|interface|enum)\s+([A-Za-z0-9_]+)"
              runtime))
        expected-types (map first configurable-java-compat-types)
        pkl-parser (slurp "targets/pkl/destinations/parser.edn")
        pkl-core (slurp "targets/pkl/destinations/core.edn")
        rawhttp (slurp "targets/rawhttp/destinations/core.edn")]
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
        "DripSharp.Brine alone suppresses the intentional duplicate internal JavaCompat types")
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
        bridge (slurp "targets/pkl/runtime/DripSharp.Brine.RuntimeBridge.cs")]
    (is (str/includes? body-rules
                       "(pkl-runtime-call \"IsRrbTreeLeaf\""))
    (is (str/includes? body-rules "MapOfEntriesLoose"))
    (is (not (str/includes? generic-rules "DripSharp.Brine.Runtime.PklRuntimeBridge")))
    (is (str/includes? bridge "internal static bool IsRrbTreeLeaf(object? value)"))
    (is (str/includes? bridge "PClassInfoEquals<T>"))
    (is (str/includes? bridge "PClassInfoAsObject<T>"))
    (is (str/includes? bridge "MapOfEntriesLoose<K, V>"))
    (is (str/includes? bridge "RrbTree<TOuter>.MutRrbt<T>"))
    (is (str/includes? bridge "target.Append(value)"))
    (is (not (str/includes? bridge "target.Add(value)")))
    (is (str/includes? project-rules
                       "PklRuntimeBridge.PClassInfoEquals(this, obj)"))
    (is (str/includes? bridge "DripSharp.Brine.Util.Paguro"))))

(deftest java-uri-component-mappings-retain-decoded-and-raw-api-pairs
  (let [body-rules (source "java_library")
        mapping-rules (source "java_library_mappings")
        runtime (java-compat-runtime)]
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
  (let [runtime (java-compat-runtime)
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
        runtime (java-compat-runtime)
        util-runtime (slurp "runtime/DripSharp.JavaCompat.Java.Util.cs")]
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
                      (subs util-runtime
                            (.indexOf util-runtime "internal sealed class JavaMapEntry")
                            (.indexOf util-runtime
                                      "internal static partial class JavaCompat")))))))
