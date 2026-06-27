# Transform Pipeline

## Transform Rules

Transform rules should be deterministic and data-driven.

A transform rule should declare what it handles:

```clojure
{:rule/id :java.method/to-csharp-method
 :rule/source-lang :lang/java
 :rule/input-kind :java.node/method
 :rule/status :rule.status/implemented
 :rule/version 3}
```

For feature-level support:

```clojure
{:rule/id :kt.data-class/to-csharp-record
 :rule/source-lang :lang/kotlin
 :rule/input-feature :kt.feature/data-class
 :rule/output-feature :csharp.feature/record
 :rule/status :rule.status/implemented}
```

Every transform application should be recorded:

```clojure
{:rule-app/id "..."
 :rule-app/rule [:rule/id :java.method/to-csharp-method]
 :rule-app/source-node [:node/id "..."]
 :rule-app/pass [:pass/id "emit-v42"]
 :rule-app/status :rule-app.status/success}
```

Failures should also be recorded:

```clojure
{:rule-app/id "..."
 :rule-app/rule [:rule/id :java.stream/to-linq]
 :rule-app/source-node [:node/id "..."]
 :rule-app/pass [:pass/id "emit-v42"]
 :rule-app/status :rule-app.status/failed
 :rule-app/error "Unsupported collector groupingBy"}
```

## Transform Function Shape

Conceptually, transform functions should be pure-ish.

Example:

```clojure
(defmulti emit-node :node/kind)

(defmethod emit-node :java.node/if [ctx node]
  ...)

(defmethod emit-node :kt.node/when [ctx node]
  ...)
```

But each transform should return more than text.

It should return something like:

```clojure
{:text "if (...) { ... }"
 :source-node node-id
 :rule :java.if/to-csharp-if
 :features-covered #{:java.feature/if-statement}
 :imports-required #{"System"}
 :helpers-required #{:helper/java-objects-equals}
 :provenance [...]}
```

The emitter should produce:

```text
C# text
+ source mapping
+ rule applications
+ required usings/imports
+ required compatibility helpers
+ diagnostics/warnings
```

Compatibility helpers are generated from helper metadata, not patched into
disposable C# output. Type mapping and rules may request helper ids such as
`:helper/java-optional`; the C# emitter resolves those ids through its helper
catalog, writes helper source files under the generated project, records helper
inventory in provenance and destination artifacts, and includes helper files in
the generated `.csproj`.

Unknown type mappings may still emit a simple fallback type name so downstream
output remains inspectable, but the fallback must produce structured C#
diagnostics with the source type, language, mapping reason, and fallback reason.
Silent type fallback is a pipeline bug.

## Current Implementation Status

The committed sample pipeline implements the core data flow for a supported
subset:

```text
source discovery
  -> Datomic source facts
  -> Java/Kotlin extraction and enrichment
  -> transform rule registration and coverage checks
  -> C# emission with provenance/rule applications/helper metadata
  -> destination project facts and generated .csproj
  -> optional dotnet build
  -> compiler diagnostic ingestion and mapping-quality summaries
```

The C# emitter currently covers the committed Java samples and selected Kotlin
samples, not arbitrary Pkl source. Java coverage includes classes, interfaces,
records, enums, annotations, methods, constructors, fields, locals, assignment,
return/if/throw/foreach/try/catch/finally/synchronized statements, switch
expressions, common literals/operators, selected stream APIs, reflection
inspection APIs, nullable annotations, `Optional` helpers, collection/map APIs,
and known Java runtime mappings. Kotlin coverage includes basic declarations,
objects/interfaces, top-level file facades, simple returns/throws/properties,
safe calls, Elvis expressions, and selected Java API calls.

The full-Pkl research path is deliberately facts-first. `research-dry-run`
discovers classpath and destination mappings, ingests source facts, registers
rules, writes inventory and unresolved-reference diagnostics, and skips C#
emission in `:facts-only` mode. Emission-capable modes are blocked by the
unresolved-reference gate and by missing full-project emission.

## Source-to-Destination Provenance

Every emitted C# span should map back to source facts.

Example:

```clojure
{:emit/id "..."
 :emit/source-node [:node/id "..."]
 :emit/dest-file "Generated/Foo.cs"
 :emit/start-line 42
 :emit/start-column 1
 :emit/end-line 57
 :emit/end-column 2
 :emit/rule [:rule/id :java.method/to-csharp-method]
 :emit/pass [:pass/id "emit-v42"]}
```

This mapping is essential for interpreting compiler diagnostics.

When the compiler reports:

```text
Foo.cs(51,18): error CS1503: Argument 1: cannot convert from ...
```

the system should resolve:

```text
Foo.cs line 51 col 18
  -> emitted span
  -> transform rule
  -> source node
  -> source feature
  -> original Java/Kotlin file and construct
```

Then the fix is made in the model or transform rule, not in `Foo.cs`.

## Pass Metadata

Track each pipeline run.

Analysis pass:

```clojure
{:pass/id "analysis-2026-06-23T18:00:00Z"
 :pass/kind :pass.kind/source-analysis
 :pass/source-hash "..."
 :pass/tool-version "git-sha"}
```

Emit pass:

```clojure
{:pass/id "emit-v42"
 :pass/kind :pass.kind/csharp-emit
 :pass/input-analysis [:pass/id "analysis-2026-06-23T18:00:00Z"]
 :pass/transform-version "git-sha-of-transpiler"}
```

Compile pass:

```clojure
{:pass/id "compile-v42"
 :pass/kind :pass.kind/csharp-compile
 :pass/input-emit [:pass/id "emit-v42"]
 :pass/compiler "dotnet build"
 :pass/status :pass.status/failed}
```

## Compiler Diagnostics

Compiler diagnostics should be ingested back into Datomic.

Example:

```clojure
{:diagnostic/id "compile-v42:Foo.cs:51:18:CS1503"
 :diagnostic/pass [:pass/id "compile-v42"]
 :diagnostic/code "CS1503"
 :diagnostic/message "Argument 1: cannot convert from ..."
 :diagnostic/file "Generated/Foo.cs"
 :diagnostic/start-line 51
 :diagnostic/start-column 18
 :diagnostic/severity :diagnostic.severity/error
 :diagnostic/source-node [:node/id "..."]
 :diagnostic/rule [:rule/id :java.collection/call]
 :diagnostic/source-features [[:feature/id "..."]]
 :diagnostic/mapping-status :diagnostic.mapping/mapped
 :diagnostic/mapping-reason :diagnostic.mapping/provenance-span
 :diagnostic/status :diagnostic.status/open}
```

Diagnostics are not patch instructions. They are failing test cases for the
transpiler.

Current sample diagnostic ingestion parses `dotnet build` output, transacts a
compile pass plus diagnostic facts, and writes
`target/diagnostics/dotnet-diagnostic-facts.edn`. Mapped diagnostics carry the
source node, transform rule, and source feature refs from provenance. Unmapped
diagnostics are grouped separately under `:unmapped-rankings` so missing
provenance spans are visible instead of being mixed into rule failures.

## Pre-Emit Gates

Before emitting C#, run coverage checks.

The system should be able to fail early with messages like:

```text
No transform rule for:
  :kt.feature/delegated-property
  src/main/kotlin/Foo.kt:44
```

or:

```text
Unsupported Java construct:
  :java.feature/synchronized-block
  src/main/java/com/acme/Cache.java:88
```

This is better than emitting nonsense and waiting for C# compilation to fail.

The planner should verify:

```text
Every reachable source construct has exactly one applicable transform rule,
or is explicitly marked intentionally unconverted/stubbed.
```

Unsupported constructs may be allowed only under an explicit mode, for example:

```text
--emit-stubs
```

In stub mode, the generated output may contain clear TODOs or throwing
placeholders. But this should be deliberate.

Implemented gates today include transform rule coverage for sample runs and an
unresolved-reference gate for `research-dry-run`. In facts-only research mode,
unresolved references are reported as warnings in
`target/research-pkl/diagnostics/unresolved-refs.edn`; emission-capable modes
fail while unresolved semantic refs remain above the allowed threshold.
