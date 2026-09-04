# blog-web-v2 性能審查報告（develop @ f0e3cb1）

- **審查對象**：`origin/develop`（f0e3cb1），唯讀 worktree `.worktrees/review-develop`
- **方法**：靜態分析（rg / git / 讀檔）。**未執行 Maven、未跑測試、未修改任何檔案、未派 subagent**。索引查證只讀 `ai-docs/schema.md` 對應表的區塊，並以 `blog-db-migration/src/main/resources/db/migration/V*.sql` 的 `CREATE INDEX` 全清單交叉驗證（兩者一致）。
- **維度**：性能（資料存取 / SQL 與索引 / 交易邊界 / 快取 / MQ / ES / 檔案 / 連線池）
- **基線**：`git show docs/audit-findings-registry:ai-docs/findings.md`（無 PERF 專章；性能相關者為 RACE-16/17、DATA-04/08/09/11/13、FILE-04/05）
- **與另兩維度的關係**：`be-review-security.md`（SEC-01~28）、`be-review-architecture.md`（ARCH-01~28）。重疊處標「同 SEC-xx / ARCH-xx」；只在**量化解讀不同**時另立 finding。

---

## 1. 摘要

- 共 **38 條**：**CRITICAL 0 / HIGH 4 / MEDIUM 25 / LOW 9**。
- **最該先修的三件事**：
  1. **PERF-01（HIGH）ES 全量重建是 1+3N 的 N+1**：`ArticleFacadeImpl.findAllPublishedForIndex()` 對每篇文章各發 3 條 SQL（tags / authorUsername / authorNickname），同時把全站 `SELECT *`（含 `content_md`+`content_html`+`toc`）的三份副本放進堆，由 admin 端點**同步**跑完才回應。全 repo 最貴的單一操作。
  2. **PERF-03（HIGH）`batchGetProgress` 名為批次、實為 N 次序列 Redis round-trip ＋ 整頁文章的第二次 `SELECT *`**：每個登入使用者的每次列表請求都觸發；`size=100` ⇒ 100 次 HGETALL ＋ 100 篇文章的完整內文被無謂重撈。「已批次化」的假象在此破功。
  3. **PERF-02（HIGH）列表作者解析 N+1**：`ArticleResponseMapper` 逐篇呼叫 `UserFacade.getUserUuidById`/`getUserNicknameById`，各自 `SELECT * FROM users`；**`UserFacade` 介面根本沒有批次方法**。tags/categories/liked/bookmarked/series 全部正確批次化，唯獨作者漏掉。
- **放大器**：五組列表端點 `size`/`page` 無上下界（PERF-05，同 SEC-04）。`GET /api/v1/articles?size=1000` 匿名 ≈ **2005 條 SQL**；帶 token 再加 1000 次 Redis RTT。另 `GET /api/v1/articles/archive` 連分頁參數都沒有（PERF-04）。
- **索引缺口成家族**：`articles.published_at`、`article_tags.tag_id`、`user_tag_follows.tag_id` 皆無索引；5 張 `ON DELETE CASCADE` 指向 `articles(id)` 的子表沒有 `article_id` 前導索引（PERF-07/08/10/11）。
- **設定面全空白**：`application*.yaml` 沒有任何 Hikari / Redis / Rabbit prefetch / concurrency / 排程池設定；且 `RabbitMqConfig` 手刻 `SimpleRabbitListenerContainerFactory`，使 `spring.rabbitmq.listener.simple.*` 被**靜默忽略**（PERF-15/16）。
- **正面對照組（已驗證做對）**：`ArticleQueryService.enrich` 的 liked/bookmarked/series 批次；`CommentMapper` 全套 JOIN + IN 批次 ＋ V13/V14 的 partial 複合索引；`VersionMapper.listSummaries` 明確選欄 + `length(content)`；`/api/v1/files/{id}/content` 走 302 + presigned + `private, max-age=240`（圖片位元組不經應用伺服器）；Transaction+MQ 時序全站合規；`ViewCountServiceImpl` 用 SCAN 不用 KEYS、GETDEL 不用 GET+DEL。

---

## 2. Findings（按 severity 排序）

### HIGH

---

#### PERF-01（HIGH）｜ES 全量重建是 1+3N 的 N+1，全站內文三份副本同時進堆，且同步阻塞 request thread

- **file:line**
  - `blog-module-article/src/main/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImpl.java:114-119`（`findAllPublishedForIndex()` = `articleMapper.findAllPublished().stream().map(this::toIndexData)`）
  - 同檔 `:186-207`（`toIndexData()`：`:187` `articleMapper.findTagsByArticleUuid(...)`、`:199-200` `userFacade.getUserUsernameById(...)` + `getUserNicknameById(...)`）
  - `blog-module-article/.../mapper/ArticleMapper.java:149-150`（`SELECT * FROM articles WHERE status='PUBLISHED' ORDER BY published_at DESC`，無 LIMIT）
  - `blog-module-search/.../service/SearchServiceImpl.java:202-220`（`reindexAll()`）；`blog-module-search/.../controller/AdminSearchController.java:43-48`（`POST /api/v1/admin/search/reindex` 同步呼叫）
  - 附帶：`ArticleFacadeImpl.java:214-227`（`stripMarkdown` 10 條 `replaceAll`，pattern 未預編譯為 `static final`，逐篇全文執行 N 次）
- **違反 / 情境**：效能簡報 §1（N+1）、§6（索引重建是否全量阻塞）。N 篇已發布文章 ⇒ **1 + 3N 條 SQL**（tag 1 條 + `SELECT * FROM users` 2 條；同一作者每篇都重查一次，`UserFacadeImpl.java:36-64` 用 `CrudRepository.findById`，無任何快取）。記憶體側同時存在三份：`List<Article>`（含 `content_md`+`content_html`+`toc`）、`List<ArticleIndexData>`（stripMarkdown 後純文字）、`List<ArticleDocument>`。整段在 HTTP request 執行緒內同步跑完，無逾時保護。
- **建議修法**：(a) 改一條 JOIN 查詢（articles ⨝ users ⨝ article_tags ⨝ tags）＋明確選欄（不要 `content_html`/`toc`）；(b) keyset 分批 + ES bulk；(c) 端點改非同步（202 + 進度查詢）；(d) `stripMarkdown` 的 Pattern 預編譯。
- **基線對應**：**新**（基線 DATA-04 只談「reindexAll 不刪殘留」的一致性面，未指出 N+1 與記憶體）。關聯 PERF-13。

---

#### PERF-02（HIGH）｜文章列表作者解析 N+1：每篇 2 條 `SELECT * FROM users`，`UserFacade` 介面無批次方法

- **file:line**
  - `blog-module-article/.../service/ArticleResponseMapper.java:116-117`（`toSummaryResponse` 內 `resolveAuthorUuid` / `resolveAuthorNickname`）、`:62-63`（`toResponse` 同樣兩次）、`:191-197`（兩個 resolve 方法本體）
  - `blog-infrastructure/.../facade/UserFacade.java:17-42`（介面只有三個單筆方法，**無任何批次方法**）
  - `blog-module-user/.../facade/UserFacadeImpl.java:36-64`（三個方法各自 `userRepository.findById(userId)` ⇒ Spring Data JDBC `SELECT * FROM users WHERE id=?`）
  - 呼叫鏈：`ArticleQuerySubService.java:167-173`（`toSummaryResponses`）供 `:59-96` 的 `getPublishedArticles` / `getPublishedArticlesByCategorySlug` / `getMyArticles` / `getPendingArticles` 使用
- **違反 / 情境**：效能簡報 §1「跨模組 Facade 是否提供批次版本、呼叫端是否用了」。同一份檔案裡 `batchToTagResponsesMap`（`:152-165`）、`batchToCategoryResponsesMap`（`:174-189`）都正確批次化，`ArticleQueryService.enrich()`（`ArticleQueryService.java:206-261`）連 liked/bookmarked/progress/series 都批次化了——**作者是唯一漏網的**。即使整頁同一作者也照樣查 2N 次，且拉回 `users` 全欄（含 `password_hash`）。`size=10` 的公開列表共約 25 條 SQL，其中 20 條出自這裡。
- **建議修法**：`UserFacade` 加 `Map<Long, UserBasicInfo> getUsersByIds(Collection<Long>)`，比照 `batchToTagResponsesMap` 模式在 `toSummaryResponses` 先建 map；順手把 `SELECT *` 收斂成只選 `id, uuid, nickname, username`。
- **基線對應**：**新**。關聯 **ARCH-04**（`ArticleQueryService` 跨模組查詢）——本條是同一區域的量化版：不只是分層問題，在 N 篇文章下直接放大成 2N 條 SQL。

---

#### PERF-03（HIGH）｜`batchGetProgress` 是 N 次序列 Redis round-trip ＋ 整頁文章的第二次 `SELECT *`

- **file:line**
  - `blog-module-reading/.../service/ReadingProgressService.java:84-113`
    - `:89` `articleFacade.findByIds(articleIds)` → `ArticleFacadeImpl.java:302-306` → `ArticleQuerySubService.java:145-152` → `articleRepository.findAllById(ids)` ＝ **`SELECT * FROM articles WHERE id IN (...)`，含 `content_md`/`content_html`/`toc`**，只為取出 `ArticleData` 的 6 個純量（`blog-infrastructure/.../facade/dto/ArticleData.java:29-36`）
    - `:95-106` `for (entry : idToUuid.entrySet()) { redisTemplate.opsForHash().entries(key) }` ＝ **每篇一次 HGETALL，無 pipeline、無 MGET**
  - 呼叫端：`blog-module-article/.../service/ArticleQueryService.java:253`（`enrich` 內；每個登入使用者的每次列表請求）
- **違反 / 情境**：效能簡報 §1。方法名與 JavaDoc 都宣稱「批次、避免 N+1」，實際是 Redis 側的 N+1，且 DB 側重複撈了列表查詢**剛剛才撈過**的同一批 row（第二次含大 TEXT 欄位）。`size=100` 的登入列表 ⇒ 100 次序列 Redis RTT ＋ 100 篇文章的完整內文被搬運兩次。同模組的 `batchIsLiked`（`ArticleLikeService.java:91-96`）與 `batchIsBookmarked`（`BookmarkService.java:53-58`）都是單條 SQL，形成刺眼反差。
- **建議修法**：(a) uuid 已在 `enrich` 內由 `articleMapper.findIdsByUuids` 取得，改讓 `ReadingFacade.batchGetProgress` 直接收 `Map<Long,UUID>`，刪掉 `findByIds`；(b) N 次 HGETALL 改單次 `executePipelined`，或直接一條 `findByUserIdAndArticleIdIn` 走 DB（表上已有 `uq_user_reading_progress_user_article`）。
- **基線對應**：**新**。

---

#### PERF-04（HIGH）｜`GET /api/v1/articles/archive` 匿名、無分頁、全表 `SELECT *`、`ORDER BY published_at`（該欄無索引）、無快取

- **file:line**
  - `blog-module-article/.../controller/ArticleController.java:97-99`（`@GetMapping("/archive")`，公開，無任何參數）
  - `blog-module-article/.../service/ArticleQuerySubService.java:98-117`（`getArchive()`：`articleMapper.findAllPublished()` → `batchToTagResponsesMap(全部 uuid)`）
  - `blog-module-article/.../mapper/ArticleMapper.java:149-150`（`SELECT *` 全欄、無 LIMIT、`ORDER BY published_at DESC`）
  - `ai-docs/schema.md` §articles Indexes：只有 `idx_articles_author_id` / `idx_articles_status` / `idx_articles_created_at` / `idx_articles_series_position`——**`published_at` 無索引**（`V2__add_article_indexes.sql:4-7` 佐證）
- **違反 / 情境**：效能簡報 §2「無上限的分頁」「`SELECT *` 大文字欄位在列表查詢被拉出來」。單一匿名 GET 就把**全站文章語料**（`content_md`/`content_html`/`toc` 皆為 TEXT，`ai-docs/schema.md:82-84,96`）撈進堆，只為輸出 uuid/title/slug/publishedAt/tags 五個欄位；接著 `findTagsByArticleUuids` 用 `<foreach>` 組出含**全部文章 UUID** 的 `IN (...)`（`ArticleMapper.java:217-224`），文章數成長後 SQL 文字本身就是問題。排序欄位無索引 ⇒ 每次全表掃 + sort。無任何快取。
- **建議修法**：(a) 換成明確選欄的輕量查詢（比照 `VersionMapper.listSummaries`）；(b) 加 `CREATE INDEX idx_articles_published_at ON articles (published_at DESC) WHERE status='PUBLISHED'`（同時解 PERF-08）；(c) 結果加 Redis 快取（歸檔頁本質低頻變動）或改分頁 / 按年份切。
- **基線對應**：**新**。

---

### MEDIUM

---

#### PERF-05（MEDIUM）｜五組列表端點 `size`/`page` 無上下界 → 把其餘每一條 N+1 都變成匿名可觸發的放大器

- **file:line**：`ArticleController.java:76-79`（公開）、`:215-217`；`AdminArticleController.java:50-53`；`CommentController.java:74-77`（匿名可存取）；`BookmarkController.java:60-61`；`VersionController.java:64-65`；`SearchController.java:61-62`（公開）。全部是 `@RequestParam(defaultValue=...) int size`，**無 `@Max`/`@Min`**。`PageResult.of`（`blog-common/.../api/response/PageResult.java:57-67`）也不夾。對照組：`SeriesService.java:48,61-63` 有 `MAX_PAGE_SIZE=100` + `Math.min/Math.max`。
- **違反 / 情境**：效能簡報 §2。**同 SEC-04**（安全維度已列 MEDIUM）。本條的獨立價值是量化耦合：`GET /api/v1/articles?size=1000` 匿名 ⇒ `1(page)+1(count)+1(tags)+2000(PERF-02)+2(enrich)` ≈ **2005 條 SQL**；帶 token 再加 PERF-03 的 1000 次 Redis RTT 與第二次全頁 `SELECT *`。另 `offset = (page-1)*size` 未夾（`ArticleQuerySubService.java:60,68,76,91`），`page` 無上界 ⇒ 深 OFFSET 掃描。
- **建議修法**：把 `SeriesService` 的夾範圍寫法抽成 `blog-common` 的共用 helper，套到五組端點；或在 DTO 上用 `@Min/@Max` + `@Validated`。
- **基線對應**：**新**（PERF 角度）；安全面同 SEC-04。

---

#### PERF-06（MEDIUM）｜列表與詳情一律 `SELECT *`，把三個 TEXT 欄位拖進所有讀取路徑

- **file:line**：`ArticleMapper.java:41`（`findPublishedPage`）、`:61`、`:76`、`:108`、`:149`、`:178`（`a.*`）、`:282`（`findBySeriesIdOrderByPosition`）；`ArticleQuerySubService.java:129`、`:150`（`articleRepository.findAllById`）。列表 DTO `ArticleSummaryResponse` / `ArticleArchiveResponse` 完全不含這三欄（`ArticleResponseMapper.java:110-130`、`ArticleQuerySubService.java:107-115`）。
- **違反 / 情境**：效能簡報 §2。`ai-docs/schema.md:82-84,96`：`content_md` TEXT NOT NULL、`content_html` TEXT、`toc` TEXT。同專案已有正確範本（`blog-module-version/.../mapper/VersionMapper.java:52-66` 明確選欄 + `length(content) AS content_length`）卻沒套到 article。與 PERF-03/PERF-20 相乘：同一批 row 的大 TEXT 在單次請求裡最多被搬三趟。
- **建議修法**：列表路徑另建輕量投影（`ArticleRecommendMapper.xml` 已示範 `ArticleSummaryRow`），或至少在列表 SQL 明列欄位。
- **基線對應**：**新**。

---

#### PERF-07（MEDIUM）｜`article_tags.tag_id` 無索引 → 相關文章推薦與標籤刪除前檢查皆全表掃

- **file:line**
  - `blog-module-article/src/main/resources/mapper/ArticleRecommendMapper.xml:20-30`（`findByTagIds`：`JOIN article_tags at ON a.uuid = at.article_id ... WHERE at.tag_id IN (...) ORDER BY a.view_count DESC`）
  - `blog-module-tag/.../repository/ArticleTagRepository.java:61-62`（`SELECT COUNT(*) FROM article_tags WHERE tag_id = :tagId`）
  - `blog-module-tag/.../repository/UserTagFollowRepository.java:59-60`（`DELETE FROM user_tag_follows WHERE tag_id = :tagId`）
  - `ai-docs/schema.md` §article_tags Indexes：只有 `article_tags_pkey` on **(article_id, tag_id)**；§user_tag_follows：只有 PK on **(user_id, tag_id)**。兩者 `tag_id` 都非前導欄位 ⇒ B-tree 用不上
- **違反 / 情境**：效能簡報 §2「缺索引的查詢」。`findByTagIds` 在**每篇文章詳情頁的相關推薦 cache miss** 都會跑（`RecommendServiceImpl.java:154-155`），成本是 `article_tags` 全表掃 ＋ `ORDER BY view_count`（該欄亦無索引）。中間表缺反向索引是教科書級的 junction-table 漏洞。
- **建議修法**：Flyway 新增 `CREATE INDEX idx_article_tags_tag_id ON article_tags(tag_id);` 與 `CREATE INDEX idx_user_tag_follows_tag_id ON user_tag_follows(tag_id);`，並依 CLAUDE.md §Schema Maintenance 同步更新 `ai-docs/schema.md`。
- **基線對應**：**新**。

---

#### PERF-08（MEDIUM）｜`articles.published_at` 無索引，但三個熱路徑都以它篩選或排序

- **file:line**
  - `ArticleRecommendMapper.java:100-107`（`findPublishedAfter`：`WHERE a.published_at >= ? AND status='PUBLISHED'`）——由 `TrendingRefreshJob.refreshPeriod`（`blog-module-recommend/.../job/TrendingRefreshJob.java:105-107`）**每 30 分鐘呼叫 3 次**（24h / 7d / 30d，見 `:59-63,74`）
  - `ArticleRecommendMapper.java:68-82`（`findRecentPublished`：`ORDER BY a.published_at DESC LIMIT ?`）——相關推薦第三層降級（`RecommendServiceImpl.java:177`）
  - `ArticleMapper.java:149`（`findAllPublished`：`ORDER BY published_at DESC`）——archive + reindex
  - `ai-docs/schema.md` §articles Indexes 無 `published_at`（V1–V21 的 `CREATE INDEX` 全清單亦無）
- **違反 / 情境**：效能簡報 §2。目前是**每 30 分鐘 3 次全表掃**；且 30d 的結果本身就是 7d、24h 的超集，三次查詢可合併為一次。
- **建議修法**：`CREATE INDEX idx_articles_published_at ON articles (published_at DESC) WHERE status='PUBLISHED'`（partial，同時服務 PERF-04）；`TrendingRefreshJob` 改為查一次 30d 再在記憶體切三個視窗。
- **基線對應**：**新**。

---

#### PERF-09（MEDIUM）｜公開列表缺 `(status, created_at DESC)` 複合索引，且每頁重算 `COUNT(*)`

- **file:line**：`ArticleMapper.java:41`（`WHERE status='PUBLISHED' ORDER BY created_at DESC`）＋ `:49-50`（`countPublished`）；`:108` + `:116-117`（PENDING_REVIEW 同型）；呼叫端 `ArticleQuerySubService.java:61-62,92-93`。`ai-docs/schema.md` §articles：`idx_articles_status`（單欄）與 `idx_articles_created_at`（單欄）兩個**分離**索引。
- **違反 / 情境**：效能簡報 §2「缺索引的查詢」「COUNT(*) 每頁重算」。兩個單欄索引無法同時滿足「篩 status + 依 created_at 排序 + LIMIT」；planner 只能二選一（走 created_at 索引逐列過濾 status，或 bitmap status 後全量排序），深 OFFSET 時退化明顯。`countPublished()` 每次翻頁都重掃一次。
- **建議修法**：`CREATE INDEX idx_articles_status_created ON articles (status, created_at DESC)`；分頁改 keyset（`WHERE created_at < ?`），或把 total 快取到 Redis（分鐘級 TTL 即可）。
- **基線對應**：**新**。

---

#### PERF-10（MEDIUM）｜5 張以 `ON DELETE CASCADE` 指向 `articles(id)` 的子表沒有 `article_id` 前導索引 → 刪一篇文章觸發 5 次全表掃

- **file:line**（皆據 `ai-docs/schema.md` 對應表 Indexes 區塊，並與 V12–V16 migration 交叉驗證）
  - `user_article_likes`：唯一索引 `uq_user_article_likes_user_article (user_id, article_id)` — `article_id` 非前導
  - `user_bookmarks`：`uq_user_bookmarks_user_article (user_id, article_id)` — 同上
  - `user_highlights`：`idx_user_highlights_article_for_user (user_id, article_id)`（名稱誤導，實為 user 前導）
  - `user_reading_progress`：`uq_user_reading_progress_user_article (user_id, article_id)`
  - `comments`：只有 partial `idx_comments_article_top_level (article_id, created_at DESC) WHERE parent_id IS NULL`（見 PERF-11）
  - 觸發點：`ArticleCommandSubService.java:215-237`（`deleteArticle` 硬刪）
  - 有做對的對照：`article_versions` 有 `idx_article_versions_article_created (article_id, created_at DESC)`；`article_categories` / `article_tags` 的複合 PK 以 `article_id` 前導
- **違反 / 情境**：效能簡報 §2。PostgreSQL 執行 `ON DELETE CASCADE` 時對每張子表發 `DELETE ... WHERE article_id = ?`；無可用索引 ⇒ **seq scan**。這五張正好是成長最快的互動表。刪文章是低頻操作，故列 MEDIUM，但資料量成長後會變成長時間持鎖。
- **建議修法**：一次 migration 補 `CREATE INDEX ... ON <table>(article_id)` ×5（`comments` 可與 PERF-11 合併）。
- **基線對應**：**新**。

---

#### PERF-11（MEDIUM）｜`comments.article_id` 沒有非-partial 索引 → 每次留言列表的 `countByArticle` 全表掃

- **file:line**：`blog-module-comment/.../mapper/CommentMapper.java:81-83`（`SELECT COUNT(*) FROM comments WHERE article_id = #{articleId} AND (parent_id IS NULL OR deleted_at IS NULL)`）；呼叫端 `CommentService.java:250`（`listComments` 每次都算）。`ai-docs/schema.md` §comments Indexes：三個索引全是 partial 或 user 前導，**無任何無條件含 `article_id` 的索引**。
- **違反 / 情境**：效能簡報 §2。`WHERE article_id=? AND (A OR B)` 的 OR 分支中，`deleted_at IS NULL` 那半沒有任何索引可用 ⇒ planner 退回 `comments` 全表掃。同一次請求裡 `findTopLevelByArticle`（`:31-51`）與 `countTopLevelByArticle`（`:86-87`）都能命中 partial 索引，唯獨這條不行。
- **建議修法**：`CREATE INDEX idx_comments_article_id ON comments (article_id)`（同時解 PERF-10 的 comments 項），或把 `totalAll` 拆成兩條可索引的 COUNT 相加。
- **基線對應**：**新**。

---

#### PERF-12（MEDIUM）｜ES `search()` 未做 `_source` 過濾，整篇 `content` 隨每筆 hit 回傳後被丟棄；且無 deep-paging 守衛

- **file:line**：`blog-module-search/.../service/SearchServiceImpl.java:110-123`（`NativeQuery.builder()` 無 `.withSourceFilter(...)`）；`:299-313`（`toSearchResult` 只取 title/summary/slug/author/tags/publishedAt/viewCount/likeCount，**`content` 完全沒被用到**）；`blog-module-search/.../document/ArticleDocument.java:58-59`（`content` 為 `FieldType.Text`，預設進 `_source`）；`:112` `PageRequest.of(page-1, size)` 直接吃未夾的參數（PERF-05）。
- **違反 / 情境**：效能簡報 §6「回傳欄位是否包含整篇 content」。每筆搜尋結果都把整篇去格式後的內文從 ES 搬到 app 再丟掉，隨每頁筆數線性放大。另 `from+size` 超過 ES 預設 `index.max_result_window`（10000）會直接丟例外 → 500。
- **建議修法**：加 `withSourceFilter(new FetchSourceFilter(new String[]{"title","summary","slug","author","tags","publishedAt","viewCount","likeCount"}, null))`；size/page 夾範圍並確保 `from+size` 不超過 window。
- **基線對應**：**新**。

---

#### PERF-13（MEDIUM）｜`reindexAll()` 仍不清舊索引、不分批（backlog 描述的缺口確認 100% 未修）

- **file:line**：`SearchServiceImpl.java:202-220`——`articleSearchRepository.saveAll(documents)` 前**無 `deleteAll()`、無 alias 切換、無分批**，與 `ai-docs/backlog/2026-07-29-index-cache-rebuild-completeness.md` 問題 1 的描述逐字吻合。backlog 問題 2（`tag:autocomplete` 無回填）同樣未修：全 repo 唯一寫入點仍是 `TagNormalizationServiceImpl.java:74`（僅建立新標籤時 ZADD），`TagServiceImpl.suggest`（`:69-80`）只讀不補。
- **違反 / 情境**：效能簡報 §6 ＋ §4（快取重建完整性）。已刪文章的 ES 幽靈文件永遠留著 → 索引體積只增不減、`documentCount` 虛高。單次 `saveAll` 對大語料是一次巨型 bulk 請求。
- **建議修法**：依 backlog「重建索引 = 重建所有衍生儲存」：create-new-index → 分批 bulk → alias 切換；同一流程掃 `tags` 表全量回填 `tag:autocomplete`。
- **基線對應**：對應基線 **DATA-04**（Medium-High，**仍 OPEN、零進展**）。

---

#### PERF-14（MEDIUM）｜`article.published` queue 有綁定無 consumer，且每則訊息帶整篇內文 → 永久堆積的是「全站語料副本」

- **file:line**
  - Queue 宣告：`blog-module-article/.../config/ArticleRabbitMqConfig.java:35`（`QUEUE_PUBLISHED = "article.published"`）、`:98`（durable + DLQ args）、`:107-112`（`articlePublishedBinding` 綁到 `article.events` / rk `article.published`）
  - Producer：`blog-module-article/.../service/ArticleEventPublisher.java:115-134`（`publishPublished`；`:122` `stripMarkdown(article.getContent())`）
  - Payload：`blog-infrastructure/.../event/ArticlePublishedEvent.java:30-40`（含 `String contentText`）
  - Consumer：`rg "@RabbitListener"` 全 repo 命中 9 個 listener，**沒有任何一個訂閱 `article.published`**（同 rk 的 `queue.search.index`、`recommend.article.published` 各有自己的 queue 與 consumer）
- **違反 / 情境**：效能簡報 §5。**同 ARCH-05**（架構面已列 MEDIUM）。本條的獨立量化解讀：堆積速率不是「一則小訊息」而是**每次發布一份完整文章純文字**，且 queue 是 durable + 帶 DLQ args（不會過期、不會被丟棄）。RabbitMQ 觸及 memory/disk high watermark 時會對**所有** publisher 連線施加 flow control；本專案 MQ 送出都在 request 執行緒內（commit 後，`publisher-confirm-type: correlated` + `mandatory=true`，`application.yaml:25-26`），一旦 broker 阻塞，發布/更新文章的 HTTP 請求會直接掛住。
- **建議修法**：確認無下游需求就刪 queue + binding（最省）；若保留為未來擴充，至少加 `x-max-length` / `x-message-ttl`，並把 `contentText` 從 payload 拿掉改由 consumer 回查。
- **基線對應**：**新**（PERF 角度）；架構面同 ARCH-05。

---

#### PERF-15（MEDIUM）｜連線池 / prefetch / concurrency 全無設定，且手刻的 listener factory 使 `spring.rabbitmq.listener.simple.*` 被靜默忽略

- **file:line**
  - `blog-start/src/main/resources/application.yaml`（全檔 132 行）、`application-dev.yaml`、`application-demo.yaml`：`rg "Prefetch|Concurrent|hikari|pool-size"` 於全 repo（排除 target）**只命中一個無關的測試方法名**——沒有任何 `spring.datasource.hikari.*`、Lettuce pool、`listener.simple.prefetch`、`listener.simple.concurrency`、`spring.task.scheduling.pool.size`
  - `blog-infrastructure/.../config/RabbitMqConfig.java:141-152`：`new SimpleRabbitListenerContainerFactory()` 從零手建，**未經 `SimpleRabbitListenerContainerFactoryConfigurer`** ⇒ Spring Boot 的 `spring.rabbitmq.listener.simple.*` 屬性一律不生效（`application.yaml:27-29` 的 `acknowledge-mode: manual` 之所以「有效」，是因為 `:149` 程式碼自己又設了一次）
- **違反 / 情境**：效能簡報 §8。實際生效的是框架預設：Hikari `maximum-pool-size=10` / `connection-timeout=30s`；`SimpleMessageListenerContainer` `concurrentConsumers=1`、`prefetchCount=250`。9 個 consumer 各 1 條執行緒、無法水平擴張；DB 池 10 條要同時扛 HTTP 請求（PERF-02/03 每請求數十至數千次連線取用——列表路徑無 `readOnly` 交易，每條 statement 各自借還連線，見 PERF-29）＋ 3 個排程 job ＋ 9 個 consumer。**最危險的是第二點**：未來有人在 yaml 加 `prefetch: 10` 或 `concurrency: 4` 會「設了沒反應」，且無任何錯誤訊息。
- **建議修法**：(a) `rabbitListenerContainerFactory` 改用 `SimpleRabbitListenerContainerFactoryConfigurer.configure(factory, connectionFactory)` 起手再套自訂項，讓 yaml 屬性重新生效；(b) 在 `application.yaml` 明確寫出 Hikari `maximum-pool-size`/`connection-timeout`/`max-lifetime` 與 listener `prefetch`/`concurrency`，即使值等於預設也讓它成為可見決策。
- **基線對應**：**新**。關聯 ARCH-02（同一個 factory 的 retry advice 是死碼）。

---

#### PERF-16（MEDIUM）｜排程執行緒池為預設單執行緒，3 個 job 串行；其中最重的 job 持 120s 鎖

- **file:line**：`blog-start/.../BlogWebV2Application.java:15`（`@EnableScheduling`），全 repo 無 `TaskSchedulerCustomizer` / `ThreadPoolTaskScheduler` bean / `spring.task.scheduling.pool.size` ⇒ Spring Boot 預設 `pool-size=1`。三個 job：`TrendingRefreshJob.java:74`（`fixedDelay=1800000`）、`ViewCountFlushJob.java:33`（`fixedDelay=300_000, initialDelay=60_000`）、`ReadingProgressFlushJob.java:34`（`fixedDelayString=${reading.progress.flush-interval-ms:300000}`）。
- **違反 / 情境**：效能簡報 §8 ＋ §5。`TrendingRefreshJob` 一次跑 3 條全表掃（PERF-08）＋ ZSet 重建，期間持有 `lock:trending-refresh`（TTL 120s，`:54,77-78`）；它一慢，同執行緒上的兩個 flush job 就整批延後，瀏覽計數與閱讀進度持久化跟著遲滯（放大基線 DATA-09 的遺失視窗）。
- **建議修法**：`spring.task.scheduling.pool.size: 3`（或自訂 `ThreadPoolTaskScheduler`）；`TrendingRefreshJob` 依 PERF-08 合併查詢以縮短鎖持有。
- **基線對應**：**新**；關聯基線 RACE-17（鎖過期，Info）、DATA-09。

---

#### PERF-17（MEDIUM）｜`TagServiceImpl.getHotTags()` 快取命中路徑仍是 N 次 `findById`，Redis 形同虛設

- **file:line**：`blog-module-tag/.../service/TagServiceImpl.java:85-106`——`:90` 從 `tag:hot` ZSet 取回 id+score，`:93-98` 對每個 id 各呼叫一次 `tagRepository.findById(id)`（完整 `SELECT * FROM tags WHERE id=?`）。回填路徑 `:103-105` 也是逐筆 ZADD（N 次 Redis round-trip）。
- **違反 / 情境**：效能簡報 §1 + §4。熱門標籤 widget 是多頁面共用的公開元件，「命中快取」卻仍發 N 條 SQL——快取只省下 `ORDER BY usage_count` 那一次排序，沒省下任何 round-trip。
- **建議修法**：改 `tagRepository.findAllById(ids)` 一次撈完再依 ZSet 順序重排；或把 tag DTO 序列化進 Redis（比照 `RecommendServiceImpl.getRelatedArticles` 的 JSON 快取）。回填改 `opsForZSet().add(key, Set<TypedTuple>)` 單次。
- **基線對應**：**新**。

---

#### PERF-18（MEDIUM）｜`tag:hot` 過期後被 `ZINCRBY` 以無 TTL 的殘缺狀態重建；`tag:detail:*` 24h 快取不隨 usage_count 更新失效

- **file:line**
  - 建立/續期：`TagServiceImpl.java:103-105`（miss → 由 DB top-20 回填 ZSet，之後才 `expire(TAG_HOT_KEY, 1h)`）
  - 破壞點：`blog-module-tag/.../consumer/TagUsageConsumer.java:90`（`opsForZSet().incrementScore(TAG_HOT_KEY, tagId, 1.0)`）
  - 陳舊點：`TagServiceImpl.java:152-161`（`tag:detail:{slug}` Hash 內含 `usageCount`，TTL 24h）vs `TagUsageConsumer.java:88-89`（增 `usage_count` 但**不刪** `tag:detail:{slug}`）；`adminUpdateTag`（`:197`）刪 detail 但不動 ZSet，`adminDeleteTag`（`:214-215`）兩個都刪
- **違反 / 情境**：效能簡報 §4「失效策略（寫入後有沒有正確 evict）」＋ backlog cache-rebuild 的同型病灶（衍生儲存只有增量寫入、沒有完整重建）。Redis `ZINCRBY` 對**不存在**的 key 會建立新 key 且**不帶 TTL**。因此只要 `tag:hot` 在 1h TTL 到期後、下一次 `getHotTags()` 之前有任何文章被打標籤，ZSet 就會以「只有那一個 tag、score=1」的殘缺狀態重生且**永不過期** ⇒ `getHotTags` 從此永遠走 cache-hit 分支，回傳錯誤榜單、再也不從 DB 重建。（**PLAUSIBLE**：未實測 Redis 行為，但 `ZINCRBY` 對缺失 key 的語意見官方文件；程式路徑已確認。）
- **建議修法**：(a) consumer 端在 `incrementScore` 後補 `expire(TAG_HOT_KEY, 1h)`（或用 Lua 只在 key 存在時 incr）；(b) `TagUsageConsumer` 一併 `delete(getTagDetailKey(slug))`；(c) 依 backlog 加一個「重建所有衍生 Redis 結構」的維運入口。
- **基線對應**：**新**；同源於基線 T3（計數器漂移）與 backlog `2026-07-29-index-cache-rebuild-completeness.md`。

---

#### PERF-19（MEDIUM）｜`processed_events` 只增不刪，無任何 cleanup job

- **file:line**：`blog-infrastructure/.../idempotency/IdempotencyService.java:58-63`（每則成功消費的事件都 `INSERT INTO processed_events`）；`blog-db-migration/.../V17__add_processed_events.sql:12-13`（`idx_processed_events_processed_at`，`ai-docs/schema.md` 註明「給未來 cleanup 用」）；`rg "processed_events"` 於主程式碼**只有 INSERT**，`DELETE` 僅出現在測試（`blog-start/.../DatabaseCleaner.java:29`、`CrossModuleSeriesIT.java:121,133`）。
- **違反 / 情境**：效能簡報 §5。表隨事件量單調成長，UNIQUE 索引 `(event_id, consumer_name)` 隨之膨脹；每則事件的 dedup 檢查都得走越來越大的索引。目前 3/9 consumer 使用它，未來依 roadmap 工作包 C 鋪滿後成長速率乘以 3。
- **建議修法**：加 `@Scheduled` cleanup（`DELETE FROM processed_events WHERE processed_at < now() - interval '30 days'`，分批），索引已就緒。
- **基線對應**：**新**；關聯基線 DATA-11 / roadmap 工作包 C——鋪得越滿，本條越痛。

---

#### PERF-20（MEDIUM）｜每篇文章詳情都為了 6 個純量欄位再撈一次整列 `SELECT *`；列表則無條件查 series

- **file:line**
  - 詳情：`blog-module-article/.../service/ArticleQueryService.java:275-280`（`enrichSingle` → `seriesFacade.getSeriesNavigation(articleId)`，**無條件呼叫**）→ `blog-module-series/.../facade/SeriesFacadeImpl.java:41-47`（`articleFacade.findById(articleId)`，只讀 `seriesId`/`seriesPosition` 就可能 early-return）→ `ArticleFacadeImpl.java:294-296` → `articleRepository.findById` ＝ `SELECT * FROM articles WHERE id=?`。目標 DTO `ArticleData` 只有 6 個純量（`ArticleData.java:29-36`）
  - 列表：`ArticleQueryService.java:226-227`（`if (includeSeriesNav) seriesFacade.batchGetSeriesBasicInfo(articleIds)` 無條件呼叫，之後才在 `:229` 逐筆判斷 `getSeriesPosition() != null`——判斷依據其實在呼叫前就已在記憶體）
- **違反 / 情境**：效能簡報 §1 §2。`GET /api/v1/articles/{uuid}` 一次請求的固定成本：`SELECT *`（詳情本體）＋ `findIdByUuid` ＋ **第二次 `SELECT *`（含三個 TEXT 欄位，只為 series 判斷）** ＋ tags ＋ categories ＋ viewCount（DB+Redis）＋ 2 條 `SELECT * FROM users`（PERF-02）。不屬於任何 series 的文章也照付。列表端則是多發一條多數情況下必然空手而回的查詢。
- **建議修法**：`ArticleFacade` 加輕量 `findSeriesRefById(Long)`，SQL 只選 `id, uuid, author_id, status, series_id, series_position`；`enrich` 先 `filter(r -> r.getSeriesPosition()!=null)` 取子集，空則跳過。
- **基線對應**：**新**；關聯 **ARCH-04**（跨模組查詢）——本條是同一現象的成本量化。

---

#### PERF-21（MEDIUM）｜version 自動快照為了比長度而把整份版本內容撈回 app，且同一份資料在一次事件內查兩遍

- **file:line**
  - `blog-module-version/.../service/AutoSnapshotPolicy.java:31-52`：`:32` `articleFacade.findContentById(articleId)`（整列 `SELECT *`）、`:35` `preferenceResolver.resolveForUser(...)`（1 條 SQL）、`:38` `versionRepo.findLatestByArticleAndType(...)`（**整列 `article_versions`，含 TEXT `content`**）、`:46-47` 只用了 `content.length()`
  - `blog-module-version/.../service/VersioningService.java:80-97`：`recordAutoSnapshot` **再次** `findContentById`（`:81`）＋ **再次** `resolveForUser`（`:84`）
  - 觸發：`ArticleVersionConsumer.java:47-52`（每個 `SAVED` 事件都先 `shouldSnapshot`）；`ArticleCommandSubService.java:120,213`（create/update 都發 `SAVED`）
- **違反 / 情境**：效能簡報 §1。同專案 `VersionMapper.java:54` 已示範 `length(content) AS content_length` 的正確寫法，這裡卻整份撈回來在 Java 端 `.length()`。編輯器自動存檔期間，每次存檔付 3 條查詢（含兩份大 TEXT），真的要快照時再付 4 條（其中 2 條與剛才重複）。
- **建議修法**：新增 `versionMapper.findLatestAutoMeta(articleId)` 只回 `created_at, length(content)`；把 `shouldSnapshot` 與 `recordAutoSnapshot` 合併為一次呼叫（article 與 config 一路傳下去）。
- **基線對應**：**新**。

---

#### PERF-22（MEDIUM）｜留言 replies 一次全撈、無上限

- **file:line**：`blog-module-comment/.../service/CommentService.java:219-221`（`commentMapper.findRepliesByParentIds(topLevelIds)`）；`CommentMapper.java:57-74`（SQL 無 LIMIT，且 `:60` 對每一列跑相關子查詢 `(SELECT p.uuid FROM comments p WHERE p.id = c.parent_id)`）。
- **違反 / 情境**：效能簡報 §2「無上限」。top-level 有分頁（但 size 無上界，PERF-05），replies 完全沒有——單一熱門討論串的所有回覆（含 `content` + `content_html` 兩個 TEXT 欄位）一次載入。`size=1000` 的 top-level ＋ 每則數十回覆 ⇒ 單次回應可達數十 MB。
- **建議修法**：每個 parent 取前 N 則（`ROW_NUMBER() OVER (PARTITION BY parent_id ORDER BY created_at)` ≤ N）＋「查看更多回覆」端點；`parent_uuid` 改用 JOIN 取代相關子查詢。
- **基線對應**：**新**。

---

#### PERF-23（MEDIUM）｜`FileServiceImpl.bindToArticle` 在單一交易內逐檔 `findById` + `save`（N+1 寫）

- **file:line**：`blog-module-file/.../service/FileServiceImpl.java:341-388`——`:360` `findByArticleUuid` 後 `:361-367` 逐筆 `save`（解綁）；`:373-388` 對每個 target `findById` + `save`（綁定）。全程包在 `@Transactional`（`:341`）內。觸發：`ArticleFileBinder.bindFilesToArticleSafely`，即**每次文章 create/update**（`ArticleCommandSubService.java:128,216`）。
- **違反 / 情境**：效能簡報 §1 + §3。一篇圖多的文章 ⇒ 2M+1 條 SQL 佔著同一條 Hikari 連線（池只有 10 條，PERF-15）。
- **建議修法**：解綁改單條 `UPDATE file_metadata SET article_uuid=NULL WHERE article_uuid=? AND id NOT IN (...)`；綁定改單條 `UPDATE ... WHERE id IN (...) AND uploader_id=?`（擁有權檢查直接寫進 WHERE，行為等價且更安全）。
- **基線對應**：**新**。

---

#### PERF-24（MEDIUM）｜上傳先整包讀進記憶體才驗大小，`spring.servlet.multipart` 完全未設定

- **file:line**：`FileServiceImpl.java:134`（`fileBytes = file.getBytes()`，全讀）在前，`:144`（`fileBytes.length > MAX_FILE_SIZE`，5MB，定義於 `:63`）在後；全 repo `rg "multipart"` 於 `application*.yaml` **零命中** ⇒ 實際生效的是 Spring Boot 預設 `max-file-size=1MB`。
- **違反 / 情境**：效能簡報 §7 的資源耗盡變體。程式碼宣稱 5MB、框架實際擋在 1MB，兩者矛盾；且驗證發生在整包緩衝之後。
- **建議修法**：設定 `spring.servlet.multipart.max-file-size` / `max-request-size` 對齊 5MB；理想上改 size-limited stream 邊讀邊擋。
- **基線對應**：**確認未變，對應基線 FILE-05**；同 **SEC-17**。

---

#### PERF-25（MEDIUM）｜縮圖無原始像素上限（decompression bomb）→ 打爆唯一的 thumbnail consumer 執行緒

- **file:line**：`blog-module-file/.../consumer/ThumbnailConsumer.java:84-85`（`Thumbnails.of(inputStream).width(300)`，解碼前未讀 header 檢查寬高）。
- **違反 / 情境**：效能簡報 §7。屬非同步 consumer，不卡上傳請求本身；但 `concurrentConsumers=1`（PERF-15）⇒ 單一惡意圖片就能讓整條 `file.thumbnail` queue 停擺或 OOM。
- **建議修法**：先以 `ImageIO.createImageInputStream` + `ImageReader.getWidth/getHeight` 讀 header，超過門檻（如 total pixels > 5e7）直接拒絕，不進入完整解碼。
- **基線對應**：**確認未變，對應基線 FILE-04**；同 **SEC-11**。

---

#### PERF-26（MEDIUM）｜`recommend:related:*` 無互斥鎖（stampede），且 cache key 未含 `limit` 造成命中即降級

- **file:line**：`blog-module-recommend/.../service/RecommendServiceImpl.java:78-105`——純 GET → compute → SET，無 per-key lock、無 stale-while-revalidate；`:79` `getRelatedKey(articleUuid)` **不含 limit**，`:86` 命中時 `result.size() > limit ? subList : result`。
- **違反 / 情境**：效能簡報 §4「cache stampede（熱門文章）」。miss 路徑要跑 `getPublishedArticleBasicInfo` ＋ `getArticlesByTagIds`（全表掃 `article_tags`，PERF-07）＋ ES `more_like_this` ＋ `getRecentPublishedArticles`（無索引 `published_at`，PERF-08）——全站第二貴的讀路徑，卻只有 1h TTL 保護，且掛在每個文章詳情頁上。第二個問題：若首個填充快取的請求帶 `limit=1`（`RecommendController.java:51` 允許 1~20），接下來 1 小時內所有 `limit=20` 的請求都只拿得到 1 筆，且因為是 cache hit 而不會重算。
- **建議修法**：cache key 加 limit，或一律以 MAX=20 計算後再 subList（寫入時保證存滿）；熱門文章加 `SETNX` 互斥鎖或 early-refresh。
- **基線對應**：**新**（stampede 面）；key 語意面關聯基線 DATA-13。

---

#### PERF-27（MEDIUM）｜文章存檔時的關聯同步全部逐筆化（tags 刪光重插、categories 逐個查）

- **file:line**
  - `blog-module-tag/.../facade/TagFacadeImpl.java:48-57`（`syncArticleTags`：`findTagsByArticleId` → `existingTags.forEach(deleteByArticleIdAndTagId)` → `tagIds.forEach(save)`）；`:60-63`（`deleteArticleTags` 同型）；`:37-45`（`findOrCreateTags` 逐個 `findOrCreate` → 每個 tag 一條 `findBySlug`，`TagNormalizationServiceImpl.java:61`）
  - `blog-module-article/.../service/ArticleCommandSubService.java:508-516`（`syncCategories`：迴圈內 `categoryRepository.findByUuid(...)` + `categoryMapper.insertArticleCategory(...)`）
  - 呼叫端：文章 create/update 的交易內（`ArticleCommandSubService.java:96-104,185-198`）
- **違反 / 情境**：效能簡報 §1。M 個舊 tag + K 個新 tag ⇒ `1 + M + K + K` 條 SQL 在同一交易內；無條件刪除重插會讓 `article_tags` 產生無謂 dead tuple。N 通常不大（個位數），故列 MEDIUM 而非 HIGH，但它落在寫入交易的關鍵區段內。
- **建議修法**：`DELETE FROM article_tags WHERE article_id=?`（單條）＋ 批次 `INSERT ... VALUES (...),(...) ON CONFLICT DO NOTHING`；`findOrCreateTags` 改一條 `WHERE slug IN (...)` 先撈已存在者，只對缺的 insert；`syncCategories` 改 `findAllByUuidIn` + 批次 insert。
- **基線對應**：**新**；關聯基線 RACE-08 / DATA-01（同一段程式的正確性問題——修 T2/T3 時可一併處理）。

---

#### PERF-28（MEDIUM）｜`FileServiceImpl.deleteFile` 的 `@Transactional` 橫跨兩次 MinIO 網路呼叫

- **file:line**：`blog-module-file/.../service/FileServiceImpl.java:241`（`@Transactional`）、`:250-255` 與 `:257-262`（兩次 `minioClient.removeObject`）。
- **違反 / 情境**：效能簡報 §3「`@Transactional` 範圍過大（包住外部 I/O）」。從資源佔用角度：MinIO 延遲或故障時，DB 交易與一條 Hikari 連線（池共 10 條，PERF-15）被佔住直到 MinIO 返回；MinIO client 無設定逾時 ⇒ 可能是分鐘級。同檔 `uploadFile` 已示範正確做法（用 `transactionTemplate` 把 DB 動作收斂、外部 I/O 移出）。
- **建議修法**：比照 `uploadFile` 的 `transactionTemplate` 模式，DB delete 在交易內、MinIO 刪除移出（best-effort 補償）。
- **基線對應**：**對應基線 RACE-16（Info，仍 OPEN）**——本報告從資源佔用角度重新定性為 MEDIUM。

---

#### PERF-29（MEDIUM）｜列表讀取路徑無 `@Transactional(readOnly = true)`，每條 statement 各自借還連線

- **file:line**：`ArticleQuerySubService.java:59-96`（四個分頁查詢方法皆無 `@Transactional`）、`:123-143`、`:167-173`；`ArticleQueryService.java:60-110,202-261`（`enrich` 亦無）；`ArticleServiceImpl.java:76-90`（三個 delegate 無標註）。對照：同檔 `:98`、`:158` 的 `getArchive` / `findBySeriesIdOrderByPosition` 有 `readOnly=true`；全 repo 63 個 `@Transactional` 中只有 13 個標了 `readOnly`。
- **違反 / 情境**：效能簡報 §3「讀操作沒 `readOnly`」。無交易 ⇒ 每條 MyBatis/Repository 呼叫各自 `getConnection`/回池，且喪失 `readOnly` 給 driver/DB 的提示與同一快照的一致性。與 PERF-02/03 相乘：`size=100` 的登入列表約 208 次連線借還，池只有 10 條。
- **注意（不要盲改）**：`ArticleServiceImpl.java:47-50,64-67` 的 JavaDoc 明確說明 `getArticleByUuid` / `getArticleBySlug` **刻意不開交易**，因為緊接的 `recordView` 會送 MQ（`code-standards.md` §Transaction+MQ / `judgment.md` §2 第一列）。本條只能加在**純讀、不發 MQ** 的列表方法上。
- **建議修法**：對 `getPublishedArticles` / `getPublishedArticlesByCategorySlug` / `getMyArticles` / `getPendingArticles` / `getArticleSummariesByIds` 及 `enrich` 加 `@Transactional(readOnly = true)`；詳情路徑維持現狀。
- **基線對應**：**新**。

---

### LOW

---

#### PERF-30（LOW）｜若把 ARCH-02 的 retry 修活，`StatefulRetryOperationsInterceptor` 會在 `concurrency=1` 下阻塞整條 queue

- **file:line**：`RabbitMqConfig.java:150`（`factory.setAdviceChain(buildRetryInterceptor())`）、`:233-248`（初始 1s、乘數 5、上限 25s、3 次）；`concurrentConsumers` 未設 ⇒ 預設 1（PERF-15）。
- **情境**：stateful retry 的退避是在 **consumer 執行緒上 sleep**。一旦 9 個 consumer 依 ARCH-02 的建議改為 rethrow，單一毒訊息就會讓該 queue 停擺約 1+5+25 ≈ 31 秒，期間後續訊息完全不處理。這不是反對修 ARCH-02，而是**修它時必須同時設 `concurrency`**（或改用非阻塞的 delayed-retry queue），否則會把「不重試」換成「頭部阻塞」。
- **建議修法**：修 ARCH-02 的同一個 PR 內設 `concurrency: 2-4`，或改用 `x-delayed-message` / DLQ+TTL 回流的非阻塞重試。
- **基線對應**：**新**；直接關聯 **ARCH-02**（本條是它的修復前置條件）。

---

#### PERF-31（LOW）｜兩個 flush job 逐筆發語句，且 `reading:dirty` 用 SMEMBERS 一次全取

- **file:line**：`blog-module-article/.../service/ViewCountServiceImpl.java:93-121`（方法名 `flushViewCounts` / `incrementViewCountBatch`，實際迴圈內每 key 一條 UPDATE，`:110`）；`blog-module-reading/.../job/ReadingProgressFlushJob.java:36`（`opsForSet().members(READING_DIRTY_KEY)`，無界）、`:39-68`（每 entry：1 HGETALL + 1 `articleFacade.findIdByUuid` + 1 `progressMapper.upsert` + 1 SREM ⇒ **每 entry 2 條 SQL**，且 `findIdByUuid` 對同一篇文章的不同使用者是重複查詢）。
- **情境**：效能簡報 §5「flush job 頻率與批次大小」。5 分鐘週期、目前規模影響小，屬擴展性備忘。
- **建議修法**：`reading:dirty` 改 SSCAN 分批（或 SPOP N）；`findIdByUuid` 先去重成一次 `IN (...)`；upsert 改 MyBatis `<foreach>` 多列 `INSERT ... ON CONFLICT DO UPDATE`。
- **基線對應**：關聯基線 DATA-08 / RACE-13（同一段程式的 lost-update 問題）。

---

#### PERF-32（LOW）｜`JwtAuthenticationFilter` 每個已認證請求 2 次 HGET（可併為 1 次），cache miss 時 4 次寫

- **file:line**：`blog-infrastructure/.../security/JwtAuthenticationFilter.java:60-61`（兩次 `opsForHash().get(...)`）、`:80-84`（三次 `put` + 一次 `expire`）。
- **情境**：效能簡報 §4。此 filter 在**每一個**帶 token 的請求上執行；2 次 Redis RTT 可以是 1 次（`opsForHash().multiGet(key, List.of(FIELD_VERSION, FIELD_STATUS))`），miss 路徑的 4 次寫可用 `putAll` + `expire` 降為 2 次。
- **建議修法**：`multiGet` / `putAll`。
- **基線對應**：**新**。

---

#### PERF-33（LOW）｜`/api/v1/files/{id}/content` 每張圖多一條 `SELECT * FROM users`

- **file:line**：`blog-module-file/.../controller/FileController.java:161`（`resolveOptionalUserUuid(userId)`）→ `:278-284` → `UserFacade.getUserUuidById` ⇒ 一次 `SELECT * FROM users`。`getFileMetadata` 端點（`:126`）同理。
- **情境**：效能簡報 §7。端點其他部分做得很好（302 + presigned、單次 metadata 查詢供授權與簽名共用、`private, max-age=240`；`:159-196` 的註解明確記錄了「原本 canRead 與 generatePresignedUrl 各查一次，一頁十張圖就是二十次 DB 查詢」——同一個道理還剩這一條沒收）。登入使用者瀏覽含 10 張圖的文章 ⇒ 10 次完全相同的 user 查詢。
- **建議修法**：把 `userUuid` 放進 JWT claim 或 `user:auth:{id}` hash（該 hash 已存 version/status/role），filter 解析時一併帶入 principal。修 PERF-02 時一併處理。
- **基線對應**：**新**。

---

#### PERF-34（LOW）｜`SeriesMapper.findPublic` 對同一條件同時跑相關子查詢 COUNT 與 EXISTS

- **file:line**：`blog-module-series/.../mapper/SeriesMapper.java:31-44`（`:34-35` `(SELECT COUNT(*) FROM articles a WHERE a.series_id=s.id AND a.status='PUBLISHED')` 投影、`:39-40` `WHERE EXISTS (SELECT 1 FROM articles a WHERE 同樣條件)`）。
- **情境**：效能簡報 §2。每列跑兩次語意重複的子查詢；COUNT 的結果已足以推出 EXISTS。`articles` 上的 `idx_articles_series_position` 是 partial (series_id, series_position)，`status='PUBLISHED'` 需回 heap 過濾。
- **建議修法**：改 `LEFT JOIN LATERAL (SELECT COUNT(*) c FROM articles ...) x ON true WHERE x.c > 0`，或 GROUP BY + HAVING。
- **基線對應**：**新**；關聯 **ARCH-13**（`SeriesMapper` 直讀 `articles`，7 處）——本條是其中兩處的成本量化。

---

#### PERF-35（LOW / PLAUSIBLE）｜ES 全文查詢對整篇 `content` 套 `fuzziness("AUTO")`

- **file:line**：`SearchServiceImpl.java:89-93`（`multiMatch` 的 `fields("title^3","summary^2","content^1")` + `.fuzziness("AUTO")`）。
- **情境**：效能簡報 §6。fuzzy 會對每個 term 展開編輯距離內的變體再比對；套在 `content`（整篇正文的 Text 欄位）上比只套 title/summary 昂貴得多。**無 wildcard / 無 regex / 無 leading-`*`**，這點是好的。標 PLAUSIBLE：實際成本取決於語料與分詞器（註解說明 IK 由 index template 套用，本 repo 看不到該設定）。
- **建議修法**：改用 per-field fuzziness（只對 title/summary 開），或 `cross_fields` / `phrase_prefix`。
- **基線對應**：**新**。

---

#### PERF-36（LOW）｜每次搜尋都在讀路徑上同步寫 Redis（ZINCRBY + ZCARD [+ ZREMRANGEBYRANK]）

- **file:line**：`SearchServiceImpl.java:133-135`（`search()` 內呼叫 `recordSearch`）→ `:275-291`（`incrementScore` + `zCard`，超過 500 再 `removeRange`；登入者再加 `leftPush` + `trim` + `expire` 三次）。
- **情境**：效能簡報 §4。匿名搜尋 2 次寫入 RTT、登入搜尋 5 次，全在同步回應路徑上。（此行為的**安全**面向已由 SEC-05 記錄——匿名可寫全站熱門搜尋詞。）
- **建議修法**：`recordSearch` 改非同步（`@Async` 或丟 MQ），或至少用 pipeline 合併。
- **基線對應**：**新**；同 SEC-05（不同角度）。

---

#### PERF-37（LOW）｜自動快照把整篇 `content` 複製進 `article_versions`，`retain=50`

- **file:line**：`blog-start/src/main/resources/application.yaml:116-121`（`version.auto.retain: 50` / `interval-seconds: 60` / `diff-chars: 50`）；`ai-docs/schema.md` §article_versions：`content TEXT NOT NULL`；`VersioningService.java:80-97`（`recordAutoSnapshot` → `versionRepo.save(v)` → `versionMapper.retainAuto(articleId, 50)`）。
- **情境**：效能簡報 §2（資料量）。穩態下 `article_versions` 的體積 ≈ `articles.content_md` 的 50 倍（每篇最多 50 份 AUTO 快照，另加 MANUAL/PUBLISHED）。
- **建議修法**：若儲存成本成為問題，改存 diff/patch 而非全文，或降低 `retain` 預設；至少在維運文件記錄此倍率。
- **基線對應**：**新**。

---

#### PERF-38（LOW）｜`UserMapper` 兩個方法是 `SELECT *` + 前導萬用字元 ILIKE + 無上限，且目前是死碼

- **file:line**：`blog-module-user/.../mapper/UserMapper.java:29-30`（`SELECT * FROM users WHERE role = #{role}`，`users.role` 無索引，無 LIMIT）、`:38-39`（`ILIKE CONCAT('%', #{keyword}, '%')`）。全 repo（含測試）`rg "searchByNickname|findByRole"` **無任何呼叫端**。
- **情境**：效能簡報 §2「`LIKE '%x%'`」。前導 `%` 無法用 B-tree；兩者皆無分頁。目前不構成現存風險，僅記錄為「若未來接上管理員使用者搜尋就會直接踩雷」。
- **建議修法**：刪除死碼；若要保留，改 pg_trgm GIN 索引 + 分頁 + 明確選欄。
- **基線對應**：**新**。

---

## 3. 基線 `findings.md` 對照表（性能維度相關項）

| ID | 狀態 | 證據（develop @ f0e3cb1） |
|----|------|--------------------------|
| RACE-16（Info：`@Transactional` 橫跨 MinIO removeObject） | **OPEN，未變** | `FileServiceImpl.java:241`（`@Transactional`）、`:250-262`（兩次 `minioClient.removeObject`）。本報告從連線池佔用角度重新定性為 MEDIUM → PERF-28 |
| RACE-17（Info：TrendingRefreshJob 鎖 120s + 共用 tmpKey） | **OPEN，未變** | `TrendingRefreshJob.java:54`（`LOCK_TTL_SECONDS=120`）、`:125-128`（`tmpKey = key + ":tmp"` 固定字串；`delete`→`add`→`rename`）。性能面延伸為 PERF-16（單執行緒排程池 × 鎖持有時間） |
| DATA-04（ES 刪除 best-effort + `reindexAll` 只 saveAll 不刪殘留） | **OPEN，零進展** | `SearchServiceImpl.java:202-220` 仍無 `deleteAll()` / 無 alias 切換 / 無分批 → PERF-13；並新增發現 1+3N 的 N+1 → PERF-01 |
| DATA-08 / RACE-13（ReadingProgressFlushJob lost-update 視窗） | **OPEN，未變** | `ReadingProgressFlushJob.java:36`（SMEMBERS）、`:46-64`（讀後 SREM 視窗原樣保留）。性能面新增 PERF-31（逐筆 SQL、無界 SMEMBERS） |
| DATA-09（ViewCount flush 崩潰視窗；`article:views:*` 24h TTL） | **OPEN，未變** | `ViewCountServiceImpl.java:93-121`（逐 key GETDEL→UPDATE，失敗才回補，`:112-113`）、`RedisKeyConstant.java:118`（`ARTICLE_VIEWS_TTL_HOURS=24`）。PERF-16 會延長此視窗 |
| DATA-11 / roadmap 工作包 C（consumer 冪等鋪滿） | **部分進展（3/9）** | `IdempotencyService` 現由 `TagUsageConsumer.java:76-81`、`SeriesArticleDeletedConsumer`、`ViewCountConsumer` 使用。性能面副作用：`processed_events` 無 cleanup（PERF-19），鋪得越滿成長越快 |
| DATA-13（`recommend:related:*` 內嵌已刪文章至多 1 小時） | **OPEN，未變** | `RecommendServiceImpl.java:70`（TTL 1h）、`:97-102`（寫入）；唯一 evict 是 `ArticlePublishedConsumer.java:54-55`，只刪**該篇自己**的 key。性能面新增 stampede 與 limit-key 問題 → PERF-26 |
| FILE-04（縮圖無最大像素防護） | **OPEN，未變** | `ThumbnailConsumer.java:84-85` → PERF-25（同 SEC-11） |
| FILE-05（size 檢查在全量讀入之後；multipart 未設定） | **OPEN，未變** | `FileServiceImpl.java:134`（`getBytes()`）在 `:144`（size 檢查）之前；`rg "multipart"` 於 `application*.yaml` 零命中 → PERF-24（同 SEC-17） |
| roadmap 工作包 C2（consumer 改拋例外讓 retry 生效） | **未做** | 9/9 consumer 仍手動 `catch → basicNack(requeue=false)`（逐點清單見 ARCH-02）。性能面前置條件見 PERF-30 |
| backlog `2026-07-29-index-cache-rebuild-completeness.md` | **兩項皆未修** | 問題 1：`SearchServiceImpl.java:208` 仍是裸 `saveAll`。問題 2：`tag:autocomplete` 唯一寫入點仍是 `TagNormalizationServiceImpl.java:74`，`TagServiceImpl.suggest:69-80` 只讀不補。另發現同源新病灶 → PERF-18（`tag:hot` 過期後被 ZINCRBY 無 TTL 重建） |
| 基線「已驗證安全/一致」中的性能相關斷言 | **複核通過** | 「MQ 皆 commit 後發送」：`ArticleCommandSubService.java:120-131,206-217` 的 `transactionTemplate.execute` 結束後才 publish；`judgment.md` §2 第一列（`@Transactional` 內出現 `rabbitTemplate`）全 repo 無違例。「瀏覽去重 SETNX」：`ArticleViewSubService.java:27-28`。「ViewCount 用 GETDEL」：`ViewCountServiceImpl.java:103` |

> 其餘基線條目（AUTH-*、RACE-01~15、DATA-01~03/05~07/10/12/14、XSS-*、DEP-*、FE-*、TEST-*）不屬性能維度，本報告不重複判定；見 `be-review-security.md` §3 與 `be-review-architecture.md` §3。

---

## 4. 檢查過但無 finding 的清單（含方法）

| 區域 | 檢查方法 | 結論 |
|------|----------|------|
| 檔案內容代理端點是否把物件讀進記憶體 | 讀 `FileController.java:155-197` 全段 + `FileServiceImpl.generatePresignedUrl` 呼叫點 | **做對了**：只回 302 + `Location`（presigned URL），圖片位元組完全不經應用伺服器；只查一次 metadata 供授權與簽名共用（`:159-186`，註解記錄了這次優化）；有 `Cache-Control: private, max-age=240`，刻意短於 presigned 的 5 分鐘效期。唯一殘留是 PERF-33 的 user 查詢 |
| 縮圖是否同步阻塞上傳請求 | 讀 `FileServiceImpl.uploadFile` 全段 + `ThumbnailConsumer.java:63-113` | **做對了**：走 `file.thumbnail` queue 非同步，上傳請求不等縮圖。風險只在 consumer 側（PERF-25） |
| 留言列表是否 N+1 | 讀 `CommentService.listComments:200-254` + `CommentMapper.java` 全檔 | **做對了**：top-level 一條（JOIN users）、replies 一條 IN 批次、liked 一條 IN 批次，共 5-6 條固定查詢，與筆數無關。索引 `idx_comments_article_top_level` / `idx_comments_replies`（V13 partial 複合）精準對應查詢形狀。問題只在 `countByArticle` 的 OR 條件（PERF-11）與 replies 無上限（PERF-22） |
| 推薦模組的摘要組裝是否 N+1 | 讀 `ArticleFacadeImpl.assembleSummaryInfoList:238-266` + `ArticleRecommendMapper.xml` 全檔 | **做對了**：`findByTagIds` / `findByUuids` / `findRecentPublished` 都直接 JOIN `users` 取 nickname（不走 `UserFacade`），tag 名稱一條 IN 批次。與 `ArticleResponseMapper` 的作者 N+1（PERF-02）形成同 repo 內的正反對照 |
| 版本列表是否拉大 TEXT 欄位 | 讀 `VersionMapper.java:52-66` | **做對了**：明確選欄 + `length(content) AS content_length`，不拉 `content`。全 repo 最佳投影範例 |
| Transaction + MQ 時序（`judgment.md` §2 第一列） | `rg "@Transactional"` 全 repo（63 處）逐一比對 `rabbitTemplate` 呼叫點；重點讀 `ArticleCommandSubService.java:92-232`、`ArticleServiceImpl.java:47-50,64-67` | **無違例**：所有 MQ 送出都在 `transactionTemplate.execute` 之後；`ArticleServiceImpl` 兩處還在 JavaDoc 明確記錄了「刻意不開交易，避免在交易內送 MQ 並持有連線」 |
| `enrich()` 的 liked / bookmarked 是否批次 | 讀 `ArticleQueryService.java:206-261` → `ReadingFacadeImpl.java:37-66` → `ArticleLikeService.java:91-96` / `BookmarkService.java:53-58` → 對應 Mapper | **做對了**：各一條 `WHERE user_id=? AND article_id IN (...)`。三個 batch 方法中只有 `batchGetProgress` 不合格（PERF-03） |
| Redis 是否用 `KEYS` | `rg "KEYS|keys\("` + 讀 `ViewCountServiceImpl.java:94-97` | **做對了**：用 `ScanOptions ... count(100)` + `Cursor`，且 try-with-resources 關閉 cursor |
| ES 查詢是否有 wildcard / regex 全掃 | 讀 `SearchServiceImpl.search:82-138` 完整 query 建構 | **無 wildcard / 無 regex / 無 leading-`*`**：只有 `multiMatch` + `term` filter + `nested` filter。唯一成本疑慮是 `fuzziness AUTO`（PERF-35，標 PLAUSIBLE） |
| trending ZSet 寫入是否逐筆 | 讀 `TrendingRefreshJob.refreshPeriod:105-129` | **做對了**：`opsForZSet().add(tmpKey, tuples)` 單次批次寫入，再 `rename` 原子切換。問題在上游的 3 次全表掃（PERF-08） |
| MyBatis 是否有字串拼接（影響 plan cache / 注入） | 讀全部 13 個 Mapper 的 `@Select` / `@Update` / XML | 全部用 `#{}` 綁定；`<foreach>` 內也是 `#{uuid}::uuid`。無 `${}` |
| 分頁 offset 計算是否溢位 | 讀 `ArticleQuerySubService.java:60,68,76,91`、`CommentService.java:211`、`SeriesService.java:63` | article 模組已用 `(long)` 轉型避免 int 溢位；comment 用 `int` + `Math.max(0, ...)`，極大 page 溢位成負數後會退化為第一頁——非性能問題 |
| 測試 profile 是否影響 production 池設定 | 讀 8 個 `application-test.yaml` + `application-e2e.yaml` | 皆為測試 profile，不影響 production（`allow-bean-definition-overriding` 的問題屬 ARCH-21） |
| `blog-common` / `blog-infrastructure` 是否有隱藏熱路徑 | `rg "for \(|forEach\("` 於 `**/service/**`、`**/facade/**`、`**/job/**` 後過濾含 Repository / Mapper / Facade 呼叫的行 | 全數已收斂進上述 findings（`SearchServiceImpl:208`、`FileServiceImpl:364,375`、`ArticleCommandSubService:512-514`、`TagFacadeImpl:51,56,62`、`TagServiceImpl:96`、`TagUsageConsumer:83`、`ArticleQuerySubService:129,150`），無遺漏 |
| Flyway migration 與 `schema.md` 的索引是否一致 | `rg "CREATE (UNIQUE )?INDEX" blog-db-migration/.../migration/` 全清單 vs `ai-docs/schema.md` 各表 Indexes 區塊 | **完全一致**（20 條 CREATE INDEX 逐條對得上）。`schema.md` 作為索引真相版本可信，本報告的索引結論全部據此 |

---

## 5. 修復順序建議（依 ROI，實際排程待 Yuan 定）

**第一批：便宜、低風險、立刻見效（一個 PR 可完成）**
1. **PERF-05** 分頁夾範圍（抄 `SeriesService.java:61-63`，五個端點）——先把放大器關掉，其餘 N+1 的爆炸半徑立刻收斂。
2. **PERF-07/08/09/10/11** 一次 Flyway 補齊索引：`article_tags(tag_id)`、`user_tag_follows(tag_id)`、`articles(published_at DESC) WHERE status='PUBLISHED'`、`articles(status, created_at DESC)`、`comments(article_id)`、`user_article_likes(article_id)`、`user_bookmarks(article_id)`、`user_highlights(article_id)`、`user_reading_progress(article_id)`。**依 CLAUDE.md §Schema Maintenance 必須同步更新 `ai-docs/schema.md`**（各表 Indexes 列 + Migration Index 補一行）。
3. **PERF-12** ES `withSourceFilter`（三行改動）。
4. **PERF-15** listener factory 改用 `SimpleRabbitListenerContainerFactoryConfigurer` 起手 + yaml 明寫 Hikari / prefetch / concurrency；**PERF-16** 排程池設 3。

**第二批：讀路徑主線（依序，各自獨立可測）**
5. **PERF-02** `UserFacade.getUsersByIds` 批次 + `toSummaryResponses` 改用 map（順帶解 PERF-33）。
6. **PERF-03** `batchGetProgress` 去掉 `findByIds`、N 次 HGETALL 改 pipeline 或單條 SQL。
7. **PERF-06 + PERF-20** article 列表 / 跨模組查詢改明確選欄的輕量投影。
8. **PERF-29** 給列表方法加 `readOnly=true`（**務必先讀該條目的「不要盲改」警告**：詳情路徑刻意不開交易）。

**第三批：需要設計決策**
9. **PERF-01 + PERF-13** 重建索引整體重做（JOIN 化 + 分批 + alias 切換 + `tag:autocomplete` 回填），一併結清 backlog `2026-07-29-index-cache-rebuild-completeness.md` 與基線 DATA-04。
10. **PERF-04** archive 端點（投影 + 快取 或 分頁）。
11. **PERF-14** `article.published` queue 去留（需 Yuan 決定是否還有下游規劃）。
12. **PERF-30** 必須與 ARCH-02 的修復放在同一個 PR，否則「不重試」會變成「頭部阻塞」。
