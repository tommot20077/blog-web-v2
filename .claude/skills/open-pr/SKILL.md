---
name: open-pr
description: Open a Pull Request from the current feature or hotfix branch to the correct target branch (develop for features, main for hotfixes). Run once per feature after all commits are pushed.
---

# Skill: open-pr

Open a Pull Request from the current branch to the correct target branch.
Run this skill **once per feature**, after all commits are pushed.

---

## Branch Strategy

```
main        ← production; merged via release PR from develop
develop     ← integration; merged from feature/* via PR
feature/*   ← one branch per task, branched from develop
hotfix/*    ← emergency fix, branched from main → PR → main (+ back-merge to develop)
```

Protected branches: `main`, `develop` — PRs must originate from `feature/*` or `hotfix/*`.

---

## Step 0 — Branch Check

```bash
git branch --show-current
```

- If on `main` or `develop`: **STOP**.
  - Warn Yuan: PRs must originate from `feature/*` or `hotfix/*`.
  - Do not proceed.
- If on `feature/*` or `hotfix/*`: proceed to Step 1.

---

## Step 1 — Determine PR Target

| Source branch | Target branch |
|---------------|--------------|
| `feature/*`   | `develop`    |
| `hotfix/*`    | `main`       |

---

## Step 2 — Verify Push Status

```bash
git status
```

- If there are uncommitted changes: **STOP**.
  - Prompt Yuan to run `/git-action` first to commit and push.
- If the branch has no upstream or is ahead of remote: push first.
  ```bash
  git push -u origin <current-branch>
  ```

---

## Step 3 — Sync with Target Branch

Fetch the latest changes from the target branch and **merge** (not rebase) to ensure the PR is up to date:

```bash
git fetch origin
git merge origin/<target-branch>
```

- If merge encounters conflicts: **STOP**.
  - Inform Yuan that conflicts need manual resolution.
  - List the conflicting files.
- If merge succeeds with new commits:
  ```bash
  git push
  ```
- If already up to date: proceed to the next step.

**IMPORTANT:** Never use `git rebase` — always use `git merge` to sync with the target branch.

---

## Step 4 — Compose PR

- **Title**: subject of the last commit on this branch (run `git log -1 --pretty=%s`).
  - If multiple commits exist on the branch, ask Yuan to confirm the PR title.
- **Body**: summarise what changed and why (bullet points).

---

## Step 5 — Create PR via GitHub CLI

```bash
gh pr create \
  --title "<commit subject>" \
  --base <target-branch> \
  --body "$(cat <<'PR_BODY'
## Summary
- <bullet points of changes>

## Test Plan
- [ ] Unit tests pass
- [ ] Integration tests pass

🤖 Generated with [Claude Code](https://claude.com/claude-code)
PR_BODY
)"
```

---

## Step 6 — Report

Print:
- PR URL
- Source branch → target branch

Example:

> PR 已建立：https://github.com/owner/repo/pull/42
> feature/xxx → develop
