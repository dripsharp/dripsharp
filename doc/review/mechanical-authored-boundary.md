# Mechanical / Authored Boundary

Snapshot proposal, 2026-07-24. This document proposes making the separation
between mechanically translated code and LLM-authored code an explicit,
measurable, enforced contract. It formalizes policy that already exists in
spirit across `doc/architecture.md` ("Reusable Translation and Runtime
Boundary") and both product goals.

## Why this boundary is the product

Vibeformer's value proposition is that shipped .NET behavior is *derived*
from mature, well-tested upstream Java — not re-invented. Every line of
authored code in a shipped package is a line whose correctness rests on an
LLM's understanding instead of on upstream's test history, so the authored
fraction is the honest measure of how much of the product is "trust the
translator" versus "trust a fresh implementation." Keeping that fraction
small, visible, and evidenced is what distinguishes this system from ordinary
LLM code generation.

## Code classes

Every file that participates in a shipped package or its proof belongs to
exactly one class:

| Class | Definition | Durability | Trust source |
| --- | --- | --- | --- |
| **M — Mechanical** | Emitted by the translator from resolved upstream source. Never edited. | Disposable; regenerated from scratch | Upstream tests + differential proofs |
| **A1 — Authored compat** | Generic JVM-semantics replacements (`runtime/Vibeformer.JavaCompat.cs`, regex data). Product-neutral by rule. | Durable | Direct compat tests + every target's differential |
| **A2 — Authored destination runtime** | Product substrates (`runtime/Pkl.Core.*.cs`, `runtime/PdfCube.FontBox.*.cs`). May contain product semantics. | Durable | Ported upstream tests + target differentials |
| **V — Validation** | Oracles, probes, consumers, contracts (`validation/`). Never shipped. | Durable | The perturbation self-tests; review |
| **T — Translator** | The Clojure system itself. Never shipped. | Durable | Its test suite + the proof ladder it runs |

Class M is the overwhelming target: shipped packages should be almost
entirely M, with A1/A2 as bounded exceptions.

## Current state (measured at this review)

Handwritten C# that ships inside generated packages:

| File | Lines | Class | Shipped into |
| --- | --- | --- | --- |
| `Vibeformer.JavaCompat.cs` | 8,449 | A1 | every package requesting `:java-compat` |
| `Vibeformer.JavaRegexUnicodeData.cs` | 80 | A1 | packages requesting `:java-regex-unicode` |
| `Pkl.Core.Substrate.cs` | 3,172 | A2 | Pkl.Core |
| `Pkl.Core.DotNet.cs` | 2,045 | A2 | Pkl.Core |
| `Pkl.Core.Loading.cs` | 975 | A2 | Pkl.Core |
| `Pkl.Core.ValueModel.DotNet.cs` | 896 | A2 | Pkl.Core |
| `Pkl.Core.RuntimeBridge.cs` | 121 | A2 | Pkl.Core |
| `PdfCube.FontBox.Discovery.cs` | 120 | A2 | PdfCube.FontBox |
| `PdfCube.FontBox.Compat.cs` | 77 | A2 | PdfCube.FontBox |

Observations:

* The mechanisms for *injecting* authored code are already disciplined: assets
  flow only through the bundle's `:destination-bridges` /
  `:product-runtime-assets` capabilities, destinations must declare
  `:runtime-sources`, and the harness rejects a destination requesting
  runtime assets from a bundle without that capability
  (`harness.clj:344-349`). Nothing enters a package accidentally.
* What is missing is the *accounting*: no shipped artifact records which of
  its files are M versus A, no ratio is computed, and A-class files carry no
  provenance or evidence linkage. The generation manifest
  (`generation-manifest.edn`) records artifacts and strategies, which is most
  of the raw material.
* PdfCube's A2 footprint (~200 lines against five large upstream modules) is
  exemplary. Pkl's A2 footprint (~7,200 lines) is legitimately larger — the
  product goal explicitly authorizes a focused Truffle-substrate replacement —
  but it is precisely the code that most needs the evidence regime below.

## Proposal 1: authorship ledger in every emission

Extend the generation manifest with a per-file authorship ledger:

```clojure
{:authorship
 {:schema-version 1
  :files [{:path "src/PdfCube/XmpBox/XMPMetadata.cs"
           :class :mechanical
           :source {:file "…/xmpbox/…/XMPMetadata.java" :revision "9286e47d…"}}
          {:path "src/Vibeformer/Runtime/JavaCompat.cs"
           :class :authored-compat
           :provenance "vibeformer/runtime/Vibeformer.JavaCompat.cs"
           :sha256 "…"
           :evidence [:compat-differential :pdfcube-xmpbox-differential]}]
  :totals {:mechanical-lines 41230
           :authored-lines 8529
           :authored-fraction 0.171}}}
```

Everything needed already exists at emission time: declaration artifacts know
their Spoon sources, and `copy-assets!` (`java_project.clj:516`) knows every
authored asset it copies. The packaging inspector should then verify the
packed assembly's source inventory against the ledger, so the ledger is
proven, not asserted.

## Proposal 2: gates on authored code

Make the boundary fail-closed like everything else:

1. **No unlisted authored files.** A file entering a package that is neither
   translator-emitted nor a declared runtime asset fails emission. (Already
   effectively true; make it an explicit check against the ledger.)
2. **No authored growth without evidence.** Each A-class file (or region,
   once files are split per concern) must name at least one green proof that
   exercises it — a compat differential family, a ported upstream test, or a
   target differential. A new public type in an A-class file with no evidence
   reference fails the proof ladder. This is the enforcement teeth for the
   existing rule that native replacements be "independently tested against
   upstream behavior" (`doc/architecture.md`).
3. **Authored-fraction budget per package.** Record the fraction in the
   pinned package contract the differentials already assert
   (e.g. `validate-package-contract!`,
   `pdfcube/xmpbox_metadata_differential.clj:214`). Raising a package's
   budget is then a reviewed, deliberate diff — the same pattern as the
   user-approved exclusion lists. Suggested initial budgets: current measured
   values, frozen.
4. **A1 stays product-neutral mechanically.** Extend the identity-guard idea:
   scan A1 sources for product identity fragments (`Pkl`, `PdfCube`, target
   namespaces) the same way profiles guard destination identities
   (`harness.clj:306`). Today this is convention; make it a check.

## Proposal 3: rules of engagement for authoring agents

Written for the agent that is about to write C# by hand — the moment the
boundary is most at risk:

1. **Exhaust the mechanical path first.** A missing mapping is a registry
   entry, not a compat class. A compat class is justified only when .NET has
   no faithful ordinary representation (`doc/transform-pipeline.md`, Helpers).
2. **Declare before writing.** New authored code starts by classifying itself
   (A1 or A2) and stating the JVM/product contract it replaces, in the file
   header. A1 code citing a product behavior is misclassified by definition.
3. **Evidence lands with the code.** The proof (compat differential rows,
   ported test, differential family) ships in the same change; gate 2 makes
   this mechanical.
4. **Smallest faithful surface.** Implement the upstream contract actually
   demanded by resolved occurrences — the gap report from the mapping
   registry (roadmap §2) defines "demanded" — not the full JDK class.
5. **Authored code never patches mechanical output.** Already absolute policy;
   restated here because it defines the boundary: interaction happens only
   through mappings that *target* authored types, never through edits to
   generated files.

## Proposal 4: report the boundary

Once the ledger exists, publish a small per-release boundary report (a table
per package: mechanical lines, authored lines by file, fraction, evidence
index). This is cheap, it is the number the vision statement cares about
("outputs almost exclusively mechanical translation"), and trend visibility is
what keeps a hundred small authored additions from silently becoming a
reimplementation. The report belongs with release evidence, not in durable
docs.

## Relationship to existing contracts

Nothing above narrows or extends any product goal. The exclusion lists,
completion rules, and adaptation boundaries in `doc/targets/*` are unchanged;
this proposal only makes one of their shared premises — "equivalent behavior
may be implemented through generated C# or focused .NET replacements, without
silently redesigning upstream behavior" — auditable per package and
enforceable per change.
