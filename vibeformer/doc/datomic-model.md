# Datomic Model

## Files

Example:

```clojure
{:file/path "src/main/java/com/acme/Foo.java"
 :file/lang :lang/java
 :file/hash "sha256..."
 :file/project [:project/id "my-project"]
 :file/package "com.acme"}
```

For Kotlin:

```clojure
{:file/path "src/main/kotlin/com/acme/Foo.kt"
 :file/lang :lang/kotlin
 :file/hash "sha256..."
 :file/project [:project/id "my-project"]
 :file/package "com.acme"}
```

## Source Nodes

Use normalized source nodes for AST-ish structure.

```clojure
{:node/id "my-project:src/main/java/com/acme/Foo.java:method:com.acme.Foo.bar"
 :node/lang :lang/java
 :node/kind :java.node/method
 :node/name "bar"
 :node/file [:file/path "src/main/java/com/acme/Foo.java"]
 :node/parent [:node/id "..."]
 :node/ordinal 3
 :node/start-line 44
 :node/start-column 5
 :node/end-line 71
 :node/end-column 6
 :node/source-hash "..."}
```

Do not rely only on line numbers for identity. Use stable IDs based on:

* Project ID
* File path
* Language
* Node kind
* Qualified name when available
* Parent/ordinal path
* Source span
* Source text hash

## Declarations

Separate syntax nodes from semantic declarations.

```clojure
{:decl/id "java:com.acme.Foo#bar(java.lang.String)"
 :decl/lang :lang/java
 :decl/kind :decl.kind/method
 :decl/name "bar"
 :decl/qualified-name "com.acme.Foo.bar"
 :decl/source-node [:node/id "..."]
 :decl/return-type [:type/id "java.lang.String"]
 :decl/modifiers #{:public :static}}
```

Kotlin example:

```clojure
{:decl/id "kt:com.acme.foo"
 :decl/lang :lang/kotlin
 :decl/kind :decl.kind/function
 :decl/name "foo"
 :decl/qualified-name "com.acme.foo"
 :decl/source-node [:node/id "..."]
 :decl/return-type [:type/id "kotlin.String?"]}
```

## Types

Represent source-language types explicitly.

```clojure
{:type/id "java.util.List<com.acme.Customer>"
 :type/lang :lang/java
 :type/name "java.util.List"
 :type/args [[:type/id "com.acme.Customer"]]
 :type/nullable? false}
```

Kotlin nullability should be first-class:

```clojure
{:type/id "kotlin.String?"
 :type/lang :lang/kotlin
 :type/name "kotlin.String"
 :type/nullable? true}
```

## Symbol and Type References

Track references from use sites to declarations where possible.

```clojure
{:ref/id "..."
 :ref/kind :ref.kind/method-call
 :ref/from-node [:node/id "..."]
 :ref/to-decl [:decl/id "java.util.List#add(E)"]
 :ref/name "add"
 :ref/owner-type [:type/id "java.util.List<T>"]
 :ref/resolved? true}
```

For unresolved refs:

```clojure
{:ref/id "..."
 :ref/kind :ref.kind/method-call
 :ref/from-node [:node/id "..."]
 :ref/name "foo"
 :ref/resolved? false
 :ref/reason :resolve.reason/missing-classpath}
```

Unresolved references should not be ignored. They are important risk signals.

## Feature Inventory

Every conversion-relevant construct should be represented as a feature fact.

Example:

```clojure
{:feature/id "..."
 :feature/lang :lang/java
 :feature/kind :java.feature/anonymous-class
 :feature/node [:node/id "..."]
 :feature/status :feature.status/unsupported
 :feature/severity :feature.severity/hard}
```

Java feature examples:

```clojure
:java.feature/class
:java.feature/interface
:java.feature/enum
:java.feature/annotation
:java.feature/generic-method
:java.feature/wildcard-generic
:java.feature/raw-type
:java.feature/anonymous-class
:java.feature/inner-class
:java.feature/static-import
:java.feature/package-private-member
:java.feature/checked-exception
:java.feature/try-with-resources
:java.feature/synchronized-method
:java.feature/synchronized-block
:java.feature/lambda
:java.feature/stream-api
:java.feature/reflection
:java.feature/native-method
```

Kotlin feature examples:

```clojure
:kt.feature/nullability
:kt.feature/safe-call
:kt.feature/elvis
:kt.feature/data-class
:kt.feature/sealed-class
:kt.feature/object-declaration
:kt.feature/companion-object
:kt.feature/top-level-function
:kt.feature/extension-function
:kt.feature/extension-property
:kt.feature/default-argument
:kt.feature/named-argument
:kt.feature/destructuring
:kt.feature/delegated-property
:kt.feature/smart-cast
:kt.feature/lambda
:kt.feature/inline-function
:kt.feature/reified-type-parameter
:kt.feature/suspend-function
:kt.feature/coroutine
```

Feature inventory is used to decide what to implement next.

## Example Datomic Queries

Count all features:

```clojure
(d/q '[:find ?kind (count ?f)
       :where
       [?f :feature/kind ?kind]]
     db)
```

Find unsupported features:

```clojure
(d/q '[:find ?kind (count ?f)
       :where
       [?f :feature/status :feature.status/unsupported]
       [?f :feature/kind ?kind]]
     db)
```

Find unsupported features by file:

```clojure
(d/q '[:find ?path ?kind (count ?f)
       :where
       [?f :feature/status :feature.status/unsupported]
       [?f :feature/kind ?kind]
       [?f :feature/node ?n]
       [?n :node/file ?file]
       [?file :file/path ?path]]
     db)
```

Find highest-value transform rules to implement:

```clojure
(d/q '[:find ?kind (count ?f) (count-distinct ?file)
       :where
       [?f :feature/status :feature.status/unsupported]
       [?f :feature/kind ?kind]
       [?f :feature/node ?node]
       [?node :node/file ?file]]
     db)
```

Find files with no unsupported features:

```clojure
(d/q '[:find ?path
       :where
       [?file :file/path ?path]
       (not-join [?file]
         [?node :node/file ?file]
         [?feature :feature/node ?node]
         [?feature :feature/status :feature.status/unsupported])]
     db)
```

Find which transform rules produce compiler errors:

```clojure
(d/q '[:find ?rule ?code (count ?d)
       :where
       [?d :diagnostic/rule ?rule]
       [?d :diagnostic/code ?code]]
     db)
```

Find which source features are associated with compiler errors:

```clojure
(d/q '[:find ?feature (count ?d)
       :where
       [?d :diagnostic/source-node ?n]
       [?f :feature/node ?n]
       [?f :feature/kind ?feature]]
     db)
```
