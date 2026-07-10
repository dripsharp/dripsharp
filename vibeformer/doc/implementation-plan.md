# Durable Implementation Order

Temporary work breakdown, status, and follow-ups belong in Beads. This document
records only the durable dependency order for implementation.

## Translator Spine

1. Resolve projects, source sets, generated sources, dependencies, and the real
   language classpath.
2. Build typed semantic frontend models with resolved types and symbols.
3. Recursively translate declarations, statements, and expressions.
4. Apply explicit resolved-symbol mappings for types, methods, constructors,
   fields, language semantics, and dependencies.
5. Emit complete C# projects with source mappings and explicit diagnostics.
6. Compile from scratch with `dotnet build`.
7. Compare generated-package behavior against independent upstream evidence.
8. Add focused compatibility or destination-runtime code only where existing
   .NET facilities cannot preserve required behavior.

Optional inventory, provenance, and diagnostic persistence may be added around
this spine. It must not become a prerequisite for translating the typed AST.

## First Proof: Complete Pkl Parser

The first architectural proof is the complete `pkl-parser` Java module, not a
hand-selected declaration slice.

Acceptance requires:

* The real module classpath and generated inputs are resolved.
* All reachable Java declarations and bodies translate through Spoon's typed
  model.
* The complete generated C# parser project builds without manual edits.
* Unsupported or unresolved constructs fail explicitly.
* Parser behavior is compared independently with upstream Pkl tests and
  fixtures.
* No Pkl-specific source parser or qualified-name override is added to the
  reusable Java translator.

Completing this proof does not complete or narrow the Pkl product goal.

## Subsequent Product Work

After the translator architecture is proven on `pkl-parser`, use the same path
for ordinary Pkl Java code such as value types, public APIs, utilities, and
configuration binding.

Build the Pkl evaluator/runtime behind an explicit destination-runtime boundary:

* Reuse the translated parser AST.
* Translate ordinary Java behavior normally.
* Map standard-library/platform behavior to .NET.
* Implement only the missing Truffle/JVM/Pkl execution semantics as durable C#.
* Validate through independent upstream behavior evidence.

## Testing Layers

1. Recursive structural-transform tests.
2. Resolved type/member/constructor/field mapping tests.
3. Complete generated-project compilation tests.
4. Compiler-diagnostic regression tests.
5. Independent upstream-versus-generated-package behavior tests.

Tests should exercise public output, not implementation-generated claims about
coverage or completion.
