# Code Assessment

Snapshot review, 2026-07-24. File references are relative to `vibeformer/`.

## Summary

The architecture described in `doc/architecture.md` is actually implemented,
and implemented with unusual discipline: fail-closed gates exist at every
layer, semantic dispatch really is keyed by resolved identity rather than
text, emission really is deterministic, and the verification ladder proves
behavior against a pinned JVM oracle rather than against the translator's own
assumptions. The generic kernel is small, clean, and genuinely
product-neutral.

The main structural problem is that the codebase contains **two generations of
the same architecture**: the Pkl bundle (`pkl/java_project.clj`,
`pkl/java_body.clj`, 6,481 lines) is a complete, older, parallel
implementation of declaration emission, type mapping, and body translation,
while PdfCube composes over the newer shared foundation
(`java_library.clj`). The second problem is that the newer foundation and the
compat runtime are growing as monoliths — a 6,590-line namespace with a
2,660-line function, and an 8,449-line C# file — organized as code where much
of the content is really mapping *data*. Both problems are consolidation
work, not redesign work; the underlying seams are good.

## Well designed

### 1. The fail-closed translation kernel

`java_translate.clj` (662 lines) is the best namespace in the repository.
Every element gets exactly one plan — a semantic mapping by resolved identity,
a structural rule by Spoon interface, or a blocking diagnostic
(`plan-for`, `java_translate.clj:433`). Coverage is audited per visit and
`coverage-gate!` (`java_translate.clj:604`) refuses accepted output on any
blocked visit, any fallback, or diagnostic mode categorically. Missing
coverage cannot silently degrade into guessed C#. Two details show real care:

* Cascading failures are suppressed — when a child already carries a blocking
  diagnostic, the parent emits nothing rather than manufacturing a second
  failure (`java_translate.clj:445-451`), so diagnostics point at the
  originating element.
* Rule registries are validated up front: unique ids, unique classes, ordered
  specificity (`structural-rules`, `java_translate.clj:22`), and mapping keys
  must match their category's key grammar (`valid-mapping-key?`,
  `java_translate.clj:40`).

### 2. Identity-based semantic resolution

`spoon.clj` resolves every reference in the model to a stable string identity
(`executable:owner#name(params)`, `field:owner#name`,
`type-parameter:declarer#name`) derived from Spoon's resolved objects, and the
occurrence index is an `IdentityHashMap` over the live reference objects
(`java_translate.clj:82-89`) — deliberately not Java equality or rendered
text. Overload resolution therefore belongs to the frontend, exactly as
`doc/transform-pipeline.md` requires. The closure selector
(`select-resolved-closure!`, `spoon.clj:976`) is a genuinely sophisticated
worklist algorithm: expansion ranks (`:shell` < `:body` < `:public-api`),
compilation obligations for abstract/override/default-interface members
(`compilation-obligation-items`, `spoon.clj:899`), instance initializers
pulled in with constructors, and owner chains — with hard failures on
ambiguous or missing declarations (`exact-declaration!`, `spoon.clj:713`).

Edge cases show maturity: implicit record self-references
(`spoon.clj:264-293`), enum synthetic members, implicit canonical/default
constructors, array `length`, class literals, and formal type variables
nested in resolved library signatures (`type-parameter-key`,
`spoon.clj:213-236`).

### 3. The neutral project-input contract

`project_input.clj` is exemplary boundary design: exact key sets (missing
*and* unknown fields fail), deterministic canonical ordering of every
collection, SHA-256 verification of external classpath artifacts against
their recorded hashes (`project_input.clj:157-165`), root-membership checks,
and a self-dependency guard. Both discovery backends (Gradle wrapper, pinned
Maven with an EventSpy) adapt to this one schema, so Spoon configuration and
everything downstream is build-tool-agnostic. This is the contract that makes
"any Java project" plausible.

### 4. Deterministic emission with full accountability

`java_project.clj` earns its "product-neutral emitter" claim:

* Every output is sorted, EDN is canonicalized (`canonicalize`,
  `java_project.clj:242`), and the csproj sets `Deterministic` and
  `ContinuousIntegrationBuild`.
* Collision gates cover generated file paths, declaration names by owner and
  signature, and resource destinations (`collision-errors`,
  `java_project.clj:414`; `emit-project!:773-779,801-810`).
* Source accounting requires every production source file to have produced a
  top-level declaration or be package metadata
  (`source-accounting`, `java_project.clj:460`) — files cannot be silently
  dropped.
* Every emitted declaration must appear in the source map
  (`emit-project!:842-845`), and the manifest records sources, artifacts,
  resources, and coverage totals.

The dominant-root scheduler (`java_project.clj:594-759`) — member-level
parallelism for one oversized class, with results reassembled by canonical
index so parallelism never affects output — is thoughtful performance work
that preserved determinism.

### 5. The proof ladder verifies the verifier

The cumulative `generate → verify → pack → package → differential` ladder is
well beyond typical transpiler validation:

* Packing builds twice and compares canonicalized package bytes; consumption
  restores from a fresh isolated feed.
* Differentials compare normalized observations from a pinned JVM oracle
  against a *package-only* .NET probe, so evidence is independent of the
  generator (`pdfcube/xmpbox_metadata_differential.clj`).
* The comparator is itself tested every run by a deliberate perturbation that
  must be detected (`prove-perturbation!`,
  `pdfcube/xmpbox_metadata_differential.clj:105`) — verification of the
  verifier, which matters in an LLM-built system where a silently vacuous
  comparator is a real failure mode.
* Package contracts are pinned exactly — source counts, dependency identities,
  legal-file hashes, public-contract row counts, zero public stubs
  (`validate-package-contract!`,
  `pdfcube/xmpbox_metadata_differential.clj:214`).

### 6. Extension seams are explicit, validated contracts

Product composition happens through three seams, all schema-versioned,
validated before work starts, and selected by explicit namespace-qualified
symbols in configuration rather than by product-name dispatch:

* The destination rule bundle contract (`rule-contract`,
  `java_project.clj:23-42`) with required components and hooks validated by
  `validate-rule-bundle!`.
* The public-surface strategy contract with product-family compatibility
  checks (`public_surface.clj:14-53`).
* Profile/destination/bundle agreement enforced as one fail-closed plan
  before discovery (`prepare-profile!`, `harness.clj:317`), including the
  capability check that a destination may not request product runtime assets
  a bundle cannot supply.

The identity guard (`validate-identity-guard!`, `harness.clj:306`) — profiles
declare forbidden product-identity fragments checked against all destination
identities — and `java-types/product-neutral?` are unusual and valuable
anti-drift mechanisms for agent-maintained code.

### 7. The PdfCube bundle is the model for what a target should cost

`pdfcube/java_project.clj` (785 lines) is mostly declarative product policy —
the five-product map with namespace prefixes, dependency projections, approved
runtime packages, legal files with pinned hashes — plus a thin functional
composition over `java-library/rule-bundle` (`rule-bundle`,
`pdfcube/java_project.clj:740`). Dependency decisions are auditable data:
every source coordinate maps to an approved projection kind (`:bcl`,
`:microsoft-package`, `:skia-sharp`, `:internal-capability`,
`:translated-source`) with SHA-256-pinned source artifacts. This is the shape
target #4 should copy.

### 8. Performance is engineered, not accidental

The canonical-source cache with instrumentation counters and weak per-frontend
lifetime (`spoon.clj:57-138`), cheap `frontend-identity` on every visit versus
expensive `frontend-diagnostic` rendering only on failure, the bounded shared
executor with nested-pool sequentialization (`concurrency.clj:71-117`), and
the emission profile reporting worker participation all indicate the team
measured before optimizing.

## Poorly designed / risks

### 1. Two parallel implementations of the destination layer

The Pkl bundle does not use `java_library.clj` at all — it has its own
`csharp-keywords`, `identifier`, `pascal`, `destination-namespace`,
`type-node`, its own 344-line `external-type-mappings` table
(`pkl/java_project.clj:233-577`), and its own complete body-rule set in
`pkl/java_body.clj`. `java_library.clj` reimplements all of these concepts
for the shared foundation PdfCube uses. Every kernel-adjacent improvement now
must be made twice or silently diverges; the file-churn history shows both
bundles are still hot. This is the largest single liability in the codebase
(~5,000+ lines of avoidable parallel code) and it will grow with every Pkl
milestone until Pkl is migrated onto the shared foundation.

### 2. Mapping knowledge is code-shaped when it is really data

`java_library.clj` is 6,590 lines; `body-rules` alone is one 2,660-line
function (`java_library.clj:1766-4426`). Resolved-symbol coverage lives in
giant inline set literals split across multiple `cond` branches — e.g.
`extended-neutral-executable-keys` (`java_library.clj:632-808`) plus a second
~700-key anonymous set inside `resolved-name`
(`java_library.clj:874-1300`). Consequences:

* Whether a given JDK member is covered, and *how*, is answerable only by
  reading code paths; there is no coverage report, no per-mapping metadata
  (semantic caveats, which target demanded it, which differential family
  proves it).
* Two mapping sets in different branches can disagree and nothing detects it.
* Every new target's JDK gap-filling churns the same enormous file,
  guaranteeing merge friction once more than one target is worked at a time.

The same criticism applies to `differential.clj`, where roughly 850 lines of
expected observation rows are inline Clojure data
(`differential.clj:892-1737`).

### 3. The context-free type table cannot express its own caveats

`java_types.clj` is a flat name-keyed table, and several entries are
semantically lossy in ways the registry has no way to record or condition:

* `java.util.LinkedHashSet → HashSet` (`java_types.clj:284`) silently drops
  deterministic iteration order — precisely the kind of divergence
  differential tests catch only if a fixture happens to iterate.
* `java.lang.RuntimeException → System.Exception` and
  `java.lang.Exception → System.Exception` (`java_types.clj:41-42`) collapse
  the exception hierarchy; Java code with sibling catch clauses either fails
  to compile (visible) or changes catch discrimination (invisible until a
  differential hits it).
* `java.util.EnumMap → Dictionary` (ordering), `java.io.PrintStream →
  TextWriter`, `java.util.Calendar → DateTimeOffset` are similar
  usage-dependent approximations.

`doc/conversion-concerns.md` explicitly says a name-only substitution table is
not sufficient, and the fail-closed member mappings do most of the real work —
but the type registry is where per-mapping semantic caveats should live and
currently cannot. Each entry should be able to carry conditions or at least a
recorded caveat that review and differential planning can consume.

### 4. C# emission is mostly raw strings

`csharp.clj` is elegant but tiny: seven node kinds with a real precedence
model, everything else is `csharp/raw` text assembled by rules — including in
the kernel itself (`goto`/`try/catch` fragments,
`java_translate.clj:289-347`). Escaping, spacing, and syntactic validity are
re-owned by every rule; `dotnet build` is the only syntax check. Two places
already resort to post-render *text surgery*:

* PdfCube's csproj is produced by `str/replace` on the base project text,
  anchored to exact literal lines (`project-text`,
  `pdfcube/java_project.clj:657-683`) — a silent no-op if the base text ever
  changes.
* The FontBox compatibility-namespace rename requires the replacement
  namespace to have the **same character length** as
  `Vibeformer.Runtime` so source-map offsets survive a post-render
  `str/replace` (`transform-source-text`,
  `pdfcube/java_project.clj:691-701`). That constraint is a wart: destination
  namespace choice is coupled to string length because renaming happens after
  rendering instead of at node construction time.

Full Roslyn is not needed, but the writer needs a few more first-class node
kinds (declaration, block, statement-list with indentation policy) so that
structure-changing transforms happen on nodes, not on rendered text.

### 5. Pkl identities leak into generic namespaces

The boundary rule ("generic namespaces must not depend on a product bundle")
is honored for *code* dependencies but not for *identities and layout*:

* `harness.clj:14-35` hardcodes the full `pkl-parser` profile inline (every
  other profile is an EDN file) and `generate-with-executor!` defaults the
  profile to `"pkl-parser"` (`harness.clj:686`), as do `packaging.clj:1033`,
  `packaging.clj:1175`, and four subcommands in `main.clj:37-42`.
* `differential.clj`, `language_snippet_contract.clj`,
  `language_snippet_runner.clj`, `pkl_core_test_contract.clj`,
  `pkl_core_corpus_runner.clj`, and `public_api_contract.clj` are all
  Pkl-specific (the first is ~1,700 lines of Pkl cases under a generic name)
  yet live at the top level, while PdfCube's equivalents correctly live under
  `pdfcube/`. The layout misrepresents the boundary and makes the generic
  layer look far larger than it is.

### 6. Per-target verification is hand-rolled each time

The three PdfCube differential namespaces each reimplement the same skeleton:
compile-and-run a Java oracle against the pinned checkout, copy a C# probe
into the isolated consumer, run both, compare TSV observations, perturb the
oracle, pin an expected contract, write a summary
(`pdfcube/io_differential.clj`, `pdfcube/fontbox_differential.clj`,
`pdfcube/xmpbox_metadata_differential.clj` — including three private copies of
`current-host`, `write-text!`, and the oracle/probe runners). Each new proof
also requires a new hardcoded subcommand in `main.clj:25-51`. The observation
format (TSV of `family\tid\tvalue` with required families) has already
converged across targets; the harness around it should be one parameterized
namespace plus per-target data.

### 7. Pinned baselines are scattered constants

The PDFBox revision `9286e47d…` and version `3.0.8` appear in the bundle
(`pdfcube/java_project.clj:16-19`), in ten config EDN files, in each
differential (`pinned-revision`, expected `production-sources 74`,
`required-rows 1199`, legal-file SHA-256s), and in expected package versions.
The synchronization contract in `doc/targets/pdfbox/product-goal.md` makes
baseline advancement routine maintenance — but today a `3.0.9` bump is a
scavenger hunt across code and config with no single authoritative record and
no tooling to re-pin expected counts deliberately.

### 8. Hand-rolled validation with opaque failures

`read-profile` validates with one ~40-clause `and` expression
(`harness.clj:95-135`); failure reports "Invalid Vibeformer generation
profile" with the whole map and no indication of which clause failed.
`valid-gradle-profile?`/`valid-maven-profile?` are the same style. Contrast
`project_input.clj` and `java_project.clj/validate-configuration!`, which
report per-field errors. In an agent-driven loop, error specificity is
iteration speed; the weak spots should be brought up to the good spots'
standard (or to a small schema helper that names the failing predicate).

### 9. Parallel resolution over a shared live Spoon model

`validate-references!` and `resolve-closure-occurrences!` run resolution
concurrently over the shared Spoon model (`spoon.clj:649,818` via
`concurrency/mapv-ordered`). Resolution calls like `.getTypeDeclaration`,
`.getActualClass`, and `.getAllExecutables` can populate lazy internal state;
Spoon makes no general thread-safety promise. The single-worker
"deterministic debug mode" (`harness.clj:774`) suggests awareness. Nothing
observed proves a race, but this is the classic source of unreproducible
resolution diagnostics; the safety argument (which Spoon operations are
touched, why they are safe, or what serializes them) is currently recorded
nowhere.

### 10. Duplicated utility helpers

Cross-namespace copies found: SHA-256 file digest in 12 namespaces,
`write-text!` in 9, `xml-escape` in 3, `current-host` in 3, plus
`portable-path` variants in `harness.clj` and `java_project.clj` and two
`expansion-rank` definitions (`spoon.clj:667`, `harness.clj:245`). Individually
trivial; collectively they signal that the "no shared util" line has been
crossed and copies will drift (one already differs: `digest-file` buffer sizes
and option handling).

### 11. Minor

* `spoon.clj` totals hardcode `:shadow-symbols 0 :unresolved-symbols 0
  :ambiguous-symbols 0 :fallback-symbols 0` (`spoon.clj:1076-1080`), and
  `summary-line` prints them as if measured — vestigial fields that make the
  summary lie about being a measurement.
* The kernel hardcodes the runtime type name
  `Vibeformer.Runtime.JavaLabeledControlFlowException`
  (`java_translate.clj:290`) — the one destination identity baked into the
  kernel; it should come from the bundle/runtime contract.
* `Vibeformer.JavaCompat.cs` is a single 8,449-line file containing ~113
  types; `Pkl.Core.Substrate.cs` is 3,172 lines. The one-file-per-assembly
  convention has outlived its convenience.
* Broad `catch Throwable → diagnostic` in resolvers (`spoon.clj:337,449,552`)
  and rule emission (`java_translate.clj:479`) is the right product behavior
  but can disguise translator bugs as "unresolved symbol"; the diagnostic
  should preserve the exception class and stack summary to keep the two
  distinguishable.

## Test suite observation

The suite (~11,600 lines) leans heavily on real integration: fixture projects
with checked-in Gradle/Maven builds, whole-pipeline harness tests, and
per-feature end-to-end tests (`labeled_control_flow_test.clj`,
`linked_hash_map_test.clj`). That matches the "compilation and behavior are
the oracles" philosophy and is the right emphasis for a translator. The cost
is that almost nothing is fast: most tests need a JVM toolchain, Gradle or
Maven, and often `dotnet`. There is no quick sub-second tier for kernel logic
(`csharp_test.clj` and `java_translate_test.clj` are the seed of one). Zero
TODO/FIXME/HACK markers exist in the source — consistent with the
"no deferred work in code" discipline.
