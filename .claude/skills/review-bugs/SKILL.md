---
name: review-bugs
description: Periodically review accumulated bug reports in ai-docs/bug-reports/ — analyse patterns, identify hotspots, suggest improvements to code standards and review checklists, and update guidelines with Yuan's approval.
---

# Skill: review-bugs

Periodically review accumulated bug reports. Analyse patterns and hotspots, produce improvement suggestions, and update guideline documents with Yuan's approval.

---

## Step 0 — Load All Reports

```bash
ls ai-docs/bug-reports/*.md | grep -v INDEX.md
```

- If no reports exist: **STOP**.
  - Tell Yuan:
    > No bug reports found. Run `/post-bug` after fixing a bug to record a post-mortem.

- Read `ai-docs/bug-reports/INDEX.md` for existing statistics.
- Read all individual report files (use `Read` tool in parallel).

---

## Step 1 — Statistical Analysis

Produce the following statistics from all reports:

### 1a — Category Distribution

```
## Category Distribution

| Category | Count | % |
|----------|-------|---|
| Careless Oversight | N | XX% |
| Knowledge Gap | N | XX% |
| Architectural Flaw | N | XX% |
| Requirement Misunderstanding | N | XX% |
| Race Condition | N | XX% |
| Legacy Tech Debt | N | XX% |
```

### 1b — Module Hotspots

```
## Module Hotspots

| Module | Bug Count | Most Common Category |
|--------|-----------|---------------------|
| <module> | N | <category> |
```

### 1c — Severity Distribution

```
## Severity Distribution

| Severity | Count | % |
|----------|-------|---|
| HIGH | N | XX% |
| MEDIUM | N | XX% |
| LOW | N | XX% |
```

### 1d — Impact Distribution

```
## Discovery Stage

| Stage | Count | % |
|-------|-------|---|
| code-review-caught | N | XX% |
| dev | N | XX% |
| staging | N | XX% |
| production | N | XX% |
```

### 1e — Time Trend

```
## Time Trend

| Month | Bug Count | Primary Category |
|-------|-----------|-----------------|
| YYYY-MM | N | <category> |
```

---

## Step 2 — Pattern Recognition

Analyse the data and identify:

1. **Recurring root causes** — same type of mistake appearing multiple times
2. **Module hotspots** — modules with disproportionately many bugs
3. **Category trends** — increasing or decreasing categories over time
4. **Prevention gaps** — preventive measures marked as TODO in reports
5. **Cross-cutting patterns** — issues spanning multiple modules or categories

Present findings clearly:

```
## Patterns Found

### Pattern 1: <pattern name>
- Occurrences: N
- Related reports: BUG-YYYY-NNN, BUG-YYYY-NNN
- Description: <description>
- Suggested improvement: <suggestion>

### Pattern 2: ...
```

---

## Step 3 — Generate Improvement Suggestions

Based on the patterns found, produce concrete suggestions in these categories:

### 3a — Code Standards Updates

- Suggest specific additions or modifications to `ai-docs/code-standards.md`
- Include the exact text to add/change

### 3b — Security Updates

- If security-related patterns found, suggest updates to `ai-docs/security.md`

### 3c — Testing Standards Updates

- Suggest additions to `ai-docs/testing-standards.md` based on testing gaps

### 3d — Review Checklist

- Suggest new checklist items for `/github-review` based on common bugs
- Format as actionable review points

### 3e — Judgment Updates

- Suggest new entries for `ai-docs/judgment.md` (project-specific judgment rules, one correct + one forbidden example each) for knowledge gaps that are not mechanical rules

### 3f — Tooling Suggestions

- Suggest new linting rules, test coverage requirements, or CI checks

### 3g — Outstanding TODO Triage (mandatory)

For every preventive measure still marked TODO across all reports, propose exactly one of:

1. **Promote** — apply the guideline edit now (doc-type)
2. **Backlog** — create or link an `ai-docs/backlog/` item (program-type)
3. **Defer** — keep TODO with an explicit written reason and a revisit trigger

Include these in Step 4's discussion. A bare TODO is not an allowed end state (see `ai-docs/maintenance.md` §1).

Format each suggestion:

```
### Suggestion <N>: <title>
- Type: Guideline update / Review checklist / Memory / Tooling
- Target file: <file path>
- Based on reports: BUG-YYYY-NNN, BUG-YYYY-NNN
- Content:
  <exact content to add/change>
```

---

## Step 4 — Discuss with Yuan

Present all suggestions and use `AskUserQuestion`:

> Here are the improvement suggestions based on bug report analysis, N items total.
>
> Select items to execute (comma-separated numbers), or enter "all" to execute all:
>
> 1. <suggestion 1 title>
> 2. <suggestion 2 title>
> ...
>
> Enter "none" to skip, or provide modifications.

---

## Step 5 — Execute Approved Improvements

For each approved suggestion:

1. Read the target file
2. Apply the changes using `Edit` tool
3. Confirm the change was applied correctly

Do **NOT** commit — remind Yuan to use `/git-action` when ready.

---

## Step 6 — Update INDEX.md

Update `ai-docs/bug-reports/INDEX.md`:

1. Refresh the **Statistics** section with latest numbers from Step 1
2. Add a new entry to the **Review Log** section:

```markdown
### YYYY-MM-DD Review

- Reports analysed: N
- Key findings: <key findings>
- Improvements applied:
  - <improvement 1>
  - <improvement 2>
```

---

## Step 7 — Summary

Print:
- Total reports analysed
- Key patterns found
- Improvements applied
- Outstanding TODO preventive measures across all reports

Then remind Yuan:

> Review complete. Run `/git-action` to commit changes when ready.

---

## Tool Reference

| Tool | Step | Purpose |
|---|---|---|
| `Bash` (`ls`) | Step 0 | List report files |
| `Read` | Step 0 | Read all reports and INDEX.md |
| `AskUserQuestion` | Step 4 | Discuss suggestions with Yuan |
| `Read` | Step 5 | Read target files before editing |
| `Edit` | Step 5 | Apply approved changes |
| `Edit` | Step 6 | Update INDEX.md |

---

## Edge Cases

**Only 1-2 reports:**
- Still perform analysis but note that trends may not be statistically meaningful
- Focus on individual prevention measures rather than patterns

**Reports with inconsistent format:**
- Extract what you can and note any parsing issues to Yuan

**Conflicting suggestions:**
- If two patterns suggest contradictory improvements, present both options to Yuan with trade-offs

**No actionable patterns found:**
- Report the good news and suggest continuing to record bugs for future analysis

**Guidelines file doesn't exist:**
- Skip that category of suggestions and note the missing file
