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
* [PdfCube Product Goal](doc/targets/pdfbox/product-goal.md) and
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
clojure -M:run generate
clojure -M:run generate path/to/java-project-profile.edn
clojure -M:run verify
clojure -J-Xmx8g -M:run pack pkl-core-value-model
clojure -M:run package
clojure -J-Xmx8g -M:run package pkl-core-value-model
clojure -M:run differential
clojure -J-Xmx28g -M:run pdfcube-io-differential
clojure -J-Xmx28g -M:run pdfcube-fontbox-differential
clojure -J-Xmx28g -M:run pdfcube-pdfbox-differential
clojure -J-Xmx28g -M:run pdfcube-pdfbox-low-level-differential
clojure -J-Xmx28g -M:run pdfcube-pdfbox-document-lifecycle-differential
clojure -J-Xmx28g -M:run pdfcube-pdfbox-font-text-differential
clojure -J-Xmx28g -M:run pdfcube-pdfbox-graphics-differential
clojure -J-Xmx28g -M:run pdfcube-pdfbox-rendering-differential
clojure -J-Xmx28g -M:run pdfcube-pdfbox-image-differential
clojure -J-Xmx28g -M:run pdfcube-pdfbox-interchange-differential
clojure -J-Xmx28g -M:run pdfcube-pdfbox-interaction-differential
clojure -J-Xmx28g -M:run pdfcube-pdfbox-manipulation-differential
clojure -J-Xmx28g -M:run pdfcube-pdfbox-security-differential
clojure -J-Xmx28g -M:run pdfcube-xmpbox-metadata-differential
clojure -M:run language-snippet-contract
clojure -J-Xmx8g -M:run language-snippet-package
clojure -J-Xmx8g -M:run pkl-core-corpus
clojure -M:test
clojure -M:test --namespace dripsharp.harness-test
```

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

`generate` verifies every configured source checkout, including its exact
revision when pinned, before it removes and recreates `target` or starts project
discovery. With the default profile it verifies `research/pkl` at its configured
revision and obtains the pkl-parser production sources, resources, compile
classpath, and Java toolchain from its Gradle project. Generated files under
`target` are disposable.

The optional profile argument may also be a workspace-relative or absolute EDN
file for another Gradle Java build. A profile uses this durable ingestion
contract:

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

`verify` performs that same clean generation and immediately builds the fresh
project with warnings as errors. Compiler diagnostics are parsed and correlated
through `source-map.edn` to the originating Spoon element and translation rule.

`pack [profile]` performs clean Release generation and verification, packs the
selected profile and all declared dependency profiles twice, proves byte-for-byte
package determinism, and writes the inspected packages to a fresh local feed.
The pkl-core profile currently requires an explicit larger JVM heap, such as
`-J-Xmx8g`, in this environment.

`package [profile]` first performs the clean `verify` gate, packs that exact generated
build into a fresh local feed, and restores, builds, and runs a newly created
consumer with an isolated NuGet package cache. The consumer has no project
reference or access to generated source. Consumer selection belongs to the
destination configuration rather than a profile-name switch. The default Pkl
profile still exercises the public parser package; `pkl-core-value-model`
exercises the packed evaluator and value-model surface plus its exact package
dependency on `Pkl.Parser`.

`differential` performs both complete package gates. It separately builds and
runs the pinned upstream JVM parser as an oracle, then runs a package-only .NET
probe over all 940 LanguageSnippetTests inputs and the upstream lexer/span edge
cases (956 cases and 2,871 normalized observations in total). It retains that
complete parser proof, then independently compares the
packaged Pkl.Core evaluator and value model with a separate JVM oracle across
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

`pdfcube-io-differential` performs two clean PdfCube.IO builds, proves
byte-identical packing, mirrors the complete external dependency closure into a
local-only feed, and runs a package-reference-only consumer. It then compares
25 normalized buffer, positioning, view, EOF, file, memory-map, scratch,
memory-limit, lifecycle, and failure observations with a live oracle compiled
from the pinned PDFBox 3.0.8 sources. The supported-host workflow applies that
canonical CPU trace on Windows, Linux, and macOS on x64 and ARM64.

`pdfcube-pdfbox-differential` performs one dependency-closed, twice-clean
deterministic pack of `PdfCube.PdfBox`, `PdfCube.FontBox`, and `PdfCube.IO`,
then reuses the exact fresh package-reference-only consumer across
representative create, load, parse, save, incremental-update, manipulation,
extraction, rendering, form, security, signing, and print-layout workflows.
It requires the complete compiled public contracts, zero generated public
stubs, exact resources and legal payloads, exact runtime dependency closure,
normalized agreement with the pinned PDFBox 3.0.8 Java oracles, and a
deliberately detected mismatch. Its supported-host workflow restores, builds,
and smokes the retained package feed on Windows, Linux, and macOS on x64 and
ARM64.

`language-snippet-contract` validates the pinned, source-controlled manifest
for all 940 upstream `LanguageSnippetTests` cases, then executes the upstream
JVM engine twice on its supported Java 21 toolchain. It compares the engine's
normalized success, output, logger, and error bytes with the source-controlled
expectations, rejects missing or duplicate observations, proves deterministic
repetition, and deliberately perturbs the oracle to prove mismatch detection.
This is evaluator behavior evidence; it does not change product scope or make
the excluded YAML or Pkl-binary product surfaces part of the .NET package.

`language-snippet-package` performs a fresh deterministic `Pkl.Core`/`Pkl.Parser`
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

`pkl-core-corpus` executes every row of the pinned non-language Pkl.Core test
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
