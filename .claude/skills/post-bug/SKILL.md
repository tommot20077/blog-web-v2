---
name: post-bug
description: Record a post-mortem report after fixing a bug — analyses the fix commit, discusses root cause and category with Yuan, generates a structured report in ai-docs/bug-reports/, and updates the index.
---

# Skill: post-bug

Post-mortem recording after a bug fix. Analyse the fix commit, discuss root cause and category with Yuan, generate a structured report and update the index.

---

## Step 0 — Guard

```bash
git status --porcelain
```

- If there are uncommitted changes: **STOP**.
  - Tell Yuan:
    > Uncommitted changes detected. Please run `/git-action` first, then run `/post-bug` again.

---

## Step 1 — Locate the Bug Fix Commit

```bash
git log --oneline --grep="^fix" -5
```

- Present the list to Yuan via `AskUserQuestion`:
  > Here are the recent fix commits. Please select the one to review (enter number or SHA):
  >
  > 1. `<sha> <subject>`
  > 2. `<sha> <subject>`
  > ...
  >
  > If the commit you want to review is not listed, enter the commit SHA directly.

---

## Step 2 — Gather Context

Run the following **in parallel** for the selected commit:

| Command | Purpose |
|---|---|
| `git show <sha> --stat` | Affected files overview |
| `git show <sha> -p` | Full diff |
| `git log --oneline -5 -- <affected-files>` | Recent history of affected files |

Also read any test files changed in the commit to understand what was tested.

---

## Step 3 — Analyse and Discuss with Yuan

### 3a — Present Analysis

Summarise findings in a table:

```
## Initial Analysis

| Item | Detail |
|------|--------|
| Commit | <sha> — <subject> |
| Affected modules | <modules> |
| Files changed | <count> |
| Test changes | Yes / No |
| Suspected root cause | <root cause analysis> |
| Suggested category | <category> |
| Suggested severity | HIGH / MEDIUM / LOW |
```

### 3b — Discuss with Yuan

Use `AskUserQuestion` to confirm/correct each item:

> **Bug Category** (multiple allowed, comma-separated):
>
> 1. Careless Oversight — typo, missed edge case, copy-paste error
> 2. Knowledge Gap — unfamiliar API/framework behaviour, language feature
> 3. Architectural Flaw — poor design, unclear responsibility, tight coupling
> 4. Requirement Misunderstanding — misread requirement, ambiguous boundary
> 5. Race Condition — concurrency, timing issue
> 6. Legacy Tech Debt — old code issue, outdated dependency
>
> Claude suggests: **<suggested category>**
> Is the root cause analysis correct? Is the severity correct?
> At which stage was this bug discovered? (dev / staging / production / code-review)
> Any preventive measures to suggest?

---

## Step 4 — Determine Report ID and Filename

1. Read `ai-docs/bug-reports/INDEX.md` to count existing entries
2. Calculate next ID: `BUG-<YYYY>-<NNN>` where NNN = existing count + 1 (zero-padded to 3 digits)
3. Generate slug from commit subject: extract keywords, lowercase, hyphen-separated, max 50 chars
4. Filename: `ai-docs/bug-reports/YYYY-MM-DD-<slug>.md`

---

## Step 5 — Generate Report

Write the report file using this template:

```markdown
---
id: BUG-YYYY-NNN
date: YYYY-MM-DD
commit: <short-sha>
modules: [<module1>, <module2>]
category: [<category>]
severity: HIGH|MEDIUM|LOW
impact: production|staging|dev|code-review-caught
---

# Bug Review: <commit subject>

## Summary

| Item | Detail |
|------|--------|
| ID | BUG-YYYY-NNN |
| Date | YYYY-MM-DD |
| Commit | `<sha>` |
| Modules | <modules> |
| Category | <category> |
| Severity | <severity> |
| Impact | <impact> |

## Symptoms

<Describe the observable bug behaviour>

## Root Cause Analysis

<Explain the root cause>

### Before Fix

\`\`\`java
// Problematic code
\`\`\`

### After Fix

\`\`\`java
// Fixed code
\`\`\`

## Affected Files

<List all changed files>

## Timeline

| Event | Time / Commit |
|-------|--------------|
| Introduced | <if traceable> |
| Discovered | <how and when> |
| Fixed | `<sha>` |

## Preventive Measures

| Measure | Status |
|---------|--------|
| <measure 1> | TODO |
| <measure 2> | TODO |

## Lesson Learned

<One-sentence takeaway>
```

---

## Step 6 — Update Index

Append the new entry to `ai-docs/bug-reports/INDEX.md`:

1. Add a row to the **Report List** table
2. Update the **Statistics** section counts

---

## Step 7 — Knowledge Transfer (Conditional)

If the category includes **Knowledge Gap** or **Architectural Flaw**:

- Use `AskUserQuestion` to suggest promoting the lesson into the matching guideline file
  (`ai-docs/code-standards.md` / `ai-docs/security.md` / `ai-docs/testing-standards.md` /
  `ai-docs/architecture.md` — pick by topic):
  > This is a "<category>" bug. Suggest adding the following rule to `<target ai-docs file>`:
  >
  > <suggested rule, with one correct and one forbidden example>
  >
  > Apply it now?

- If Yuan agrees, apply the edit and set that preventive measure's status to DONE
  (with date + target file) in the report — doc-type measures should not be left as TODO.

**Measure triage (mandatory, all categories)** — for EVERY preventive measure in the report:

- **doc-type** (a guideline edit prevents it) → apply now (with Yuan's approval above), mark `DONE (date, target file)`
- **program-type** (needs tests / tooling / CI) → create `ai-docs/backlog/YYYY-MM-DD-<slug>.md` with the item + acceptance criteria, mark the measure `TODO (backlog: <file>)`
- A bare `TODO` with no destination is not an allowed end state (see `ai-docs/maintenance.md` §1).

---

## Step 8 — Report Summary

Print:
- Report file path
- Bug ID
- One-line summary

Then remind Yuan:

> Report generated. Run `/git-action` to commit when ready.
> After accumulating several reports, run `/review-bugs` for trend analysis and improvements.

---

## Bug Categories Reference

| # | Category | Examples |
|---|----------|----------|
| 1 | Careless Oversight | Typo, missed edge case, copy-paste error |
| 2 | Knowledge Gap | Unfamiliar API/framework behaviour, language feature |
| 3 | Architectural Flaw | Poor design, unclear responsibility, tight coupling |
| 4 | Requirement Misunderstanding | Misread requirement, ambiguous boundary |
| 5 | Race Condition | Concurrency, timing issue |
| 6 | Legacy Tech Debt | Old code issue, outdated dependency |

---

## Tool Reference

| Tool | Step | Purpose |
|---|---|---|
| `Bash` (`git status`) | Step 0 | Guard check |
| `Bash` (`git log --grep`) | Step 1 | Find fix commits |
| `Bash` (`git show`) | Step 2 | Commit details and diff |
| `Read` | Step 2 | Read test files |
| `AskUserQuestion` | Step 1, 3, 7 | Confirm selections and discuss |
| `Write` | Step 5 | Generate report file |
| `Edit` | Step 6 | Update INDEX.md |
| `Edit`/`Write` | Step 7 | Promote rule into matching ai-docs file / create backlog item (conditional) |

---

## Edge Cases

**No fix commits found:**
- Ask Yuan to provide a commit SHA manually

**Multiple bugs in one commit:**
- Generate one report per commit; if Yuan identifies multiple bugs, create separate reports and cross-reference them

**Cannot determine root cause from diff alone:**
- Use `AskUserQuestion` to ask Yuan for additional context about the bug's symptoms and discovery

**INDEX.md does not exist:**
- Create it with the initial structure from the template
