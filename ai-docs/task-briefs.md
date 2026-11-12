# 任務交辦範本(Task Briefs)

> 讀者:主線 session。派任何 subagent 前,從下面五種範本挑一種,填空後整段作為 prompt。
> 格式對齊 `.claude/skills/subagent-driven-development/SKILL.md`:工作目錄契約永遠是 prompt 第一行。
> 調度規則(選哪個模型、何時升級)見 `ai-docs/agent-dispatch.md`。

## 共通頭(每個範本都以此開場)

```
Working directory: <WORKTREE_FULL_PATH>
All commands (mvnw, git, file edits) MUST be run from this directory.
Do NOT operate on the main repository root.
---
```

## 1. 搜尋/盤點(Search)

```
【目標與動機】盤點 <什麼> 的所有出現位置,因為 <為什麼需要>。
【範圍】模組:<模組清單或全部>;檔案類型:<*.java / *.sql / ...>
【禁區】只讀不改。
【驗收條件】結果涵蓋所有模組(列出你掃過的模組清單以茲證明);每筆附 file:line。
【回報格式】一張表:file:line | 片段(≤1行) | 分類。超過 30 筆 → 寫入 <輸出檔路徑>,只回摘要統計 + 路徑。
```

## 2. 實作(Implement,TDD 強制)

```
【目標與動機】實作 <功能/修復>,對應 <plan 檔/issue 的路徑>。
【規範】遵守 ai-docs/code-standards.md、security.md、testing-standards.md;先讀與本任務相關的章節。
【流程】TDD 鐵律:
  1. Boundary Analysis:先產出測試場景表(class, method, scenarios)
  2. RED:寫失敗測試,跑 `./mvnw test -pl <module> -am --no-transfer-progress 2>&1 | tee logs/<task>-red.log` 確認真的失敗
  3. GREEN:最小實作使其通過,再跑確認
  4. REFACTOR:保持綠的前提下整理
【禁區】不改 <模組/檔案> 之外的東西;不弱化既有測試;不碰已套用的 migration 檔。
【驗收條件】場景表齊全;RED 有失敗證據;最終測試綠(surefire 路徑);輸出 pristine。
【回報格式】場景表 + 測試名稱與結果 + 變更檔案清單(file:line 級)+ log 檔路徑。
```

## 3. 重構(Refactor)

```
【目標與動機】把 <現況模式> 重構為 <目標模式>,依據 <規範章節/報告>。
【前置】先跑受影響模組測試建立綠基線:<指令>,存 logs/<task>-baseline.log。
【禁區】行為不得改變(公開 API、DB schema、MQ 訊息格式凍結);不新增功能。
【驗收條件】基線綠 → 重構後同樣綠;無新增測試豁免;diff 只含重構範圍。
【回報格式】重構前後對照(每類一例)+ 變更檔案清單 + 兩份 log 路徑。
```

## 4. 研究(Research)

```
【目標與動機】回答 <具體問題>,供 <什麼決策> 使用。
【材料】先讀:<repo 內相關檔案>;外部查證:<官方文件範圍>。版本號/API 行為必須查證,不憑記憶。
【驗收條件】每個結論附來源(repo 內 file:line 或外部 URL);查不到的明確標「未確認」,不編造。
【回報格式】結論(≤5 條)→ 各附證據 → 「未確認」清單 → 建議(若被問)。長篇分析寫入 <輸出檔路徑>。
```

## 5. 審查(Review)

```
【目標與動機】審查 <diff 範圍/PR>,聚焦 <正確性/安全/邊界>。
【檢查清單】對照:ai-docs/judgment.md §2 危險模式訊號表(逐條 grep 驗證)、
  code-standards.md(尤其 Transaction+MQ、Error Handling)、security.md(原則 1/7/8)、
  architecture.md 跨模組邊界、testing-standards.md(測試命名/覆蓋)。
【驗收條件】每條 finding 附 file:line + 違反的規範條文 + 建議修法;無 finding 也要列「檢查過什麼」。
【回報格式】severity 分級表(CRITICAL/HIGH/MEDIUM/LOW),按 severity 排序;不確定的標 PLAUSIBLE 而非斷言。
```

## 驗收與回收(主線的責任)

- 收到回報先抽查(`ai-docs/judgment.md` §7),再標完成
- 升級門檻依模型分層(Haiku 錯一次/Sonnet 同任務連錯兩次),照 `ai-docs/agent-dispatch.md` 升降級路徑執行,勿在此另記數字
