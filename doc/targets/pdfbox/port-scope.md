# PdfCarton Port Scope

## Product and Source Baseline

PdfCarton mechanically translates the reusable library modules of the latest
stable Apache PDFBox release into separately consumable .NET packages. The
initial baseline is PDFBox `3.0.8`; later stable releases, including stable major
releases, replace that baseline only when their numeric major, minor, and patch
version is greater than the current baseline, through the synchronization
workflow defined in the [product goal](product-goal.md). Publication of a
maintenance release on a lower major line does not downgrade PdfCarton.

Development snapshots and release candidates may provide advance compatibility
evidence, but they are not the product baseline while a stable release exists.

## Included Modules and Packages

| Upstream module | PdfCarton package | Responsibility |
| --- | --- | --- |
| `pdfbox-io` | `DripSharp.PdfCarton.IO` | Random-access I/O, buffering, stream caches, scratch storage, and shared I/O utilities. |
| `fontbox` | `DripSharp.PdfCarton.Fonts` | Font parsing, CMaps, encodings, subsetting, discovery, and supported TrueType/OpenType, Type 1, CFF, and related behavior. |
| `xmpbox` | `DripSharp.PdfCarton.Xmp` | XMP metadata modeling, parsing, serialization, creation, and validation. |
| `pdfbox` | `DripSharp.PdfCarton` | The complete core PDF object model, parsing, writing, document APIs, content processing, rendering, extraction, manipulation, forms, security, signing, printing, and related behavior. |
| `preflight` | `DripSharp.PdfCarton.Preflight` | PDF/A validation behavior supplied by Apache Preflight. |

The project and assembly dependency graph mirrors the source modules:

```text
DripSharp.PdfCarton.IO

DripSharp.PdfCarton.Fonts -> DripSharp.PdfCarton.IO

DripSharp.PdfCarton.Xmp

DripSharp.PdfCarton -> DripSharp.PdfCarton.IO
                    -> DripSharp.PdfCarton.Fonts

DripSharp.PdfCarton.Preflight -> DripSharp.PdfCarton
                              -> DripSharp.PdfCarton.Xmp
```

These five assemblies ship together in the single public
`DripSharp.PdfCarton` NuGet package. Reusable
compatibility code maps to BCL or SkiaSharp types where appropriate and is
otherwise internalized into the owning assemblies. It does not become a sixth
public compatibility assembly without explicit approval.

The module boundary controls project and assembly ownership, not behavioral
completeness. Every
production type and behavior in each selected module remains in scope unless
the authoritative product goal explicitly excludes it.

## Mechanical API Policy

PdfCarton preserves upstream library structure and semantics rather than choosing
a smaller task-oriented API. Translation must retain, as closely as C# and .NET
permit:

* Public classes, interfaces, enums, constructors, methods, fields, constants,
  overload families, inheritance, and generic relationships.
* Low-level COS, parser, writer, content-stream, I/O, font, metadata, and
  validation APIs in addition to high-level document APIs.
* Resource ownership, random-access behavior, incremental-update behavior,
  exception conditions, ordering, equality, and other observable contracts.
* Public extension points and provider mechanisms.
* Module resources required for correct runtime behavior.

.NET namespace and type adaptations must be deterministic mappings from
resolved upstream symbols. Convenience APIs may be added later without
replacing or weakening the mechanically translated contract.

Java packages map deterministically into `DripSharp.PdfCarton` namespaces, and
public names use C# casing. Member kinds remain mechanical: a Java method
remains a method, a field remains a field, and overload families remain
overload families. For example, `getNumberOfPages()` maps to
`GetNumberOfPages()`, not to a property.

Generated PdfCarton projects disable C# nullable reference types. Translation
does not infer or publish nullable-reference annotations for the Java API.

If a literal Java signature has no C# representation, the translator must use
the closest systematic .NET representation and retain equivalent observable
behavior. An unrepresentable or unimplemented public contract is a blocking
gap, not an accepted compatibility reduction.

## Included Behavior

Including the five library modules includes their complete production behavior,
not only commonly advertised workflows. Among other areas, this covers PDF
creation, loading, parsing and recovery, saving and incremental updates, content
streams, document manipulation, text extraction, page rendering, images, color,
fonts, forms, annotations, navigation, attachments, encryption, signatures,
printing, XMP metadata, and PDF/A validation to the extent implemented by the
selected stable upstream release.

This inventory is descriptive, not a whitelist. Omission from the paragraph is
not a product exclusion.

## Excluded Artifacts

The following upstream artifacts are not shipped as PdfCarton products:

* `debugger` and `debugger-app`.
* `tools` and `app`.
* `preflight-app`.
* `examples`.
* Benchmarks and build, test, release, and distribution-only modules or assets.

Source and fixtures from excluded artifacts remain usable as behavior evidence
for included libraries. An excluded wrapper does not exclude the underlying
library API it exercises.

These are exclusions from shipped product surfaces, not from the 2026-08-03
shipped-test contract. Upstream Maven, JVM, benchmark, build, test-runner,
release, example-application, and shaded-application infrastructure is not
translated as a product, while every upstream ordinary test that specifies
in-scope behavior is adapted into the repository-local .NET suite. Missing or
difficult translation, optional fixture size, and absent one-to-one helper
types do not remove a test or behavior from that suite.

## Complete Adapted Test Suite

`DripSharp.PdfCarton.Tests`, or explicitly declared companion test projects,
contains the complete adapted ordinary upstream suite for all five selected
modules. Test projects target `net10.0`, set `IsTestProject=true` and
`IsPackable=false`, and reference all required production and test-support
projects within the generated repository. They are included in repository-local
restore, warnings-as-errors build, and `dotnet test` execution but never in
NuGet packing.

The target-owned test-suite contract and generated ledgers account for every
pinned test source, ordinary case, parameter row or provider result, helper,
fixture, resource, enabled or upstream-disabled state, platform condition,
framework call, and cross-module dependency. Fixture destinations preserve
classpath/resource lookup semantics and carry exact hashes, source paths,
licenses, authorship, and attribution. Upstream-disabled cases retain the exact
upstream state and reason; the adaptation adds no skips or quarantines.

The existing five focused public-API consumer tests remain a distinct shipped
strategy, one for each production package. They are neither counted as the
complete adapted suite nor duplicated as upstream-derived executions.

## Project Ingestion

PDFBox is a multi-module Maven build. Accepted product generation requires
DripSharp to discover the real Maven reactor, selected source sets, generated
sources, resources, toolchain, module dependencies, and external classpath.

Maven support must be implemented as reusable DripSharp project ingestion.
Hand-maintained PdfCarton source inventories or classpaths are not a durable
substitute for semantic project resolution.

## Platform and Dependency Adaptation

Generated PdfCarton production projects target only `netstandard2.0`.
Generated test projects and all executable runners, probes, differential and
validation hosts, and isolated package consumers target `net10.0` and reference
or consume those production projects. Supported hosts are Windows, Linux, and
macOS on x64 and ARM64.

.NET Framework 4.8 consumption compatibility is inferred from the
`netstandard2.0` contract and compatible dependency assets. The repository does
not build or execute net48 and does not claim empirical .NET Framework 4.8
runtime certification.

Approved standard-library and Microsoft-package mappings are recorded in
[Dependency Mappings](dependencies.md). That document also records the
SkiaSharp boundary and specialized capabilities implemented inside PdfCarton.

Each Java or third-party dependency used by an included module becomes one of:

* A normal .NET framework API.
* A mechanically translated project or package.
* An appropriate .NET dependency.
* A reusable compatibility capability.
* Focused PdfCarton runtime code for destination-specific semantics.
* A blocking unsupported dependency pending implementation.

A blocking dependency is not an exclusion. Java AWT, ImageIO, printing,
cryptography, XML, logging, compression, font discovery, image codecs, and
provider mechanisms must be adapted wherever the selected modules require them.

## Validation

Compilation is necessary but insufficient. Verification must include:

* Clean generation and compilation of every selected package.
* Package dependency and public-surface checks.
* Isolated consumption of each package through its public API.
* The complete repository-local adapted upstream ordinary test suite, including
  parameters, helpers, fixtures, enablement, and platform conditions.
* Independent comparisons against the pinned stable Java release using upstream
  PDFs, fonts, metadata, and other fixtures.
* Cross-package workflows representative of the complete selected module graph.

Clean generated-checkout verification restores and builds all five production
projects and every shipped test project with warnings as errors, then runs the
suite without a DripSharp checkout, Java runtime, Maven installation, or
external fixture tree. Repeated generation must produce byte-identical managed
test inputs and ledgers. Target-supported host execution includes the native
SkiaSharp assets and exact platform conditions required by the tests.

By explicit user decision on 2026-07-28, operating-system execution evidence is
required only for macOS on x64 and ARM64. Windows and Linux remain supported
destination platforms, but their execution evidence must not be requested,
required, or treated as a completion blocker.

Generated C# is disposable and must not be edited into correctness. Failures are
fixed in source discovery, symbol mappings, translation rules, project emission,
compatibility capabilities, or focused PdfCarton runtime code, then regenerated
from scratch.
