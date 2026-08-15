# PdfCarton Dependency Mappings

## Purpose

This document records approved dependency and platform mappings for the five
PdfCarton source modules. SkiaSharp and the optional `SkiaSharp.HarfBuzz` and
`HarfBuzzSharp` text-shaping packages are the approved third-party product
dependencies; .NET standard-library and Microsoft-package mappings remain
allowed.

The absence of a resolved mapping is not a product exclusion. It is blocking
product work under the [authoritative product goal](product-goal.md).

The initial source baseline is Apache PDFBox `3.0.8`. Dependency versions and
usage must be re-audited whenever PdfCarton advances to a greater stable upstream
release under the monotonic version-selection policy in the
[authoritative product goal](product-goal.md).

## PdfCarton Assembly Dependencies

The selected upstream libraries become separate PdfCarton assemblies inside
the single public `DripSharp.PdfCarton` NuGet package:

| Source module | Destination assembly | PdfCarton dependencies |
| --- | --- | --- |
| `pdfbox-io` | `DripSharp.PdfCarton.IO` | None. |
| `fontbox` | `DripSharp.PdfCarton.Fonts` | `DripSharp.PdfCarton.IO`. |
| `xmpbox` | `DripSharp.PdfCarton.Xmp` | None. |
| `pdfbox` | `DripSharp.PdfCarton` | `DripSharp.PdfCarton.IO`, `DripSharp.PdfCarton.Fonts`. |
| `preflight` | `DripSharp.PdfCarton.Preflight` | `DripSharp.PdfCarton`, `DripSharp.PdfCarton.Xmp`. |

These are project and assembly references, not separate public NuGet package
dependencies or compatibility helpers. Their public types and behavior are
mechanically translated with their owning modules.

## Locked `netstandard2.0` Package-Proof Closure

PdfCarton's dependency-closed package proof copies every external NuGet archive
selected by the five production projects' `netstandard2.0` restore assets into
the fresh proof feed. The target-owned package contracts require the following
complete 21-package set at these exact versions:

| Package | Locked version | Closure role |
| --- | --- | --- |
| `Microsoft.Bcl.AsyncInterfaces` | `10.0.0` | Transitive logging dependency. |
| `Microsoft.Bcl.Cryptography` | `10.0.0` | Transitive PKCS dependency. |
| `Microsoft.CSharp` | `4.7.0` | Direct `netstandard2.0` compatibility dependency. |
| `Microsoft.Extensions.DependencyInjection.Abstractions` | `10.0.0` | Transitive logging dependency. |
| `Microsoft.Extensions.Logging.Abstractions` | `10.0.0` | Direct logging mapping. |
| `Microsoft.NETCore.Platforms` | `1.1.0` | Transitive `NETStandard.Library` platform catalog. |
| `NETStandard.Library` | `2.0.3` | SDK-selected `netstandard2.0` reference package. |
| `SkiaSharp` | `4.150.1` | Direct graphics and imaging mapping. |
| `SkiaSharp.NativeAssets.Linux` | `4.150.1` | Direct Linux native deployment asset. |
| `SkiaSharp.NativeAssets.macOS` | `4.150.1` | Transitive macOS native deployment asset. |
| `SkiaSharp.NativeAssets.Win32` | `4.150.1` | Transitive Windows native deployment asset. |
| `System.Buffers` | `4.6.1` | Transitive memory and logging dependency. |
| `System.Diagnostics.DiagnosticSource` | `10.0.0` | Transitive logging dependency. |
| `System.Formats.Asn1` | `10.0.0` | Direct ASN.1 compatibility and transitive PKCS dependency. |
| `System.Memory` | `4.6.3` | Direct `netstandard2.0` compatibility dependency. |
| `System.Numerics.Vectors` | `4.6.1` | Transitive memory dependency. |
| `System.Runtime.CompilerServices.Unsafe` | `6.1.2` | Transitive memory, diagnostics, and task dependency. |
| `System.Security.Cryptography.Cng` | `5.0.0` | Transitive PKCS dependency. |
| `System.Security.Cryptography.Pkcs` | `10.0.0` | Direct CMS/PKCS mapping. |
| `System.Text.Encoding.CodePages` | `10.0.0` | Direct `netstandard2.0` encoding compatibility dependency. |
| `System.Threading.Tasks.Extensions` | `4.6.3` | Transitive async-interface dependency. |

This is an exact fail-closed proof contract, not a minimum dependency list:
packing fails when an archive is missing, additional, or restored at another
version. Each packed component also retains its exact direct NuGet dependencies
from the corresponding `targets/pdfcube/destinations/*.edn` contract. Fresh
`net10.0` consumers verify the complete published metadata closure while
partitioning packages absent from the execution restore into exact
`packagesToPrune` evidence and a target-owned framework dependency-group
contract. At the current locked versions, the `net10.0` dependency groups omit
`Microsoft.Bcl.AsyncInterfaces` `10.0.0` from the logging chain and
`Microsoft.Bcl.Cryptography` `10.0.0` from the PKCS chain. The consumer proof
requires exactly those omissions when they are in a selected package's
published closure and rejects a missing, additional, or wrong-version omission.
Each `targets/pdfcube/destinations/*.edn` package-consumer contract pins the
applicable identities: I/O, Fonts, and Xmp pin only
`Microsoft.Bcl.AsyncInterfaces` `10.0.0`; PdfCarton and Preflight pin both
identities. The complete-family consumer derives the same exact subset from its
published closure.
Neither framework pruning nor dependency-group selection removes the archives
from the exact `netstandard2.0` package-proof closure above.

## Resolved Production Dependency Mappings

### Apache Commons Logging

Source dependency:

* `commons-logging:commons-logging:1.4.0`.

Destination:

* [`Microsoft.Extensions.Logging`](https://learn.microsoft.com/en-us/dotnet/core/extensions/logging/overview)
  abstractions and providers.

PDFBox uses Commons Logging for diagnostic messages rather than as a central
public data model. Translation should map `Log` and `LogFactory` behavior
systematically to `ILogger`-based behavior, using a focused compatibility
facade when that preserves the mechanically translated call sites more cleanly.
PdfCarton libraries must not require a particular logging provider.

### Bouncy Castle CMS, ASN.1, and X.509 Usage

Source dependencies, optional in upstream `pdfbox`:

* `org.bouncycastle:bcprov-jdk18on:1.84`.
* `org.bouncycastle:bcpkix-jdk18on:1.84`.

Destination:

* [`System.Security.Cryptography.Pkcs`](https://learn.microsoft.com/en-us/dotnet/api/system.security.cryptography.pkcs.envelopedcms?view=net-10.0)
  for CMS/PKCS#7 enveloped-data behavior.
* [`System.Formats.Asn1`](https://learn.microsoft.com/en-us/dotnet/api/system.formats.asn1.asnreader?view=net-10.0)
  for BER, CER, and DER reading and writing not covered by the higher-level CMS
  APIs.
* `System.Security.Cryptography.X509Certificates` for certificates and keys.
* `System.Security.Cryptography` for supported algorithms and key operations.

The upstream production imports are concentrated in public-key PDF encryption
and security-provider integration. PdfCarton should implement that behavior with
the Microsoft cryptographic APIs rather than porting the Java provider model or
adding Bouncy Castle by default. The mapping must retain upstream recipient,
certificate-selection, CMS encoding, decryption, and failure behavior.

PDFBox's own RC4 and PDF security-handler source remains mechanically translated;
it is not supplied by this dependency mapping.

### SkiaSharp Graphics and Images

Use [`SkiaSharp` `4.150.1`](https://www.nuget.org/packages/SkiaSharp/4.150.1)
(Skia m150), the latest stable release when selected. Re-audit later stable
releases during upstream synchronization.

SkiaSharp supplies the canvas, paths, matrices, bitmaps, shaders, clipping,
strokes, PDF blend modes, and common JPEG/PNG-style codecs. Map `GeneralPath`
to `SKPath`, `AffineTransform` to `SKMatrix`, mutable `BufferedImage` to
`SKBitmap`, and `Graphics2D` to a PdfCarton compatibility facade over `SKCanvas`.
Use the CPU raster backend for canonical differential validation.

SkiaSharp is not a Java2D or general `Raster` implementation. PdfCarton must keep
an internal managed raster model for packed, indexed, CMYK, DeviceN, and other
arbitrary-component data before conversion to a Skia image. SkiaSharp's ICC
support may accelerate representable RGB profiles but does not replace full ICC
parsing, CMYK/LUT transforms, or Preflight metadata validation.

Official `SkiaSharp.NativeAssets.*` packages required for supported hosts are
deployment artifacts of this approved dependency.

### Optional HarfBuzz Text Shaping

[`SkiaSharp.HarfBuzz` `4.150.1`](https://www.nuget.org/packages/SkiaSharp.HarfBuzz/4.150.1)
and its [`HarfBuzzSharp`](https://www.nuget.org/packages/HarfBuzzSharp)
dependency may be used when they help preserve complex-script text shaping or
Java2D-style glyph-layout behavior. PdfCarton may also use `HarfBuzzSharp`
directly when its lower-level buffer, font, feature, cluster, or glyph-position
APIs are a better fit than the SkiaSharp integration layer.

Their use is optional and should be selected from concrete translated call
sites and behavior evidence. They are appropriate for contextual glyph
selection, OpenType substitution and positioning, ligatures, joining forms,
combining marks, clusters, and glyph advances. Text in PDF content streams is
often already encoded and positioned as glyphs; PdfCarton must not reshape it
when doing so would change the source behavior.

HarfBuzz is a shaping engine, not an implementation of the Unicode
Bidirectional Algorithm. Mixed-direction run analysis, embedding levels, visual
run ordering, and behavior matching `java.text.Bidi` remain a separate required
capability. When both capabilities apply, PdfCarton must perform bidirectional
segmentation first and shape each resulting uniform-direction run.

When either HarfBuzz package is used, the corresponding official
`HarfBuzzSharp.NativeAssets.*` packages required for Windows, Linux, and macOS
on x64 and ARM64 are approved deployment artifacts. Their versions and host
coverage must be kept compatible with the selected stable SkiaSharp release and
verified during package-consumption testing.

#### PDFBox 3.0.8 Evaluation

HarfBuzz is not selected for the PDFBox `3.0.8` baseline. This is a
baseline-specific dependency decision, not a product exclusion.

The production call-site audit found one Unicode-to-complex-glyph producer
boundary: `PDAbstractContentStream.showTextInternal(String)`, reached directly
by `showText`, positioned-text arrays, form and annotation appearance
generation, plain-text formatters, and line-annotation appearance generation.
For `PDType0Font`, synchronized PDFBox already routes that boundary through
FontBox `GsubWorker` implementations and then writes the resulting glyph IDs.
The mechanically translated `GsubWorker` path is therefore the compatibility
implementation for this baseline.

`SkiaSharp.HarfBuzz` would be a suitable high-level integration for a concrete
Unicode `drawString` caller, but the selected production code has no such
caller. Direct `HarfBuzzSharp` could expose lower-level clusters and positions
at the content-stream producer boundary, but replacing `GsubWorker` there would
change the glyph sequences and positioning beyond synchronized PDFBox behavior.
Neither package improves fidelity at a concrete `3.0.8` call site.

`GroupGraphics.drawString` and `drawGlyphVector` are forwarding overrides on the
transparency-group graphics wrapper; no selected production caller supplies
Unicode text to them. PDF content processing instead enters
`PDFStreamEngine.showText(byte[])` with encoded character codes and explicit
text matrices or `TJ` adjustments. Rendering and extraction must preserve those
codes and positions rather than applying another shaping pass.

The pinned Java/package font-text differential verifies FontBox substitution
and advances for Bengali, Devanagari, Gujarati, combining marks, and Latin
ligatures. It also verifies Arabic/Hebrew ligature and combining-mark clusters,
direction/order, and glyph positions from upstream PDFs, and asserts that
extraction leaves their content-stream bytes unchanged. The package closure is
checked for zero HarfBuzz assembly dependencies and zero public HarfBuzz
signatures.

Re-audit this decision whenever the stable PDFBox baseline advances or a
selected production call site begins with Unicode text and delegates complex
layout to Java2D. If HarfBuzz is selected then, bidirectional analysis must
still precede shaping, the shaping boundary must remain internal, and the
supported-host native-asset requirements above apply.

## Resolved Java Platform Mappings

| Java capability | Destination capability | Scope |
| --- | --- | --- |
| `java.io` streams, readers, writers, and files | `System.IO` | Stream and filesystem behavior. |
| `java.nio` buffers, channels, paths, and mapped files | `System.Buffers`, `System.IO`, `System.IO.MemoryMappedFiles`, `System.Memory`, and span/memory APIs | Random access, buffering, memory mapping, and binary processing. |
| Java collections and concurrent collections | `System.Collections.Generic`, `System.Collections.Concurrent`, and appropriate immutable/read-only interfaces | Collection and cache structure. |
| Java exceptions and resource lifetime | .NET exceptions, `IDisposable`, `using`, and focused suppressed-exception compatibility where observable | Failure and cleanup semantics. |
| `java.util.zip` compression streams | `System.IO.Compression`, including deflate and zlib streams | PDF stream compression and decompression where PDFBox delegates to the JDK. |
| Message digests, symmetric algorithms, secure randomness, keys, and certificates | `System.Security.Cryptography` and `System.Security.Cryptography.X509Certificates` | Standard cryptographic operations. |
| DOM, SAX, XML parsing, transformation, and serialization | `System.Xml` and `System.Xml.Linq` where their public behavior matches | XMP, FDF/XFDF, metadata, and Preflight XML behavior. |
| Character encodings, Unicode normalization, regular expressions, locale-neutral formatting, and numeric parsing | `System.Text`, `System.Globalization`, and `System.Text.RegularExpressions` | Text and syntax processing other than bidirectional reordering. |
| Dates, times, durations, and time zones | `System.DateTimeOffset`, `System.TimeZoneInfo`, `System.TimeSpan`, and related BCL APIs | PDF and XMP temporal values. |
| Reflection and ordinary service discovery | `System.Reflection`, assembly metadata, and focused registries where needed | Resolved runtime type and provider lookup, excluding ImageIO codec discovery. |
| URI, URL, and socket primitives | `System.Uri`, `System.Net`, and related BCL APIs | Network and identifier behavior used by selected modules. |

Mappings are keyed by resolved source symbols and overloads, not by simple names.
Where Java and .NET details differ, a small reusable compatibility capability is
appropriate; the existence of such a helper does not make the dependency
unresolved.

## Test-Only and Build-Only Dependencies

The following upstream dependencies do not become PdfCarton runtime package
dependencies:

| Source dependency | Treatment |
| --- | --- |
| JUnit Jupiter | Adapt the complete ordinary upstream assertion, lifecycle, parameter, enablement, condition, helper, and fixture contract to repository-local xUnit projects. |
| Commons IO in Preflight tests | Use `System.IO` and focused test helpers. |
| Java Diff Utils | Use focused comparison/reporting test helpers; no product dependency. |
| Mockito | Use focused test doubles for the small mocked surface; no product dependency. |
| Log4j Core and Log4j JCL | Use the selected `Microsoft.Extensions.Logging` test provider or an in-memory test sink. |
| Maven parent POMs and build plugins | Use them only for upstream project discovery, source/resource resolution, and oracle execution. They are not translated or shipped. |
| Downloaded PDFs, fonts, and validation corpora | Retain as checksum-pinned behavior evidence when permitted; they are not product dependencies. |

Image-codec dependencies used in upstream tests do not become PdfCarton runtime
dependencies; their cases validate SkiaSharp or PdfCarton's internal decoders.

## Required Internal Capabilities

The approved SkiaSharp and HarfBuzz packages do not supply the following
behavior, which must be implemented or mechanically translated inside PdfCarton
without another third-party dependency:

* The Java2D compatibility facade and arbitrary-component raster model described
  above.
* Full ICC parsing, color transforms, and Preflight validation.
* JBIG2 decoding.
* JPEG 2000/JPX decoding.
* Unicode Bidirectional Algorithm behavior matching `java.text.Bidi`.
* `Printable` and `Pageable`-style page layout and rendering over `SKCanvas`;
  SkiaSharp does not provide portable printer discovery or job submission.

These are required implementation work, not product exclusions.

They may be implemented by mechanically porting source from projects licensed
under Apache-2.0, MIT, BSD, or ISC terms. Such source is built into PdfCarton
rather than referenced as another package dependency; its pinned upstream
identity, license, copyright, and required notices must be preserved. Source
under other license terms requires explicit approval.

### Pinned Internal Image Codec Source

PdfCarton builds the following codec source directly into `DripSharp.PdfCarton`; it
does not reference either codec as a package dependency:

| Capability | Pinned source identity | License and notices |
| --- | --- | --- |
| JBIG2 | `nicholsab/JBig2Decoder.NETStandard` tag `1.5.2`, commit `29a79f7f138bbcd01c077cef3120e555b8fda3d3` | The preserved `LICENSE` records the original IDRsolutions BSD-3-Clause terms and the C# port's MIT terms and copyrights. |
| JPEG 2000/JPX | `cinderblocks/CoreJ2K` tag `v2.3.3`, commit `4405df86bcd3c41bca9e0c12f3094209834bbba7` | BSD-3-Clause `LICENSE` plus the required preserved `COPYRIGHT-JJ2000-5.1` notice. |

The exact source snapshots live under `vendor/pdfcube/jbig2` and
`vendor/pdfcube/jpx`. Generation copies their C# source into the internal codec
area of `DripSharp.PdfCarton`, adds only compiler-warning and nullable wrappers, and
packs the license and copyright files under `THIRD-PARTY/`. Advancing either
pin requires re-running the image differential corpus and updating the recorded
identity and legal-file digests.
