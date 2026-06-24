# Vibeformer

Java/Kotlin to C# Transpiler Architecture: Analysis Database + Deterministic Transform Pipeline

## Goal

Build a source-to-source migration system that ports Java and Kotlin code to C#. The ultimate goal when finished will be to port pkl from Java and Kotlin to C#. The source for this is located in ../research/pkl. Do not modify it, but use it to drive the features we need and test this library. We only want to implement what is needed for that project.

The system should not be a one-shot LLM translator and should not rely on hand-editing generated C# output. Instead, it should behave more like a compiler/transpiler:

```text
analyze source
  -> store source facts in Datomic
  -> transform from facts using deterministic rules
  -> emit disposable C# output
  -> compile
  -> if compilation fails, fix the analyzer/model/transform rules
  -> delete generated output and regenerate from scratch
```

The generated C# is disposable. The durable assets are:

1. The original Java/Kotlin source.
2. The analysis facts stored in Datomic.
3. The deterministic transformation rules.
4. The mapping/provenance data between source constructs and generated output.
5. The compiler diagnostics used to improve the transformer.

Compiler errors are not treated as local patch targets. They are evidence that the transpiler is incomplete or incorrect.

## Core Philosophy

The central principle is:

```text
source code is truth
Datomic facts are analyzed truth
transform rules are the transpiler
generated C# is disposable
compiler errors are bugs in the transpiler
```

Do not patch generated C# directly. If generated code fails to compile, the correct response is to improve one or more of:

* Source fact extraction
* Semantic modeling
* Type mapping
* Rule selection
* Transformation logic
* Runtime compatibility helpers
* Project/dependency mapping

Then regenerate the entire C# project from source facts.

## High-Level Pipeline

```text
1. Source discovery
   Locate .java, .kt, build files, generated sources, resources, dependencies.

2. Per-language analysis
   Java source -> Spoon extractor
   Kotlin source -> Kotlin PSI / Kotlin Analysis API extractor

3. Normalize into Datomic
   Store declarations, types, calls, inheritance, features, source spans, rule coverage data.

4. Feature inventory
   Query what Java/Kotlin constructs actually exist in the codebase.

5. Conversion planning
   Determine which features are supported, unsupported, risky, or intentionally stubbed.

6. Transform
   Apply deterministic conversion rules from Datomic facts to C# output.

7. Emit
   Generate full C# project files and provenance mappings.

8. Compile
   Run dotnet build or csc.

9. Diagnose
   Ingest compiler errors back into Datomic and map them to source nodes and transform rules.

10. Fix transpiler
   Update extractor/model/rules/helpers.

11. Regenerate from scratch
   Delete generated output and rerun the pipeline.
```

## Technology Choices

### Clojure

Use Clojure as the orchestration and transformation language.

Reasons:

* Excellent for data-oriented programming.
* Convenient JVM interop with Spoon and Kotlin tooling.
* Natural fit for Datomic.
* Good for rule dispatch, recursive tree walking, EDN data, and REPL-driven development.

### Datomic

Use Datomic as the analysis and control-plane database.

Datomic should not store the entire raw Spoon or Kotlin PSI object graph. Instead, it should store normalized, conversion-relevant facts.

Good Datomic facts:

* Files
* Packages/namespaces
* Classes/interfaces/enums/objects
* Methods/functions/constructors
* Fields/properties
* Type refs
* Symbol refs
* Call sites
* Inheritance/interface implementation
* AST-ish source nodes
* Language features
* Unsupported constructs
* Transform rule applications
* Source-to-destination provenance
* Compiler diagnostics
* Pass metadata

Avoid storing every token, punctuation mark, whitespace node, or opaque serialized compiler object.

### Java Frontend: Spoon

Use Spoon as the Java analysis frontend.

Spoon is responsible for parsing Java source and producing a useful Java model. It should provide enough structure for:

* Classes
* Interfaces
* Enums
* Methods
* Fields
* Modifiers
* Type references
* Method calls
* Constructors
* Imports/packages
* Inheritance
* Annotations
* Statements and expressions
* Source positions

Spoon should be used for Java only.

### Kotlin Frontend: Kotlin PSI + Kotlin Analysis API

Do not treat Kotlin as “Java with nicer syntax.” Kotlin needs its own frontend.

Use Kotlin PSI and Kotlin Analysis API to extract Kotlin syntax and semantic information.

Kotlin-specific constructs must be modeled explicitly, including:

* Nullability
* Safe calls
* Elvis operator
* Data classes
* Sealed classes
* Object declarations
* Companion objects
* Top-level functions
* Extension functions
* Extension properties
* Default arguments
* Named arguments
* Destructuring
* Delegated properties
* Smart casts
* Lambdas
* Inline functions
* Reified type parameters
* Suspend functions/coroutines
* Kotlin collection semantics

KSP is not enough as the main extractor because it does not expose full expression and statement-level detail.

### C# Output Validation

Use the C# compiler directly through:

```text
dotnet build
```

or:

```text
csc
```

Roslyn is not required in the first implementation.

The compiler provides the necessary oracle:

* Does the generated C# parse?
* Does it type-check?
* Do project references resolve?
* Are overloads valid?
* Are generated helper APIs sufficient?

Compiler diagnostics should be parsed and stored in Datomic.

Roslyn may be considered later for AST-aware C# post-processing or analyzers, but it should not be part of the critical path initially.

## Important Non-Goal: Patching Generated C#

Do not use this workflow:

```text
generate rough C#
  -> patch generated C#
  -> LLM fix generated C#
  -> keep massaging generated output
```

That creates unreproducible state and undermines the transpiler.

Instead use:

```text
generate C#
  -> compile fails
  -> identify bad source feature / transform rule / type mapping
  -> fix transpiler
  -> regenerate everything
```

Generated C# should be treated like `target/`, `bin/`, generated protobuf code, or compiler output.

## Datomic Model

### Files

Example:

```clojure
{:file/path "src/main/java/com/acme/Foo.java"
 :file/lang :lang/java
 :file/hash "sha256..."
 :file/project [:project/id "my-project"]
 :file/package "com.acme"}
```

For Kotlin:

```clojure
{:file/path "src/main/kotlin/com/acme/Foo.kt"
 :file/lang :lang/kotlin
 :file/hash "sha256..."
 :file/project [:project/id "my-project"]
 :file/package "com.acme"}
```

### Source Nodes

Use normalized source nodes for AST-ish structure.

```clojure
{:node/id "my-project:src/main/java/com/acme/Foo.java:method:com.acme.Foo.bar"
 :node/lang :lang/java
 :node/kind :java.node/method
 :node/name "bar"
 :node/file [:file/path "src/main/java/com/acme/Foo.java"]
 :node/parent [:node/id "..."]
 :node/ordinal 3
 :node/start-line 44
 :node/start-column 5
 :node/end-line 71
 :node/end-column 6
 :node/source-hash "..."}
```

Do not rely only on line numbers for identity. Use stable IDs based on:

* Project ID
* File path
* Language
* Node kind
* Qualified name when available
* Parent/ordinal path
* Source span
* Source text hash

### Declarations

Separate syntax nodes from semantic declarations.

```clojure
{:decl/id "java:com.acme.Foo#bar(java.lang.String)"
 :decl/lang :lang/java
 :decl/kind :decl.kind/method
 :decl/name "bar"
 :decl/qualified-name "com.acme.Foo.bar"
 :decl/source-node [:node/id "..."]
 :decl/return-type [:type/id "java.lang.String"]
 :decl/modifiers #{:public :static}}
```

Kotlin example:

```clojure
{:decl/id "kt:com.acme.foo"
 :decl/lang :lang/kotlin
 :decl/kind :decl.kind/function
 :decl/name "foo"
 :decl/qualified-name "com.acme.foo"
 :decl/source-node [:node/id "..."]
 :decl/return-type [:type/id "kotlin.String?"]}
```

### Types

Represent source-language types explicitly.

```clojure
{:type/id "java.util.List<com.acme.Customer>"
 :type/lang :lang/java
 :type/name "java.util.List"
 :type/args [[:type/id "com.acme.Customer"]]
 :type/nullable? false}
```

Kotlin nullability should be first-class:

```clojure
{:type/id "kotlin.String?"
 :type/lang :lang/kotlin
 :type/name "kotlin.String"
 :type/nullable? true}
```

### Symbol and Type References

Track references from use sites to declarations where possible.

```clojure
{:ref/id "..."
 :ref/kind :ref.kind/method-call
 :ref/from-node [:node/id "..."]
 :ref/to-decl [:decl/id "java.util.List#add(E)"]
 :ref/name "add"
 :ref/owner-type [:type/id "java.util.List<T>"]
 :ref/resolved? true}
```

For unresolved refs:

```clojure
{:ref/id "..."
 :ref/kind :ref.kind/method-call
 :ref/from-node [:node/id "..."]
 :ref/name "foo"
 :ref/resolved? false
 :ref/reason :resolve.reason/missing-classpath}
```

Unresolved references should not be ignored. They are important risk signals.

## Feature Inventory

Every conversion-relevant construct should be represented as a feature fact.

Example:

```clojure
{:feature/id "..."
 :feature/lang :lang/java
 :feature/kind :java.feature/anonymous-class
 :feature/node [:node/id "..."]
 :feature/status :feature.status/unsupported
 :feature/severity :feature.severity/hard}
```

Java feature examples:

```clojure
:java.feature/class
:java.feature/interface
:java.feature/enum
:java.feature/annotation
:java.feature/generic-method
:java.feature/wildcard-generic
:java.feature/raw-type
:java.feature/anonymous-class
:java.feature/inner-class
:java.feature/static-import
:java.feature/package-private-member
:java.feature/checked-exception
:java.feature/try-with-resources
:java.feature/synchronized-method
:java.feature/synchronized-block
:java.feature/lambda
:java.feature/stream-api
:java.feature/reflection
:java.feature/native-method
```

Kotlin feature examples:

```clojure
:kt.feature/nullability
:kt.feature/safe-call
:kt.feature/elvis
:kt.feature/data-class
:kt.feature/sealed-class
:kt.feature/object-declaration
:kt.feature/companion-object
:kt.feature/top-level-function
:kt.feature/extension-function
:kt.feature/extension-property
:kt.feature/default-argument
:kt.feature/named-argument
:kt.feature/destructuring
:kt.feature/delegated-property
:kt.feature/smart-cast
:kt.feature/lambda
:kt.feature/inline-function
:kt.feature/reified-type-parameter
:kt.feature/suspend-function
:kt.feature/coroutine
```

Feature inventory is used to decide what to implement next.

## Example Datomic Queries

Count all features:

```clojure
(d/q '[:find ?kind (count ?f)
       :where
       [?f :feature/kind ?kind]]
     db)
```

Find unsupported features:

```clojure
(d/q '[:find ?kind (count ?f)
       :where
       [?f :feature/status :feature.status/unsupported]
       [?f :feature/kind ?kind]]
     db)
```

Find unsupported features by file:

```clojure
(d/q '[:find ?path ?kind (count ?f)
       :where
       [?f :feature/status :feature.status/unsupported]
       [?f :feature/kind ?kind]
       [?f :feature/node ?n]
       [?n :node/file ?file]
       [?file :file/path ?path]]
     db)
```

Find highest-value transform rules to implement:

```clojure
(d/q '[:find ?kind (count ?f) (count-distinct ?file)
       :where
       [?f :feature/status :feature.status/unsupported]
       [?f :feature/kind ?kind]
       [?f :feature/node ?node]
       [?node :node/file ?file]]
     db)
```

Find files with no unsupported features:

```clojure
(d/q '[:find ?path
       :where
       [?file :file/path ?path]
       (not-join [?file]
         [?node :node/file ?file]
         [?feature :feature/node ?node]
         [?feature :feature/status :feature.status/unsupported])]
     db)
```

Find which transform rules produce compiler errors:

```clojure
(d/q '[:find ?rule ?code (count ?d)
       :where
       [?d :diagnostic/rule ?rule]
       [?d :diagnostic/code ?code]]
     db)
```

Find which source features are associated with compiler errors:

```clojure
(d/q '[:find ?feature (count ?d)
       :where
       [?d :diagnostic/source-node ?n]
       [?f :feature/node ?n]
       [?f :feature/kind ?feature]]
     db)
```

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

Diagnostics are not patch instructions. They are failing test cases for the transpiler.

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

In stub mode, the generated output may contain clear TODOs or throwing placeholders. But this should be deliberate.

## Type Mapping

Type mapping should be explicit and queryable.

Examples:

```clojure
{:type-map/source [:type/id "java.lang.String"]
 :type-map/target "string"
 :type-map/rule [:rule/id :type.java-string/to-csharp-string]}
```

```clojure
{:type-map/source [:type/id "java.util.List<T>"]
 :type-map/target "List<T>"
 :type-map/required-using "System.Collections.Generic"}
```

```clojure
{:type-map/source [:type/id "kotlin.String?"]
 :type-map/target "string?"
 :type-map/rule [:rule/id :type.kotlin-nullable/to-csharp-nullable]}
```

Important mapping categories:

* Java primitives
* Java boxed primitives
* `String`
* `BigDecimal`
* `BigInteger`
* `List`
* `Map`
* `Set`
* `Optional`
* Arrays
* Kotlin nullable types
* Kotlin collections
* Kotlin function types
* Java/Kotlin exceptions
* Date/time APIs
* Framework-specific classes
* Project-local types

## Java-Specific Conversion Concerns

Java constructs that need careful handling:

* Checked exceptions
* Package-private visibility
* Static imports
* Anonymous classes
* Inner classes
* Wildcard generics
* Raw types
* Java streams
* `synchronized`
* `wait` / `notify`
* Reflection
* Annotations
* Enum classes with fields/methods
* Overloaded methods
* Varargs
* Try-with-resources
* Lombok-generated code
* Maven/Gradle dependency mapping

## Kotlin-Specific Conversion Concerns

Kotlin constructs that need careful handling:

* Nullability
* Safe calls
* Elvis operator
* Smart casts
* Data classes
* Sealed classes
* Object declarations
* Companion objects
* Extension functions/properties
* Default arguments
* Named arguments
* Top-level declarations
* Suspend functions
* Coroutines
* Flows/channels
* Delegated properties
* Inline functions
* Reified generics
* Operator overloads
* Scope functions like `let`, `run`, `also`, `apply`
* DSL builders
* Kotlin read-only vs mutable collection semantics

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
15. More advanced Java/Kotlin features ranked by actual frequency in the source codebase.
16. Runtime compatibility helpers where necessary.
17. LLM-assisted rule development.

## LLM Role

LLMs should help improve the transpiler, not produce untracked manual edits to generated C#.

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

When a compiler diagnostic is fixed, add a regression test so that feature does not break again.

## Key Design Rule

The database should model conversion-relevant facts, not merely mirror parser internals.

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

## Summary

The desired system is a compiler-like Java/Kotlin-to-C# migration pipeline.

The correct loop is:

```text
analyze -> Datomic -> transform -> compile
```

If compilation fails, the generated output is not patched. Instead, the analyzer, source model, type mapping, transform rule, or helper library is fixed. Then the generated C# output is deleted and recreated from scratch.

This keeps the migration reproducible, queryable, testable, and incrementally improvable.

The core stack is:

```text
Clojure
Datomic
Spoon for Java
Kotlin PSI / Kotlin Analysis API for Kotlin
dotnet build or csc for validation
LLMs as assistants for deterministic rule development
```

Roslyn is not required in the initial version. The C# compiler is sufficient as the validation oracle. The most important destination-side data is compiler diagnostics mapped back through source-to-destination provenance into Datomic.

The main implementation challenge is not syntax emission. It is building a useful source fact model, tracking feature coverage, and making every compiler failure actionable against the deterministic transpiler.
