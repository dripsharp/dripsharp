# Authoritative PdfCarton Product Goal

## Authority

This document is the user-owned product contract for the PdfCarton target. It
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

PdfCarton is a family of five reusable .NET assemblies in one public package for
ordinary .NET developers who need the capabilities of Apache PDFBox. It is a
mechanical Java-to-C# port, not a feature-selected reimplementation or a
redesigned PDF API.

The approved product identity is **PdfCarton**. Its generated publication
repository is `dripsharp/pdfcarton`, its parent-repository submodule path is
`products/pdfcarton`, and its public assembly, package, and namespace family is
rooted at `DripSharp.PdfCarton`.

The target preserves the selected upstream modules' public type organization,
member contracts, overloads, extension points, and observable semantics as
closely as the .NET platform permits. Systematic Java-to-.NET adaptations must
be implemented through resolved-symbol mappings, reusable compatibility
capabilities, or focused PdfCarton runtime code. They must not silently redesign
or remove upstream behavior.

The selected reusable upstream libraries are:

* `pdfbox-io`.
* `fontbox`.
* `xmpbox`.
* `pdfbox`.
* `preflight`.

Their approved .NET assembly family is:

* `DripSharp.PdfCarton.IO`.
* `DripSharp.PdfCarton.Fonts`.
* `DripSharp.PdfCarton.Xmp`.
* `DripSharp.PdfCarton`.
* `DripSharp.PdfCarton.Preflight`.

The five assemblies ship together in one public NuGet package,
`DripSharp.PdfCarton`. Project and assembly references retain dependency
relationships corresponding to the upstream module graph; those internal
boundaries do not require separate public package identities.

## Supported Destination Platforms

PdfCarton targets `net10.0`. Its supported host matrix is Windows, Linux, and
macOS on x64 and ARM64. Mobile, WebAssembly, and NativeAOT are outside this
platform contract.

By explicit user decision on 2026-07-28, completion evidence is required only
for macOS on x64 and ARM64. Windows and Linux remain supported destination
platforms, but execution evidence from those operating systems must not be
requested, required, or treated as a completion blocker.

## Stable Upstream Synchronization

PdfCarton tracks the latest stable Apache PDFBox release. The initial stable
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
behavior is PdfCarton maintenance work, not a reason to freeze or narrow the
target.

An upstream module rename, split, merge, or removal does not silently remove an
already selected PdfCarton library or behavior. The package mapping must be
updated to follow the stable upstream implementation while preserving the
approved product surface unless the user explicitly changes that surface. A new
upstream reusable library module requires an explicit target-scope decision
before becoming an additional PdfCarton product.

## User-Approved Product Exclusions

Only these upstream product artifacts are excluded from the shipped PdfCarton
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

In particular, excluding upstream Maven, JVM, benchmark, build, test-runner,
release, example-application, and shaded-application infrastructure does not
exclude a generated .NET adaptation of an upstream test that specifies
in-scope behavior. Missing translation, difficult behavior, a large or
optional fixture, and the absence of a one-to-one .NET helper type are pending
adaptation work, not additional exclusions.

## Required Adaptation Boundary

The absence of a literal JVM facility or direct .NET package does not exclude
behavior. Java standard-library, AWT, ImageIO, font, graphics, printing,
cryptographic, XML, logging, compression, and provider behavior required by the
selected modules must be represented through generated C#, ordinary .NET APIs,
reusable compatibility code, focused PdfCarton runtime code, or appropriate .NET
dependencies.

Maven project discovery required to resolve PDFBox sources, generated inputs,
resources, and dependencies belongs in DripSharp's reusable project-ingestion
layer rather than a PdfCarton-only source manifest.

## Shipped Adapted Upstream Test Suite

By explicit user decision on 2026-08-03, the generated PdfCarton repository
ships the complete adapted ordinary upstream test suite for `pdfbox-io`,
`fontbox`, `xmpbox`, `pdfbox`, and `preflight`, together with every helper,
fixture, and resource required to run it. The suite is repository-local,
runnable .NET test evidence. It is not a NuGet package, reusable product API,
or sixth PdfCarton library.

The generated suite must preserve the behavioral intent and assertions of every
ordinary upstream test, including every parameter row or provider result,
cross-module dependency, shared test utility, enablement state, and platform
condition. One-to-one Java test-class and helper layouts are not required when
a systematic .NET adapter preserves the same behavior. Every upstream-disabled
case retains its upstream state and reason. No newly introduced skip,
quarantine, or disabled case is permitted.

Every required PDF, font, color profile, image, XMP packet, encryption input,
validation corpus file, and other fixture or resource ships with deterministic
hash, exact upstream source and case accounting, license, authorship, and
attribution. The suite must restore, build with warnings as errors, and run via
`dotnet test` from a clean generated PdfCarton checkout without a DripSharp
checkout, Java runtime, Maven installation, or external fixture tree. Its
projects are non-packable and reference only projects and test support within
that generated repository.

The five focused public-API consumer tests remain a separate shipped strategy.
They prove ordinary consumer access to each package, are not counted as complete
upstream coverage, and must not cause an adapted upstream case to execute twice.

Upstream PDFBox tests, test documents, fonts, fixtures, examples, and
application usage remain authoritative evidence for selected-library behavior.
Independent comparisons with the pinned Java release remain complementary
evidence; they do not replace the shipped complete adapted suite.

## Completion Rule

PdfCarton completion requires all production behavior and the complete public API
of the five selected modules from the latest stable upstream release to be
translated or faithfully adapted; all remaining exclusions to match the
user-approved list; clean from-scratch generation; zero public implementation
stubs; successful separate package consumption; correct inter-package
dependencies; the complete shipped adapted upstream suite to restore, compile,
and pass independently; and independent behavior evidence representative of the
complete selected-module goal on macOS x64 and ARM64. Windows and Linux
execution evidence is not required for completion.

The reusable translator work used to reach that result must remain suitable for
future Java targets. PdfCarton-specific PDF semantics and platform adaptations
must not be embedded into the product-neutral Java translation kernel.

A bounded milestone may report milestone readiness. It must not report PdfCarton
completion or turn unimplemented selected-module behavior into an exclusion.
