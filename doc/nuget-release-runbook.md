# NuGet Release Runbook

## Scope and authority

This is the durable operator runbook for publishing Brine, PdfCarton, or
SqlTrellis to nuget.org. Each product repository owns one independent,
manually dispatched workflow at `.github/workflows/nuget-release.yml`:

| Product | Repository | Workflow name | Packages, in publish order |
| --- | --- | --- | --- |
| Brine | [`dripsharp/brine`](https://github.com/dripsharp/brine) | `Release Brine to NuGet` | `DripSharp.Brine.Parser`, then `DripSharp.Brine` |
| PdfCarton | [`dripsharp/pdfcarton`](https://github.com/dripsharp/pdfcarton) | `Release PdfCarton to NuGet` | `DripSharp.PdfCarton` |
| SqlTrellis | [`dripsharp/sqltrellis`](https://github.com/dripsharp/sqltrellis) | `Release SqlTrellis to NuGet` | `DripSharp.SqlTrellis` |

An explicit owner instruction to release a product authorizes the complete
release operation: prepare the requested version (or the next version in the
requested channel), run the required checks, commit and push the source and
generated changes, dispatch the product workflow, publish through its existing
trusted-publishing setup, and verify remote consumption. Do not ask the owner
to confirm publication again or request a separate one-release exception merely
because the existing GitHub environment has no reviewer or branch restrictions.
This authorization also covers retrying a failed attempt after confirming that
no package or symbol was accepted and that the version is still unused.

Honor protection rules that are actually configured. Complete an available
deployment approval within the owner's release authorization when permitted;
if GitHub requires a different reviewer or credentials unavailable to the
operator, report the specific external action needed. Do not remove or bypass
configured protections to make a run proceed. These platform constraints and
the required verification gates remain distinct from asking permission to
perform the release the owner already requested.

The canonical public inventory is exactly four package IDs:
`DripSharp.Brine.Parser`, `DripSharp.Brine`, `DripSharp.PdfCarton`, and
`DripSharp.SqlTrellis`. Its only public dependency edge is
`DripSharp.Brine.Parser` before `DripSharp.Brine`; PdfCarton and SqlTrellis are
independent of the other public packages. `DripSharp.PdfCarton` contains the
IO, Fonts, Xmp, PdfCarton, and Preflight production assemblies. Those five
project and assembly boundaries remain part of the product and its proof, but
they are not separate public package IDs.

Release one product at a time. There is no cross-product release set or
required order among the three workflows. The order in the last column is
fixed within a product family and follows the public dependency graph.

Brine, PdfCarton, and SqlTrellis remain governed by their respective
[Pkl](targets/pkl/product-goal.md),
[PDFBox](targets/pdfbox/product-goal.md), and
[JSqlParser](targets/jsqlparser/product-goal.md) product contracts. RawHTTP
remains [conformance-only](targets/rawhttp/product-goal.md) and has no product
release workflow. A successful release does not prove product completion and
does not change a product goal, approved exclusion, synchronization policy,
shipped-test policy, or completion rule. In particular, each complete adapted
test project and its fixtures remain runnable repository content under its
target contract; test projects are not made NuGet packages by this workflow.
The associated [Pkl scope](targets/pkl/port-scope.md),
[PDFBox scope](targets/pdfbox/port-scope.md) and
[dependency contract](targets/pdfbox/dependencies.md), and
[JSqlParser scope](targets/jsqlparser/port-scope.md) continue to govern the
generated packages and their full proof. This runbook does not supersede them.

## Two distinct proof boundaries

The GitHub release gate is deliberately bounded so it can run on an ordinary
four-core, 16-GB GitHub-hosted runner. It is evidence about whether one already
committed product version can be safely packed and published. It does not
replace the full local product proof in `dripsharp/dripsharp`.

Before advancing an intended product commit to a release, its full local proof
must pass. Check available RAM and CPU before starting; use a host that can
safely dedicate 22 workers and a 28-GiB JVM heap. For a new version or generated
change, follow the two-pass sequence below. The post-commit `product-sync`
already runs the full proof; do not additionally run `proof` for the same
unchanged revision merely to satisfy this runbook.

When only a standalone full proof is needed, select the product's command:

```sh
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run proof pkl
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run proof pdfcube
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run proof sqltrellis
```

Run only the command for the product being advanced. The full proof retains
clean generation, compilation, packaging and independent consumption,
differential and corpus validation, complete adapted upstream tests and
fixtures, and every other gate declared by the target. Follow the separate
[product repository synchronization contract](product-repositories.md) when a
new generated product commit is needed.

### Version changes and synchronization

Check the intended NuGet version's availability and the GitHub release
environment before starting the expensive local checks. Record whether the
existing environment requires a deployment review so the workflow can be
followed to completion. Missing protection rules are not a pending approval and
do not block an owner-requested release or require another confirmation.

For PdfCarton, update these durable inputs together:

1. In `targets/pdfcube/target.edn`, increment
   `:publication :nuget :version-policy :translator-revision` and update all
   five entries under `:publication :nuget :packages`. The revision determines
   the `-alpha.N` suffix; changing package strings alone fails target validation.
2. Update the five package versions in `targets/pdfcube/baseline.edn`, retaining
   the assembly version unless an assembly-version change is intended.
3. Update version-sensitive expectations in the PdfCarton identity, family
   packaging, PDFBox differential, Preflight differential, and Preflight corpus
   tests under `test/dripsharp/`. Search for the previous package version and
   concatenated `-alpha.N` expectations in the five-profile release-selection
   test to catch additional active references; preserve historical records.
4. After pre-commit synchronization, update version-sensitive filenames in the
   product-owned `products/pdfcarton/eng/test-release-packages.sh` and include
   them in the release commit. Editing it earlier leaves the product checkout
   dirty and blocks synchronization.
   This operational script is outside the generator's managed paths. Its tests
   require restored production-project assets; restore them before running it.
5. Regenerate the product README, project files, and generation manifests from
   those inputs. Do not manually bump the generated files.

Use this sequence from the source repository, substituting the corresponding
target and product paths for other products:

1. Inspect the product checkout with `git -C products/pdfcarton status --short`.
   Local builds and restores can leave untracked `bin/` and `obj/` directories.
   Remove only confirmed disposable build outputs before synchronization;
   preserve unrelated changes and do not use a blanket repository cleanup.
2. Run the pre-commit pass:

   ```sh
   DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run product-sync pdfcube
   ```

   It clean-generates and compiles the production profiles, checks their public
   surfaces, runs the generated test suites, and synchronizes managed paths.
   Changed staged bytes are not packaged against the old product commit.
3. Review the synchronized diff, include any product-owned release-tooling
   update, and commit in the product repository. Commit the source changes and
   updated submodule gitlink in the parent repository. Clear any disposable
   build outputs introduced by local tooling tests before the next pass.
4. For PdfCarton, run the explicit post-commit pass:

   ```sh
   DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run product-sync pdfcube --post-commit
   ```

   It runs the complete proof with two independent clean generations and
   exact-commit package builds, including compiled public-surface audits,
   independent consumers, differential/corpus checks and all other target
   gates. It then runs all generated test suites, including the complete
   shipped adapted upstream suite and fixtures, removes transient outputs,
   rechecks the clean product commit and parent gitlink, and requires final
   managed synchronization to make no changes. The separate generation/build/
   audit prefix and pre-proof test run are omitted. A successful pre-commit
   pass alone does not establish this result.

   This flag is PdfCarton-only. For other products, rerun their unflagged
   `product-sync` command. The unflagged PdfCarton command also retains the
   previous sequence for compatibility.
5. Push the product commit before the parent commit that references it, then
   dispatch the release workflow from the verified product `master` commit.

### GitHub release gate

The release workflow starts later, from an existing product-repository commit
on `master`. Its `prepare` job always:

1. restores and builds every production project once in `Release`, with warnings
   as errors;
2. restores, builds, and runs the mandatory product-owned release smoke tests;
3. compiles the shipped test projects and runs only the product's retained
   bounded test selection;
4. packs each production project once, without rebuilding, then applies any
   target-owned public bundle contract; PdfCarton's five component packages are
   internal proof inputs and only its single public package pair leaves the
   prepare boundary;
5. checks the exact public package inventory, ID, version, `netstandard2.0`
   target framework, dependency metadata, and production assembly/PDB set;
6. restores, builds, and runs a temporary package-reference-only consumer from
   an isolated local feed outside the product source tree; and
7. hands the exact tested `.nupkg` and `.snupkg` files to the publish job with a
   `SHA256SUMS` checksum list.

The bounded selections omit only the expensive evidence named here:

| Product | Retained release evidence | Evidence left to the full local proof |
| --- | --- | --- |
| Brine | both production projects compile; release smoke tests run; all shipped test projects compile; retained `DripSharp.Brine.Tests` cases run | the high-memory exhaustive adapted-upstream `UpstreamContractTests` suite |
| PdfCarton | all five production projects compile; release smoke tests run; the shipped test project compiles; focused IO, Fonts, Xmp, PDF, and Preflight consumer cases run | exhaustive adapted-upstream and fixture-integrity suites, plus parent differential and corpus work |
| SqlTrellis | the production, release-smoke, and complete shipped test projects compile; release smoke tests run | the exhaustive adapted-upstream suite, plus parent differential and corpus work |

Those omissions are release-runner resource choices only. They do not make the
omitted behavior optional or excluded.

## One-time publication setup

Configure trusted publishing independently in each product repository. Review
environment protection settings when establishing or changing release
infrastructure; an ordinary release request uses the existing environment.

### GitHub release environment

The workflow uses an environment named `release`. When the owner asks to
establish reviewer-based protection, add the selected required reviewer,
prevent self-review, disallow administrator bypass, and restrict deployment to
`master`. Changing those settings is separate infrastructure work; do not make
it a prerequisite for releasing through an existing unprotected environment.

Inspect the environment early in release preparation, for example:

```sh
gh api repos/dripsharp/pdfcarton/environments/release \
  --jq '{protection_rules, deployment_branch_policy}'
```

An empty `protection_rules` array means there is no configured review gate;
continue the authorized release. A configured environment awaiting approval is
handled through **Review deployments** after the prepare job succeeds, subject
to its actual reviewer, self-review, and branch rules. Do not ask the owner to
name a new reviewer or grant an exception when no such gate exists.

The workflow grants ordinary jobs only `contents: read`. Its `publish` job is
the only job with `id-token: write`, and that job references the
`release` environment. No long-lived NuGet API key belongs in a repository,
organization, or environment secret.

### Configure NuGet trusted publishing

On nuget.org, create three trusted-publishing policies owned by the `DripSharp`
organization. The publishing profile supplied to `NuGet/login` is `isaksky`,
which must remain authorized to publish for that organization. Use these exact
policy bindings:

| Repository owner | Repository | Workflow file | Environment |
| --- | --- | --- | --- |
| `dripsharp` | `brine` | `nuget-release.yml` | `release` |
| `dripsharp` | `pdfcarton` | `nuget-release.yml` | `release` |
| `dripsharp` | `sqltrellis` | `nuget-release.yml` | `release` |

NuGet expects the workflow file name only, not the
`.github/workflows/` prefix. The environment binding is required here. It
prevents a token from another job in the same repository from matching the
policy. The workflow obtains a short-lived key immediately before publication
through the pinned official `NuGet/login` action and exposes it only to the
push step. If trusted publishing cannot authenticate, inspect and resolve the
actual setup or credential failure; do not substitute a long-lived secret.
An environment without protection rules is not an authentication failure.

See NuGet's [trusted-publishing documentation][nuget-trusted-publishing] and
GitHub's [environment protection documentation][github-environments].

## Prepare a release

1. Confirm that the intended generated product commit is already on the
   product repository's `master` branch and that the full local proof for that
   product revision passed. Record the full product commit SHA and intended
   package version. Do not edit generated product files as a release step.
2. Confirm on nuget.org that every ID/version pair in the selected product
   family is unused. NuGet versions are immutable; an availability check does
   not reserve a version.
3. Inspect the existing `release` environment and confirm that the matching
   trusted-publishing policy is active. Follow configured protection rules;
   their absence does not require renewed owner approval.
4. In the product repository, open **Actions**, select the workflow named in
   the first table, choose **Run workflow**, select `master`, and dispatch it.
   PdfCarton defaults to prepare-only and requires the Windows artifact handoff
   described below before its separate publication dispatch. The equivalent
   preparation/dispatch commands are:

   ```sh
   gh workflow run nuget-release.yml --repo dripsharp/brine --ref master
   gh workflow run nuget-release.yml --repo dripsharp/pdfcarton --ref master
   gh workflow run nuget-release.yml --repo dripsharp/sqltrellis --ref master
   ```

   Run only the command for the product being released. Dispatch is an external
   action authorized by the owner's release request. Publication follows a
   successful prepare job and any deployment gate actually configured for Brine
   and SqlTrellis. PdfCarton additionally requires the separate supported Windows
   artifact handoff before publish mode is dispatched.
5. Record the workflow-run URL and the triggering commit SHA shown by GitHub.
   The workflow rejects any ref other than `refs/heads/master`, then
   `actions/checkout` checks out that triggering product commit. It does not
   select a commit from another repository.

## Review the prepare job

The workflow must keep publication dependent on a successful `prepare` job.
Review its normal logs for all of the following product-owned evidence:

* every production project restored and compiled in `Release` with zero errors;
* the mandatory release smoke suite passed;
* the documented bounded test selection passed;
* the essential package metadata and production-assembly check passed;
* the external local-feed consumer printed its success message; and
* the expected artifact was uploaded with 14-day retention.

The artifacts are deliberately small and direct:

| Product | Artifact | Tested package files before `SHA256SUMS` |
| --- | --- | ---: |
| Brine | `brine-nuget-release` | two `.nupkg` and two `.snupkg` files |
| PdfCarton | `pdfcarton-nuget-release` | one `.nupkg` and one `.snupkg` file; the pair contains all five production DLL/PDB pairs |
| SqlTrellis | `sqltrellis-nuget-release` | one `.nupkg` and one `.snupkg` file |

The prepare job fails if package counts differ or unrelated files appear. It
sorts the package names and writes their SHA-256 digests to `SHA256SUMS`; the
uploaded artifact contains only those exact tested files and the checksum list.

## Approve and publish

The `publish` job depends on the successful `prepare` job and uses the `release`
environment. Without configured protection rules it proceeds automatically;
the owner's release request already authorizes that publication. When a review
gate exists, compare the repository, triggering `master` commit, package
version, prepare evidence, and intended nuget.org mutation with the release
decision before choosing **Review deployments → Approve and deploy**. Reject
unexpected values and honor any requirement for a different reviewer.

Once any configured gate is satisfied, the publish job:

1. downloads the artifact produced by that same workflow run (or, for PdfCarton,
   the exact prior prepare-only run bound to the Windows-tested checksum list);
2. runs `sha256sum --check --strict SHA256SUMS` and rejects any unexpected or
   missing package;
3. obtains a short-lived NuGet API key through the environment-bound trusted-
   publishing policy; and
4. pushes the primary packages to
   `https://api.nuget.org/v3/index.json` in the fixed order from the first
   table. Each primary package's paired `.snupkg` is present for symbol
   publication.

No package uses skip-duplicate behavior. A checksum failure, login failure, or
package push failure stops the job and prevents later ordered pushes.

## Verify the published product

NuGet validates and indexes primary and symbol packages after upload. Do not
repeat the workflow merely because indexing is still in progress. A successful
publish job with `Created` and `Your package was pushed` for both the `.nupkg`
and `.snupkg` confirms upload acceptance, while the NuGet version index and
search results can still lag. Report that distinction, wait for indexing, and
continue the checks below; do not diagnose a failed upload from an initially
missing index entry or attempt to republish the immutable version. For every
ID/version in the product family:

1. confirm that the primary package and its symbols complete validation;
2. confirm in the nuget.org owner view that `DripSharp` owns the package; and
3. restore the exact published version into a new disposable `net10.0` consumer
   whose `NuGet.Config` contains only
   `https://api.nuget.org/v3/index.json`, whose package cache is empty, and
   whose references are package references rather than project references.

Build and run a representative public behavior for every package in the
family. The pre-publication external consumer in the workflow is the minimum
behavior model: Brine loads parser and evaluator behavior; PdfCarton loads and
exercises IO, Fonts, Xmp, PDF, and Preflight; SqlTrellis parses, mutates,
visits, and deparses SQL. A clean remote restore and successful behavior run
complete the release operation, but still do not establish product completion.

## Failure and immutable-version recovery

NuGet package ID/version pairs are immutable. On any publish failure, stop and
inspect nuget.org to determine which primary and symbol packages were accepted.
Do not manually push later packages, add skip-duplicate behavior, or assume a
failed client response means the server accepted nothing.

* If no package or symbol from the product family was accepted, resolve the
  transient cause, reconfirm that all versions remain unused, and dispatch the
  same product commit again under the original release authorization.
* If any primary or symbol package was accepted, the family release is partial.
  Record the remote state, unlist only with separate owner authorization if
  appropriate, assign a new version to the entire product family through the
  normal durable source and generation process, run the full local proof, and
  release the new product `master` commit from the beginning of this runbook.

One product family's partial failure does not authorize, block, or combine the
other two product workflows. Ownership transfer, unlisting, deprecation, and
support requests are separate external actions. See NuGet's
[package deletion and unlisting policy][nuget-delete] and
[deprecation guidance][nuget-deprecate].

[github-environments]: https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments
[nuget-delete]: https://learn.microsoft.com/en-us/nuget/nuget-org/policies/deleting-packages
[nuget-deprecate]: https://learn.microsoft.com/en-us/nuget/nuget-org/deprecate-packages
[nuget-trusted-publishing]: https://learn.microsoft.com/en-us/nuget/nuget-org/trusted-publishing

## PdfCarton prerequisites and local recovery

Before synchronization, run the read-only prerequisite check:

```sh
clojure -J-Xmx512m -M:run product-preflight pdfcube
```

The supported `product-sync`, `product-prepare`, `pack`, `package`, and full
`proof` entry points perform the same check before expensive work. Target
loading checks coordinated baseline/package versions. Repository checks reject
uninitialized or dirty products, mismatched parent gitlinks and staged parent
changes; staging need not exist and need not match the old product commit.
The error lists affected paths. Remove only confirmed disposable build output;
commit or preserve unrelated edits deliberately. Nothing is cleaned or stashed
by preflight. Required release scripts and the workflow's exact triggering
commit properties are checked before generation.

PdfCarton preflight also checks the configured Windows SSH host, installed
stable .NET 10 SDK/runtime, Framework 4.8.1, x64 architecture and available
memory/disk. Set `PDFCARTON_WINDOWS_HOST` to an existing SSH alias when needed.
An unavailable host, missing runtime, or insufficient capacity is an actionable
failure, never a skipped pass. Do not install tools, reboot the guest or change
SSH security as part of this check. Read the installed `windows-testing` skill
and its environment, storage and remote-command guidance before execution.

For a live owner-authorized release, also run
`product-preflight pdfcube --live` before preparation and again immediately
before dispatch. It reads current GitHub workflow, Actions and environment
settings and confirms that the public NuGet version is unused. Empty environment
protection rules remain valid. These checks do not publish or reserve a version.
The hosted publish job repeats version availability immediately before upload.

PdfCarton pre-commit preparation generates the complete dependency family once
through `pdfcube-preflight`, builds its dependency graph and audits all five
assemblies. It rejects missing or duplicate profile/audit evidence. It does not
separately regenerate transitive dependencies for each profile. The two
independent package reproducibility builds remain independent. Within each
stamping pass, projects build in dependency order with project-reference builds
disabled, so already stamped dependencies are not recursively rebuilt. The
initial clean compiled-surface build and the later exact-commit stamping build
remain distinct.

After committing the pre-commit result and updating the parent gitlink, use
`product-sync pdfcube --post-commit` for the post-commit pass. The approved
two-pass sequence schedules three complete-family generations instead of
four, and two generated-test-suite runs instead of three. It retains both
independent exact-commit package builds and the final complete shipped tests,
cleanup, repository comparison and no-drift synchronization. These counts
describe removed stage invocations; they do not predict a whole-release time
saving.

The explicit post-commit mode starts a fresh complete proof and does not cache
the pre-commit result. Changes to source, runtime, mapping, generated files or
package version require the pre-commit synchronization and commit steps again.
A product-owned release-tooling or metadata change requires proof and fresh
package stamping against the resulting clean product commit and parent gitlink,
even if generated source bytes are unchanged. Every package must identify that
commit, and changed staged bytes cannot be attributed to an older revision.
No final synchronization in this mode may introduce managed changes. Product
goals, exclusions, shipped-test requirements and completion semantics are
unchanged.

To retain a successful package stage when a later validator fails, select a
local run name on the **first** attempt and reuse it on retry:

```sh
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run proof pdfcube --resume release-check
```

Run state and isolated package feeds live under
`validation-output/pdfcarton-proof-runs/release-check/`, outside generation
cleanup. A workspace file lock excludes conflicting generation. The phase log
marks executed, failed and reused stages; reuse is not a fresh build pass.
Only a completed package stage with matching input fingerprints and unchanged,
present outputs can be reused. Source, runtime, mapping, configuration, upstream,
package version, product commit, release metadata, build options or toolchain
changes invalidate it. Parent HEAD alone is not an input. The explicitly named
family/differential validator files are excluded from package inputs, and all
validators and consumers run freshly on every retry. Unknown source changes
rebuild. Missing or modified outputs also rebuild. No old validator pass is
substituted for the repaired check.

This package recovery command does not certify a new synchronization or waive
its final shipped tests, exact-commit comparison or no-drift checks. Complete
the required synchronization procedure before release. Candidate and published
Windows verification are separate commands and result directories; neither
accepts a previous Windows or macOS result. A checker-only retry can reuse the
unchanged package archive while executing the corrected tests again.

## Required PdfCarton Windows artifact handoff

PdfCarton's release workflow defaults to `mode=prepare`: it builds and verifies
on the existing hosted runner, retains the exact public package pair and
`SHA256SUMS`, and **does not publish**. The publish mode consumes that successful
prepare run without generation, rebuilding or repacking. Brine and SqlTrellis
retain their existing workflow sequence.

To inspect a prepared artifact without publishing, use the optional diagnostic
command below. During an authorized release, use `publish-prepared` directly;
it includes this verification, so both commands are not required:

```sh
python3 scripts/verify-pdfcarton-windows.py verify-prepared \
  --run-id PREPARE_RUN_ID --version VERSION --commit FULL_PRODUCT_SHA
```

This command downloads the successful master workflow's package pair, checks its
inventory/checksums and embedded product commit, transfers it into a new owned
short `C:\WT` run directory, and restores into a fresh isolated package cache.
The combined public package is the sole product PackageReference: no source or
project references supply production assemblies. Existing mandatory smoke and
five-assembly consumers execute on Windows x64/.NET 10, with installed-font
initialization, empty-home/cache fallback, IO/Fonts URI handling, discovered-font
PDF save/reload and native rendering. A separate bounded `net481` executable
runs the four compatibility exception types in their three runtime copies on
actual .NET Framework 4.8.1, preserving serialized state. Production remains
`netstandard2.0`; this is not a general Framework certification or exhaustive
platform matrix.

When the owner has authorized publication, the supported dispatch command is:

```sh
python3 scripts/verify-pdfcarton-windows.py publish-prepared \
  --run-id PREPARE_RUN_ID --version VERSION --commit FULL_PRODUCT_SHA
```

It performs the exact candidate Windows check, then rechecks live release
prerequisites and remote master before dispatch. Failure, missing results,
transport timeout, runtime absence, stale identity or changed artifact bytes
stop before dispatch. The publish job checks the successful prepare-only run,
same product SHA and supplied checksum-list digest before uploading those exact
Windows-tested bytes. Do not dispatch `mode=publish` manually around this
command. Honor any configured environment protections; no extra owner approval
is introduced for an unprotected environment.

After upload and indexing, run the distinct published-feed check:

```sh
python3 scripts/verify-pdfcarton-windows.py published \
  --version VERSION --commit FULL_PRODUCT_SHA
```

It restores from NuGet.org only into another empty Windows cache and executes
the same bounded coverage. Published archive signatures may change its hash;
ID, version, repository commit and the five-assembly contract must still match.
Upload acceptance alone is not completed remote verification. Report a failed
or pending published check separately and do not republish the immutable version.

For an already-built local candidate, `candidate --package FILE --version VERSION
--commit FULL_PRODUCT_SHA` runs the same tests without a workflow or production
regeneration. `--host` and `--results` configure host and local result directory.
Normal `target/windows-tests/<run>/` output includes the request/input digest,
resource/runtime observations, per-stage timings and exit codes, package identity,
TRX and stdout/stderr logs retrieved even when a check fails. Unavailable or
missing results never count as success. Preserve relevant logs before later
clean generation removes disposable `target/` output. Run guest work sequentially
and sized to its measured capacity; never send the Mac's worker/heap settings.
