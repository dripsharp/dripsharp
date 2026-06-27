# Vibeformer

Vibeformer is a Clojure-based Java/Kotlin to C# transpiler architecture for
porting the `pkl` codebase. It is designed as a compiler-like pipeline: source
facts are extracted into Datomic, deterministic transform rules emit disposable
C# output, and compiler diagnostics drive fixes to the transpiler rather than
manual patches to generated files.

## Why This Exists

The target migration source is `../research/pkl`. That source tree is the input
and feature guide for this project, but it should not be modified by
Vibeformer work.

The project should implement only the Java and Kotlin features needed to port
that codebase. It is not intended to be a general-purpose one-shot LLM
translator, and generated C# is not durable state.

## Core Loop

```text
analyze source
  -> store source facts in Datomic
  -> transform from facts using deterministic rules
  -> emit disposable C# output
  -> compile
  -> if compilation fails, fix the analyzer/model/transform rules
  -> delete generated output and regenerate from scratch
```

The durable assets are:

1. The original Java/Kotlin source.
2. The analysis facts stored in Datomic.
3. The deterministic transformation rules.
4. The source-to-destination provenance data.
5. The compiler diagnostics used to improve the transformer.

Compiler errors are not local patch targets. They are evidence that the
transpiler is incomplete or incorrect.

## Current Stack

Vibeformer currently uses:

* Clojure for orchestration and transformation.
* Datomic Local for the analysis/control-plane database in tests and early
  development.
* Spoon for Java parsing and model extraction.
* Kotlin compiler embeddable PSI APIs for initial Kotlin parsing.
* `dotnet build` or `csc` as the intended C# validation oracle.

Implemented behavior currently includes:

* Datomic Local setup, normalized source facts, transform rules, destination
  project facts, diagnostic facts, and focused inventory summaries.
* Spoon extraction for Java declarations, type refs, method/field/constructor
  refs, modifiers, source spans, common statements, stream/collection/map APIs,
  reflection signals, synchronized constructs, nullable types, and unsupported
  feature markers.
* Kotlin PSI extraction for packages, objects/classes, file facades,
  functions, properties, nullability, calls, safe calls, source spans, and a
  conservative enrichment pass for stable local refs.
* C# emission for the committed Java samples and selected Kotlin samples,
  including source-to-destination provenance, rule applications, helper source
  generation, `.csproj` generation from destination facts, `dotnet build`
  validation where enabled, and compiler diagnostic ingestion.

Important current limitations:

* Full-project C# emission for `../research/pkl` is not implemented yet.
  `research-dry-run` is facts/inventory first and skips C# emission in its
  default `:facts-only` mode.
* Kotlin semantic resolution still uses PSI plus conservative fallback data.
  The Analysis API integration records setup/availability facts but does not
  yet provide full symbol/type resolution.
* Java Spoon still runs with a staged classpath seed strategy rather than a
  resolved jar classpath. Gradle dependency roots reduce some false unresolved
  refs, but unresolved refs remain a deliberate gate.
* Passing samples prove only the modeled subset. Generated C# under
  `sample-projects/*/target/` and `target/research-pkl/` is disposable.

## Quick Start

Run tests from this directory:

```bash
cd /Users/admin/src/pkl/vibeformer
clojure -T:build test
```

Run the CI-style build:

```bash
clojure -T:build ci
```

Run the first committed sample project through the supported integration stages:

```bash
clojure -T:build sample
```

Pass explicit coverage allow modes to the sample runner when intentionally
crossing a coverage boundary:

```bash
clojure -T:build sample :name java-word-count ':coverage/allow-unsupported?' true
clojure -T:build sample :name java-word-count ':coverage/allow-stubs?' true
```

The sample runner writes disposable output under
`sample-projects/<name>/target/`, including diagnostics, exported source facts,
and provenance. When an allow mode is used, `stages.edn`, `coverage.edn`, and
`provenance.edn` record it under `:coverage/allow-mode`.

Run the current full-Pkl dry-run milestone from facts and inventory upward:

```bash
clojure -T:build research-dry-run
```

The dry-run is read-only against `../research/pkl` and writes staged artifacts
under `target/research-pkl/`. It defaults to `:facts-only`; `:emit-only` and
`:compile-capable` modes are accepted so the report can name the current
non-goals and blockers explicitly. It writes `destination.edn` with destination
C# project, project-reference, package, resource, helper, and target-framework
mapping facts derived from the classpath manifest. The unresolved-reference gate
writes `target/research-pkl/diagnostics/unresolved-refs.edn`; it warns in
facts-only mode and fails emission-capable modes while semantic references
remain unresolved.

As of the current milestone, the facts-only dry-run discovers 21 Gradle
projects, 118 dependency entries, 2,120 source files, 778 Java files, and 1,342
Kotlin files in `../research/pkl`. The unresolved-reference gate is expected to
warn until Kotlin Analysis API/module setup, resolved dependency classpaths,
and additional Java/Kotlin semantic mappings reduce the remaining unresolved
references.

Inspect the Gradle/Kotlin classpath inputs for the research checkout:

```bash
clojure -T:build research-classpath
```

This writes `target/research-pkl/classpath.edn` with Gradle projects, Kotlin/
Java/resource source roots, version-catalog aliases, project dependencies,
direct coordinates, dependency expressions, and derived Java package roots used
as the current staged classpath seed.

Important project files:

* `deps.edn` contains runtime and test dependencies.
* `build.clj` contains test, CI, install, and deploy tasks.
* `src/` contains Vibeformer source.
* `test/` contains extraction, inventory, rule coverage, emitter, sample-runner,
  dry-run, destination, diagnostics, and type-mapping regression tests.
* `doc/` contains the architecture details that used to live in this README.

## Development Notes

Treat generated destination projects as scratch output. A failing compile should
turn into a focused change to extraction, modeling, mapping, transform rules, or
compatibility helpers.

When adding support for a new source feature, prefer a small fixture and a
queryable fact shape before expanding the emitter. The long-form design docs
below describe the intended model and pipeline contracts.

## Documentation

Read these documents for the detailed design:

* [Architecture](doc/architecture.md): goal, philosophy, high-level pipeline,
  and why generated C# must not be patched.
* [Port Scope](doc/port-scope.md): .NET library target, excluded product
  surfaces, and third-party library scope decisions.
* [Technology](doc/technology.md): Clojure, Datomic, Spoon, Kotlin PSI/Analysis
  API, and C# validation choices.
* [Datomic Model](doc/datomic-model.md): normalized facts, feature inventory,
  and example queries.
* [Transform Pipeline](doc/transform-pipeline.md): transform rules, transform
  return values, provenance, pass metadata, diagnostics, and pre-emit gates.
* [Conversion Concerns](doc/conversion-concerns.md): type mapping plus Java and
  Kotlin conversion risks.
* [Implementation Plan](doc/implementation-plan.md): suggested build order,
  LLM role, testing strategy, and the key design rule.

## Key Rule

The database should model conversion-relevant facts, not merely mirror parser
internals.

```text
source code is truth
Datomic facts are analyzed truth
transform rules are the transpiler
generated C# is disposable
compiler errors are bugs in the transpiler
```

When generated C# fails to compile, fix the source extraction, semantic model,
type mapping, transform rule, helper library, or project/dependency mapping.
Then regenerate the C# output from scratch.
