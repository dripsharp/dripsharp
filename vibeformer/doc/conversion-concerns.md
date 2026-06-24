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
