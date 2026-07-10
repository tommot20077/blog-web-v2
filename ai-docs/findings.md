# Findings 登記簿（2026-07 實作層稽核）

> **用途**：批次修復前的問題總帳。只記錄、不動工。每筆含 ID / 嚴重度 / 證據 file:line / 情境 / 修法方向 / 狀態。
> **來源**：2026-07-10 三路唯讀稽核（授權·IDOR / 併發·競態 / 資料一致性），承接架構層體檢（見 roadmap.md）。
> **狀態圖例**：`OPEN`（待修）/ `FIX`（修復中）/ `DONE`（已修）/ `WONTFIX`（確認為刻意設計）/ `NEEDS-DECISION`（待 Yuan 業務判斷）。

---

## 交叉主題（跨稽核共振——修一處解多筆）

- **T1 status 轉換一致性**（Q1 定案後重新定性）：AUTH-01＝DATA-03——restore 靜默改 status 時無對應的 ES/series/count 連鎖。自助發布本身合法（AUTH-02 WONTFIX），問題純為 status 轉換未收斂到單一守衛且未觸發副作用。
- **T2 `try save catch DIVE` 冪等反模式**：RACE-01/03/04/08/15、DATA-01 部分。Spring Data JDBC 的 save 例外會使外層交易 rollback-only，catch 吞不掉 → 500。正解統一為 `INSERT ... ON CONFLICT`（codebase 已有正確範本：`UserTagFollowRepository.follow`、`IdempotencyService`）。
- **T3 反正規化計數器漂移**：DATA-01（tag usage 只增不減+重複累加）、DATA-06（comment_count 雙生路徑分歧）、RACE-07/09、DATA-10（series count）。`tags.usage_count` 最嚴重（膨脹導致 tag 永久無法刪）。
- **T4 刪除/降級無連鎖**：DATA-02（刪帳內容殘留）、DATA-03/04（ES 幽靈文件 reindex 修不掉）、DATA-07（MinIO 圖片洩漏配額）、DATA-13/14。**依賴 Q2 業務決策**。
- **T5 事件冪等未鋪滿**：DATA-11 = roadmap 工作包 C。計數型 consumer 無 eventId、無 IdempotencyService，redelivery 重複累加，疊加於 T3。

---

## 業務判斷已定案（2026-07-10）

- **Q1 作者發文無需強制審核**（作者可自助發布）。影響：
  - AUTH-02 → **WONTFIX**（`DRAFT→PUBLISHED` 自助發布為刻意設計；但 `UpdateArticleRequest.status` 的 mass-assignment 仍應限制為合法轉換，見 AUTH-02 註）。
  - AUTH-01 / DATA-03 → **仍須修**，但重新定性：不是「審核繞過」，而是 restore 靜默把 PUBLISHED 降級時**無 ES 刪除 / series 不清 / count 不減**的一致性破口。
- **Q2 刪除帳號採「匿名化」**（我方建議，Yuan 可推翻）：洗 `users` 列 PII（nickname→「已刪除使用者」、avatar/bio/website/social/location→null、email→墓碑）使既有 JOIN 自動匿名；保留已發布文章、刪除草稿/PENDING；清孤兒檔案（併 DATA-07）；發 `UserDeletedEvent` 供 search reindex。DATA-02 修法據此。
- **Q3 未驗證信箱帳號不可寫入**。AUTH-06 → **確認為 bug 須修**：PENDING_VERIFICATION 不應取得寫入類 authorities/permission。

---

## AUTH — 授權 / IDOR（無 Critical；ownership 紀律整體良好）

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| AUTH-01 | Medium | version restore 靜默還原快照 status（重新定性為一致性破口，見 DATA-03；非審核繞過） | `VersioningService.java:258`、`ArticleFacadeImpl.java:391-405` | OPEN |
| AUTH-02 | Low | `UpdateArticleRequest.status` mass-assignment（自助發布為刻意設計，但仍應限制為合法轉換值） | `ArticleCommandSubService.java:49-54,363-368`、`UpdateArticleRequest.java:43` | WONTFIX（部分）|
| AUTH-03 | Medium | 公開 `GET /series/{slug}` 洩漏非 PUBLISHED 文章（status 改回 DRAFT 後 series_id 不清） | `SeriesService.java:216`、`ArticleMapper.java:282-283` | OPEN |
| AUTH-04 | Medium | comment 寫入/按讚/刪除僅 `isAuthenticated()`，未接回 COMMENT_WRITE/DELETE permission | `CommentController.java:63,73,84`、`CommentLikeController.java:35,43` | OPEN |
| AUTH-05 | Low | 草稿文章可被互動（like/bookmark/highlight/comment）+ 200/404 存在性 oracle | `ArticleMapper.java:273`（findIdByUuid 不濾 status） | OPEN |
| AUTH-06 | Medium | PENDING_VERIFICATION 帳號取得完整寫入權（Q3 定案：不應可寫入） | `JwtAuthenticationFilter.java:92` | OPEN（確認須修）|
| AUTH-07 | Low | 公開 `GET /files/{id}` 回傳含 `storagePath`（MinIO 內部路徑）+ uploaderId | `FileController.java:95-98`、`FileServiceImpl.java:239-243` | OPEN |
| AUTH-08 | Low | BookmarkController 缺 article null 檢查 → 500 而非 404 | `BookmarkController.java:40-41,50-51` | OPEN |
| AUTH-09 | Info | highlight/comment 對「不存在」vs「屬他人」回不同錯誤碼（列舉 oracle） | `HighlightService.java:64-68,77-81` | OPEN |
| AUTH-10 | Info | 權限命名不一致（AdminTag 用 SYSTEM_CONFIG；version 端點用 isAuthenticated 靠 service 兜） | `AdminTagController.java:46,60` | OPEN |
| AUTH-11 | Info | admin 改/刪他人留言未記 operator id（無稽核軌跡）；getUserFiles 收 Pageable 未套用 | `CommentService.java:146-150`、`FileServiceImpl.java:252-255` | OPEN |

---

## RACE — 併發 / 競態

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| RACE-01 | High | like/comment-like/version `try save catch DIVE` → 交易 rollback-only → 雙擊按讚回 500 | `ArticleLikeService.java:41-57`、`CommentLikeService.java:40-61`、`VersioningService.java:57-75` | OPEN |
| RACE-02 | High | nickname 無 DB UNIQUE 兜底 → 併發註冊/改名可產生永久重複暱稱 | `V1__init_schema.sql:12`、`AuthService.java:108`、`UserService.java:92` | OPEN |
| RACE-03 | Medium | 註冊 email/username 撞 UNIQUE 的 DIVE 無人接 → 500 而非 EMAIL_DUPLICATED | `AuthService.java:102-137` | OPEN |
| RACE-04 | Medium | bookmark 無 DIVE 處理 → 雙擊收藏 500 | `BookmarkService.java:29-39` | OPEN |
| RACE-05 | Medium | 文章併發編輯實質 last-write-wins（`@Version` 有但 DTO 不帶 version，靜默覆蓋） | `Article.java:130-131`、`ArticleCommandSubService.java:127,160-166`、`UpdateArticleRequest` | OPEN |
| RACE-06 | Medium | publish/reject/submit/seriesAssign/restore 未處理 OptimisticLockingFailure → 500/DLQ | `ArticleCommandSubService.java:259,305,327,479`、`ArticleFacadeImpl.java:396` | OPEN |
| RACE-07 | Medium | `tags.usage_count` 為 entity 讀改寫（無 @Version）→ 併發 lost update | `TagUsageConsumer.java:66-73` | OPEN |
| RACE-08 | Medium | tag findOrCreate 撞 UNIQUE → 整個 createArticle 交易 rollback → 發文 500 | `TagNormalizationServiceImpl.java:61-72`、`ArticleCommandSubService.java:85-101` | OPEN |
| RACE-09 | Medium-Low | 留言併發雙刪 → comment_count 雙扣（softDelete SQL 缺 `AND deleted_at IS NULL` 守衛） | `CommentService.java:174-189`、`CommentMapper.java:98` | OPEN |
| RACE-10 | Low | login 裝置數修剪非原子（ZADD→ZCARD→ZPOPMIN），短暫超 3 裝置/誤踢 | `AuthService.java:224-229` | OPEN |
| RACE-11 | Low | INCR-then-EXPIRE 非原子 → EXPIRE 漏設則計數器無 TTL → 登入失敗計數 = 帳號永久鎖定 | `AuthService.java:198-201,301-304,347-361,403-417,566-569` | OPEN |
| RACE-12 | Low | 檔案配額 check-then-act，併發上傳最多超額 N×5MB | `FileServiceImpl.java:108-111` | OPEN |
| RACE-13 | Low-Med | ReadingProgressFlushJob 讀後 SREM 視窗 → 併發更新的進度標記被抹 → 回退 | `ReadingProgressFlushJob.java:46-64` | OPEN（與 DATA-08 同源） |
| RACE-14 | Low | series article_count isNewMember 為 check-then-act → 併發雙加 +2 | `SeriesService.java:141-166` | OPEN |
| RACE-15 | Low | series slug check-then-save 撞 UNIQUE → 500 而非 SLUG_ALREADY_USED | `SeriesService.java:80,103` | OPEN |
| RACE-16 | Info | `@Transactional` 橫跨 MinIO removeObject（deleteFile）——可用性耦合 | `FileServiceImpl.java:189-216` | OPEN |
| RACE-17 | Info | TrendingRefreshJob 鎖過期（120s）+ 共用 tmpKey → 可能發布殘缺排行 | `TrendingRefreshJob.java:74-96,125-128` | OPEN |

---

## DATA — 資料一致性 / 跨儲存

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| DATA-01 | High | `tags.usage_count` 只增不減 + 同文章每次編輯/發布重複累加 → 膨脹致 tag 永久無法刪 | `TagUsageConsumer.java:66-74`、`ArticleCommandSubService.java:107,199,274`、`TagServiceImpl.java:207` | OPEN |
| DATA-02 | High | deleteAccount 只改 status，DELETED 使用者內容在所有公開讀取路徑殘留 | `UserService.java:171-185`、`ArticleMapper.java:41`、`CommentMapper.java:38` | OPEN（Q2 定案：匿名化）|
| DATA-03 | High | restore 將 PUBLISHED 靜默降 DRAFT → 無 ES 刪除、series 不清、count 不減（含 AUTH-01） | `VersioningService.java:252-262`、`ArticleFacadeImpl.java:391-405` | OPEN |
| DATA-04 | Medium-High | ES 刪除依賴 best-effort MQ，且 `reindexAll` 只 saveAll 不刪殘留 → 幽靈文件 reindex 也修不掉 | `ArticleEventPublisher.java:149-169`、`SearchServiceImpl.java:200-208` | OPEN |
| DATA-05 | Medium | ES 文件 viewCount/likeCount 硬編 0（增量索引），`sort=hot` 失效、DTO 回傳假計數 | `ArticleSearchListener.java:141-142`、`SearchServiceImpl.java:115-117,251-252` | OPEN |
| DATA-06 | Medium | comment_count（每刪-1）與留言區 totalAll（top-level 墓碑保留）規則分歧，永不收斂 | `CommentService.java:187-188`、`CommentMapper.java:81-83` | OPEN |
| DATA-07 | Medium | 刪文章不清理 MinIO 圖片 + file_metadata（無訂閱 article.deleted）→ 儲存與配額永久洩漏 | `ArticleCommandSubService.java:215-237`、`FileRabbitMqConfig` | OPEN |
| DATA-08 | Medium | ReadingProgressFlushJob lost-update 視窗 → hash TTL 3 天後進度回退 | `ReadingProgressFlushJob.java:46-64`、`RedisKeyConstant.java:370` | OPEN（同 RACE-13） |
| DATA-09 | Low | ViewCount flush 崩潰視窗遺失該批；`article:views:*` 24h TTL job 停擺則蒸發 | `ViewCountServiceImpl.java:94-121`、`RedisKeyConstant.java:118` | OPEN |
| DATA-10 | Low | series.article_count 靠 best-effort MQ；deleteSeries 留 series_position 髒值、無 recount | `ArticleCommandSubService.java:235-236`、`SeriesService.java:113-121` | OPEN |
| DATA-11 | Low | 計數型 consumer 缺冪等（僅 series 有）；ArticleTagEvent 無 eventId | `SeriesArticleDeletedConsumer.java:56`、`ArticleTagEvent.java:22` | OPEN（= 工作包 C） |
| DATA-12 | Low | 對 DRAFT/PENDING 文章可留言/按讚，預先污染計數（recordView 反而有檢查——三計數器不一致） | `CommentService.java:62-64`、`ArticleLikeController.java:63-68` | OPEN（同 AUTH-05） |
| DATA-13 | Low | `recommend:related:*` 快取內嵌已刪文章至多 1 小時 | `RecommendServiceImpl.java:82-87` | OPEN |
| DATA-14 | Low | thumbnail 競態產生 MinIO 孤兒（刪除早於 consumer 設 hasThumbnail） | `ThumbnailConsumer.java:100-103`、`FileServiceImpl.java:204` | OPEN |

---

## 已驗證安全/一致（覆蓋佐證，摘要）

- **AUTH**：article CUD/read（checkWritePermission / checkReadPermission，非 PUBLISHED 回 NOT_FOUND 無列舉洩漏）、comment edit/delete ownership、reading 全模組複合鍵刪除、series attach 雙重檢查（不能掛他人文章）、version promote/delete/restore ownership + assertVersionBelongsToArticle、preference 僅吃 principal、user /me/* 無 target 參數、register 硬編 Role.USER、search history 綁 userId、search/recommend 全鏈路強制 PUBLISHED。
- **RACE**：article_likes/comment_likes/bookmarks/tag_follows UNIQUE 約束存在；unlike/unbookmark 以 affected rows 決定 decrement；article/comment/series 計數為原子 SQL 帶 underflow 守衛；tag follow 用 ON CONFLICT；IdempotencyService 用 ON CONFLICT + REQUIRES_NEW（at-most-once 為刻意）；文章 slug 隨機後綴 + UNIQUE；瀏覽去重 SETNX；ViewCount 用 GETDEL；MQ 皆 commit 後發送。
- **DATA**：article like_count/comment_count（單則層級）增減對稱；文章硬刪 DB 級聯完整（V12–V16 ON DELETE CASCADE）；檔案配額即時 SUM 無漂移；檔案上傳/刪除 MinIO↔DB 補償；trending 每 30 分自 DB 重建 + 讀取端過濾 PUBLISHED；tag/category 刪除前置防護；軟刪留言讀取過濾統一。

---

## 建議修復批次（依 ROI；實際排程待 Yuan 定）

1. **T2 冪等反模式**（RACE-01/03/04/08/15）：統一改 ON CONFLICT + GlobalExceptionHandler 補 DIVE/OptimisticLock 兜底。一次消除一整類 500。
2. **T3 計數器**（DATA-01 tag 重設計最優先、DATA-06、RACE-07/09）。
3. **RACE-02** nickname unique migration（新 Flyway V19 + 更新 schema.md）。
4. **T1 審核狀態機**（AUTH-01/02 + DATA-03）——**先答 Q1**。
5. **T4 刪除連鎖**（DATA-02/04/07）——**先答 Q2**。
6. **RACE-05** editor expectedVersion（前後端協同）。
7. 其餘 Low/Info 依運維痛感穿插；DATA-11 併入工作包 C。
