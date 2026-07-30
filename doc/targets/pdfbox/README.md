# PdfCarton Target

## Target

The source product is the latest stable Apache PDFBox release. The destination
is the PdfCarton family of reusable .NET libraries, produced through DripSharp's
mechanical Java-to-C# translation pipeline while preserving upstream public
APIs and semantics as closely as the .NET platform permits.

## Product Identity

The generated product repository is `dripsharp/pdfcarton`, linked into the
DripSharp source repository at `products/pdfcarton`. It contains all five
approved projects:

* `DripSharp.PdfCarton` for the core PDF library.
* `DripSharp.PdfCarton.IO` for random-access and stream I/O.
* `DripSharp.PdfCarton.Fonts` for font parsing and subsetting.
* `DripSharp.PdfCarton.Xmp` for XMP metadata.
* `DripSharp.PdfCarton.Preflight` for PDF/A validation.

Apache PDFBox, FontBox, XmpBox, and Preflight remain upstream identities in
source mapping, provenance, attribution, and descriptions. They are not the
PdfCarton package or top-level namespace family.

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

The shared [product repository contract](../../product-repositories.md) governs
PdfCarton's generated-publication lifecycle.
