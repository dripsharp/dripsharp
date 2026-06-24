# Sample Projects

This directory contains small source projects for end-to-end Vibeformer test
runs. These are not unit-test fixtures. They are durable Java and/or Kotlin
inputs that can be analyzed, transformed, emitted as C#, and compiled during
manual or scripted validation runs.

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
