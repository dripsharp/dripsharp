# RawHTTP Conformance Target Goal

RawHTTP Core is the permanent reusable-translator conformance target for the
shared Java-library translation pipeline. Its required proof is deliberately
small enough for continuous integration and must remain green while product
targets evolve. Its durable product scope and completion semantics are still
governed by the repository product-goal process; this document does not add
exclusions or treat successful target-directory validation as completion.

The target contract must retain required coverage for reusable Gradle
ingestion, recursive Java-to-C# translation, clean generation and compilation,
deterministic packaging, independent package consumption, and the pinned
Java/.NET behavior equivalence proof. CI resource or duration decisions do not
make any part of that ladder optional.
