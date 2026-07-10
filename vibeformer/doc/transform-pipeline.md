# Transform Pipeline

## Translation Context

The translator receives a resolved frontend node plus context such as the
current project, namespace, containing type, generic bindings, expected target
type, and mapping registries. It translates children recursively.

Conceptually:

```clojure
(defmulti emit-element (fn [_ctx element] (class element)))

(defmethod emit-element CtIf [ctx element]
  (result
   :node (csharp-if
          (emit-element ctx (.getCondition element))
          (emit-element ctx (.getThenStatement element))
          (some->> (.getElseStatement element) (emit-element ctx)))))
```

The exact representation may differ, but the important property is that the
frontend's typed tree—not reparsed source text or a second partial AST—drives
the recursion.

## Translation Result

A translation result should carry more than text:

```clojure
{:node csharp-node
 :source-element source-element-id
 :rule :java.if/to-csharp-if
 :required-usings #{"System"}
 :required-helpers #{}
 :diagnostics []
 :provenance []}
```

Parent results combine their children's nodes and metadata. The final emitter
writes C# files, projects, helper references, diagnostics, and source mappings.

## Rule Dispatch

Rules divide into two broad categories:

1. Structural rules keyed by frontend node kind, such as class, method, block,
   `if`, loop, assignment, invocation, or literal.
2. Semantic mappings keyed by resolved symbols, such as a particular Java type,
   method overload, constructor, or field.

A method-call translator first uses Spoon's resolved executable reference. It
then either:

* Calls a project-local method that will also be recursively translated.
* Applies an explicit Java/JDK/framework-to-.NET mapping.
* Requests a focused compatibility helper.
* Emits a blocking unsupported diagnostic.

Simple method names are not sufficient mapping keys when overload or owner
identity matters.

## Coverage and Failure

Accepted output requires every reachable construct and resolved reference to
have one unambiguous translation. Unsupported or unresolved input produces a
diagnostic with the source location, frontend node, resolved information
available, and missing rule or mapping.

Fallback output may be emitted only in an explicitly diagnostic mode. It must
never count as accepted product emission, and public implementation stubs block
completion.

## Helpers and Native Code

Translation rules may request helpers by capability. Helpers are ordinary,
reviewable C# source included in the destination solution. They are not patches
to generated files and are not large C# programs embedded in Clojure strings.

Before adding a helper, verify that normal C# or an existing .NET API cannot
preserve the required semantics. Mark the helper as either reusable migration
infrastructure or destination-specific runtime behavior.

## Project Emission

Project emission derives namespaces, output paths, project references, package
references, resources, target framework, and helper/runtime references from the
resolved source projects and explicit destination mappings.

Generation is from scratch. Compiler failures are mapped to source elements and
translation rules; generated C# is never edited in place.

## Independent Validation

Small fixtures validate individual structural rules and symbol mappings. Whole
projects validate integration and compilation. Independent differential tests
validate behavior through the packed package or public .NET API.

The implementation under test must not generate its own expected behavior.
