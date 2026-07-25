# PdfCube Target

## Target

The source product is the latest stable Apache PDFBox release. The destination
is the PdfCube family of reusable .NET libraries, produced through DripSharp's
mechanical Java-to-C# translation pipeline while preserving upstream public
APIs and semantics as closely as the .NET platform permits.

## Contracts

* [Authoritative Product Goal](product-goal.md) defines the required libraries,
  synchronization policy, approved exclusions, and completion rule.
* [Port Scope](port-scope.md) records the source modules, package graph,
  product surfaces, and platform-adaptation decisions.
* [Dependency Mappings](dependencies.md) records approved BCL and Microsoft
  package mappings, the SkiaSharp boundary, and capabilities implemented
  internally.

The shared [DripSharp architecture](../../architecture.md),
[technology choices](../../technology.md), [transform pipeline](../../transform-pipeline.md),
and [conversion concerns](../../conversion-concerns.md) govern the reusable
Java-to-C# machinery used by this target.
