# Authoritative SqlTrellis Product Goal

## Authority

This document is the user-owned product contract for the SqlTrellis target. It
takes precedence over bounded source slices, milestones, manifests, acceptance
documents, and release-readiness reports for this target.

Implementation plans may define bounded milestones. They may not narrow this
goal, add an exclusion, convert unfinished production or test behavior into an
exclusion, or redefine completion without explicit user approval.

The following are not scope decisions:

* A production behavior or upstream test is difficult to translate or adapt.
* A dependency has no direct .NET replacement.
* A source, generated parser, test, helper, or fixture is outside the current
  implementation slice.
* A compiler, test, differential, or packaging gate does not yet cover a
  behavior.
* A bounded milestone is green.

Those conditions mean work is pending unless the affected surface appears in
the user-approved exclusion list below.

## Product Target

SqlTrellis is a reusable .NET library providing the complete production
behavior and public API of JSqlParser. It is a mechanical Java-to-C# port, not
a feature-selected SQL parser or an idiomatic redesign of the upstream object
model.

The approved product identity is **SqlTrellis — JSqlParser for .NET**. Its
generated publication repository is `dripsharp/sqltrellis`, its parent
repository submodule path is `products/sqltrellis`, and its production
assembly, NuGet package, and root namespace are `DripSharp.SqlTrellis`.

The product preserves the upstream parser, abstract syntax tree, statement and
expression model, schema model, feature controls, visitors, builders,
deparsers, validators, metadata utilities, extension points, and observable
semantics as closely as the .NET platform permits. This inventory is
descriptive rather than a whitelist: every production type and behavior in the
selected stable upstream library remains in scope.

JSqlParser remains the upstream identity in public descriptions, terminology,
type names where appropriate, provenance, attribution, and non-affiliation
material. It is not the top-level .NET namespace or product repository name.

DripSharp remains a reusable Java-to-C# translator. JSqlParser-specific SQL
semantics must remain outside generic translation and compatibility layers.

## Stable Upstream Synchronization

SqlTrellis tracks the latest stable JSqlParser release. The initial baseline is
JSqlParser `5.3`, tag commit
`8a9479a05c75fcb73d0ed167a822b9b18ab7abaa`.

For this target, a stable upstream release must be published as a non-draft,
non-prerelease GitHub release and as the corresponding
`com.github.jsqlparser:jsqlparser` artifact in Maven Central. A tag, snapshot,
release candidate, or development branch alone does not advance the baseline.

“Latest stable” is monotonic. SqlTrellis selects the greatest stable numeric
version that is not lower than its current baseline. Later stable patch, minor,
and major releases advance the source baseline and remain maintenance work;
publication chronology cannot cause a downgrade.

Synchronization includes the updated production sources, JavaCC/JJTree
grammar and generated parser, resources, tests, test helpers, and fixtures that
belong to the selected release. An upstream rename, move, split, or merge does
not silently remove already required behavior.

## Shipped Test Suite

The generated SqlTrellis repository ships the complete adapted upstream test
suite and its required fixtures in `DripSharp.SqlTrellis.Tests`. The project is
included in the generated solution, references the production project in that
checkout, and must restore, build, and run through `dotnet test` without a
DripSharp checkout, Java runtime, or manually maintained generated code.

The test suite is a shipped repository surface and a product-completion
requirement, but it is not a NuGet package or a second reusable product API.
Tests and helpers may use idiomatic .NET test frameworks and systematic .NET
adaptations. Adaptation must preserve every upstream test's behavioral intent,
assertions, parameter rows, resources, and enablement status. Tests may be
consolidated or reorganized when necessary; one-to-one test helper types are
not required when the complete behavior and evidence are retained.

SqlTrellis must not add skips, quarantines, weakened assertions, or fixture
omissions merely because behavior is unfinished or difficult. A test disabled
by the selected upstream release may remain equivalently disabled only when it
is still present and its upstream reason is preserved.

Dedicated benchmark harnesses and reports remain excluded. Ordinary upstream
tests of performance-sensitive edge cases remain part of the shipped test
suite.

## Supported Destination Platforms

The SqlTrellis production library targets only `netstandard2.0`; it does not
multi-target or emit a production `net10.0` or `net48` assembly. The complete
shipped adapted test project and every executable runner, probe, differential
host, validation host, and isolated package consumer target `net10.0` and
consume the `netstandard2.0` library. The supported host matrix is Windows,
Linux, and macOS on x64 and ARM64. Mobile, WebAssembly, and NativeAOT are
outside this platform contract.

The intended compatibility contract includes consumption from .NET Framework
4.8 as well as modern .NET. This repository infers that compatibility from the
`netstandard2.0` API contract and compatible package dependencies. It does not
provide a .NET Framework 4.8 build host, runtime, CI job, VM, compatibility
runner, or empirical net48 execution certification.

By explicit user decision on 2026-07-31, completion execution evidence is
required only for macOS on x64 and ARM64. Windows and Linux remain supported
destination platforms, but execution evidence from those operating systems is
not required and must not become a completion blocker.

## License Selection

JSqlParser is dual-licensed under LGPL-2.1 or Apache License 2.0. SqlTrellis
uses the Apache License 2.0 option. Required upstream license, copyright,
notice, fixture, and third-party attribution must be preserved in the generated
repository and packages.

## User-Approved Product Exclusions

Only these upstream artifacts are excluded from the shipped SqlTrellis
surface:

* Maven, Gradle, release, signing, publishing, code-quality, and distribution
  infrastructure as shipped artifacts.
* The documentation site and generated Javadocs as shipped artifacts.
* Dedicated JMH and other benchmark harnesses, benchmark execution, and
  benchmark reports.
* Java JAR publication and Java-specific packaging metadata.
* Publication of `DripSharp.SqlTrellis.Tests` as a NuGet package.
* Manual patches to generated production or test C# as durable implementation.

These exclusions do not remove source-generation inputs, production types in
the published upstream library, ordinary tests, fixtures, or behavior evidence.
Build tooling needed to generate the Java parser and discover the real source
graph remains required input to DripSharp even though that tooling is not
shipped in the .NET product.

## Required Adaptation Boundary

The absence of a literal Java or test facility does not exclude behavior.
Java standard-library, JavaCC/JJTree, reflection, collections, concurrency,
regular-expression, stream, metadata, JUnit, assertion, mocking, database-test,
and fixture behavior required by the selected production and test surfaces
must be represented through generated C#, ordinary .NET APIs, reusable
compatibility code, appropriate .NET dependencies, or focused SqlTrellis test
support.

The JavaCC/JJTree grammar is authoritative source input. Clean generation must
run the upstream generation phase through reusable project ingestion and
translate the resulting Java parser. Hand-porting or manually repairing the
generated parser is not a durable implementation path.

## Completion Rule

SqlTrellis completion requires all production behavior and the complete public
API of the latest stable upstream release to be translated or faithfully
adapted; the complete adapted upstream test suite and fixtures to be present
and runnable in the generated repository; all remaining exclusions to match
the user-approved list; clean generation from the grammar and Java sources;
zero public implementation stubs; no added test skips or weakened assertions;
successful package creation and independent package consumption; and
representative differential evidence against the selected Java release on
macOS x64 and ARM64.

The generated repository must independently restore, build, and pass its
complete `DripSharp.SqlTrellis.Tests` project. Production and test fixes belong
in project ingestion, translation rules, symbol mappings, compatibility
capabilities, authored test adapters, or other generator inputs, followed by
clean regeneration.

A bounded milestone may report milestone readiness. It must not report
SqlTrellis completion or turn unimplemented production or test behavior into
an exclusion.
