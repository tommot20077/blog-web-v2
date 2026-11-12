# Implementation Plan: 文章章節導覽（TOC）

## Design Reference
- Design doc: `docs/superpowers/specs/2026-07-20-article-toc-design.md`
- Approved by: Yuan（授權主線定案，2026-07-23）

## 執行拓撲

| 流 | Repo | Worktree | 分支 |
|---|---|---|---|
| 後端 | blog-web-v2 | `.worktrees/article-toc` | `feature/article-toc`（既存） |
| 前端 | blog-web-v2-front-end | `.worktrees/article-toc` | `feature/article-toc`（自 `feature/pre-launch-remediation` 切出） |

前端基底**必須**是 `feature/pre-launch-remediation`——`ArticleDetail.vue` 的現行版本（含 `.art-nav-dot`）只在該分支。完成後 PR 併回該分支。

兩流可平行：契約以 spec §3 為準（`toc: [{id, text, level}]`，空文章為 `[]` 非 null，`level ∈ {2,3}`，id 格式 `^heading-[\p{L}\p{N}-]{1,64}$`）。前端先以 mock 契約開發，不等後端。

## 環境備忘（subagent 必讀）

- 後端測試：bash PATH 不含 mvn/cmd，須以 `.bat` 執行（PATH 重設 + 絕對路徑），bat 內 `set PATH` 含 `D:\end\Java\jdk-21\bin;D:\end\Java\apache-maven-3.9.15\bin`、`JAVA_HOME=D:\end\Java\jdk-21`，`cd /d <worktree>`，經 `MSYS_NO_PATHCONV=1 /c/Windows/System32/cmd.exe /d /c "<bat>"` 呼叫，輸出 tee 至 `logs/`。
- 前端測試：`fnm exec --using default -- cmd /c "npx vitest run ..."`。
- `.bat` 只能整份 Write 重寫，不可 Edit 逐行改。
- 單一失敗不重跑全套（CLAUDE.md 鐵律）。

---

## 後端任務

### Task T1 — blog-module-article / Service: 渲染器產 TOC 與 heading id
- **目標**：`ArticleMarkdownRenderer` 簽章改為 `RenderResult render(String markdown)`，`record RenderResult(String html, List<TocEntry> toc)`、`record TocEntry(String id, String text, int level)`。TOC 自 flexmark **AST** 走訪 `Heading` 節點取得（僅 h2/h3）；HTML 中對應標題注入 `id="heading-<slug>"`（Unicode slug：保留字母/數字，空白與其餘字元轉連字號，截 64 字元）；同文重複標題附序號去重（`heading-安裝步驟-2`）；sanitizer allowlist 新增 `id` 僅限 h2/h3、pattern `^heading-[\p{L}\p{N}-]{1,64}$`。`toPlainText` 不變。null 入 → null 出；空字串 → `RenderResult("", List.of())`。
- **⚠ 先驗證再實作（spec §4.1 明訂）**：flexmark 產 heading id 的 API 未實測（預期 `HtmlRenderer.Builder` 掛自訂 `HtmlIdGenerator`）。第一步寫最小驗證測試確認 API 行為；若不可行，退路是渲染後按 AST 清單順序比對注入 id。把驗證結果寫進回報。
- **TDD**：先寫 `ArticleMarkdownRendererTest` 新場景：`h2h3產生正確id`、`中文標題slug不退化為空`、`重複標題去重`、`h1h4不進TOC`、`無heading回空清單`、`使用者注入的id被剝除`（`<h2 id="app">` → id 被剝）、`既有XSS防護不回歸`。確認 RED 再實作。
- **相依**：無
- **驗證**：`mvn test -pl blog-module-article -am --no-transfer-progress 2>&1 | tee logs/t1.log`

### Task T2 — blog-db-migration / Migration: V19 加 toc 欄位
- **目標**：`V19__add_article_toc.sql`：`ALTER TABLE articles ADD COLUMN toc TEXT;`（nullable、無 DEFAULT）。**同步更新 `ai-docs/schema.md`**（CLAUDE.md 強制）：articles 欄位區塊 + Migration Index 末尾補 V19。不動 V1–V18。
- **TDD**：migration 由 T3 的既有測試載入流程覆蓋（模組測試 schema 若獨立維護，一併同步該檔——先查 `blog-module-article/src/test/resources/db/migration/` 是否有 per-module 測試 schema，有則同步）。
- **相依**：無
- **驗證**：`mvn test -pl blog-db-migration --no-transfer-progress 2>&1 | tee logs/t2.log`（若該模組無測試則以 T3 驗證涵蓋）

### Task T3 — blog-module-article / Service: create/update 持久化 TOC
- **目標**：`Article` model 加 `@Column("toc") private String toc;`。`ArticleCommandSubService` create（:69 附近）與 update（:142 附近）改接 `RenderResult`，同時 set `contentHtml` 與 `toc`（TOC 序列化為 JSON 字串；用 repo 既有 JSON 工具/Jackson）。注意 Spring Data JDBC 對未設值欄位送顯式 NULL——兩條路徑都必須顯式 set。
- **TDD**：先改 `ArticleCommandSubServiceTest`（:114、:1549 的 mock 改回傳 `RenderResult`）並新增 `create持久化toc`、`update持久化toc` 場景，確認 RED 再實作。
- **相依**：T1、T2
- **驗證**：`mvn test -pl blog-module-article --no-transfer-progress 2>&1 | tee logs/t3.log`

### Task T4 — blog-module-article / DTO+Mapper: API 回傳結構化 toc
- **目標**：`ArticleResponse` 與 `EditorArticleResponse` 新增 `List<TocEntry> toc`；`ArticleResponseMapper` 的 `toResponse`（:45）與 `toEditorResponse`（:83）反序列化 DB 字串為 `List<TocEntry>`——空值/解析失敗回**空陣列**不拋例外。`toSummaryResponse` **不加**。
- **TDD**：先寫 `ArticleResponseMapperTest` 場景：`toc正常反序列化`、`toc為null回空陣列`、`toc解析失敗回空陣列`、`summary不含toc`。確認 RED 再實作。
- **相依**：T3
- **驗證**：`mvn test -pl blog-module-article --no-transfer-progress 2>&1 | tee logs/t4.log`

### Task T5 — blog-module-article / Controller IT: 端到端契約
- **目標**：`ArticleControllerIT` 新增：建立含 h2/h3 的文章 → GET 詳情 → 回應 `toc` 為結構化陣列、id 符合 `^heading-` 格式、順序與文件一致；無 heading 文章 → `toc` 為 `[]`。
- **TDD**：IT 本身即測試。
- **相依**：T4
- **驗證**：`mvn test -pl blog-module-article --no-transfer-progress 2>&1 | tee logs/t5.log`（需 Docker Desktop 執行中）

---

## 前端任務

> 前端 worktree 內先讀前端 repo 自有 `ai-docs/judgment.md`、`ai-docs/code-standards.md`、`ai-docs/design-system.md`。
> `src/assets/design/*.css` 是 designer source **不可編輯**；要覆寫在 `src/index.css` patches 區塊做。

### Task T6 — 前端 / Component: ArticleToc 側欄元件
- **目標**：新增 `ArticleToc.vue`：props 接 spec §3 契約的 `toc` 陣列 + 當前 active id；h2 平排、h3 縮排；點擊 emit 選取事件；`toc` 空陣列時整個不渲染（無空殼）。型別定義加進文章詳情的 API 型別（`toc: TocEntry[]`）。
- **TDD**：先寫 `ArticleToc.test.ts`：層級縮排、空陣列不渲染、點擊 emit 正確 id、active 高亮。確認 RED 再實作。
- **相依**：無（契約為 spec §3，不等後端）
- **驗證**：`npx vitest run src/components 2>&1 | tee logs/t6.log`

### Task T7 — 前端 / View: ArticleDetail 整合
- **目標**：移除 `.art-nav` 三顆 dot 與相關 CSS（`ArticleDetail.vue:221-224` 一帶）；掛 `ArticleToc`（讀 API 回應的 `toc`；後端未上線前欄位缺失時視為 `[]`，不噴錯）；點擊 → `id` 平滑捲動；scroll-spy（IntersectionObserver 或 scroll listener）更新 active；深連結：進站帶 `#heading-xxx` 時捲至該標題——**須驗證與 router `scrollBehavior` 相容**（該處先前有「無條件 top:0 吃掉 hash」的已修缺陷，本次不可回歸）。
- **TDD**：先寫 `ArticleDetail` 相關測試：dot 已移除（querySelector 零命中）、toc 傳遞正確、缺 `toc` 欄位不噴錯。scroll-spy 以可測邏輯抽離（composable 或純函式）單測。確認 RED 再實作。
- **相依**：T6
- **驗證**：`npx vitest run src/views src/components 2>&1 | tee logs/t7.log`

### Task T8 — 前端 / 整合回歸: 全套綠
- **目標**：全套 vitest 綠；既有 `ui-layout-regression` / `auth-ui-regression` 的錨點相關不變量在單元層面不受影響。
- **TDD**：純驗證任務。
- **相依**：T7
- **驗證**：`npx vitest run 2>&1 | tee logs/t8.log`

---

## 主線驗收協定

每任務回報後抽查：RED log 確有失敗、測試名稱對得上場景表、diff 未越界（T1 尤其：`toPlainText` 與既有 4 個 `checkWritePermission` 呼叫點不得動；T7：不得動 `src/assets/design/*.css`）。T5 後主線以 curl 對本地後端實測契約；T8 後主線瀏覽器實點（含深連結進站、scroll-spy、暗色模式下的 TOC 對比）。安全相關（T1 sanitizer 變更）依 agent-dispatch「高風險判斷第二意見」：完成後另派 review agent 從攻擊者視角審一次。
