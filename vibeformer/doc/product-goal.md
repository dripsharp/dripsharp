# Authoritative Product Goal

## Authority

This document is the user-owned product contract for Vibeformer. It restores
the intent recorded by `doc/port-scope.md` at commit
`1d09c0da80015d937827ebae9c6d66267ca1af25` and takes precedence over bounded
source-slice, milestone, manifest, acceptance, or release-readiness documents.

Agents may refine implementation plans and create bounded milestones. They may
not narrow this goal, add an exclusion, or redefine completion without explicit
user approval in the current conversation.

The following are not scope decisions:

* A behavior is difficult to port.
* A behavior is not implemented yet.
* A behavior is outside the current selected source slice.
* A dependency has no direct .NET replacement.
* A test or compiler gate does not currently cover the behavior.
* A bounded milestone is green.

Those conditions mean the behavior is pending product work unless it appears in
the user-approved exclusion list below.

## Product Target

The first product target is a .NET library, not a full replacement for the Pkl
JVM distribution.

The target library must provide the Pkl behavior needed by .NET consumers:

* Core Pkl parsing, evaluation, value model, module loading, and runtime
  behavior.
* Public .NET library APIs equivalent to the useful Java/Kotlin library entry
  points.
* C# code generation for Pkl schemas and APIs.

Equivalent behavior may be implemented through generated C#, native .NET
helpers, or focused .NET replacements. Literal JVM, GraalVM, or Truffle ports
are not required, but the product behavior they support remains in scope.

## User-Approved Product Exclusions

Only these product surfaces are excluded:

* YAML support.
* MessagePack and Pkl binary transport support.
* Pkl server support.
* CLI product support, except a small validation harness when useful.
* Gradle product integration.
* Documentation-site generation.
* Java, Kotlin, and other non-C# code-generation products.
* Native-image and JVM distribution packaging.
* Build, benchmark, and test infrastructure as shipped product surface.
* Manual patches to generated C# as durable implementation.

Test infrastructure is excluded from what is shipped, not from the evidence
used to prove behavior. Upstream Pkl tests and fixtures are authoritative
behavior specifications and should be ported, adapted, or used for differential
validation where they cover the .NET library goal.

## In-Scope Pending Areas

Unless the user later decides otherwise, these are in-scope product work even
when a bounded milestone does not implement them:

* Complete parser behavior needed by the library runtime.
* Complete evaluator and runtime semantics.
* The complete value model and useful object/config binding behavior.
* Local, HTTP, classpath, modulepath, package, project, and custom
  module/resource loading needed by normal .NET consumers.
* Project, package, and evaluator settings needed by the public library API.
* Security-policy behavior required to expose module and resource loading
  safely.
* Useful evaluator-builder, config-evaluator, schema, and generated-loader APIs.
* Native .NET dependency and runtime-substrate replacements required by those
  behaviors.

## Completion Rule

Project completion requires every required capability above to be complete, all
remaining exclusions to match the user-approved list, clean from-scratch
generation, zero public implementation stubs, successful package consumption,
and independent behavior evidence representative of the full goal.

A bounded milestone may report milestone readiness. It must never report
project completion.
