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
* [Target Directory Contract](target-directory-contract.md) defines the
  product-neutral, fail-closed operational manifest used to compose target
  profiles, mappings, runtime assets, baselines, and validation evidence.
* [Product Repository Contract](product-repositories.md) defines the GitHub
  repository, Git submodule, staging, and generated-publication model.
* [Local NuGet Release Runbook](nuget-release-runbook.md) defines the
  credential-free preparation and inspection flow, the separately authorized
  local publication boundary, recovery rules, and the later trusted-publishing
  handoff.
* [Repository Work History](repository-history.md) reconstructs the project's
  major development phases, rewinds, progress loops, product expansion, and
  recent stabilization from the Git history.
* [Conversion Concerns](conversion-concerns.md) records Java-to-.NET semantic
  differences that every target must address deliberately.

## Reference Material

* [IKVM Comparison and Ecosystem Assessment](ikvm-comparison.md) records the
  dated architectural comparison, distribution guidance, adoption evidence,
  Java-version boundary, commercial examples, and generic-erasure implications
  established during the 2026-08-06 assessment.

Shared documents must not acquire the source identities, public API decisions,
runtime semantics, or exclusions of a particular product target.

## Product Targets

Target-specific goals, scope, exclusions, runtime behavior, and evidence policy
live under [`targets/`](targets/):

* [Brine — Pkl for .NET](targets/pkl/) — a .NET library providing the approved
  Pkl product behavior.
* [PdfCarton](targets/pdfbox/) — mechanically translated .NET libraries tracking
  the latest stable Apache PDFBox release.

Adding a target does not alter another target's scope or exclusions. Temporary
plans, implementation status, and milestone sequencing do not belong in these
durable documents.
