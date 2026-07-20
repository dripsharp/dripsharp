The main project we are working on is /vibeformer (clojure).

The Vibeformer product goal is fixed by
`vibeformer/doc/product-goal.md`, whose baseline is commit
`1d09c0da80015d937827ebae9c6d66267ca1af25`. Agents may create bounded
implementation milestones, but must not narrow the product goal, add product
exclusions, convert unfinished product behavior into an exclusion, or redefine
project completion without explicit user approval in the current conversation.
Difficulty, missing implementation, a selected source slice, or a green
milestone gate is not evidence that product behavior is out of scope.

Changes to `vibeformer/doc/product-goal.md`, the user-approved exclusion list,
or project-completion semantics require explicit user approval. Upstream Pkl
tests are behavior evidence even when their test infrastructure is not shipped
as part of the .NET product.

Use Beads, not `vibeformer/doc/`, for temporary planning, current status,
next-slice selection, progress logs, and deferred follow-up work. Documentation
should contain only durable product goals, architecture, contracts, and
reference material.

When a worker discovers additional in-scope work, search Beads first. If it is
not required to complete the current issue, file a deduplicated follow-up with
concrete evidence and acceptance criteria under the appropriate epic. If it
blocks the current issue, record the dependency and do not close the current
issue prematurely. Beads issues must not narrow the product goal or introduce
new exclusions.

# Resource Safety

Before any full test, differential, corpus, package, or clean-generation gate,
check available RAM/CPU. Set `VIBEFORMER_WORKERS` to 22, and  and 28 GiB for the JVM heap. Be careful about running workloads that more than an hour. Consider using clj-nrepl-eval on specific parts instead.

# Clojure Parenthesis Repair

The command `clj-paren-repair` is installed on your path.

Examples:
`clj-paren-repair <files>`
`clj-paren-repair path/to/file1.clj path/to/file2.clj path/to/file3.clj`

**IMPORTANT:** Do NOT try to manually repair parenthesis errors.
If you encounter unbalanced delimiters, run `clj-paren-repair` on the file
instead of attempting to fix them yourself. If the tool doesn't work,
report to the user that they need to fix the delimiter error manually.

The tool automatically formats files with cljfmt when it processes them.

# Clojure REPL Evaluation

The command `clj-nrepl-eval` is installed on your path for evaluating Clojure code via nREPL.

**Discover nREPL servers:**

`clj-nrepl-eval --discover-ports`

**Evaluate code:**

`clj-nrepl-eval -p <port> "<clojure-code>"`

With timeout (milliseconds)

`clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"`

The REPL session persists between evaluations - namespaces and state are maintained.
Always use `:reload` when requiring namespaces to pick up changes.
