# PdfCarton Skia regression probe

Run against the generated PdfCarton checkout:

```sh
dotnet run --project validation/pdfcube-skia-performance -c Release
```

The probe checks raster pixel formats, premultiplied reads against Skia, padded
rows, managed raster copies, drawing after raster mutation, transformed and
curved clips, thick strokes, bounded paint requests, multiply blending, and
binary drawing. It then measures bulk raster writes and small blends on a
larger bitmap. Timings are reported for comparison, not used as pass/fail limits.

Use `-- --benchmark` to run only the measurements. Override the generated
checkout with `-p:PdfCartonRoot=/absolute/path/to/pdfcarton` when necessary.

Run correctness checks without stopwatch measurements:

```sh
dotnet run --project validation/pdfcube-skia-performance -c Release -- --regressions-only
```
