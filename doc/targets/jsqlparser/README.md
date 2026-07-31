# SqlTrellis — JSqlParser for .NET

## Target

The source product is the latest stable JSqlParser release. The destination is
**SqlTrellis — JSqlParser for .NET**, a mechanically translated, independently
consumable .NET library that preserves the complete published JSqlParser API
and behavior as closely as the .NET platform permits.

## Product Identity

The generated product repository is `dripsharp/sqltrellis`, linked into the
DripSharp source repository at `products/sqltrellis`. It contains:

* `DripSharp.SqlTrellis`, the assembly, NuGet package, and root namespace for
  the production library.
* `DripSharp.SqlTrellis.Tests`, the complete adapted upstream test suite and
  fixtures as a repository-local runnable test project. It is not published as
  a NuGet package.

JSqlParser remains the upstream identity in public descriptions, source
mapping, provenance, attribution, and non-affiliation material. It is not the
top-level namespace, repository name, or NuGet package identity.

## Contracts

* [Authoritative Product Goal](product-goal.md) defines the required production
  and test behavior, synchronization policy, approved exclusions, and
  completion rule.
* [Port Scope](port-scope.md) records the selected source surfaces, generated
  parser policy, API mapping, test adaptation, platform contract, and
  validation requirements.

The shared [DripSharp architecture](../../architecture.md),
[technology choices](../../technology.md),
[transform pipeline](../../transform-pipeline.md), and
[conversion concerns](../../conversion-concerns.md) govern reusable Java-to-C#
translation used by this target.

The shared [product repository contract](../../product-repositories.md) governs
SqlTrellis generation and publication. Generated production and test C# is
disposable and is never edited manually into correctness.
