# PdfCube Dependency Mappings

## Purpose

This document records approved dependency and platform mappings for the five
PdfCube source modules. SkiaSharp is the only approved third-party product
dependency; .NET standard-library and Microsoft-package mappings remain allowed.

The absence of a resolved mapping is not a product exclusion. It is blocking
product work under the [authoritative product goal](product-goal.md).

The initial source baseline is Apache PDFBox `3.0.8`. Dependency versions and
usage must be re-audited whenever PdfCube moves to a later stable upstream
release.

## PdfCube Package Dependencies

The selected upstream libraries become separately packaged PdfCube libraries:

| Source module | Destination package | PdfCube dependencies |
| --- | --- | --- |
| `pdfbox-io` | `PdfCube.IO` | None. |
| `fontbox` | `PdfCube.FontBox` | `PdfCube.IO`. |
| `xmpbox` | `PdfCube.XmpBox` | None. |
| `pdfbox` | `PdfCube.PdfBox` | `PdfCube.IO`, `PdfCube.FontBox`. |
| `preflight` | `PdfCube.Preflight` | `PdfCube.PdfBox`, `PdfCube.XmpBox`. |

These are project/package references, not compatibility helpers. Their public
types and behavior are mechanically translated with their owning modules.

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
PdfCube libraries must not require a particular logging provider.

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
and security-provider integration. PdfCube should implement that behavior with
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
`SKBitmap`, and `Graphics2D` to a PdfCube compatibility facade over `SKCanvas`.
Use the CPU raster backend for canonical differential validation.

SkiaSharp is not a Java2D or general `Raster` implementation. PdfCube must keep
an internal managed raster model for packed, indexed, CMYK, DeviceN, and other
arbitrary-component data before conversion to a Skia image. SkiaSharp's ICC
support may accelerate representable RGB profiles but does not replace full ICC
parsing, CMYK/LUT transforms, or Preflight metadata validation.

Official `SkiaSharp.NativeAssets.*` packages required for supported hosts are
deployment artifacts of this approved dependency.

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

The following upstream dependencies do not become PdfCube runtime package
dependencies:

| Source dependency | Treatment |
| --- | --- |
| JUnit Jupiter | Adapt relevant upstream assertions and fixtures to the selected Microsoft .NET test infrastructure. |
| Commons IO in Preflight tests | Use `System.IO` and focused test helpers. |
| Java Diff Utils | Use focused comparison/reporting test helpers; no product dependency. |
| Mockito | Use focused test doubles for the small mocked surface; no product dependency. |
| Log4j Core and Log4j JCL | Use the selected `Microsoft.Extensions.Logging` test provider or an in-memory test sink. |
| Maven parent POMs and build plugins | Use them only for upstream project discovery, source/resource resolution, and oracle execution. They are not translated or shipped. |
| Downloaded PDFs, fonts, and validation corpora | Retain as checksum-pinned behavior evidence when permitted; they are not product dependencies. |

Image-codec dependencies used in upstream tests do not become PdfCube runtime
dependencies; their cases validate SkiaSharp or PdfCube's internal decoders.

## Required Internal Capabilities

SkiaSharp does not supply the following behavior, which must be implemented or
mechanically translated inside PdfCube without another third-party dependency:

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
under Apache-2.0, MIT, BSD, or ISC terms. Such source is built into PdfCube
rather than referenced as another package dependency; its pinned upstream
identity, license, copyright, and required notices must be preserved. Source
under other license terms requires explicit approval.
