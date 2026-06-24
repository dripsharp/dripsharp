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

The project includes `com.datomic/local` so tests and early analysis code can
use the Datomic Client API without a server. The smoke test creates a unique
Datomic Local system/database with `:storage-dir :mem`, transacts a small
file/source-node/declaration fact graph, queries it back, and calls
`datomic.local/release-db` so the in-memory database is disposable and
repeatable in local development or CI.

Datomic Local is Apache 2.0 licensed and available from Maven, so this project
does not need Datomic Pro credentials or a transactor for the embedded smoke
tests. Durable local databases use filesystem storage instead: either pass an
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
  Kotlin PSI classes from ordinary JVM code. The smoke test currently exercises
  PSI parsing only: package names, object/class/function/property declarations,
  type references, nullable type syntax, call expressions, safe calls, and
  text-offset source positions.
* Kotlin Analysis API remains the semantic follow-on. Its `KaSession` model is
  intended for type and symbol resolution, but it requires module/session setup
  and a richer classpath story than the first parser smoke test. Add it when
  the extractor starts resolving symbols beyond syntax.

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
