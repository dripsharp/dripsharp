# Sample Projects

This directory contains small source projects for end-to-end Vibeformer test
runs. These are not unit-test fixtures. They are durable Java and/or Kotlin
inputs that can be analyzed, transformed, emitted as C#, and compiled during
manual or scripted validation runs.

The sample workflow exists to drive Vibeformer toward the compiler-like
architecture described in `doc/architecture.md`, `doc/implementation-plan.md`,
`doc/transform-pipeline.md`, `doc/datomic-model.md`, and
`doc/conversion-concerns.md`: sample by sample, convert real Pkl-shaped
Java/Kotlin constructs into normalized facts, deterministic transform rules,
disposable C# output, provenance, diagnostics, and tests.

Each sample project should follow this layout:

```text
sample-projects/
  sample-name/
    source/
      ... original Java and/or Kotlin project files ...
    target/
      csharp/
      diagnostics/
      facts/
      provenance.edn
```

The `source/` directory is the source-of-truth input and should be committed.
Keep each sample focused on a small language or library behavior so failures are
easy to attribute.

The `target/` directory is scratch output and is ignored by git. It is where
Vibeformer should emit generated C# projects, compiler diagnostics, exported
facts, and source-to-destination provenance for a run. Generated C# should be
deleted and regenerated rather than patched by hand.

Recommended target contents:

```text
target/
  csharp/          # generated .csproj and .cs files
  diagnostics/     # dotnet build logs and compiler diagnostics
  facts/           # optional exported analysis facts for debugging
  provenance.edn   # source-to-output mapping snapshot
```

Run the default smoke sample from the Vibeformer checkout with:

```bash
clojure -T:build sample
```

Run a specific sample with:

```bash
clojure -T:build sample :name sample-name
```

## Sample-Driven Beads Loop

Use Beads as the issue queue for expanding sample coverage. Start each session
with ready Beads. If none are ready, inspect the current samples, generated
diagnostics, provenance, architecture docs, and `../research/pkl` constructs to
create the next focused Beads.

Choose samples as small, representative, Pkl-shaped increments. A good sample
exposes one meaningful slice of work: extraction/modeling, type mapping,
transform rule selection, emission, provenance, diagnostic ingestion, or test
coverage. Avoid large samples that mix many unsupported constructs before the
pipeline can classify them clearly.

When a sample fails, treat the failure as evidence about the transpiler. Convert
each major exposed gap into explicit Beads with enough context to reproduce the
sample diagnostics or source construct. Close those Beads only after the
analyzer, model, rules, emitter, provenance, diagnostics, or tests have improved
and the relevant sample artifacts show the new behavior.

Generated C# remains disposable. Do not patch files under `target/csharp` by
hand; fix the durable pipeline inputs and regenerate.
