# Implementation Plan: 檔案存取控制（草稿圖片不公開）

## Design Reference
- Design doc: `docs/superpowers/specs/2026-07-26-file-access-control-design.md`
- Approved by: Yuan（2026-07-26，含 §3.1 相對路徑決策）

## 執行拓撲

| 流 | Repo | Worktree | 分支 |
|---|---|---|---|
| 後端 | blog-web-v2 | `.worktrees/minio` | `feature/file-access-control`（既存，已含 spec commit） |
| 前端 | blog-web-v2-front-end | `.worktrees/file-access` | `feature/file-access-control`（自 `feature/pre-launch-remediation` 切出） |

**相依順序**：F1（前端）僅需契約（相對路徑格式），可與後端平行；F2 需 B4 完成後才能端到端驗。

## 環境備忘（subagent 必讀）

- 後端測試：bash PATH 無 mvn/cmd，須以 `.bat` 執行（PATH 含 `D:\end\Java\jdk-21\bin;D:\end\Java\apache-maven-3.9.15\bin`、`JAVA_HOME`），經 `MSYS_NO_PATHCONV=1 /c/Windows/System32/cmd.exe /d /c "<bat>" < /dev/null` 呼叫，輸出 tee 至 `logs/`。
- **改 migration 後必須 `mvn install -pl blog-db-migration -DskipTests` 更新 .m2**，否則各模組 IT 會載入舊 jar，症狀為 `column "xxx" does not exist`（本專案已踩過）。
- `.bat` 只能整份 Write 重寫，不可 Edit 逐行改；診斷用 `.bat` 內容一律 ASCII（中文會編碼錯亂）。
- 前端測試：`fnm exec --using default -- cmd /c "npx vitest run ..."`。
- 既有 flaky（非新引入）：全套並行時 `tagService.test.ts` / `useSettings.test.ts` / `authService.test.ts` 偶有 5000ms 逾時，隔離重跑即綠。

---

## 後端任務

### Task B1 — blog-db-migration / Migration: V20 加 article_uuid
- **目標**：`V20__add_file_metadata_article_uuid.sql`：
  ```sql
  ALTER TABLE file_metadata ADD COLUMN article_uuid UUID;
  CREATE INDEX idx_file_metadata_article_uuid ON file_metadata (article_uuid);
  ```
  nullable、**刻意不設 FK**（跨模組邊界，與 `articles.cover_image_url` 用字串的既有取捨一致，於註解說明）。
  **同步更新 `ai-docs/schema.md`**（file_metadata 欄位區塊 + Migration Index 補 V20）——CLAUDE.md 強制規定。
- **版號注意**：develop 最大為 V18，`feature/article-toc` 已佔用 V19；本批次用 **V20**，不可衝號。
- **TDD**：migration 由 B2/B3 的測試載入流程覆蓋；本任務以 SQL 靜態檢查 + V1–V19 零 diff 佐證。
- **相依**：無
- **驗證**：`mvn install -pl blog-db-migration -DskipTests` 成功；`git diff` 顯示 V1–V19 未被修改。

### Task B2 — blog-module-file / Model+Service: 綁定與授權判斷
- **目標**：
  1. `FileMetadata` model 加 `articleUuid` 欄位（`@Column("article_uuid")`）。**Spring Data JDBC 對未設值欄位送顯式 NULL，既有 create 路徑須確認不會誤清**（本專案已多次踩此坑）。
  2. `FileService` 新增 `bindToArticle(UUID articleUuid, List<UUID> fileUuids)`：把指定檔案綁到文章；**同一文章重新綁定時，先解除該文章既有但不在清單內的綁定**（避免文章移除某圖後權限殘留）。
  3. `FileService` 新增 `canRead(UUID fileId, Long requesterId, Role requesterRole)`（簽章依既有慣例調整）：依 spec §4 授權矩陣判斷。取得「文章是否已發布」須透過 **ArticleFacade**（已存在），不得直接查 article 表。
- **TDD**：先寫 `FileServiceImplTest`：AVATAR 匿名可讀／已綁定+PUBLISHED 匿名可讀／已綁定+DRAFT 匿名與他人皆拒、上傳者與 ADMIN 可讀／未綁定僅上傳者與 ADMIN／檔案不存在。以及 bindToArticle 的「重新綁定會解除舊綁定」。確認 RED。
- **相依**：B1
- **驗證**：`mvn test -pl blog-module-file -am --no-transfer-progress 2>&1 | tee logs/b2.log`

### Task B3 — blog-infrastructure + blog-module-file / Facade: FileFacade
- **目標**：新增 `FileFacade` 介面（定義於 `blog-infrastructure`，比照既有 6 個 facade 的風格與位置），提供 `bindFilesToArticle(UUID articleUuid, List<UUID> fileUuids)`；實作於 `blog-module-file`。
  遵守 `ai-docs/architecture.md`：「Modules interact ONLY via Service Interfaces」。
- **TDD**：先寫 facade 實作的測試（委派正確、參數透傳）。確認 RED。
- **相依**：B2
- **驗證**：`mvn test -pl blog-module-file -am --no-transfer-progress 2>&1 | tee logs/b3.log`

### Task B4 — blog-module-file / Controller: 代理端點
- **目標**：`GET /api/v1/files/{id}/content`：
  - 呼叫 `canRead(...)` 判斷；通過 → **302 redirect** 至 MinIO presigned URL（效期 5 分鐘，用 MinIO SDK `getPresignedObjectUrl`）；拒絕 → 403；檔案不存在 → 404。
  - **必須回 302，不可回傳圖片 bytes**（設計目的即為避免流量經過應用伺服器）。
  - `SecurityConfig` 需允許匿名到達此端點（才能判斷 spec §4 規則 1、2），但**授權判斷在 service 層，不可因 permitAll 而跳過**。請於該處加註解說明此意圖，避免日後被誤讀為「這是公開端點」。
  - **`uploadFile` 回傳的 url 改為相對路徑** `/api/v1/files/{id}/content`（spec §3.1），不再回傳 MinIO 直連網址；接受可選的 `articleUuid` 參數，有值即綁定。
- **TDD**：先寫 `FileControllerTest`/IT：授權矩陣每條各一（含反例）、回應為 302 且 `Location` 指向 presigned URL、上傳回傳相對路徑。確認 RED。
- **相依**：B2
- **驗證**：`mvn test -pl blog-module-file -am --no-transfer-progress 2>&1 | tee logs/b4.log`

### Task B5 — blog-infrastructure / Config: bucket 維持私有 + 回歸護欄
- **目標**：`MinioConfig` **不得**設定 bucket 為公開讀取。新增測試斷言：`ensureBucket` 不會呼叫 `setBucketPolicy` 授予匿名讀取（或若有設定 policy，其內容不含 `s3:GetObject` 給 `Principal: *`）。
  **此為回歸護欄**：防止日後有人遇到「圖片看不到」就改回 public-read，把整套權限控制廢掉。於測試加註解說明此意圖。
- **TDD**：測試即目的。
- **相依**：無（可與 B2–B4 平行）
- **驗證**：`mvn test -pl blog-infrastructure -am --no-transfer-progress 2>&1 | tee logs/b5.log`

### Task B6 — blog-module-article / Service: 儲存時回填綁定
- **目標**：文章 create/update 後，掃描 `content` 中出現的 `/api/v1/files/{uuid}/content`，取出 fileUuid 清單，透過 **FileFacade** 綁定至該文章。
  - 用正則萃取 UUID；**非法或不存在的 uuid 應安靜略過，不可讓文章儲存失敗**（內文是使用者輸入，不可因一個壞連結就存不了檔）。
  - update 時傳入完整清單（B2 的 bindToArticle 會解除不在清單內的舊綁定）。
- **TDD**：先寫測試：內文含 N 張圖 → 綁定 N 個；update 移除一張 → 該張解除綁定；內文含格式錯誤的 uuid → 不拋錯且其餘正常綁定；內文無圖 → 不呼叫 facade 或傳空清單。確認 RED。
- **相依**：B3
- **驗證**：`mvn test -pl blog-module-article -am --no-transfer-progress 2>&1 | tee logs/b6.log`

---

## 前端任務

### Task F1 — 前端 / API+Config: 相對路徑與 dev proxy
- **目標**：
  1. **移除** `src/api/real/fileService.ts:5-15` 的 `normalizeUploadUrl()` 及其呼叫——它是為修補「後端回容器內部主機名」而生的補丁，改相對路徑後不再需要（且對相對路徑做 `new URL()` 會拋錯走 catch，屬死碼）。
  2. `vite.config.ts` 的 `server` 區塊新增 proxy：`/api` → `http://localhost:9010`（`changeOrigin: true`），使本機相對路徑可解析。
  3. **不動 `apiClient.ts:49` 的 `VITE_API_BASE_URL` 絕對網址呼叫**——改動 API 呼叫方式風險過大且非本任務目標；proxy 只服務內文圖片的相對路徑。
- **TDD**：先寫測試：`uploadFile` 回傳的 url 原樣透傳（不再被改寫）；既有上傳流程不回歸。確認 RED。
- **相依**：無（契約已定，不等後端）
- **驗證**：`npx vitest run src/api 2>&1 | tee logs/f1.log`

### Task F2 — 前端 / 整合回歸
- **目標**：全套 vitest 綠；確認編輯器圖片上傳流程（`useEditorImageUpload`）對相對路徑正常運作（插入內文的 markdown 為相對路徑）。
- **相依**：F1
- **驗證**：`npx vitest run 2>&1 | tee logs/f2.log`

---

## 主線驗收協定

依 `ai-docs/judgment.md` §7 與 `agent-dispatch.md`「驗證不自驗」：每任務回報後抽查 RED log 確有失敗、測試名稱對得上場景表、diff 未越界。

**高風險項目需第二意見**（agent-dispatch「高風險判斷」）：B4 的授權矩陣完成後，另派 review agent 以攻擊者視角審一次，重點檢查——能否繞過 `canRead`、未綁定檔案是否真的私有、presigned URL 效期與洩漏面、`SecurityConfig` 的 permitAll 是否被誤用成跳過檢查。

全部完成後主線親自端到端實測：草稿圖片匿名存取應 403、發布後應 200、作者本人始終 200。
