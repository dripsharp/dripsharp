# Conversion Concerns

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

Current implemented type mapping covers the committed sample subset: Java and
Kotlin scalar types, common collection/map types, `Map.Entry`/`KeyValuePair`,
arrays, Java `Optional` helper types, selected Java reflection/io/path/regex
types, Kotlin function types, known Java exceptions, nullable annotations, and
project-local declarations. Unknown type mappings are allowed to keep output
inspectable only when they also emit structured C# diagnostics.

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

Currently implemented Java sample coverage includes package/class/interface/
record/enum/annotation shapes, fields, constructors, methods, locals,
assignment, return, if, throw, foreach, try/catch/finally, synchronized methods
and blocks, switch expressions, selected stream operations, selected
collection/map APIs, selected reflection inspection APIs, nullable annotations,
Java exceptions, object creation for project-local/known runtime types, and
runtime helpers for Java optionals.

Still high-risk or incomplete for full Pkl:

* Full dependency-backed overload/type/member resolution.
* Try-with-resources and resource lifetime translation.
* Anonymous/inner classes and broader inheritance edge cases.
* Reflection invocation and dynamic member lookup.
* Raw/wildcard generics beyond the currently modeled sample subset.
* Java concurrency primitives beyond synchronized locking.
* Broad framework-specific APIs surfaced by `../research/pkl`.

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

Currently implemented Kotlin sample coverage includes PSI extraction, file
facades for top-level declarations, object/interface declarations, properties,
simple functions, nullability, safe calls, Elvis expressions, selected Java API
calls, and conservative local reference enrichment. Kotlin emission remains
sample-scoped and is not yet a full Kotlin-to-C# path.

Still high-risk or incomplete for full Pkl:

* Analysis API-backed symbol/type resolution and overload disambiguation.
* Extension functions/properties, receivers, and smart casts.
* Data/sealed classes and compiler-generated member semantics.
* Coroutines, suspend functions, flows, and channels.
* Scope functions and DSL builder patterns.
* Kotlin collection mutability/read-only semantics across Java interop.
