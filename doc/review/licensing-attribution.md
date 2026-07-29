# Licensing and Attribution Strategy

Snapshot proposal, 2026-07-24. Engineering-level strategy for license
selection, compliance mechanics, and attribution across the translator and
its product packages. This is not legal advice; items marked **[counsel]**
deserve a lawyer's eyes before they ship at scale. Once decisions are made,
the durable rules belong in `doc/` and in per-target contracts, not in this
snapshot.

## The legal model of what Vibeformer produces

Mechanical translation is *derivation*, not authorship. A generated package
is a derivative work of the upstream Java project, so:

* Upstream copyright persists in the output. The translation does not launder
  it away, and the project should never claim otherwise.
* The upstream license governs what the output may do and must carry. An
  Apache-2.0 input yields an output that must satisfy Apache-2.0 §4; a GPL
  input would yield a GPL output, whatever the package metadata says.
* Vibeformer's own creative contribution to mechanical output is thin, and
  much of it is LLM-generated, whose copyrightability is unsettled in most
  jurisdictions. Strategy: claim copyright only over the authored runtime and
  the translator itself, never over mechanically translated files.
  **[counsel]** on how the org wants to phrase any claim over authored,
  LLM-written code.

This maps cleanly onto the
[mechanical/authored boundary](mechanical-authored-boundary.md): class M
files inherit upstream's license; class A1/A2 files are original works that
the org licenses deliberately; class V and T never ship.

## Current posture (measured)

| Artifact | Upstream license | What ships today |
| --- | --- | --- |
| Translator + runtime sources (`vibeformer/`) | — | **No license file anywhere in the repo.** Default all-rights-reserved. |
| PdfCube.* packages | Apache-2.0 + NOTICE (PDFBox) | LICENSE.txt and NOTICE.txt packed with pinned SHA-256s (`pdfcube/java_project.clj:307-319`), `PackageLicenseFile`, `repository-commit` recorded. Authors field: `"Apache PDFBox contributors,Vibeformer translation"`. |
| Pkl.Core package | Apache-2.0 + NOTICE (apple/pkl) | `license-expression "Apache-2.0"` only. **Upstream LICENSE/NOTICE are not packed**, though `research/pkl/NOTICE.txt` exists. Authors: `"Vibeformer"`. |
| RawHTTP validation package | Apache-2.0 | `license-expression "Apache-2.0"`; no legal files. |
| Runtime package dependencies | SkiaSharp (MIT), Microsoft.Extensions.Logging.Abstractions (MIT) | Referenced as NuGet dependencies, not redistributed — no inclusion obligations, no action needed. |

Immediate gaps: the Pkl package is an Apache-2.0 derivative that does not
carry the upstream LICENSE or NOTICE (§4(a)/(d) compliance gap once
distributed); the translator has no license; and the PdfCube Authors field
puts upstream contributors in an authorship position (see Trademarks below).

## Strategy

### 1. License allowlist gates target selection

The upstream license is a scope decision made **before** a target is
approved, recorded in the target's contract:

| Upstream license | Policy |
| --- | --- |
| Apache-2.0, MIT, BSD-2/3, ISC, UPL | Proceed. Output carries the upstream license. |
| MPL-2.0, EPL-2.0, LGPL | Case-by-case with **[counsel]**; file-level and linking copyleft interact awkwardly with whole-project translation. Default no. |
| GPL, AGPL | Out, unless the org deliberately wants a GPL product family. |
| Dual/multi-licensed, CLA-encumbered | Pin the exact license election in the target contract. |

Standing warning worth writing down: the Pkl target's policy of
*behaviorally reimplementing* Truffle rather than translating it is also the
legally safe choice — GraalVM/Truffle sources sit under a GPLv2+CPE / UPL
mix, and mechanically translating them would import those terms. The
architecture rule ("focused .NET replacement, not a Truffle port") and the
license rule reinforce each other; keep both.

All three current upstreams (PDFBox, Pkl, RawHTTP) are Apache-2.0, so
everything below is written Apache-first; the mechanics generalize.

### 2. One outbound license per package: the upstream's

Generated packages are licensed under the upstream license (Apache-2.0 →
`Apache-2.0`), and the authored runtime code embedded in them (A1/A2) is
licensed the same way, so every package has exactly one license story.
Licensing the org's authored compat code Apache-2.0 costs nothing and avoids
mixed-license packages. The alternative — carving authored files out under a
different license — buys complexity and no rights worth having.

### 3. Apache-2.0 compliance, clause by clause, as emitter mechanics

Make each obligation a generated, gated artifact rather than a manual step:

* **§4(a) — carry the license.** Pack upstream `LICENSE.txt` in every
  package. The PdfCube `legal-files` pattern (SHA-256-pinned source, packed
  path, `PackageLicenseFile`) is correct; promote it from PdfCube-specific
  code into the generic destination contract so Pkl and future targets get it
  by declaration. NuGet note: `PackageLicenseExpression` and
  `PackageLicenseFile` are mutually exclusive — targets shipping the upstream
  license text use the file form; expression-only is acceptable **only** for
  packages that also pack the text (expression aids tooling, the file is the
  compliance artifact).
* **§4(b) — state changes.** Translation changes every file. Satisfy it
  per-file: extend the generated header (currently bare
  `// <auto-generated />`, `java_project.clj:671-673`) with provenance —

  ```csharp
  // <auto-generated />
  // Mechanically translated from org/apache/xmpbox/XMPMetadata.java
  // at apache/pdfbox 9286e47d (3.0.8) by Vibeformer <version>.
  // Translated derivative of the Apache PDFBox project; see NOTICE.txt.
  ```

  This simultaneously satisfies "prominent notices stating that You changed
  the files," gives per-file provenance the authorship ledger wants, and
  costs one template change in the emitter.
* **§4(d) — retain NOTICE.** Pack upstream `NOTICE.txt` verbatim, plus an
  appended translation notice (NOTICE may be *added to*, not edited):

  ```text
  ---
  This package is an independent mechanical translation of the above
  software to C#/.NET, produced by <tool>. It is not developed, endorsed,
  or supported by the original project or its foundation.
  ```

* **Attributions embedded in resources.** PDFBox's NOTICE attributes the
  Adobe Glyph List, Zapf Dingbats list, and PaDaF/Atos code; FontBox packages
  embed those resource files directly. Copying resources byte-identical
  (current behavior) plus retaining NOTICE covers this — add a check that a
  target's packed resources never ship without the NOTICE that attributes
  them.

### 4. Trademarks and naming

Product naming is already right: distinct marks (`PdfCube`, not
"PDFBox.NET") with nominative references in descriptions. Tighten three
things:

* **Authors field.** `"Apache PDFBox contributors,Vibeformer translation"`
  (`targets/pdfcube/destinations/*.edn`) puts the ASF's contributors in the
  publisher/authorship position of an artifact they never released — the
  exact implication trademark policies prohibit. Authors should be the
  publishing org only; upstream credit belongs in `Copyright`, the
  description, and NOTICE. Suggested shape: Authors = `<org>`, Copyright =
  `Portions Copyright The Apache Software Foundation, licensed under
  Apache-2.0`.
* **Non-affiliation disclaimer** in every package description and README:
  "independent translation; not affiliated with or endorsed by the Apache
  Software Foundation" (respectively Apple for Pkl — *Pkl* is an Apple
  mark). The current descriptions say "mechanically translated by Vibeformer"
  but never disclaim affiliation.
* **Package IDs never contain upstream marks.** `PdfCube.*` is clean; keep
  the rule explicit so target #4 doesn't ship `Foo.PDFBox`. The existing
  identity-guard mechanism (`harness.clj:306`) can enforce a
  *forbidden-trademark* fragment list per target — the machinery already
  exists for product identities; reuse it for marks. **[counsel]** if a
  future target's mark owner has a stricter published policy.

### 5. License the translator itself — decide, don't default

The repository currently has no LICENSE, which means all-rights-reserved by
default and undefined status for every contribution. This is a user/org
decision, not an engineering one; the two coherent options:

* **Internal/proprietary**: add an explicit internal-use notice so the status
  is chosen rather than accidental.
* **Apache-2.0**: matches the ecosystem it consumes, permits the products'
  licensing trivially, and is the natural choice if the translator is ever
  shared.

Either way, authored runtime files (`runtime/*.cs`) should carry explicit
headers (copyright owner, SPDX identifier), since they — unlike the
translator — ship inside distributed packages and are the org's clearest
copyrightable contribution.

### 6. Make it data, make it fail-closed

Consistent with the [scaling roadmap](scaling-roadmap.md), the per-target
legal contract belongs in the target's data, verified like everything else:

```clojure
;; targets/<name>/baseline.edn (or destination contract)
{:upstream-license "Apache-2.0"          ; SPDX, from the allowlist
 :legal-files [{:kind :license …sha256…}
               {:kind :notice  …sha256…}]
 :notice-appendix "…translation notice…"
 :forbidden-marks ["PDFBox" "Apache PDFBox"]
 :attribution {:copyright "Portions Copyright …"
               :disclaimer "…not affiliated…"}}
```

Gates, all of which extend existing machinery: packaging fails if a declared
legal file is missing, hash-changed, or absent from the packed `.nupkg`
(PdfCube's `validate-legal-inputs!` generalized); the package inspector
asserts LICENSE/NOTICE presence and the disclaimer string; the identity guard
rejects forbidden marks in package IDs, assembly names, and namespaces; and a
new target cannot generate at all without an allowlisted
`:upstream-license`. Upstream license *changes on re-baseline* (rare but
real) then surface as a hash mismatch requiring explicit approval instead of
sliding through a version bump.

## Actionable gaps, in order

1. Pack Pkl's upstream `LICENSE.txt` and `NOTICE.txt` (+ translation
   appendix) — it is the one current §4 compliance gap.
2. Fix the PdfCube `Authors` fields and add non-affiliation disclaimers to
   all package descriptions.
3. Add per-file provenance headers to generated output (§4(b), and the
   authorship ledger gets its provenance for free).
4. Choose and add a license (or explicit internal notice) for the repository
   itself; add SPDX headers to `runtime/*.cs`.
5. Generalize `legal-files` + license allowlist into the neutral destination
   contract when the target-as-data work happens.
