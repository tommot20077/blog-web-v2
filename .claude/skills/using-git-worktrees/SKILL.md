---
name: using-git-worktrees
description: Use when starting feature work that needs isolation from current workspace or before executing implementation plans - creates isolated git worktrees with smart directory selection and safety verification
---

# Using Git Worktrees

## Overview

Git worktrees create isolated workspaces sharing the same repository, allowing work on multiple branches simultaneously without switching.

**Core principle:** Systematic directory selection + safety verification + explicit working directory contract = reliable isolation.

**Announce at start:** "I'm using the using-git-worktrees skill to set up an isolated workspace."

## Directory Selection Process

Follow this priority order:

### 1. Check Existing Directories

```bash
# Check in priority order
ls -d .worktrees 2>/dev/null     # Preferred (hidden)
ls -d worktrees 2>/dev/null      # Alternative
```

**If found:** Use that directory. If both exist, `.worktrees` wins.

### 2. Check CLAUDE.md

```bash
grep -i "worktree.*director" CLAUDE.md 2>/dev/null
```

**If preference specified:** Use it without asking.

### 3. Ask User

If no directory exists and no CLAUDE.md preference:

```
No worktree directory found. Where should I create worktrees?

1. .worktrees/ (project-local, hidden)

Which would you prefer?
```

## Safety Verification

### For Project-Local Directories (.worktrees or worktrees)

**MUST verify directory is ignored before creating worktree:**

```bash
# Check if directory is ignored (respects local, global, and system gitignore)
git check-ignore -q .worktrees 2>/dev/null || git check-ignore -q worktrees 2>/dev/null
```

**If NOT ignored:**

This project's `.gitignore` does **not** currently contain a `.worktrees` entry. If using `.worktrees/`, you **must** add and commit the `.gitignore` change first:

1. Add `.worktrees/` to `.gitignore`
2. Commit the change: `git add .gitignore && git commit -m "chore: add .worktrees to .gitignore"`
3. Proceed with worktree creation

**Why critical:** Prevents accidentally committing worktree contents to repository.

## Creation Steps

### 1. Detect Project Name

```bash
project=$(basename "$(git rev-parse --show-toplevel)")
```

### 1.5 Ask Base Branch and Sync from Remote

**MUST ask the user** which branch to base the worktree on. Never assume or auto-determine.

Use AskUserQuestion to ask:
```
要從哪個分支切出去開發？
```

Provide options based on common branches (e.g., main, develop, current branch), but always allow custom input.

After the user specifies the base branch:

```bash
# Fetch all remote updates (does NOT affect current workspace)
git fetch origin

# Verify the base branch exists on remote
git rev-parse --verify "origin/$BASE_BRANCH" 2>/dev/null
```

**IMPORTANT:** Do NOT run `git checkout` or `git switch` in the main workspace.
Use `origin/$BASE_BRANCH` directly when creating the worktree to avoid touching the current working directory.

### 2. Create Worktree

```bash
# Determine full path
path=".worktrees/$BRANCH_NAME"

# Branch off from the remote base branch directly — no checkout needed
git worktree add "$path" -b "$BRANCH_NAME" "origin/$BASE_BRANCH"
```

**Branch naming convention** — follow Conventional Commits style:
- `feature/login-lock`
- `fix/jwt-validation`
- `refactor/user-facade`
- `test/article-controller`

### 3. Run Project Setup

This is a Java Maven project. Maven resolves dependencies automatically on first build — no manual install step is required.

Ensure the Maven wrapper is executable:

```bash
# Only needed once per worktree; skip if already executable
chmod +x "$path/mvnw"
```

### 4. Verify Clean Baseline

Run tests **from the worktree directory** to ensure the worktree starts clean:

```bash
./mvnw -f "$path/pom.xml" test -pl blog-infrastructure,blog-module-user,blog-module-article -am --no-transfer-progress
```

**If tests fail:** Report failures, ask whether to proceed or investigate.

**If tests pass:** Report ready.

### 5. Declare Working Directory Contract

After the worktree is created and verified, **explicitly state the working directory contract**:

```
Worktree ready at: <WORKTREE_FULL_PATH>
Working directory contract: ALL subsequent commands, subagents, and Maven
invocations MUST run from <WORKTREE_FULL_PATH>, not the main repository root.
Tests passing (<N> tests, 0 failures)
Ready to implement <feature-name>
```

This contract is binding for:
- Every subagent dispatched by `subagent-driven-development`
- Every `git` command (commits, status, diff)
- Every `./mvnw` invocation

**Pass the worktree path explicitly** when handing off to `subagent-driven-development`:

```
Worktree path: <WORKTREE_FULL_PATH>
All operations must be performed inside this directory.
```

## Quick Reference

| Situation | Action |
|-----------|--------|
| `.worktrees/` exists | Use it (verify ignored) |
| `worktrees/` exists | Use it (verify ignored) |
| Both exist | Use `.worktrees/` |
| Neither exists | Check CLAUDE.md → Ask user |
| Directory not ignored | Add to .gitignore + commit |
| Tests fail during baseline | Report failures + ask |

## Common Mistakes

### Skipping ignore verification

- **Problem:** Worktree contents get tracked, pollute git status
- **Fix:** Always use `git check-ignore` before creating project-local worktree

### Assuming directory location

- **Problem:** Creates inconsistency, violates project conventions
- **Fix:** Follow priority: existing > CLAUDE.md > ask

### Proceeding with failing tests

- **Problem:** Can't distinguish new bugs from pre-existing issues
- **Fix:** Report failures, get explicit permission to proceed

### Not syncing base branch before worktree creation

- **Problem:** Worktree branches from stale local HEAD, missing recent merged PRs
- **Fix:** Always `git fetch origin` + use `origin/<base>` directly in `git worktree add`

### Assuming base branch without asking

- **Problem:** User may want to branch from a feature branch, not main/develop
- **Fix:** Always ask the user which base branch to use via AskUserQuestion

### Switching branches in main workspace to sync

- **Problem:** Disrupts the user's current work, changes working directory state
- **Fix:** Never `git checkout` or `git switch` in main workspace. Use `origin/<branch>` directly

### Running commands from the main repo instead of the worktree

- **Problem:** Changes land in the wrong branch; tests run against wrong code
- **Fix:** Always resolve absolute path with `git rev-parse --show-toplevel` inside the worktree, or use `-f <worktree>/pom.xml` for Maven

## Example Workflow

```
You: I'm using the using-git-worktrees skill to set up an isolated workspace.

[Check .worktrees/ - not found]
[Check CLAUDE.md - no preference]
[Ask user - chooses .worktrees/]
[Verify ignored - NOT ignored, .gitignore missing entry]
[Add .worktrees/ to .gitignore]
[Commit: git add .gitignore && git commit -m "chore: add .worktrees to .gitignore"]
[Ask user: 要從哪個分支切出去開發？ → user chooses "develop"]
[git fetch origin]
[Create worktree: git worktree add .worktrees/feature/login-lock -b feature/login-lock origin/develop]
[chmod +x .worktrees/feature/login-lock/mvnw]
[Run: ./mvnw -f .worktrees/feature/login-lock/pom.xml test -pl blog-infrastructure,blog-module-user,blog-module-article -am --no-transfer-progress]
[Result: BUILD SUCCESS, 143 tests, 0 failures]

Worktree ready at: /mnt/d/end/workspace/java/blog-web-v2/.worktrees/feature/login-lock
Working directory contract: ALL subsequent commands, subagents, and Maven
invocations MUST run from /mnt/d/end/workspace/java/blog-web-v2/.worktrees/feature/login-lock
Tests passing (143 tests, 0 failures)
Ready to implement login-lock feature
```

## Red Flags

**Never:**
- Create worktree without verifying it's ignored (project-local)
- Skip baseline test verification
- Proceed with failing tests without asking
- Assume directory location when ambiguous
- Skip CLAUDE.md check
- Run git or Maven commands from the main repo when a worktree is active

**Always:**
- Follow directory priority: existing > CLAUDE.md > ask
- Verify directory is ignored for project-local
- Verify clean test baseline with Maven command
- Declare working directory contract after worktree creation
- Pass the worktree path explicitly to any downstream skill or subagent

## Integration

**Called by:**
- **brainstorming** (Phase 4) - REQUIRED when design is approved and implementation follows
- **subagent-driven-development** - REQUIRED before executing any tasks
- Any skill needing isolated workspace

**Pairs with:**
- **finishing-a-development-branch** - REQUIRED for cleanup after work complete
