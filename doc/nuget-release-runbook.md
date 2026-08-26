# Local NuGet Release Runbook

## Scope and authority

This runbook is the durable operator contract for preparing, inspecting, and,
after separate authorization, publishing the production NuGet release set. Run
every command from the root of `dripsharp/dripsharp`.

The release set contains exactly these four production packages:

| Position | Package | Approved release version | Internal dependencies |
| ---: | --- | --- | --- |
| 0 | `DripSharp.Brine.Parser` | `0.32.0-alpha.1` | none |
| 1 | `DripSharp.PdfCarton` | `3.0.8-alpha.2` | none within DripSharp; contains the IO, Fonts, XMP, PDFBox, and Preflight assemblies |
| 2 | `DripSharp.SqlTrellis` | `5.3.0-alpha.1` | none |
| 3 | `DripSharp.Brine` | `0.32.0-alpha.1` | `DripSharp.Brine.Parser` at the exact version |

The preparation manifest computes this deterministic dependency-first order;
operators and automation must not reconstruct or reorder it. Exact external
dependencies are also recorded and inspected in each package entry.

A target-specific manifest is also a complete release boundary for that target.
For PdfCarton, `nuget-release-prepare pdfcube` emits exactly one `.nupkg` and
one `.snupkg`, both named `DripSharp.PdfCarton.3.0.8-alpha.2`, and the same
preflight and publisher accept that one-package manifest without requiring a
simultaneous Brine or SqlTrellis release.

Brine, PdfCarton, and SqlTrellis remain governed by their respective
[Pkl](targets/pkl/product-goal.md),
[PDFBox](targets/pdfbox/product-goal.md), and
[JSqlParser](targets/jsqlparser/product-goal.md) product contracts. RawHTTP
remains [conformance-only](targets/rawhttp/product-goal.md). The complete
adapted test projects and fixtures required by the target contracts remain
runnable, shipped repository evidence but are non-packable and are not part of
the four-package inventory. A successful release is not product completion
and does not change any product scope, exclusion, synchronization rule, or
shipped-test policy.

All four production packages contain assemblies and portable symbols only
under `lib/netstandard2.0`, with canonical `.NETStandard2.0` dependency groups.
Release preparation and remote validation use `net10.0` consumers to restore,
compile, load, and exercise those assemblies. Compatibility with .NET Framework
4.8 is inferred from the `netstandard2.0` contract and compatible dependency
closure; this repository does not run a net48 build, host, VM, CI job, or
compatibility test and does not empirically certify net48 execution.

All steps before **Authorized live push** are either local-only or explicitly
read-only. No command in this document implicitly authorizes an upload,
unlisting, ownership change, tag, release, or GitHub Actions workflow.

## Ownership and approval gates

### Approved non-secret decision

Inspect the human-approved package decision before preparing a release:

```sh
br show pkl-4m8d.1 --json
```

The command must exit zero and show a closed decision with all of these exact
values:

| Field | Approved value |
| --- | --- |
| Package `Authors` | `Isak Sky` |
| nuget.org owner | organization `DripSharp` |
| Publishing account | individual account `isaksky` acting for `DripSharp` |
| Source | `https://api.nuget.org/v3/index.json` |
| Project URLs | `https://github.com/dripsharp/brine`, `https://github.com/dripsharp/pdfcarton`, and `https://github.com/dripsharp/sqltrellis` |
| Repository URLs | the corresponding project URL with `.git` appended |
| Repository type | `git` |
| Repository commit | exact clean generated-product commit used to build the package |
| Initial release status | the three `alpha.1` prerelease families listed above |

Stop if the decision is absent, open, or different. The target-owned
`targets/pkl/target.edn`, `targets/pdfcube/target.edn`, and
`targets/sqltrellis/target.edn` contracts must also name this decision and the
same values; preparation and publication revalidate those contracts.

`Authors` is display metadata embedded in a package. It neither authenticates
the publisher nor assigns gallery ownership. nuget.org assigns management and
future-publish rights to package owners. For the first push, `isaksky` must be
an authorized member of the `DripSharp` nuget.org organization and the API key
or trusted-publishing policy must be created for the `DripSharp` owner. See
NuGet's [package-owner guidance][nuget-publish] and
[organization model][nuget-organizations]. Never infer ownership from the
package's `Authors`, repository owner, or namespace.

The approved metadata decision does **not** authorize a live push. Immediately
before a future live step, a human must separately approve this exact release:

* the manifest path and SHA-256;
* every ID/version pair in the selected manifest and its printed publish order;
* the `DripSharp` organization owner and `isaksky` publishing account;
* the exact nuget.org source; and
* the intended external mutation.

The `--authorize-publish` switch is the operator's assertion that this separate
approval exists. It is not itself approval and must not be added speculatively.
Approval records contain the manifest digest and non-secret facts only, never a
credential.

### Account and credential prerequisites

Before a future first push, confirm in nuget.org that the `DripSharp`
organization exists, that `isaksky` has the required organization role, and
that publication notifications and two-factor authentication are enabled. If a
temporary local API key is explicitly approved, create it under the
`DripSharp` owner with only push scope and the narrowest package glob that
covers `DripSharp.*`. Do not paste its value into a shell command, file,
NuGet.Config, log, GitHub secret, issue, chat, or release artifact.

The local driver reads `NUGET_API_KEY` from its environment and does not persist
or print it. It supplies that value to NuGet's supported `--api-key` and
`--symbol-api-key` options while exposing only a redacted display command in
results and failures.

The release contract imposes no minimum .NET SDK patch version. The local
preparation, inspection, remote check, and dry-run do not require a
publication credential.

## Prepare the complete release

Preparation runs full proof, clean generation, compilation, Release packing,
two-pack byte comparison, exact package inspection, fresh local-feed
publication, isolated consumer validation, and generated-product commit checks
for every selected target. Before this full gate, check the host's available
RAM and CPU. On macOS:

```sh
sysctl -n hw.memsize
sysctl -n hw.logicalcpu
```

The host must be able to dedicate a 28 GiB JVM heap and 22 workers without
unsafe memory pressure. Stop or move to a suitable host if it cannot.

Prepare only the complete `all` selection used by preflight and publication:

```sh
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run nuget-release-prepare all
```

The command must exit zero. Its final line has this shape:

```text
Credential-free NuGet release preparation passed: {:artifact-directory ".../target/nuget-release/all", :manifest ".../target/nuget-release/all/release-manifest.edn", :manifest-sha256 "<64 lowercase hexadecimal characters>", :products 3, :packages 4, :publish-order ["DripSharp.Brine.Parser" "DripSharp.PdfCarton" "DripSharp.SqlTrellis" "DripSharp.Brine"]}
```

Set local path variables only after that success:

```sh
RELEASE_DIRECTORY="$PWD/target/nuget-release/all"
RELEASE_MANIFEST="$RELEASE_DIRECTORY/release-manifest.edn"
export RELEASE_DIRECTORY RELEASE_MANIFEST
```

The preparation entry point accepts no credential and records
`:network-mutations []`, `:publication-credentials-accepted false`, and
`:remote-availability :not-checked`. It does not reserve an ID and it makes no
network mutation.

To prepare and validate only PdfCarton instead, use:

```sh
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run nuget-release-prepare pdfcube
clojure -M:run nuget-release-preflight target/nuget-release/pdfcube/release-manifest.edn --check-nuget-org
clojure -M:run nuget-release-publish target/nuget-release/pdfcube/release-manifest.edn
```

The last command is a credential-free dry run. The separately authorized live
form is documented under **Authorized live push** below; use the same
`pdfcube/release-manifest.edn` path.

For the complete PdfCarton-only flow, including the hidden API-key prompt and
authorized live push, run:

```sh
./scripts/publish-pdfcarton-alpha.sh
```

The script performs the three credential-free steps above before prompting. It
passes the key through the live publisher's environment and clears it on exit;
the publisher then invokes NuGet with the required API-key options and redacts
their values from diagnostics.

After a failed push, use the fast retry only when the checked remote state says
the exact version is still available and the proved manifest and artifacts are
unchanged:

```sh
./scripts/publish-pdfcarton-alpha.sh --retry
```

This skips clean generation, packing, package-consumer proofs, and the redundant
dry-run plan. It still runs the credential-free nuget.org availability check
before prompting. The live publisher then validates the manifest, artifact
digests, package metadata, and remote availability again before it reads the
key or pushes anything. If the existing manifest is absent or invalid, the
retry fails closed; run the full command without `--retry` to rebuild it.

## Inspect the bundle

List the deterministic artifact boundary:

```sh
find "$RELEASE_DIRECTORY" -maxdepth 1 -type f -exec basename {} \; | LC_ALL=C sort
```

Expected file output is exactly these 9 names. The local preflight below also
rejects any extra entry, including a directory or symbolic link:

```text
DripSharp.Brine.0.32.0-alpha.1.nupkg
DripSharp.Brine.0.32.0-alpha.1.snupkg
DripSharp.Brine.Parser.0.32.0-alpha.1.nupkg
DripSharp.Brine.Parser.0.32.0-alpha.1.snupkg
DripSharp.PdfCarton.3.0.8-alpha.2.nupkg
DripSharp.PdfCarton.3.0.8-alpha.2.snupkg
DripSharp.SqlTrellis.5.3.0-alpha.1.nupkg
DripSharp.SqlTrellis.5.3.0-alpha.1.snupkg
release-manifest.edn
```

Confirm the manifest digest independently:

```sh
shasum -a 256 "$RELEASE_MANIFEST"
```

Expected output begins with the exact 64-character digest printed by
preparation and ends with the manifest path. Bind the separate live approval to
that digest. Never edit, rename, replace, or add files under the artifact
directory after preparation; rerun preparation instead.

Run the credential-free local preflight:

```sh
clojure -M:run nuget-release-preflight "$RELEASE_MANIFEST"
```

The command must exit zero and print a final line beginning with:

```text
NuGet release preflight passed:
```

Its EDN report must contain `:kind :nuget-release-preflight`,
`:package-count 4`, `:duplicate-version-policy :fail-closed`, the exact
publish order above, `:size-limit-bytes 262144000`, and
`:remote-availability {:status :not-checked ...}`. The report contains a size
record for each `.nupkg` and `.snupkg`. Preflight also reopens every archive and
revalidates its ID, version, target framework, exact dependency edges, symbol
pair, portable PDB, file path, SHA-256, directory inventory, and target-owned
contract. Any discrepancy is a stop condition.

## Inspect the dry-run publication plan

The default publication command is a local dry-run:

```sh
clojure -M:run nuget-release-publish "$RELEASE_MANIFEST"
```

It must exit zero and print one line beginning with:

```text
NuGet publication dry-run plan:
```

The plan must report `:mode :dry-run`,
`:credential-channel "NUGET_API_KEY"`,
`:duplicate-version-policy :fail-closed`, the exact manifest digest and source,
and four ordered steps matching the table above. Every step must show
`:symbols {:status :paired ...}`. Its command vectors contain `dotnet nuget
push`, the exact `.nupkg` path, the exact source, a 300-second timeout, and
`--force-english-output`; they contain neither `--api-key` nor
`--skip-duplicate`. Dry-run does not read a credential and performs no network
request or push.

## Check nuget.org availability read-only

Run the bounded credential-free availability check close to the authorized
live operation:

```sh
clojure -M:run nuget-release-preflight "$RELEASE_MANIFEST" --check-nuget-org
```

It makes only HTTPS GET requests: one nuget.org service-index request and one
version-inventory request for each package. Expected success is exit zero and
a `NuGet release preflight passed:` report with
`:remote-availability {:status :checked, :package-count 4, ...}` and each exact
ID/version marked `:status :available`.

An existing exact ID/version produces exit 1 and:

```text
DripSharp command failed: NuGet release has an existing remote ID/version conflict
```

An HTTP error, timeout, or malformed response also exits 1 as indeterminate.
Neither condition may be converted to success. An availability result does not
reserve an ID; the live driver repeats the same complete remote check before it
reads the credential or starts the first push.

## Authorized live push

This is the only mutating step. Do not run it unless the exact release has the
separate human authorization described above.

Read a freshly approved organization-scoped key without terminal echo or shell
history. The following keeps the value in a shell variable rather than a
command argument or file:

```sh
printf 'NuGet API key: ' >&2
IFS= read -r -s NUGET_API_KEY
printf '\n' >&2
```

The only visible output is the `NuGet API key: ` prompt followed by a blank
line; the typed value must never appear.

Publish the exact proved manifest, capture the process status, and immediately
discard the shell variable:

```sh
NUGET_API_KEY="$NUGET_API_KEY" clojure -M:run nuget-release-publish "$RELEASE_MANIFEST" --live --authorize-publish --source https://api.nuget.org/v3/index.json
NUGET_PUBLISH_STATUS=$?
unset NUGET_API_KEY NUGET_SYMBOL_API_KEY
test "$NUGET_PUBLISH_STATUS" -eq 0
```

The driver validates the complete bundle again, repeats the read-only
availability check, and only then reads `NUGET_API_KEY`. For each dependency-
ordered `.nupkg` push it supplies the same key through NuGet's `--api-key` and
`--symbol-api-key` options. The executed process necessarily receives those
arguments, but the driver uses a redacted display command so plans, results,
and errors do not contain the value. Because the proved, paired `.snupkg` is in
the same directory and the plan does not use `--no-symbols`, NuGet publishes
the primary package first and then its symbol package. NuGet.org accepts
portable-PDB `.snupkg` files through the V3 source; see the [symbol-package
contract][nuget-symbols].

Success is exit zero and a final line beginning with:

```text
NuGet publication completed:
```

The result must have `:mode :live` and four `:completed` records in the exact
manifest order. A successful push response means the upload was accepted; it
does not mean gallery or symbol indexing is complete. Do not run a second push
to compensate for normal indexing delay.

## Validation, indexing, and remote restore

NuGet validates and indexes primary and symbol packages after upload. The
gallery reports packages under Unlisted Packages while processing. NuGet says
normal validation and indexing usually complete within 15 minutes; if a
package is still processing after an hour, check
[status.nuget.org](https://status.nuget.org/) and then use the package page's
authenticated Contact Support link. Symbol validation is separate, so confirm
all four `.snupkg` results and all four primary packages in the owner view. See
NuGet's [validation and indexing guidance][nuget-publish] and
[symbol indexing guidance][nuget-symbols].

After indexing, prove a clean remote dependency restore from nuget.org only.
Create an isolated disposable consumer:

```sh
NUGET_VERIFY_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/dripsharp-nuget-verify.XXXXXX")"
NUGET_VERIFY_PROJECT="$NUGET_VERIFY_ROOT/ReleaseConsumer/ReleaseConsumer.csproj"
export NUGET_VERIFY_ROOT NUGET_VERIFY_PROJECT
dotnet new classlib --name ReleaseConsumer --framework net10.0 --output "$NUGET_VERIFY_ROOT/ReleaseConsumer"
dotnet add "$NUGET_VERIFY_PROJECT" package DripSharp.Brine --version 0.32.0-alpha.1 --no-restore
dotnet add "$NUGET_VERIFY_PROJECT" package DripSharp.PdfCarton --version 3.0.8-alpha.2 --no-restore
dotnet add "$NUGET_VERIFY_PROJECT" package DripSharp.SqlTrellis --version 5.3.0-alpha.1 --no-restore
printf '%s\n' '<?xml version="1.0" encoding="utf-8"?>' '<configuration>' '  <packageSources>' '    <clear />' '    <add key="nuget.org" value="https://api.nuget.org/v3/index.json" protocolVersion="3" />' '  </packageSources>' '</configuration>' > "$NUGET_VERIFY_ROOT/NuGet.Config"
NUGET_PACKAGES="$NUGET_VERIFY_ROOT/packages" NUGET_HTTP_CACHE_PATH="$NUGET_VERIFY_ROOT/http-cache" NUGET_PLUGINS_CACHE_PATH="$NUGET_VERIFY_ROOT/plugins-cache" NUGET_SCRATCH="$NUGET_VERIFY_ROOT/scratch" dotnet restore "$NUGET_VERIFY_PROJECT" --configfile "$NUGET_VERIFY_ROOT/NuGet.Config" --packages "$NUGET_VERIFY_ROOT/packages" --no-http-cache --force --verbosity normal
dotnet build "$NUGET_VERIFY_PROJECT" --configuration Release --no-restore
dotnet list "$NUGET_VERIFY_PROJECT" package --include-transitive
```

The three `dotnet add` commands must report that exact-version references were
added. Restore must report `Restored ...ReleaseConsumer.csproj` and exit zero;
build must report `Build succeeded.` with zero errors. The final package list
must include these exact four internal ID/version pairs, either directly or
transitively:

```text
DripSharp.Brine.Parser 0.32.0-alpha.1
DripSharp.Brine 0.32.0-alpha.1
DripSharp.PdfCarton 3.0.8-alpha.2
DripSharp.SqlTrellis 5.3.0-alpha.1
```

External dependency rows may also appear. The isolated cache and source-only
NuGet.Config ensure this proof cannot succeed from the preparation feed or an
ambient package cache. If exact restore still returns not found while the
owner page says validation is in progress, wait for indexing and repeat the
same restore; do not republish.

Finally, inspect every package's nuget.org owner view and confirm that
`DripSharp` is an owner. Correct ownership is a gallery authorization property,
not something the `.nuspec` `Authors` field can prove.

## Partial failure and immutable-version recovery

nuget.org package ID/version pairs are immutable. A published version cannot be
overwritten with different bytes or repaired metadata, and the driver treats a
duplicate response as a hard conflict rather than using `--skip-duplicate`.
Source control rollback, deleting the local artifact directory, or rebuilding
the same version does not roll back a remote upload.

On a failed live command, stop immediately. The expected CLI error is:

```text
DripSharp command failed: NuGet publication stopped after a package push failure
```

The failed step's remote state is unknown: the server can accept a primary
package before a symbol upload or client response fails. Do not guess from the
local exit code and do not manually push the remaining files. Use the same
credential-free preflight boundary to print the complete remote state:

```sh
clojure -M -e '(require (quote [dripsharp.nuget-release-publisher :as publisher])) (try (publisher/preflight! {:manifest "target/nuget-release/all/release-manifest.edn" :check-nuget-org? true}) (catch clojure.lang.ExceptionInfo error (binding [*out* *err*] (prn (select-keys (ex-data error) [:reason :conflicts :indeterminate :remote-availability]))) (System/exit 1)))'
```

Expected recovery output is either a passing checked report with all four
versions `:available`, or a failing map with
`:reason :remote-version-conflict`, exact `:conflicts`, and a
`:remote-availability` record for all four packages. Indeterminate remote
state remains a stop condition.

Recover according to the observed state:

1. If all four exact versions remain available, nuget.org accepted none of
   them. Resolve the transient cause, obtain fresh authorization and a fresh
   credential, repeat the normal remote check, and rerun the same live driver.
2. If any exact version is a conflict, the release is irreversibly partial.
   Do not rerun the same manifest, use `--skip-duplicate`, push a missing symbol
   manually, or publish the remaining packages out of band. Record the accepted
   ID/version set, check each primary and symbol validation state, and obtain a
   new version decision. Increment the target-owned translator revision for
   every affected product family so all packages in that family move together,
   regenerate the complete four-package manifest, and restart this runbook.
   Exact dependency versions and the driver determine which new package bytes
   are valid; never patch an existing archive.

There is no automatic rollback command. nuget.org generally does not
permanently delete packages. A separately authorized owner may unlist a bad or
partial version in the nuget.org management UI and may also deprecate it with a
replacement recommendation. Unlisting only removes normal search visibility;
exact-version restore remains possible and the version remains unavailable for
reuse. Exceptional permanent removal requires NuGet support. See NuGet's
[deletion and unlisting policy][nuget-delete] and
[deprecation guidance][nuget-deprecate]. Ownership transfer or owner removal is
also a separate external mutation and is never part of release rollback.

## GitHub Actions trusted-publishing handoff

Each generated product repository supplies a manually dispatched
`.github/workflows/nuget-release.yml` workflow. The workflow checks out the
authoritative `dripsharp/dripsharp` repository, requires its product gitlink to
equal the product workflow commit, and runs the target-specific release driver.

The PdfCarton workflow sets exactly
`PDFCARTON_RELEASE_REDUCED_TESTS=1` to fit GitHub's free four-core public
runner. The release driver accepts that product-owned value only when PdfCarton
is the sole selected target and invokes `products/pdfcarton/eng/verify-release.sh`.
That verifier always restores and compiles all five published projects, runs
the mandatory release smoke suite and the five focused consumer classes, and
omits only its documented exhaustive adapted-upstream, fixture-integrity,
differential, corpus, and high-memory proof work. The manifest records
`:test-verification :reduced-pdfcarton-release`. The former
`DRIPSHARP_NUGET_RELEASE_SKIP_TESTS=1` whole-ladder path is rejected for
PdfCarton (as it is for Brine); SqlTrellis is the only product workflow that
still uses that legacy GitHub-only mode.

Bounded release verification does not count as a complete target proof and
does not change any product goal, exclusion, or completion criterion. The
release flow retains clean Release compilation, two-pack reproducibility,
package and symbol inspection, fresh-feed consumer validation, repository
synchronization, remote version availability checks, and fail-closed
publication.

The workflows remain thin orchestration layers over the tested Clojure
boundaries:

1. Check out `dripsharp/dripsharp`, the selected generated-product submodule,
   and its pinned upstream source; install the approved Java, Clojure, and .NET
   toolchains; allocate a 10 GiB heap and four workers; and invoke the
   target-specific release preparation with its explicit reduced-test flag.
2. Retain `target/nuget-release/<target>` as one hash-bound artifact and invoke
   the same offline `nuget-release-preflight` and default
   `nuget-release-publish` dry-run. YAML must not discover packages, rebuild the
   dependency graph, enumerate four pushes, inspect archives, or implement
   retry/skip logic.
3. Put the live job behind a protected release environment and explicit human
   approval. Grant only `contents: read` and `id-token: write` as needed for
   that job. Configure a nuget.org trusted-publishing policy owned by the
   `DripSharp` organization and bound to the exact `dripsharp/dripsharp`
   repository, workflow filename, and release environment.
4. Immediately before publication, use the official `NuGet/login` action to
   exchange GitHub's OIDC token for a short-lived NuGet API key. Bind the policy
   to the non-secret `isaksky` NuGet profile name. NuGet documents a one-hour
   temporary-key lifetime, so do not obtain it during the long preparation
   job. Pin and review the action version under the repository's normal supply-
   chain policy.
5. Expose the action output as `NUGET_API_KEY` only in the environment of one
   invocation of the existing live driver. Do not interpolate it into the
   driver command line, echo it, persist it, upload it, or duplicate the driver's
   symbol, ordering, availability, authorization, or failure logic in YAML.
   The command remains:

   ```sh
   clojure -M:run nuget-release-publish target/nuget-release/<target>/release-manifest.edn --live --authorize-publish --source https://api.nuget.org/v3/index.json
   ```

6. Run the same isolated nuget.org-only restore verification after indexing.
   Workflow retries must follow the immutable-version and partial-failure
   procedure above; the workflow must never turn a collision into success.

Before the first run in each product repository, create a protected GitHub
environment named `release` with required reviewers. Create a corresponding
nuget.org trusted-publishing policy for profile `isaksky`, repository owner
`dripsharp`, workflow file `nuget-release.yml`, environment `release`, and the
repository name `brine`, `pdfcarton`, or `sqltrellis`.

NuGet's [trusted-publishing contract][nuget-trusted-publishing] explains the
OIDC policy and short-lived key exchange. GitHub's
[OIDC permission contract][github-oidc] explains why `id-token: write` is
required. If trusted publishing is unavailable, automation must stop unless a
different short-lived credential mechanism is explicitly approved. A
long-lived repository secret is not the default migration contract.

[github-oidc]: https://docs.github.com/en/actions/reference/security/oidc
[nuget-delete]: https://learn.microsoft.com/en-us/nuget/nuget-org/policies/deleting-packages
[nuget-deprecate]: https://learn.microsoft.com/en-us/nuget/nuget-org/deprecate-packages
[nuget-organizations]: https://learn.microsoft.com/en-us/nuget/nuget-org/organizations-on-nuget-org
[nuget-publish]: https://learn.microsoft.com/en-us/nuget/nuget-org/publish-a-package
[nuget-symbols]: https://learn.microsoft.com/en-us/nuget/create-packages/symbol-packages-snupkg
[nuget-trusted-publishing]: https://learn.microsoft.com/en-us/nuget/nuget-org/trusted-publishing
