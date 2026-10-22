# PdfCarton pdfbox-* differentials

Each `dripsharp.pdfcube.pdfbox-*-differential` namespace compiles and runs an
unchanged Java oracle against the pinned PDFBox 3.0.8 sources and a .NET probe
against PdfCarton, then requires the two normalized traces to match (rendering
compares RGBA pixels within its explicit tolerances).

| Namespace suffix     | Validation directory                  | Project-reference probe                                       |
|----------------------|---------------------------------------|---------------------------------------------------------------|
| `low-level`          | `pdfcube-pdfbox`                      | `DripSharp.PdfCarton.LowLevelProbe.csproj`                    |
| `bidi`               | `pdfcube-pdfbox-bidi`                 | `extraction/DripSharp.PdfCarton.BidiExtractionProbe.csproj`   |
| `document-lifecycle` | `pdfcube-pdfbox-document-lifecycle`   | `DripSharp.PdfCarton.DocumentLifecycleProbe.csproj`           |
| `font-text`          | `pdfcube-pdfbox-font-text`            | `DripSharp.PdfCarton.FontTextProbe.csproj`                    |
| `graphics`           | `pdfcube-pdfbox-graphics`             | `DripSharp.PdfCarton.GraphicsProbe.csproj`                    |
| `image`              | `pdfcube-pdfbox-image` (own README)   | `DripSharp.PdfCarton.ImageProbe.csproj`                       |
| `interaction`        | `pdfcube-pdfbox-interaction`          | `DripSharp.PdfCarton.InteractionProbe.csproj`                 |
| `interchange`        | `pdfcube-pdfbox-interchange`          | `DripSharp.PdfCarton.InterchangeProbe.csproj`                 |
| `manipulation`       | `pdfcube-pdfbox-manipulation`         | `DripSharp.PdfCarton.ManipulationProbe.csproj`                |
| `printing`           | `pdfcube-pdfbox-printing`             | `DripSharp.PdfCarton.PrintingProjectProbe.csproj`             |
| `rendering`          | `pdfcube-pdfbox-rendering`            | `DripSharp.PdfCarton.RenderingProbe.csproj`                   |
| `security`           | `pdfcube-pdfbox-security`             | `DripSharp.PdfCarton.SecurityProbe.csproj`                    |

`dripsharp.pdfcube.pdfbox-differential` is the aggregate: it runs the
low-level, document-lifecycle, manipulation, font-text, rendering, interaction,
security and printing workflows. The bidi direct comparison always builds
`DripSharp.PdfCarton.BidiProbe.csproj` from the authored runtime sources; only
its extraction half uses PdfCarton.

## Local run against the generated project

From a worktree with `research/pdfbox` at the pinned revision (generation also
needs `products/pdfcarton` at its gitlink for the pinned suite fixtures), and
after checking available RAM and CPU:

```sh
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run generate pdfcube pdfcube-pdfbox
clojure -J-Xmx8g -M -e \
  "(require 'dripsharp.pdfcube.pdfbox-graphics-differential)
   (dripsharp.pdfcube.pdfbox-graphics-differential/verify! {:generated-project? true})"
```

Replace `graphics` with any suffix above, or require
`dripsharp.pdfcube.pdfbox-differential` to run every aggregate workflow in this
mode. The first command regenerates `target/generated/pdfcarton` and
`target/maven-reactor-inputs-0.tsv`; skip it to reuse an existing generation.
The second builds the checked-in project-reference probe against that staging,
compiles and runs the Java oracle from the inputs the manifest records, and
exits non-zero on any difference. `:maven-manifest` selects another manifest.
A missing generation fails with the generate command; a `research/pdfbox`
checkout at another revision is rejected before anything runs. This mode claims
neither clean generation nor package consumption, so the aggregate skips its
package-contract check and security skips its packed crypto-closure check.

## Package proof

`(verify!)` without options packs `pdfcube-pdfbox` through the PdfCarton
target and runs the probe in an isolated NuGet-only consumer. Packing stamps
the exact generated product commit, so it requires `products/pdfcarton` to be
synchronized with the generated staging and its publication origin.

Evidence goes to `validation-output/pdfcube-pdfbox-<suffix>-differential`
(`pdfcube-pdfbox-differential` for the aggregate). Process-free checks:

```sh
clojure -M:test --namespace dripsharp.pdfcube-generated-project-test
```
