# Sample Projects

This directory is a regression corpus of small Java and Kotlin source projects
covering language and library behaviors previously encountered by Vibeformer.

The committed `source/` trees are durable inputs. Generated content under each
sample's `target/` directory is disposable and must never be patched by hand.

These samples may support focused translator tests, but they are not product
milestones and do not establish module completeness. The first architectural
proof is clean translation, compilation, and independent behavior comparison
for the complete `pkl-parser` module, as described in
[`../doc/architecture.md`](../doc/architecture.md).

When the replacement runner exists, it may reuse these inputs as regression
cases. Current commands and temporary sequencing belong in Beads rather than in
this document.
