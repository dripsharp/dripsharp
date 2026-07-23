# Product Targets

Each product translated with Vibeformer has an independent directory under
this folder. Use a stable, lowercase target identifier for the directory name.

## Required Target Documents

Every target directory contains:

* `README.md` — target index, source system, destination product, and links to
  the target contracts.
* `product-goal.md` — the user-owned target outcome, required behavior,
  approved exclusions, and completion rule.
* `port-scope.md` — the concrete source and product surfaces included in or
  excluded from the port, including dependency and platform decisions.

Add durable target-specific architecture, mappings, or validation documents
only when they cannot be expressed in the shared translator documentation.
Current status, milestone plans, and deferred work belong in Beads rather than
the target directory.

## Independence Rules

* A target's exclusions apply only to that target.
* Missing or difficult behavior is pending work unless its target contract
  explicitly excludes it.
* A bounded milestone does not redefine a target's product goal or completion
  rule.
* Target-specific evaluation, language, and runtime semantics stay outside the
  reusable Java-to-C# translation layers.
* A new target may reuse generic translation rules and compatibility
  capabilities, but must declare its own product scope and evidence.

Copy the document structure of an existing target when starting another one;
do not copy that target's substantive scope decisions or exclusions.
