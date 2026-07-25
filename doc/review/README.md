# Vibeformer Code Review

A point-in-time design assessment of the Vibeformer translator, recorded
2026-07-24 at commit `009be90e` (five PdfCube profiles defined; IO, FontBox,
and XmpBox differentials passing; Pkl core value-model work in progress).

Unlike the durable contracts under [`doc/`](../README.md), these documents are
a dated snapshot. They describe the implementation as reviewed and propose
directions; they do not change any product contract.

All `Vibeformer`, `vibeformer`, and `VIBEFORMER` references—and old
`src/vibeformer` paths—within `doc/review/` are intentionally retained as
historical evidence of that pre-DripSharp snapshot. They are not active product
identity, configuration, paths, or current checkout guidance.

* [Code Assessment](code-assessment.md) — what is well designed, what is
  poorly designed, and where the risks are, with file evidence.
* [Scaling Roadmap](scaling-roadmap.md) — ideas for taking the vision forward:
  scaling to many source projects and industrializing the target workflow.
* [Mechanical / Authored Boundary](mechanical-authored-boundary.md) — a
  proposal for making the separation between mechanically translated output
  and LLM-authored code explicit, measurable, and enforced.
* [Licensing and Attribution](licensing-attribution.md) — strategy for
  license selection per target, Apache-2.0 compliance mechanics, trademark
  handling, and the repository's own license.

## Review method

All of `src/vibeformer/java_translate.clj`, `csharp.clj`, `java_types.clj`,
`public_surface.clj`, `spoon.clj`, `concurrency.clj`, `harness.clj`,
`java_project.clj`, `project_input.clj`, `main.clj`, and
`pdfcube/java_project.clj` were read in full. `java_library.clj`,
`pkl/java_project.clj`, `pkl/java_body.clj`, `differential.clj`,
`packaging.clj`, and `pdfcube/xmpbox_metadata_differential.clj` were read in
representative sections with structural outlines. Cross-cutting checks
(product-identity leaks, duplicated helpers, TODO markers) were run over the
whole source tree, and file-churn history was taken from git.

## Size inventory (for context)

| Area | Approximate size |
| --- | --- |
| Clojure source (`src/`) | ~30,400 lines across 32 namespaces |
| Clojure tests (`test/`) | ~11,600 lines |
| Handwritten C# runtime (`runtime/`) | 15,935 lines in 9 files |
| — generic Java compat (`Vibeformer.JavaCompat.cs` + regex data) | 8,529 lines, ~113 types |
| — Pkl destination runtime (`Pkl.Core.*.cs`) | ~7,200 lines |
| — PdfCube destination runtime (`PdfCube.FontBox.*.cs`) | ~200 lines |
| Pkl rule bundle (`pkl/java_project.clj` + `pkl/java_body.clj`) | 6,481 lines |
| Shared destination foundation (`java_library.clj`) | 6,590 lines |
| PdfCube bundle + differentials (`pdfcube/*`) | ~1,975 lines |
| Destination/profile configuration (`config/*.edn`) | 15 files |
