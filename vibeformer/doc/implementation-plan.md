# Implementation Plan

## Implementation Status

This file is a living plan. Items marked implemented are present for the
committed samples and/or facts-only research dry-run; they do not imply Pkl is
portable yet.

Implemented:

1. Project/file discovery for samples and `../research/pkl`.
2. Java source extraction with Spoon for declarations, refs, source nodes,
   features, common statements, selected APIs, and unsupported constructs.
3. Kotlin source extraction with PSI for syntax facts, top-level file facades,
   source spans, and conservative enrichment.
4. Datomic schema for files, nodes, declarations, types, refs, features,
   transform rules, rule applications, diagnostics, destination projects, and
   provenance-oriented facts.
5. Feature inventory and unresolved-reference reports.
6. Java C# emission for the committed sample subset.
7. Kotlin C# emission for selected committed samples.
8. Type mapping for primitives, strings, collections/maps, nullable types,
   arrays, optionals, exceptions, function types, and project-local types.
9. Sample-level destination project generation from destination facts.
10. Sample-level `dotnet build`, diagnostic parsing, diagnostic fact ingestion,
    and mapped/unmapped diagnostic quality summaries.
11. Research classpath, destination, inventory, provenance, and unresolved-ref
    dry-run artifacts under `target/research-pkl/`.

Partial or intentionally blocked:

1. Kotlin Analysis API integration records setup/availability facts, but full
   symbol/type resolution is not active in the current dependency set.
2. Java dependency resolution uses Gradle-derived package-root seeds, not a
   resolved jar classpath.
3. `research-dry-run` defaults to facts-only mode; full-project C# emission and
   `dotnet build` over `../research/pkl` are explicit future stages.
4. Emission-capable research modes are blocked by unresolved semantic refs and
   missing full-project emission.

Still to implement for the first full-Pkl dry-run milestone:

1. Reduce unresolved Java/Kotlin refs with stronger semantic resolution and
   project/dependency classpath facts.
2. Expand Java/Kotlin source extraction and transform rules according to
   research inventory frequency.
3. Generate full C# project trees for the research checkout from destination
   mapping facts.
4. Run `dotnet build` where feasible, ingest diagnostics, and turn ranked
   diagnostics into focused analyzer/model/rule/helper work.
5. Keep provenance strong enough that compiler diagnostics map back to source
   nodes, source features, and transform rules.

## Original Build Order

The original build order remains useful for sequencing new work:

1. Project/file discovery.
2. Java source extraction with Spoon.
3. Kotlin source extraction with Kotlin PSI/Analysis API.
4. Datomic schema for files, nodes, declarations, types, refs, features.
5. Feature inventory queries.
6. Basic Java class/interface/method/field emission.
7. Basic Kotlin class/function/property emission.
8. Type mapping for primitives, strings, collections, nullable types.
9. Basic statements and expressions:

   * return
   * if
   * while
   * for
   * switch/when
   * assignment
   * method calls
   * field/property access

10. Project generation:

   * `.csproj`
   * namespaces
   * usings
   * generated folder structure

11. Compile step via `dotnet build`.
12. Diagnostic ingestion into Datomic.
13. Source-to-destination provenance mapping.
14. Rule coverage gates.
15. More advanced Java/Kotlin features ranked by actual frequency in the
    source codebase.
16. Runtime compatibility helpers where necessary.
17. LLM-assisted rule development.

## LLM Role

LLMs should help improve the transpiler, not produce untracked manual edits to
generated C#.

Good LLM use:

```text
Here is a source feature.
Here are 20 examples from the analyzed codebase.
Here is the current deterministic transform rule.
Here are the compiler errors produced by this rule.
Suggest a better deterministic transform.
```

Bad LLM use:

```text
Here is generated Foo.cs. Please patch it manually.
```

Acceptable LLM tasks:

* Help design transform rules.
* Explain compiler failures.
* Suggest type mappings.
* Generate Clojure code for deterministic transforms.
* Generate test fixtures.
* Classify unsupported constructs.
* Propose compatibility helper APIs.
* Summarize feature inventory.
* Help port one rule at a time.

Generated C# should still be regenerated from source after rules are updated.

## Testing Strategy

Each transform rule should have fixtures.

Example fixture shape:

```text
input:
  Java/Kotlin snippet

expected:
  Generated C# snippet or normalized structural expectation

compile:
  Should compile or should produce known intentional stub
```

Test levels:

1. Unit tests for individual feature extractors.
2. Unit tests for type mapping.
3. Unit tests for transform rules.
4. Golden-file tests for generated C# snippets.
5. Whole-project compile tests.
6. Regression tests for compiler diagnostics previously seen.

When a compiler diagnostic is fixed, add a regression test so that feature does
not break again.

## Key Design Rule

The database should model conversion-relevant facts, not merely mirror parser
internals.

Bad goal:

```text
Store the entire Spoon/Kotlin AST in Datomic.
```

Good goal:

```text
Store enough normalized facts to answer:
  What constructs exist?
  What do they refer to?
  Which constructs are supported?
  Which transform rule applies?
  What generated output came from this source node?
  Which compiler errors came from this rule?
  What should we implement next?
```
