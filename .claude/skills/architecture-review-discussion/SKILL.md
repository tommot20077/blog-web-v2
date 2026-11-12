---
name: architecture-review-discussion
description: "Facilitate multi-role architecture review discussions using an Agent Team. Spawns specialized agents (architects, security lead, CTO) that explore the codebase, evaluate designs, ask each other questions, and resolve disputes through multiple rounds. Use when Yuan wants a team-based architecture review, design critique, or cross-functional discussion."
---

# Architecture Review Discussion (Agent Team)

## Overview

Orchestrate a multi-round, multi-role architecture review discussion using Agent Team mode. Each team member independently explores the codebase from their professional perspective, then engages in cross-discussion with other members — asking questions, responding to challenges, and resolving disputes.

The team lead (you) acts as **secretary**, recording every round's questions, responses, and decisions to a discussion log file.

## When to Use

- Yuan asks for an architecture review or design critique
- Yuan wants multiple perspectives (backend, integration, security, management) on a design
- Yuan mentions "team discussion", "architecture review", "cross-functional review"
- Yuan wants to simulate a design review meeting with different roles

## Team Composition (Default)

| Role | Agent Name | Model | Responsibilities |
|------|-----------|-------|-----------------|
| Senior Architect A | `architect-backend` | opus | Backend architecture, module structure, layering, DDD, ORM, DB schema, batch processing, error handling |
| Senior Architect B | `architect-integration` | opus | Integration, scalability, event-driven design, DevOps, caching, search, resilience patterns, deployment |
| Security Lead | `security-lead` | opus | Authentication, authorization, CORS/CSRF, input validation, key management, dependency security, deployment security |
| CTO | `cto` | opus | Overall direction, tech stack risk, complexity vs capability, prioritization, final decisions on disputes |
| Secretary | `team-lead` (you) | — | Coordinate rounds, relay questions, record discussions, manage discuss.md |

**Customization**: Yuan may request different roles (e.g., DBA, frontend lead, QA lead). Adapt the team composition accordingly while keeping the CTO as the final decision-maker.

## Output File

All discussion records go to: `docs/plans/discuss.md`

---

## Process Flow

```
Round 1: Independent Exploration + Initial Assessment + Questions
    |
    v
[Secretary records Round 1 to discuss.md]
    |
    v
Round 2: Cross-Response (each member answers questions from others + raises follow-ups)
    |
    v
[Secretary records Round 2 to discuss.md, identifies disputes]
    |
    v
Round 3+: Follow-up Responses + Dispute Resolution
    |
    v
[Secretary records Round 3 to discuss.md]
    |
    v
Final: CTO Summary + Verdicts on all disputes
    |
    v
[Secretary records final decisions, shuts down team]
```

---

## Detailed Steps

### Step 0: Explore the Codebase

Before creating the team, launch **up to 3 Explore agents** in parallel to understand the project's current state:

- Architecture documents, design plans
- Code structure, modules, dependencies
- Configuration files, security setup
- Testing infrastructure, deployment

This gives you the context needed to craft effective prompts for each team member.

### Step 1: Create Team + Tasks

```
TeamCreate: team_name = "arch-review"
```

Create tasks for each round to track progress.

### Step 2: Round 1 — Independent Exploration (4 agents in parallel)

Spawn all team members simultaneously with `team_name: "arch-review"` and `model: opus`.

Each member's prompt must include:

1. **Role definition** — their specific responsibilities
2. **Team member list** — names of all other members (for SendMessage)
3. **File list to read** — all relevant architecture docs, code files, configs
4. **Output requirements**:
   - Professional assessment (strengths, risks, suggestions) — at least 3 of each
   - Questions for each other member (at least 1-2 per member)
5. **Language**: Traditional Chinese (繁體中文)
6. **Format**: Structured sections with headers

**Critical**: Tell each agent to use `SendMessage` to send their responses to other team members AND a summary to `team-lead`.

### Step 3: Record Round 1

As secretary, write Round 1 to `docs/plans/discuss.md` with structure:

```markdown
# [Project] 架構審查討論紀錄

> 日期：YYYY-MM-DD
> 參與者：[roles]
> 書記：主流程記錄

## Round 1：各自探索 + 初步評估 + 提問

### [Role Name]
#### 評估
**優點：** ...
**風險與問題：** ...
**改進建議：** ...

#### 對其他成員的提問
**→ [Other Role]：** ...
```

### Step 4: Round 2 — Cross-Response

Send each member the questions directed at them from ALL other members via `SendMessage`. Include:

1. The exact questions (organized by sender)
2. Instruction to respond with depth and evidence
3. Instruction to raise follow-up questions
4. Instruction to send responses via SendMessage to the questioner + summary to team-lead

### Step 5: Record Round 2 + Identify Disputes

Record all responses. **Identify disputes** where members disagree:

```markdown
## Round 2 決策衝突待解
1. **[Topic]**: [Member A] 建議 X ↔ [Member B] 建議 Y
```

### Step 6: Round 3 — Follow-up + Dispute Resolution

Send each member:
1. Follow-up questions from Round 2
2. **Disputes to take a position on** (with both sides summarized)
3. Ask CTO specifically to **make final verdicts** on all disputes

Tell CTO this is the final round — their summary should include:
- Verdicts on all disputes with reasoning
- Key consensus points
- Architecture adjustments from the discussion
- Action items
- Documents to update

### Step 7: Record Round 3 + Final Decisions

Update `discuss.md` with:
- Round 3 responses
- CTO verdicts
- Full decision summary table
- Changes from original design
- Action items
- Member reflections

### Step 8: Shutdown Team

Send `shutdown_request` to all team members. Present final summary to Yuan.

---

## Agent Prompt Templates

### Architect A (Backend) — Round 1

```
你是 [project] 架構審查團隊中的「資深架構師 A（後端架構）」。

## 你的職責範圍
- 模組結構、分層設計、DDD
- ORM/數據存取選型
- 資料庫 Schema 設計
- 批次處理設計
- 錯誤處理策略

## 團隊成員
- `architect-integration`：架構師 B（整合與擴展）
- `security-lead`：資安主管
- `cto`：CTO
- `team-lead`：書記

## 任務
1. 深入閱讀以下檔案：[file list]
2. 給出專業評估（繁體中文）：優點(≥3)、風險(≥3)、改進建議
3. 對其他成員提出問題（每人≥1-2個）
4. 回覆完用 SendMessage 分別發送給對應成員，最後總結給 team-lead
```

### Cross-Response (Round 2+) — Template

```
Round [N] 開始！你已經收到其他成員的追問，請回應以下問題：

## 來自 [Member Name] 的問題：
Q1: [question]
Q2: [question]

## 來自 [Another Member] 的問題：
Q3: [question]

## 爭議待解：[topic]
[Member A] 建議 X，[Member B] 建議 Y。請給出你的最終立場。

請回覆後用 SendMessage 分別發送給對應成員，最後總結給 team-lead。
```

### CTO Final Round — Template

```
這可能是最後一輪。請給出：

1. 爭議裁決結果 + 理由
2. 關鍵共識清單
3. 最終架構建議（原始設計 vs 調整）
4. Sprint 0 / Phase 1 行動項目
5. 需要更新的設計文件清單

請發送完整的最終總結給 team-lead。
```

---

## Key Principles

- **All agents use opus model** — ensures high-quality, deep analysis
- **SendMessage for inter-agent communication** — agents talk directly to each other
- **Secretary records everything** — every round's Q&A and decisions go to discuss.md
- **CTO has final say** — disputes are escalated to CTO for verdict
- **Evidence-based** — agents must read actual files and cite specifics
- **Traditional Chinese** — all discussion in 繁體中文, English only for code/technical terms
- **Multiple rounds** — at least 3 rounds (explore → cross-respond → resolve)
- **Identify and resolve disputes** — don't let disagreements go unresolved

## Discussion Log Format

The `discuss.md` file should be structured for easy review:

```markdown
# [Project] 架構審查討論紀錄
> 日期 / 參與者 / 書記

## Round 1：各自探索 + 提問
### [Each member's assessment + questions]

## Round 2：交叉回應 + 追問
### [Each member's responses in table format]
### Round 2 決策衝突待解

## Round 3：追問回應 + 爭議解決
### [Responses + dispute positions]

## CTO 最終裁決
### [Verdict tables]

## 全部決策彙總
### [Comprehensive decision tables]
### [Changes from original design]
### [Action items]

## 附錄：成員心得
### [Each member's key takeaway]
```

## Scaling the Discussion

- **Simple review (1-2 rounds)**: Skip Round 3, CTO summarizes after Round 2
- **Deep review (4+ rounds)**: Add rounds as needed until CTO determines all issues are resolved
- **Focused review**: Reduce team to 2-3 members focused on specific concerns
- **Broader review**: Add roles (DBA, QA Lead, Frontend Lead, DevOps Engineer) as needed
