# 制度維護協議(Maintenance)

> 讀者:在此 repo 工作的任何 AI session + Yuan。定義:學習怎麼流動、誰能改哪些檔、常載層怎麼保持薄。

## 1. 學習晉升飛輪(唯一的一條學習管道,不新建第二套)

```
臨時觀察/踩雷
  → 修完 bug 後跑 /post-bug → ai-docs/bug-reports/(結構化 post-mortem)
    → 預防措施「當場分流」:
        文件類(改規範就能防)→ 立即晉升進對應 ai-docs 檔,狀態標 DONE(日期+目標檔)
        程式類(要寫測試/工具)→ 開 ai-docs/backlog/ 項目,狀態標 TODO(待排入 backlog)
  → 每累積 3 份報告、或出現 HIGH bug 後、或上線前 → 跑 /review-bugs 做模式分析
    → 重複出現的坑 → 晉升為規範條文(code-standards / security / testing-standards / architecture)
    → 判斷類教訓(非機械規則)→ 寫進 ai-docs/judgment.md(一正例一反例)
    → 已硬化且常被違反的規則 → 才考慮升級為常載(CLAUDE.md)一行路由或鐵律
```

**鐵律:預防措施不准以 TODO 狀態沉睡。** 每次 `/review-bugs` 必須盤點所有報告中殘留的 TODO 措施:要嘛晉升、要嘛移入 backlog、要嘛明寫「擱置+理由」。
(教訓來源:BUG-2026-001 的 5 條措施懸置 2026-03-21 → 2026-07-07 未落地;該報告自己寫著「未文件化的最佳實踐等於沒有最佳實踐」。)

## 2. 檔案所有權表(動檔前先查這張表)

| 檔案 | AI 可自行改? | 規則 |
|------|-------------|------|
| `ai-docs/schema.md` | ✅ 必須改 | 每個新 migration 同步(CLAUDE.md「Schema Maintenance」) |
| `ai-docs/bug-reports/*`、`ai-docs/backlog/*`、`ai-docs/integration-tests/*` | ✅ | 照 skill 流程產生/更新 |
| `ai-docs/judgment.md`、`ai-docs/task-briefs.md`、`ai-docs/agent-dispatch.md`、本檔 | ⚠️ 提案制 | 可草擬修改,合併前給 Yuan 過目 |
| `ai-docs/code-standards.md`、`testing-standards.md`、`git-convention.md`、`architecture.md` | ⚠️ 提案制 | 收緊規則可提案;放寬規則必問 |
| `ai-docs/security.md` | ❌ 先問 | 任何變更(尤其公開端點表、權限)先問 Yuan |
| `CLAUDE.md` | ❌ 先問 | 常載層,改一行影響所有 session |
| `AGENTS.md`、`GEMINI.md` | ❌ 永不直編 | 指標檔,內容以 CLAUDE.md 為唯一真相(見 §3) |
| `.claude/skills/*` | ❌ 先問 | 程序變更影響所有工作流 |
| `blog-db-migration` 已套用的 `V*.sql` | ❌ 永不改 | 見 `judgment.md` §3 |

## 3. 常載路由層規則

- **CLAUDE.md 是唯一真相**,上限 150 行,只放:身分/溝通原則、鐵律級短規則、索引(純文字/markdown 連結,不用 `@` import——`@` 是開場全載,不是按需)。
- **AGENTS.md 與 GEMINI.md 是純指標檔**(只有「去讀 CLAUDE.md」一件事),永不放內容。歷史教訓:三檔手動同步,GEMINI.md 已漂移(少了 Test Execution Rules 等三節)才改成此制。
- 新的長內容一律寫 `ai-docs/` 新檔,CLAUDE.md 只加一行索引。
- 新增 repo root 的 `*.md` 前先想:它屬於 ai-docs 哪一類?root 散檔是文件腐化的起點。

## 4. 何時做成 skill(判準,照抄制度模板)

- 事實/規則 → 寫進 `ai-docs/` 文件(convention)
- 多步驟程序 + 弱模型會做錯 + 沒有現成指令 → 才做 `.claude/skills/`
- 已有現成指令/skill(如 `/post-bug`、`/review-bugs`、`/git-action`)→ 別包一層重造

## 5. 制度健康檢查(每次 /review-bugs 順手做,5 分鐘)

1. CLAUDE.md 是否仍 ≤150 行、索引連結是否都指向存在的檔(壞連結=斷線)
2. AGENTS.md / GEMINI.md 是否仍是指標檔(有人塞內容就恢復)
3. bug-reports 各報告有無沉睡的 TODO 措施
4. `ai-docs/backlog/` 有無過期項目(完成了沒關掉、或已無意義)
