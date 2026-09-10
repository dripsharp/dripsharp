# PdfCarton benchmarks

BenchmarkDotNet measures raster access, small multiply blends, and warm rendering
of existing upstream PDF fixtures. Run from the DripSharp repository root with
the .NET 10 SDK and the `products/pdfcarton` and `research/pdfbox` checkouts present.

```sh
dotnet run --project validation/pdfcube-benchmarks -c Release -- \
  --filter '*' --artifacts validation-output/pdfcarton-benchmarks
```

The default BenchmarkDotNet job performs pilot, warm-up, and measured iterations
in separate benchmark processes. Keep the machine idle during measurements.
Use `--job short` for an exploratory run; its confidence intervals can be wide.
Use `--job dry` only to check execution, never for performance conclusions.
Reports include time, error, standard deviation, GC counts, and managed allocated
bytes. Managed allocation counts do not include Skia's native bitmap memory.

Select individual classes with `--filter '*RasterBenchmarks*'`,
`--filter '*SmallBlendBenchmarks*'`, or `--filter '*RenderingBenchmarks*'`.
Benchmark setup and cleanup are excluded from timings. Read results into reused
buffers; writes consume a prebuilt sample buffer. Small blends report per-blend
cost from a batch of nonoverlapping rectangles, with the destination reset before
each iteration. These results are not directly comparable to the older stopwatch
probe's three-operation totals and first-use overhead.

Rendering uses page zero at 72 DPI from survey.pdf (text),
tiger-as-form-xobject.pdf (vector), jpegrgb.pdf (image), and PDFBOX-3195.pdf
(transparency). The document and renderer are reused; repeated renders exercise
warm document/font/image caches. Output bitmap disposal is included. This is a
small, varied diagnostic set, not an exhaustive corpus or an end-to-end
load/render/save benchmark. Fixtures are used in place, not copied or downloaded.
Set `PDFBOX_RESOURCE_ROOT` to the PDFBox `pdfbox/src/test/resources` directory
when running outside the repository.

## CPU profiling

```sh
dotnet run --project validation/pdfcube-benchmarks -c Release -- \
  --filter '*RenderingBenchmarks*' --job short --profiler EP \
  --artifacts validation-output/pdfcarton-profiles
```

BenchmarkDotNet's [EventPipe profiler](https://benchmarkdotnet.org/articles/features/event-pipe-profiler.html)
collects a separate profiling run and exports `.nettrace` and `.speedscope.json`
files. Inspect benchmark workload stacks rather than startup/setup samples.
Inclusive stack costs overlap and must not be added together. EventPipe is most
useful for managed call paths; native Skia/codec internals may appear only as
transition frames, so these traces do not provide a complete native CPU profile.

## Comparing revisions

Run the actual revisions immediately before and after the Skia optimization:

```sh
python3 validation/pdfcube-benchmarks/compare-revisions.py \
  --output validation-output/pdfcarton-old-new-comparison
```

The output directory must be new. The script archives product commits
`e235b924c9a52f23603e02a9aa7ff80bed3c98e4` and
`e9930c79165d077df0593981947810fdf5c410c9`, freezes one identical benchmark harness,
and runs the default BenchmarkDotNet job sequentially against isolated builds.
It keeps source snapshots, exact commands/revisions, logs, and statistical
reports. `--before` and `--after` select other product refs; `--operations raster`
or `--operations blend` limits the work; `--job short` is exploratory only.

Historical raster inputs use varying RGB with opaque alpha, starting from an
opaque bitmap. Setup verifies the readback. Transparent component-at-a-time
writes had different behavior in the old implementation, so they would not be
an equivalent performance comparison. Historical blend timings are explicitly
**page reset plus one 8x8 multiply blend**: both revisions include the same
1024x1024 erase of an ARGB bitmap with opaque contents, allowing the harness to select invocation counts appropriate
to their very different costs. These are not bare-blend timings.
An RGB destination is unsuitable for this historical comparison: its old packed
writer interpreted the unused alpha byte as transparent and failed the expected
pixel check. The ARGB case must still pass the same expected color assertion.

Use the same benchmark source, SDK, fixtures, configuration, and hardware for
both revisions. Build against an isolated generated checkout with the environment
variable `PdfCartonRoot=/absolute/path/to/pdfcarton` preceding `dotnet run`.
It is inherited by BenchmarkDotNet's child builds; an outer `-p:` argument alone
does not propagate to them. Use separate artifact
directories and avoid concurrent measurements. Rebuild after changing the
referenced revision; do not reuse a `--no-build` binary from the other revision.
Compare distributions and reported error, not only a single mean. The project
does not set timing thresholds or claim whole-document speedups from raster
microbenchmarks.

The 4,096-draw blend batch targets the current bounded-layer implementation.
Before the rendering optimization, every small blend processed the entire page;
reduce the batch identically on both revisions before comparing that historical
path, or iterations can take an impractically long time.

## Candidate raster strategies

```sh
dotnet run --project validation/pdfcube-benchmarks -c Release -- \
  --filter '*RasterStrategyBenchmarks*' \
  --artifacts validation-output/pdfcarton-raster-strategies
```

These benchmark-only experiments compare the current JavaRaster implementation
against packed scalar loops, portable Vector128 conversion, and scalar
conversion plus a native Skia transfer through a reusable staging bitmap.
Read and write groups each have their own current-implementation baseline.
All consume the same RGBA integer samples; channel conversion, format/region
checks, and write notifications are included in the timed work. Reads reuse
their output buffer. The SIMD implementation falls back to scalar where
Vector128 is not hardware accelerated.

The candidate kernels are restricted to little-endian BGRA8888 unpremultiplied
bitmaps; they are not replacements for the general product API. Their inputs
include varying RGB and alpha, so use the baseline within this suite rather
than dividing candidate results by the opaque historical suite's numbers.
Every setup checks exact output against the current implementation, including
odd widths and vector tails, padded rows, subrectangles, sentinel preservation,
transparent/translucent alpha, and byte truncation of integer samples. The
native-transfer candidate operates on full images and also checks padded rows.
The staging strategy retains an extra 1 MiB native bitmap at 512x512; this is
outside the reported per-operation managed allocation count. No conversion is
precomputed outside the measurement.
The experimental Vector128 code uses .NET 10 APIs and remains a benchmark-only
comparison. PdfCarton's production path preserves netstandard2.0 using the
existing `System.Numerics.Vector<T>` widening/narrowing APIs and exact channel
arithmetic. The red/blue swap masks each channel before multiplication and
power-of-two floating-point conversion; all intermediate channel values are
exactly representable. Unsupported formats, alpha modes, managed rasters, and
hosts without hardware-accelerated vectors retain their scalar paths.

## Verifying SIMD integration before committing

Capture the unchanged product commit before regeneration. Once generation and
correctness checks pass, compare it against a frozen copy of the generated
working tree. For example, with that commit stored in `BASELINE`:

```sh
python3 validation/pdfcube-benchmarks/compare-revisions.py \
  --before "$BASELINE" --after-working-tree --operations strategies \
  --output validation-output/pdfcarton-simd-strategies

python3 validation/pdfcube-benchmarks/compare-revisions.py \
  --before "$BASELINE" --after-working-tree --operations rendering \
  --output validation-output/pdfcarton-simd-rendering
```

Run these commands sequentially while the machine is otherwise idle, after the
repository's RAM/CPU check. Both snapshots and the identical harness are frozen
before measurement begins. The candidate snapshot includes tracked and
nonignored untracked generated files; its starting commit, diff and status are
retained. No product checkout or commit is modified. `--after REF` instead of
`--after-working-tree` reproduces a committed implementation later.

`strategies` runs the public raster read/write methods and the original Vector128
prototype on identical varying RGBA/alpha arrays at 512x512. Compare each public
method across revisions to measure integration; compare the generated public
method with its same-run prototype to assess retained gains. `integration` runs
only the two public methods when prototype measurements are unnecessary. The
other candidate strategies remain available through the direct command above.
All conversion and notifications stay inside timings. Reads reuse their output
array, so these cases test allocation-free operation, not array allocation cost.

Use default BenchmarkDotNet jobs for reported results. Report each mean with
its `Error` (half-width of the 99.9% confidence interval), managed allocated
bytes, and the baseline/generated mean ratio. Confidence intervals describe
each estimate; the ratio of means is not itself a measured confidence interval.
Do not treat overlapping confidence intervals as evidence of equivalence.

The rendering comparison logs a SHA256 of each fixture's visible bitmap bytes
during setup, outside timing. Require matching dimensions, format, alpha mode,
and pixel hashes across revisions as well as successful runs. Rendering times
and allocations cover four warm first-page workloads described above; they
support conclusions about those workloads only. Native Skia allocations remain
outside the managed allocation count.

To exercise the generated scalar fallback in BenchmarkDotNet, use a separate
sequential run against the same frozen generated snapshot and harness:

```sh
PdfCartonRoot="$PWD/validation-output/pdfcarton-simd-strategies/after/products/pdfcarton" \
DOTNET_EnableHWIntrinsic=0 \
dotnet run --project validation-output/pdfcarton-simd-strategies/after/validation/pdfcube-benchmarks \
  -c Release -- --filter '*RasterStrategyBenchmarks.CurrentRead' \
  '*RasterStrategyBenchmarks.CurrentWrite' \
  --artifacts "$PWD/validation-output/pdfcarton-simd-scalar"
```

The environment switch disables hardware intrinsics for the entire benchmark
process, including other JIT-generated operations and `Vector<T>` behavior. It
tests fallback execution but does not isolate the cost of choosing the scalar
kernel. Do not attribute the entire timing difference to SIMD alone.

The portable candidate suite makes that choice locally with a boolean argument,
keeping hardware intrinsics enabled for both kernels in the same host/JIT
configuration:

```sh
dotnet run --project validation/pdfcube-benchmarks -c Release -- \
  --filter '*PortableRasterBenchmarks*' \
  --artifacts validation-output/pdfcarton-portable-candidate
```

`PortableRead`/`PortableWrite` use `System.Numerics.Vector<T>` and an exact channel
swap using masked values and power-of-two floating-point conversion;
`ScalarRead`/`ScalarWrite` select the same wrappers' scalar loops.
`CurrentRead`/`CurrentWrite` call the referenced product. This suite uses varying
full-range integer samples, validates every red/blue byte pair and alpha values,
and checks regions, tails, padding and byte truncation during setup. Compare
within this suite: its sample arrays differ from the original strategy suite.
Candidate kernel results guide implementation choices; repeat the frozen
baseline/generated public API comparison after integrating a changed kernel.

To summarize a CPU trace without installing a viewer:

```sh
python3 validation/pdfcube-benchmarks/summarize-profile.py TRACE.speedscope.json \
  --include '!PdfCarton\.Benchmarks\.RenderingBenchmarks\.RenderPage\(\)$' \
  --include '^Activity WorkloadActual'
```

The summary restricts attribution to measured-workload stacks containing the
selected method. Anchor frame expressions so process command lines cannot match.
Inlining may remove that frame; a zero-match result requires inspecting the
trace and selecting a surviving workload frame. Trace shares are diagnostic
attribution, not replacements for BenchmarkDotNet's timing results.
