---
name: assess-port-candidate
description: Research a candidate Java library as a possible DripSharp target, present the evidence and consequential scope choices in a local GitBook-style browser questionnaire, collect the user's decisions, stop the temporary Babashka server, and process the submitted answers. Use when the user asks to evaluate, investigate, compare, scope, or decide whether and how to port a Java library with DripSharp, especially when they want an interactive decision UI.
---

# Assess Port Candidate

Investigate one candidate library, explain its fit with DripSharp, and collect product-scope decisions through the bundled local UI. Treat the UI as a decision surface, not as a substitute for evidence.

## Workflow

1. Review repository documentation before researching upstream.
2. Investigate the candidate and current translator capabilities.
3. Build a data-driven assessment EDN file.
4. Launch the bundled Babashka server and open its URL in the in-app browser.
5. Wait for submission without abandoning the running server.
6. Read and process the answer EDN after the server exits.
7. Stop the server manually if the task is interrupted or replaced.

## Review repository documentation first

Start with `doc/README.md` and `doc/targets/README.md`. Follow their links to the shared architecture, technology, transform pipeline, target-directory, product-repository, mapping, and conversion-concern contracts that bear on the candidate. Read the closest existing target's `README.md`, `product-goal.md`, `port-scope.md`, and dependency documents.

Do this before browsing upstream or proposing scope. Preserve these repository rules:

- Do not infer exclusions from difficulty, missing implementation, or a bounded milestone.
- Distinguish a proposed product-scope choice from an implementation slice.
- Keep target-specific semantics outside product-neutral translation layers.
- Put temporary assessment files outside `doc/`; prefer a caller-created temporary directory.
- Do not create or modify a product goal, target, Beads issue, repository, or submodule unless separately authorized.

## Investigate the candidate

Use primary upstream sources for current facts. Establish at least:

- latest stable release policy, immutable revision, language level, build system, and license;
- reactor/module graph, source/test/resource size, generated sources, and application versus library surfaces;
- production and test dependencies, optional facilities, service-provider resources, native/platform behavior, and security boundaries;
- likely destination package graph and public API adaptation constraints;
- reusable DripSharp capabilities, target-owned work, and concrete ingestion blockers;
- validation strategy, upstream test infrastructure, fixtures, differential families, and platform-sensitive evidence;
- order-of-magnitude effort and the decisions that materially change it.

Run read-only or disposable spikes when they materially reduce uncertainty. Label coarse inventories and estimates as such. Do not present a narrowed slice as the whole product.

Use `~/bin/clj-surgeon` for structural outlines and dependency mapping when inspecting large translator namespaces would otherwise require ambiguous textual search.

## Prepare the assessment

Create a fresh temporary directory with `mktemp -d`. Create `assessment.edn` there with `apply_patch`, using `assets/assessment-template.edn` as the schema example. Keep prose concise enough to scan in a browser.

Every assessment must include:

- a candid recommendation and summary;
- quantified facts and findings;
- sources with direct URLs;
- sections that pair relevant evidence with consequential questions;
- mutually exclusive options where the choice is exclusive;
- `:recommended? true` on at most one option per question when a recommendation is justified;
- free-text space for product identity and qualifications when relevant.

Use stable, lowercase kebab-case string IDs for sections, questions, and option values. Supported question types are `:single`, `:multi`, `:text`, and `:boolean`. Required multi-select questions require at least one selection.

Do not ask the user to decide facts the investigation can establish. Ask only about product intent, acceptable adaptations, exclusions, packaging, platforms, evidence, naming, or tradeoffs.

## Run the questionnaire

Verify `bb` is available, then start:

```bash
bb .agents/skills/assess-port-candidate/scripts/serve_assessment.clj \
  --input <temporary-directory>/assessment.edn \
  --output <temporary-directory>/answers.edn \
  --port 0 \
  --timeout-minutes 30
```

Run it through a persistent execution session. Parse the `PORT_CANDIDATE_UI_READY` URL from startup output. The server binds only to `127.0.0.1`, uses an unguessable per-run token, validates submissions, writes the answer file atomically, and stops itself after submit, cancel, or timeout.

Use the available in-app Browser control skill to open the exact URL. If that browser cannot navigate to loopback, open the exact URL in the system browser with `open '<url>'` on macOS or the platform equivalent. If no browser opener is available, give the user the clickable loopback URL. Do not replace the browser UI with a prose questionnaire.

Tell the user briefly that the assessment is ready and that submitting it returns control. Poll the server session in bounded intervals and keep the user updated during a long wait. Do not inspect or manipulate the user's selections in the browser.

## Process answers and shut down

After the execution session exits, read `answers.edn`.

- For `:submitted`, summarize the decisions, distinguish approved scope from implementation sequencing, identify contradictions or unanswered optional choices, and answer the user's original candidate-port question in light of those decisions.
- For `:cancelled`, acknowledge cancellation and do not infer choices.
- For `:timed-out`, report that no decisions were submitted and offer to reopen a fresh assessment.

The server normally stops itself. If the conversation is interrupted, the request is replaced, browser opening fails irrecoverably, or manual cleanup is required, send an interrupt to the persistent execution session and verify that it exits. Never leave the questionnaire server running.

Move the caller-created temporary directory to Trash after processing unless the user asks to preserve the raw assessment and answer artifact. Do not remove unrelated paths.

## Bundled resources

- `scripts/serve_assessment.clj` — loopback HTTP server, validation, atomic answer writer, and lifecycle management.
- `assets/assessment-template.edn` — assessment data contract example.
- `assets/app.html`, `assets/style.css`, `assets/app.js` — GitBook-style questionnaire UI.
