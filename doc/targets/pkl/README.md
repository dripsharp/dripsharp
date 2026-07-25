# Pkl Target

## Target

The source product is Pkl's Java/JVM implementation. The destination product is
an independently consumable .NET library with idiomatic public APIs and C# code
generation for Pkl schemas and APIs.

## Contracts

* [Authoritative Product Goal](product-goal.md) defines the required product
  behavior, approved exclusions, and completion rule.
* [Port Scope](port-scope.md) records source-surface, dependency, platform, and
  code-generation decisions for the Pkl port.

The shared [DripSharp architecture](../../architecture.md),
[technology choices](../../technology.md), [transform pipeline](../../transform-pipeline.md),
and [conversion concerns](../../conversion-concerns.md) govern the reusable
Java-to-C# machinery used by this target.
