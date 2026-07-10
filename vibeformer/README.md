# Vibeformer

Vibeformer is a Clojure-based Java/Kotlin-to-C# source translator. Its first
product use is producing a complete, independently usable .NET implementation
of the in-scope Pkl library behavior, but the translator architecture is meant
to remain useful for other Java and Kotlin projects.

The central Java path is deliberately direct:

```text
resolved project and classpath
  -> Spoon typed semantic AST
  -> recursive translation using resolved symbols
  -> ordinary, disposable C# source
  -> compile
  -> independent behavior comparison
```

Kotlin follows the same contract using Kotlin's semantic frontend. Translation
rules map ordinary JVM library types and methods to their .NET equivalents.
Compatibility helpers belong in the translator only when .NET lacks the
required JVM facility. Pkl evaluation or language behavior is product code,
not a generic translator runtime.

Generated C# is disposable. Compilation or behavior failures must be fixed in
the frontend configuration, recursive translation, symbol mappings, or a
clearly owned compatibility/product implementation, then regenerated without
manual patches.

## Documentation

The documentation is limited to durable product and architecture decisions:

* [Product Goal](doc/product-goal.md) defines completion and protects the fixed
  product scope.
* [Port Scope](doc/port-scope.md) records the user-approved .NET product surface
  and exclusions.
* [Architecture](doc/architecture.md) defines the end-to-end system and
  ownership boundaries.
* [Technology](doc/technology.md) records the frontend and validation choices.
* [Transform Pipeline](doc/transform-pipeline.md) defines recursive translation
  and mapping contracts.
* [Conversion Concerns](doc/conversion-concerns.md) records semantic mismatches
  that require explicit handling.
* [Implementation Plan](doc/implementation-plan.md) records only durable
  dependency order; current milestones and follow-up work belong in Beads.

## Development Rule

Use complete projects with their real generated sources and classpaths. A
passing hand-selected source slice or sample fixture is useful evidence for a
local rule but is not evidence that a module or the product is complete.
