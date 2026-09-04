## Addressing & Roles

*   Always address me as **"Yuan"**.
*   We are **Colleagues**: You bring logic and rigor; I bring business context. We solve problems together.
*   Always response in **Traditional Chinese**. Use English only for code, technical terms, or when quoting documentation.

## Communication Principles

1.  **Ask before acting**: Don't verify invalid assumptions.
2.  **Evidence-based**: Show me logs, documentation, or test outputs to back up claims.

# CRITICAL INSTRUCTION: TDD IS MANDATORY

> **NO CODE WITHOUT TESTS.**
> You MUST write a failing test BEFORE writing any implementation code.
> This is a hard constraint. Do not optimize, do not "just fix it quickly".
> Follow the cycle: **Red -> Green -> Refactor**.

## TDD Implementation Process

1.  **Red**: Write a failing test that defines the desired functionality or bug fix.
    *   Run the test to confirm it fails.
2.  **Green**: Write the *minimal* amount of code to make the test pass.
    *   Run the test to confirm success.
3.  **Refactor**: Improve the code structure/quality while keeping tests green.

## Test Execution Rules

1.  **Save output to file**: Always redirect test output to `./logs/` (e.g., `./mvnw test ... 2>&1 | tee logs/test-output.log`). This avoids re-running the entire suite just to check results.
2.  **Check previous results first**: Before re-running tests for a specific failure, **always** check the last run's `logs/test-output.log` or Surefire XML reports (`<module>/target/surefire-reports/TEST-*.xml`) first.
3.  **Never re-run the full suite for a single failure**: Read the existing report, identify the root cause, fix it, then run only the affected test class.
4.  **Surefire reports location**: `<module>/target/surefire-reports/TEST-*.xml` — use these for structured results instead of re-executing.

---

## External Repositories

- Infrastructure (Docker, k3s, deployment configs): `D:\end\workspace\infrastructure`
- Frontend (Vue 3 SPA, API contract counterpart): `D:\end\workspace\vue\blog-web-v2-front-end`

## Rules File Convention

`CLAUDE.md`(本檔)是 AI 規則的**唯一真相**。root 的 `AGENTS.md` 與 `GEMINI.md` 只是指標檔——永不直接編輯它們,規則變更一律改本檔(見 [ai-docs/maintenance.md](ai-docs/maintenance.md) §3)。

## Schema Maintenance

每次新增 Flyway migration（V{N+1}）時，**必須同步更新 `ai-docs/schema.md`**：
- 新增表 → 加完整 table 區塊（columns / indexes / FKs）
- ALTER 既有表 → 更新對應區塊的欄位列表
- 索引調整（ADD / DROP）→ 更新 Indexes 列表
- 在 Migration Index 末尾補一行 V{N+1} 描述

這條規則保證 `ai-docs/schema.md` 是真相版本，避免疊讀 V1..V{N} 才能瞭解當前結構。

## Guidelines Index

- Architecture and Design: [ai-docs/architecture.md](ai-docs/architecture.md)
- Database Schema: [ai-docs/schema.md](ai-docs/schema.md)
- Code Standards: [ai-docs/code-standards.md](ai-docs/code-standards.md)
- Git Commits: [ai-docs/git-convention.md](ai-docs/git-convention.md)
- Testing Standards: [ai-docs/testing-standards.md](ai-docs/testing-standards.md)
- Security and Permissions: [ai-docs/security.md](ai-docs/security.md)
- Flyway Migration Convention: [ai-docs/flyway-convention.md](ai-docs/flyway-convention.md)
- Integration Tests Log: [ai-docs/integration-tests/](ai-docs/integration-tests/)

## Operating Rules Index(判斷與調度,做任何非平凡任務前先讀前兩份)

- Project Judgment(何時停/問/換路,危險模式訊號): [ai-docs/judgment.md](ai-docs/judgment.md)
- Agent Dispatch(模型調度、升降級、驗證不自驗): [ai-docs/agent-dispatch.md](ai-docs/agent-dispatch.md)
- Task Briefs(subagent 交辦範本 ×5): [ai-docs/task-briefs.md](ai-docs/task-briefs.md)
- Maintenance(學習晉升飛輪、檔案所有權表): [ai-docs/maintenance.md](ai-docs/maintenance.md)
- Bug Post-mortems(踩雷紀錄,新任務前查同域舊坑): [ai-docs/bug-reports/INDEX.md](ai-docs/bug-reports/INDEX.md)
- Backlog: [ai-docs/backlog/](ai-docs/backlog/)
- Audits(稽核推導過程,動 findings.md 任何一條前先讀對應原始報告): [ai-docs/audits/](ai-docs/audits/)
- Institution Notes(給未來 session 的信): [ai-docs/institution-notes.md](ai-docs/institution-notes.md)
