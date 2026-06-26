(ns vibeformer.transform.rules
  (:require [datomic.client.api :as d]))

(def implemented-status :rule.status/implemented)
(def stubbed-status :rule.status/stubbed)
(def unsupported-status :rule.status/unsupported)

(def initial-java-rules
  "Initial Java rule catalog for sample coverage checks.

  These entries deliberately mark not-yet-emitted Java constructs as stubbed
  and known source incompatibilities as unsupported so coverage reports point
  at an explicit rule instead of a missing catalog entry."
  [{:rule/id :java.class-node/to-csharp-class
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/class
    :rule/status implemented-status}
   {:rule/id :java.interface-node/to-csharp-interface
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/interface
    :rule/status implemented-status}
   {:rule/id :java.enum-node/to-csharp-enum
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/enum
    :rule/status implemented-status}
   {:rule/id :java.annotation-node/to-csharp-attribute
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/annotation
    :rule/status implemented-status}
   {:rule/id :java.record-node/to-csharp-record
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/record
    :rule/status implemented-status}
   {:rule/id :java.record-component-node/to-csharp-parameter
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/record-component
    :rule/status implemented-status}
   {:rule/id :java.assignment-node/to-csharp-assignment
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/assignment
    :rule/status implemented-status}
   {:rule/id :java.constructor-node/to-csharp-constructor
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/constructor
    :rule/status implemented-status}
   {:rule/id :java.field-node/to-csharp-field
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/field
    :rule/status implemented-status}
   {:rule/id :java.method-node/to-csharp-method
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/method
    :rule/status implemented-status}
   {:rule/id :java.method-call-node/to-csharp-invocation
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/method-call
    :rule/status implemented-status}
   {:rule/id :java.method-reference-node/to-csharp-method-reference
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/method-reference
    :rule/status implemented-status}
   {:rule/id :java.object-creation-node/to-csharp-new
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/object-creation
    :rule/status implemented-status}
   {:rule/id :java.local-variable-node/to-csharp-local
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/local-variable
    :rule/status implemented-status}
   {:rule/id :java.return-statement-node/to-csharp-return
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/return-statement
    :rule/status implemented-status}
   {:rule/id :java.if-statement-node/to-csharp-if
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/if-statement
    :rule/status implemented-status}
   {:rule/id :java.literal-node/to-csharp-literal
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/literal
    :rule/status implemented-status}
   {:rule/id :java.variable-read-node/to-csharp-variable
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/variable-read
    :rule/status implemented-status}
   {:rule/id :java.field-read-node/to-csharp-member
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/field-read
    :rule/status implemented-status}
   {:rule/id :java.field-write-node/to-csharp-member
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/field-write
    :rule/status implemented-status}
   {:rule/id :java.this-node/to-csharp-this
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/this
    :rule/status implemented-status}
   {:rule/id :java.throw-statement-node/to-csharp-throw
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/throw-statement
    :rule/status implemented-status}
   {:rule/id :java.type-pattern-node/to-csharp-pattern
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/type-pattern
    :rule/status implemented-status}
   {:rule/id :java.type-access-node/to-csharp-type
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/type-access
    :rule/status implemented-status}
   {:rule/id :java.type-cast-node/to-csharp-cast
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/type-cast
    :rule/status implemented-status}
   {:rule/id :java.variable-write-node/to-csharp-variable
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/variable-write
    :rule/status implemented-status}
   {:rule/id :java.array-read-node/to-csharp-indexer
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/array-read
    :rule/status implemented-status}
   {:rule/id :java.binary-operator-node/to-csharp-binary
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/binary-operator
    :rule/status implemented-status}
   {:rule/id :java.conditional-expression-node/to-csharp-conditional
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/conditional-expression
    :rule/status implemented-status}
   {:rule/id :java.lambda-node/to-csharp-lambda
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/lambda
    :rule/status implemented-status}
   {:rule/id :java.unary-operator-node/to-csharp-unary
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/unary-operator
    :rule/status implemented-status}
   {:rule/id :java.switch-expression-node/to-csharp-switch
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/switch-expression
    :rule/status implemented-status}
   {:rule/id :java.switch-case-node/to-csharp-switch-arm
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/switch-case
    :rule/status implemented-status}
   {:rule/id :java.synchronized-block-node/to-csharp-lock
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/synchronized-block
    :rule/output-feature :csharp.feature/lock
    :rule/status implemented-status}
   {:rule/id :java.regex-pattern-compile/to-csharp-regex
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/pattern-compile
    :rule/output-feature :csharp.api/regex
    :rule/status implemented-status}
   {:rule/id :java.string-trim/to-csharp-trim
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/string-trim
    :rule/output-feature :csharp.api/string-trim
    :rule/status implemented-status}
   {:rule/id :java.string-is-empty/to-csharp-is-null-or-empty
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/string-is-empty
    :rule/output-feature :csharp.api/string-is-null-or-empty
    :rule/status implemented-status}
   {:rule/id :java.string-length/to-csharp-length
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/string-length
    :rule/output-feature :csharp.api/string-length
    :rule/status implemented-status}
   {:rule/id :java.string-code-points/to-csharp-rune-values
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/string-code-points
    :rule/output-feature :csharp.api/string-runes
    :rule/status implemented-status}
   {:rule/id :java.regex-split/to-csharp-regex-split
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/pattern-split
    :rule/output-feature :csharp.api/regex-split
    :rule/status implemented-status}
   {:rule/id :java.printstream-println/to-csharp-console
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/printstream-println
    :rule/output-feature :csharp.api/console-write-line
    :rule/status implemented-status}
   {:rule/id :java.system-exit/to-csharp-environment-exit
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/system-exit
    :rule/output-feature :csharp.api/environment-exit
    :rule/status implemented-status}
   {:rule/id :java.path-of/to-csharp-string-path
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/path-of
    :rule/output-feature :csharp.api/string-path
    :rule/status implemented-status}
   {:rule/id :java.files-read-string/to-csharp-file-read-all-text
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/files-read-string
    :rule/output-feature :csharp.api/file-read-all-text
    :rule/status implemented-status}
   {:rule/id :java.integer-to-string/to-csharp-convert-to-string
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/integer-to-string
    :rule/output-feature :csharp.api/convert-to-string
    :rule/status implemented-status}
   {:rule/id :java.objects-require-non-null/to-csharp-null-check
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/objects-require-non-null
    :rule/output-feature :csharp.api/null-coalescing-throw
    :rule/status implemented-status}
   {:rule/id :java.objects-equals/to-csharp-object-equals
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/objects-equals
    :rule/output-feature :csharp.api/object-equals
    :rule/status implemented-status}
   {:rule/id :java.objects-hash/to-csharp-hash-code-combine
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/objects-hash
    :rule/output-feature :csharp.api/hash-code-combine
    :rule/status implemented-status}
   {:rule/id :java.math-round/to-csharp-java-round
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/math-round
    :rule/output-feature :csharp.api/math-floor-cast
    :rule/status implemented-status}
   {:rule/id :java.math-min/to-csharp-math-min
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/math-min
    :rule/output-feature :csharp.api/math-min
    :rule/status implemented-status}
   {:rule/id :java.math-max/to-csharp-math-max
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/math-max
    :rule/output-feature :csharp.api/math-max
    :rule/status implemented-status}
   {:rule/id :java.double-hash-code/to-csharp-get-hash-code
    :rule/source-lang :lang/java
    :rule/input-feature :java.api/double-hash-code
    :rule/output-feature :csharp.api/get-hash-code
    :rule/status implemented-status}
   {:rule/id :java.class-type-literal/to-csharp-typeof
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/type-literal
    :rule/output-feature :csharp.api/typeof
    :rule/status implemented-status}
   {:rule/id :java.class-get-type-name/to-csharp-full-name
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/get-type-name
    :rule/output-feature :csharp.api/type-full-name
    :rule/status implemented-status}
   {:rule/id :java.class-get-name/to-csharp-full-name
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/get-name
    :rule/output-feature :csharp.api/type-full-name
    :rule/status implemented-status}
   {:rule/id :java.class-get-simple-name/to-csharp-name
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/get-simple-name
    :rule/output-feature :csharp.api/type-name
    :rule/status implemented-status}
   {:rule/id :java.class-get-modifiers/to-csharp-attributes
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/get-modifiers
    :rule/output-feature :csharp.api/type-attributes
    :rule/status implemented-status}
   {:rule/id :java.class-is-assignable-from/to-csharp-is-assignable-from
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/is-assignable-from
    :rule/output-feature :csharp.api/type-is-assignable-from
    :rule/status implemented-status}
   {:rule/id :java.class-is-array/to-csharp-is-array
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/is-array
    :rule/output-feature :csharp.api/type-is-array
    :rule/status implemented-status}
   {:rule/id :java.class-is-primitive/to-csharp-is-primitive
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/is-primitive
    :rule/output-feature :csharp.api/type-is-primitive
    :rule/status implemented-status}
   {:rule/id :java.class-get-generic-superclass/to-csharp-base-type
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/get-generic-superclass
    :rule/output-feature :csharp.api/type-base-type
    :rule/status implemented-status}
   {:rule/id :java.class-get-type-parameters/to-csharp-generic-arguments
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/get-type-parameters
    :rule/output-feature :csharp.api/type-generic-arguments
    :rule/status implemented-status}
   {:rule/id :java.class-get-component-type/to-csharp-element-type
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/get-component-type
    :rule/output-feature :csharp.api/type-element-type
    :rule/status implemented-status}
   {:rule/id :java.class-is-enum/to-csharp-is-enum
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/is-enum
    :rule/output-feature :csharp.api/type-is-enum
    :rule/status implemented-status}
   {:rule/id :java.class-get-class-loader/to-csharp-assembly
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/get-class-loader
    :rule/output-feature :csharp.api/type-assembly
    :rule/status implemented-status}
   {:rule/id :java.class-cast/to-csharp-cast
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/cast
    :rule/output-feature :csharp.feature/cast
    :rule/status implemented-status}
   {:rule/id :java.class-get-resource-as-stream/to-csharp-manifest-resource-stream
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/get-resource-as-stream
    :rule/output-feature :csharp.api/manifest-resource-stream
    :rule/status implemented-status}
   {:rule/id :java.type-get-type-name/to-csharp-full-name
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.type/get-type-name
    :rule/output-feature :csharp.api/type-full-name
    :rule/status implemented-status}
   {:rule/id :java.parameterized-type-get-actual-type-arguments/to-csharp-generic-arguments
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.parameterized-type/get-actual-type-arguments
    :rule/output-feature :csharp.api/type-generic-arguments
    :rule/status implemented-status}
   {:rule/id :java.parameterized-type-get-raw-type/to-csharp-type
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.parameterized-type/get-raw-type
    :rule/output-feature :csharp.api/type
    :rule/status implemented-status}
   {:rule/id :java.parameterized-type-get-owner-type/to-csharp-declaring-type
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.parameterized-type/get-owner-type
    :rule/output-feature :csharp.api/type-declaring-type
    :rule/status implemented-status}
   {:rule/id :java.reflection-executable-get-parameters/to-csharp-get-parameters
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.executable/get-parameters
    :rule/output-feature :csharp.api/constructor-parameters
    :rule/status implemented-status}
   {:rule/id :java.reflection-parameter-is-name-present/to-csharp-name-check
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.parameter/is-name-present
    :rule/output-feature :csharp.api/parameter-name
    :rule/status implemented-status}
   {:rule/id :java.reflection-parameter-get-name/to-csharp-name
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.parameter/get-name
    :rule/output-feature :csharp.api/parameter-name
    :rule/status implemented-status}
   {:rule/id :java.modifier-is-abstract/to-csharp-type-attributes
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.modifier/is-abstract
    :rule/output-feature :csharp.api/type-attributes
    :rule/status implemented-status}
   {:rule/id :java.reflection/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/reflection
    :rule/status unsupported-status}
   {:rule/id :java.class-for-name/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/for-name
    :rule/status unsupported-status}
   {:rule/id :java.class-get-method/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/get-method
    :rule/status unsupported-status}
   {:rule/id :java.class-get-declared-method/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/get-declared-method
    :rule/status unsupported-status}
   {:rule/id :java.class-get-declared-methods/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/get-declared-methods
    :rule/status unsupported-status}
   {:rule/id :java.class-get-declared-constructors/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/get-declared-constructors
    :rule/status unsupported-status}
   {:rule/id :java.class-get-annotation/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.class/get-annotation
    :rule/status unsupported-status}
   {:rule/id :java.reflection-method-invoke/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.method/invoke
    :rule/status unsupported-status}
   {:rule/id :java.reflection-constructor-new-instance/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.constructor/new-instance
    :rule/status unsupported-status}
   {:rule/id :java.reflection-constructor-get-annotation/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.constructor/get-annotation
    :rule/status unsupported-status}
   {:rule/id :java.reflection-parameter-get-annotation/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.parameter/get-annotation
    :rule/status unsupported-status}
   {:rule/id :java.reflection-wildcard-type-get-lower-bounds/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.wildcard-type/get-lower-bounds
    :rule/status unsupported-status}
   {:rule/id :java.reflection-wildcard-type-get-upper-bounds/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.reflection.wildcard-type/get-upper-bounds
    :rule/status unsupported-status}
   {:rule/id :java.stream-source/to-csharp-enumerable
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/source-to-enumerable
    :rule/output-feature :csharp.api/linq
    :rule/status implemented-status}
   {:rule/id :java.stream-map/to-csharp-select
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/map
    :rule/output-feature :csharp.api/linq-select
    :rule/status implemented-status}
   {:rule/id :java.stream-filter/to-csharp-where
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/filter
    :rule/output-feature :csharp.api/linq-where
    :rule/status implemented-status}
   {:rule/id :java.stream-flat-map/to-csharp-select-many
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/flat-map
    :rule/output-feature :csharp.api/linq-select-many
    :rule/status implemented-status}
   {:rule/id :java.stream-map-to-int/to-csharp-select
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/map-to-int
    :rule/output-feature :csharp.api/linq-select
    :rule/status implemented-status}
   {:rule/id :java.stream-map-to-long/to-csharp-select
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/map-to-long
    :rule/output-feature :csharp.api/linq-select
    :rule/status implemented-status}
   {:rule/id :java.stream-to-list/to-csharp-to-list
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/to-list
    :rule/output-feature :csharp.api/linq-to-list
    :rule/status implemented-status}
   {:rule/id :java.stream-to-array/to-csharp-to-array
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/to-array
    :rule/output-feature :csharp.api/linq-to-array
    :rule/status implemented-status}
   {:rule/id :java.stream-count/to-csharp-count
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/count
    :rule/output-feature :csharp.api/linq-count
    :rule/status implemented-status}
   {:rule/id :java.stream-sum/to-csharp-sum
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/sum
    :rule/output-feature :csharp.api/linq-sum
    :rule/status implemented-status}
   {:rule/id :java.stream-max/to-csharp-max
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/max
    :rule/output-feature :csharp.api/linq-max
    :rule/status implemented-status}
   {:rule/id :java.stream-any-match/to-csharp-any
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/any-match
    :rule/output-feature :csharp.api/linq-any
    :rule/status implemented-status}
   {:rule/id :java.stream-all-match/to-csharp-all
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/all-match
    :rule/output-feature :csharp.api/linq-all
    :rule/status implemented-status}
   {:rule/id :java.stream-none-match/to-csharp-not-any
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/none-match
    :rule/output-feature :csharp.api/linq-any
    :rule/status implemented-status}
   {:rule/id :java.stream-find-first/to-csharp-first-or-default
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/find-first
    :rule/output-feature :csharp.api/linq-first-or-default
    :rule/status implemented-status}
   {:rule/id :java.stream-distinct/to-csharp-distinct
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/distinct
    :rule/output-feature :csharp.api/linq-distinct
    :rule/status implemented-status}
   {:rule/id :java.stream-sorted/to-csharp-order-by
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/sorted
    :rule/output-feature :csharp.api/linq-order-by
    :rule/status implemented-status}
   {:rule/id :java.stream-iterator/to-csharp-enumerator
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/iterator
    :rule/output-feature :csharp.api/enumerator
    :rule/status implemented-status}
   {:rule/id :java.iterator-has-next/to-csharp-move-next
    :rule/source-lang :lang/java
    :rule/input-feature :java.iterator/has-next
    :rule/output-feature :csharp.api/enumerator-move-next
    :rule/status implemented-status}
   {:rule/id :java.primitive-iterator-next-int/to-csharp-current
    :rule/source-lang :lang/java
    :rule/input-feature :java.primitive-iterator/next-int
    :rule/output-feature :csharp.api/enumerator-current
    :rule/status implemented-status}
   {:rule/id :java.stream-collector-to-list/to-csharp-to-list
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream.collector/to-list
    :rule/output-feature :csharp.api/linq-to-list
    :rule/status implemented-status}
   {:rule/id :java.stream-collector-to-set/to-csharp-to-hash-set
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream.collector/to-set
    :rule/output-feature :csharp.api/linq-to-hash-set
    :rule/status implemented-status}
   {:rule/id :java.stream-collector-joining/to-csharp-string-join
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream.collector/joining
    :rule/output-feature :csharp.api/string-join
    :rule/status implemented-status}
   {:rule/id :java.stream-collector-to-map/to-csharp-to-dictionary
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream.collector/to-map
    :rule/output-feature :csharp.api/linq-to-dictionary
    :rule/status implemented-status}
   {:rule/id :java.stream-collector-to-collection/to-csharp-collection-constructor
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream.collector/to-collection
    :rule/output-feature :csharp.api/collection-constructor
    :rule/status implemented-status}
   {:rule/id :java.stream-collect-to-list/to-csharp-to-list
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/collect-to-list
    :rule/output-feature :csharp.api/linq-to-list
    :rule/status implemented-status}
   {:rule/id :java.stream-collect-to-set/to-csharp-to-hash-set
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/collect-to-set
    :rule/output-feature :csharp.api/linq-to-hash-set
    :rule/status implemented-status}
   {:rule/id :java.stream-collect-joining/to-csharp-string-join
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/collect-joining
    :rule/output-feature :csharp.api/string-join
    :rule/status implemented-status}
   {:rule/id :java.stream-collect-to-map/to-csharp-to-dictionary
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/collect-to-map
    :rule/output-feature :csharp.api/linq-to-dictionary
    :rule/status implemented-status}
   {:rule/id :java.stream-collect-to-collection/to-csharp-collection-constructor
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/collect-to-collection
    :rule/output-feature :csharp.api/collection-constructor
    :rule/status implemented-status}
   {:rule/id :java.optional-or-else/to-csharp-default-if-empty-max
    :rule/source-lang :lang/java
    :rule/input-feature :java.optional/or-else
    :rule/output-feature :csharp.api/linq-default-if-empty-max
    :rule/status implemented-status}
   {:rule/id :java.stream-collect/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.stream/collect
    :rule/status unsupported-status}
   {:rule/id :java.stream-api/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/stream-api
    :rule/status unsupported-status}
   {:rule/id :java.lambda-feature/to-csharp-lambda
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/lambda
    :rule/output-feature :csharp.feature/lambda
    :rule/status implemented-status}
   {:rule/id :java.statement-node/to-csharp-stub
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/statement
    :rule/status stubbed-status}
   {:rule/id :java.expression-node/to-csharp-stub
    :rule/source-lang :lang/java
    :rule/input-kind :java.node/expression
    :rule/status stubbed-status}
   {:rule/id :java.class-feature/to-csharp-class
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/class
    :rule/output-feature :csharp.feature/class
    :rule/status implemented-status}
   {:rule/id :java.interface-feature/to-csharp-interface
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/interface
    :rule/output-feature :csharp.feature/interface
    :rule/status implemented-status}
   {:rule/id :java.enum-feature/to-csharp-enum
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/enum
    :rule/output-feature :csharp.feature/enum
    :rule/status implemented-status}
   {:rule/id :java.annotation-feature/to-csharp-attribute
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/annotation
    :rule/output-feature :csharp.feature/attribute
    :rule/status implemented-status}
   {:rule/id :java.record-feature/to-csharp-record
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/record
    :rule/output-feature :csharp.feature/record
    :rule/status implemented-status}
   {:rule/id :java.record-component-feature/to-csharp-parameter
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/record-component
    :rule/output-feature :csharp.feature/record-parameter
    :rule/status implemented-status}
   {:rule/id :java.generic-method-feature/to-csharp-generic-method
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/generic-method
    :rule/output-feature :csharp.feature/generic-method
    :rule/status implemented-status}
   {:rule/id :java.field-feature/to-csharp-field
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/field
    :rule/output-feature :csharp.feature/field
    :rule/status implemented-status}
   {:rule/id :java.package-private-member/to-csharp-internal
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/package-private-member
    :rule/output-feature :csharp.feature/internal-member
    :rule/status implemented-status}
   {:rule/id :java.checked-exception/to-csharp-unchecked-signature
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/checked-exception
    :rule/output-feature :csharp.feature/unchecked-exception-signature
    :rule/status implemented-status}
   {:rule/id :java.native-method/unsupported
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/native-method
    :rule/status unsupported-status}
   {:rule/id :java.synchronized-method/to-csharp-lock
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/synchronized-method
    :rule/output-feature :csharp.feature/lock
    :rule/status implemented-status}
   {:rule/id :java.synchronized-block/to-csharp-lock
    :rule/source-lang :lang/java
    :rule/input-feature :java.feature/synchronized-block
    :rule/output-feature :csharp.feature/lock
    :rule/status implemented-status}])

(def initial-kotlin-rules
  "Initial Kotlin rule catalog for sample coverage checks.

  Kotlin source is ingested into normalized facts before full Kotlin C# emission
  is implemented. Declaration-level constructs are emitted deterministically;
  expression/body constructs remain intentionally stubbed so coverage can
  distinguish facts-only samples from emitted samples."
  [{:rule/id :kotlin.package-node/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/package
    :rule/output-feature :csharp.feature/namespace
    :rule/status implemented-status}
   {:rule/id :kotlin.object-node/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/object
    :rule/output-feature :csharp.feature/static-class
    :rule/status implemented-status}
   {:rule/id :kotlin.class-node/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/class
    :rule/output-feature :csharp.feature/class
    :rule/status implemented-status}
   {:rule/id :kotlin.companion-object-node/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/companion-object
    :rule/status stubbed-status}
   {:rule/id :kotlin.property-node/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/property
    :rule/output-feature :csharp.feature/property
    :rule/status implemented-status}
   {:rule/id :kotlin.function-node/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/function
    :rule/output-feature :csharp.feature/method
    :rule/status implemented-status}
   {:rule/id :kotlin.declaration-node/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/declaration
    :rule/status stubbed-status}
   {:rule/id :kotlin.call-expression-node/to-csharp-expression
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/call-expression
    :rule/output-feature :csharp.feature/expression
    :rule/status implemented-status}
   {:rule/id :kotlin.call-receiver-node/to-csharp-expression
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/call-receiver
    :rule/output-feature :csharp.feature/expression
    :rule/status implemented-status}
   {:rule/id :kotlin.call-argument-node/to-csharp-expression
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/call-argument
    :rule/output-feature :csharp.feature/expression
    :rule/status implemented-status}
   {:rule/id :kotlin.safe-call-node/to-csharp-null-check
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/safe-call
    :rule/output-feature :csharp.feature/null-check
    :rule/status implemented-status}
   {:rule/id :kotlin.elvis-expression-node/to-csharp-coalesce
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/elvis-expression
    :rule/output-feature :csharp.feature/null-coalescing
    :rule/status implemented-status}
   {:rule/id :kotlin.local-property-node/to-csharp-local
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/local-property
    :rule/output-feature :csharp.feature/local
    :rule/status implemented-status}
   {:rule/id :kotlin.return-node/to-csharp-return
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/return
    :rule/output-feature :csharp.feature/return
    :rule/status implemented-status}
   {:rule/id :kotlin.throw-node/to-csharp-throw
    :rule/source-lang :lang/kotlin
    :rule/input-kind :kotlin.node/throw
    :rule/output-feature :csharp.feature/throw
    :rule/status implemented-status}
   {:rule/id :kotlin.package-feature/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-feature :kotlin.feature/package
    :rule/output-feature :csharp.feature/namespace
    :rule/status implemented-status}
   {:rule/id :kotlin.object-feature/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-feature :kotlin.feature/object
    :rule/output-feature :csharp.feature/static-class
    :rule/status implemented-status}
   {:rule/id :kotlin.class-feature/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-feature :kotlin.feature/class
    :rule/output-feature :csharp.feature/class
    :rule/status implemented-status}
   {:rule/id :kotlin.companion-object-feature/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-feature :kotlin.feature/companion-object
    :rule/status stubbed-status}
   {:rule/id :kotlin.property-feature/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-feature :kotlin.feature/property
    :rule/output-feature :csharp.feature/property
    :rule/status implemented-status}
   {:rule/id :kotlin.function-feature/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-feature :kotlin.feature/function
    :rule/output-feature :csharp.feature/method
    :rule/status implemented-status}
   {:rule/id :kotlin.declaration-feature/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-feature :kotlin.feature/declaration
    :rule/status stubbed-status}
   {:rule/id :kotlin.top-level-declaration-feature/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-feature :kotlin.feature/top-level-declaration
    :rule/output-feature :csharp.feature/member
    :rule/status implemented-status}
   {:rule/id :kotlin.nullable-type-feature/to-csharp-stub
    :rule/source-lang :lang/kotlin
    :rule/input-feature :kotlin.feature/nullable-type
    :rule/output-feature :csharp.feature/nullable-type
    :rule/status implemented-status}
   {:rule/id :kotlin.call-expression-feature/to-csharp-expression
    :rule/source-lang :lang/kotlin
    :rule/input-feature :kotlin.feature/call-expression
    :rule/output-feature :csharp.feature/expression
    :rule/status implemented-status}
   {:rule/id :kotlin.safe-call-feature/to-csharp-null-check
    :rule/source-lang :lang/kotlin
    :rule/input-feature :kotlin.feature/safe-call
    :rule/output-feature :csharp.feature/null-check
    :rule/status implemented-status}
   {:rule/id :kotlin.elvis-expression-feature/to-csharp-coalesce
    :rule/source-lang :lang/kotlin
    :rule/input-feature :kotlin.feature/elvis-expression
    :rule/output-feature :csharp.feature/null-coalescing
    :rule/status implemented-status}])

(defn- require-key [rule k]
  (when-not (contains? rule k)
    (throw (ex-info (str "Transform rule is missing " k ".")
                    {:rule rule
                     :missing-key k}))))

(defn normalize-rule
  "Validates a transform rule map and fills deterministic defaults."
  [rule]
  (doseq [k [:rule/id :rule/source-lang :rule/status]]
    (require-key rule k))
  (let [has-node-kind? (contains? rule :rule/input-kind)
        has-feature? (contains? rule :rule/input-feature)]
    (when (= has-node-kind? has-feature?)
      (throw (ex-info "Transform rule must declare exactly one input selector."
                      {:rule rule
                       :selectors [:rule/input-kind :rule/input-feature]}))))
  (update rule :rule/version #(long (or % 1))))

(defn tx-data
  "Returns normalized Datomic tx-data for transform rule definitions."
  [rules]
  (->> rules
       (map normalize-rule)
       (sort-by :rule/id)
       vec))

(defn register!
  "Transacts transform rule definitions into a Datomic connection."
  [conn rules]
  (d/transact conn {:tx-data (tx-data rules)}))

(defn- pull-rules [db query lang kind]
  (->> (d/q query db lang kind)
       (map first)
       (sort-by :rule/id)
       vec))

(defn rules-for-node-kind
  "Returns transform rules for a source language and node kind."
  [db lang kind]
  (pull-rules db
              '[:find (pull ?rule [:rule/id
                                    :rule/source-lang
                                    :rule/input-kind
                                    :rule/output-feature
                                    :rule/status
                                    :rule/version])
                :in $ ?lang ?kind
                :where
                [?rule :rule/source-lang ?lang]
                [?rule :rule/input-kind ?kind]]
              lang
              kind))

(defn rules-for-feature-kind
  "Returns transform rules for a source language and feature kind."
  [db lang kind]
  (pull-rules db
              '[:find (pull ?rule [:rule/id
                                    :rule/source-lang
                                    :rule/input-feature
                                    :rule/output-feature
                                    :rule/status
                                    :rule/version])
                :in $ ?lang ?kind
                :where
                [?rule :rule/source-lang ?lang]
                [?rule :rule/input-feature ?kind]]
              lang
              kind))

(defn- file-summary [file]
  {:file/id (:file/id file)
   :file/path (:file/path file)
   :file/lang (:file/lang file)})

(defn- source-span [source]
  {:start-line (:node/start-line source)
   :start-column (:node/start-column source)
   :end-line (:node/end-line source)
   :end-column (:node/end-column source)})

(defn- node-constructs [db]
  (->> (d/q '[:find (pull ?node [:node/id
                                  :node/lang
                                  :node/kind
                                  :node/start-line
                                  :node/start-column
                                  :node/end-line
                                  :node/end-column
                                  {:node/file [:file/id :file/path :file/lang]}])
              :where [?node :node/id]]
            db)
       (map (fn [[node]]
              {:coverage/input :coverage.input/node
               :source/id (:node/id node)
               :source/lang (:node/lang node)
               :source/kind (:node/kind node)
               :source/file (file-summary (:node/file node))
               :source/span (source-span node)}))
       (sort-by (juxt :source/lang :source/kind :source/id))
       vec))

(defn- feature-constructs [db]
  (->> (d/q '[:find (pull ?feature [:feature/id
                                      :feature/lang
                                      :feature/kind
                                      :feature/status
                                      {:feature/node [:node/id
                                                      :node/start-line
                                                      :node/start-column
                                                      :node/end-line
                                                      :node/end-column
                                                      {:node/file [:file/id :file/path :file/lang]}]}])
              :where [?feature :feature/id]]
            db)
       (map (fn [[feature]]
              (let [node (:feature/node feature)]
                {:coverage/input :coverage.input/feature
                 :source/id (:feature/id feature)
                 :source/lang (:feature/lang feature)
                 :source/kind (:feature/kind feature)
                 :source/status (:feature/status feature)
                 :source/node-id (:node/id node)
                 :source/file (file-summary (:node/file node))
                 :source/span (source-span node)})))
       (sort-by (juxt :source/lang :source/kind :source/id))
       vec))

(defn source-constructs
  "Returns all node and feature constructs that must be covered before emission."
  [db]
  (vec (concat (node-constructs db) (feature-constructs db))))

(defn- applicable-rules [db {:coverage/keys [input] :source/keys [lang kind]}]
  (case input
    :coverage.input/node (rules-for-node-kind db lang kind)
    :coverage.input/feature (rules-for-feature-kind db lang kind)))

(defn- allowed-statuses [{:keys [allow-stubs? allow-unsupported?]}]
  (cond-> #{implemented-status}
    allow-stubs? (conj stubbed-status)
    allow-unsupported? (conj unsupported-status)))

(defn- failure [construct reason rules]
  (assoc construct
         :coverage/reason reason
         :coverage/rules (mapv #(select-keys % [:rule/id :rule/status :rule/version])
                               rules)))

(defn- coverage-failure [allowed construct rules]
  (cond
    (empty? rules)
    (failure construct :coverage.reason/missing-rule [])

    (< 1 (count rules))
    (failure construct :coverage.reason/ambiguous-rule rules)

    (contains? allowed (:rule/status (first rules)))
    nil

    (= stubbed-status (:rule/status (first rules)))
    (failure construct :coverage.reason/stubbed-rule rules)

    (= unsupported-status (:rule/status (first rules)))
    (failure construct :coverage.reason/unsupported-rule rules)

    :else
    (failure construct :coverage.reason/unimplemented-rule rules)))

(defn coverage-report
  "Checks source constructs against registered transform rules.

  By default only implemented rules pass. Set :allow-stubs? or
  :allow-unsupported? to deliberately cross those mode boundaries."
  ([db]
   (coverage-report db {}))
  ([db opts]
   (let [allowed (allowed-statuses opts)
         failures (->> (source-constructs db)
                       (keep (fn [construct]
                               (coverage-failure allowed
                                                 construct
                                                 (applicable-rules db construct))))
                       vec)]
     {:ok? (empty? failures)
      :failures failures})))

(defn assert-coverage!
  "Throws with an actionable report when transform rule coverage is incomplete."
  ([db]
   (assert-coverage! db {}))
  ([db opts]
   (let [{:keys [ok?] :as report} (coverage-report db opts)]
     (when-not ok?
       (throw (ex-info "Transform rule coverage failed." report)))
     report)))
