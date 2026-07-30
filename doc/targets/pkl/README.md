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
