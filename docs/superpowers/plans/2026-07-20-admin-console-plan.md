# Implementation Plan: Admin 後台殼層

## Design Reference
- Design doc: `docs/superpowers/specs/2026-07-20-admin-console-design.md`
- Approved by: Yuan（授權主線定案，2026-07-23）

## 執行拓撲

| 流 | Repo | Worktree | 分支 |
|---|---|---|---|
| 後端 | blog-web-v2 | `.worktrees/admin-console` | `feature/admin-console`（既存） |
| 前端 | blog-web-v2-front-end | `.worktrees/admin-console` | `feature/admin-console`（自 `feature/pre-launch-remediation` 切出） |

前端基底**必須**是 `feature/pre-launch-remediation`——ShellRail 與上一批 UI 修復只存在於該分支。完成後 PR 併回該分支。

後端與前端流可平行；前端內部任務有相依順序（見各任務「相依」欄）。
API 契約以 spec §5.1 為準，兩端不互等。

## 環境備忘（subagent 必讀）

- 後端測試：bash PATH 不含 mvn/cmd，須以 `.bat` 執行（PATH 重設 + 絕對路徑），配方見主 repo 記憶檔 `running-maven-tests.md` 的模式：
  `MSYS_NO_PATHCONV=1 /c/Windows/System32/cmd.exe /d /c "<bat>"`，bat 內 `set PATH` 含 `D:\end\Java\jdk-21\bin;D:\end\Java\apache-maven-3.9.15\bin`、`JAVA_HOME=D:\end\Java\jdk-21`，`cd /d <worktree>`，輸出 tee 至 `logs/`。
- 前端測試：`fnm exec --using default -- cmd /c "npx vitest run ..."`（bat 內 PATH 需含 fnm 的 winget 目錄）。
- `.bat` 只能整份 Write 重寫，不可 Edit 逐行改（CRLF/LF 混合會壞）。
- 測試輸出一律存 worktree 的 `logs/`；單一失敗不重跑全套（CLAUDE.md 鐵律）。

---

## 後端任務

### Task A1 — blog-common+blog-module-search / Service: reindex 時間戳與狀態查詢
- **目標**：`RedisKeyConstant` 新增搜尋索引時間戳常數（依既有命名慣例，如 `SEARCH_REINDEX_AT_KEY = "search:reindex:at"`）；`SearchServiceImpl.reindexAll()`（:200-208）完成後寫入 ISO-8601 時間戳；新增 `SearchService.getIndexStatus()` 回傳 `SearchIndexStatusResponse(documentCount, lastReindexAt, healthy)`：`documentCount` 取 `articleSearchRepository.count()`；ES 例外時捕捉，回 `healthy=false`、`documentCount=null`，**不拋出**。
- **TDD**：先寫 `SearchServiceImplTest`：`reindexAll_寫入時間戳`、`getIndexStatus_正常回傳文件數`、`getIndexStatus_ES例外時healthy為false不拋出`、`getIndexStatus_從未重建時lastReindexAt為null`。確認 RED 再實作。
- **相依**：無
- **驗證**：`mvn test -pl blog-module-search -am --no-transfer-progress 2>&1 | tee logs/a1.log`

### Task A2 — blog-module-search / Controller: 狀態端點
- **目標**：`AdminSearchController` 新增 `GET /api/v1/admin/search/status`，`@PreAuthorize("hasAuthority('SYSTEM_CONFIG')")`，委派 `getIndexStatus()`。JavaDoc 繁中。
- **TDD**：先寫 `AdminSearchControllerTest`：`status_回傳結構正確`、`status_無SYSTEM_CONFIG權限拒絕`。確認 RED 再實作。
- **相依**：A1
- **驗證**：`mvn test -pl blog-module-search --no-transfer-progress 2>&1 | tee logs/a2.log`（A1 已測過上游，不必 -am 重跑）

---

## 前端任務

> 前端 worktree 內先讀 `ai-docs/judgment.md`、`ai-docs/code-standards.md`、`ai-docs/design-system.md`（前端 repo 自有治理層）。
> 硬約束（spec §3.2 / §9）：**ShellRail 對外 props/emits 逐字不變；`ShellRail.test.ts` 不得修改且必須全綠**。若發現必須改該測試檔，立即停止回報主線，不要繼續。

### Task A3 — 前端 / Component: 抽 rail presentational base
- **目標**：從 `ShellRail.vue` 抽出無狀態基座元件（建議 `RailBase.vue`）：接受導覽項目、active key、可選樹狀子項；負責版面呈現、展開/收合、768px 收窄。`ShellRail` 改為組合基座並提供原有 NAV_ITEMS 與 settings 樹邏輯，對外介面不變。
- **TDD**：重構型任務——先跑既有測試建綠基線（`ShellRail.test.ts` 等）存 `logs/a3-baseline.log`；為 `RailBase` 新寫單元測試（項目呈現/active 高亮/子項展開收合/emit）；重構後基線與新測試同綠。
- **相依**：無
- **驗證**：`npx vitest run src/components/layout 2>&1 | tee logs/a3.log`

### Task A4 — 前端 / Router+Layout: admin 路由群與 AdminRail
- **目標**：新增 `AdminRail.vue`（組合 RailBase；項目：總覽 `/admin`、待審 `/admin/review`、分類 `/admin/categories`、標籤 `/admin/tags`、搜尋索引 `/admin/search`）。router 新增 `/admin`、`/admin/categories`、`/admin/tags`、`/admin/search` 四條路由（先掛佔位頁），連同既有 `/admin/review` 全部 `meta: { requiresAuth: true, requiredRole: 'ADMIN' }` 並套 admin 殼層版面（AdminRail + 內容區）。`/admin/review` 既有功能與其測試不得壞。
- **TDD**：先寫路由守衛測試（非 ADMIN 進入被導離）與 AdminRail 渲染測試，確認 RED 再實作。
- **相依**：A3
- **驗證**：`npx vitest run src/components/layout src/router src/views/AdminReviewView.test.ts 2>&1 | tee logs/a4.log`（AdminReviewView 若無既有測試檔則跑全套 vitest）

### Task A5 — 前端 / API 層: admin 完整讀取方法
- **目標**：`adminService` 新增不收窄的讀取：`getCategoriesFull(): Promise<CategoryResponse[]>`（直接映射後端完整欄位，含 `description`/`sortOrder`）、`getTagsFull(): Promise<AdminTagResponse[]>`（含 `color`/`icon`/`description`/`usageCount`）、`getSearchStatus(): Promise<SearchIndexStatus>`（對應 spec §5.1 契約）。**不動既有 `categoryService.getCategories()` / `tagService.getAllTags()`**（編輯器等既有呼叫端在用）。
- **TDD**：先寫 `adminService.test.ts` 對應方法測試（mock apiClient，驗欄位不被丟棄），確認 RED 再實作。
- **相依**：無（可與 A3/A4 平行）
- **驗證**：`npx vitest run src/api 2>&1 | tee logs/a5.log`

### Task A6 — 前端 / View: 總覽儀表板 `/admin`
- **目標**：四格指標卡：待審數（`getPendingCount`）、分類數、標籤數（A5 方法）、搜尋索引（`getSearchStatus`；`healthy=false` 顯示「ES 離線」、`lastReindexAt=null` 顯示「從未重建」）。各格載入/錯誤狀態獨立，一格失敗不拖垮整頁。點擊格子導向對應子頁。
- **TDD**：先寫 view 測試（四格渲染真實 mock 資料、ES 離線態、單格錯誤隔離），確認 RED 再實作。
- **相依**：A4、A5
- **驗證**：`npx vitest run src/views 2>&1 | tee logs/a6.log`

### Task A7 — 前端 / View: 分類管理 `/admin/categories`
- **目標**：表格（名稱/slug/描述/排序）+ 新增/編輯表單（四欄）+ 刪除確認對話框。刪除失敗時**必須呈現後端業務錯誤**（`CATEGORY_HAS_ARTICLES` → 明確訊息「該分類下仍有文章」），不可靜默。成功操作後重拉清單。
- **TDD**：先寫 view 測試（清單渲染含 description/sortOrder、新增/編輯提交 payload 正確、刪除確認流程、業務錯誤呈現），確認 RED 再實作。
- **相依**：A4、A5
- **驗證**：`npx vitest run src/views 2>&1 | tee logs/a7.log`

### Task A8 — 前端 / View: 標籤管理 `/admin/tags`
- **目標**：表格（名稱/slug/文章數/顏色/圖示/描述）。編輯**僅** `color`/`icon`/`description`（`UpdateTagRequest` 只有這三欄；name/slug 唯讀呈現，不給輸入框）。刪除：`usageCount > 0` 預先禁用按鈕 + 後端 `TAG_IN_USE` 錯誤兜底呈現。
- **TDD**：先寫 view 測試（唯讀欄無輸入框、編輯 payload 僅三欄、usageCount>0 按鈕禁用、TAG_IN_USE 呈現），確認 RED 再實作。
- **相依**：A4、A5
- **驗證**：`npx vitest run src/views 2>&1 | tee logs/a8.log`

### Task A9 — 前端 / View: 搜尋索引 `/admin/search`
- **目標**：顯示狀態（文件數/最後重建時間/健康）+「重建索引」按鈕：確認對話框 → 執行中禁用 + 進行中指示 → 完成後重拉狀態並回饋。失敗呈現錯誤。
- **TDD**：先寫 view 測試（狀態渲染、確認流程、執行中禁用、完成後重拉），確認 RED 再實作。
- **相依**：A4、A5
- **驗證**：`npx vitest run src/views 2>&1 | tee logs/a9.log`

### Task A10 — 前端 / 整合回歸: 全套綠 + 殼層一致性
- **目標**：跑全套 vitest 確認無回歸；確認 `/admin/review` 在殼層下原功能不變；`ui-layout-regression` 相關斷言不受 rail 重構影響（單元層面能驗的部分）。
- **TDD**：純驗證任務。
- **相依**：A6、A7、A8、A9
- **驗證**：`npx vitest run 2>&1 | tee logs/a10.log`

---

## 主線驗收協定（依 ai-docs/judgment.md §7、agent-dispatch.md「驗證不自驗」）

每個任務回報後主線抽查：RED log 確有失敗紀錄、測試名稱與場景表對得上、diff 未越界（尤其 A3 不得動 `ShellRail.test.ts`、A5 不得動既有 service 方法）。後端最終以 surefire XML 為證據；前端以 vitest 輸出為證據。全部完成後主線於瀏覽器實點驗證（admin 五頁 + 儀表板資料真實性），再回報 Yuan。
