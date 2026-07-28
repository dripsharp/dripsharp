# Declarative Resolved-Symbol Mapping Registry

DripSharp's product-neutral Java-to-C# layer represents reusable type and
member adaptations as validated data. Pkl and PdfCube may contribute entries,
but neither product owns the registry schema or interpreter.

The executable contract is
[`dripsharp.java-mapping-registry`](../src/dripsharp/java_mapping_registry.clj).
This document records the durable schema and strategy meanings.

## Entry Schema

Registry construction accepts a sequence, rather than a map, so duplicate
resolved-key ownership remains observable and fails validation instead of being
silently overwritten.

Every entry contains:

```clojure
{:id :java.util/list-size
 :key "executable:java.util.List#size()"
 :strategy :property-access
 :destination "Count"
 :caveats #{}
 :introduced-by :pdfcube-io
 :evidence #{:differential/io}
 :required-usings #{}
 :required-helpers #{}}
```

`:id` is a stable qualified keyword. `:key` uses the exact resolved Spoon
identity grammar for types, formal type parameters, executables, constructors,
or fields. `:caveats` is always present, even when empty. `:introduced-by`
records the target that first required the mapping. `:evidence` is always
present; a mapping with any semantic caveat must cite at least one evidence
reference. Helper and using requirements are optional sets that normalize to
empty sets.

An optional `:kind` may repeat the key-derived kind, but cannot override it.
Constructor entries use `executable:...#<init>(...)` keys and normalize to the
`:constructor` kind.

## Strategies

The interpreter supports these closed strategies:

| Strategy | Meaning |
| --- | --- |
| `:rename` | Rename a type, field, constructor, or executable while preserving its ordinary destination shape. |
| `:property-access` | Emit a static or targeted C# property/member access with no invocation arguments. |
| `:compat-call` | Call a named compatibility function, passing an instance target first when one exists, followed by source arguments. |
| `:argument-reshape` | Select, reorder, splice, or add literal arguments and emit a declared static, member, or constructor call shape. |
| `:template` | Compose a bounded vector of raw fragments and validated target/argument selectors into a structured node. |
| `:custom-handler` | Delegate an irregular case to a qualified handler identity registered explicitly when the registry is compiled. |

Argument selectors are `:target`, `:arguments`, `:type-arguments`,
`[:argument n]`, and `[:literal "text"]`. Templates use the same selectors and
may also contain literal strings. Unsupported selectors, out-of-range
arguments, missing targets, and invalid custom-handler results fail closed.

## Ownership and Failure Contract

A compiled registry has exactly one owner for each resolved key and exactly one
meaning for each mapping identity. Malformed keys, duplicate keys, reused
identities, unknown entry fields, strategy fields that contradict one another,
unsupported strategies or call shapes, missing metadata, unevidenced caveats,
and unregistered handlers all reject registry construction.

Interpretation uses exact-key lookup. An unmapped resolved identity produces a
blocking exception; it never falls back to simple names, source text, or guessed
C# output. Every successful fragment carries the mapping identity, resolved
key, strategy, caveats, introducing target, and evidence references for later
generation reporting.
