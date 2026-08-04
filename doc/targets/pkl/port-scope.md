# Port Scope

## Product Target

DripSharp is not trying to port every artifact in `../research/pkl`.
The first product target is a .NET library, not a full replacement for the
JVM distribution.

The target library should provide the Pkl behavior needed by .NET consumers:

* Core Pkl parsing, evaluation, value model, module loading, and runtime
  behavior.
* Idiomatic public .NET APIs that provide the useful Pkl library capabilities
  available to upstream consumers.
* C# code generation for Pkl schemas and APIs.

Pkl is the first product target, not the definition of the translator. The
Java-to-C# pipeline, resolved-symbol mappings, and compatibility layer
should remain reusable by future projects. Pkl evaluation and language
semantics belong in the generated/product implementation rather than in a
generic DripSharp runtime. The generic runtime should contain only facilities
needed to bridge JVM behavior for which .NET has no suitable equivalent.

The target does not include command-line tooling, the Pkl server, Gradle
integration, documentation site generation, Java/Kotlin code generators, native
image packaging, or upstream build/test infrastructure as product API except
where those systems expose runtime behavior that the .NET library must
preserve. The generated repository-local .NET test adaptation described below
is shipped evidence, not product API.

## Explicit Scope Decisions

These decisions are current product-scope constraints for the .NET library
port:

* YAML support is out of scope.
* MessagePack support is out of scope.
* C# code generation is in scope.
* Java, Kotlin, and other non-C# code generation are out of scope.
* Kotlin-to-C# source translation and a Kotlin frontend are out of scope.
* Server support is out of scope.
* CLI support is out of scope unless a small command-line harness is needed only
  to validate the library.
* Documentation-generation support is out of scope.

The non-C# code-generation exclusion concerns Pkl schema-generator outputs, not
the Java source accepted by DripSharp. Reusable general Java-to-C# translation
remains an architectural requirement.

If a source feature exists only to support an out-of-scope product surface, the
transpiler should record it as deliberately excluded instead of trying to map it
accidentally.

## Third-Party Library Groups

Third-party dependencies in `../research/pkl` should be evaluated by the product
surface they support, not merely by Maven coordinate.

### Core Runtime

These are relevant to the .NET library target:

* GraalVM / Truffle: supports the current JVM runtime and language
  implementation. There is no direct .NET package mapping. Pkl-specific
  evaluation behavior must be reimplemented as product code; only genuinely
  reusable missing-JVM facilities belong in DripSharp's compatibility layer.
* Paguro: supports persistent/immutable data structures. Prefer .NET collection
  semantics such as `System.Collections.Immutable` or focused helpers, depending
  on the exact source usage.
* JSpecify and Error Prone annotations: support nullability and static-analysis
  metadata. Map to C# nullable annotations or drop when they have no runtime
  effect.

### Data Formats

These decisions apply to data-format libraries:

* First-party Pkl JSON utilities are in scope only if needed by the .NET
  library behavior. Prefer a Pkl-compatible facade over `System.Text.Json` when
  replacing the implementation is simpler than transpiling the Java utility.
* SnakeYAML is out of scope because YAML support is out of scope.
* MessagePack is out of scope.

### .NET Library Binding APIs

These are relevant when they support public .NET library behavior:

* GeAnTyRef exists to model Java generic reflection. Replace it with .NET
  reflection and generic type metadata where equivalent binding behavior is in
  scope.
* Kotlin source, reflection, and convenience APIs are not .NET target
  dependencies. When they provide evidence for useful Pkl binding behavior,
  expose that behavior through idiomatic .NET APIs rather than translating the
  Kotlin implementation or preserving Kotlin conventions.

### C# Code Generation

C# code generation is in scope. The Java and Kotlin codegen modules provide
useful source examples, but their target products are out of scope.

* JavaPoet and KotlinPoet should not be ported as runtime dependencies.
* Existing Java/Kotlin codegen behavior should be mined for concepts and tests
  that matter to C# output.
* The durable implementation should be a C# generator driven by the Pkl schema
  and value-model capabilities exposed to .NET plus deterministic C# symbol
  mappings.

The Kotlin implementation language of an upstream helper or generator is not a
reason to add Kotlin translation to DripSharp. Required behavior should be
implemented directly for the C# product.

### Excluded Product Surfaces

The following libraries generally belong to out-of-scope surfaces for the first
.NET library target:

* Clikt, Clikt Markdown, and JLine: CLI support.
* Pkl server MessagePack protocol code: server support.
* CommonMark, `kotlinx.html`, `kotlinx.serialization`, and coroutines in
  `pkl-doc`: documentation generation.
* Gradle API and Gradle plugin dependencies: Gradle integration.
* JUnit, AssertJ, WireMock, Jimfs, and JMH: tests and benchmarks.
* Shadow, Spotless, Error Prone, NullAway, checksum, download, and native-image
  build plugins: build and packaging infrastructure.

Out-of-scope source still matters when it exposes behavior required by an
in-scope runtime path. Otherwise, project selection should exclude it
explicitly rather than letting it enter translation accidentally.

## Target Test-Suite Contract

The user-approved 2026-08-03 policy requires Brine to ship a complete adapted
suite for every pinned upstream test that specifies behavior of the selected
generated `pkl-parser` and `pkl-core` production profiles. This consists of the
complete LanguageSnippet corpus contract, the complete Pkl.Core JUnit contract,
and the complete pkl-parser JUnit contract, including parameter and fixture
invocations. Focused public-consumer tests remain distinct cases and continue to
run alongside that suite.

The adaptation inventories exact upstream case identities, parameter rows,
source and helper files, fixtures and resources, enabled or disabled state,
disabled reason, and platform conditions. It may use systematic .NET adapters
and contract-driven runners instead of reproducing the Kotlin helper hierarchy
or upstream file layout one-for-one. Tests disabled upstream may retain that
state and reason; Brine adds no skips or quarantines. Source language, missing
translation, and implementation difficulty are not classifications and do not
exclude behavior.

The emitted xUnit and companion runner projects are non-packable, reference only
projects and support files in the generated checkout, and run with `dotnet
test`. A clean Brine checkout must restore, build with warnings as errors, and
run them without DripSharp, Java, Kotlin, Gradle, or an external fixture tree.
Every emitted contract, helper, fixture, resource, and adapter is covered by the
generated inventory and exact SHA-256 provenance ledger.

Tests in upstream modules that do not exercise either currently selected
production profile remain evidence for pending product areas rather than new
product exclusions. Adding a future Brine production profile brings its
behavioral tests under this same shipped-suite rule.
