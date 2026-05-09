# 架構解耦重構 Roadmap

> **Spec date:** 2026-05-03
> **Branch:** `refactor/architecture-decouple`
> **Status:** Approved by Yuan, ready to spawn 4 sub-projects (SP-A → SP-B → SP-D → SP-C)

---

## 1. Goal

Batch 4（Draft History / Versioning）首次成功示範 MQ event-driven 跨模組通訊。基於該 pattern，**對齊整個專案剩餘模組** 的解耦工作：

- 解掉 batch 1-3 累積的設計債（同步 facade 緊耦合 / @Lazy 循環依賴 / 直接 inject Service）
- 統一跨模組通訊規則（event vs facade vs anti-pattern 邊界清楚）
- 為未來新模組樹立 reference architecture

**範圍切分為 4 個 sub-projects**（各自 spec / plan / worktree / PR），順序 **SP-A → SP-B → SP-D → SP-C**。

---

## 2. 設計決策摘要

| 決策點 | 選擇 | 理由 |
|------|------|------|
| 重構方式 | 漸進式 4 個 sub-projects | 大爆炸 risk 高；漸進式可獨立 review、回退 |
| 順序 | SP-A → SP-B → SP-D → SP-C | A 解循環 / B 建一致 / D 收尾零碎 / C 風險最高留最後 |
| Spec 結構 | 一份總 roadmap + 各 SP 自己 spec | meta 確立解耦原則；SP 細節獨立 |
| Worktree 策略 | 每 SP 一個 worktree | 對齊 batch 1-4 慣例，PR review 邊界清楚 |
| Branch 命名 | `refactor/sp-X-<desc>` | 例如 `refactor/sp-a-series-mq-decouple` |
| Anti-pattern 鐵律 | @Lazy / 直接 inject Service / 跨模組 Repository inject 全禁 | 不留任何 workaround，不再有「下一階段再修」 |

---

## 3. Audit findings（盤點摘要）

Audit 範圍：13 個模組（common / infrastructure / db-migration / start / user / article / tag / file / search / recommend / comment / reading / series / version）。

### 3.1 模組依賴關係（pom 層）

依賴關係相對乾淨 — 5 個 module（version / comment / reading / series / article）都單向依賴 article module，沒有反向耦合。

### 3.2 既有 6 個 Facade

| Facade | Impl 模組 | 被 inject 次數 | 主要職責 |
|---|---|---|---|
| ArticleFacade | article | 2 | recommend / search 撈 article 元資料 |
| ReadingFacade | reading | 2 | article enrich liked/bookmarked/progress |
| SeriesFacade | series | 1 | article enrich seriesNav + ⚠ notify delete |
| UserFacade | user | 4 | user nickname / username lookup |
| SearchFacade | search | 1 | recommend 找相似文章 |
| TagFacade | tag | 2 | article 寫入 / 同步 tags + ⚠ delete tags |

### 3.3 MQ event 已建立 6 個 routing key

- `article.content.changed` → version (batch 4 新)
- `article.updated` → search (re-index)
- `article.published` → search / recommend
- `article.deleted` → search (僅 search 訂閱，**series / tag 沒訂**)
- `article.tagged` → tag
- `article.viewed` → article (view count)

### 3.4 既有設計債

| # | 問題 | 嚴重度 | 屬於 SP |
|---|---|---|---|
| 1 | ArticleServiceImpl 1018 行 god class | 高 | SP-C |
| 2 | comment/reading/series/version 直接 inject ArticleService | 高 | SP-B |
| 3 | SeriesFacadeImpl 用 @Lazy 解循環 | 中 | SP-A |
| 4 | SeriesFacade.notifyArticleDeletedFromSeries 該改 MQ | 中 | SP-A |
| 5 | TagFacade.deleteArticleTags 該改 MQ | 中 | SP-D |
| 6 | AutoSnapshotPolicy 直接 inject ArticleRepository | 中 | SP-D |
| 7 | isAdmin() helper 散落多處 | 低 | SP-D |

### 3.5 沒有 @Deprecated / TODO 殘留

（好事）— 表示既有 codebase 沒留半成品。

---

## 4. 解耦原則（5 條，所有 SP 都依此）

### 原則 1：MQ Event for fire-and-forget 通知

**何時用：**
- 上游發生狀態變化要通知下游 — 上游**不需要也不該知道**誰在訂閱
- 不要求立即一致性（最終一致性可接受）
- 失敗可重試（DLQ 兜底）

**範例：**
- ✅ ArticleDeletedEvent → search 移除索引、recommend 重算、series 減 article_count、tag 清 article_tags
- ✅ ArticleUpdatedEvent → search re-index
- ✅ ArticleContentChangedEvent → version 寫快照
- ❌ 不該用：`SeriesFacade.notifyArticleDeletedFromSeries(Long)` — 應改 event

### 原則 2：Facade for 同步讀取

**何時用：**
- 跨模組需要**立即取回**某個值（呼叫者要等回應）
- Read-only operation（不改變狀態）
- Caller 知道要呼叫哪個模組（不像 event 那樣解耦）

**範例：**
- ✅ ArticleFacade.getPublishedArticleBasicInfo(uuid) — recommend 撈 article 元資料
- ✅ ReadingFacade.batchIsLiked(userId, articleIds) — article enrich liked 狀態
- ✅ UserFacade.getUserNickname(userId) — 補使用者顯示名
- ❌ 不該用：直接 inject ArticleService（應改 ArticleFacade）

### 原則 3：絕不使用的 anti-patterns

| Anti-pattern | 為何錯 | 改用 |
|---|---|---|
| `@Lazy` setter injection 解循環 | 設計有問題，啟動順序脆弱 | Event（write 方向）或重新切模組 |
| 跨模組直接 inject Service | 繞過 facade 抽象層 | Facade（read）/ Event（write） |
| 跨模組直接 inject Repository / Mapper | 完全破壞模組邊界 | Facade method 包裝 |
| Service write method 被 facade 暴露 | facade 該 read-only | Event 通知 |

**鐵律：** 4 個 SP 完成後，整個 codebase 不該再出現任何 anti-pattern。發現一個就開新 SP 修。

### 原則 4：Event payload 設計（輕量 vs rich）

**判斷規則：consumer 收到 event 時能不能 query DB 撈到 entity？**

| 情境 | Payload 風格 | 範例 |
|---|---|---|
| 能撈到（entity 還在） | **輕量 marker**（id + uuid + action + timestamp） | ArticleContentChangedEvent (SAVED / UPDATED) |
| 撈不到（entity 已刪） | **Rich payload**（必要 metadata 都包進 event） | ArticleDeletedEvent — 要包 seriesId / authorId 等 |

**SP-A 的具體應用：** 既有 ArticleDeletedEvent payload `{articleUuid, occurredAt}` 不夠 rich，series consumer 需要 seriesId 才能 decrement count。SP-A 要擴充 payload。

### 原則 5：可靠性語意

- **At-least-once delivery**（RabbitMQ persistent queue + DLQ）
- **Best-effort 發送**（producer try/catch + log warn，不阻塞 commit）
- **Consumer idempotent**（事件可能重送，handler 要可重複執行不出錯）
- **Ordering 不保證**（同 article 連發 event 可能亂序，consumer 要能處理）

---

## 5. 4 個 Sub-Projects 範圍

### 5.1 SP-A: series-mq-decouple（先做）

**目的：** 解 series ↔ article 循環依賴 + 落實原則 1。

**範圍：**
- ArticleDeletedEvent payload 從 `{articleUuid, occurredAt}` **擴充加 seriesId / authorId**
- ArticleServiceImpl.deleteArticle：刪除前讀 article.seriesId / authorId 包進 event
- Series 模組新增 `ArticleDeletedConsumer @RabbitListener` 訂 `article.deleted`，內部 `if seriesId != null then decrementArticleCount`
- `SeriesFacade.notifyArticleDeletedFromSeries(Long)` interface method **刪除**
- SeriesFacadeImpl 評估能否移除 @Lazy ArticleService（看 getSeriesNavigation 是否還需要 ArticleService — 若需要留到 SP-B）
- 既有 cross-module IT「DELETE article → series.article_count -1」確認仍綠

**Done definition:**
- SeriesFacade interface 不再含 notify method
- SeriesFacadeImpl 不再 inject ArticleService（如可）或仍 @Lazy（待 SP-B 徹底解）
- ArticleDeletedEvent rich payload 通用設計（其他 SP 也用）
- IT 全綠

**預估：** 6-7 tasks。

**風險：** 低。

### 5.2 SP-B: article-facade-routing

**目的：** comment / reading / series / version 4 模組統一改用 ArticleFacade，落實原則 2。

**範圍：**
- 盤點 4 模組對 ArticleService 的所有呼叫，列出需要的 read method 清單
- ArticleFacade 補對應 method（如 `findById` / `findByUuid` / `findBySeriesIdOrderByPosition` / 等）
- 4 模組改 inject `ArticleFacade` 取代 `ArticleService`：
  - **comment** — 改 ArticleService → ArticleFacade
  - **reading** — ArticleLikeService 對 ArticleService 的 read inject 改 ArticleFacade
  - **series** — SeriesService 改 ArticleFacade；SeriesFacadeImpl 完全移除 ArticleService inject（@Lazy 鐵律消除）
  - **version** — VersioningService / AutoSnapshotPolicy 改 ArticleFacade
- ArticleService 內部 read method 仍可保留（給 ArticleControllerImpl 內部用），但「對外 API」收斂在 ArticleFacade

**Done definition:**
- 全 codebase grep `private final ArticleService` 只剩 article 模組內部使用
- @Lazy 完全消除（grep 0 結果）
- 5 個 affected modules tests 全綠

**預估：** 10-12 tasks。

**風險：** 中 — 影響 4 個模組，IT 都要改 mock。

### 5.3 SP-D: events-and-helpers-cleanup（接 SP-B）

**目的：** 收尾零碎債。

**範圍：**
- **TagFacade.deleteArticleTags 改 event**：tag 模組訂 ArticleDeletedEvent → 自己清 article_tags（消除 facade write method）
- **AutoSnapshotPolicy 改 inject ArticleFacade**（依賴 SP-B 補的 facade method）
- **VersioningService 直接 inject ArticleRepository 改 facade**（同上）
- **SecurityUtils.isAdmin 提取**到 `blog-infrastructure/security/SecurityUtils`，取代 VersionController / PreferenceController / ArticleController 等重複 helper
- 確認 SeriesFacade.notifyArticleDeletedFromSeries 已移除乾淨（SP-A 應已做）

**Done definition:**
- TagFacade 不再有 write method
- 跨模組 Repository inject grep 0 結果
- isAdmin 重複 helper grep 0 結果（只剩 SecurityUtils 一處）

**預估：** 6-8 tasks。

**風險：** 低 — 都是局部清理。

### 5.4 SP-C: article-service-split（最後做）

**目的：** ArticleServiceImpl 1018 行 god class 拆分。

**初步拆分構想（SP-C brainstorm 時細化）：**
- `ArticleCommandService`（write: createArticle / updateArticle / deleteArticle / publishArticle）
- `ArticleQueryService`（read — 已存在，本 SP 可能擴充職責）
- `ArticleViewService`（瀏覽追蹤 + Redis 防刷 + ViewCount event）
- `ArticleService` interface 保留（avoid breaking change）但 impl 內部委派 3 個 sub-service

**為何最後做：**
- 需要 SP-A 解循環、SP-B 建 facade routing、SP-D 收尾後，邊界才穩定
- ArticleEventPublisher pattern（batch 4）已驗證，拆 god class 時可同 pattern 切
- 風險最高，SP-A/B/D 全綠了再動 god class

**Done definition:**
- ArticleServiceImpl 不再 > 500 行
- 3 個 sub-service 各自職責清楚（依 single responsibility）
- IT 全綠

**預估：** 15-18 tasks。

**風險：** 高 — 核心模組大改。

---

## 6. 跨 SP 通用 Conventions

### 6.1 Worktree 與 Branch

每個 SP 各自 worktree：
- SP-A: `feature/refactor-sp-a-series-mq-decouple`
- SP-B: `feature/refactor-sp-b-article-facade-routing`
- SP-D: `feature/refactor-sp-d-cleanup`
- SP-C: `feature/refactor-sp-c-article-service-split`

每個 SP 從 develop 切（前一 SP merge 後，依序）。如果想並行（如 SP-D 跟 SP-C 邏輯不衝突可並行），看到時候情況決定。

### 6.2 工作流程（每個 SP）

1. 切 worktree + branch
2. brainstorm（用 superpowers:brainstorming） → 寫 SP-X spec
3. writing-plans → 寫 SP-X plan
4. subagent-driven-development → 實作
5. finishing-a-development-branch → push + PR
6. PR review → merge 到 develop
7. 下個 SP 從新 develop 切

### 6.3 Commit & PR 慣例

不變：
- Conventional Commits
- 繁中描述
- Co-Authored-By 行
- PR title / body 對齊 batch 1-4 風格

### 6.4 測試策略（每個 SP）

- Unit tests 對齊 SP 改動範圍
- IT 對齊 SP 改動範圍
- Cross-module IT 確認跨模組行為不變
- 全模組 sanity（mvnw test）每個 SP commit 前確認

### 6.5 已知 limitations 持續記錄

- categoryId schema 缺陷（batch 4 留下，未來新 SP 處理）
- 其他發現的 limitation 在 SP spec 末尾的「後續批次預告」記錄

---

## 7. Risk & Mitigation

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| SP-B 大量 facade method 補完導致 ArticleFacade 介面爆炸 | 中 | 中 | 盤點時優先 reuse 既有 method；超過 10 個 method 考慮拆 facade |
| SP-C ArticleServiceImpl 拆分破壞既有 IT | 中 | 高 | 拆分前每個 method 都有對應 IT；拆分後逐個驗 |
| 4 個 SP 連續做工作量大（~40 tasks） | 高 | 中 | 每個 SP 完整收尾再開下個（避免半途切換）；用 subagent-driven 加速 |
| ArticleDeletedEvent payload 擴充破壞既有 search consumer | 低 | 中 | search 用 record pattern matching，多欄位不影響；SP-A test 涵蓋 |
| ArticleFacade method 增加 → article 模組編譯時間變慢 | 低 | 低 | 接受；維護性 > 編譯速度 |

---

## 8. 後續批次預告

完成 4 個 SP 後可接續：
- **categories restore**（修 batch 4 limitation — Article entity 用 article_categories 多對多但 ArticleVersion 存單值欄位 categoryId 為 dead field）
- **Bookmark 分類**（batch 2 留下的可選功能）
- **Series 章節**（series 內小節分類）
- **Tags index 整合 Series**（discovery 改善）
- **新模組或新功能**（依需求）

完成解耦後新加模組/功能可直接套 reference architecture：
- Read 操作 → Facade
- Write 通知 → MQ event
- Schema 跨模組關聯 → Facade method 隱藏 Repository
