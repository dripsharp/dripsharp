# DripSharp Documentation

DripSharp separates reusable translator contracts from product-target
contracts.

## Shared Translator Documentation

These documents apply to every Java-to-C# target:

* [Architecture](architecture.md) describes the reusable translation system
  and its product-extension boundary.
* [Technology](technology.md) records the semantic frontend, C# representation,
  and validation choices.
* [Transform Pipeline](transform-pipeline.md) defines recursive translation,
  rule dispatch, generation, and validation contracts.
* [Conversion Concerns](conversion-concerns.md) records Java-to-.NET semantic
  differences that every target must address deliberately.

Shared documents must not acquire the source identities, public API decisions,
runtime semantics, or exclusions of a particular product target.

## Product Targets

Target-specific goals, scope, exclusions, runtime behavior, and evidence policy
live under [`targets/`](targets/):

* [Pkl](targets/pkl/) — a .NET library providing the approved Pkl product
  behavior.
* [PdfCube](targets/pdfbox/) — mechanically translated .NET libraries tracking
  the latest stable Apache PDFBox release.

Adding a target does not alter another target's scope or exclusions. Temporary
plans, implementation status, and milestone sequencing do not belong in these
durable documents.
