# Target Directory Contract

## Purpose and authority

An operational target is a directory at `targets/<target-id>`. Its manifest
selects all target-owned translation inputs without registering the target in a
generic Clojure dispatch table. The target directory references the durable
product contracts under `doc/targets/`; it does not restate, narrow, exclude,
or replace them.

Target ids are stable lowercase names. Adding a conforming directory may add
target-owned rule bundles or validation runners, but does not require a change
to the product-neutral directory loader.

## Manifest

`targets/<target-id>/target.edn` uses schema version 8 for generated repository
publication and has exactly these keys. Schema version 7 remains readable for
the existing conformance-only target; it cannot declare a generated product
publication.

```clojure
{:schema-version 8
 :target :example
 :product-family :example

 :contracts
 {:product-goal "doc/targets/example/product-goal.md"
  :port-scope "doc/targets/example/port-scope.md"
  :dependencies []}

 :baseline "baseline.edn"
 :legal-policy "legal/policy.edn"

 :java
 {:source-language-version 17
  :runtime-major 21
  :preview-features? false}

 :capabilities
 #{:java-compat :example/mappings :example/runtime}

 :authorship
 {:compatibility "config/authored-compat.edn"
  :destination "authorship.edn"
  :third-party "third-party.edn"}

 :profiles
 [{:id "example-core"
   :path "profiles/core.edn"
   :destination :core
   :mapping-overlays [:example/core]
   :runtime-assets [:example/runtime]
   :validation-contracts [:example-core]
   :authorship
   {:sources [:example/runtime]
    :evidence [:example-complete-proof]
    :review "reviewed-change-id"
    :budget {:authored-lines 120 :total-lines 1200}}
   :required-capabilities
   #{:java-compat :example/mappings :example/runtime}}]

 :destinations
 [{:id :core
   :path "destinations/core.edn"}]

 :mapping-overlays
 [{:id :example/core
   :path "mappings/core.edn"}]

 :runtime-assets
 [{:id :example/runtime
   :path "runtime/Example.Core.Runtime.cs"
   :capabilities #{:example/runtime}}]

 :validation-contracts
 [{:id :example-core
   :kind :differential
   :path "validation/core.edn"}]

 :publication
 {:kind :generated-repository
  :repository-slug "dripsharp/example"
  :repository-url "https://github.com/dripsharp/example.git"
  :default-branch "master"
  :submodule-path "products/example"
  :staging-path "target/generated/example"
  :profile-projects {"example-core" "src/Example.Core"}
  :managed-paths ["src" "tests" "LICENSE" "NOTICE" "README.md"]
  :test-suites "test-suites.edn"
  :nuget
  {:decision "the approved publication-metadata decision"
   :authors "Approved Publisher"
   :owner-organization "DripSharp"
   :publishing-account "approved-account"
   :source "https://api.nuget.org/v3/index.json"
   :project-url "https://github.com/dripsharp/example"
   :repository-url "https://github.com/dripsharp/example.git"
   :repository-type "git"
   :repository-commit-policy :exact-clean-generated-product-commit
   :version-policy {:kind :upstream-alpha-revision
                    :translator-revision 1}
   :readme "README.md"
   :icon {:status :deferred
          :reason "An icon is deferred for a documented value-neutral reason."}
   :packages {"Example.Core" {:version "1.2.3-alpha.1"}}}
  :publication-mode :pull-request}

 :proof
 {:role :product
  :ladders
  [{:id :example-complete-proof
    :kind :target-validations
    :profiles ["example-core"]
    :validation-contracts [:example-core]
    :resource-class :high-memory}]}}
```

Unknown or missing keys are errors. Descriptor ids and paths are unique.
Operational paths are normalized portable relative paths and must remain in
their declared target-owned areas. Product-goal, port-scope, and dependency
documents remain workspace-relative paths under `doc/targets/`.

Every declared destination, overlay, runtime asset, and validation contract
must be selected by at least one profile. Every selected id must exist.
Declared capabilities must exactly equal the union provided by destinations,
mapping overlays, and runtime assets; a profile's required capabilities must
be available from its selected inputs.

## Publication contract

Every target declares exactly one publication variant. A generated product
family uses `:generated-repository` with the exact keys shown above. Its
repository slug and canonical Git URL are `dripsharp/<product-family>` and
`https://github.com/dripsharp/<product-family>.git`; its default branch is
`master`; its durable checkout and disposable staging roots are
`products/<product-family>` and `target/generated/<product-family>`; and its
publication mode is `:pull-request`.

`:profile-projects` maps every target profile exactly once to a distinct
portable project path below a declared managed path. Each mapped path must
agree with the profile destination's generated project directory after the
`target/` staging prefix is removed. `:managed-paths` is a nonempty,
non-overlapping vector of top-level repository paths. This keeps generated
and proved output outside the product checkout and gives synchronization an
exact copy boundary. Generated product publications must manage `tests/` and
reference the canonical target-owned `test-suites.edn` contract.

Schema version 8 also requires one exact `:nuget` contract for every generated
product repository. Its package ids equal the production profile destinations
and baseline package inventory. `:upstream-alpha-revision` normalizes a numeric
upstream version to three components and derives `<upstream>-alpha.<revision>`;
the derived values must equal both the NuGet contract and baseline. Publisher,
project URL, repository URL and type, README, and commit policy must agree with
every destination configuration. The package repository is the generated
DripSharp product repository, while upstream repository and revision evidence
remain in the baseline, legal, and provenance contracts.

A multi-project target may add `:bundle` to its NuGet contract:

```clojure
{:package-id "Example.Core"
 :profile "example-complete"
 :component-package-ids ["Example.Support" "Example.Core"]}
```

The component IDs must be the exact production package catalog and the selected
profile's dependency closure. Component packages remain deterministic internal
proof artifacts; release preparation emits only `:package-id`, containing every
component assembly and portable PDB, and proves an isolated consumer with that
single package reference.

`:exact-clean-generated-product-commit` is resolved only during packaging. The
packager proves that the product checkout is clean, its origin is the declared
repository, its parent gitlink is exact, and its managed inventory is identical
to the proved staging inventory. It injects that product HEAD as
`RepositoryCommit` and exact nupkg inspection rejects any mismatch. A static
upstream source revision in destination package metadata is forbidden.

Every production package packs the generated repository's root `README.md`.
The README lists the family projects, exact prerelease versions, installation
commands, build and test entry points, upstream source identity, independent
translation status, and legal files. Until an icon is approved, the icon entry
must explicitly record `:deferred` and a non-promotional, value-neutral reason.

The reusable test-suite contract has this shape (vectors may contain multiple
exact project identities and multiple strategies may contribute to one
project):

Generated-product target manifests use schema version 9 and declare the
framework split once:

```clojure
:frameworks
{:production "netstandard2.0"
 :execution "net10.0"
 :net48-compatibility :inferred-from-netstandard2.0
 :net48-runtime-tested? false}
```

Every destination project's `:target-framework` must equal `:production`.
Every generated test-suite project's `:target-framework` must equal
`:execution`. Package asset paths use the lowercase production TFM, while
nuspec dependency groups use its exact NuGet canonical identifier (for example,
`netstandard2.0` and `.NETStandard2.0`, respectively). All executable validation
and isolated consumers use the execution value. The net48 fields record that
compatibility is inferred rather than runtime-tested.

```clojure
{:schema-version 2
 :projects
 [{:id "DripSharp.Example.Tests"
   :directory "tests/DripSharp.Example.Tests"
   :assembly-name "DripSharp.Example.Tests"
   :target-framework "<the target's execution framework>"
   :profile-references ["example-core"]
   :project-references []
   :packages
   [{:id "Microsoft.NET.Test.Sdk" :version "17.14.1"}
    {:id "xunit" :version "2.9.3"}
    {:id "xunit.runner.visualstudio" :version "3.1.4"}
    {:id "Castle.Core" :version "5.1.1"}]}]
 :strategies
 [{:id :focused-consumer
   :kind :focused-consumer
   :policy :shipped
   :project "DripSharp.Example.Tests"
   :handler dripsharp.consumer-tests/focused-consumer-strategy!
   :profile-tests
   {"example-core"
    {:source "consumer-tests/CoreConsumerTests.cs"
     :destination "CoreConsumerTests.cs"
     :sha256 "<lowercase SHA-256>"}}
   :fixtures
   [{:source "consumer-tests/fixtures/example.txt"
     :destination "Fixtures/example.txt"
     :sha256 "<lowercase SHA-256>"
     :license "Apache-2.0"
     :attribution "Authored for the generated consumer suite."}]}
  {:id :adapted-java
   :kind :adapted-upstream
   :policy :shipped
   :project "DripSharp.Example.Tests"
   :handler dripsharp.java-test-suite/strategy!
   :suite
   {:source "adapted-tests/java-suite.edn"
    :sha256 "<lowercase SHA-256>"}}]}
```

Project ids and assembly names agree exactly, including casing. Profile
references select declared production projects; additional project references
must remain below `tests/`. Each strategy is either `:focused-consumer` or
`:adapted-upstream`, names a qualified callable handler, and declares either
`:shipped` or `:validation-only` policy. Strategies sharing a project must
agree on policy. Validation-only project directories must be below an excluded
publication path; shipped projects must not be excluded.

A project may declare `:solution-inclusion true` when its target contract
requires explicit inclusion in the generated product solution. The target
directory rejects any other value; solution materialization remains part of
the target's later generated-repository emission milestone.

Focused test sources and fixtures remain target-owned, checksum-pinned inputs.
Every project uses the product family's exact target framework and includes the
three required pinned test packages; additional test packages must also use
exact stable versions. Shared generation enforces containment,
`IsTestProject=true`, `IsPackable=false`, deterministic staging, fixture
attribution, `SHA256SUMS`, build-artifact cleanup, and ordered `dotnet restore`,
`dotnet build`, and `dotnet test` execution. Strategy handlers contribute
target-specific adapted sources or provenance checks without target branches
in shared orchestration.

The reusable `dripsharp.java-test-suite/strategy!` handler requires the exact
`:suite` record shown above. Its source is a checksum-pinned target-owned EDN
declaration below `adapted-tests/`. That declaration pins each selected Java
source and governed upstream revision, adapted C# helper, fixture, destination,
attribution, and loss-sensitive accounting digest. Custom target handlers such
as Brine's existing upstream-derived strategy may omit `:suite`; they remain
responsible for an equivalent target-owned provenance and integrity contract.
Adding `:suite` does not change a strategy's target-specific `:shipped` or
`:validation-only` policy.

A target that exists only as permanent translator conformance instead uses
the exact variant:

```clojure
{:publication {:kind :conformance-only}}
```

That variant permits no repository, staging, project, managed-path, or mode
fields and requires the `:reusable-translator-conformance` proof role.
Conversely, `:generated-repository` requires the `:product` proof role.
Ordinary generation, proof, and synchronization do not create product
repositories, push branches, or open pull requests.

## Owned file contracts

### Baseline

`baseline.edn` is the target's single reviewed upstream baseline record. It
uses the shared baseline schema and identifies the same target. Its profile
names must exactly match the manifest profiles. Its Java language version,
upstream license, legal sets, package ids, and profile identities are checked
against the manifest and referenced files.

The source language level and build runtime are separate declarations. The
runtime major may be newer than the source language level but not older.
Explicit profile runtime selections must agree with the target declaration.
Live discovery still verifies the observed compiler release against the
baseline; directory validation does not substitute for that evidence.

### Legal policy

`legal/policy.edn` schema version 4 has exactly these keys:

```clojure
{:schema-version 4
 :target :example
 :upstream-license "Apache-2.0"
 :allowed-upstream-licenses #{"Apache-2.0"}
 :legal-sets #{:upstream}
 :notice-appendix-sha256 nil
 :profile-legal-sets {"example-core" [:upstream]}
 :resource-notice-legal-sets {"example-core" [:upstream]}
 :package-metadata
 {"example-core"
  {:required-description-fragments
   ["independent translation" "not affiliated with UpstreamCo"]
   :forbidden-identity-marks ["UpstreamCo"]}}}
```

The selected license must be allowed and must equal the baseline license.
Legal-set names must equal the baseline legal sets, and every profile has one
ordered legal-set selection. Destination and validation package contracts
must use that same selection. Legal source, destination, and package paths are
validated before discovery. Each legal source is also hashed during target
preflight and must match its pinned `:source-sha256`, or its package `:sha256`
when no distinct source digest is declared. Every profile also has one exact
package-metadata policy. Required description fragments must occur in the
configured package description, and forbidden upstream-owner marks must not
occur in package ids, titles, authors, assembly names, root namespaces, or
destination namespace identities. Upstream attribution in descriptions,
copyright metadata, repository URLs, LICENSE, and NOTICE remains allowed.
Exact nupkg inspection then proves that the validated description and identity
metadata reached the artifact unchanged.

`:notice-appendix-sha256` is always present. It is `nil` when the target has no
translation appendix, or the lowercase SHA-256 of the exact target-baseline
`:notice-appendix` text. A selected appendix must be nonblank and the baseline
must contain at least one hash-pinned NOTICE input. Missing, unexpected,
malformed, or changed appendix text fails target preflight; the legal-file
package hash then proves that the exact appendix reached the artifact.

Every profile has one exact `:resource-notice-legal-sets` vector. An empty
vector explicitly declares that its mechanically copied production resources
do not depend on NOTICE attribution. Each selected legal set must also be in
the profile's legal-set selection and must contain at least one pinned NOTICE
input. Execution resolves those inputs to exact package paths. Package planning
then fails if a profile that declares attributed resources emits none, or if
any declared NOTICE path is absent from its legal-file contract; exact nupkg
inspection proves the pinned NOTICE payloads and assembly resources both
reached the artifact.

### Mapping overlays

Each `mappings/*.edn` file uses this exact wrapper:

```clojure
{:schema-version 1
 :target :example
 :product-family :example
 :id :example/core
 :capabilities #{:example/mappings}
 :custom-handlers {}
 :entries []}
```

Entries use the shared declarative resolved-symbol registry schema. The loader
compiles every overlay before returning the target contract, so malformed
keys, duplicate ownership, contradictory strategies, missing evidence, and
unresolved custom handlers fail before source discovery. `:custom-handlers`
maps qualified handler keywords to qualified target-owned Clojure symbols.
Entry provenance must name the target or one of its profiles.

### Runtime assets

Runtime descriptors point to regular `.cs` files under `runtime/` and declare
the capabilities those files provide. For each profile, the selected runtime
paths must exactly match its destination's `:runtime-sources`. Shared
product-neutral compatibility sources remain shared inputs; a target
directory must not claim product behavior belonging to another target.

### Source-accounting contracts

The workspace owns one shared compatibility contract under `config/`; each
target owns `authorship.edn` and `third-party.edn`. All use schema version 1
and list the complete reviewed non-mechanical source inventory. Shared groups
are `:authored-compat`, product-owned target groups are
`:authored-destination-runtime`, and pinned source owned by external projects
is `:vendored-third-party`.

```clojure
{:schema-version 1
 :scope :example
 :class :authored-destination-runtime
 :sources
 [{:id :example/runtime
   :kind :file
   :provenance "targets/example/runtime/Example.Core.Runtime.cs"
   :include-pattern nil
   :charset nil
   :capability nil
   :source-files 1
   :max-source-lines 120
   :max-emitted-lines 120
   :source-inventory-sha256 "…"
   :public-types {:count 3 :sha256 "…"}}]}
```

A `:tree` group uses an anchored `:include-pattern`; a shared compatibility
group also names the capability that selects it. File count, portable path
inventory, source line ceiling, emitted line ceiling, and public declaration
fingerprint are frozen reviewed facts. Drift fails target preflight and is
recomputed again from live inputs during emission and package inspection.
When generation deliberately projects vendored implementation declarations to
lower accessibility, that vendored group also records
`:emitted-public-types {:count … :sha256 …}`. `:public-types` continues to pin
the unchanged source snapshot, while `:emitted-public-types` pins the generated
compile inputs; the emitted count may not exceed the source count. Authored
source groups cannot use this projection.
Every declared target or third-party group must be selected by at least one
profile, and every selected runtime asset must belong to a selected authored
target group.

Each profile freezes its package-level authored line count and authored/total
line fraction. The declared authored count must equal the sum of only its
selected `:authored-compat` and `:authored-destination-runtime` group ceilings;
vendored third-party lines remain in the total denominator but never count as
DripSharp-authored. Increasing a group ceiling, changing the public-type
fingerprint, selecting a new group, or raising the package budget therefore
requires an explicit `:review` contract diff. `:evidence` must name a required
proof ladder that covers that profile; a successful product proof makes the
linked behavior evidence green.

Emission records every compile input in authorship-ledger schema version 3.
Mechanical inputs carry exact upstream revision and header provenance;
authored and vendored third-party inputs carry their reviewed source path,
class, hash, and line count. Authored inputs additionally participate in
evidence linkage and budget proof. Package inspection rejects a missing or
extra compile input, an uncontracted source, source growth, public surface
drift, or an authored fraction above the frozen ratio.
Shared compatibility text is additionally scanned for target, product-family,
package, assembly, and destination namespace identities so it remains
product-neutral for existing and future targets.

### Validation

A `:differential` descriptor points to the shared exact-key differential
contract. Its target, baseline profile, generation profile, assembly, target
framework, and legal sets must agree with the target baseline, destination,
and legal policy. Oracle sources are target-owned `.java` files under
`validation/oracle/`; package-only probes are target-owned `.cs` files under
`validation/probe/`. Required context paths are checked before execution.

A target whose evidence model is genuinely different may use `:kind :custom`.
Its contract has exactly these keys:

```clojure
{:schema-version 1
 :id :example-core
 :target :example
 :profile "example-core"
 :baseline-profile :core
 :runner example.validation/run!
 :oracle-sources ["validation/oracle/ExampleOracle.java"]
 :probe-sources ["validation/probe/ExampleProbe.cs"]
 :legal-sets [:upstream]}
```

The runner must resolve to a callable target-owned symbol. Custom validation
retains the same identity, path, baseline, and legal checks as common
differentials; it does not weaken the product's independent behavior-evidence
or completion requirements.

## Preflight rule

`dripsharp.target-directory/read-target` is the product-neutral preflight. A
workflow must obtain a fully validated result before source checkout
verification, Gradle or Maven discovery, output cleanup, generation, packing,
consumption, or differential execution. A returned contract contains the
validated records and compiled mapping registries; later stages must consume
those records rather than re-reading unvalidated target files.

Successful directory validation is configuration evidence only. It is not
clean-generation, compilation, packaging, consumption, behavior, milestone,
or product-completion evidence.

## Required proof ladders

The manifest's `:proof` contract makes omission impossible to use as a shorter
gate. Its role is either `:product` or
`:reusable-translator-conformance`, and its nonempty `:ladders` vector must
cover every declared profile and validation contract exactly once. Every
ladder is required; the schema intentionally has no optional, skip, or
continue-on-error flag.

A `:target-validations` ladder runs the listed validation contracts through
the metadata-driven target executor. A `:custom` ladder additionally names a
qualified callable runner for an aggregate target proof whose evidence model
cannot be represented as a single common differential. The declared profile
and validation coverage remains exhaustive for both kinds.

Each ladder selects either the `:conformance` or `:high-memory` resource
class. The reusable-translator conformance role is restricted to the
product-neutral Java-library family and the conformance class. Resource
classes select appropriate required CI runners and time budgets; they never
make product behavior optional.
