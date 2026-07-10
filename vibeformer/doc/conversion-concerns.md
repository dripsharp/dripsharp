# Conversion Concerns

## Resolved-Symbol Mappings

Mappings are explicit, deterministic, and selected from resolved frontend
symbols. Required registries include:

* Types and generic type constructors.
* Methods and overload families.
* Constructors.
* Fields, properties, and enum constants.
* Language semantics such as initialization, locking, and resource lifetime.
* Dependencies, projects, packages, and resources.

Project-local declarations normally map to recursively translated C#
declarations. Java/JDK symbols normally map to equivalent .NET types and APIs.
Compatibility helpers are reserved for semantic mismatches without a faithful
ordinary .NET representation.

## Common Type Mappings

Examples include:

```text
java.lang.String       -> string
java.math.BigInteger   -> System.Numerics.BigInteger
java.util.List<T>      -> List<T> or an appropriate interface
java.util.Map<K,V>     -> Dictionary<K,V> or an appropriate interface
java.net.URI           -> System.Uri
```

The exact destination type depends on source semantics, mutability, public API
shape, and resolved usage. A name-only substitution table is not sufficient.

## Common Member Mappings

Resolved members map by declaring type, method or field identity, parameter
types, and generic shape. Examples include:

```text
java.util.List.size()       -> Count
java.util.List.get(int)     -> indexer access
java.util.Objects.equals    -> object.Equals
java.util.Objects.hash      -> System.HashCode
```

Overload resolution belongs to the Java frontend. The translator should not
guess an overload from argument text.

## Java Semantic Differences

Areas requiring deliberate translation include:

* Static and instance initialization order.
* Package-private visibility.
* Checked exceptions.
* Try-with-resources and suppressed exceptions.
* Inner and anonymous classes.
* Wildcard generics, raw types, and variance.
* Java enum classes with fields and methods.
* Reflection and generic type metadata.
* `synchronized`, `wait`, and `notify` semantics.
* Collection views, equality, hashing, and iteration order.
* Java streams and collectors.
* Annotation-processor or generated-source behavior.

These are localized semantic translation problems. They do not justify a
parallel Java runtime for ordinary constructs that .NET already supports.

## Kotlin Semantic Differences

Kotlin additionally requires explicit handling for nullability, smart casts,
extension members, default and named arguments, data/sealed classes, objects and
companions, top-level declarations, delegated properties, function types,
coroutines, and collection mutability.

## Dependency Decisions

Each source dependency should become one of:

* A translated project reference.
* An existing .NET package or framework API.
* A small reusable compatibility component.
* A destination-specific runtime component.
* A user-approved product exclusion.
* A blocking unsupported dependency.

The absence of a direct package replacement does not make source behavior out
of scope.
