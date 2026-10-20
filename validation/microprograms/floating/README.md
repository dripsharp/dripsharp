# Floating-point conversion and numeric text (local only)

This family sends one saved input batch to Java, direct authored JavaCompat,
and the same Java source mechanically translated into a `netstandard2.0`
library consumed by a `net10.0` observer. The Java baseline is resolved from
`validation/java-compat/contract.edn` (Java 17). It is deliberately not inferred
from the generator's JVM or the PdfCarton oracle configuration.

After checking available RAM/CPU, run the bounded regression and exploration:

```sh
export DRIPSHARP_WORKERS=22
export JAVA_TOOL_OPTIONS=-Xmx28g
clojure -J-Xmx28g -M -m dripsharp.floating-microprogram --seed 73 --samples 24
```

There are 976 recorded operations and at most 24 seeded operations, below the
existing 1,000-operation microprogram limit. Seeded operations alternate double
conversion and float text, with the actual decimal input saved before either
implementation runs. Each path builds its fixed runner once per invocation;
existing runner timeouts and process diagnostics apply. `--path direct` or
`--path translated` isolates a path. `--samples 0` selects only authored cases.
A focused rounding replay is:

```sh
clojure -J-Xmx28g -M -m dripsharp.floating-microprogram \
  --samples 0 --case root-round-19
```

The returned `snapshot`, path-specific summaries, and `replay` command retain
actual inputs, generator version/seed, host and runtime versions, and the first
operation-level difference. Replay never regenerates input:

```sh
clojure -J-Xmx28g -M -m dripsharp.floating-microprogram \
  --replay validation-output/microprograms/floating-EXAMPLE/snapshot.edn
```

The live regression namespace also demonstrates that losing negative zero is
reported and that replay retains the injected mismatch:

```sh
clojure -J-Xmx28g -M:test --namespace dripsharp.floating-microprogram-live
```

This namespace is outside default discovery; registration starts no processes.
No new PR/push workflow, hosted matrix, or scheduled campaign is added. Only
process-free input admission tests enter ordinary test discovery. When running
with cached dependencies in a network-restricted local environment, an explicit
`NuGetAudit=false` environment setting avoids the unavailable vulnerability-feed
lookup; it does not relax compiler warnings or semantic comparisons.

## Observations and contracts

Every row names a locale (`root`, `de`, or `ar`), operation/pattern, and numeric
input as exact UTF-16 tokens. Each runtime sets its own locale explicitly before
the operation. Locale-independent PDF-style formatting additionally supplies
root decimal symbols while the ambient culture remains German or Arabic.

* Parsing compares exact IEEE bits for finite values, infinities, and signed
  zero. Float observations use Java's canonical `floatToIntBits` NaN contract;
  double NaNs are observed as the category `nan`. No raw NaN payload portability
  is claimed. Decimal/exponent and hexadecimal forms, suffixes, ASCII trim,
  overflow, underflow, nearest-even ties, and rejected comma/localized forms are
  included. Rejection is observed at the parse operation as `illegal-argument`.
* Raw casts compare exact integral results and canonical float bits, including
  float-to-double widening. Boxed `Number.intValue/longValue` exercise resolved
  mappings separately. Integer-to-float/double cases straddle 2^24 and 2^53.
  NaN-to-zero, truncation, saturation, and subsequent byte/short/char narrowing
  are exact, with no tolerance. A raw negation expression retains signed zero.
* `Math.round` compares exact integer results, including the double immediately
  below 0.5, negative halves, and exceptional values.
* `Double.toString` and `Float.toString` compare exact Java 17 text, including
  exponent thresholds, subnormal/minimum-normal/maximum-finite values, and
  negative zero. Java 17's legacy float digit selection differs from newer
  shortest-decimal algorithms; this is a versioned observation, not a claim
  that every JDK release prints the same digits.
* `DecimalFormat` compares exact text for fixed patterns `0.00`, `#,##0.###`,
  `000.000`, and invariant PDF-style `0.########`, with HALF_EVEN ties such as
  2.625/2.675. Arabic observations preserve digits, separators, the directional
  sign mark, and Java 17 CLDR NaN text. Root infinity is `∞`. Host ICU defaults
  are not silently substituted for this explicit Java 17 locale contract.

No operation uses an approximate comparator. Build failures, timeouts, unknown
exceptions, and invalid input remain distinct from expected parse rejection.
This bounded family complements upstream/package tests; a passing batch does
not assert complete numeric equivalence or PdfCarton product completion.
