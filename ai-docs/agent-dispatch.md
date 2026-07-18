# Agent 調度守則(Model Dispatch)

> 讀者:在此 repo 工作的任何 AI session(含較小模型)。目標:把貴的 context 花在判斷上,把便宜的 context 花在廣度上。
> 姊妹檔:前端 repo `D:\end\workspace\vue\blog-web-v2-front-end\ai-docs\agent-dispatch.md`(獨立維護,不要求逐字同步)。

## 現役模型速查(2026-07 查證;會過時,調度前先確認實際可用型號,查不到就寫「待查」,不要憑記憶編)

| 模型 | 定位 | 用在 |
|------|------|------|
| Fable 5(`claude-fable-5`) | 最高階、額度稀缺 | 立制度、架構級判斷、一次性難題;不做日常任務 |
| Opus 4.8(`claude-opus-4-8`) | 高階 | 複雜實作、跨模組重構、難 bug 根因 |
| Sonnet 5(`claude-sonnet-5`) | 日常主力 | 單模組實作、測試修復、review、文件 |
| Haiku 4.5(`claude-haiku-4-5-20251001`) | 便宜快速 | 批次 grep/掃描、機械性套用已知模式、格式整理 |

## 分工原則:指揮官不下場

主線 session 負責:讀關鍵一手材料(規範檔、失敗的測試報告、待判斷的 diff)、做決策、驗收。
以下工作一律派 subagent,主線只收結論:

- 廣度掃描(多模組 grep、找呼叫點、盤點現況)
- 批次改檔(同一模式套用到 N 個檔案)
- 跑測試與讀 surefire 報告(遵守 CLAUDE.md「Test Execution Rules」:輸出 tee 到 `logs/`、單一失敗不重跑全套)
- 驗證他人(或自己)的產出

## 交辦三要素(缺一不派)

每個 subagent prompt 必含:

1. **目標與動機**:做什麼、為什麼(含 worktree 工作目錄契約,見 `ai-docs/task-briefs.md`)
2. **驗收條件**:可機械判定的完成標準(測試綠、檔案存在、grep 零命中……)
3. **回報格式**:只回結論 + `file:line` 證據;長產物寫檔、回傳路徑;禁止把整份檔案內容貼回主線

範本見 `ai-docs/task-briefs.md`(搜尋/實作/重構/研究/審查五種)。

## 回報合約(subagent 端)

- 結論先行,一段講完;證據用 `file:line`
- 產出超過 ~30 行 → 寫檔(路徑照任務指定),回報只給路徑 + 三行摘要
- 做不到就明說做不到 + 卡在哪,不要交「看起來像完成」的半成品

## 升降級路徑

- **升級**:Haiku 級錯一次 → 換 Sonnet 重派。Sonnet 級**同一子任務連錯兩次** → 帶完整失敗軌跡(兩次的 prompt、產出、驗收失敗原因)升 Opus。
- **降級**:高階模型解出模式後,把模式寫成明確步驟,降回便宜模型批次套用。
- **止損**:同一件事最多兩輪升級。仍不過 → 停下,把失敗軌跡整理好問 Yuan,不要無限重試。

## 驗證不自驗

產出者不能當自己的驗收者:

- 檔案類 → 派 fresh-context agent read-back(檔案存在、內容完整、規則可執行)
- 程式類 → 跑測試,以 surefire XML / `logs/test-output.log` 為證據,不接受「應該會過」
- 高風險判斷(安全、migration、跨模組介面)→ 第二意見:再派一個 agent 從反方立場審一次,或直接問 Yuan

## 本 repo 的成本地雷(java 特有)

- 全套 Maven suite 很貴:**永不**為單一失敗重跑全套(CLAUDE.md「Test Execution Rules」是鐵律)
- `ai-docs/schema.md`(585 行)與 `docs/` 深檔是按需引用,不要叫 subagent「把整份讀回來」,叫它回結論
- E2E(`e2e/`)最貴,只在契約層變更或上線前跑
