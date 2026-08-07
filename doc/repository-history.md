# Repository Work History

## Scope and method

This report reconstructs the repository's development history through commit
topology, local and remote-tracking reflogs, surviving side branches, commit
messages, and representative diffs. It describes the history visible from the
local repository at `54c3e117b6fca151ecad0a9d40eadac679866f08` on 2026-08-06.
Dates below are commit or reflog times in the repository's local `-06:00`
offset.

This is a historical characterization, not a completion assessment. The
product goals under `doc/targets/` remain authoritative, including their rule
that a green bounded milestone does not establish full product completion.

The evidence has two limits. The oldest retained `master` reflog entry is from
2026-07-01, so earlier branch movements cannot be reconstructed from the
reflog. Reflogs are also local and expiring. The commits and named archive
branches are stronger durable evidence than the reflog alone.

## Summary characterization

DripSharp's history is a compressed transition from an exploratory translator
research project into a governed, multi-product translation and publication
system.

The first week built breadth quickly: Spoon and Kotlin frontends, a Datomic
fact model, inventories, transform rules, sample generation, and Pkl research
dry runs. The next twelve days accumulated 1,118 commits on top of the Pkl
scope baseline. That line repeatedly widened selected slices, repaired
diagnostics, added stubs and retries, moved scope boundaries, and eventually
made release- and project-completion claims that the later history explicitly
rejected. On 2026-07-09, `master` was deliberately rewound to the last trusted
scope decision and rebuilt around direct typed-AST translation.

The replacement line made substantially stronger product progress, but it did
not become linear immediately. It discarded a 46-commit NuGet metadata
fingerprinting loop and later archived a 23-commit row-exact schema-evidence
detour. From 2026-07-22 onward the work became directionally steadier: the
translator was separated from target-specific behavior, PDFBox became a
second product family, Vibeformer became DripSharp, reusable mappings and test
adaptation were extracted, and JSqlParser became a third product family.

The most recent work is stable in direction rather than quiet in volume. It
advances generated product repositories, complete adapted upstream test
suites, release proofs, NuGet publication tooling, synchronized product
revisions, and architecture documentation. No comparable rewind appears after
2026-07-22. The history is still very young and active, so this should not be
read as evidence that every governed product goal is finished.

![DripSharp repository history from research loop to multi-product
platform](architecture-diagrams/dripsharp-repository-history.svg)

The editable source for this dynamic history view is
[`dripsharp-repository-history.d2`](architecture-diagrams/dripsharp-repository-history.d2).

## Quantitative landmarks

| Line or interval | Commits | What it represents |
| --- | ---: | --- |
| Initial commit through `1d09c0da` | 167 | Shared exploratory foundation and the durable Pkl scope decision |
| `1d09c0da..master-old-2` | 1,118 | First continuation, abandoned by the major rewind |
| `1d09c0da..master` | 469 | Replacement continuation through the report snapshot |
| Current `master` total | 636 | The shared 167 commits plus the 469-commit replacement line |
| `7f03727c..nuget-madness` | 46 | Abandoned NuGet fingerprinting/hardening loop |
| `f6739e3e..8e904890` | 23 | Row-exact schema-evidence detour removed from `master` |

The commit rate itself is historically informative. The abandoned first
continuation contains 1,118 commits from 2026-06-27 through 2026-07-09,
including 124 on July 1, 119 on July 2, 135 on both July 3 and July 4, 117 on
July 5, 103 on July 7, and 109 on July 8. The replacement line also had an
intense hardening burst: 86 commits on July 29 and 91 on July 30. It then fell
to 42 commits total from July 31 through August 6 while landing larger product
milestones.

Commit-message counts reinforce the distinction, though they are only lexical
signals. In the 1,118 abandoned commits, 94 subjects mention Spoon, 58 mention
Kotlin, 37 mention retries, 20 mention stubs, 42 mention scope or exclusions,
and only one mentions a differential. In the 469-commit replacement line, 16
subjects mention differentials, 30 mention NuGet, 69 mention PDF work, 12
mention SqlTrellis or JSqlParser, and 72 mention Brine or Pkl. The later line is
more visibly organized around end-to-end products and independent behavior
evidence.

## Chronology

### 1. Exploratory foundation: 2026-06-23 through 2026-06-27

The repository began as Vibeformer at `172f4965`. The initial architecture was
ambitious and mechanism-heavy from the start:

* `6809c384` added the Spoon Java parser smoke test.
* `8dfd03c5` added the Kotlin parser smoke test.
* `1ae75bb2` added Datomic Local.
* `d835578e`, `f03d97d8`, and `11b26bda` established a Datomic fact schema and
  normalized Java/Kotlin extraction.
* `4220d9fa` added feature inventory reports.

Between the initial commit and `1d09c0da`, 166 further commits changed 100
files with about 31,000 insertions. This was productive discovery: it exposed
the semantic and tooling surface of Java/Kotlin-to-C# translation. It also
created a large intermediate representation and reporting apparatus before a
single product path had been proved end to end.

The important boundary is `1d09c0da` on 2026-06-27, "Port scope, library scope
decisions." It defined Pkl's intended .NET library surface. That commit later
became both the rewind point and the fixed baseline cited by the current Pkl
product goal.

### 2. The first scaling attempt and its failure: 2026-06-27 through 2026-07-09

The line now preserved as `master-old-2` grew by 1,118 commits in roughly
twelve days. Its subjects show recurring local progress loops:

* resolving one Java or Kotlin reference category at a time;
* expanding or constraining selected source slices;
* repeatedly extending Spoon retry budgets and companion-source recovery;
* generating stubs, suppressing stubs, and ratcheting acceptance counters;
* closing narrowly defined Beads epics and then opening the next adjacent
  widening or diagnostic task.

There was real progress on parsing, generation, schema codegen, imports,
resources, and package consumption. The problem was not that the work produced
nothing. The problem visible in hindsight is that local evidence and milestone
machinery started standing in for the product boundary. Representative late
subjects include `72606eee`, "PROJECT_COMPLETED!: validate .NET library release
readiness," followed by `63533bda`, "Fix bad scope," `0fec3bf0`, "Remove spam,"
and `80a7a46f`, "Try to get on track." The abandoned line's final commits then
implemented more evaluator behavior and updated documentation.

The replacement product-goal document in `bb1c2335` directly records the
lesson: difficulty, missing implementation, absence from a selected slice, or
a green bounded milestone are not scope decisions, and a bounded milestone
must never report project completion. That makes scope drift and premature
completion semantics the clearest documented mistake in the old line.

### 3. The major rewind and direct-AST restart: 2026-07-09

The rewind was deliberate and recoverable:

1. At 21:22, branch `master-old-2` was created at `5453495e`, preserving the
   old tip.
2. At 21:27, the `master` reflog records `reset: moving to 1d09c0da...`.
3. At 21:45, `bb1c2335` committed "Reset Vibeformer around direct typed AST
   translation."
4. At 21:54, `3eb3101a` followed with "Fix docs, reset beads." The
   remote-tracking reflog moved from `5453495e` to this non-descendant, showing
   that the rewritten line was also pushed.

The topology is unambiguous: `master` and `master-old-2` share `1d09c0da` as
their merge base; `master-old-2` has 1,118 commits absent from the replacement
line. The restart also removed much of the original shared implementation.
`bb1c2335` deleted roughly 29,000 lines, including the Datomic schema, fact
ingestion, research dry-run pipeline, old C# emitter, transform rules, and
their tests. It retained the product goal but replaced the architecture with a
shorter path:

`resolved source and classpath -> Spoon typed semantic AST -> recursive
translation -> disposable C# -> compile -> independent behavior comparison`.

This was the repository's decisive architectural correction: keep typed
semantic resolution, but stop treating a persisted fact model, selected-slice
reports, or provenance machinery as the product.

### 4. Fast reconstruction and a second progress loop: 2026-07-09 through 2026-07-16

The restart rebuilt vertically. By `08cf82b6` it translated all executable
Pkl-parser Spoon bodies and resolved symbols; `903bb667` drove the generated
parser to deterministic clean compilation; `ddaa37a9` compiled the Pkl core
closure; and `7f03727c` proved reproducible NuGet output across clean builds.

The line then spent 46 commits hardening exact NuGet/CLR/PE fingerprints, from
restore isolation through method RVAs and binary layout metadata. At
2026-07-16 07:16 this work was preserved as `nuget-madness` at `f9aaf949`, and
`master` was reset to `7f03727c`. The branch name and repetitive subjects make
the diagnosis unusually explicit: the proof mechanism had outrun the current
product need. Unlike the July 9 rewind, this correction kept the rebuilt
translator and removed one bounded detour.

### 5. Product behavior, generalization, and an evidence detour: 2026-07-16 through 2026-07-22

After the NuGet reset, work returned to user-facing Pkl behavior:

* `45b83b74` added packaged schema generation and typed config binding.
* July 17-20 added loading, policy, evaluator, diagnostic, analyzer, logging,
  public API, and full-corpus behavior gates.
* `73ded096` through `6afc7a2a` translated RawHTTP and proved package-only
  Java/.NET equivalence, demonstrating reuse outside Pkl.
* `f6739e3e` completed the then-current clean full-product gate while preserving
  generic nullability contracts.

The next 23 commits built row-specific, observation-exact schema-binding
evidence. On July 21 they were moved off `master`; the archive branch has 24
unique commits because `0364314d` adds an archive marker after the 23-commit
detour. `master` reset to `f6739e3e`, then `8936f24a` removed more than 4,000
lines of schema-contract machinery and restored focused product verification.

On July 22, one 191-line architecture commit (`3a562f37`) was reset and
immediately replaced by the materially equivalent `d763ae51`. This was a
one-commit history cleanup, not another architectural rewind.

### 6. Multi-target platform and product expansion: 2026-07-22 through 2026-07-30

`3d5392e8` reorganized the documentation around multiple independently governed
targets. `8812af33` added the PDFBox target, initially using the PdfCube working
identity. Over the next several days the history translated and verified IO,
FontBox, XmpBox, PdfBox, Preflight, image codecs, document workflows, and a
multi-package family. This provided the forcing function for separating shared
Java lowering from Pkl-specific rules.

`86f5130d` on July 25 renamed Vibeformer to DripSharp and promoted the Clojure
project to the repository root. On July 27-28, commits such as `38acdeec`,
`becf0aef`, `bf6c4a8a`, and `26519267` composed Pkl over a shared Java-library
foundation and moved symbol mappings into validated declarative registries.
`917fc62d` formalized RawHTTP as a permanent translator conformance target. It
is important to distinguish RawHTTP from the published products: its role is
to prove translator generality on a smaller independent Java library.

July 29-30 combined genuine platformization with another high-density hardening
loop. The line added target-directory contracts, reviewed baselines, legal and
authorship boundaries, product repository synchronization, alpha assembly,
and extensive fail-closed checks for paths, symlinks, host configuration,
NuGet, and MSBuild. The 177 commits across those two days remain ancestral to
`master`, unlike the earlier detours, and they support the current release and
publication model. Still, the repetitive one-condition-per-commit subjects
show that the repository retained a tendency to spend long runs on proof and
environment hardening after a product milestone became green.

The same interval established final public identities: `3a9f82c4` documented
Brine for the Pkl product, and `f4aef1ca` adopted PdfCarton for the PDFBox
product. `d4526c68` then implemented fail-closed synchronization of generated
product repositories.

### 7. Third product and recent stable work: 2026-07-31 through 2026-08-06

The last phase is less dominated by rewrites and more by reusable delivery:

* `e84de029` defined SqlTrellis, a complete JSqlParser-to-.NET product target.
* `f7509b32` through `df50f77f` generalized Java test-suite modeling and JUnit
  4/Jupiter-to-xUnit adaptation across targets.
* `9cce07f0` and `92369ac2` translated the JSqlParser model, statement API,
  parser, and utility graph.
* `f1bfb355`, `e51f2123`, and `6c3e9984` shipped its adapted tests,
  differential evidence, and generated product repository.
* `10bb3fc7` and `8d06a277` expanded Brine and PdfCarton to complete adapted
  upstream test suites under their governed policies.
* `f4e4870b` completed the SqlTrellis release proof.
* `c6d95868` through `747ec5ab` added gallery-ready metadata, aggregate release
  preparation, fail-closed publishing, release-set preflight, and the NuGet
  release runbook.
* The eight local commits after `origin/master` advance synchronized Brine,
  PdfCarton, and SqlTrellis product revisions and add architecture diagrams and
  package-evidence checks, including target-scoped differential legal evidence
  in `54c3e117`.

This is the strongest evidence for recent stability: three independently
governed product families now exercise a shared translator, test adaptation,
package proof, and publication path; commits increasingly land as coherent
cross-cutting milestones; and no side branch records another abandoned
direction after July 22. The caution is equally clear: the period is only one
week long at this snapshot, and continued product work must still be measured
against each target's full goal rather than the latest release gate.

## Product lineage

| Upstream | DripSharp role | Public product identity | Historical landmarks |
| --- | --- | --- | --- |
| Pkl | First product and original requirements driver | Brine | Scope baseline `1d09c0da` (Jun 27); authoritative restart `bb1c2335` (Jul 9); Brine identity `3a9f82c4`/`6ed283c2` (Jul 29) |
| Apache PDFBox | Second product and main generalization driver | PdfCarton | Target introduced as PdfCube by `8812af33` (Jul 22); product identity and repository `f4aef1ca` (Jul 29) |
| RawHTTP | Permanent conformance target, not a product repository | None | Full translation/equivalence `73ded096..6afc7a2a` (Jul 20-21); formal target `917fc62d` (Jul 28) |
| JSqlParser | Third product and test-adaptation driver | SqlTrellis | Goal `e84de029` (Jul 31); full translation `9cce07f0`/`92369ac2` (Aug 1-2); product repository `6c3e9984` (Aug 3) |

## Recurring lessons visible in the history

1. **Scope must be durable and human-owned.** The largest rewind followed a
   period in which selected-slice readiness and project completion were
   conflated. The fixed product goals and current `AGENTS.md` rules are direct
   institutional memory of that failure.

2. **Vertical behavior evidence is more valuable than exhaustive internal
   evidence.** The successful restart shortened the core path and emphasized
   clean generation, compilation, package consumption, and independent
   behavior comparison. Two later detours were cut when metadata or row-exact
   evidence became an end in itself.

3. **A second product changed the architecture more than more work on the first
   product did.** PDFBox forced reusable Java-library lowering, neutral target
   contracts, declarative mappings, multiple package graphs, and the DripSharp
   identity. RawHTTP supplied an earlier small-scale check; SqlTrellis then
   validated test-framework reuse.

4. **The project works in very small commits and can enter proof-hardening
   loops.** This makes failures and reversals recoverable, but commit count can
   substantially overstate forward product movement. Named preservation
   branches and explicit resets were healthy corrections to that tendency.

5. **Recent work is converging on delivery infrastructure.** Generated product
   repositories, full adapted upstream suites, synchronized revisions, release
   proofs, and NuGet handoff documentation are qualitatively different from
   the early selected-slice and diagnostic-counter loops. They connect the
   translator to independently consumable outputs while preserving separate
   product-goal authority.

## Reproducing the main findings

The central topology can be checked without changing the current checkout:

```sh
git merge-base master master-old-2
git rev-list --left-right --count master...master-old-2
git merge-base master nuget-madness
git rev-list --left-right --count master...nuget-madness
git merge-base master archive/schema-evidence-detour-2026-07-21
git rev-list --left-right --count master...archive/schema-evidence-detour-2026-07-21
git reflog show master --date=iso-strict
git log --all --graph --decorate --date=iso-strict --oneline
```

At this snapshot the first pair returns merge base `1d09c0da...` and counts
`469 1118`. The named branches preserve the work that was intentionally moved
off `master`, so no historical checkout or destructive operation is required
to inspect it.
