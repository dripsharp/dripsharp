# Technology

## Clojure

Clojure is the orchestration and transformation language. It provides direct
JVM interop with the Java frontend and works well for recursive visitor
dispatch, immutable translation results, mapping registries, and diagnostics.

## Java Frontend: Spoon

Spoon is the Java semantic frontend. Product translation must configure it with
the actual source sets, generated sources, dependencies, and classpath so that
types and symbols resolve correctly.

The translator consumes Spoon elements directly and recursively. Required
semantic information includes:

* Declared and inferred types.
* Generic arguments and bounds.
* Resolved method overloads.
* Resolved constructor and field targets.
* Inheritance and interface relationships.
* Modifiers, annotations, statements, expressions, and source positions.

Spoon no-classpath mode may support diagnostics over incomplete inputs, but it
is not an accepted product-emission path.

## Kotlin Source Policy

Vibeformer does not require a Kotlin PSI or Analysis API frontend and does not
translate Kotlin to C#. Kotlin source and tests may be inspected as behavior
references when they describe an in-scope Pkl capability. The destination
behavior is then implemented through translated Java or an idiomatic .NET
implementation.

This is a language-scope decision, not a reduction in Vibeformer's generality:
the Java frontend, recursive translator, symbol mappings, and compatibility
capabilities remain reusable for non-Pkl Java projects.

## C# Representation

The translator may initially emit C# through a small structured writer rather
than a full Roslyn dependency. Translation results must preserve nesting and
precedence and accumulate required usings, helper requests, diagnostics, and
source mappings.

Roslyn can be introduced later if a concrete need for destination-AST
rewriting or analyzers justifies it.

## Validation

Use `dotnet build` as the compilation oracle. Map compiler diagnostics back to
source elements and mapping/transform rules.

Use independent consumers and upstream differential tests as the behavior
oracle. A generated test that shares implementation assumptions with the
translator is not sufficient product evidence.

## Core Stack

```text
Clojure orchestration and recursive translators
Spoon typed semantic model for Java
resolved-symbol mapping registries
ordinary C# compatibility/destination-runtime projects
dotnet build and independent behavior tests
```
