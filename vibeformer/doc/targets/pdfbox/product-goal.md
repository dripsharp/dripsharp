# Authoritative PdfCube Product Goal

## Authority

This document is the user-owned product contract for the PdfCube target. It
takes precedence over bounded source slices, milestones, manifests, acceptance
documents, and release-readiness reports for this target.

Implementation plans may define bounded milestones. They may not narrow this
goal, add an exclusion, convert unfinished behavior into an exclusion, or
redefine completion without explicit user approval.

The following are not scope decisions:

* A behavior is difficult to translate.
* A behavior depends on Java AWT, ImageIO, cryptography, printing, XML, or a
  third-party Java provider.
* A dependency has no direct .NET replacement.
* A behavior is outside the current selected source slice.
* A compiler or behavior gate does not cover the behavior yet.
* A bounded milestone is green.

Those conditions mean the behavior is pending product work unless it appears
in the user-approved exclusion list below.

## Product Target

PdfCube is a family of five separately packaged reusable .NET libraries for
ordinary .NET developers who need the capabilities of Apache PDFBox. It is a
mechanical Java-to-C# port, not a feature-selected reimplementation or a
redesigned PDF API.

The target preserves the selected upstream modules' public type organization,
member contracts, overloads, extension points, and observable semantics as
closely as the .NET platform permits. Systematic Java-to-.NET adaptations must
be implemented through resolved-symbol mappings, reusable compatibility
capabilities, or focused PdfCube runtime code. They must not silently redesign
or remove upstream behavior.

The selected reusable upstream libraries are:

* `pdfbox-io`.
* `fontbox`.
* `xmpbox`.
* `pdfbox`.
* `preflight`.

Their initial .NET package family is:

* `PdfCube.IO`.
* `PdfCube.FontBox`.
* `PdfCube.XmpBox`.
* `PdfCube.PdfBox`.
* `PdfCube.Preflight`.

Each library is packaged separately with dependency relationships corresponding
to the upstream module graph.

## Supported Destination Platforms

PdfCube targets `net10.0`. Its supported host matrix is Windows, Linux, and
macOS on x64 and ARM64. Mobile, WebAssembly, and NativeAOT are outside this
platform contract.

## Stable Upstream Synchronization

PdfCube tracks the latest stable Apache PDFBox release. The initial stable
baseline is PDFBox `3.0.8`, tag commit
`9286e47d89d6877005c9d2d0f2fd38793a62519a`.

For this target, "latest stable" is monotonic: select the greatest stable
Apache PDFBox version under numeric major, minor, and patch ordering that is not
lower than the current baseline. Publication chronology across simultaneously
maintained release lines does not cause a downgrade. In particular, PDFBox
`2.0.37` does not replace `3.0.8` even though `2.0.37` was published later. A
future stable `3.0.9`, `3.1.0`, or `4.0.0` would advance the baseline, while a
later-published release on a lower major line would not.

Synchronization includes those later stable patch, minor, and major releases. A
pre-release, snapshot, release candidate, or development branch does not replace
the current stable baseline. When Apache publishes a greater stable release,
moving the source baseline and preserving the selected modules' updated public
behavior is PdfCube maintenance work, not a reason to freeze or narrow the
target.

An upstream module rename, split, merge, or removal does not silently remove an
already selected PdfCube library or behavior. The package mapping must be
updated to follow the stable upstream implementation while preserving the
approved product surface unless the user explicitly changes that surface. A new
upstream reusable library module requires an explicit target-scope decision
before becoming an additional PdfCube product.

## User-Approved Product Exclusions

Only these upstream product artifacts are excluded from the shipped PdfCube
surface:

* The PDFBox command-line tools.
* The Swing PDF debugger.
* The bundled `app`, `debugger-app`, and `preflight-app` applications.
* The examples artifact as a shipped or translated product.
* Benchmark, build, test, release, Maven, OSGi, JAR, and shaded-application
  infrastructure as shipped product surfaces.
* Manual patches to generated C# as durable implementation.

These exclusions concern shipped artifacts, not library behavior or evidence.
Examples, tests, fixtures, and application usage may specify or demonstrate
behavior required by the five selected libraries.

## Required Adaptation Boundary

The absence of a literal JVM facility or direct .NET package does not exclude
behavior. Java standard-library, AWT, ImageIO, font, graphics, printing,
cryptographic, XML, logging, compression, and provider behavior required by the
selected modules must be represented through generated C#, ordinary .NET APIs,
reusable compatibility code, focused PdfCube runtime code, or appropriate .NET
dependencies.

Maven project discovery required to resolve PDFBox sources, generated inputs,
resources, and dependencies belongs in Vibeformer's reusable project-ingestion
layer rather than a PdfCube-only source manifest.

## Behavior Evidence

Upstream PDFBox tests, test documents, fonts, fixtures, examples, and application
usage are authoritative evidence for selected-library behavior even when those
artifacts are not shipped. Validation should prefer focused assertions against
public package behavior and independent comparisons with the pinned upstream
release.

## Completion Rule

PdfCube completion requires all production behavior and the complete public API
of the five selected modules from the latest stable upstream release to be
translated or faithfully adapted; all remaining exclusions to match the
user-approved list; clean from-scratch generation; zero public implementation
stubs; successful separate package consumption; correct inter-package
dependencies; and independent behavior evidence representative of the complete
selected-module goal across the supported host matrix.

The reusable translator work used to reach that result must remain suitable for
future Java targets. PdfCube-specific PDF semantics and platform adaptations
must not be embedded into the product-neutral Java translation kernel.

A bounded milestone may report milestone readiness. It must not report PdfCube
completion or turn unimplemented selected-module behavior into an exclusion.
