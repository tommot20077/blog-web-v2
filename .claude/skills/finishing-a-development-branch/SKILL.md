---
name: finishing-a-development-branch
description: Use when implementation is complete, all tests pass, and you need to decide how to integrate the work - guides completion of development work by presenting structured options for merge, PR, or cleanup
---

# Finishing a Development Branch

## Overview

Guide completion of development work by presenting clear options and handling chosen workflow.

**Core principle:** Verify tests → Present options → Execute choice → Clean up.

**Announce at start:** "I'm using the finishing-a-development-branch skill to complete this work."

## The Process

### Step 1: Verify Tests

**Before presenting options, verify tests pass:**

```bash
./mvnw test -pl blog-infrastructure,blog-module-user,blog-module-article -am --no-transfer-progress
```

**If tests fail:**
```
Tests failing (<N> failures). Must fix before completing:

[Show failures]

Cannot proceed with merge/PR until tests pass.
```

Stop. Don't proceed to Step 2.

**If tests pass:** Continue to Step 2.

### Step 2: Determine Base Branch

The base branch for this project is always **`develop`**. All feature/fix/refactor branches target `develop`, not `main` or `master`.

### Step 3: Present Options

Present exactly these 4 options:

```
Implementation complete. What would you like to do?

1. Merge back to develop locally
2. Push and create a Pull Request targeting develop
3. Keep the branch as-is (I'll handle it later)
4. Discard this work

Which option?
```

**Don't add explanation** - keep options concise.

### Step 4: Execute Choice

#### Option 1: Merge Locally

```bash
# Switch to develop
git checkout develop

# Pull latest
git pull

# Fast-forward merge only (preserves linear history)
git merge --ff-only <feature-branch>
```

If fast-forward fails (diverged history):
```
Fast-forward not possible. Run git rebase develop on your feature branch first, then retry.
```

After successful merge:
```bash
# Verify tests on merged result
./mvnw test -pl blog-infrastructure,blog-module-user,blog-module-article -am --no-transfer-progress

# If tests pass
git branch -d <feature-branch>
```

Then: Cleanup worktree (Step 5)

#### Option 2: Push and Create PR

PR title **must** follow Conventional Commits with Traditional Chinese subject:
`<type>(<scope>): <繁體中文 subject>`

Examples:
- `feat(user): 實作登入鎖定功能`
- `fix(security): 修正 JWT Token 版本驗證邏輯`
- `refactor(article): 重構文章狀態機轉換`

```bash
# Push branch
git push -u origin <feature-branch>

# Create PR targeting develop
gh pr create \
  --base develop \
  --title "<type>(<scope>): <繁體中文 subject>" \
  --body "$(cat <<'EOF'
## Summary
- <what changed - bullet 1>
- <what changed - bullet 2>

## Test Plan
- [ ] All existing tests pass
- [ ] <specific verification step>

## Related
<!-- Fixes #<issue> -->
EOF
)"
```

Then: Cleanup worktree (Step 5)

#### Option 3: Keep As-Is

Report: "Keeping branch <name>. Worktree preserved at <path>."

**Don't cleanup worktree.**

#### Option 4: Discard

**Confirm first:**
```
This will permanently delete:
- Branch <name>
- All commits: <commit-list>
- Worktree at <path>

Type 'discard' to confirm.
```

Wait for exact confirmation.

If confirmed:
```bash
git checkout develop
git branch -D <feature-branch>
```

Then: Cleanup worktree (Step 5)

### Step 5: Cleanup Worktree

**For Options 1, 2, 4:**

Check if in worktree:
```bash
git worktree list | grep $(git branch --show-current)
```

If yes:
```bash
git worktree remove <worktree-path>
```

**For Option 3:** Keep worktree.

## Quick Reference

| Option | Merge | Push | Keep Worktree | Cleanup Branch |
|--------|-------|------|---------------|----------------|
| 1. Merge locally | ✓ (--ff-only) | - | - | ✓ |
| 2. Create PR | - | ✓ | ✓ | - |
| 3. Keep as-is | - | - | ✓ | - |
| 4. Discard | - | - | - | ✓ (force) |

## Common Mistakes

**Skipping test verification**
- **Problem:** Merge broken code, create failing PR
- **Fix:** Always verify tests before offering options

**Wrong base branch**
- **Problem:** PR targets `main` instead of `develop`
- **Fix:** Always use `develop` as base branch for this project

**Non-conforming PR title**
- **Problem:** PR title doesn't follow project conventions
- **Fix:** Always use `<type>(<scope>): <繁體中文 subject>` format

**Using regular merge instead of --ff-only**
- **Problem:** Creates merge commits, pollutes linear history
- **Fix:** Use `--ff-only`; if it fails, rebase first

**Open-ended questions**
- **Problem:** "What should I do next?" → ambiguous
- **Fix:** Present exactly 4 structured options

**Automatic worktree cleanup**
- **Problem:** Remove worktree when might need it (Option 2, 3)
- **Fix:** Only cleanup for Options 1 and 4

**No confirmation for discard**
- **Problem:** Accidentally delete work
- **Fix:** Require typed "discard" confirmation

## Red Flags

**Never:**
- Proceed with failing tests
- Merge without verifying tests on result
- Delete work without confirmation
- Force-push without explicit request
- Target `main` or `master` instead of `develop`

**Always:**
- Verify tests before offering options
- Present exactly 4 options
- Use `develop` as the base branch
- Use `--ff-only` for local merges
- Format PR title as `<type>(<scope>): <繁體中文 subject>`
- Get typed confirmation for Option 4
- Clean up worktree for Options 1 & 4 only

## Integration

**Called by:**
- **subagent-driven-development** (Step 7) - After all tasks complete
- **executing-plans** (Step 5) - After all batches complete

**Pairs with:**
- **using-git-worktrees** - Cleans up worktree created by that skill
