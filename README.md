# DripSharp

DripSharp is a Clojure-based Java-to-C# source translator. Its first
product use is producing a complete, independently usable .NET implementation
of the in-scope Pkl library behavior, but the translator architecture is meant
to remain useful for other Java projects.

The central Java path is deliberately direct:

```text
resolved project and classpath
  -> Spoon typed semantic AST
  -> recursive translation using resolved symbols
  -> ordinary, disposable C# source
  -> compile
  -> independent behavior comparison
```

Translation rules map ordinary JVM library types and methods to their .NET
equivalents. Compatibility helpers belong in the translator only when .NET
lacks the required JVM facility. Pkl evaluation or language behavior is product
code, not a generic translator runtime.

Kotlin-to-C# translation is out of scope. Kotlin source and tests may provide
behavior evidence for an in-scope Pkl capability, but that behavior is
implemented through translated Java or directly through an idiomatic .NET
implementation. This does not make DripSharp Pkl-specific: its Java frontend,
recursive translator, mappings, and general compatibility capabilities remain
reusable for future Java projects.

Generated C# is disposable. Compilation or behavior failures must be fixed in
the frontend configuration, recursive translation, symbol mappings, or a
clearly owned compatibility/product implementation, then regenerated without
manual patches.

## Documentation

The documentation is limited to durable product and architecture decisions:

* [Documentation Index](doc/README.md) separates reusable translator contracts
  from product-target contracts.
* [Product Targets](doc/targets/) defines the structure used for any number of
  independently scoped ports.
* [Pkl Product Goal](doc/targets/pkl/product-goal.md) defines completion and
  protects the fixed Pkl product scope.
* [Pkl Port Scope](doc/targets/pkl/port-scope.md) records the user-approved .NET
  product surface and exclusions for Pkl.
* [PdfCarton Product Goal](doc/targets/pdfbox/product-goal.md) and
  [Port Scope](doc/targets/pdfbox/port-scope.md) define the synchronized,
  mechanically translated Apache PDFBox library family.
* [Architecture](doc/architecture.md) defines the end-to-end system and
  ownership boundaries.
* [Technology](doc/technology.md) records the frontend and validation choices.
* [Transform Pipeline](doc/transform-pipeline.md) defines recursive translation
  and mapping contracts.
* [Conversion Concerns](doc/conversion-concerns.md) records semantic mismatches
  that require explicit handling.

## Development Rule

Use complete projects with their real generated sources and classpaths. A
passing hand-selected source slice or sample fixture is useful evidence for a
local rule but is not evidence that a module or the product is complete.

## Development Commands

Run these commands from the repository root:

```text
clojure -M:run generate pkl pkl-parser
clojure -M:run verify pkl pkl-parser
clojure -J-Xmx8g -M:run pack pkl pkl-core-value-model
clojure -J-Xmx8g -M:run package pkl pkl-core-value-model
clojure -J-Xmx28g -M:run differential pkl
clojure -J-Xmx28g -M:run differential pdfcube pdfcube-io
clojure -J-Xmx28g -M:run differential pdfcube pdfcube-fontbox
clojure -J-Xmx28g -M:run differential pdfcube pdfcube-xmpbox-metadata
clojure -J-Xmx8g -M:run package rawhttp rawhttp-core
clojure -J-Xmx8g -M:run differential rawhttp
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run proof rawhttp
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run proof pkl
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run proof pdfcube
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run authorship-report all
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run rebaseline pkl
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run rebaseline pdfcube
DRIPSHARP_WORKERS=22 clojure -J-Xmx28g -M:run rebaseline rawhttp
clojure -M:unit-test
clojure -M:test
clojure -M:test --namespace dripsharp.harness-test
```

`clojure -M:unit-test` is the process-free, sub-second feedback tier for pure
translation planning, mapping registries, configuration diagnostics, C# and
project rendering, source accountability, and destination bundle contracts.
It uses a bounded 512 MiB heap and does not launch Spoon models, Gradle, Maven,
dotnet, package consumers, or differential oracles. It complements the
default full test suite and the target proof ladders; it does not replace
either.

Generation and differential validation use one bounded executor. The default
worker count is the JVM's available-processor count. Set `DRIPSHARP_WORKERS=1`
for sequential debugging or performance comparison; the equivalent JVM
property is `-Ddripsharp.workers=1`. Output remains deterministic at every
worker count. Semantic-reference resolution is serialized within each live
Spoon model because its lazy operations mutate shared frontend state. Positive
values greater than one still enable multicore declaration translation,
independent dependency profiles, and independent differential probes without
nested pools. When one declaration root dominates generation, its members share
the same bounded work queue as ordinary roots and are reassembled in canonical
source order, eliminating the single-worker tail without changing generated
bytes.

`generate <target> <profile>` preflights the selected target directory,
including its baseline, profile, destination, legal policy, mapping overlays,
runtime assets, and validation contracts, before it removes and recreates
`target` or starts project discovery. There is no implicit target or profile.
The selected profile obtains production sources, resources, compile classpath,
and Java toolchain from its configured build. Generated files under `target`
are disposable.

Low-level harness calls also require an explicit profile selection and may use
a workspace-relative or absolute profile EDN file. A profile uses this durable
ingestion contract:

```clojure
{:schema-version 1
 :profile "example-library"
 :product-family :java-library
 :project-root "../example-java"
 :revision "0123456789abcdef0123456789abcdef01234567"
 :gradle-wrapper "gradlew"
 :gradle-project ":library"
 :destination-bundle dripsharp.java-library/rule-bundle
 :destination-config "config/example-library.edn"
 :identity-guard {:forbidden-fragments ["reserved-product-name"]}
 :dependency-profiles []}
```

`project-root` may be workspace-relative or absolute, `gradle-wrapper` may be
project-relative or absolute, and `gradle-project` may be `:` for the build's
root project. Older wrappers can select a compatible installed runtime with
`:gradle-java-major`. The profile and destination must name the same qualified
bundle selector and product family; the selected bundle, public-surface
strategy, optional runtime assets, and identity guard are validated before
`target` is cleaned or Gradle is run. The destination also declares nullable
and warnings-as-errors policy, deterministic package metadata, exact source
project/external dependencies, resources, an explicit public-surface strategy,
and either a source-backed or compile-only isolated package consumer.

The wrapper runs the selected production compilation and resource tasks, so
ingestion sees configured and plugin-generated Java sources, processed
resources, the fully resolved compile classpath, source project dependencies,
external coordinates and artifact hashes, and compiled outputs from Gradle
project dependencies. Projects without compile dependencies have an empty
classpath. `dependency-profiles` independently generates translated .NET
project/package dependencies; each referenced profile can select its own Java
build root, Gradle project, destination bundle, and public contract.

Each `targets/<target>/baseline.edn` is the single reviewed baseline record for
that target. Profiles, destination bundles, packaging checks, and differentials
resolve upstream identity, Java language level, source and public-contract
counts, package versions, artifact hashes, and legal-file contracts from that
record. `rebaseline <target>` observes the clean upstream checkout and prints
the complete current and candidate records, their field-level delta, and an
approval token. Its `:legal-review` section separately calls out any observed
upstream-license, pinned legal-file hash, or NOTICE-appendix changes that need
explicit review; those changes remain covered by the same exact approval
token. The preview does not write the record. Re-run the exact command printed
in the preview with `--approve <token>` to apply that exact recomputed delta.
The approval path can replace only the selected baseline record; product goals,
port scopes, dependency contracts, exclusions, and completion rules are
protected and remain unchanged.

`verify` performs that same clean generation and immediately builds the fresh
project with warnings as errors. Compiler diagnostics are parsed and correlated
through `source-map.edn` to the originating Spoon element and translation rule.

`pack <target> <profile>` performs clean Release generation and verification, packs the
selected profile and all declared dependency profiles twice, proves byte-for-byte
package determinism, and writes the inspected packages to a fresh local feed.
The pkl-core profile currently requires an explicit larger JVM heap, such as
`-J-Xmx8g`, in this environment.

`package <target> <profile>` first performs the clean `verify` gate, packs that exact generated
build into a fresh local feed, and restores, builds, and runs a newly created
consumer with an isolated NuGet package cache. The consumer has no project
reference or access to generated source. Consumer selection belongs to the
destination configuration rather than a profile-name switch.
`pkl-parser`
exercises the public parser package; `pkl-core-value-model`
exercises the packed evaluator and value-model surface plus its exact package
dependency on `DripSharp.Brine.Parser`.

`nuget-release-prepare <pkl|pdfcube|sqltrellis|all>` is the credential-free
local NuGet release entry point. It discovers only target-owned production
package inventories, runs the selected targets' complete proof ladders, and
passes every package closure through the clean twice-pack, exact inspection,
fresh-feed, and isolated-consumer gates. It writes byte-stable `.nupkg` and
`.snupkg` files plus `release-manifest.edn` under
`target/nuget-release/<selection>`. The command accepts no publication
credential and performs no tag, release, upload, ownership, or network
mutation. Its deterministic manifest records remote availability as
`:not-checked`. The same run writes `product-authorship-report.edn` and
`product-authorship-report.md` under `target/authorship-report/<selection>`.
Those reports aggregate the package-inspected mechanical, shared authored,
product-authored, and vendored source classes, deduplicate shared authored
inputs, list their durable provenance and linked proofs, and report generated
test-suite provenance separately from production percentages.

`authorship-report <pkl|pdfcube|sqltrellis|all>` is the explicit reporting
entry point. Because an exact report requires reconciled package inputs, it
runs the same credential-free proof and package preparation as
`nuget-release-prepare`; it is not a shortcut over stale generated files.
"Authored" in the report means reviewed non-mechanical DripSharp source. It
does not attempt to infer whether an LLM or a human typed an individual line,
and deterministic source-mapped adaptations performed by the translator remain
classified as mechanical.

`nuget-release-preflight <manifest> [--check-nuget-org]` requires the complete
eight-package `all` release set and revalidates exact target-owned identities,
versions, dependency closure and order, hashes, symbol pairing, and the
configured 250 MiB nuget.org artifact limit. Offline mode performs no network
request and reports every ID/version as not checked. The optional nuget.org
check uses bounded credential-free GET requests and fails the release on an
existing exact ID/version or an indeterminate response.

`nuget-release-publish <manifest>` revalidates that proved manifest, its exact
eight-package target-owned package graph, every package and symbol digest, and
the package metadata before printing the ordered push plan. Dry-run is the
default and performs no network operation. Live publication first requires a
successful remote availability preflight and additionally requires
`--live --authorize-publish --source <https-source>`; the source must equal the
target-owned HTTPS source and the key must be injected through `NUGET_API_KEY`
(and is forwarded to the symbol push as `NUGET_SYMBOL_API_KEY`). The key is
never accepted as an argument, printed, or written to release evidence. Remote
collisions and push-time conflicts are hard failures; the command never uses
skip-duplicate behavior.

The [local NuGet release runbook](doc/nuget-release-runbook.md) gives the exact
operator commands, expected evidence, nuget.org ownership boundary,
immutable-version recovery rules, isolated post-publish restore, and the
contract for a later GitHub Actions trusted-publishing handoff.

`differential <target> [validation-id]` dispatches the target manifest's
validation contracts. The Pkl validation performs both complete package gates.
`proof <target>` runs every required ladder in the target's schema-version 3
proof contract. That contract exhaustively covers the target's profiles and
validations and assigns CI resources without making any proof optional.
It separately builds and
runs the pinned upstream JVM parser as an oracle, then runs a package-only .NET
probe over the baseline-recorded LanguageSnippetTests inputs and the upstream lexer/span edge
cases (956 cases and 2,871 normalized observations in total). It retains that
complete parser proof, then independently compares the
packaged DripSharp.Brine evaluator and value model with a separate JVM oracle across
107 normalized observations: module and nested-object export; object, path, and
string expression evaluation; text, JSON, bytes, and multi-file output;
untyped and typed output-value export; local and standard-library imports;
local text/byte resources; module security denial; collection, bytes, and regex
runtime values; four normalized error classes; and `Duration`, `DataSize`,
`Pair`, `PNull`, `PClassInfo`, and `ModuleSource` behavior. The value-model
observations separately exercise every visitor and converter dispatch family,
equality/hash/order and schema identity, formatter edge cases, JSON/PCF/PList/
properties rendering and invalid values, and idiomatic .NET byte, nullable, and
read-only collection facades. The 107 observations include 68 independently
checked `toFixed` cases. Each core case gets a
fresh evaluator, and neither package probe loads generated sources or shares
runtime state with its oracle. The core gate also executes a source-backed
loading, security-policy, and evaluator-configuration contract against the
upstream JVM. Its 73 behavior families distinguish 64 directly comparable JVM
families from nine .NET-specific assembly, embedded-resource, path, disposal,
idiomatic loading-surface, configured-external-process/failure-lifecycle, and
timeout-cancellation/cleanup adaptations; every family in this contract has
implementation evidence without redefining broader product completion. A
package-reference-only .NET consumer independently exercises 38 normalized
observations, including HTTP/TLS and proxy behavior, package and projectpackage
resolution, verified cache/offline behavior, assembly-relative loading, embedded
resources, configured external readers, canonical policy checks, errors,
ownership boundaries, and evaluator timeout deadlines across CPU, reader,
network, package, project/settings, and subprocess paths. It rejects project/source leakage, verifies the runtime
locations and hashes of the exact packed assemblies, and audits the selected
loading, evaluator, analyzer, logging, diagnostic, value-model, schema,
formatter, renderer, and output public surface for
implementation stubs. The 25
normalized upstream observations cover local, directory, archive, custom,
HTTP/TLS, package, and projectpackage loading; cache and integrity behavior;
settings, evaluator/analyzer APIs, logging, diagnostics, platform/release metadata,
policy, errors, and lifecycle, with deliberate mismatch detection. The
core gate also compiles and runs separate
package-reference-only generator and generated-consumer projects. A JVM oracle
independently compares 40 observations across nine normalized schemas,
including method and standard-library generic ownership, amended-module
relationships, recursive alias identity and termination, and the upstream
rejection of user-defined type parameters. It also compares six
code-generation failures, the reflected contract of seven compiled generated
types, representative bound values, and 14 independent binding failures across
21 focused cases. The generated consumer uses an isolated
package cache, nullable analysis, and warnings as errors; it evaluates through
the emitted loaders without project references or manual source edits. All
comparisons deliberately perturb an oracle result to prove mismatches are
detected. Proof outputs are retained under
`validation-output/differential-proof` even while later generation cleans the
disposable `target` tree.

`pdfcube-io-differential` performs two clean DripSharp.PdfCarton.IO builds, proves
byte-identical packing, mirrors the complete external dependency closure into a
local-only feed, and runs a package-reference-only consumer. It then compares
25 normalized buffer, positioning, view, EOF, file, memory-map, scratch,
memory-limit, lifecycle, and failure observations with a live oracle compiled
from the reviewed PDFBox baseline sources. The supported-host workflow applies that
canonical CPU trace on Windows, Linux, and macOS on x64 and ARM64.

`pdfcube-pdfbox-differential` performs one dependency-closed, twice-clean
deterministic pack of `DripSharp.PdfCarton`, `DripSharp.PdfCarton.Fonts`, and `DripSharp.PdfCarton.IO`,
then reuses the exact fresh package-reference-only consumer across
representative create, load, parse, save, incremental-update, manipulation,
extraction, rendering, form, security, signing, and print-layout workflows.
It requires the complete compiled public contracts, zero generated public
stubs, exact resources and legal payloads, exact runtime dependency closure,
normalized agreement with the reviewed PDFBox baseline Java oracles, and a
deliberately detected mismatch. Its supported-host workflow restores, builds,
and smokes the retained package feed on Windows, Linux, and macOS on x64 and
ARM64.

`pdfcube-preflight-differential` performs one dependency-closed, twice-clean
deterministic pack of `DripSharp.PdfCarton.Preflight`, `DripSharp.PdfCarton`,
`DripSharp.PdfCarton.Xmp`, `DripSharp.PdfCarton.Fonts`, and `DripSharp.PdfCarton.IO`. A separate
package-reference-only consumer exercises parser, context, configuration,
validation, result, error, and lifecycle APIs from a fresh local feed and
isolated cache. The gate requires exact package identity, dependencies,
resources, legal payload, complete compiled public surface, and zero public
stubs; compares normalized execution and representative PDF/A corpus
observations with the reviewed PDFBox baseline Java implementation; and proves
deliberate mismatch, timeout, crash, leak, missing-row, and nondeterminism
detection. Its supported-host workflow restores, builds, and smokes the same
package feed on Windows, Linux, and macOS on x64 and ARM64.

`language-snippet-contract` validates the pinned, source-controlled manifest
for all 940 upstream `LanguageSnippetTests` cases, then executes the upstream
JVM engine twice on its supported Java 21 toolchain. It compares the engine's
normalized success, output, logger, and error bytes with the source-controlled
expectations, rejects missing or duplicate observations, proves deterministic
repetition, and deliberately perturbs the oracle to prove mismatch detection.
This is evaluator behavior evidence; it does not change product scope or make
the excluded YAML or Pkl-binary product surfaces part of the .NET package.

`language-snippet-package` performs a fresh deterministic `DripSharp.Brine`/`DripSharp.Brine.Parser`
pack and restores a package-reference-only runner into an isolated NuGet cache.
The runner verifies the exact loaded assembly hashes, reproduces the upstream
environment, properties, logger, project, power-assertion, no-cache, TLS, and
test-package-service configuration, and evaluates each of the 909 in-scope
manifest rows in a bounded child process. Crashes and timeouts are recorded and
cannot abort later rows; the 31 approved YAML-only exclusions remain explicit.
Two complete runs must be byte-for-byte deterministic. Rich case results, a
family baseline, and every oracle mismatch are retained under
`validation-output/language-snippet-package`; mismatches remain pending product
implementation rather than accepted results or new exclusions.

`pkl-core-corpus` executes every row of the pinned non-language Pkl Core test
contract twice through independent bounded JVM children, then performs a fresh
deterministic package gate and executes the same ordered rows twice from a
package-reference-only .NET consumer. The consumer recreates temporary-file,
archive, module-path, local HTTP/TLS/proxy/package, environment/property,
service, subprocess, and platform-path fixtures without uncontrolled network
access. It verifies the exact loaded package assembly paths and hashes. Missing,
duplicate, stale, skipped, unowned, crashed, or timed-out rows remain explicit
and fail closed; byte-for-byte repetition and deliberate JVM/package,
coverage, classification, provenance, crash, and timeout controls are retained under
`validation-output/pkl-core-corpus`. The aggregate command re-discovers the
live upstream case set, requires all three implementation partitions to form
one non-overlapping 523-row product matrix, and fails unless every in-scope row
matches the independent package result. Product mismatches cannot become
exclusions.
