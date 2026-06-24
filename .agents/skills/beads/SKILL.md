---
name: beads
description: Manually invoked Beads issue-tracking workflow for this repository. Use only when the user explicitly asks to use Beads, invokes `$beads`, mentions `br` or `bd` issue commands, asks to manage Beads issues, or asks to start/end a Beads-tracked session.
---

# Beads Workflow

## Overview

Use this skill only after an explicit user request for Beads. Do not infer Beads usage solely from the presence of a `.beads/` directory.

Use Beads as the source of truth for issue tracking in this repository. Beads data lives in `.beads/` and is tracked in git, so sync it before ending a session when issue data changes.

## Essential Commands

```bash
# View ready issues: open, unblocked, not deferred
br ready              # or: bd ready

# List and search
br list --status=open # All open issues
br show <id>          # Full issue details with dependencies
br search "keyword"   # Full-text search

# Create and update
br create --title="..." --description="..." --type=task --priority=2
br update <id> --status=in_progress
br close <id> --reason="Completed"
br close <id1> <id2>

# Sync with git
br sync --flush-only
br sync --status
```

## Workflow

1. Start by running `br ready` to find actionable work.
2. Claim work with `br update <id> --status=in_progress`.
3. Implement the task.
4. Close completed issues with `br close <id> --reason="Completed"`.
5. Run `br sync --flush-only` before ending a session if Beads data changed.

## Concepts

- Dependencies can block issues. `br ready` shows only open, unblocked work.
- Priorities are numeric: P0 critical, P1 high, P2 medium, P3 low, P4 backlog.
- Valid issue types include `task`, `bug`, `feature`, `epic`, `chore`, `docs`, and `question`.
- Add dependencies with `br dep add <issue> <depends-on>`.

## Best Practices

- Check `br ready` at session start to find available work.
- Update status as work progresses: `in_progress` to `closed`.
- Create new issues with `br create` when discovering follow-up work.
- Use descriptive titles and set an appropriate numeric priority and type.
- Always run `br sync --flush-only` before ending a session where Beads data changed.

## Session Protocol

Before ending a session, run this checklist:

```bash
git status
git add <files>
br sync --flush-only
git commit -m "..."
```
