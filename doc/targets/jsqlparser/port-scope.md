# SqlTrellis Port Scope

## Product and Source Baseline

SqlTrellis mechanically translates the complete published library and adapts
the complete test suite of the latest stable JSqlParser release. The initial
baseline is JSqlParser `5.3`, tag commit
`8a9479a05c75fcb73d0ed167a822b9b18ab7abaa`.

Later stable releases replace that baseline only through the monotonic
selection policy in the [product goal](product-goal.md). Snapshots,
prereleases, development branches, and tags without both a stable GitHub
release and a Maven Central artifact may provide advance evidence but are not
product baselines.

## Included Production Surface

The selected production surface comprises:

| Upstream surface | SqlTrellis destination | Scope |
| --- | --- | --- |
| `src/main/java` | `src/DripSharp.SqlTrellis` | Every production type and behavior in the published library. |
| `src/main/jjtree/net/sf/jsqlparser/parser/JSqlParserCC.jjt` | Generated parser sources within `src/DripSharp.SqlTrellis` | The authoritative JavaCC/JJTree grammar and all Java generated from it. |
| `src/main/resources` | Production project resources | Every runtime resource required by selected production behavior. |

The production project becomes the `DripSharp.SqlTrellis` assembly and NuGet
package. There is no second production package or public compatibility package
without explicit approval.

The included behavior covers parsing, statement and expression modeling,
schema modeling, SQL construction, visitors, deparsing, feature configuration,
validation, metadata utilities, and every other behavior present in the
selected production sources. Omission from this summary is not an exclusion.

## Included Test Surface

The generated product repository contains
`tests/DripSharp.SqlTrellis.Tests`, an adapted test project comprising:

* Every ordinary upstream test under `src/test/java`.
* Required test helpers and parameterized test data.
* Every required fixture and resource under `src/test/resources`.
* Required attribution and license material for test inputs.
* Systematic .NET adapters needed to run the suite without a JVM or DripSharp
  checkout.

Dedicated JMH benchmark runners, benchmark source sets, benchmark execution,
and benchmark reports are excluded. A test that exercises a
performance-sensitive parser edge case through the ordinary upstream test
task is a test, not an excluded benchmark.

The test project is part of the generated solution and public repository. It
is not packed or published to NuGet. Its project references resolve only to the
repository's generated `src/` tree.

## Parser Source Generation

JSqlParser uses Maven and JavaCC/JJTree to generate parser Java during the
source-generation phase. Accepted SqlTrellis generation requires DripSharp to
discover and execute the real Maven generation lifecycle, grammar, generated
source roots, ordinary source roots, resources, test sources, test resources,
and build classpath.

This capability belongs in reusable DripSharp Maven project ingestion. A
checked-in, hand-maintained generated-Java inventory or a C# rewrite of the
grammar is not a durable substitute. The pipeline is:

```text
JSqlParserCC.jjt
  -> upstream JavaCC/JJTree generation
  -> generated Java parser sources
  -> DripSharp resolved-symbol translation
  -> generated SqlTrellis C#
```

Source generation must be deterministic for the pinned upstream release. The
generated product repository need not ship JavaCC, a JVM, Maven, Gradle, or the
Java grammar as runtime dependencies.

## Mechanical API Policy

SqlTrellis preserves upstream library organization and observable semantics
rather than designing a smaller task-oriented parser API. Translation retains,
as closely as C# permits:

* Public classes, interfaces, enums, constructors, methods, fields, constants,
  overload families, inheritance, and generic relationships.
* The complete AST shape and public visitor, builder, parser, deparser,
  validation, metadata, and extension contracts.
* Parsing and deparsing results, feature flags, exception conditions,
  ordering, equality, mutability, and other observable behavior.
* Public extension points and required resources.

The `net.sf.jsqlparser` package maps to `DripSharp.SqlTrellis`; nested Java
packages map deterministically beneath that namespace. Public identifiers use
systematic C# casing. Member kinds remain mechanical: a Java method remains a
method, a field remains a field, and overload families remain overload
families. Java getter and setter methods do not become properties merely for
idiomatic appearance.

Generated production projects disable C# nullable reference types. Translation
does not infer or publish nullable-reference annotations for the Java API.

Convenience APIs may be added later without replacing, weakening, or obscuring
the mechanically translated contract. If a literal Java signature has no C#
representation, the closest systematic .NET representation must preserve its
observable behavior. An unrepresentable or unimplemented contract is a
blocking gap, not an accepted compatibility reduction.

## Test Adaptation Policy

The test project may use xUnit and appropriate .NET assertion, mocking,
database, and fixture facilities instead of literal JUnit, AssertJ, Mockito,
Hamcrest, H2, or other Java test dependencies. Such substitution is an
adaptation boundary, not authority to reduce coverage.

Each upstream test's assertions, inputs, parameter rows, expected exceptions,
and relevant setup and teardown semantics must remain represented. Equivalent
tests may be reorganized or consolidated, and focused authored adapters may
replace Java-only test infrastructure. Every divergence must be systematic or
traceable to an explicit destination-platform adaptation.

No new skip, quarantine, weakened assertion, or fixture omission is allowed as
a substitute for implementing required behavior. Upstream-disabled tests may
retain their selected-release status and reason, but remain present in the
generated suite.

## Dependencies and Platform Adaptation

The production source has no required third-party runtime library beyond Java
platform behavior; JavaCC/JJTree is a build-time generator. Any future stable
release dependency must be classified as a normal .NET API, translated
library, appropriate .NET dependency, reusable compatibility capability,
focused SqlTrellis implementation, or blocking unsupported dependency.

Test-only dependencies may map to suitable .NET test packages or focused test
support. Test dependency adaptation must not leak JSqlParser-specific semantics
into DripSharp's product-neutral translation kernel.

The SqlTrellis production project targets only `netstandard2.0`. Its complete
shipped test project and all executable runners, probes, differential and
validation hosts, and isolated package consumers target `net10.0` and consume
that production assembly. SqlTrellis supports Windows, Linux, and macOS on x64
and ARM64. Mobile, WebAssembly, and NativeAOT are excluded. Completion execution
evidence is required only on macOS x64 and ARM64.

.NET Framework 4.8 consumption compatibility is inferred from the
`netstandard2.0` contract and compatible dependencies. The repository does not
build or execute net48 and does not claim empirical .NET Framework 4.8 runtime
certification.

## Excluded Artifacts

The following upstream artifacts are not shipped as SqlTrellis product
surfaces:

* Maven, Gradle, signing, publishing, release, distribution, and code-quality
  infrastructure.
* Documentation-site and generated-Javadoc products.
* Dedicated benchmark harnesses, execution, and reports.
* Java JARs and Java-specific packaging metadata.

The test suite is expressly included as a shipped repository surface. The
exclusion of Java test frameworks and build systems does not exclude their
tests, helpers, fixtures, or behavioral evidence.

## Validation

Compilation is necessary but insufficient. Verification must include:

* Clean Maven/JavaCC source generation followed by clean C# generation.
* Compilation and public-surface checks for `DripSharp.SqlTrellis`.
* NuGet package creation and isolated public package consumption.
* Independent restore, build, and `dotnet test` of the complete generated
  `DripSharp.SqlTrellis.Tests` project.
* Checks that the adapted suite contains every required upstream test,
  parameter row, helper behavior, and fixture without added skips or weakened
  assertions.
* Differential parsing, AST, deparsing, validation, and exception comparisons
  against the pinned upstream Java release.
* Completion execution evidence on macOS x64 and ARM64.

Generated production and test C# is disposable. Failures are fixed in source
generation, project ingestion, resolved-symbol mappings, translation rules,
compatibility capabilities, focused runtime or test support, or other generator
inputs, then regenerated from scratch.
