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

Use Ork, not `vibeformer/doc/`, for temporary planning, current status,
next-slice selection, progress logs, and deferred follow-up work. The imported
`.beads` data is a read-only historical archive; do not run `br` or modify
`.beads`. Documentation should contain only durable product goals,
architecture, contracts, and reference material.

Work only on the exact Ork task supplied for the execution. When a worker
discovers additional in-scope work, report a deduplicated follow-up through the
structured Ork completion report with concrete evidence and acceptance
criteria under the appropriate parent task. If it blocks the current task,
report the blocker and do not claim completion prematurely. Ork tasks must not
narrow the product goal or introduce new exclusions.
