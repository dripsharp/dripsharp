# Implementation Plan

## Implementation Order

Suggested implementation order:

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
