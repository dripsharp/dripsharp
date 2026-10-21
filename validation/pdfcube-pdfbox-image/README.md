# PdfCarton image codec differential

Compares the pinned PDFBox 3.0.8 sources (with jbig2-imageio 3.0.5 and
jai-imageio 1.4.0 from the local Maven cache) against PdfCarton on the
authoritative JBIG2 and JPX fixtures in
`research/pdfbox/tools/src/test/resources/input/ImageIOUtil`. Both sides write
a normalized trace of codec metadata, full pixels, region/subsampling reads and
a malformed-JPX failure; the traces must be identical.

## Local run against the generated project

From a worktree with `research/pdfbox` checked out at the pinned revision, and
after checking available RAM and CPU:

```sh
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run generate pdfcube pdfcube-pdfbox
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M -e \
  "(require 'dripsharp.pdfcube.pdfbox-image-differential)
   (dripsharp.pdfcube.pdfbox-image-differential/verify! {:generated-project? true})"
```

The first command regenerates `target/generated/pdfcarton` and
`target/maven-reactor-inputs-0.tsv`; skip it to reuse an existing generation.
The second builds the checked-in project-reference probe
(`DripSharp.PdfCarton.ImageProbe.csproj`) against that staging, compiles and
runs the Java oracle from the inputs the manifest records, and exits non-zero
on any trace difference. `:maven-manifest` selects another manifest. This mode
claims neither clean generation nor package consumption.

## Package proof

`(verify!)` without options packs `pdfcube-pdfbox` through the PdfCarton
target and runs the same probe in an isolated NuGet-only consumer. Packing
stamps the exact generated product commit, so it requires `products/pdfcarton`
to be synchronized with the generated staging; the complete family proof
(`DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run proof pdfcube`) runs it the same way.

Evidence goes to `validation-output/pdfcube-pdfbox-image-differential`.
Process-free checks:

```sh
clojure -M:test --namespace dripsharp.pdfcube-pdfbox-image-differential-test
```
