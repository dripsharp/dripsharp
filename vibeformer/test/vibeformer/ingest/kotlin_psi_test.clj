(ns vibeformer.ingest.kotlin-psi-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [datomic.local :as dl]
            [vibeformer.datomic.schema :as schema]
            [vibeformer.ingest.java-spoon :as java-spoon]
            [vibeformer.ingest.kotlin-psi :as kotlin-psi]
            [vibeformer.ingest.source :as source]
            [vibeformer.inventory :as inventory])
  (:import (java.nio.charset StandardCharsets)
           (java.nio.file Files Path Paths)
           (java.util UUID)))

(def kotlin-fixture
  "package com.acme.parser

import java.util.Locale

object Fixture {
  val defaultName: String? = \"world\"
}

class KotlinGreeter(private val initialName: String?) {
  companion object {
    val fallback: String = \"empty\"
  }

  val name: String? = initialName
  val salutation: String = \"Hello\"

  fun greeting(locale: Locale): String {
    val normalized: String? = name?.uppercase(locale)
    return \"$salutation, ${normalized?.trim() ?: Fixture.defaultName}\"
  }
}

fun topLevelMessage(value: String?): String = value?.trim() ?: Fixture.fallback
")

(def kotlin-semantic-fixture
  "package com.acme.semantic

import java.util.Locale

class LocalValue

class LocalHash {
  override fun hashCode(): Int = 7
}

fun helper(value: String): String = value

fun choose(value: String): String = value
fun choose(value: String?): String = value ?: \"\"

fun usesAction(action: Action): Action = action

fun gradleDslCalls(): Any {
  id(\"org.example.plugin\")
  named(\"publish\")
  addConfiguredDependencyTo(\"implementation\", \"org.example:lib:1\")
  addDependencyTo(\"implementation\", \"org.example:lib:1\")
  addExternalModuleDependencyTo(\"api\", \"org.example:lib:1\")
  configure<Action> { }
  getByName(\"main\")
  append(\"suffix\")
  return getByName(\"main\")
}

fun stringify(value: Any): String = value.toString()

fun hashes(value: Any, local: LocalHash): Int = value.hashCode() + local.hashCode()

fun usesHelper(value: String, local: LocalValue, locale: Locale, missing: MissingType): String {
  val localAgain: LocalValue = local
  val helped: String = helper(value)
  val unclear: String = choose(value)
  return MissingApi.format(helped)
}
")

(def kotlin-same-file-helper-a-fixture
  "package com.acme.alpha

class AlphaResult

private fun writePklFile(value: String): AlphaResult = AlphaResult()

fun useAlpha(): AlphaResult = writePklFile(\"alpha\")
")

(def kotlin-same-file-helper-b-fixture
  "package com.acme.beta

class BetaResult

private fun writePklFile(value: String): BetaResult = BetaResult()

fun useBeta(): BetaResult = writePklFile(\"beta\")
")

(def kotlin-inherited-helper-fixture
  "package com.acme.inherited

class PklFile

open class AbstractTest {
  protected fun writeFile(fileName: String, contents: String): PklFile = PklFile()
}

open class OtherTest {
  protected fun writeFile(fileName: String, contents: String): PklFile = PklFile()
}

class GradleTest : AbstractTest() {
  fun writes(): PklFile = writeFile(\"input.pkl\", \"x\")
}
")

(def kotlin-extension-helper-fixture
  "package com.acme.extensions

import java.nio.file.Path

class Box

fun Path.writeFile(fileName: String, contents: String): Path = this

fun Path.writeEmptyFile(fileName: String): Path = writeFile(fileName, \"\")

fun Box.writeFile(fileName: String, contents: String): Box = this

fun writes(tempDir: Path, box: Box): Path {
  val certs = tempDir.writeFile(\"random.pem\", \"RANDOM\")
  val projectDir = tempDir.resolve(\"project\")
  val project = projectDir.writeFile(\"PklProject\", \"name = \\\"project\\\"\")
  val empty = tempDir.writeEmptyFile(\"empty.pem\")
  val boxed = box.writeFile(\"box.txt\", \"BOX\")
  return project
}

class EvaluatorLike {
  private val tempDir: Path = Path.of(\"tmp\")

  fun writesFromProperty(): Path = tempDir.writeFile(\"random.pem\", \"RANDOM\")
}
")

(def kotlin-static-get-fixture
  "package com.acme.staticget

import java.net.URI

fun staticGets(): Any {
  val className = ClassName.get(String::class.java)
  val typeName = ParameterizedTypeName.get(ClassName.get(\"pkg\", \"Box\"), ClassName.get(String::class.java))
  val info = PClassInfo.get(\"module\", \"Person\", URI(\"repl:test\"))
  val identifier = Identifier.get(\"name\")
  return className
}
")

(def kotlin-constructor-call-fixture
  "package com.acme.ctors

import java.io.StringWriter

data class KotlinOptions(val name: String)

fun constructorCalls(): Any {
  val kotlinOptions = KotlinOptions(\"fixture\")
  val javaOptions = JavaOptions()
  val writer = StringWriter()
  val missing = MissingOptions()
  return kotlinOptions
}
")

(def java-constructor-call-fixture
  "package com.acme.ctors;

public final class JavaOptions {
}
")

(def kotlin-builder-build-fixture
  "package com.acme.builder

import java.net.URI
import java.net.http.HttpRequest

class Product {
  class Builder {
    fun option(): Builder = this
    fun build(): Product = Product()
  }

  companion object {
    fun builder(): Builder = Builder()
  }
}

class ProductBuilder {
  fun option(): ProductBuilder = this
  fun build(): Product = Product()
}

class Runner {
  fun build(): Any = Any()
}

fun builderBuilds(runner: Runner): Any {
  val chained = Product.builder().option().build()
  val explicit: ProductBuilder = ProductBuilder()
  val explicitProduct = explicit.build()
  val request = HttpRequest.newBuilder(URI(\"https://example.com\")).header(\"x\", \"y\").build()
  val unknown = runner.build()
  return chained
}
")

(def kotlin-matches-fixture
  "package com.acme.matches

import java.net.URI

class Rule(val pattern: String) {
  fun matches(uri: URI): Boolean = true
}

fun matchCalls(value: Any): Boolean {
  val stringMatches = \"abc\".matches(Regex(\"a.*\"))
  val regex = Regex(\"b.*\")
  val regexMatches = regex.matches(\"bbb\")
  val rule = Rule(\"*\")
  val ruleMatches = rule.matches(URI(\"https://example.com\"))
  val unknown = value.matches(\"anything\")
  return stringMatches && regexMatches && ruleMatches
}
")

(def kotlin-api-call-fixture
  "package com.acme.api

import java.net.URI
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import org.gradle.api.provider.ListProperty
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageBufferPacker
import com.github.tomakehurst.wiremock.client.WireMock.*
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.walk

data class ApiDoc(val moduleName: String, val dependencies: List<String>, val outputBytes: ByteArray)
data class PObject(val classInfo: String)

class JavaVersionRange {
  companion object {
    fun inclusive(floor: Int, ceiling: Int): JavaVersionRange = JavaVersionRange()
  }
}

val List<PObject>.isUnlisted: Boolean
  get() = any { it.classInfo == \"Unlisted\" }

interface CommandLine {
  fun word(): String
  fun wordCursor(): Int
}

interface ConfigurableFileCollection {
  fun filter(predicate: (Path) -> Boolean): ConfigurableFileCollection
}

interface TaskResult {
  val output: String
}

fun Path.listFilesRecursively(): List<Path> = listOf(this)

fun String.stripFilesAndLines(): String = this

fun apiCalls(path: Path): URI {
  val text = \"\"\"
    value
  \"\"\".trimIndent()
  val marginText = \"\"\"
    |value
  \"\"\".trimMargin()
  val values = listOf(text, marginText)
  assertThat(values.size).isEqualTo(1)
  return URI(\"file:///tmp\").resolve(path.toUri())
}

fun values(root: Path): List<URI> {
  return listOf(root.resolve(\"child\").toUri(), URI(\"https://example.com\"))
}

fun hasText(values: List<String>, text: String): Boolean {
  val normalized = text.replace(\"value\", \"other\").trim()
  val copied = values.toList()
  val filtered = values.filter { it.length > 0 }
  val joined = values.joinToString(\",\")
  val lambdaFiltered = values.filter { it.isNotEmpty() }
  val lambdaJoined = values.joinToString(\",\") { it.substring(0, 1) }
  val prefix = text.substring(0, 1)
  val chainedBytes = text.substring(0, 1).toByteArray()
  assertThat(values).contains(text)
  return values.contains(text) &&
    text.contains(\"value\") &&
    text.isNotEmpty() &&
    values.isNotEmpty() &&
    normalized.startsWith(\"other\") &&
    copied.any { it.length > 0 } &&
    joined.isNotEmpty() &&
    lambdaFiltered.isNotEmpty() &&
    lambdaJoined.isNotEmpty() &&
    prefix.isNotEmpty() &&
    chainedBytes.isNotEmpty() &&
    !values.isEmpty()
}

fun stdlibValues(values: List<String>): Boolean {
  val empty = emptyList<String>()
  val built = buildList<String> { add(\"built\") }
  val names = mutableSetOf(\"name\")
  val counts = mutableMapOf(\"one\" to 1)
  val bytes = byteArrayOf(1.toByte(), 2.toByte())
  val encoded = \"encoded\"
  return values.first().endsWith(\"e\") &&
    names.isNotEmpty() &&
    counts.isNotEmpty() &&
    empty.isEmpty() &&
    encoded.toByteArray().isNotEmpty() &&
    !bytes.isEmpty()
}

fun pathFacts(path: Path): Boolean {
  assertThat(path).exists()
  val dir = path.createParentDirectories().createDirectories()
  return path.readText().isNotEmpty() && path.exists() && dir.exists()
}

fun propertyAndPlatformFacts(doc: ApiDoc, root: Path): Boolean {
  val module = doc.moduleName.substring(0, 1)
  val deps = doc.dependencies.filter { it.toString().isNotEmpty() }.toList()
  val dependencyText = doc.dependencies.joinToString(\",\")
  val dotted = doc.moduleName.split(\".\").map { it.toString() }.joinToString(\".\")
  val nonBlankLines = doc.moduleName.lines().filterNot { it.toString().isEmpty() }.joinToString(\"\\n\")
  val streamList = Files.walk(root).filter { it.toString().isNotEmpty() }.toList()
  val regexMatches = Regex(\"[a-z]+\").findAll(doc.moduleName).toList()
  val walkedFiles = root.toFile().walk().map { it.toString() }.toList()
  val output = ByteArrayOutputStream()
  val outputBytes = output.toByteArray()
  return doc.dependencies.any { it.toString().isNotEmpty() } &&
    doc.outputBytes.isNotEmpty() &&
    module.isNotEmpty() &&
    deps.isNotEmpty() &&
    dependencyText.isNotEmpty() &&
    dotted.isNotEmpty() &&
    nonBlankLines.isNotEmpty() &&
    streamList.isNotEmpty() &&
    regexMatches.isNotEmpty() &&
    walkedFiles.dropLast(1).any { it.toString().isNotEmpty() } &&
    outputBytes.isNotEmpty()
}

fun inferredNameReceivers(): Boolean {
  val responses = loadResponses()
  val packageSelectors = loadSelectors()
  val normalizedPath = loadPath()
  val output = loadOutput()
  val payloadBytes = loadPayloadBytes()
  return responses.joinToString(\",\").isNotEmpty() &&
    packageSelectors.any { it.toString().isNotEmpty() } &&
    normalizedPath.substring(0, 1).isNotEmpty() &&
    output.toByteArray().isNotEmpty() &&
    payloadBytes.isNotEmpty()
}

fun interopReceiverFacts(root: Path, jvmArgs: ListProperty<String>): Boolean {
  val listed = Files.list(root).filter { it.toString().isNotEmpty() }.toList()
  val collected = Files.walk(root).collect(Collectors.toList())
  val props = System.getProperties().filter { it.key.toString().isNotEmpty() }
  val msgBytes = MessagePack.newDefaultBufferPacker().apply { packInt(1) }.toByteArray()
  val streamBytes = ByteArrayOutputStream().apply { write(1) }.toByteArray()
  val packerThread: ThreadLocal<MessageBufferPacker> =
    ThreadLocal.withInitial { MessagePack.newDefaultBufferPacker() }
  val packer = packerThread.get()
  val threadBytes = packer.toByteArray()
  val range = JavaVersionRange.inclusive(8, 21).toList()
  val argsText = jvmArgs.get().joinToString(\" \")
  val options = loadOptions()
  val deltas = loadDeltas()
  val packageDatas = loadPackageDatas()
  val walked = root.walk().map { it.toString() }.toList()
  val generated = generateSequence(\"first\") { null }.mapNotNull { it }.toList()
  val lines = \"a\\nb\".lineSequence().joinToString(\"|\")
  val combined = (listOf(\"a\") + listOf(\"b\")).joinToString(\"\")
  val maybePath = \"linux/x64\".substring(0, 5).takeIf { it.isNotEmpty() }
  val noProxy = listOf(\"localhost\")
  noProxy.let { System.setProperty(\"http.nonProxyHosts\", it.joinToString(\"|\")) }
  return listed.isNotEmpty() &&
    props.isNotEmpty() &&
    msgBytes.isNotEmpty() &&
    streamBytes.isNotEmpty() &&
    threadBytes.isNotEmpty() &&
    range.isNotEmpty() &&
    argsText.isNotEmpty() &&
    options.toList().isNotEmpty() &&
    deltas.joinToString(\"\\n\").isNotEmpty() &&
    packageDatas.distinctBy { it.toString() }.toList().isNotEmpty() &&
    walked.isNotEmpty() &&
    generated.isNotEmpty() &&
    lines.isNotEmpty() &&
    combined.isNotEmpty() &&
    maybePath.isNotEmpty()
}

fun commandLineFacts(commandLine: CommandLine): Boolean {
  return commandLine.word().substring(0, commandLine.wordCursor()).isNotEmpty()
}

fun residualReceiverFacts(
  root: Path,
  classpath: ConfigurableFileCollection,
  packages: Map<String, Collection<String>>,
  taskResult: TaskResult,
): Boolean {
  val files = root.listFilesRecursively().filter { it.toString().isNotEmpty() }
  val pathInput = classpath.filter { it.exists() }
  val formats = arrayOf(\"json\", \"yaml\").joinToString()
  val cleaned = taskResult.output.stripFilesAndLines().lineSequence().joinToString(\"\\n\")
  val props = convert {
    val eq = it.indexOf('=')
    if (eq == -1) it else it.substring(0, eq) + it.substring(eq + 1)
  }
  val docPackages = packages.map { it.value.toList() }
  val current = StringBuilder()
  return files.isNotEmpty() &&
    pathInput.toString().isNotEmpty() &&
    formats.isNotEmpty() &&
    cleaned.isNotEmpty() &&
    props.isNotEmpty() &&
    docPackages.isNotEmpty() &&
    current.isNotEmpty()
}

fun wireMockMatcherFacts(): Any {
  return stubFor(any(anyUrl()).willReturn(aResponse().proxiedFrom(\"https://example.com\")))
}

fun wireMockBuilderChainFacts(): Any {
  stubFor(get(urlEqualTo(\"/foo.pkl\")).withHost(equalTo(\"example.com\")).willReturn(ok(\"foo = 1\")))
  stubFor(get(urlEqualTo(\"/bar.pkl\")).willReturn(permanentRedirect(\"/baz.pkl\")))
  verify(getRequestedFor(urlEqualTo(\"/foo.pkl\")).withHeader(\"X-Foo\", equalTo(\"Foo\")))
  verify(getRequestedFor(urlEqualTo(\"/bar.pkl\")).withHeader(\"X-Bar\", matching(\"bar.*\")).withoutHeader(\"X-Old\"))
  return stubFor(get(anyUrl()).willReturn(ok()))
}

fun assertions() {
  val same = Any()
  assertThat(listOf(1, 2)).hasSize(2).containsExactly(1, 2)
  assertThat(listOf(\"a\")).containsOnly(\"a\")
  assertThat(listOf(\"a\")).isNotEmpty()
  assertThat(null as String?).isNull()
  assertThat(same).isSameAs(same).isNotNull()
  assertThat(1).isLessThan(2)
  assertThat(2).isGreaterThan(1)
  assertThat(\"left\").isNotEqualTo(\"right\").isInstanceOf(String::class.java)
  assertThat(false).isFalse()
  assertThatCode { throw IllegalArgumentException(\"bad\") }
    .hasMessage(\"bad\")
    .hasMessageContaining(\"ba\")
    .hasMessageStartingWith(\"b\")
  assertThatCode { println(\"ok\") }.doesNotThrowAnyException()
  assertTrue(true)
  assertFalse(false)
  assertDoesNotThrow { \"ok\" }
  if (false) fail(\"unreachable\")
}
")

(def kotlin-object-overrides-fixture
  "package com.acme.overrides

import java.net.URI

interface ModuleReader {
  val isLocal: Boolean
  val scheme: String

  fun read(uri: URI): String

  fun listElements(uri: URI): List<String>
}

object FixtureModuleReader : ModuleReader {
  override val isLocal: Boolean = true

  override val scheme: String = \"foo\"

  override fun read(uri: URI): String = \"hello\"

  override fun listElements(uri: URI): List<String> {
    throw NotImplementedError()
  }
}
")

(def java-pseudo-type-fixture
  "package com.acme.semantic;

public final class JavaPseudoTypes {
  public void log(MissingDependency missing) {
    var value = missing.make();
  }
}
")

(defn- with-empty-db [f]
  (let [system (str "vibeformer-kotlin-psi-test-" (UUID/randomUUID))
        db-name (str "facts-" (UUID/randomUUID))
        client (d/client {:server-type :datomic-local
                          :storage-dir :mem
                          :system system})
        created? (atom false)]
    (try
      (is (true? (d/create-database client {:db-name db-name})))
      (reset! created? true)
      (f (d/connect client {:db-name db-name}))
      (finally
        (when @created?
          (dl/release-db {:system system
                          :storage-dir :mem
                          :db-name db-name}))))))

(defn- temp-root []
  (Files/createTempDirectory "vibeformer-kotlin-psi-" (make-array java.nio.file.attribute.FileAttribute 0)))

(defn- write-file! [^Path root relative-path content]
  (let [file (.resolve root relative-path)]
    (Files/createDirectories (.getParent file) (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/writeString file content StandardCharsets/UTF_8 (make-array java.nio.file.OpenOption 0))
    file))

(defn- entity-counts [db]
  {:nodes (ffirst (d/q '[:find (count ?node)
                         :where [?node :node/id]]
                       db))
   :decls (ffirst (d/q '[:find (count ?decl)
                         :where [?decl :decl/id]]
                       db))
   :types (ffirst (d/q '[:find (count ?type)
                         :where [?type :type/id]]
                       db))
   :refs (ffirst (d/q '[:find (count ?ref)
                        :where [?ref :ref/id]]
                      db))
   :features (ffirst (d/q '[:find (count ?feature)
                            :where [?feature :feature/id]]
                          db))})

(deftest bounds-large-kotlin-node-values-for-datomic-storage
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/kotlin/com/acme/large/LargeValue.kt"
            large-text (apply str (repeat 5000 "x"))
            source-text (str "package com.acme.large\n\n"
                             "val huge: String = \"" large-text "\"\n")
            opts {:source/root root
                  :project/id "large-value"
                  :project/name "Large Value"}]
        (write-file! root file-path source-text)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "large-value"})
        (let [db (d/db conn)
              [[value source-hash start-line end-line]]
              (d/q '[:find ?value ?hash ?start-line ?end-line
                     :where
                     [?node :node/kind :kotlin.node/property]
                     [?node :node/name "huge"]
                     [?node :node/value ?value]
                     [?node :node/source-hash ?hash]
                     [?node :node/start-line ?start-line]
                     [?node :node/end-line ?end-line]]
                   db)]
          (is (<= (count value) 1024))
          (is (str/starts-with? value "\"xxxxxxxx"))
          (is (str/includes? value "... [truncated sha256:"))
          (is (re-find #"^sha256:" source-hash))
          (is (= [3 3] [start-line end-line])))))))

(deftest extracts-normalized-kotlin-facts-with-psi
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/kotlin/com/acme/parser/Greeter.kt"
            opts {:source/root root
                  :project/id "fixture"
                  :project/name "Fixture"}]
        (write-file! root file-path kotlin-fixture)
        (source/ingest! conn opts)
        (let [first-run (kotlin-psi/ingest! conn {:project/id "fixture"})
              db (d/db conn)
              counts (entity-counts db)]
          (is (= {:project/id "fixture"
                  :kotlin-files 1}
                 (select-keys first-run [:project/id :kotlin-files])))
          (is (pos? (:transacted-facts first-run)))

          (testing "packages, classes, objects, companion objects, top-level functions, and properties are queryable"
            (is (= #{["com.acme.parser"]}
                   (set (d/q '[:find ?node-name
                               :where
                               [?node :node/file [:file/id "fixture:src/main/kotlin/com/acme/parser/Greeter.kt"]]
                               [?node :node/kind :kotlin.node/package]
                               [?node :node/name ?node-name]]
                             db))))
            (is (set/subset?
                 #{[:kotlin.node/object "Fixture" :decl.kind/object "com.acme.parser.Fixture"]
                   [:kotlin.node/class "KotlinGreeter" :decl.kind/class "com.acme.parser.KotlinGreeter"]
                   [:kotlin.node/companion-object "Companion" :decl.kind/companion-object "com.acme.parser.KotlinGreeter.Companion"]
                   [:kotlin.node/property "name" :decl.kind/property "com.acme.parser.KotlinGreeter.name"]
                   [:kotlin.node/function "greeting" :decl.kind/function "com.acme.parser.KotlinGreeter.greeting(Locale)"]
                   [:kotlin.node/function "topLevelMessage" :decl.kind/function "com.acme.parser.topLevelMessage(String?)"]}
                 (set (d/q '[:find ?node-kind ?node-name ?decl-kind ?decl-qname
                             :where
                             [?node :node/file [:file/id "fixture:src/main/kotlin/com/acme/parser/Greeter.kt"]]
                             [?node :node/kind ?node-kind]
                             [?node :node/name ?node-name]
                             [(contains? #{:kotlin.node/package
                                           :kotlin.node/object
                                           :kotlin.node/class
                                           :kotlin.node/companion-object
                                           :kotlin.node/property
                                           :kotlin.node/function}
                                          ?node-kind)]
                             [?decl :decl/source-node ?node]
                             [?decl :decl/kind ?decl-kind]
                             [?decl :decl/qualified-name ?decl-qname]]
                           db)))))

          (testing "nullable source-language type syntax and type-use refs are queryable"
            (is (= #{["kotlin:String?" "String" true]
                     ["kotlin:String" "String" false]
                     ["kotlin:Locale" "Locale" false]}
                   (set (d/q '[:find ?type-id ?type-name ?nullable?
                               :where
                               [?type :type/lang :lang/kotlin]
                               [?type :type/id ?type-id]
                               [?type :type/name ?type-name]
                               [?type :type/nullable? ?nullable?]
                               [(contains? #{"kotlin:String?" "kotlin:String" "kotlin:Locale"} ?type-id)]]
                             db))))
            (is (set/subset?
                 #{["name" "kotlin:String?"]
                   ["greeting" "kotlin:String"]
                   ["topLevelMessage" "kotlin:String?"]}
                 (set (d/q '[:find ?node-name ?type-id
                             :where
                             [?ref :ref/kind :ref.kind/type-use]
                             [?ref :ref/from-node ?node]
                             [?node :node/name ?node-name]
                             [?ref :ref/to-type ?type]
                             [?type :type/id ?type-id]
                             [(contains? #{"name" "greeting" "topLevelMessage"} ?node-name)]
                             [(contains? #{"kotlin:String?" "kotlin:String"} ?type-id)]]
                           db)))))

          (testing "call expressions, safe calls, Elvis expressions, and features are queryable"
            (is (= #{["uppercase" false]
                     ["trim" false]}
                   (set (d/q '[:find ?name ?resolved?
                               :where
                               [?ref :ref/kind :ref.kind/function-call]
                               [?ref :ref/name ?name]
                               [?ref :ref/resolved? ?resolved?]]
                             db))))
            (is (= #{[:kotlin.feature/class :feature.status/supported]
                     [:kotlin.feature/object :feature.status/supported]
                     [:kotlin.feature/companion-object :feature.status/supported]
                     [:kotlin.feature/top-level-declaration :feature.status/supported]
                     [:kotlin.feature/nullable-type :feature.status/supported]
                     [:kotlin.feature/safe-call :feature.status/supported]
                     [:kotlin.feature/elvis-expression :feature.status/supported]
                     [:kotlin.feature/call-expression :feature.status/supported]}
                   (set (d/q '[:find ?kind ?status
                               :where
                               [?feature :feature/kind ?kind]
                               [?feature :feature/status ?status]
                               [(contains? #{:kotlin.feature/class
                                             :kotlin.feature/object
                                             :kotlin.feature/companion-object
                                             :kotlin.feature/top-level-declaration
                                             :kotlin.feature/nullable-type
                                             :kotlin.feature/safe-call
                                             :kotlin.feature/elvis-expression
                                             :kotlin.feature/call-expression}
                                            ?kind)]]
                             db))))
            (is (= #{[:kotlin.node/safe-call "?."]
                     [:kotlin.node/elvis-expression "?:"]}
                   (set (d/q '[:find ?kind ?name
                               :where
                               [?node :node/kind ?kind]
                               [?node :node/name ?name]
                               [(contains? #{:kotlin.node/safe-call :kotlin.node/elvis-expression} ?kind)]]
                             db)))))

          (testing "source spans are captured from Kotlin PSI offsets"
            (is (= #{[9 1 "KotlinGreeter"]
                     [17 3 "greeting"]}
                   (set (d/q '[:find ?line ?column ?name
                               :where
                               [?node :node/name ?name]
                               [?node :node/start-line ?line]
                               [?node :node/start-column ?column]
                               [(contains? #{"KotlinGreeter" "greeting"} ?name)]]
                             db))))
            (is (every? #(re-find #"^sha256:" %)
                        (map first
                             (d/q '[:find ?hash
                                    :where
                                    [?node :node/lang :lang/kotlin]
                                    [?node :node/source-hash ?hash]]
                                  db)))))

          (testing "unchanged reruns keep logical fact counts stable"
            (kotlin-psi/ingest! conn {:project/id "fixture"})
            (is (= counts (entity-counts (d/db conn))))))))))

(deftest enriches-kotlin-refs-with-conservative-semantic-resolution
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/kotlin/com/acme/semantic/Semantics.kt"
            opts {:source/root root
                  :project/id "semantic"
                  :project/name "Semantic Fixture"}]
        (write-file! root file-path kotlin-semantic-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "semantic"})
        (let [first-run (kotlin-psi/enrich! conn {:project/id "semantic"
                                                  :kotlin/classpath-types {"Locale" "java.util.Locale"
                                                                          "Action" "org.gradle.api.Action"}})
              db (d/db conn)]
          (is (= {:project/id "semantic"}
                 (select-keys first-run [:project/id])))
          (is (pos? (:semantic-refs first-run)))

          (testing "project-local function calls resolve to declaration facts"
            (is (= #{["helper" "helper" "com.acme.semantic.helper(String)" true]
                     ["choose" "choose" "com.acme.semantic.choose(String)" true]}
                   (set (d/q '[:find ?ref-name ?decl-name ?qualified-name
                                      ?resolved?
                               :where
                               [?ref :ref/kind :ref.kind/function-call]
                               [?ref :ref/name ?ref-name]
                               [?ref :ref/resolved? ?resolved?]
                               [?ref :ref/to-decl ?decl]
                               [?decl :decl/name ?decl-name]
                               [?decl :decl/qualified-name ?qualified-name]
                               [(contains? #{"helper" "choose"} ?ref-name)]]
                             db)))))

          (testing "value parameter type facts are queryable for overload resolution"
            (is (contains?
                 (set (d/q '[:find ?parameter-name ?type-id
                             :where
                             [?node :node/kind :kotlin.node/value-parameter]
                             [?node :node/name ?parameter-name]
                             [?ref :ref/from-node ?node]
                             [?ref :ref/kind :ref.kind/type-use]
                             [?ref :ref/to-type ?type]
                             [?type :type/id ?type-id]
                             [(= ?parameter-name "value")]]
                           db))
                 ["value" "kotlin:String"])))

          (testing "source and declared classpath types resolve deliberately"
            (is (= #{["LocalValue" true]
                     ["Locale" true]
                     ["String" true]}
                   (set (d/q '[:find ?type-name ?resolved?
                               :where
                               [?ref :ref/kind :ref.kind/type-use]
                               [?ref :ref/name ?type-name]
                               [?ref :ref/resolved? ?resolved?]
                               [(contains? #{"LocalValue" "Locale" "String"} ?type-name)]]
                             db))))
            (is (= #{["Action" "org.gradle.api.Action" true]}
                   (set (d/q '[:find ?type-name ?type-id ?resolved?
                               :where
                               [?ref :ref/kind :ref.kind/type-use]
                               [?ref :ref/name ?type-name]
                               [?ref :ref/to-type ?type]
                               [?type :type/id ?type-id]
                               [?ref :ref/resolved? ?resolved?]
                               [(= ?type-name "Action")]]
                             db)))))

          (testing "Gradle DSL fallback calls resolve to stable owner and return type facts"
            (let [target-names #{"named"
                                 "addConfiguredDependencyTo"
                                 "addDependencyTo"
                                 "addExternalModuleDependencyTo"
                                 "configure"
                                 "id"
                                 "getByName"
                                 "append"}
                  resolved (set (d/q '[:find ?name ?type-id ?owner-id ?resolved?
                                        :in $ ?target-names
                                        :where
                                        [?ref :ref/kind :ref.kind/function-call]
                                        [?ref :ref/name ?name]
                                        [(contains? ?target-names ?name)]
                                        [?ref :ref/resolved? ?resolved?]
                                        [?ref :ref/to-type ?type]
                                        [?type :type/id ?type-id]
                                        [?ref :ref/owner-type ?owner]
                                        [?owner :type/id ?owner-id]]
                                      db
                                      target-names))
                  unresolved (set (d/q '[:find ?name ?reason
                                          :in $ ?target-names
                                          :where
                                          [?ref :ref/resolved? false]
                                          [?ref :ref/name ?name]
                                          [(contains? ?target-names ?name)]
                                          [?ref :ref/reason ?reason]]
                                        db
                                        target-names))]
              (is (= #{["named"
                        "org.gradle.api.NamedDomainObjectProvider"
                        "org.gradle.api.NamedDomainObjectCollection"
                        true]
                       ["addConfiguredDependencyTo"
                        "org.gradle.api.artifacts.ExternalModuleDependency"
                        "org.gradle.kotlin.dsl.DependencyHandlerScope"
                        true]
                       ["addDependencyTo"
                        "org.gradle.api.artifacts.Dependency"
                        "org.gradle.kotlin.dsl.DependencyHandlerScope"
                        true]
                       ["addExternalModuleDependencyTo"
                        "org.gradle.api.artifacts.ExternalModuleDependency"
                        "org.gradle.kotlin.dsl.DependencyHandlerScope"
                        true]
                       ["configure"
                        "kotlin:Unit"
                        "org.gradle.api.plugins.ExtensionContainer"
                        true]
                       ["id"
                        "org.gradle.plugin.use.PluginDependencySpec"
                        "org.gradle.plugin.use.PluginDependenciesSpec"
                        true]
                       ["getByName"
                        "kotlin:Any"
                        "org.gradle.api.NamedDomainObjectCollection"
                        true]
                       ["append"
                        "java.lang.Appendable"
                        "java.lang.Appendable"
                        true]}
                     resolved))
              (is (empty? unresolved))))

          (testing "universal toString calls resolve to Kotlin String"
            (let [resolved (set (d/q '[:find ?type-id ?owner-id ?resolved?
                                        :where
                                        [?ref :ref/kind :ref.kind/function-call]
                                        [?ref :ref/name "toString"]
                                        [?ref :ref/resolved? ?resolved?]
                                        [?ref :ref/to-type ?type]
                                        [?type :type/id ?type-id]
                                        [?ref :ref/owner-type ?owner]
                                        [?owner :type/id ?owner-id]]
                                      db))
                  unresolved (set (d/q '[:find ?reason
                                          :where
                                          [?ref :ref/resolved? false]
                                          [?ref :ref/name "toString"]
                                          [?ref :ref/reason ?reason]]
                                        db))]
              (is (= #{["kotlin:String" "kotlin:Any" true]}
                     resolved))
              (is (empty? unresolved))))

          (testing "universal hashCode calls resolve while local overrides keep declarations"
            (let [known-resolved (set (d/q '[:find ?type-id ?owner-id ?resolved?
                                             :where
                                             [?ref :ref/kind :ref.kind/function-call]
                                             [?ref :ref/name "hashCode"]
                                             [?ref :ref/resolved? ?resolved?]
                                             [?ref :ref/to-type ?type]
                                             [?type :type/id ?type-id]
                                             [?ref :ref/owner-type ?owner]
                                             [?owner :type/id ?owner-id]
                                             [(missing? $ ?ref :ref/to-decl)]]
                                           db))
                  local-resolved (set (d/q '[:find ?decl-id ?type-id ?owner-id ?resolved?
                                             :where
                                             [?ref :ref/kind :ref.kind/function-call]
                                             [?ref :ref/name "hashCode"]
                                             [?ref :ref/resolved? ?resolved?]
                                             [?ref :ref/to-decl ?decl]
                                             [?decl :decl/id ?decl-id]
                                             [?ref :ref/to-type ?type]
                                             [?type :type/id ?type-id]
                                             [?ref :ref/owner-type ?owner]
                                             [?owner :type/id ?owner-id]]
                                           db))
                  unresolved (set (d/q '[:find ?reason
                                          :where
                                          [?ref :ref/resolved? false]
                                          [?ref :ref/name "hashCode"]
                                          [?ref :ref/reason ?reason]]
                                        db))]
              (is (= #{["kotlin:Int" "kotlin:Any" true]}
                     known-resolved))
              (is (= #{["kotlin:function:com.acme.semantic.LocalHash.hashCode()"
                        "kotlin:Int"
                        "kotlin:com.acme.semantic.LocalHash"
                        true]}
                     local-resolved))
              (is (empty? unresolved))))

          (testing "nullable type-use refs keep nullability after semantic resolution"
            (is (contains?
                 (set (d/q '[:find ?source-name ?type-id ?nullable?
                             :where
                             [?ref :ref/kind :ref.kind/type-use]
                             [?ref :ref/source-name ?source-name]
                             [?ref :ref/to-type ?type]
                             [?type :type/id ?type-id]
                             [?type :type/nullable? ?nullable?]
                             [(= ?source-name "value")]]
                           db))
                 ["value" "kotlin:String?" true])))

          (testing "unresolved refs keep explicit reasons"
            (is (= #{["MissingType" :resolve.reason/missing-classpath]
                     ["format" :resolve.reason/missing-classpath]}
                   (set (d/q '[:find ?name ?reason
                               :where
                               [?ref :ref/resolved? false]
                               [?ref :ref/name ?name]
                               [?ref :ref/reason ?reason]
                               [(contains? #{"MissingType" "format" "choose"} ?name)]]
                             db)))))

          (testing "semantic reruns keep logical fact counts stable"
            (let [after-counts (entity-counts (d/db conn))]
            (kotlin-psi/enrich! conn {:project/id "semantic"
                                      :kotlin/classpath-types {"Locale" "java.util.Locale"
                                                              "Action" "org.gradle.api.Action"}})
              (is (= after-counts (entity-counts (d/db conn)))))))))))

(deftest resolves-same-file-kotlin-helper-function-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            opts {:source/root root
                  :project/id "same-file"
                  :project/name "Same File Helpers"}]
        (write-file! root
                     "src/test/kotlin/com/acme/alpha/Helpers.kt"
                     kotlin-same-file-helper-a-fixture)
        (write-file! root
                     "src/test/kotlin/com/acme/beta/Helpers.kt"
                     kotlin-same-file-helper-b-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "same-file"})
        (kotlin-psi/enrich! conn {:project/id "same-file"})
        (let [db (d/db conn)
              resolved (set (d/q '[:find ?file-id ?decl-id ?type-id ?resolved?
                                    :where
                                    [?ref :ref/kind :ref.kind/function-call]
                                    [?ref :ref/name "writePklFile"]
                                    [?ref :ref/from-node ?node]
                                    [?node :node/file ?file]
                                    [?file :file/id ?file-id]
                                    [?ref :ref/to-decl ?decl]
                                    [?decl :decl/id ?decl-id]
                                    [?ref :ref/to-type ?type]
                                    [?type :type/id ?type-id]
                                    [?ref :ref/resolved? ?resolved?]]
                                  db))
              unresolved (set (d/q '[:find ?file-id ?reason
                                      :where
                                      [?ref :ref/kind :ref.kind/function-call]
                                      [?ref :ref/name "writePklFile"]
                                      [?ref :ref/from-node ?node]
                                      [?node :node/file ?file]
                                      [?file :file/id ?file-id]
                                      [?ref :ref/resolved? false]
                                      [?ref :ref/reason ?reason]]
                                    db))]
          (is (= #{["same-file:src/test/kotlin/com/acme/alpha/Helpers.kt"
                    "kotlin:function:com.acme.alpha.writePklFile(String)"
                    "kotlin:AlphaResult"
                    true]
                   ["same-file:src/test/kotlin/com/acme/beta/Helpers.kt"
                    "kotlin:function:com.acme.beta.writePklFile(String)"
                    "kotlin:BetaResult"
                    true]}
                 resolved))
          (is (empty? unresolved)))))))

(deftest resolves-inherited-kotlin-helper-function-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            opts {:source/root root
                  :project/id "inherited-helper"
                  :project/name "Inherited Helper"}]
        (write-file! root
                     "src/test/kotlin/com/acme/inherited/GradleTest.kt"
                     kotlin-inherited-helper-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "inherited-helper"})
        (kotlin-psi/enrich! conn {:project/id "inherited-helper"})
        (let [db (d/db conn)
              resolved (set (d/q '[:find ?decl-id ?type-id ?owner-id ?resolved?
                                    :where
                                    [?ref :ref/kind :ref.kind/function-call]
                                    [?ref :ref/name "writeFile"]
                                    [?ref :ref/to-decl ?decl]
                                    [?decl :decl/id ?decl-id]
                                    [?ref :ref/to-type ?type]
                                    [?type :type/id ?type-id]
                                    [?ref :ref/owner-type ?owner]
                                    [?owner :type/id ?owner-id]
                                    [?ref :ref/resolved? ?resolved?]]
                                  db))
              unresolved (set (d/q '[:find ?reason
                                      :where
                                      [?ref :ref/kind :ref.kind/function-call]
                                      [?ref :ref/name ?name]
                                      [(contains? #{"writeFile" "writeEmptyFile"} ?name)]
                                      [?ref :ref/resolved? false]
                                      [?ref :ref/reason ?reason]]
                                    db))]
          (is (= #{["kotlin:function:com.acme.inherited.AbstractTest.writeFile(String,String)"
                    "kotlin:PklFile"
                    "kotlin:com.acme.inherited.AbstractTest"
                    true]}
                 resolved))
          (is (empty? unresolved)))))))

(deftest resolves-kotlin-extension-helper-function-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            opts {:source/root root
                  :project/id "extension-helper"
                  :project/name "Extension Helper"}]
        (write-file! root
                     "src/test/kotlin/com/acme/extensions/Extensions.kt"
                     kotlin-extension-helper-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "extension-helper"})
        (kotlin-psi/enrich! conn {:project/id "extension-helper"})
        (let [db (d/db conn)
              extension-decls (set (d/q '[:find ?decl-id ?receiver-type-id
                                           :where
                                           [?decl :decl/name "writeFile"]
                                           [?decl :decl/id ?decl-id]
                                           [?decl :decl/receiver-type ?receiver-type]
                                           [?receiver-type :type/id ?receiver-type-id]]
                                         db))
              resolved (set (d/q '[:find ?decl-id ?type-id ?owner-id ?resolved?
                                    :where
                                    [?ref :ref/kind :ref.kind/function-call]
                                    [?ref :ref/name "writeFile"]
                                    [?ref :ref/to-decl ?decl]
                                    [?decl :decl/id ?decl-id]
                                    [?ref :ref/to-type ?type]
                                    [?type :type/id ?type-id]
                                    [?ref :ref/owner-type ?owner]
                                    [?owner :type/id ?owner-id]
                                    [?ref :ref/resolved? ?resolved?]]
                                  db))
              unresolved (set (d/q '[:find ?reason
                                      :where
                                      [?ref :ref/kind :ref.kind/function-call]
                                      [?ref :ref/name "writeFile"]
                                      [?ref :ref/resolved? false]
                                      [?ref :ref/reason ?reason]]
                                    db))]
          (is (= #{["kotlin:function:com.acme.extensions.Path.writeFile(String,String)" "kotlin:Path"]
                   ["kotlin:function:com.acme.extensions.Box.writeFile(String,String)" "kotlin:Box"]}
                 extension-decls))
          (is (= #{["kotlin:function:com.acme.extensions.Path.writeFile(String,String)"
                    "kotlin:Path"
                    "kotlin:Path"
                    true]
                   ["kotlin:function:com.acme.extensions.Box.writeFile(String,String)"
                    "kotlin:Box"
                    "kotlin:com.acme.extensions.Box"
                    true]}
                 resolved))
          (is (empty? unresolved)))))))

(deftest resolves-known-kotlin-static-get-factories
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            opts {:source/root root
                  :project/id "static-get"
                  :project/name "Static Get"}]
        (write-file! root
                     "src/test/kotlin/com/acme/staticget/StaticGet.kt"
                     kotlin-static-get-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "static-get"})
        (kotlin-psi/enrich! conn {:project/id "static-get"})
        (let [db (d/db conn)
              resolved (set (d/q '[:find ?type-id ?owner-id ?resolved?
                                    :where
                                    [?ref :ref/kind :ref.kind/function-call]
                                    [?ref :ref/name "get"]
                                    [?ref :ref/resolved? ?resolved?]
                                    [?ref :ref/to-type ?type]
                                    [?type :type/id ?type-id]
                                    [?ref :ref/owner-type ?owner]
                                    [?owner :type/id ?owner-id]]
                                  db))
              unresolved (set (d/q '[:find ?reason
                                      :where
                                      [?ref :ref/kind :ref.kind/function-call]
                                      [?ref :ref/name "get"]
                                      [?ref :ref/resolved? false]
                                      [?ref :ref/reason ?reason]]
                                    db))]
          (is (= #{["com.squareup.javapoet.ClassName"
                    "com.squareup.javapoet.ClassName"
                    true]
                   ["com.squareup.javapoet.ParameterizedTypeName"
                    "com.squareup.javapoet.ParameterizedTypeName"
                    true]
                   ["org.pkl.core.PClassInfo"
                    "org.pkl.core.PClassInfo"
                    true]
                   ["org.pkl.core.runtime.Identifier"
                    "org.pkl.core.runtime.Identifier"
                    true]}
                 resolved))
          (is (empty? unresolved)))))))

(deftest resolves-kotlin-constructor-style-type-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            opts {:source/root root
                  :project/id "constructor-calls"
                  :project/name "Constructor Calls"}]
        (write-file! root
                     "src/main/kotlin/com/acme/ctors/Constructors.kt"
                     kotlin-constructor-call-fixture)
        (write-file! root
                     "src/main/java/com/acme/ctors/JavaOptions.java"
                     java-constructor-call-fixture)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "constructor-calls"})
        (kotlin-psi/ingest! conn {:project/id "constructor-calls"})
        (kotlin-psi/enrich! conn {:project/id "constructor-calls"})
        (let [db (d/db conn)
              constructor-names #{"KotlinOptions" "JavaOptions" "StringWriter"}
              resolved (set (d/q '[:find ?name ?type-id ?owner-id ?resolved?
                                    :in $ ?constructor-names
                                    :where
                                    [?ref :ref/kind :ref.kind/function-call]
                                    [?ref :ref/name ?name]
                                    [(contains? ?constructor-names ?name)]
                                    [?ref :ref/resolved? ?resolved?]
                                    [?ref :ref/to-type ?type]
                                    [?type :type/id ?type-id]
                                    [?ref :ref/owner-type ?owner]
                                    [?owner :type/id ?owner-id]]
                                  db
                                  constructor-names))
              unresolved (set (d/q '[:find ?name ?reason
                                      :where
                                      [?ref :ref/kind :ref.kind/function-call]
                                      [?ref :ref/name ?name]
                                      [?ref :ref/resolved? false]
                                      [?ref :ref/reason ?reason]
                                      [(contains? #{"KotlinOptions"
                                                    "JavaOptions"
                                                    "StringWriter"
                                                    "MissingOptions"}
                                                  ?name)]]
                                    db))]
          (is (= #{["KotlinOptions"
                    "kotlin:com.acme.ctors.KotlinOptions"
                    "kotlin:com.acme.ctors.KotlinOptions"
                    true]
                   ["JavaOptions"
                    "com.acme.ctors.JavaOptions"
                    "com.acme.ctors.JavaOptions"
                    true]
                   ["StringWriter"
                    "java.io.StringWriter"
                    "java.io.StringWriter"
                    true]}
                 resolved))
          (is (= #{["MissingOptions" :resolve.reason/missing-classpath]}
                 unresolved)))))))

(deftest resolves-kotlin-builder-chain-build-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            opts {:source/root root
                  :project/id "builder-build"
                  :project/name "Builder Build"}]
        (write-file! root
                     "src/main/kotlin/com/acme/builder/BuilderBuild.kt"
                     kotlin-builder-build-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "builder-build"})
        (kotlin-psi/enrich! conn {:project/id "builder-build"})
        (let [db (d/db conn)
              resolved (set (d/q '[:find ?type-id ?owner-id ?resolved?
                                    :where
                                    [?ref :ref/kind :ref.kind/function-call]
                                    [?ref :ref/name "build"]
                                    [?ref :ref/resolved? ?resolved?]
                                    [?ref :ref/to-type ?type]
                                    [?type :type/id ?type-id]
                                    [?ref :ref/owner-type ?owner]
                                    [?owner :type/id ?owner-id]]
                                  db))
              unresolved (set (d/q '[:find ?reason
                                      :where
                                      [?ref :ref/kind :ref.kind/function-call]
                                      [?ref :ref/name "build"]
                                      [?ref :ref/resolved? false]
                                      [?ref :ref/reason ?reason]]
                                    db))]
          (is (= #{["kotlin:com.acme.builder.Product"
                    "kotlin:com.acme.builder.Product.Builder"
                    true]
                   ["kotlin:com.acme.builder.Product"
                    "kotlin:com.acme.builder.ProductBuilder"
                    true]
                   ["java.net.http.HttpRequest"
                    "java.net.http.HttpRequest.Builder"
                    true]}
                 resolved))
          (is (= #{[:resolve.reason/analysis-api-limitation]}
                 unresolved)))))))

(deftest resolves-kotlin-receiver-matches-calls
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            opts {:source/root root
                  :project/id "matches"
                  :project/name "Matches"}]
        (write-file! root
                     "src/main/kotlin/com/acme/matches/Matches.kt"
                     kotlin-matches-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "matches"})
        (kotlin-psi/enrich! conn {:project/id "matches"})
        (let [db (d/db conn)
              resolved (set (d/q '[:find ?type-id ?owner-id ?resolved?
                                    :where
                                    [?ref :ref/kind :ref.kind/function-call]
                                    [?ref :ref/name "matches"]
                                    [?ref :ref/resolved? ?resolved?]
                                    [?ref :ref/to-type ?type]
                                    [?type :type/id ?type-id]
                                    [?ref :ref/owner-type ?owner]
                                    [?owner :type/id ?owner-id]]
                                  db))
              resolved-decls (set (d/q '[:find ?decl-id ?resolved?
                                          :where
                                          [?ref :ref/kind :ref.kind/function-call]
                                          [?ref :ref/name "matches"]
                                          [?ref :ref/resolved? ?resolved?]
                                          [?ref :ref/to-decl ?decl]
                                          [?decl :decl/id ?decl-id]]
                                        db))
              unresolved (set (d/q '[:find ?reason
                                      :where
                                      [?ref :ref/kind :ref.kind/function-call]
                                      [?ref :ref/name "matches"]
                                      [?ref :ref/resolved? false]
                                      [?ref :ref/reason ?reason]]
                                    db))]
          (is (= #{["kotlin:Boolean" "kotlin:String" true]
                   ["kotlin:Boolean" "kotlin.text.Regex" true]
                   ["kotlin:Boolean" "kotlin:com.acme.matches.Rule" true]}
                 resolved))
          (is (= #{["kotlin:function:com.acme.matches.Rule.matches(URI)" true]}
                 resolved-decls))
          (is (= #{[:resolve.reason/analysis-api-limitation]}
                 unresolved)))))))

(deftest kotlin-enrichment-does-not-rewrite-java-reference-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            java-path "src/main/java/com/acme/semantic/JavaPseudoTypes.java"
            kotlin-path "src/main/kotlin/com/acme/semantic/KotlinMissing.kt"
            kotlin-source "package com.acme.semantic\n\nfun unresolved(missing: MissingType): MissingType = missing\n"
            opts {:source/root root
                  :project/id "mixed"
                  :project/name "Mixed Fixture"}]
        (write-file! root java-path java-pseudo-type-fixture)
        (write-file! root kotlin-path kotlin-source)
        (source/ingest! conn opts)
        (java-spoon/ingest! conn {:project/id "mixed"})
        (kotlin-psi/ingest! conn {:project/id "mixed"})
        (kotlin-psi/enrich! conn {:project/id "mixed"})
        (let [db (d/db conn)
              java-type-refs (set (d/q '[:find ?role ?name ?resolved?
                                          :where
                                          [?file :file/lang :lang/java]
                                          [?node :node/file ?file]
                                          [?ref :ref/from-node ?node]
                                          [?ref :ref/kind :ref.kind/type-use]
                                          [?ref :ref/role ?role]
                                          [?ref :ref/name ?name]
                                          [?ref :ref/resolved? ?resolved?]
                                          [(contains? #{"var" "void"} ?name)]]
                                        db))
              unresolved-details (:unresolved-ref-detail-rankings (inventory/summary db))]
          (is (= #{[:local-type "var" true]
                   [:return-type "void" true]}
                 java-type-refs))
          (is (= [{:lang :lang/kotlin
                   :kind :ref.kind/type-use
                   :name "MissingType"
                   :owner ""
                   :reason :resolve.reason/missing-classpath
                   :count 3
                   :file-count 1}
                  {:lang :lang/java
                   :kind :ref.kind/method-call
                   :name "make"
                   :owner "com.acme.semantic.MissingDependency"
                   :reason :resolve.reason/missing-classpath
                   :count 1
                   :file-count 1}
                  {:lang :lang/java
                   :kind :ref.kind/type-use
                   :name "com.acme.semantic.MissingDependency"
                   :owner ""
                   :reason :resolve.reason/missing-classpath
                   :count 1
                   :file-count 1}]
                 unresolved-details))
          (is (not-any? #(and (= :lang/java (:lang %))
                              (contains? #{"var" "void"} (:name %)))
                        unresolved-details)))))))

(deftest resolves-known-kotlin-api-call-seeds
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/kotlin/com/acme/api/ApiCalls.kt"
            opts {:source/root root
                  :project/id "api-calls"
                  :project/name "API Calls"}]
        (write-file! root file-path kotlin-api-call-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "api-calls"})
        (let [first-run (kotlin-psi/enrich! conn {:project/id "api-calls"})
              db (d/db conn)
              target-names #{"trimIndent"
                             "trimMargin"
                             "listOf"
                             "assertThat"
                             "assertThatCode"
                             "assertDoesNotThrow"
                             "assertFalse"
                             "assertTrue"
                             "byteArrayOf"
                             "buildList"
                             "any"
                             "anyUrl"
                             "aResponse"
                             "equalTo"
                             "containsExactly"
                             "containsOnly"
                             "createDirectories"
                             "createParentDirectories"
                             "doesNotThrowAnyException"
                             "emptyList"
                             "endsWith"
                             "exists"
                             "fail"
                             "first"
                             "get"
                             "getRequestedFor"
                             "hasMessage"
                             "hasMessageContaining"
                             "hasMessageStartingWith"
                             "hasSize"
                             "isEqualTo"
                             "isFalse"
                             "isGreaterThan"
                             "isEmpty"
                             "isInstanceOf"
                             "isLessThan"
                             "isNotEmpty"
                             "isNotEqualTo"
                             "isNotNull"
                             "isNull"
                             "isSameAs"
                             "filter"
                             "joinToString"
                             "matching"
                             "proxiedFrom"
                             "mutableMapOf"
                             "mutableSetOf"
                             "readText"
                             "replace"
                             "URI"
                             "resolve"
                             "startsWith"
                             "substring"
                             "toByte"
                             "toByteArray"
                             "toList"
                             "toUri"
                             "trim"
                             "stubFor"
                             "urlEqualTo"
                             "verify"
                             "withHeader"
                             "withHost"
                             "withoutHeader"
                             "willReturn"
                             "contains"}
              resolved-calls (set (d/q '[:find ?name ?type-id ?owner-id ?resolved?
                                          :in $ ?target-names
                                          :where
                                          [?ref :ref/kind :ref.kind/function-call]
                                          [?ref :ref/name ?name]
                                          [(contains? ?target-names ?name)]
                                          [?ref :ref/resolved? ?resolved?]
                                          [?ref :ref/to-type ?type]
                                          [?type :type/id ?type-id]
                                          [?ref :ref/owner-type ?owner]
                                          [?owner :type/id ?owner-id]]
                                        db
                                        target-names))
              unresolved-targets (d/q '[:find ?name ?reason
                                        :in $ ?target-names
                                        :where
                                        [?ref :ref/resolved? false]
                                        [?ref :ref/name ?name]
                                        [(contains? ?target-names ?name)]
                                        [?ref :ref/reason ?reason]]
                                      db
                                      target-names)
              ranked-targets (filter #(contains? target-names (:name %))
                                     (:unresolved-api-call-rankings (inventory/summary db)))]
          (is (pos? (:type-stubs first-run)))
          (testing "generic Kotlin type-use facts keep ordered type arguments"
            (is (= #{["kotlin:List<kotlin:URI>" "List" 0 "kotlin:URI" "URI"]}
                   (set (d/q '[:find ?type-id ?type-name ?ordinal ?arg-id ?arg-name
                               :where
                               [?type :type/id ?type-id]
                               [?type :type/name ?type-name]
                               [?type :type/args ?arg]
                               [?arg :type.arg/ordinal ?ordinal]
                               [?arg :type.arg/type ?arg-type]
                               [?arg-type :type/id ?arg-id]
                               [?arg-type :type/name ?arg-name]
                               [(= ?type-id "kotlin:List<kotlin:URI>")]]
                             db)))))
          (testing "generic root collection types resolve through Kotlin stdlib aliases"
            (is (contains?
                 (set (d/q '[:find ?name ?type-id ?resolved?
                             :where
                             [?ref :ref/kind :ref.kind/type-use]
                             [?ref :ref/name ?name]
                             [?ref :ref/to-type ?type]
                             [?type :type/id ?type-id]
                             [?ref :ref/resolved? ?resolved?]
                             [(= ?name "List")]]
                           db))
                 ["List" "kotlin.collections.List" true])))
          (testing "function body shape is queryable for later deterministic emission"
            (let [body-values (set (d/q '[:find ?kind ?name ?value
                                           :where
                                           [?node :node/kind ?kind]
                                           [?node :node/name ?name]
                                           [?node :node/value ?value]
                                           [(contains? #{:kotlin.node/local-property
                                                         :kotlin.node/return}
                                                        ?kind)]]
                                         db))
                  expression-edges (set (d/q '[:find ?parent-kind ?role ?child-kind ?child-value
                                                :where
                                                [?child :node/parent ?parent]
                                                [?parent :node/kind ?parent-kind]
                                                [?child :node/role ?role]
                                                [?child :node/kind ?child-kind]
                                                [?child :node/value ?child-value]
                                                [(contains? #{:kotlin.node/local-property
                                                              :kotlin.node/return
                                                              :kotlin.node/elvis-expression
                                                              :kotlin.node/call-expression}
                                                             ?parent-kind)]]
                                              db))
                  legacy-call-children (set (d/q '[:find ?call-name ?child-kind ?child-value
                                                    :where
                                                    [?call :node/kind :kotlin.node/call-expression]
                                                    [?call :node/name ?call-name]
                                                    [?child :node/parent ?call]
                                                    [?child :node/kind ?child-kind]
                                                    [?child :node/value ?child-value]
                                                    [(contains? #{"resolve" "URI"} ?call-name)]
                                                    [(contains? #{:kotlin.node/call-receiver
                                                                  :kotlin.node/call-argument}
                                                                 ?child-kind)]]
                                                  db))]
              (is (contains? (set (map (juxt first second) body-values))
                             [:kotlin.node/local-property "text"]))
              (is (contains? body-values
                             [:kotlin.node/return "return" "URI(\"file:///tmp\").resolve(path.toUri())"]))
              (is (some #(and (= :kotlin.node/local-property (first %))
                              (str/includes? (nth % 2) ".trimIndent()"))
                        body-values))
              (is (some #(and (= :kotlin.node/local-property (first %))
                              (str/includes? (nth % 2) ".trimMargin()"))
                        body-values))
              (is (set/subset?
                   #{[:kotlin.node/local-property :initializer :kotlin.node/qualified-expression "\"\"\"\n    value\n  \"\"\".trimIndent()"]
                     [:kotlin.node/local-property :initializer :kotlin.node/qualified-expression "\"\"\"\n    |value\n  \"\"\".trimMargin()"]
                     [:kotlin.node/local-property :initializer :kotlin.node/call-expression "listOf(text, marginText)"]
                     [:kotlin.node/return :return-expression :kotlin.node/qualified-expression "URI(\"file:///tmp\").resolve(path.toUri())"]
                     [:kotlin.node/return :return-expression :kotlin.node/call-expression "listOf(root.resolve(\"child\").toUri(), URI(\"https://example.com\"))"]
                     [:kotlin.node/call-expression :argument :kotlin.node/qualified-expression "root.resolve(\"child\").toUri()"]}
                   expression-edges))
              (is (set/subset?
                   #{["resolve" :kotlin.node/call-receiver "URI(\"file:///tmp\")"]
                     ["resolve" :kotlin.node/call-argument "path.toUri()"]
                     ["URI" :kotlin.node/call-argument "\"file:///tmp\""]}
                   legacy-call-children))))
          (is (= #{["trimIndent" "kotlin:String" "kotlin.text.StringsKt" true]
                   ["trimMargin" "kotlin:String" "kotlin.text.StringsKt" true]
                   ["listOf" "kotlin.collections.List" "kotlin.collections.CollectionsKt" true]
                   ["assertThat" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.Assertions" true]
                   ["assertThatCode" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.Assertions" true]
                   ["assertDoesNotThrow" "kotlin:Any" "org.junit.jupiter.api.Assertions" true]
                   ["assertFalse" "kotlin:Unit" "org.junit.jupiter.api.Assertions" true]
                   ["assertTrue" "kotlin:Unit" "org.junit.jupiter.api.Assertions" true]
                   ["aResponse" "com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder" "com.github.tomakehurst.wiremock.client.WireMock" true]
                   ["byteArrayOf" "kotlin:ByteArray" "kotlin.collections.ArraysKt" true]
                   ["buildList" "kotlin.collections.List" "kotlin.collections.CollectionsKt" true]
                   ["any" "kotlin:Boolean" "kotlin.collections.List" true]
                   ["any" "com.github.tomakehurst.wiremock.client.MappingBuilder" "com.github.tomakehurst.wiremock.client.WireMock" true]
                   ["any" "kotlin:Boolean" "kotlin:List<kotlin:PObject>" true]
                   ["anyUrl" "com.github.tomakehurst.wiremock.matching.UrlPattern" "com.github.tomakehurst.wiremock.client.WireMock" true]
                   ["equalTo" "com.github.tomakehurst.wiremock.matching.StringValuePattern" "com.github.tomakehurst.wiremock.client.WireMock" true]
                   ["containsExactly" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["containsOnly" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["createDirectories" "java.nio.file.Path" "java.nio.file.Path" true]
                   ["createParentDirectories" "java.nio.file.Path" "java.nio.file.Path" true]
                   ["doesNotThrowAnyException" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["emptyList" "kotlin.collections.List" "kotlin.collections.CollectionsKt" true]
                   ["endsWith" "kotlin:Boolean" "kotlin:String" true]
                   ["exists" "kotlin:Boolean" "java.nio.file.Path" true]
                   ["exists" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["fail" "kotlin:Nothing" "org.junit.jupiter.api.Assertions" true]
                   ["first" "kotlin:Any" "kotlin.collections.Iterable" true]
                   ["get" "com.github.tomakehurst.wiremock.client.MappingBuilder" "com.github.tomakehurst.wiremock.client.WireMock" true]
                   ["get" "kotlin.collections.List" "kotlin:ListProperty<kotlin:String>" true]
                   ["get" "kotlin:MessageBufferPacker" "kotlin:ThreadLocal<kotlin:MessageBufferPacker>" true]
                   ["getRequestedFor" "com.github.tomakehurst.wiremock.client.RequestPatternBuilder" "com.github.tomakehurst.wiremock.client.WireMock" true]
                   ["hasMessage" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["hasMessageContaining" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["hasMessageStartingWith" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["hasSize" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["isEqualTo" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["isFalse" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["isGreaterThan" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["isEmpty" "kotlin:Boolean" "kotlin.collections.Collection" true]
                   ["isInstanceOf" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["isLessThan" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["isNotEmpty" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["isNotEmpty" "kotlin:Boolean" "kotlin.collections.List" true]
                   ["isNotEmpty" "kotlin:Boolean" "kotlin:List<kotlin:String>" true]
                   ["isNotEmpty" "kotlin:Boolean" "kotlin.collections.MutableMap" true]
                   ["isNotEmpty" "kotlin:Boolean" "kotlin.collections.MutableSet" true]
                   ["isNotEmpty" "kotlin:Boolean" "kotlin:ByteArray" true]
                   ["isNotEmpty" "kotlin:Boolean" "kotlin:String" true]
                   ["isNotEqualTo" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["isNotNull" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["isNull" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["isSameAs" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["filter" "kotlin.collections.List" "kotlin.collections.List" true]
                   ["filter" "kotlin.collections.List" "kotlin:List<kotlin:String>" true]
                   ["filter" "kotlin.collections.List" "kotlin:ConfigurableFileCollection" true]
                   ["filter" "kotlin.collections.List" "kotlin.collections.Map" true]
                   ["filter" "java.util.stream.Stream" "java.util.stream.Stream" true]
                   ["joinToString" "kotlin:String" "kotlin:Array" true]
                   ["joinToString" "kotlin:String" "kotlin.collections.List" true]
                   ["joinToString" "kotlin:String" "kotlin:List<kotlin:String>" true]
                   ["joinToString" "kotlin:String" "kotlin.sequences.Sequence" true]
                   ["matching" "com.github.tomakehurst.wiremock.matching.StringValuePattern" "com.github.tomakehurst.wiremock.client.WireMock" true]
                   ["mutableMapOf" "kotlin.collections.MutableMap" "kotlin.collections.MapsKt" true]
                   ["mutableSetOf" "kotlin.collections.MutableSet" "kotlin.collections.SetsKt" true]
                   ["proxiedFrom" "com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder" "com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder" true]
                   ["readText" "kotlin:String" "java.nio.file.Path" true]
                   ["replace" "kotlin:String" "kotlin:String" true]
                   ["stubFor" "com.github.tomakehurst.wiremock.stubbing.StubMapping" "com.github.tomakehurst.wiremock.client.WireMock" true]
                   ["urlEqualTo" "com.github.tomakehurst.wiremock.matching.UrlPattern" "com.github.tomakehurst.wiremock.client.WireMock" true]
                   ["URI" "java.net.URI" "java.net.URI" true]
                   ["verify" "kotlin:Unit" "com.github.tomakehurst.wiremock.client.WireMock" true]
                   ["resolve" "java.nio.file.Path" "java.nio.file.Path" true]
                   ["startsWith" "kotlin:Boolean" "kotlin:String" true]
                   ["substring" "kotlin:String" "kotlin:String" true]
                   ["toByte" "kotlin:Byte" "kotlin:Number" true]
                   ["toByteArray" "kotlin:ByteArray" "java.io.ByteArrayOutputStream" true]
                   ["toByteArray" "kotlin:ByteArray" "kotlin:MessageBufferPacker" true]
                   ["toByteArray" "kotlin:ByteArray" "org.msgpack.core.MessageBufferPacker" true]
                   ["toByteArray" "kotlin:ByteArray" "kotlin:String" true]
                   ["toList" "java.util.stream.Collector" "java.util.stream.Collectors" true]
                   ["toList" "kotlin.collections.List" "kotlin:Collection<kotlin:String>" true]
                   ["toList" "kotlin.collections.List" "kotlin:com.acme.api.JavaVersionRange" true]
                   ["toList" "kotlin.collections.List" "kotlin.sequences.Sequence" true]
                   ["toList" "kotlin.collections.List" "java.util.stream.Stream" true]
                   ["toList" "kotlin.collections.List" "kotlin.collections.List" true]
                   ["toList" "kotlin.collections.List" "kotlin:List<kotlin:String>" true]
                   ["toUri" "java.net.URI" "java.nio.file.Path" true]
                   ["trim" "kotlin:String" "kotlin:String" true]
                   ["withHeader" "com.github.tomakehurst.wiremock.client.RequestPatternBuilder" "com.github.tomakehurst.wiremock.client.RequestPatternBuilder" true]
                   ["withHost" "com.github.tomakehurst.wiremock.client.MappingBuilder" "com.github.tomakehurst.wiremock.client.MappingBuilder" true]
                   ["withoutHeader" "com.github.tomakehurst.wiremock.client.RequestPatternBuilder" "com.github.tomakehurst.wiremock.client.RequestPatternBuilder" true]
                   ["willReturn" "com.github.tomakehurst.wiremock.client.MappingBuilder" "com.github.tomakehurst.wiremock.client.MappingBuilder" true]
                   ["contains" "org.assertj.core.api.AbstractAssert" "org.assertj.core.api.AbstractAssert" true]
                   ["contains" "kotlin:Boolean" "kotlin:List<kotlin:String>" true]
                   ["contains" "kotlin:Boolean" "kotlin:String" true]}
                 resolved-calls))
          (is (empty? unresolved-targets))
          (is (empty? ranked-targets)))))))

(deftest records-analysis-api-prototype-setup-for-committed-kotlin-sample
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [sample-source (.normalize (.toAbsolutePath (Paths/get "sample-projects/kotlin-api-calls/source"
                                                                   (make-array String 0))))
            project-id "kotlin-api-calls-analysis-api"]
        (is (Files/isDirectory sample-source (make-array java.nio.file.LinkOption 0)))
        (source/ingest! conn {:source/root sample-source
                              :project/id project-id
                              :project/name "Kotlin API Calls Analysis API Prototype"})
        (kotlin-psi/ingest! conn {:project/id project-id})
        (let [first-run (kotlin-psi/enrich! conn {:project/id project-id
                                                  :kotlin/analysis-api? true})
              db (d/db conn)]
          (testing "the prototype stores only stable setup/pass/diagnostic data"
            (is (= :analysis-api.prototype/unavailable
                   (:analysis-api/status first-run)))
            (is (= {:project/id project-id
                    :analysis-api/session-class "org.jetbrains.kotlin.analysis.api.KaSession"
                    :analysis-api/available? false
                    :analysis-api/status :analysis-api.status/unavailable
                    :analysis-api/reason :analysis-api.reason/classes-not-on-classpath
                    :analysis-api/source-files 1}
                   (-> (:analysis-api/setup first-run)
                       (select-keys [:project/id
                                     :analysis-api/session-class
                                     :analysis-api/available?
                                     :analysis-api/status
                                     :analysis-api/reason])
                       (assoc :analysis-api/source-files
                              (get-in first-run [:analysis-api/setup
                                                 :analysis-api/module
                                                 :source/files])))))
            (is (= #{["KOTLIN_ANALYSIS_API_UNAVAILABLE"
                      :diagnostic.severity/warning
                      :diagnostic.mapping/unmapped]}
                   (set (d/q '[:find ?code ?severity ?mapping
                               :where
                               [?diagnostic :diagnostic/code ?code]
                               [?diagnostic :diagnostic/severity ?severity]
                               [?diagnostic :diagnostic/mapping-status ?mapping]
                               [(= ?code "KOTLIN_ANALYSIS_API_UNAVAILABLE")]]
                             db))))
            (is (= #{[:pass.kind/kotlin-analysis-api-prototype
                      :pass.status/skipped
                      "Kotlin Analysis API"]}
                   (set (d/q '[:find ?kind ?status ?compiler
                               :where
                               [?pass :pass/kind ?kind]
                               [?pass :pass/status ?status]
                               [?pass :pass/compiler ?compiler]
                               [(= ?kind :pass.kind/kotlin-analysis-api-prototype)]]
                             db)))))

          (testing "conservative fallback still resolves stable sample refs"
            (is (= #{["trimIndent" "kotlin:String" true]
                     ["listOf" "kotlin.collections.List" true]
                     ["resolve" "java.nio.file.Path" true]
                     ["toUri" "java.net.URI" true]
                     ["contains" "kotlin:Boolean" true]}
                   (set (d/q '[:find ?name ?type-id ?resolved?
                               :where
                               [?ref :ref/kind :ref.kind/function-call]
                               [?ref :ref/name ?name]
                               [(contains? #{"trimIndent" "listOf" "resolve" "toUri" "contains"} ?name)]
                               [?ref :ref/resolved? ?resolved?]
                               [?ref :ref/to-type ?type]
                               [?type :type/id ?type-id]]
                             db))))))))))

(deftest extracts-kotlin-object-interface-override-facts
  (with-empty-db
    (fn [conn]
      (schema/install! conn)
      (let [root (temp-root)
            file-path "src/main/kotlin/com/acme/overrides/ObjectOverrides.kt"
            opts {:source/root root
                  :project/id "object-overrides"
                  :project/name "Object Overrides"}]
        (write-file! root file-path kotlin-object-overrides-fixture)
        (source/ingest! conn opts)
        (kotlin-psi/ingest! conn {:project/id "object-overrides"})
        (kotlin-psi/enrich! conn {:project/id "object-overrides"})
        (let [db (d/db conn)]
          (testing "Kotlin interfaces and object implementation refs are normalized"
            (is (= #{[:decl.kind/interface "com.acme.overrides.ModuleReader"]
                     [:decl.kind/object "com.acme.overrides.FixtureModuleReader"]}
                   (set (d/q '[:find ?kind ?qname
                               :where
                               [?decl :decl/lang :lang/kotlin]
                               [?decl :decl/kind ?kind]
                               [?decl :decl/qualified-name ?qname]
                               [(contains? #{"com.acme.overrides.ModuleReader"
                                             "com.acme.overrides.FixtureModuleReader"}
                                            ?qname)]]
                             db))))
            (is (= #{["FixtureModuleReader" "kotlin:com.acme.overrides.ModuleReader" "ModuleReader"]}
                   (set (d/q '[:find ?node-name ?type-id ?ref-name
                               :where
                               [?node :node/name ?node-name]
                               [?ref :ref/from-node ?node]
                               [?ref :ref/kind :ref.kind/implements]
                               [?ref :ref/name ?ref-name]
                               [?ref :ref/to-type ?type]
                               [?type :type/id ?type-id]]
                             db)))))

          (testing "override modifiers and expression values are queryable"
            (is (= #{"isLocal" "scheme" "read" "listElements"}
                   (set (map first
                             (d/q '[:find ?name
                                    :where
                                    [?decl :decl/modifiers :override]
                                    [?decl :decl/name ?name]]
                                  db)))))
            (is (= #{["isLocal" "true"]
                     ["scheme" "\"foo\""]
                     ["read" "\"hello\""]}
                   (set (d/q '[:find ?name ?value
                               :where
                               [?decl :decl/name ?name]
                               [?decl :decl/source-node ?node]
                               [?node :node/value ?value]
                               [(contains? #{"isLocal" "scheme" "read"} ?name)]]
                             db)))))

          (testing "throw bodies are explicit source nodes for rule coverage"
            (is (= #{["throw" "NotImplementedError()"]}
                   (set (d/q '[:find ?name ?value
                               :where
                               [?node :node/kind :kotlin.node/throw]
                               [?node :node/name ?name]
                               [?node :node/value ?value]]
                             db))))))))))
