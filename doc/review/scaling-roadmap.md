# Scaling Roadmap

Snapshot proposals, 2026-07-24. Nothing here changes a product contract; the
authoritative goals under `doc/targets/` remain in force.

## The vision this serves

Vibeformer should scale from two product targets to many, where:

* The translator is built and maintained by LLM agents.
* Shipped output is almost exclusively **mechanical translation** of existing,
  well-tested upstream projects.
* Hand-authored (LLM-written) code is the narrow exception — missing standard
  library semantics and destination substrates — and is explicitly bounded,
  reviewed, and evidenced (see
  [Mechanical / Authored Boundary](mechanical-authored-boundary.md)).

The existing design already points this way: fail-closed coverage, identity
guards, pinned oracles, and validated extension seams are exactly the
guardrails an agent-built system needs. The gap is that adding a target today
still costs too much bespoke Clojure, and the knowledge the translator
accumulates (mappings, compat behavior) is stored in a shape that resists
audit and reuse. The proposals below are ordered by leverage.

## 1. Converge on one destination foundation (highest leverage)

Migrate the Pkl bundle onto `java_library.clj` the way PdfCube already
composes over it. Today Pkl carries a complete parallel implementation
(6,481 lines) of declaration emission, type mapping, and body rules. Until it
is migrated:

* every improvement to the shared foundation must be duplicated or the
  bundles diverge;
* the Pkl bundle keeps teaching agents the wrong pattern for target #4;
* the true cost of a new target is obscured (PdfCube's 785-line bundle is the
  honest number; Pkl's 6,481 is the legacy number).

This is mechanical consolidation work with the strongest possible safety net
already in place: the language-snippet contract, core corpus, and
differential gates define observable behavior, so the migration can proceed
bundle-piece by bundle-piece with regeneration after each step. Target
end-state: `pkl/` contains only Pkl product policy, Pkl semantic mappings for
Pkl-specific dependencies, and the Pkl runtime bridge — the same shape as
`pdfcube/`.

## 2. Make mapping knowledge data, not code

The resolved-symbol registries are the translator's accumulated capital, and
they are currently stored as inline set literals and `cond` branches inside a
6,590-line namespace. Restructure them as declarative registry data (EDN or
literal maps in small per-package namespaces) where each entry carries:

```clojure
{:key "executable:java.util.List#sort(java.util.Comparator)"
 :strategy :rename                ; or :member, :property, :compat-call,
                                  ; :template, :custom-fn
 :destination "Sort"
 :caveats #{:comparer-null-contract}
 :introduced-by :pdfcube-io       ; provenance: which target demanded it
 :evidence #{:differential/io "java_library_test"}}
```

A small interpreter in `java_library.clj` executes the common strategies
(rename, property access, static-to-compat-call, argument reshaping);
genuinely irregular mappings keep bespoke emit functions but are registered
in the same table. Benefits:

* **Coverage becomes reportable.** A generated artifact can list every mapped
  identity, its strategy, caveats, and evidence — reviewable by humans,
  diffable by agents, and comparable across targets.
* **Gap analysis becomes a batch operation.** Resolving a candidate module
  already produces every occurrence key; joining against the registry yields
  the complete unmapped-symbol backlog *before* translation starts, with
  frequency counts to order the work. Today gaps surface one blocking
  diagnostic at a time.
* **Merge friction drops.** Two targets filling different JDK gaps touch
  different small files instead of the same giant one.
* The same treatment applies to `java_types.clj`, whose entries need a place
  to record semantic caveats (iteration order, exception-hierarchy collapse)
  — see assessment §3.

## 3. Target-as-a-directory: make adding a target a data exercise

Define a convention where one directory owns everything a target contributes:

```text
targets/<name>/
  profiles/*.edn            ; generation profiles (already file-selectable)
  destinations/*.edn        ; destination contracts
  mappings/*.edn            ; target-specific semantic mapping overlays
  runtime/*.cs              ; destination runtime assets (if any)
  validation/
    oracle/*.java           ; upstream oracle programs
    probe/*.cs              ; package-only probes
    contract.edn            ; differential contract: fixtures, required
                            ; observation families, pinned counts, hosts
  baseline.edn              ; single authoritative upstream pin (see §5)
```

The harness already accepts profile EDN paths without registration
(`read-profile`, `harness.clj:78-81`); extend the same courtesy to
differentials and CLI commands so that **no generic file changes when a
target is added**. Today each differential requires a new namespace plus a
hardcoded `main.clj` subcommand; `main.clj` should dispatch
`differential <target>` from target metadata instead. The inline `pkl-parser`
profile and the `"pkl-parser"` defaults in `harness.clj`, `packaging.clj`,
and `main.clj` should disappear as part of this (a missing profile argument
should be an error, not a Pkl default).

With §2 and §3 in place, the add-a-target playbook becomes:

1. Pin the upstream checkout (`baseline.edn`), select modules, write
   profiles/destinations — data only.
2. Run gap analysis; the output is the mapping backlog.
3. Fill mappings (mostly registry entries; occasionally compat code, which
   crosses the authored boundary and follows its rules).
4. Write the differential contract + oracle + probe.
5. Generate, verify, pack, consume, differential — all preexisting machinery.

## 4. Extract the generic differential harness

The three PdfCube differentials have already converged on a shape: compile a
Java oracle against the pinned checkout, run a package-only C# probe inside
the isolated consumer, compare normalized TSV observations
(`family\tid\tvalue` with required families), perturb the oracle to prove the
comparator, pin an expected package contract, emit a summary. Extract exactly
that into one namespace driven by the per-target `contract.edn`, keeping:

* the perturbation self-test (non-negotiable — it verifies the verifier);
* required-family coverage sets;
* the pinned expected contract, but sourced from `baseline.edn` + the
  generation manifest rather than inline constants.

Per-target Clojure then shrinks to zero for the common case; unusual proofs
(like the Pkl language-snippet corpus) remain bespoke but rare. Version the
observation format explicitly (the header-sentinel pattern in
`rawhttp_package.clj:15-17` is the right idea).

## 5. One authoritative baseline record per target

Upstream version, revision, artifact hashes, expected source counts, and
expected public-contract row counts are currently scattered constants
(assessment §7). Consolidate into `targets/<name>/baseline.edn`, consumed by
profiles, bundle validation, and differentials. Add a deliberate
**re-baseline command** for the monotonic-upgrade workflow the PdfCube
contract already mandates: it fetches/pins the new stable release, regenerates,
recomputes the derived expectations (source counts, contract rows), and
presents the delta for explicit approval before rewriting `baseline.edn`.
Version bumps become one reviewed diff instead of a scavenger hunt.

## 6. Treat the compat runtime as a product of its own

`Vibeformer.JavaCompat.cs` (8,449 lines, ~113 types) is the single most
reused artifact across all current and future targets, and the largest body
of authored code. It deserves library-grade structure:

* Split by JDK area (`Java.Io.cs`, `Java.Util.cs`, `Java.Nio.cs`,
  `Java.Text.cs`, …) with one behavioral concern per file.
* Give it its own differential-style test: small Java programs exercising the
  emulated APIs on a real JVM versus the same operations through the compat
  types on .NET. Today compat behavior is only tested indirectly through
  whole-target differentials, so a subtly wrong `JavaDeque` surfaces as a
  confusing PdfBox mismatch instead of a direct compat failure.
* Record per-type provenance (which JDK contract, which upstream Javadoc
  semantics, which target demanded it) — this is the "authored" side of the
  boundary manifest.
* Keep it internalized into owning packages per current policy; whether it
  ever becomes a standalone package is a user scope decision, but structuring
  it as if it were one costs nothing and keeps that door open.

## 7. Sharpen the generic/product layout

Move the Pkl-specific top-level namespaces (`differential.clj` — rename it;
it is the Pkl differential — `language_snippet_*`, `pkl_core_*`,
`public_api_contract.clj`) under `pkl/`, mirroring `pdfcube/`. Extract the
duplicated helpers (SHA-256 digest, `write-text!`, `xml-escape`, host
detection, portable paths) into one small util namespace. Neither change is
urgent; both make the codebase teach the right structure to the next agent
that reads it, which is how conventions actually propagate in an LLM-built
system.

## 8. Invest in error ergonomics as agent throughput

In an agent loop, the error message *is* the interface. The strong precedent
(`project_input.clj`: per-field, exact missing/unknown keys) should become
the standard everywhere; the weak spots (`read-profile`'s single 40-clause
`and`) should name the failing predicate and offending value. A tiny
validation helper (or adopting malli for the config boundary only) would pay
for itself in reduced agent iterations. Similarly, preserve exception class
and stack summary when resolvers convert `Throwable` to diagnostics, so
translator bugs remain distinguishable from genuinely unresolvable input.

## 9. Candidate-target strategy

Choose the next targets to maximize what they teach the translator, not just
their product value:

* **rawhttp-core is already half-formalized** (`rawhttp_package.clj`,
  `config/rawhttp-core.edn`) as a generality probe — small, network-flavored,
  concurrency-touching. Finish formalizing it as a permanent *conformance
  target*: cheap to regenerate, run in CI, and required to stay green, so the
  reusable layer provably stays reusable while big targets evolve.
* Prefer next real targets that stress currently-thin areas one at a time:
  reflection-heavy (Guava-like), generics-heavy (a collections library),
  concurrency-heavy (an executor-based library). Each broadens the mapping
  registry and compat runtime — which after §2/§6 is measurable growth in
  data and evidenced library code, not sprawl.

* The Java-version dimension deserves an explicit statement per target
  (records/switch-expressions are handled today; pattern matching, sealed
  hierarchies, and virtual threads will arrive with newer baselines).

## 10. Keep the guardrails ahead of the agents

The mechanisms that make this codebase safe to build with LLMs — coverage
gates, identity guards, perturbation self-tests, pinned contracts, doc-anchored
exclusions (`public_api_contract.clj` literally cites
`doc/targets/pkl/product-goal.md` lines) — should be extended as the system
scales:

* CI must run the conformance target (§9) and every target's proof ladder;
  a change that greens one target by breaking another must be undetectable
  only by omission, never by tolerance.
* The boundary manifest (companion document) becomes a gate: authored-code
  growth in a package without linked evidence fails the build.
* Thread-safety of parallel Spoon resolution should be argued in writing or
  the parallelism narrowed (assessment §9); unreproducible resolution
  failures are uniquely poisonous to agent loops, which will retry instead of
  reporting them.

## Suggested sequencing

| Phase | Work | Why first |
| --- | --- | --- |
| 1 | Pkl-onto-`java-library` migration (§1); util/layout cleanup rides along (§7) | Removes the double-maintenance tax every later step pays |
| 2 | Mapping registries as data + gap-analysis report (§2) | Turns target onboarding into a measurable backlog |
| 3 | Generic differential harness + baseline records (§4, §5) | Makes proofs and upstream bumps cheap before more targets multiply them |
| 4 | Target-as-directory convention + CLI dispatch from metadata (§3) | New targets stop touching generic code at all |
| 5 | Compat-runtime restructuring and direct compat differential (§6) | Hardens the shared runtime before many targets depend on it |
| 6 | Boundary manifest and CI gates (§10 and companion doc) | Locks in the mechanical/authored separation as the fleet grows |
