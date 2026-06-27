# Architecture

## Goal

Build a source-to-source migration system that ports Java and Kotlin code to
C#. The ultimate goal is to port `pkl` from Java and Kotlin to C#. The source
for this is located in `../research/pkl`. Do not modify it, but use it to drive
the features Vibeformer needs and test this library. Implement only what is
needed for that project.

The system should not be a one-shot LLM translator and should not rely on
hand-editing generated C# output. Instead, it should behave more like a
compiler/transpiler:

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

Compiler errors are not treated as local patch targets. They are evidence that
the transpiler is incomplete or incorrect.

## Core Philosophy

The central principle is:

```text
source code is truth
Datomic facts are analyzed truth
transform rules are the transpiler
generated C# is disposable
compiler errors are bugs in the transpiler
```

Do not patch generated C# directly. If generated code fails to compile, the
correct response is to improve one or more of:

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

## Current Milestone Boundary

The committed implementation has not completed this whole pipeline for
`../research/pkl`. The current milestone is intentionally staged:

```text
samples:
  analyze -> Datomic -> transform -> emit C# -> optional dotnet build
          -> ingest diagnostics when build output exists

research/pkl:
  discover -> classpath/destination mapping -> source facts -> inventory
           -> unresolved-reference diagnostics -> provenance
           -> skip C# emission in facts-only mode
```

That distinction matters. Green samples and a green facts-only research dry-run
mean the durable pipeline is improving; they do not mean Pkl is portable yet.
The next milestone is to reduce unresolved references, generate full research
C# projects from destination facts, run `dotnet build` where feasible, and feed
diagnostics back into analyzer/model/rule/helper work.

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

Generated C# should be treated like `target/`, `bin/`, generated protobuf code,
or compiler output.

## Summary

The desired system is a compiler-like Java/Kotlin-to-C# migration pipeline.

The correct loop is:

```text
analyze -> Datomic -> transform -> compile
```

If compilation fails, the generated output is not patched. Instead, the
analyzer, source model, type mapping, transform rule, or helper library is
fixed. Then the generated C# output is deleted and recreated from scratch.

This keeps the migration reproducible, queryable, testable, and incrementally
improvable.

The main implementation challenge is not syntax emission. It is building a
useful source fact model, tracking feature coverage, and making every compiler
failure actionable against the deterministic transpiler.
