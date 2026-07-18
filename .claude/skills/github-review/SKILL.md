---
name: github-review
description: Fetch all comments and review discussions from an open GitHub PR, discuss each item with Yuan to decide what to implement, then automatically hand off to /review-implementing for approved items.
---

# GitHub PR Review — Discuss & Implement

Fetch PR comments → Categorise → Discuss trade-offs with Yuan → Confirm implementation list → Hand off to `/review-implementing`.

## Workflow

### Step 0 — Identify the Target PR

Run `git remote get-url origin` to parse the repo owner/name, and `git branch --show-current` to get the current branch.

**Selection rules:**
- If the current branch has a matching open PR, select it automatically
- Otherwise, list all open PRs with `mcp__github__list_pull_requests` and let Yuan choose
- If no open PRs exist → **STOP** and inform Yuan (suggest running `/open-pr` first)

---

### Step 1 — Fetch All Comments

Call the following three tools **in parallel** for the selected PR:

| Tool call | Data |
|---|---|
| `pull_request_read` (get_reviews) | Overall review decisions (APPROVE / REQUEST_CHANGES / COMMENT) |
| `pull_request_read` (get_review_comments) | Line-level review threads (includes `isResolved` / `isOutdated`) |
| `pull_request_read` (get_comments) | General PR comments (issue comments) |

---

### Step 2 — Filter Actionable Items

**Discard** the following (do not bring into discussion):
- Review threads where `isResolved: true` (already marked resolved by the developer)
- Review threads where `isOutdated: true` (code has changed, comment is stale)
- Pure APPROVE reviews with no comment body
- Bot comments (author login contains `[bot]`)

**Keep** everything that requires a human decision or action.

If no actionable items remain → inform Yuan that all comments are resolved, suggest merging, **STOP**.

---

### Step 3 — Categorise and Present

Group actionable items into 4 categories and present them as a table:

| Category | Description | Default action |
|---|---|---|
| 🔴 Bug / Correctness | Logic errors, data issues, potential exceptions | Must implement |
| 🟡 Design / Architecture | Structural changes, pattern improvements, interface design | Discuss first |
| 🟢 Style / Documentation | Naming, JavaDoc, formatting, wording | Accept by default |
| ⚪ Question / Clarification | Reviewer is asking, not requesting a change | Reply with explanation |

Format for each item:
```
[icon] #<n> — <file path>:<line> (if applicable)
Reviewer: <author>
Comment: <original text>
Default: <default action>
```

---

### Step 4 — Discuss With Yuan

Only the following categories require interactive discussion — the rest go straight to the implementation list:

- **🟡 Design / Architecture** — use `AskUserQuestion` for each item
  - Explain technical impact and trade-offs (change scope, test impact, backwards compatibility)
  - Offer at least two options: "Accept suggestion" and "Keep as-is", plus alternatives where relevant
- **⚪ Question / Clarification** — draft a reply for Yuan to review and approve

🔴 Bug and 🟢 Style/Documentation items are added to the list automatically without prompting (unless Yuan raises an objection).

---

### Step 5 — Confirm Implementation List

Summarise all "decided to implement" items:

```
## Confirmed Implementation List

The following items will be passed to /review-implementing:

1. [🔴] <brief description> — <file path>
2. [🟡] <brief description> — <file path>
3. [🟢] <brief description> — <file path>
...

Total: N items. Confirm to start implementation.
```

Use `AskUserQuestion` for a final confirmation (options: "Confirm — start implementation", "Adjust list", "Cancel").

If the list is empty (only ⚪ items) → inform Yuan that no code changes are needed, suggest merging, **STOP**.

---

### Step 6 — Hand Off to Implementation

After Yuan confirms, call the `Skill` tool to invoke `review-implementing`, passing the confirmed item list from Step 5 as context.

---

## Tool Reference

| Tool | Step | Purpose |
|---|---|---|
| `Bash` (`git remote get-url origin`) | Step 0 | Parse repo owner/name |
| `Bash` (`git branch --show-current`) | Step 0 | Get current branch |
| `mcp__github__list_pull_requests` | Step 0 | List open PRs |
| `mcp__github__pull_request_read` (get) | Step 1 | PR basic info |
| `mcp__github__pull_request_read` (get_reviews) | Step 1 | Overall reviews |
| `mcp__github__pull_request_read` (get_review_comments) | Step 1 | Line-level threads |
| `mcp__github__pull_request_read` (get_comments) | Step 1 | General comments |
| `AskUserQuestion` | Step 4, 5 | Discussion and final confirmation |
| `Skill` (`review-implementing`) | Step 6 | Hand off to implementation |

---

## Skill Chain

```
/github-review  →  (auto hand-off)  →  /review-implementing  →  (auto hand-off)  →  /git-action
(fetch/discuss)                        (TDD implementation)                          (commit/push)
```

---

## Edge Cases

**No open PRs:**
- Inform Yuan and suggest running `/open-pr` first

**Large number of comments (>20):**
- Present a summary count by category first
- Ask Yuan whether to process all at once or in batches

**Contradictory reviewer comments:**
- Clearly explain the conflict and ask Yuan to decide

**Comment requires a breaking change:**
- Highlight it explicitly in Step 4, explain the impact, and let Yuan decide before adding to the list

**GitHub API call fails:**
- Report the error to Yuan and suggest checking repo permissions or network connectivity
