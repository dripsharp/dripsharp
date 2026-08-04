# Brine — Pkl for .NET

## Target

The source product is Pkl's Java/JVM implementation. The destination product is
**Brine — Pkl for .NET**, an independently consumable .NET library with
idiomatic public APIs and C# code generation for Pkl schemas and APIs.

## Product Identity

The generated product repository is
[`dripsharp/brine`](https://github.com/dripsharp/brine), linked into the
DripSharp source repository at `products/brine`. It contains both approved
projects:

* `DripSharp.Brine` is the main assembly, package identity, and root namespace.
* `DripSharp.Brine.Parser` is the parser assembly, package identity, and root
  namespace.
* Parser syntax APIs use `DripSharp.Brine.Parser.Syntax` and
  `DripSharp.Brine.Parser.Syntax.Generic`.

“Pkl” remains the language and upstream identity in type names, terminology,
descriptions, provenance, attribution, and non-affiliation notices. It is not
the top-level .NET namespace or the product repository name.

## Contracts

* [Authoritative Product Goal](product-goal.md) defines the required product
  behavior, approved exclusions, and completion rule.
* [Port Scope](port-scope.md) records source-surface, dependency, platform, and
  code-generation decisions for the Pkl port.

The shared [DripSharp architecture](../../architecture.md),
[technology choices](../../technology.md), [transform pipeline](../../transform-pipeline.md),
and [conversion concerns](../../conversion-concerns.md) govern the reusable
Java-to-C# machinery used by this target.

The shared [product repository contract](../../product-repositories.md) governs
Brine's generated-publication lifecycle.

The generated Brine checkout includes the complete repository-local adapted
suite for the selected production profiles: the pinned upstream LanguageSnippet,
Pkl.Core, and pkl-parser contracts plus the existing focused consumer cases.
Independent xUnit case identities and companion .NET runners preserve parameter
and fixture invocations, enablement, platform conditions, assertions, and exact
provenance. The non-packable suite restores, builds with warnings as errors, and
runs through `dotnet test` using only files in the generated checkout; it does
not require DripSharp, Java, Kotlin, Gradle, or an external fixture tree.

DripSharp owns the disposable generator, authored package adapters, fixture
inventory, mechanical/authored/vendored provenance boundary, and fail-closed
checks; the generated product repository remains a consumer of those inputs
rather than a durable source of manual test fixes.
