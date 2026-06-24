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
 :diagnostic/status :diagnostic.status/open}
```

Diagnostics are not patch instructions. They are failing test cases for the
transpiler.

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
