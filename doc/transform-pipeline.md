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
 :source-mappings []}
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

Reusable type and member adaptations use the validated
[declarative resolved-symbol mapping registry](mapping-registry.md). Registry
entries retain strategy, semantic caveats, introducing target, and behavior
evidence; duplicate, contradictory, malformed, unsupported, or unmapped
identities fail closed.

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

`dripsharp.java-project` owns the product-neutral scheduling, source and
declaration accounting, collision gates, resource copying, and deterministic
project files. A destination supplies one explicit rule bundle with structural
declaration rules, resolved Java/JDK mappings, namespace/project/resource
policy, destination bridges, and optional product runtime assets. Pkl and
PdfCarton each select their own bundle from product-owned configuration; the
reusable emitter neither imports nor falls back to either bundle.

Profile orchestration resolves the profile, destination, bundle factory, and
public-surface strategy as one fail-closed plan before source discovery or
output cleanup. Profile, destination, bundle, and public strategy product
families must agree. Dependency/resource identities from the validated neutral
project input are compared to the destination contract before Spoon resolution,
and product runtime
sources require an explicit bundle capability. Isolated-consumer behavior is
destination metadata, not a product-name dispatch table.

Operational target inputs follow the shared
[target-directory contract](target-directory-contract.md). The exact-key,
schema-versioned manifest is validated before discovery and binds the
authoritative product documents, baseline, legal policy, Java versions,
profiles, destinations, mapping overlays, runtime assets, and validation
contracts. The product-neutral loader has no target registry: a conforming
target directory can be added without editing generic source. A validated
directory is configuration evidence only and does not weaken any target's
generation, packaging, consumption, behavior, or completion gates.

Generation is from scratch. Compiler failures are mapped to source elements and
translation rules; generated C# is never edited in place.

## Independent Validation

Small fixtures validate individual structural rules and symbol mappings. Whole
projects validate integration and compilation. Independent differential tests
validate behavior through the packed package or public .NET API.

The implementation under test must not generate its own expected behavior.

Common differentials use the product-neutral runner in
`dripsharp.differential` and a target-owned, schema-versioned contract. The
contract selects the baseline profile, JVM oracle source, isolated package-only
.NET probe, required observation families and count, package expectations,
supported hosts, and summary data. Observation streams begin with
`DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1`, followed by exact
`family<TAB>id<TAB>value` rows. Unknown contract keys, unsupported schema
versions, malformed or duplicate rows, missing families, count drift, or a
header mismatch fail before evidence can be accepted.

The neutral proof ladder performs clean deterministic packing and isolated
consumption, compiles the JVM oracle against the pinned project input, runs the
probe only inside the package consumer, compares normalized observations,
deliberately perturbs the oracle to prove mismatch detection, validates the
package against the authoritative baseline plus generation evidence, and emits
one summary. A bounded target extension may prepare unusual fixtures or add
non-core summary evidence, but it cannot replace runner-owned proof fields.
Differentials whose evidence model is genuinely different may keep a
target-specific runner while reusing the neutral comparison primitives.
