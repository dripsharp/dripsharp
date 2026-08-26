# NuGet Release Runbook

## Scope and authority

This is the durable operator runbook for publishing Brine, PdfCarton, or
SqlTrellis to nuget.org. Each product repository owns one independent,
manually dispatched workflow at `.github/workflows/nuget-release.yml`:

| Product | Repository | Workflow name | Packages, in publish order |
| --- | --- | --- | --- |
| Brine | [`dripsharp/brine`](https://github.com/dripsharp/brine) | `Release Brine to NuGet` | `DripSharp.Brine.Parser`, then `DripSharp.Brine` |
| PdfCarton | [`dripsharp/pdfcarton`](https://github.com/dripsharp/pdfcarton) | `Release PdfCarton to NuGet` | `DripSharp.PdfCarton.IO`, `DripSharp.PdfCarton.Fonts`, `DripSharp.PdfCarton.Xmp`, `DripSharp.PdfCarton`, then `DripSharp.PdfCarton.Preflight` |
| SqlTrellis | [`dripsharp/sqltrellis`](https://github.com/dripsharp/sqltrellis) | `Release SqlTrellis to NuGet` | `DripSharp.SqlTrellis` |

Release one product at a time. There is no cross-product release set or
required order among the three workflows. The order in the last column is
fixed within a product family and follows its package dependencies.

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

Before advancing an intended product commit to a release, run its full local
proof on a host that can safely dedicate 22 workers and a 28-GiB JVM heap:

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

The release workflow starts later, from an existing product-repository commit
on `master`. Its `prepare` job always:

1. restores and builds every published project once in `Release`, with warnings
   as errors;
2. restores, builds, and runs the mandatory product-owned release smoke tests;
3. compiles the shipped test projects and runs only the product's retained
   bounded test selection;
4. packs every published project once, without rebuilding;
5. checks the package ID, version, `netstandard2.0` target framework, exact
   dependency metadata, and expected production assembly;
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

Complete this setup independently in all three product repositories before any
release dispatch.

### Protect the GitHub release environment

In **Settings → Environments**, create an environment named `release`. Add at
least one required reviewer, enable prevention of self-review, disallow
administrator bypass of protection rules, and restrict deployment to the
`master` branch. Do not rely on a workflow run to create this environment:
GitHub otherwise creates it without protection rules.

The workflow grants ordinary jobs only `contents: read`. Its `publish` job is
the only job with `id-token: write`, and that job references the protected
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
push step. If trusted publishing or the protected environment is unavailable,
stop; do not substitute a long-lived secret.

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
3. Confirm that the `release` environment has the required reviewer and branch
   protection and that the matching trusted-publishing policy is active.
4. In the product repository, open **Actions**, select the workflow named in
   the first table, choose **Run workflow**, select `master`, and dispatch it.
   The equivalent GitHub CLI commands are:

   ```sh
   gh workflow run nuget-release.yml --repo dripsharp/brine --ref master
   gh workflow run nuget-release.yml --repo dripsharp/pdfcarton --ref master
   gh workflow run nuget-release.yml --repo dripsharp/sqltrellis --ref master
   ```

   Run only the command for the product being released. Dispatch is an external
   action that can lead to package publication after approval.
5. Record the workflow-run URL and the triggering commit SHA shown by GitHub.
   The workflow rejects any ref other than `refs/heads/master`, then
   `actions/checkout` checks out that triggering product commit. It does not
   select a commit from another repository.

## Review the prepare job

Do not approve publication unless the `prepare` job is green. Review its normal
logs for all of the following product-owned evidence:

* every published project restored and compiled in `Release` with zero errors;
* the mandatory release smoke suite passed;
* the documented bounded test selection passed;
* the essential package metadata and production-assembly check passed;
* the external local-feed consumer printed its success message; and
* the expected artifact was uploaded with 14-day retention.

The artifacts are deliberately small and direct:

| Product | Artifact | Tested package files before `SHA256SUMS` |
| --- | --- | ---: |
| Brine | `brine-nuget-release` | two `.nupkg` and two `.snupkg` files |
| PdfCarton | `pdfcarton-nuget-release` | five `.nupkg` and five `.snupkg` files |
| SqlTrellis | `sqltrellis-nuget-release` | one `.nupkg` and one `.snupkg` file |

The prepare job fails if package counts differ or unrelated files appear. It
sorts the package names and writes their SHA-256 digests to `SHA256SUMS`; the
uploaded artifact contains only those exact tested files and the checksum list.

## Approve and publish

The `publish` job depends on the successful `prepare` job and waits on the
protected `release` environment. The required reviewer must compare the run's
repository, triggering `master` commit, package version, prepare evidence, and
intended nuget.org mutation with the release decision. Reject the deployment if
any value is unexpected. Otherwise choose **Review deployments → Approve and
deploy**.

After approval, the publish job:

1. downloads the artifact produced by that same workflow run;
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
repeat the workflow merely because indexing is still in progress. For every
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
  transient cause, reconfirm that all versions remain unused, obtain a fresh
  release approval, and dispatch the same product commit again.
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
