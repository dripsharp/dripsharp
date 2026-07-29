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

`targets/<target-id>/target.edn` uses schema version 3 and has exactly these
keys:

```clojure
{:schema-version 3
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
  :destination "authorship.edn"}

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

`legal/policy.edn` schema version 2 has exactly these keys:

```clojure
{:schema-version 2
 :target :example
 :upstream-license "Apache-2.0"
 :allowed-upstream-licenses #{"Apache-2.0"}
 :legal-sets #{:upstream}
 :profile-legal-sets {"example-core" [:upstream]}
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
validated before discovery. Every profile also has one exact package-metadata
policy. Required description fragments must occur in the configured package
description, and forbidden upstream-owner marks must not occur in package ids,
titles, authors, assembly names, root namespaces, or destination namespace
identities. Upstream attribution in descriptions, copyright metadata,
repository URLs, LICENSE, and NOTICE remains allowed. Exact nupkg inspection
then proves that the validated description and identity metadata reached the
artifact unchanged. Legal hash verification and resource-specific NOTICE
attribution remain separate legal gates.

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

### Authored source contracts

The workspace owns one shared compatibility contract under `config/`; each
target owns `authorship.edn`. Both use schema version 1 and list the complete
reviewed authored source inventory. Shared groups are
`:authored-compat`; target groups are `:authored-destination-runtime`.

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
Every declared target group must be selected by at least one profile, and
every selected runtime asset must belong to a selected target group.

Each profile freezes its package-level authored line count and authored/total
line fraction. The declared authored count must equal the sum of its selected
group ceilings. Increasing a group ceiling, changing the public-type
fingerprint, selecting a new group, or raising the package budget therefore
requires an explicit `:review` contract diff. `:evidence` must name a required
proof ladder that covers that profile; a successful product proof makes the
linked behavior evidence green.

Emission records every compile input in authorship-ledger schema version 2.
Mechanical inputs carry exact upstream revision and header provenance;
authored inputs carry their reviewed source path, class, hash, line count,
evidence linkage, and budget proof. Package inspection rejects a missing or
extra compile input, an uncontracted authored source, authored growth, public
authored surface drift, or an authored fraction above the frozen ratio.
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
