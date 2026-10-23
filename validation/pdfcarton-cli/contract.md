# pdfcli contract

`pdfcli` is one command-line interface with two implementations: one over Java
Apache PDFBox 3.0.8 (the oracle) and one over PdfCarton. A comparison harness
runs both on the same inputs and compares their output and performance. This
document is the whole interface: a second implementation is written from it
alone, and the harness relies on nothing else.

The CLIs are validation tooling, not product. They do not change the PdfCarton
product goal, exclusions or shipped tests.

## Implementations

| `implementation.name` | Location                            | Run from the repository root               |
|-----------------------|-------------------------------------|--------------------------------------------|
| `pdfbox-jvm`          | `validation/pdfcarton-cli/jvm/`     | `clojure -M:pdfcli <command> [options] <input.pdf> [...]` |
| `pdfcarton-dotnet`    | `validation/pdfcarton-cli/dotnet/`  | `dotnet validation/pdfcarton-cli/dotnet/bin/Release/net10.0/DripSharp.PdfCarton.Tests.dll <command> [options] <input.pdf> [...]` |

The JVM implementation uses `org.apache.pdfbox:pdfbox:3.0.8` and
`org.apache.pdfbox:xmpbox:3.0.8` from Maven Central (the upstream version the
repository pins in `targets/pdfcube/baseline.edn`), plus
`org.apache.pdfbox:jbig2-imageio:3.0.5`, the JBIG2 decoder version PDFBox 3.0.8
itself tests with. All three are Apache-2.0. No JPEG 2000 ImageIO plugin is on
its classpath, so it reports JPX images as `decode-failed` (see `images`). Its
test is `clojure -M:pdfcli-test`, run from the repository root with
`research/pdfbox` checked out at its gitlink.

The .NET implementation is a net10.0 console app over the generated PdfCarton
family (`DripSharp.PdfCarton` and `DripSharp.PdfCarton.Xmp`), referenced as
projects under `PdfCartonRoot` as `validation/pdfcube-benchmarks` does. Its
assembly is named `DripSharp.PdfCarton.Tests`, the name PdfCarton grants
`InternalsVisibleTo`, because PdfCarton's `BufferedImage` `getRGB`/`setRGB`
bridge (`DripSharp.Runtime.PdfCartonFontCompat`) is not public API. It writes
PNGs with SkiaSharp from the image's stored samples, as ImageIO writes a
raster. Its test, `clojure -M:pdfcli-dotnet-test`, builds it and runs the JVM
implementation's contract tests against it, one process per invocation.

Where this contract names a PDFBox 3.0.8 API, that API's behaviour defines the
field or operation. PdfCarton is a translation of that API, so its
implementation calls the corresponding members; any other implementation must
reproduce the behaviour.

## README

Run from the repository root, with `research/pdfbox` checked out at its
gitlink for the test-resource PDFs.

| Implementation     | Build (one line)                                                                                                     | Run (one line)                                                                                          |
|--------------------|----------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| `pdfbox-jvm`       | none; the first `clojure -M:pdfcli` run fetches the Maven dependencies                                               | `clojure -M:pdfcli <command> [options] <input.pdf> [...]`                                               |
| `pdfcarton-dotnet` | `dotnet build validation/pdfcarton-cli/dotnet -c Release -warnaserror -p:PdfCartonRoot=$PWD/target/generated/pdfcarton` | `dotnet validation/pdfcarton-cli/dotnet/bin/Release/net10.0/DripSharp.PdfCarton.Tests.dll <command> [options] <input.pdf> [...]` |

- The .NET build needs a generated PdfCarton: `DRIPSHARP_WORKERS=22 clojure
  -J-Xmx28g -M:run generate pdfcube pdfcube-preflight` writes it to
  `target/generated/pdfcarton`. Without `-p:PdfCartonRoot` the build uses the
  `products/pdfcarton` checkout. Add `-p:NuGetAudit=false` when building
  offline.
- Tests: `clojure -M:pdfcli-test` (JVM) and `clojure -M:pdfcli-dotnet-test`
  (.NET; builds first against `PDFCARTON_ROOT`, default
  `target/generated/pdfcarton`).
- In timing mode the .NET implementation reports allocations with
  `GC.GetAllocatedBytesForCurrentThread()`, as above, and says so in a stderr
  line that also gives each iteration's process-wide
  `GC.GetTotalAllocatedBytes()` delta, so work on other threads is visible.

## Invocation

```
pdfcli <command> [options] <input.pdf> [...]
```

- The first argument is the command. A missing or unknown command is a `usage`
  error, and the error envelope's `command` is `null`.
- Every later argument that starts with `--` is an option, except after a bare
  `--`, which makes all remaining arguments positional. Any other argument is
  positional (an input path).
- An option that takes a value takes the next argument verbatim, even if it
  starts with `--`. There is no `--name=value` form. A missing value, an
  option the command does not accept, and an option given twice are `usage`
  errors.
- Options and positional arguments may be interleaved.
- Paths are used exactly as given, relative to the working directory, and
  echoed exactly as given wherever the output repeats them.
- Every command except `create` and `merge` takes exactly one input. `merge`
  takes one or more; `create` takes none. Any other count is a `usage` error.
- The timing options `--warmup N` and `--repeat M` are accepted by every
  command (see [Timing mode](#timing-mode)).
- Output paths must not name an input; the behaviour is then unspecified.

### Option value syntax

All value checks are `usage` errors and happen before any file is touched.

| Kind        | Accepted text                                                                                  |
|-------------|------------------------------------------------------------------------------------------------|
| integer     | ASCII digits only (`[0-9]+`), within the signed 32-bit range                                   |
| page range  | `N`, `N-M`, `N-` (to the last page) or `-M` (from page 1); `N`, `M` integers >= 1; `N <= M`; `-` alone is invalid |
| range list  | one or more page ranges separated by `,`; no empty items, no spaces                           |
| dpi         | `[0-9]+(\.[0-9]+)?`, parsed as an IEEE 754 binary32 value (round to nearest), > 0 and finite |
| angle       | optional `-` then ASCII digits, a signed 32-bit integer that is a multiple of 90              |
| string      | any text, including the empty string                                                           |

**Page selection.** A page range `[N, M]` (with `N-` meaning `M` = 2147483647
and `-M` meaning `N` = 1) is resolved against a document of `P` pages to
`start = max(N, 1)`, `end = min(M, P)`. When `start > end` the selection is
empty; commands that take `--pages` then process no page and still succeed.
Without `--pages` the selection is every page (`start = 1`, `end = P`).

## Output

- stdout carries exactly one JSON document, the envelope, on one line,
  followed by a single `\n`, encoded as UTF-8 without a byte-order mark. This
  holds for success and for every error, including `usage` errors.
- stderr is free-form diagnostics (library logging, warnings) and is never
  compared.
- The process exit code is `0` on success and the error's exit code otherwise.

### Canonical JSON

Both implementations produce byte-identical JSON for identical data:

- No whitespace outside strings. Object members appear in exactly the order
  this contract lists them; every listed member is always present, `null`
  when it has no value.
- Strings: `"` and `\` are escaped as `\"` and `\\`; U+0008, U+0009, U+000A,
  U+000C and U+000D as `\b`, `\t`, `\n`, `\f` and `\r`; other code points
  below U+0020 as `\u00XX` with lowercase hex digits. Every other character,
  including `/`, U+007F and all non-ASCII characters, is written literally in
  UTF-8. An unpaired UTF-16 surrogate is written as U+FFFD.
- Integers: optional `-` and decimal digits, no leading zeros, no fraction.
- Reals (values PDFBox holds as 32-bit `float`): the shortest decimal that
  rounds back to the same `float`, as Java's `Float.toString` (JDK 19 or
  later) chooses its digits: at least two significant digits are considered,
  and among the shortest candidates the one closest to the exact value wins.
  It is written in plain notation: no exponent, no `+`, a `0` before a leading
  `.`, no trailing fractional zeros, and no `.` for integral values. Negative
  zero is `0`; NaN and infinities are `null`. Examples: `612`, `595.276`,
  `0.1`, `-12.5`, `1.4`. (.NET's `float.ToString("R")` digits agree, except
  that when they have one significant digit the value rounded to two
  significant digits is used if it round-trips.)
- Milliseconds (timing only): the binary64 value rounded half-to-even to 3
  fractional digits, written in the same plain notation.
- `true`, `false`, `null` as usual. Arrays keep the order this contract gives.
- Hashes are lowercase hexadecimal SHA-256 digests.
- Object keys that come from documents (`info.custom`) are sorted in ordinal
  UTF-16 code unit order.

### Envelope

Success:

```json
{"ok":true,"command":"info","implementation":{...},"result":{...},"timing":null}
```

| Member           | Value                                                                              |
|------------------|------------------------------------------------------------------------------------|
| `ok`             | `true`                                                                             |
| `command`        | the command name                                                                   |
| `implementation` | object: `name` (table above), `library` (e.g. `"Apache PDFBox"`), `libraryVersion` (e.g. `"3.0.8"`), `runtime` (e.g. `"Java 25.0.2"`), all strings |
| `result`         | the command's result object (below)                                                |
| `timing`         | the timing object in timing mode, otherwise `null`                                 |

Failure:

```json
{"ok":false,"command":"info","implementation":{...},"error":{"code":"io","message":"...","exception":"..."}}
```

`command` is `null` when the command is missing or unknown. `error` holds
`code` (a string from the table below), `message` (human-readable text) and
`exception` (the implementation's exception type name).

### Error codes

The first failing check decides the error. Checks run in this order:

1. Argument parsing and option values (`usage`).
2. Every input path must name a readable regular file (`io`).
3. `--out-dir` is created with its parents if missing (`io` on failure);
   the parent directory of `--out` must exist (`io` otherwise).
4. `form-fill` reads its `--values` file (`io` if unreadable, `usage` if
   malformed).
5. The operation itself, including loading the input.
6. Reopening written outputs for the result summary (`operation-failed` on
   any failure).

| Code                      | Exit | Meaning                                                                                       |
|---------------------------|------|-----------------------------------------------------------------------------------------------|
| `usage`                   | 2    | Bad command line, bad option value, or a malformed `form-fill` values file                    |
| `io`                      | 3    | An input or values file is unreadable, or an output location cannot be created               |
| `invalid-password`        | 4    | Loading an input threw PDFBox `InvalidPasswordException`                                       |
| `owner-password-required` | 4    | `decrypt` opened the input without owner permission                                           |
| `load-failed`             | 5    | Loading an input threw anything else                                                          |
| `page-range`              | 1    | A `split` range selects no page                                                               |
| `not-encrypted`           | 1    | `decrypt` input is not encrypted                                                              |
| `no-acroform`             | 1    | `form-fill` input has no AcroForm                                                              |
| `field-not-found`         | 1    | `form-fill` names a field that `PDAcroForm.getField` does not find                             |
| `invalid-value`           | 1    | `form-fill` gives an array to a field that is not a choice field                              |
| `operation-failed`        | 1    | Any other failure after loading (any exception or error thrown by the operation)              |

"Loading" means opening an input named on the command line through the load
rule below. `merge` loads its inputs inside PDFMergerUtility, so a broken
`merge` input is `operation-failed`.

### Normalised fields

Two runs of one implementation on the same arguments produce identical
output, apart from the fields below. The comparison harness ignores these
fields when it compares the two implementations' JSON; everything else must
be equal.

| Field                                                        | Reason                                       |
|--------------------------------------------------------------|----------------------------------------------|
| `implementation` (whole object)                              | identifies the implementation                |
| `timing.iterationsMs`, `timing.medianMs`, `timing.allocatedBytes`, `timing.medianAllocatedBytes` | nondeterministic measurements |
| `error.message`, `error.exception`                           | implementation-specific text                 |
| `bytes` in write-command results (also in `split` parts)     | serialiser-specific file sizes               |
| `render`: `pages[].sha256`                                   | PNG encoder-specific bytes; rendering is compared by decoded pixels |

Written PDF files are not byte-compared: PDFBox writes a time-based `/ID`
and AES encryption uses random salts and IVs. The harness compares written
files by reopening them.

## Conventions shared by commands

**Load rule.** An input is opened with `Loader.loadPDF(File, password)`, where
the password is `--password` or the empty string, with PDFBox's default
loading settings (lenient parsing, default stream cache). Documents are
closed when the operation ends.

**Save rule.** Unless a command says otherwise, an output is written with
`PDDocument.save(File)`, which in PDFBox 3.0.8 uses
`CompressParameters.DEFAULT_COMPRESSION` (compressed object streams). An
existing output file is overwritten.

**Written summary.** Write commands report each written PDF by reopening it
(through the load rule, with the password the command names) after the
operation. Their result begins with:

| Member      | Type    | Value                                   |
|-------------|---------|-----------------------------------------|
| `output`    | string  | the `--out` path as given               |
| `bytes`     | integer | size of the written file in bytes       |
| `pageCount` | integer | `getNumberOfPages()` of the reopened file |

**Rectangles** are arrays `[lowerLeftX, lowerLeftY, upperRightX, upperRightY]`
of reals from the `PDRectangle` getters.

**Resource walk.** `images` and `fonts` visit the resources of each selected
page (`PDPage.getResources()`, which is inherited and may be absent) as
follows. `visit(R, prefix)`: report what the command collects from the
dictionary `R` itself; then, for each key of `R`'s `/XObject` dictionary in
ordinal order of the name text, look at the resolved value: if it is a stream
whose `/Subtype` is `/Image`, the command may report it as `prefix + name`;
if it is a stream whose `/Subtype` is `/Form` and that same stream object is
not already on the current descent path, call
`visit(PDFormXObject(stream).getResources(), prefix + name + "/")` (a form
without `/Resources` contributes nothing). The page itself starts with
prefix `""`. Annotation appearances, patterns, Type 3 glyphs and inline images
are not visited.

**Pixel hash.** The SHA-256 of an image's pixels is computed over its rows top
to bottom, each pixel left to right as four bytes R, G, B, A taken from the
32-bit ARGB value `BufferedImage.getRGB(x, y)` returns.

**Output file names** inside `--out-dir` are fixed by each command; numbers
are 1-based, written in ASCII digits whatever the locale, and zero-padded to
the stated width (wider when needed).

## Timing mode

Giving `--warmup N` (default 0), `--repeat M` (default 1, must be >= 1, else
`usage`), or both switches on timing mode. The command then executes its
operation `N` times without measuring, and then `M` times measuring each
iteration, in one process. The result object is the one from the last
measured iteration; the summary step (reopening outputs, hashing written
PNGs) runs once afterwards.

The **operation** of every command is everything it does to produce its
result: loading inputs, processing, writing every output file, and closing
documents. It excludes process startup, argument parsing, the input and
output checks, reading the `form-fill` values file, the written-output
summary, the `render` PNG hashes, and JSON serialisation.

```json
"timing":{"warmup":2,"repeat":5,"iterationsMs":[...],"medianMs":12.345,"allocatedBytes":[...],"medianAllocatedBytes":1234567}
```

| Member                 | Type          | Value                                                                    |
|------------------------|---------------|--------------------------------------------------------------------------|
| `warmup`               | integer       | `N`                                                                      |
| `repeat`               | integer       | `M`                                                                      |
| `iterationsMs`         | milliseconds[] | wall time of each measured iteration, in order, from a monotonic clock |
| `medianMs`             | milliseconds  | median of the unrounded durations; for even `M` the mean of the two middle values |
| `allocatedBytes`       | integer[]     | bytes allocated by the executing thread during each measured iteration  |
| `medianAllocatedBytes` | integer       | median of `allocatedBytes`; for even `M`, `lo + floor((hi - lo) / 2)` of the two middle values |

Allocation is the difference of the current thread's allocation counter
around the iteration: `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes()`
on the JVM, `GC.GetAllocatedBytesForCurrentThread()` on .NET. Allocations
on other threads are not counted. Cold (process) wall time is measured by
the caller, not reported here.

## Commands

### info

`pdfcli info [--password P] <input.pdf>`

| Member          | Type            | Value                                                                       |
|-----------------|-----------------|-----------------------------------------------------------------------------|
| `version`       | real            | `PDDocument.getVersion()` (the later of header and catalog `/Version` from 1.4 on) |
| `headerVersion` | real            | `COSDocument.getVersion()` (the header version)                             |
| `pageCount`     | integer         | `getNumberOfPages()`                                                        |
| `encrypted`     | boolean         | `isEncrypted()`                                                             |
| `encryption`    | object or null  | `null` unless encrypted; else `filter` (string or null, `PDEncryption.getFilter()`), `version` (`getVersion()`), `revision` (`getRevision()`), `keyLength` (`getLength()`, 40 when `/Length` is absent), `permissions` (`getPermissions()`, the signed `/P` value, 0 when absent), `ownerPermission` (`getCurrentAccessPermission().isOwnerPermission()`) |
| `info`          | object or null  | `null` when the trailer has no `/Info` dictionary; else the members `Title`, `Author`, `Subject`, `Keywords`, `Creator`, `Producer`, `CreationDate`, `ModDate`, `Trapped`, then `custom` |
| `xmp`           | object or null  | `null` when the catalog has no `/Metadata` stream; else the XMP summary     |
| `pages`         | array           | one object per page in order: `page` (1-based integer), `mediaBox` (`PDPage.getMediaBox()`), `cropBox` (`getCropBox()`), both rectangles, and `rotation` (integer, `getRotation()`) |

Each `info` member is the resolved value of that key in the `/Info`
dictionary: a string's text (`COSString.getString()`, so dates stay raw
`D:...` strings), a name's text for name values, `null` when absent or of any
other type. `custom` is an object of every other key of the dictionary, keys
in ordinal order, values by the same rule.

XMP summary, from the decoded `/Metadata` stream bytes:

| Member            | Type               | Value                                                                  |
|-------------------|--------------------|------------------------------------------------------------------------|
| `length`          | integer or null    | decoded byte count; `null` when decoding throws                        |
| `sha256`          | string or null     | hash of the decoded bytes; `null` when decoding throws                 |
| `parsed`          | boolean            | xmpbox `DomXmpParser` with `setStrictParsing(false)` parsed the bytes   |
| `schemas`         | string[] or null   | distinct non-null `getNamespace()` of `XMPMetadata.getAllSchemas()`, ordinal order; `null` when not parsed |
| `dcTitle`         | string or null     | `getDublinCoreSchema().getTitle()`                                    |
| `dcCreators`      | string[] or null   | `getDublinCoreSchema().getCreators()`                                 |
| `pdfProducer`     | string or null     | `getAdobePDFSchema().getProducer()`                                   |
| `xmpCreatorTool`  | string or null     | `getXMPBasicSchema().getCreatorTool()`                                |
| `pdfaPart`        | integer or null    | `getPDFAIdentificationSchema().getPart()`                             |
| `pdfaConformance` | string or null     | `getPDFAIdentificationSchema().getConformance()`                      |

The last six are `null` when not parsed, when the schema is absent, or when
the getter throws.

### text

`pdfcli text [--password P] [--pages RANGE] [--sort] <input.pdf>`

Runs a new `PDFTextStripper` with `setSortByPosition(--sort given)`,
`setLineSeparator("\n")`, `setPageEnd("\n")`, `setStartPage(start)`,
`setEndPage(end)` and otherwise default settings, and calls `getText`. An
empty selection yields `""` without running the stripper.

| Member           | Type    | Value                                   |
|------------------|---------|-----------------------------------------|
| `startPage`      | integer | resolved `start`                        |
| `endPage`        | integer | resolved `end` (may be below `start`)   |
| `sortByPosition` | boolean | whether `--sort` was given              |
| `text`           | string  | the extracted text                      |

### render

`pdfcli render [--password P] [--pages RANGE] [--dpi D] [--image-type T] (--out-dir DIR | --no-write) <input.pdf>`

Exactly one of `--out-dir` and `--no-write` is required. `--dpi` defaults to
72. `--image-type` is `rgb` (default), `argb`, `gray` or `binary`, meaning
`ImageType.RGB`, `ARGB`, `GRAY`, `BINARY`. One `PDFRenderer` renders each
selected page in order with `renderImageWithDPI(pageIndex, dpi, imageType)`.
Unless `--no-write` is given, each image is written as PNG to
`DIR/page-NNNN.png` (`NNNN` = page number, width 4); writing is part of the
operation.

| Member      | Type           | Value                                         |
|-------------|----------------|-----------------------------------------------|
| `dpi`       | real           | the dpi used                                  |
| `imageType` | string         | the image type name used                      |
| `outDir`    | string or null | `--out-dir` as given; `null` with `--no-write` |
| `pages`     | array          | per selected page: `page` (integer), `width`, `height` (integers, of the rendered image), `file` (file name within `outDir`, or `null`), `sha256` (hash of the PNG file bytes, or `null`) |

Rendered pages are compared by decoding the PNGs: the sizes must match, and
the pixels must be "fairly close" by the group's tolerance.

### images

`pdfcli images [--password P] [--pages RANGE] [--out-dir DIR] <input.pdf>`

Reports every image XObject the resource walk reaches on the selected pages,
in walk order. Each entry is loaded with `PDResources.getXObject(name)` on the
resources dictionary that holds it, then decoded with
`PDImageXObject.getImage()`. With `--out-dir`, each decoded image is written
as PNG to `DIR/page-PPPP-NNN.png` (`PPPP` = page, width 4; `NNN` = the
image's 1-based position among that page's entries, width 3) as part of the
operation.

| Member             | Type             | Value                                                                       |
|--------------------|------------------|-----------------------------------------------------------------------------|
| `page`             | integer          | page number                                                                 |
| `name`             | string           | walk path, e.g. `Im1` or `Fm0/Im1`                                          |
| `width`            | integer          | stream `/Width`, -1 when absent                                             |
| `height`           | integer          | stream `/Height`, -1 when absent                                            |
| `colorSpace`       | string or null   | `getColorSpace().getName()`; `null` when loading failed or it throws        |
| `bitsPerComponent` | integer or null  | `getBitsPerComponent()`; `null` when loading failed or it throws            |
| `filters`          | string[]         | the stream's `/Filter` names in order (a single name gives one element; absent gives `[]`) |
| `stencil`          | boolean          | `isStencil()`; `false` when loading failed                                  |
| `suffix`           | string or null   | `getSuffix()`; `null` when loading failed or it throws                      |
| `decodedSha256`    | string or null   | pixel hash of the decoded image; `null` when not decoded                    |
| `file`             | string or null   | written file name, or `null`                                                |
| `error`            | string or null   | `"load-failed"` when `getXObject` threw, `"decode-failed"` when `getImage()` threw, else `null` |

A per-image error does not fail the command.

### fonts

`pdfcli fonts [--password P] [--pages RANGE] <input.pdf>`

For each resources dictionary the walk visits, reports each key of its
`/Font` dictionary whose resolved value is a dictionary, in ordinal key
order, before descending into forms. Each font is loaded with
`PDResources.getFont(name)`.

| Member      | Type             | Value                                                                         |
|-------------|------------------|-------------------------------------------------------------------------------|
| `page`      | integer          | page number                                                                   |
| `name`      | string           | walk path of the font resource, e.g. `F1` or `Fm0/F1`                         |
| `baseFont`  | string or null   | the font dictionary's `/BaseFont` name                                        |
| `subtype`   | string or null   | the font dictionary's `/Subtype` name                                         |
| `embedded`  | boolean or null  | `PDFont.isEmbedded()`; `null` when loading failed                             |
| `encoding`  | string or null   | for `PDType0Font`, `getCMap().getName()`; for other `PDSimpleFont`s (Type 1, TrueType, Type 3), `getEncoding().getEncodingName()` (PDFBox names such as `WinAnsiEncoding` or `built-in (Type 1) with differences`); `null` when absent, when loading failed, or when it throws |
| `toUnicode` | boolean          | the font dictionary has a `/ToUnicode` key                                    |
| `error`     | string or null   | `"load-failed"` when `getFont` threw or returned null, else `null`            |

### outline

`pdfcli outline [--password P] <input.pdf>`

| Member       | Type    | Value                                                                    |
|--------------|---------|--------------------------------------------------------------------------|
| `hasOutline` | boolean | `getDocumentCatalog().getDocumentOutline()` is not null                  |
| `items`      | array   | the outline's children (`getFirstChild()`, then `getNextSibling()`), `[]` without an outline |

Each item: `title` (`getTitle()`, string or null), `page` (1-based index of
`findDestinationPage(document)` in the page tree, `null` when it is null, not
in the tree, or throws), `action` (`getAction().getSubType()`, string or
null, also `null` when it throws), `open` (`isNodeOpen()`), `children` (the
item's own children, by the same rule). An item dictionary already reported
anywhere in the outline is not reported again, and its sibling chain stops
there.

### annotations

`pdfcli annotations [--password P] [--pages RANGE] <input.pdf>`

| Member        | Type  | Value                                                    |
|---------------|-------|----------------------------------------------------------|
| `annotations` | array | for each selected page in order, each `PDPage.getAnnotations()` entry in order |

Each entry: `page` (integer), `subtype` (`getSubtype()`, string or null),
`rect` (`getRectangle()`, rectangle or null), `contents` (`getContents()`,
string or null), `name` (`getAnnotationName()`, the `/NM` string or null),
`flags` (`getAnnotationFlags()`, integer).

### form-fields

`pdfcli form-fields [--password P] <input.pdf>`

The AcroForm is `getDocumentCatalog().getAcroForm()` (the no-argument form,
which applies PDFBox's default AcroForm fixup).

| Member            | Type    | Value                                                         |
|-------------------|---------|---------------------------------------------------------------|
| `hasAcroForm`     | boolean | the AcroForm is not null                                      |
| `needAppearances` | boolean | `getNeedAppearances()`; `false` without an AcroForm           |
| `fields`          | array   | pre-order walk: each field of `getFields()` in order, each followed by its `PDNonTerminalField.getChildren()` recursively; `[]` without an AcroForm |

Each field:

| Member     | Type                     | Value                                                                     |
|------------|--------------------------|---------------------------------------------------------------------------|
| `name`     | string                   | `getFullyQualifiedName()`                                                 |
| `type`     | string or null           | `getFieldType()` (inherited `/FT`)                                        |
| `kind`     | string                   | by PDFBox class: `text` (PDTextField), `checkbox` (PDCheckBox), `radio` (PDRadioButton), `pushbutton` (PDPushButton), `combo` (PDComboBox), `list` (PDListBox), `signature` (PDSignatureField), `nonterminal` (PDNonTerminalField), else `other` |
| `value`    | string, string[] or null | `text`: `PDTextField.getValue()`; `checkbox`/`radio`: `PDButton.getValue()`; `combo`/`list`: `PDChoice.getValue()` as an array; others `null` |
| `options`  | string[] or null         | `checkbox`/`radio`: `getOnValues()` sorted in ordinal order; `combo`/`list`: `getOptions()` in order; others `null` |
| `flags`    | integer                  | `getFieldFlags()`                                                         |
| `readOnly` | boolean                  | `isReadOnly()`                                                            |
| `required` | boolean                  | `isRequired()`                                                            |

### form-fill

`pdfcli form-fill [--password P] --values VALUES.json --out OUT.pdf <input.pdf>`

`VALUES.json` is a UTF-8 JSON object mapping fully qualified field names to a
string or an array of strings. Anything else (not an object, another value
type, duplicate keys, invalid JSON, anything but whitespace after the object)
is a `usage` error. The fields are set in
ordinal order of their names: `getAcroForm().getField(name)` (`no-acroform`
without an AcroForm, `field-not-found` when it returns null); a string calls
`PDField.setValue(String)`; an array calls `PDChoice.setValue(List)` on a
choice field and is `invalid-value` on any other field. Exceptions from
`setValue` (for example a checkbox value that is not an on-value, or a list
for a field that is not multi-select) are `operation-failed`. The document is
then saved to `OUT.pdf`.

Result: the written summary (reopened with `--password`), then `fields`: for
each name in the order set, from the reopened document's AcroForm, an object
with `name`, `kind` and `value` as in `form-fields`.

### attachments

`pdfcli attachments [--password P] [--out-dir DIR] <input.pdf>`

Lists the catalog's `/Names` `/EmbeddedFiles` name tree. A node yields its
`/Names` pairs in order (pairs whose key is not a string are skipped), then
the entries of each `/Kids` dictionary in order; a node dictionary is visited
at most once. For a dictionary value the file specification is
`PDComplexFileSpecification`; the embedded file is the first non-null of
`getEmbeddedFileUnicode()`, `getEmbeddedFile()`, `getEmbeddedFileDos()`,
`getEmbeddedFileMac()`, `getEmbeddedFileUnix()`. With `--out-dir`, each
embedded file's decoded bytes are written to `DIR/NNN-NAME` (`NNN` = the
entry's 1-based position, width 3; `NAME` = `fileName`, else the tree key,
else `attachment`, with every code point outside `A-Z`, `a-z`, `0-9`, `.`,
`_`, `-` replaced by `_`) as part of the operation.

| Member        | Type  | Value        |
|---------------|-------|--------------|
| `attachments` | array | entries in tree order |

Each entry: `name` (tree key), `fileName` (`getFilename()`; for a string
value, its text; else `null`), `description` (`getFileDescription()` or
null), `subtype` (embedded file `getSubtype()` or null), `size` (integer
`/Params` `/Size`, or null), `length` (decoded byte count or null), `sha256`
(hash of the decoded bytes or null), `file` (written file name or null).

### merge

`pdfcli merge --out OUT.pdf <input.pdf> [<input.pdf> ...]`

A `PDFMergerUtility` gets `addSource(File)` for each input in order,
`setDestinationFileName(OUT.pdf)`, and
`mergeDocuments(IOUtils.createMemoryOnlyStreamCache())` (default merge mode
and compression). `--password` is not accepted.

Result: the written summary (reopened without password), then `inputCount`
(integer).

### split

`pdfcli split [--password P] --ranges LIST --out-dir DIR <input.pdf>`

Each range of `LIST` is resolved against the page count; if any resolved
range is empty the command fails with `page-range` before writing anything.
For each range `[s, e]` in order, a new `Splitter` with `setStartPage(s)`,
`setEndPage(e)` and `setSplitAtPage(e - s + 1)` splits the document; its
single result is saved to `DIR/part-NNN.pdf` (`NNN` = the range's 1-based
position, width 3) and every result document is closed. Ranges may overlap.

| Member   | Type   | Value                                                                    |
|----------|--------|--------------------------------------------------------------------------|
| `outDir` | string | `--out-dir` as given                                                     |
| `parts`  | array  | per range: `range` (`"s-e"` of the resolved range), `file`, `bytes`, `pageCount` (of the part reopened with `--password`) |

### rotate

`pdfcli rotate [--password P] --angle A [--pages RANGE] --out OUT.pdf <input.pdf>`

For each selected page, `setRotation(((getRotation() + A) mod 360 + 360) mod 360)`;
then save. Result: the written summary (reopened with `--password`), then
`rotations`: `getRotation()` of every page of the reopened file, in order.

### encrypt

`pdfcli encrypt [--password P] --owner-password O [--user-password U] [--key-length 40|128|256] [--permissions LIST] --out OUT.pdf <input.pdf>`

`--user-password` defaults to the empty string and `--key-length` to 256.
`--permissions` is a comma-separated list of granted permissions, possibly
empty (`""` grants none): `print` (`setCanPrint`), `modify`
(`setCanModify`), `extract` (`setCanExtractContent`), `annotate`
(`setCanModifyAnnotations`), `fill-in` (`setCanFillInForm`),
`extract-for-accessibility` (`setCanExtractForAccessibility`), `assemble`
(`setCanAssembleDocument`), `print-faithful` (`setCanPrintFaithful`); an
unknown name is a `usage` error. Starting from `new AccessPermission()` (all
granted), each of the eight setters is called with whether its name is in the
list; without `--permissions` none is called. The document is protected with
`new StandardProtectionPolicy(O, U, permissions)`,
`setEncryptionKeyLength(keyLength)`, `setPreferAES(true)` and
`PDDocument.protect(policy)`, then saved.

Result: the written summary (reopened with the owner password), then
`encryption`: the reopened document's encryption object as in `info`,
without `ownerPermission`.

### decrypt

`pdfcli decrypt [--password P] --out OUT.pdf <input.pdf>`

Fails with `not-encrypted` when `isEncrypted()` is false, then with
`owner-password-required` when `getCurrentAccessPermission().isOwnerPermission()`
is false. Otherwise calls `setAllSecurityToBeRemoved(true)` and saves.

Result: the written summary (reopened without password), then `encrypted`
(`isEncrypted()` of the reopened file).

### create

`pdfcli create [--text TEXT] --out OUT.pdf`

Builds a new `PDDocument` with one `new PDPage(PDRectangle.LETTER)` and, on
it, through one `new PDPageContentStream(document, page)`, in this order:

1. `setNonStrokingColor(0.2f, 0.4f, 0.8f)`, `setStrokingColor(0f, 0f, 0f)`,
   `setLineWidth(2f)`, `addRect(72f, 620f, 200f, 60f)`, `fillAndStroke()`.
2. `drawImage(image, 72f, 520f, 64f, 64f)`, where `image` is
   `LosslessFactory.createFromImage(document, img)` and `img` is a 16x16
   `TYPE_INT_RGB` image whose pixel `(x, y)` (top-left origin) has red
   `16x`, green `16y` and blue 128.
3. `beginText()`, `setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)`,
   `newLineAtOffset(72f, 720f)`, `showText(TEXT)`, `endText()`. `TEXT`
   defaults to `Hello from pdfcli`; text Helvetica's WinAnsi encoding cannot
   represent is `operation-failed`.

The stream is closed and the document saved. Result: the written summary
(reopened without password).

### save

`pdfcli save [--password P] [--compress] --out OUT.pdf <input.pdf>`

Saves with `PDDocument.save(File, CompressParameters.DEFAULT_COMPRESSION)`
when `--compress` is given, else with `CompressParameters.NO_COMPRESSION`.
Result: the written summary (reopened with `--password`), then `compressed`
(boolean).
