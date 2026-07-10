# Port Scope

## Product Target

Vibeformer is not trying to port every artifact in `../research/pkl`.
The first product target is a .NET library, not a full replacement for the
JVM distribution.

The target library should provide the Pkl behavior needed by .NET consumers:

* Core Pkl parsing, evaluation, value model, module loading, and runtime
  behavior.
* Public .NET library APIs equivalent to the useful Java/Kotlin library entry
  points.
* C# code generation for Pkl schemas and APIs.

Pkl is the first product target, not the definition of the translator. The
Java/Kotlin-to-C# pipeline, resolved-symbol mappings, and compatibility layer
should remain reusable by future projects. Pkl evaluation and language
semantics belong in the generated/product implementation rather than in a
generic Vibeformer runtime. The generic runtime should contain only facilities
needed to bridge JVM behavior for which .NET has no suitable equivalent.

The target does not include command-line tooling, the Pkl server, Gradle
integration, documentation site generation, Java/Kotlin code generators, native
image packaging, or build/test infrastructure except where those systems expose
runtime behavior that the .NET library must preserve.

## Explicit Scope Decisions

These decisions are current product-scope constraints for the .NET library
port:

* YAML support is out of scope.
* MessagePack support is out of scope.
* C# code generation is in scope.
* Java, Kotlin, and other non-C# code generation are out of scope.
* Server support is out of scope.
* CLI support is out of scope unless a small command-line harness is needed only
  to validate the library.
* Documentation-generation support is out of scope.

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
  reusable missing-JVM facilities belong in Vibeformer's compatibility layer.
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
* Kotlin reflection is not itself a .NET target dependency. If Kotlin-specific
  binding behavior matters, expose it through native .NET API design rather than
  preserving Kotlin reflection APIs.

### C# Code Generation

C# code generation is in scope. The Java and Kotlin codegen modules provide
useful source examples, but their target products are out of scope.

* JavaPoet and KotlinPoet should not be ported as runtime dependencies.
* Existing Java/Kotlin codegen behavior should be mined for concepts and tests
  that matter to C# output.
* The durable implementation should be a C# generator, driven by normalized
  facts and deterministic rules.

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

Out-of-scope does not mean the source can be ignored during analysis. It means
the inventory, coverage gates, and reports should classify these features as
excluded from the .NET library target unless they are needed by an in-scope
runtime path.
