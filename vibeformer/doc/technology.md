# Technology

## Clojure

Use Clojure as the orchestration and transformation language.

Reasons:

* Excellent for data-oriented programming.
* Convenient JVM interop with Spoon and Kotlin tooling.
* Natural fit for Datomic.
* Good for rule dispatch, recursive tree walking, EDN data, and REPL-driven
  development.

## Datomic

Use Datomic as the analysis and control-plane database.

Datomic should not store the entire raw Spoon or Kotlin PSI object graph.
Instead, it should store normalized, conversion-relevant facts.

Good Datomic facts:

* Files
* Packages/namespaces
* Classes/interfaces/enums/objects
* Methods/functions/constructors
* Fields/properties
* Type refs
* Symbol refs
* Call sites
* Inheritance/interface implementation
* AST-ish source nodes
* Language features
* Unsupported constructs
* Transform rule applications
* Source-to-destination provenance
* Compiler diagnostics
* Pass metadata

Avoid storing every token, punctuation mark, whitespace node, or opaque
serialized compiler object.

## Datomic Local Development and Tests

The project includes `com.datomic/local` so tests and analysis code can use the
Datomic Client API without a server. The Datomic tests create unique Local
systems/databases with `:storage-dir :mem`, transact normalized source,
destination, rule, diagnostic, and inventory facts, query them back, and call
`datomic.local/release-db` so in-memory databases are disposable and repeatable
in local development or CI.

Datomic Local is Apache 2.0 licensed and available from Maven, so this project
does not need Datomic Pro credentials or a transactor for embedded tests.
Durable local databases use filesystem storage instead: either pass an
explicit `:storage-dir` to the local client or configure
`~/.datomic/local.edn`; Datomic stores databases below
`<storage-dir>/<system-name>/<database-name>`, which must be cleaned up manually
if durable test storage is ever introduced.

## Java Frontend: Spoon

Use Spoon as the Java analysis frontend.

Spoon is responsible for parsing Java source and producing a useful Java model.
It should provide enough structure for:

* Classes
* Interfaces
* Enums
* Methods
* Fields
* Modifiers
* Type references
* Method calls
* Constructors
* Imports/packages
* Inheritance
* Annotations
* Statements and expressions
* Source positions

Spoon should be used for Java only.

Current Java frontend status:

* Spoon runs in no-classpath mode for repeatable facts over incomplete source
  roots, then Vibeformer resolves project-local refs and staged dependency
  refs from explicit classpath seeds.
* `research-classpath` derives Java package roots from Gradle dependency
  coordinates and version-catalog library groups. `research-dry-run` passes
  those roots into Java ingestion so external dependency-backed refs can be
  classified as resolved when their qualified names match a seeded package
  root.
* This is not a resolved jar classpath. It improves inventory quality, but
  unresolved Java refs still indicate missing semantic modeling, missing
  classpath data, or source constructs that need explicit facts.
* The Java model currently extracts and emits a focused Pkl-shaped subset:
  declaration structure, common statements, switch expressions, synchronized
  constructs, selected reflection APIs, stream APIs, collection/map APIs,
  nullable annotations, and runtime helper requests. Generated samples are the
  executable proof for that subset.

## Kotlin Frontend: Kotlin PSI + Kotlin Analysis API

Do not treat Kotlin as "Java with nicer syntax." Kotlin needs its own frontend.

Use Kotlin PSI and Kotlin Analysis API to extract Kotlin syntax and semantic
information.

Kotlin-specific constructs must be modeled explicitly, including:

* Nullability
* Safe calls
* Elvis operator
* Data classes
* Sealed classes
* Object declarations
* Companion objects
* Top-level functions
* Extension functions
* Extension properties
* Default arguments
* Named arguments
* Destructuring
* Delegated properties
* Smart casts
* Lambdas
* Inline functions
* Reified type parameters
* Suspend functions/coroutines
* Kotlin collection semantics

KSP is not enough as the main extractor because it does not expose full
expression and statement-level detail.

Initial dependency choice:

* `org.jetbrains.kotlin/kotlin-compiler-embeddable` at `2.2.21` is the first
  dependency because it exposes `KotlinCoreEnvironment`, `KtPsiFactory`, and
  Kotlin PSI classes from ordinary JVM code. The implemented PSI extractor now
  covers package names, object/class/function/property declarations, top-level
  file facades, type references, nullable type syntax, call expressions, safe
  calls, and text-offset/source-span positions.
* Kotlin Analysis API remains the semantic follow-on. Its `KaSession` model is
  intended for type and symbol resolution, but it requires module/session setup
  and a richer classpath story than the current conservative fallback. Add it
  when the extractor starts resolving symbols beyond stable local facts and
  explicit classpath seeds.

Current semantic enrichment constraints:

* `vibeformer.ingest.kotlin-psi/enrich!` is a separate pass after PSI ingestion.
  It updates existing Kotlin `:ref/*` facts idempotently instead of replacing
  syntax extraction.
* The pass resolves project-local function calls when a call name has exactly
  one matching Kotlin function declaration in the ingested project. Ambiguous
  overloads are kept unresolved with `:resolve.reason/analysis-api-limitation`
  until a real `KaSession` can disambiguate by receiver, argument, and smart-cast
  context.
* Source types are resolved from ingested Kotlin class/object declarations.
  Standard Kotlin scalar types are treated as available classpath types.
  Additional dependency types must be provided through `:kotlin/classpath-types`;
  anything not declared or listed remains unresolved with
  `:resolve.reason/missing-classpath`.
* The pass does not cache Analysis API lifetime-owned values. A full Analysis
  API module/session integration must keep `KaSession`, symbols, and types
  inside the analysis block and store only stable ids in Datomic.

Analysis API prototype status:

* `kotlin-compiler-embeddable` 2.2.21 does not put
  `org.jetbrains.kotlin.analysis.api.KaSession` on Vibeformer's runtime
  classpath. The standalone artifact is published separately as
  `org.jetbrains.kotlin:analysis-api-standalone-for-ide` in JetBrains'
  IntelliJ dependencies Maven repository, with build-suffixed versions such as
  `2.2.21-483` and `2.2.21-484`; those versions do not line up exactly with the
  compiler artifact version and may not resolve all transitive artifacts through
  Maven Central.
* `vibeformer.ingest.kotlin-analysis-api` records a stable module/session setup
  descriptor for the attempted Analysis API pass: project id/root, source file
  ids, explicit classpath type seeds, explicit classpath roots, availability of
  the Analysis API session class, and a pass/diagnostic fact when the API is not
  available. The descriptor is plain data and intentionally excludes `KaSession`,
  symbols, types, PSI-backed references, or other lifetime-owned values.
* Enrichment can be invoked with `:kotlin/analysis-api? true`. In the current
  dependency set this records the setup pass and diagnostic, then uses the
  conservative fallback to resolve stable refs for the committed
  `kotlin-api-calls` sample. When the Analysis API dependency is introduced,
  the implementation should replace only the prototype resolution hook while
  preserving the stable Datomic fact contract.
* `clojure -T:build research-classpath` writes
  `target/research-pkl/classpath.edn`, a read-only Gradle/Kotlin input
  manifest for `../research/pkl`. It records included Gradle projects, included
  builds, conventional Kotlin/Java/resource source roots, version-catalog
  library/plugin aliases, project accessor dependencies, direct coordinates,
  and other dependency expressions. This is an interim manifest, not a resolved
  jar classpath; the next Analysis API step should turn the manifest into
  module roots and dependency roots, preferably by adding Gradle-derived
  resolution facts rather than embedding Gradle state in the analyzer.

## C# Output Validation

Use the C# compiler directly through:

```text
dotnet build
```

or:

```text
csc
```

Roslyn is not required in the first implementation.

The compiler provides the necessary oracle:

* Does the generated C# parse?
* Does it type-check?
* Do project references resolve?
* Are overloads valid?
* Are generated helper APIs sufficient?

Compiler diagnostics should be parsed and stored in Datomic.

Current diagnostic status:

* Sample runs can invoke `dotnet build`, parse compiler output, write raw build
  logs and `dotnet-build.edn`, transact diagnostic facts, and write
  `dotnet-diagnostic-facts.edn`.
* Diagnostics that fall inside emitted provenance spans are marked
  `:diagnostic.mapping/mapped` and record the source node, transform rule, and
  source features responsible for the emitted span.
* Diagnostics without a provenance span are marked
  `:diagnostic.mapping/unmapped` and ranked separately so provenance holes are
  visible as pipeline defects.
* Full-Pkl research dry-runs currently skip diagnostics ingestion because C#
  emission and `dotnet build` are skipped in `:facts-only` mode.

Roslyn may be considered later for AST-aware C# post-processing or analyzers,
but it should not be part of the critical path initially.

The core stack is:

```text
Clojure
Datomic
Spoon for Java
Kotlin PSI / Kotlin Analysis API for Kotlin
dotnet build or csc for validation
LLMs as assistants for deterministic rule development
```

Roslyn is not required in the initial version. The C# compiler is sufficient as
the validation oracle. The most important destination-side data is compiler
diagnostics mapped back through source-to-destination provenance into Datomic.

Destination project mapping is also modeled as data. The sample pipeline
transacts destination C# project facts and generates `.csproj` files from those
facts. The full-Pkl dry-run writes `target/research-pkl/destination.edn` with
project, project-reference, package, resource, helper, and target-framework
mapping derived from the Gradle/classpath manifest before any full-project
emission is attempted.
