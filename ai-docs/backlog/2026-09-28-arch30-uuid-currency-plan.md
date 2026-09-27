# ARCH-30 第 2 段計畫：跨模組的文章識別由 Long 主鍵改為 UUID

- **建立日期**: 2026-09-28
- **狀態**: 計畫待 Yuan 拍板（§5 的三個決定），尚未動任何程式碼或 migration
- **上游**: `2026-09-06-handoff-sec04-arch30.md` §2；`findings.md` ARCH-30
- **範圍**: 只做 §2.3 的**第 2 段**。第 1 段（ARCH-16 可觀測性）與第 3 段（拆 CASCADE、改事件驅動刪除）**不在範圍內**

---

## 0. 一句話

其他模組以 `articles.id`（Long）作為文章識別，既寫進自己的表、也在 facade 與事件中流通。本計畫把這個「貨幣」換成 `articles.uuid`：**刪除語意不變**（新的 FK 指向 `articles(uuid)`，同樣 `ON DELETE CASCADE`，與 V9 的 `article_tags` 做法相同），但其他模組不再需要知道 article 模組的內部主鍵——這是日後拆服務的前提。

---

## 1. 現況盤點（2026-09-28，已抽查）

### 1.1 facade 中以 Long 表示文章的方法：20 個

| Facade | 方法（Long 的角色） |
|---|---|
| `ArticleFacade` | `findIdByUuid`（回傳）、`findById`／`findByIds`（參數＋回傳 DTO 的 id）、`findByUuid`／`findBySeriesIdOrderByPosition`（回傳 DTO 的 id）、`increment/decrementCommentCount`、`increment/decrementLikeCount`、`updateSeriesAssignment`、`findContentById`、`applyRestoreContent`、`filterReadableIds`（參數＋回傳） |
| `ArticleLookupFacade` | `findByUuid`（回傳 DTO 的 id；唯一呼叫端 file 模組**沒用到 id**） |
| `ReadingFacade` | `batchIsBookmarked`、`isBookmarked`、`batchGetProgress`、`batchIsLiked`、`isLiked` |
| `SeriesFacade` | `getSeriesNavigation(Long articleId)` |

帶 Long 文章 id 的 facade DTO：`ArticleData.id`、`ArticleContentData.id`。

### 1.2 跨模組呼叫點：39 處（37 處真的用到 Long）

主要寫入路徑——**這些是「貨幣必須連儲存層一起換」的原因**：

| 模組 | 寫入點 | 取得 Long 的方式 |
|---|---|---|
| comment | `CommentService.java:65,91` → `comments.article_id` | `findIdByUuid` |
| reading | `BookmarkController.java:95` → `user_bookmarks` | `findIdByUuid` |
| reading | `ArticleLikeController.java:62` → `user_article_likes` | `findIdByUuid` |
| reading | `HighlightService.java:34,42` → `user_highlights` | `findIdByUuid` |
| reading | `ReadingProgressService.java:51,58`、`ReadingProgressFlushJob.java:52,63` → `user_reading_progress` | `findIdByUuid` |
| version | `VersioningService.java:443`（`snapshotFromContent`）→ `article_versions` | `findContentById`／事件 |

**繞過 facade 的 Long 路徑（2 條）**：reading 的 `BookmarkQueryService.java:72` 與 series 的 `SeriesService.java:385` 直接注入 article 模組的 `ArticleQueryService`，以 `List<Long>` 呼叫 `getArticleSummariesByIds`。只改 facade 會漏掉這兩條。

**來回轉換**（改完可消除）：reading 的 `batchGetProgress` 收到 id 後再呼叫 `findByIds` 換回 uuid（`ReadingProgressService.java:89-91`）；`SeriesFacadeImpl.getSeriesNavigation` 收到 id 後再 `findById`（`SeriesFacadeImpl.java:45`）——文章本來就有 uuid，繞了一圈。

### 1.3 以 `article_id BIGINT` 儲存文章主鍵的表：6 張（皆 `ON DELETE CASCADE`，皆無 `article_uuid`）

| 表 | owner 模組 | 建立於 |
|---|---|---|
| `comments` | comment | V1 |
| `user_article_likes` | reading | V1（V15 改名） |
| `user_bookmarks` | reading | V14 |
| `user_highlights` | reading | V14 |
| `user_reading_progress` | reading | V14 |
| `article_versions` | version | V16 |

對照：`article_tags.article_id` 早已是 UUID（V9，FK → `articles(uuid)`）；`file_metadata.article_uuid` 是 UUID（V20，刻意無 FK）。

### 1.4 MQ 事件

`blog-infrastructure/**/event` 中 0 個欄位攜帶文章 Long。article 模組自有事件中有 2 個：`ArticleContentChangedEvent.articleId`（version consumer **實質依賴**）、`ArticleDeletedEvent.articleId`（series 只用於 log，search 忽略）。

### 1.5 對外 API：0 處暴露文章 Long

所有 controller 參數與回應 DTO 的識別欄位皆為 UUID。本計畫**不動對外契約**。

### 1.6 範圍外但相關

series 的主鍵（Long）同樣跨模組流通（`ArticleData.seriesId`、`updateSeriesAssignment`、`ArticleDeletedEvent.seriesId`、`articles.series_id` FK）。與本計畫同型，建議文章做完後比照處理，另案。

---

## 2. 為什麼不能只改介面

若只把 facade 簽章改成 UUID、各模組內部照舊存 `article_id`，寫入時每個模組仍須先把 UUID 換成 article 的 Long 主鍵——`findIdByUuid` 只是換了位置，其他模組對 article 內部主鍵的依賴一點都沒少。ARCH-30 要解的是「跨模組耦合的真正載體是 schema」，所以**儲存層必須一起換**。

---

## 3. 做法：expand → switch → contract

每張表獨立走完四步，模組之間可以分批：

| 步驟 | 內容 | 破壞性？ |
|---|---|---|
| **P1 Expand** | `ADD COLUMN article_uuid UUID` → 由 `articles` 回填 → `SET NOT NULL` → `ADD FK (article_uuid) REFERENCES articles(uuid) ON DELETE CASCADE` → 複製既有的索引與 UNIQUE（如 `UNIQUE(user_id, article_id)` 對應 `UNIQUE(user_id, article_uuid)`） | **含 NOT NULL 回填 → `judgment.md` §5 必問** |
| **P2 Dual-write** | entity 同時寫兩欄；讀取仍走 `article_id` | 否 |
| **P3 Switch** | 讀寫改走 `article_uuid`；facade 簽章改 UUID、刪除 `findIdByUuid` 的寫入用法；事件加上 `articleUuid`；兩條繞過 facade 的路徑改走 facade | 否（內部介面） |
| **P4 Contract** | `DROP` 舊的 `article_id` 欄位、舊 FK、舊索引 | **DROP → §5 必問，且與 P1 分開授權** |

刪除語意在整個過程中**不變**：P1 起新 FK 就是 CASCADE，P4 只是拿掉重複的舊 FK。所以 ARCH-16（可觀測性）不是本計畫的前置——它是第 3 段（拆 CASCADE、改事件補償）的前置。

防線（`judgment.md` §8／§9）：P3 完成時加一條 ArchUnit 守衛——非 article 模組的 facade 簽章不得以 `Long` 表示文章、不得 import `dowob.xyz.blog.module.article..` 的類別（附反向驗證 fixture）。

---

## 4. 模組順序

1. **comment（試點）**：1 張表、寫入點集中在 `CommentService`，最能以最小代價驗證整套 P1～P3 的做法與 migration 模板
2. **reading**：4 張表、呼叫點最多，並一併拿掉 `BookmarkQueryService` 對 `ArticleQueryService` 的直接依賴與 `batchGetProgress` 的來回轉換
3. **version**：1 張表，但依賴 `ArticleContentChangedEvent.articleId`，事件要先加 `articleUuid`
4. **article 自身的 facade 收尾**：刪除 Long 版本的方法、`ArticleData.id`
5. **P4**：全部模組切換完成、穩定後，一次提出所有 DROP 請 Yuan 授權

每個模組一個 PR，本機 Docker 跑完 IT 再開。

---

## 5. 需要 Yuan 拍板

1. **採用「儲存層一起換」的 expand–contract 做法**（相對於只改介面，理由見 §2）
2. **授權 P1 migration**：對既有表新增 `article_uuid`、回填、設 NOT NULL、加新 FK——屬 `judgment.md` §5 的「NOT NULL 回填」。可先只授權試點的 `comments`
3. **試點模組**：建議 comment
4. **P4 的 DROP 另行授權**，本計畫現在不寫任何 DROP
