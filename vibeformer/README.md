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

The existing tests smoke-check:

* Datomic Local setup and simple fact graph transactions.
* Spoon extraction of package, class, method, field, type, call, modifier, and
  source-position facts.
* Kotlin PSI extraction of package, object, class, function, property,
  nullability, call, safe-call, and text-offset source-position facts.

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

The sample runner writes disposable output under
`sample-projects/<name>/target/`, including diagnostics, exported source facts,
and provenance. C# emission and `dotnet build` are recorded as skipped until
project generation exists.

Important project files:

* `deps.edn` contains runtime and test dependencies.
* `build.clj` contains test, CI, install, and deploy tasks.
* `src/` contains Vibeformer source.
* `test/` contains parser and Datomic smoke tests.
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
