---
name: review-implementing
description: Process and implement code review feedback systematically. Use when user provides reviewer comments, PR feedback, code review notes, or asks to implement suggestions from reviews.
---

# Review Feedback Implementation

Systematically process and implement changes based on code review feedback.

## When to Use

- User provides reviewer comments or feedback
- User pastes PR review notes
- User mentions implementing review suggestions
- User says "address these comments" or "implement feedback"
- User shares a list of changes requested by reviewers

## Systematic Workflow

### 1. Parse Reviewer Notes

Identify individual feedback items:
- Split numbered lists (1., 2., etc.)
- Handle bullet points or unnumbered feedback
- Extract distinct change requests
- Clarify ambiguous items before starting

### 2. Create Task List

Use `TaskCreate` for each feedback item:
- Each feedback item becomes one or more tasks
- Break down complex feedback into smaller tasks
- Make tasks specific and measurable
- Mark the first task as `in_progress` before starting

Example tasks:
```
- Add JavaDoc to ArticleService.publish()
- Fix duplicate tag detection logic
- Add unit test for ArticleService.publish() edge case
```

### 2.5. Branch Sync (if base branch has new commits)

Before implementing any changes, check whether the feature branch has fallen behind the base branch (e.g. develop has merged other PRs).

```bash
git fetch origin
git log HEAD..origin/develop --oneline
```

**If new commits exist, use merge — rebase is strictly forbidden:**

```bash
git merge origin/<base-branch>
```

**Rules:**
- **NEVER** `git rebase` — rebase rewrites commit history and forces a push with `--force`
- **NEVER** `git push --force` or `git push --force-with-lease`
- A merge commit preserves the original history and allows a normal `git push`

**Handling conflicts:**
- After resolving conflicts: `git add <file>` → `git merge --continue`
- If conflicts are complex, report to Yuan immediately — do not guess

### 3. Implement Changes Systematically

For each task:

**Locate relevant code:**
- Use Grep to search for classes/methods
- Use Glob to find files by pattern
- Read current implementation before editing

**Determine change type, then act:**

| Change type | Approach |
|---|---|
| Pure refactor / style / formatting | Edit directly, then run tests to confirm no regression |
| New behaviour / new feature | **TDD required**: write failing test first, then implement, then refactor |
| JavaDoc / documentation | Edit only, no test needed |
| Missing test coverage | Write the test directly (this IS the Red step) |

**TDD cycle for new/changed implementation code:**
1. **Red** — Write a failing test; run it to confirm it fails
2. **Green** — Write minimal code to make the test pass; run it to confirm
3. **Refactor** — Improve structure while keeping tests green

**Project conventions:**
- All JavaDoc and inline comments must be in **Traditional Chinese (繁體中文)**
- All classes require Description, Author, Version JavaDoc
- All public methods require purpose, `@param`, and `@return` JavaDoc
- Method names in `camelCase`

**Update status:**
- Use `TaskUpdate` to mark task as `completed` once tests pass
- Only one task `in_progress` at any time

### 4. Validation

Run tests for the affected module:
```bash
./mvnw test -pl <module> -am --no-transfer-progress
```

All tests must be green with pristine output before marking a task complete.

### 5. Communication

Keep Yuan informed:
- Update task list in real-time
- Ask for clarification on ambiguous feedback before starting
- Report blockers or conflicts immediately
- Summarise all changes at completion

When all tasks are completed, invoke `/git-action` to commit and push all changes.

## Edge Cases

**Conflicting feedback:**
- Ask Yuan for guidance
- Explain the conflict clearly

**Breaking changes required:**
- Notify Yuan before implementing
- Discuss impact and alternatives

**Tests fail after changes:**
- Fix tests before marking task complete
- Ensure all related tests pass

**Referenced code doesn't exist:**
- Ask Yuan for clarification
- Verify understanding before proceeding

## Important Guidelines

- Use `TaskCreate` / `TaskUpdate` for tracking — never `TodoWrite`
- **New or changed implementation code always requires TDD** — no exceptions
- All JavaDoc and comments must be in **Traditional Chinese (繁體中文)**
- Run `./mvnw test` after every implementation task
- Only one task `in_progress` at a time; update status immediately on completion
- Ask before acting on unclear feedback
- Follow all conventions in `CLAUDE.md`
