# 設計：跨模組識別全面改為 UUID，並解除模組間的資料庫與程式碼耦合

- **日期**: 2026-10-24
- **狀態**: Yuan 逐段確認（範圍 / 安全網 / 登入身分 / 刪除語意 / 施工方式 / 標準流程 / 各模組 / 收尾 / 派工）
- **取代**: `ai-docs/backlog/2026-09-28-arch30-uuid-currency-plan.md`（原 ARCH-30 第 2 段，只涵蓋文章 id）——其 §1 盤點與 §3 鎖／索引注意事項仍有效
- **決策紀錄**: `ai-docs/backlog/2026-10-24-todo-review-decisions.md` §6
- **環境前提**: **目前沒有任何正式環境**（Yuan 2026-10-24 確認），亦無長期存活的環境

---

## 0. 目標與範圍

**目標**：為日後拆服務鋪路（Yuan 確認有此打算）。做完時：

1. 跨模組識別（文章、系列、使用者）在儲存層、facade、事件、登入身分**全部是 UUID**
2. 模組之間**沒有資料庫 FK**；刪除連動由事件＋每日校正排程負責
3. 模組之間**沒有直接 import**、**不直接 JOIN 他模組的表（含 `users`）**；只透過 facade 與共用事件契約溝通
4. 所有事件帶 `eventId`；所有 consumer 冪等；MQ 重試機制生效

**不在範圍**：實際拆分資料庫（schema / instance）、實際部署成獨立服務。

**不變**：
- 各模組**自己的主鍵**維持 Long（`users.id`、`articles.id`、`series.id` …）；**模組內部**關聯維持 Long（`verification_tokens.user_id`、`user_preferences.user_id`、`article_categories.article_id`、`comment_likes.comment_id`、`comments.parent_id`）
- **刪除語意不變**（Yuan 確認）：文章刪除 → 其他模組的相關資料硬刪；系列刪除 → 文章脫離系列；使用者只做軟刪除（Q2 匿名化另案 DATA-02）
- 對外 API 契約不變（對外本來就全是 UUID）

---

## 1. 現況盤點（2026-10-24）

### 1.1 要轉換的欄位（17 個，皆為 BIGINT）

| 模組 | 表 | → users | → articles | → series |
|---|---|---|---|---|
| article | `articles` | `author_id` | — | `series_id`（ON DELETE SET NULL） |
| comment | `comments` | `user_id` | `article_id`（V24 已加 `article_uuid` 並雙寫，含 FK `comments_article_uuid_fkey`） | — |
| comment | `comment_likes` | `user_id` | — | — |
| reading | `user_article_likes`、`user_bookmarks`、`user_highlights`、`user_reading_progress` | `user_id`×4 | `article_id`×4 | — |
| series | `series` | `author_id` | — | — |
| version | `article_versions` | `author_id` | `article_id` | — |
| file | `files` | `uploader_id` | — | — |

已是 UUID：`article_tags.article_id`（有 FK → `articles(uuid)`，於 tag 模組處理時移除 FK）、`user_tag_follows.user_id`（無 FK）、`file_metadata.uploader_id`（無 FK）。

`files` 表全 repo 零讀寫（僅 V1 建立；實際使用 `file_metadata`）→ **整張 DROP**（Yuan 授權）。

### 1.2 facade

42 個方法中 27 個用到 Long：ArticleFacade 15、ReadingFacade 6、UserFacade 4、SeriesFacade 2；DTO `ArticleData.id`、`ArticleContentData.id`。

### 1.3 登入身分

JWT subject = Long userId；`JwtAuthenticationFilter` 放入的 principal 為 `Long`；`@AuthenticationPrincipal Long …` 共 63 處。Redis key 以 Long 為鍵：`user:auth:{id}`、refresh、登入失敗次數、搜尋紀錄。

### 1.4 事件（11 個類別）

帶 Long 識別：`authorId`×4、`articleId`×2、`seriesId`×1、`userId`×2；有 `eventId` 者 5 個。

### 1.5 非識別類耦合

- 直接 JOIN `users`：article（`ArticleMapper`、`ArticleRecommendMapper` + XML、`ArticleSummaryRow`、`ArticleResponseMapper`）、comment（`CommentMapper`、`CommentWithAuthor`）、series（`SeriesMapper`、`SeriesService`）、version（`VersionSummaryResponse`）
- 跨模組直接 import（Q11，11 處）：reading、series → `ArticleQueryService`、`ArticleSummaryResponse`；series、version → `ArticleDeletedEvent`／`ArticleContentChangedEvent`、`ArticleRabbitMqConfig`；version → `ArticleMarkdownRenderer`、`ArticleTocCodec`、`RenderResult`
- 跨模組 FK（含 CASCADE）：見 1.1

---

## 2. 施工方式：先打地基，再逐模組一次做到底（方案 A）

```
第 0 階段 地基（不改變任何模組的行為）
  0a 安全網與事件契約
  0b 登入身分（含 Q9）
第 1～8 階段 每模組一個 PR，一次做到底（依序，前一個合併後才開始下一個）
  comment → reading → version → series → file → article → search／recommend／tag → user
收尾階段
```

否決：B（橫向分層推進——每模組被翻修 4～5 次）、C（單一長期分支——diff 無法審查、與 develop 長期衝突）。

---

## 3. 第 0 階段

### 3.1 0a：安全網與事件契約

| 元件 | 內容 |
|---|---|
| consumer 共用包裝 | 收斂 10 個 consumer 的 try/ack/catch/nack 樣板；計數 `mq.consumer.processed` / `mq.consumer.failed` / `mq.consumer.dead_lettered`（tag：consumer 名稱）。**維持現行 ack/nack 行為，不打開重試**（Yuan 決定：重試須待所有 consumer 冪等，於收尾階段打開） |
| 校正排程框架 | 抽象 `OrphanReconciliationJob`：取本模組表中對外 UUID → 以 owner facade 分批查存在性 → 刪除不存在者 → 計數 `reconciliation.orphans_removed{module,table}` ＋ log；Redis 鎖比照 `TrendingRefreshJob`；每日一次 |
| owner 存在性查詢 | `ArticleFacade.filterExistingUuids(Collection<UUID>)`、`SeriesFacade.filterExistingUuids(Collection<UUID>)`，經 `BatchedQuery` 切批（使用者為軟刪除，不需要） |
| 事件契約位置 | 跨模組事件統一放 `blog-infrastructure/.../event`；ArchUnit 守衛：事件類別必須有 `UUID eventId`、不得有 `Long`/`long` 欄位——以**例外清單**起步，各模組 PR 清掉自己的條目 |
| 跨模組 import 守衛（Q11） | 現存 11 處列入例外清單；守衛含「例外清單不得有死條目」（比照 `EndpointAuthorizationTest`）；收尾時清單必須為空 |

可觀測性的 Prometheus／Grafana／告警屬 infrastructure repo 的另案，不阻擋本計畫。

### 3.2 0b：登入身分

| 元件 | 內容 |
|---|---|
| `AuthUser(Long id, UUID uuid, Role role)` | 取代 `Long` principal。**過渡期同時帶 `id` 與 `uuid`**：未改造模組用 `id()`，已改造模組用 `uuid()`；收尾時移除 `id` |
| JWT | subject 改為 uuid；**直接切換**，舊 token 一律失效、全體重新登入（Yuan 決定；無正式環境） |
| Redis key | `user:auth:{uuid}`、refresh、登入失敗次數、搜尋紀錄改以 uuid 為鍵 |
| `AuthStateLoader`（Q9） | filter 與 `/auth/refresh` 共用：Redis 命中即用，miss 查 DB 回填（uuid → status、role、tokenVersion、id）；修掉 SEC-26 |
| 63 處 controller | `@AuthenticationPrincipal Long userId` → `@AuthenticationPrincipal AuthUser user`，內部以 `user.id()` 維持原行為——**純型別替換** |

---

## 4. 每個模組 PR 的標準流程

**不做雙寫**：無正式環境、無線上流量需要保護，故每個模組以**單一 migration** 完成 expand 與 contract。**規則**：若本計畫進行中有正式環境上線，剩餘模組改回「兩次部署、中間雙寫」。

| # | 步驟 | 驗收 |
|---|---|---|
| 1 | 綠燈基線：跑該模組測試，存 `logs/<task>-baseline.log` | log |
| 2 | RED：uuid 讀寫、刪除事件 consumer、校正排程、雙擊 200（ON CONFLICT）、作者批次查詢無 N+1 | RED log |
| 3 | Migration：新增 uuid 欄位 → 由 owner 表回填 → NOT NULL → 建涵蓋索引 → DROP 舊 Long 欄位、舊 FK、舊索引 → 在 uuid 欄位重建 UNIQUE。**新 uuid 欄位不建 FK**。同步 `ai-docs/schema.md`（欄位、索引、Migration Index） | migration 與 schema.md diff |
| 4 | entity、mapper、repository 改 uuid；T2 冪等反模式改 `INSERT ... ON CONFLICT`，依 affected rows 決定計數 | 測試 |
| 5 | controller、service 用 `AuthUser.uuid()`；呼叫 UUID 版 facade。owner 模組缺 UUID 版方法時**可於本 PR 新增（只增不刪）** | 編譯、測試 |
| 6 | JOIN `users` → `UserFacade` 批次查詢 | 斷言一頁只多 1 次查詢 |
| 7 | 跨模組直接 import → facade／共用事件 | 守衛例外清單刪除該模組條目 |
| 8 | 刪除連動：訂閱 owner 的刪除事件刪除本模組資料（天然冪等）；繼承 `OrphanReconciliationJob` | IT：刪除前後資料與現行為一致 |
| 9 | 本模組發出的事件只帶 UUID 且有 `eventId` | 守衛例外清單刪除該模組條目 |
| 10 | grep 證據：本模組不再有指向他模組的 Long 欄位／參數；附於 PR 說明 | grep 輸出 |

**Migration 授權**（Yuan 2026-10-24，`judgment.md` §5）：§1.1 的 17 個欄位可新增 uuid、回填、NOT NULL，並於同一 migration DROP 舊欄位與其 FK、索引；`comments_article_uuid_fkey`（V24）、`article_tags` 指向 `articles(uuid)` 的 FK 一併 DROP；`files` 表整張 DROP。**邊界**：各模組自己的主鍵與模組內部關聯不在授權內，不得刪除。每個 PR 須附 grep 證據與測試結果。

**鎖與資料量**：沿用 ARCH-30 計畫 §3 的注意事項；目前無正式環境、資料量可忽略，單一 migration 可接受。

---

## 5. 各模組特別事項（依施工順序）

| # | 模組 | 欄位 | 額外事項 |
|---|---|---|---|
| 1 | comment | `comments.user_id`、`comments.article_id`（DROP 舊欄與 V24 的 FK）、`comment_likes.user_id` | `CommentMapper` JOIN users；留言數改 UUID 版 facade；RACE-01（留言按讚）、RACE-09（併發雙刪雙扣） |
| 2 | reading | 4 表的 `user_id`、`article_id` | 移除對 `ArticleQueryService` 的直接依賴，改 `ArticleFacade.getSummariesByUuids`（**回應 JSON 不變**）；`ReadingFacade` 6 個方法改 UUID；閱讀進度 Redis key 與 `ReadingProgressFlushJob`；RACE-01、RACE-04、RACE-13 |
| 3 | version | `article_versions.article_id`、`author_id` | 還原改為把 Markdown 交給 article 渲染（`ArticleFacade.applyRestoreContent` 由 article 自行算 html/toc），version 不再 import 渲染器；`ArticleContentChangedEvent` 移至共用契約、帶 uuid 與 `eventId`；`VersionSummaryResponse` JOIN users |
| 4 | series | `series.author_id` | 移除 import `ArticleQueryService`、`ArticleDeletedEvent`、`ArticleRabbitMqConfig`；新增 `SeriesDeletedEvent`；`SeriesMapper` JOIN users；RACE-14、RACE-15 |
| 5 | file | DROP `files` 表 | `file_metadata.uploader_id` 已是 uuid，確認無 FK、事件與守衛合規 |
| 6 | article | `articles.author_id` → `author_uuid`、`series_id` → `series_uuid` | 訂閱 `SeriesDeletedEvent` 清空 `series_uuid`；推薦查詢 JOIN users；`ArticlePublishedEvent` 等改 uuid＋`eventId`；`ArticleFacade.countPublishedByAuthor()` 改以 `author_uuid` 計算 |
| 7 | search／recommend／tag | — | ES 文件改存 `author.uuid`、**移除 `id` 與 `username`**，重建索引；搜尋結果拿掉逐頁批次作者查詢；tag 移除 `article_tags` → `articles(uuid)` FK、改事件刪除＋校正；RACE-08 |
| 8 | user | owner | `UserFacade` 全面 UUID；模組內事件（註冊、重設密碼）也改 uuid＋`eventId`；RACE-02、RACE-03（若 Q8 尚未處理） |

### 5.1 對公開作者功能設計的修改（Yuan 同意）

`docs/plans/2026-10-23-public-author-profile-design.md` 的 `GET /api/v1/authors` 改由 **user 模組**實作（契約不變）：

1. `ArticleFacade.countPublishedByAuthor()` → `Map<UUID authorUuid, Long count>`（傳輸量與**作者人數**成正比；JavaDoc 標註「作者超過約 1,000 人時重新評估」）
2. user 模組在這些作者中以 nickname 篩選、依篇數排序、取前 20

`getPublishedStatsByAuthor` 簽章直接用 `UUID`。公開作者功能可於第 0 階段後與模組 PR 並行；article 模組未改造前，facade 內部可暫時 JOIN users，facade 介面不需再改。

---

## 6. 收尾階段（一個 PR）

| 項目 | 內容 |
|---|---|
| 移除過渡碼 | `AuthUser.id`；facade 所有 Long 版方法；DTO 的 Long 欄位（`ArticleData.id` 等） |
| 全域守衛 | ① facade 介面參數與回傳不得有代表 id 的 Long ② 事件無 Long 欄位、必有 `eventId` ③ 跨模組 import 例外清單為空 ④ IT 以 `information_schema` 斷言無跨模組 FK |
| 打開 MQ 重試 | 共用包裝改為拋例外交由 retry interceptor；`eventId` 冪等檢查集中在包裝；設定 concurrency（ARCH-02、PERF-15、PERF-30 一併處理；順序依 triage：PERF-15 → ARCH-02＋PERF-30） |
| 規範文件（提案制，需 Yuan 簽核） | `architecture.md`：移除「`users` 為 reference data 可直接 JOIN」；新增「跨模組識別一律 UUID、跨模組不得有 FK、刪除連動靠事件＋校正排程」 |
| findings | ARCH-30、ARCH-07、ARCH-09、ARCH-13、ARCH-02 與相關 RACE 條目轉 DONE |

---

## 7. 驗證與派工

```
1. 主線依 ai-docs/task-briefs.md 範本寫提示詞（共通頭＋實作範本＋§4 標準流程＋該模組 §5 事項）
2. Sonnet 實作：獨立 worktree、TDD、log 存 logs/
3. 另派 Sonnet 審查：審查範本、反方立場（不自驗）
4. 主線抽查：RED/GREEN log、grep 證據、migration 與 schema.md
5. 主線開 PR 到 develop → CI 跑 IT → Yuan 審查並合併
6. 合併後才開始下一階段（依序，不並行：共用檔案與 Flyway 版本號會衝突）
```

- 升級：Sonnet 同一任務連錯兩次 → 帶完整失敗軌跡改派 Opus；再不過 → 停下問 Yuan
- **0b 風險最高**（一次動所有模組的 63 處 controller），提示詞須最細
- IT：本雲端 container 無 Docker daemon 時，以 CI 結果為準；**不得把「沒跑」寫成「通過」**

### 7.1 驗收（每個 PR）

- 該模組單元測試＋IT 綠（CI run 連結或 surefire XML）
- RED 證據 log
- grep：該模組無指向他模組的 Long 欄位、參數、import
- 守衛例外清單確實縮短
- `schema.md` 與 migration 一致
- 刪除連動 IT：刪文章（或系列）後，本模組相關資料與改造前行為一致
