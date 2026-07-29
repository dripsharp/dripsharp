# PdfCube Port Scope

## Product and Source Baseline

PdfCube mechanically translates the reusable library modules of the latest
stable Apache PDFBox release into separately consumable .NET packages. The
initial baseline is PDFBox `3.0.8`; later stable releases, including stable major
releases, replace that baseline only when their numeric major, minor, and patch
version is greater than the current baseline, through the synchronization
workflow defined in the [product goal](product-goal.md). Publication of a
maintenance release on a lower major line does not downgrade PdfCube.

Development snapshots and release candidates may provide advance compatibility
evidence, but they are not the product baseline while a stable release exists.

## Included Modules and Packages

| Upstream module | PdfCube package | Responsibility |
| --- | --- | --- |
| `pdfbox-io` | `PdfCube.IO` | Random-access I/O, buffering, stream caches, scratch storage, and shared I/O utilities. |
| `fontbox` | `PdfCube.FontBox` | Font parsing, CMaps, encodings, subsetting, discovery, and supported TrueType/OpenType, Type 1, CFF, and related behavior. |
| `xmpbox` | `PdfCube.XmpBox` | XMP metadata modeling, parsing, serialization, creation, and validation. |
| `pdfbox` | `PdfCube.PdfBox` | The complete core PDF object model, parsing, writing, document APIs, content processing, rendering, extraction, manipulation, forms, security, signing, printing, and related behavior. |
| `preflight` | `PdfCube.Preflight` | PDF/A validation behavior supplied by Apache Preflight. |

The package dependency graph mirrors the source modules:

```text
PdfCube.IO

PdfCube.FontBox -> PdfCube.IO

PdfCube.XmpBox

PdfCube.PdfBox -> PdfCube.IO
               -> PdfCube.FontBox

PdfCube.Preflight -> PdfCube.PdfBox
                  -> PdfCube.XmpBox
```

These five packages are the complete public package family. Reusable
compatibility code maps to BCL or SkiaSharp types where appropriate and is
otherwise internalized into the owning packages. It does not become a sixth
public compatibility package without explicit approval.

The module boundary controls packaging, not behavioral completeness. Every
production type and behavior in each selected module remains in scope unless
the authoritative product goal explicitly excludes it.

## Mechanical API Policy

PdfCube preserves upstream library structure and semantics rather than choosing
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

Java packages map deterministically into `PdfCube` namespaces, and public names
use C# casing. Member kinds remain mechanical: a Java method remains a method,
a field remains a field, and overload families remain overload families. For
example, `getNumberOfPages()` maps to `GetNumberOfPages()`, not to a property.

Generated PdfCube projects disable C# nullable reference types. Translation
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

The following upstream artifacts are not shipped as PdfCube products:

* `debugger` and `debugger-app`.
* `tools` and `app`.
* `preflight-app`.
* `examples`.
* Benchmarks and build, test, release, and distribution-only modules or assets.

Source and fixtures from excluded artifacts remain usable as behavior evidence
for included libraries. An excluded wrapper does not exclude the underlying
library API it exercises.

## Project Ingestion

PDFBox is a multi-module Maven build. Accepted product generation requires
DripSharp to discover the real Maven reactor, selected source sets, generated
sources, resources, toolchain, module dependencies, and external classpath.

Maven support must be implemented as reusable DripSharp project ingestion.
Hand-maintained PdfCube source inventories or classpaths are not a durable
substitute for semantic project resolution.

## Platform and Dependency Adaptation

Generated PdfCube projects target `net10.0`. Supported hosts are Windows,
Linux, and macOS on x64 and ARM64.

Approved standard-library and Microsoft-package mappings are recorded in
[Dependency Mappings](dependencies.md). That document also records the
SkiaSharp boundary and specialized capabilities implemented inside PdfCube.

Each Java or third-party dependency used by an included module becomes one of:

* A normal .NET framework API.
* A mechanically translated project or package.
* An appropriate .NET dependency.
* A reusable compatibility capability.
* Focused PdfCube runtime code for destination-specific semantics.
* A blocking unsupported dependency pending implementation.

A blocking dependency is not an exclusion. Java AWT, ImageIO, printing,
cryptography, XML, logging, compression, font discovery, image codecs, and
provider mechanisms must be adapted wherever the selected modules require them.

## Validation

Compilation is necessary but insufficient. Verification must include:

* Clean generation and compilation of every selected package.
* Package dependency and public-surface checks.
* Isolated consumption of each package through its public API.
* Adapted upstream unit and regression tests where appropriate.
* Independent comparisons against the pinned stable Java release using upstream
  PDFs, fonts, metadata, and other fixtures.
* Cross-package workflows representative of the complete selected module graph.

By explicit user decision on 2026-07-28, operating-system execution evidence is
required only for macOS on x64 and ARM64. Windows and Linux remain supported
destination platforms, but their execution evidence must not be requested,
required, or treated as a completion blocker.

Generated C# is disposable and must not be edited into correctness. Failures are
fixed in source discovery, symbol mappings, translation rules, project emission,
compatibility capabilities, or focused PdfCube runtime code, then regenerated
from scratch.
