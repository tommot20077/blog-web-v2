# blog-web-v2 後端三維度稽核 Triage（2026-09-04）

- **審查對象**：`origin/develop` @ `f0e3cb1`（含 PR #53–#59 全部合併後的狀態）
- **輸入**：`be-review-security.md`（28）、`be-review-architecture.md`（28）、`be-review-performance.md`（38）
- **基線**：`ai-docs/findings.md`（2026-07 稽核登記簿）＋ `ai-docs/roadmap.md` 體檢發現索引 ＋ `ai-docs/backlog/`
- **本文件用途**：決策。逐條證據在三份原始報告；登記簿在 `ai-docs/findings.md`。三者分工不重疊。

---

## 1. 一頁摘要

原始 **94 條**（SEC 28 / ARCH 28 / PERF 38），去除 11 組跨維度重述後 **實際 83 條**：**HIGH 10 / MEDIUM 47 / LOW 26**，收斂成 **77 個行動項**。無 CRITICAL、無可匿名直接利用的認證繞過。

**最該先修的 5 件事**

1. **SEC-01+ARCH-28（HIGH）XFF 無條件被信任** — `AuthController.java:170-176`、`application.yaml:1-2` — 不修：登入/註冊/瀏覽去重的**整層 IP 限流形同不存在**，密碼噴灑與瀏覽數灌水皆無節流。
2. **SEC-02+ARCH-27（HIGH）restore 繞過文章狀態守衛** — `ArticleFacadeImpl.java:427-429`、`VersioningService.java:256-296` — 不修：作者可用舊快照把被 ADMIN 駁回（REJECTED）或送審中的文章直接還原成 PUBLISHED。
3. **SEC-04+PERF-05（MEDIUM，合併後實為 HIGH）分頁參數無上下界** — `ArticleController.java:76-79` 等六處 — 不修：`?size=1000` 匿名一發 ≈ 2005 條 SQL，`page=0` 直接 500；它是其餘每一條 N+1 的放大器。
4. **PERF-07/08/09/10/11（MEDIUM ×5）索引缺口家族** — `articles.published_at`、`article_tags.tag_id`、`comments.article_id` 等 9 條 — 不修：trending job 每 30 分鐘 3 次全表掃、每篇文章詳情的相關推薦全表掃、刪一篇文章觸發 5 次 seq scan。
5. **ARCH-05+PERF-14（MEDIUM）`article.published` 孤兒 queue** — `ArticleRabbitMqConfig.java:96-99,106-112` — 不修：每次發文往 durable queue 塞一份**完整文章正文**且無人消費，撞 broker watermark 後會 flow-control **阻塞全部 publisher**（含正常的 search/tag/version 事件）。

**第 6–7 名，成本同樣近零，建議同批**：`ARCH-11` 補兩個 exception handler（`GlobalExceptionHandler.java`，單點消掉基線 RACE-01/03/04/08/15 的整類 500）、`ARCH-03+ARCH-25` CI 兩行改動（`ci.yml:74`、`blog-start/pom.xml:175-177`，把已寫好卻從不執行的 ContextSmokeTest 與 red E2E 接回防線）。

---

## 2. 同一根因的合併（11 組，各保留原始視角）

| # | 合併後 | 併入 | 三方視角 |
|---|--------|------|----------|
| M1 | **SEC-01** XFF/ClientIp | ARCH-28 | 安全：XFF 可偽造 → IP 限流全層可繞（HIGH）。架構：橫切關注點被複製進兩個 Controller 的 private method，且兩份對「什麼是可信 IP」的假設互相矛盾 → 沒有共用元件就沒有單一修補點。 |
| M2 | **SEC-02** restore 狀態守衛 | ARCH-27、基線 AUTH-01/DATA-03 | 安全：REJECTED/PENDING_REVIEW → PUBLISHED 為真實授權繞過（HIGH）。架構：`ArticleContentChangedEvent.RESTORED` 在唯一 consumer 是 no-op，是「半配對」的隱式跨模組契約。資料一致性（基線）：restore 降級時無 ES 刪除 / series 不清 / count 不減。 |
| M3 | **SEC-04** 分頁上下界 | PERF-05 | 安全：匿名放大查詢 + `page=0` 造成 500。性能：它把每一條 N+1 的爆炸半徑從「size=10 的 25 條 SQL」放大到「size=1000 的 2005 條 SQL＋1000 次 Redis RTT」。 |
| M4 | **SEC-05** `recordSearch` | PERF-36 | 安全：匿名可寫入全站熱門搜尋詞並由公開端點放送（內容注入）。性能：每次搜尋在同步讀路徑上做 2–5 次 Redis 寫入。 |
| M5 | **SEC-11** 縮圖 decompression bomb | PERF-25、基線 FILE-04 | 安全：consumer OOM + MQ 反覆重投遞（DoS）。性能：`concurrentConsumers=1` ⇒ 單一惡意圖片停擺整條 `file.thumbnail` queue。 |
| M6 | **SEC-17** multipart | PERF-24、基線 FILE-05 | 安全：框架預設 1MB 與程式 5MB 檢查互相矛盾，錯誤語意不對。性能：先全量讀進記憶體再驗大小，每個並發上傳吃滿一份 heap。 |
| M7 | **ARCH-05** 孤兒 queue | PERF-14 | 架構：有 binding、有 producer、沒有 consumer，是 `judgment.md §2` 配對規則的鏡像違規。性能：堆積的不是小訊息而是**每篇完整正文**；broker flow control 會回頭掛住發文的 HTTP 請求。 |
| M8 | **ARCH-02** MQ retry 死碼 | PERF-30 | 架構：9/9 consumer 手動 try/ack/nack 抵銷 advice chain，JavaDoc 宣稱的「3 次指數退避」是謊言。性能：**若照架構建議改成 rethrow 而不同時設 concurrency，會把「不重試」換成 31 秒頭部阻塞**（見 §6 T1）。 |
| M9 | **SEC-03** refresh 鏈 | SEC-26 | 主體：以 Redis 快取為權威、無輪替、無重用偵測、角色變更無撤銷路徑。附屬：auth hash miss 時 `/refresh` 回 `ACCOUNT_SUSPENDED`——改以 DB 為權威後自然消失，同一修法。 |
| M10 | **PERF-01** ES 重建索引 | PERF-13、基線 DATA-04 | 性能 A：1+3N 的 N+1、全站正文三份副本進堆、同步阻塞 request thread。性能 B：`saveAll` 前不刪舊索引、不分批、無 alias 切換。一致性（基線）：幽靈文件 reindex 也修不掉。同一次重寫解決三者。 |
| M11 | **ARCH-13** SeriesMapper 直讀 `articles` | PERF-34、backlog M1 | 架構：跨模組直讀業務表，**從基線的 4 處惡化為 7 處**，且 `'PUBLISHED'` 寫成字面字串繞過 enum。性能：`findPublic` 對同一條件同時跑 COUNT 子查詢與 EXISTS 子查詢。 |

**相關但**不**合併（修法不同，需分開排期）**：
- `ARCH-04`（跨模組注入 concrete service）↔ `PERF-02`/`PERF-20`（同區域的成本量化）——前者是邊界問題，後者是批次化問題。
- `ARCH-02`（retry 死碼）↔ `PERF-15`（手刻 factory 讓 yaml 靜默失效）——同一個 bean，但 PERF-15 是「設了沒反應」的獨立陷阱，且是 ARCH-02 修法的前置。
- `ARCH-08`（entity-as-DTO）↔ 基線 `AUTH-07`/`FILE-01`——兩份報告對同一基線條目給出**相反結論**，見 §6 T5。

---

## 3. 四象限行動清單

> 「檔數」為預估動到的檔案數（含測試與文件）。標「待 Dn」者需先看 §4。

### 3.1 立即修（低成本、高影響）— 24 項

| 原始編號 | file:line | 修法 | 檔數 |
|---|---|---|---|
| SEC-04+PERF-05 | `ArticleController.java:76-79,215-217`、`CommentController.java:74-77`、`SearchController.java:61-62`、`BookmarkController.java:60-61`、`VersionController.java:64-65`、`AdminArticleController.java:50-53` | 抄 `SeriesService.java:61-63` 的 clamp 抽成 `blog-common` helper，六處套用 | 7 |
| PERF-07/08/09/10/11 | `ArticleRecommendMapper.xml:20-30`、`ArticleMapper.java:41,149`、`CommentMapper.java:81-83` | 一次 Flyway V22 補 9 條索引（`article_tags(tag_id)`、`user_tag_follows(tag_id)`、`articles(published_at DESC) WHERE status='PUBLISHED'`、`articles(status,created_at DESC)`、`comments(article_id)`、4 張互動表的 `article_id`）＋**同步 `ai-docs/schema.md`** | 2 |
| ARCH-05+PERF-14 | `ArticleRabbitMqConfig.java:96-99,106-112` | 刪 `articlePublishedQueue()`／`articlePublishedBinding()` 兩個 bean + 維運端 `queue.delete` | 1（待 D4） |
| ARCH-11 | `GlobalExceptionHandler.java:48-232` | 補 `DataIntegrityViolationException`→409、`OptimisticLockingFailureException`→409（更具體者優先匹配，順序無虞） | 1 |
| SEC-01+ARCH-28 | `AuthController.java:170-176`、`ArticleController.java:305-319`、`application.yaml:1-2` | 移植 `59c170d` 的 `ClientIpResolver`（rightmost-untrusted + `trustedProxyCount`）到 `blog-infrastructure`，兩個 Controller 改注入 | 4 |
| SEC-02+ARCH-27 | `ArticleFacadeImpl.java:427-429`、`VersioningService.java:256-296`、對照 `ArticleCommandSubService.java:445-456` | restore 不接受任意 status（或把 `validateStatusTransition` 提到共用 `ArticleStatusPolicy` 並傳 operatorRole）。**依 T6 鐵律先改 `TEST-03`** | 4（待 D3） |
| ARCH-01 | `blog-module-tag/.../event/ArticleTagEvent.java:21`、`TagUsageConsumer.java:7` | 刪 tag 版 record，consumer 改 import infrastructure 版；順手修正 JavaDoc 的「主鍵 vs UUID」矛盾 | 2 |
| ARCH-03(CI 部分)+ARCH-25 | `.github/workflows/ci.yml:74`、`blog-start/pom.xml:175-177` | e2e job 加 `-Dcontext.smoke=true`；加第三個 `-Pred-e2e` job（先 `continue-on-error: true`） | 2 |
| PERF-18 | `TagUsageConsumer.java:88-90`、`TagServiceImpl.java:103-105,152-161` | `incrementScore` 後補 `expire`（或 Lua 只在 key 存在時 incr）；consumer 一併刪 `tag:detail:{slug}` | 2 |
| PERF-12 | `SearchServiceImpl.java:110-123` | 加 `withSourceFilter` 排除 `content`；順手夾 `from+size` 不超過 `max_result_window` | 1 |
| PERF-15 | `RabbitMqConfig.java:141-152`、`application.yaml` | factory 改用 `SimpleRabbitListenerContainerFactoryConfigurer.configure(...)` 起手（讓 yaml 屬性重新生效）＋明寫 Hikari / prefetch / concurrency | 2 |
| PERF-16 | `BlogWebV2Application.java:15`、`application.yaml` | `spring.task.scheduling.pool.size: 3`（3 個 job 目前串在單執行緒上） | 1 |
| SEC-13 | `JwtService.java:50-51,74-85` | 非 dev/test profile 而 `jwt.private-key` 為空時啟動即 `throw`（現況是靜默每 pod 各生一把金鑰 → 間歇 401） | 2 |
| SEC-12 | `application.yaml:14-15,22-23,74-75`、`application-demo.yaml:12,18,38` | 正式路徑改無預設 `${DATABASE_PASSWORD}` 讓缺值 fail-fast；本機值移 gitignored `application-local.yaml`（比照 `-dev`） | 2 |
| SEC-11+PERF-25 | `FileServiceImpl.java:170-183`、`ThumbnailConsumer.java:84-88` | 上傳時用已讀到的 header `width*height` 設上限（≤50M 像素）；consumer 再檢一次作縱深 | 2 |
| SEC-17+PERF-24 | `FileServiceImpl.java:130-145`、`application.yaml` | 明示 `spring.servlet.multipart.max-file-size: 5MB`／`max-request-size: 6MB`；改用 `file.getSize()` 先擋再讀 bytes | 2 |
| SEC-05+PERF-36 | `SearchServiceImpl.java:275-291,150-159`、`SearchController.java:56-65` | `recordSearch` 只在 `userId != null` 時寫入；`q` 加 `@Size(max=64)` + 字元白名單；寫入改非同步或 pipeline | 2（待 D12） |
| SEC-20 | `AuthService.java:370-386`、`:495` | `forgotPassword` 比照 `resendVerification` 先 `deleteByUserIdAndType`；重設成功後刪該使用者全部 `PASSWORD_RESET` token | 1 |
| SEC-06 | `JwtAuthenticationFilter.java:94,101-103` | `PENDING_VERIFICATION` 只授 `ROLE_*`，不發任何 `Permission`（Q3 定案）。先補 `TEST-09` | 2 |
| SEC-10 | `UpdateProfileRequest.java:36-51`、`UserService.java:84-99` | `website`/`avatarUrl` 加 `@SafeUrl`（只允許 http/https）——後端 markdown 有協定白名單，個人資料 URL 卻完全沒有對等防線 | 2 |
| SEC-08 | `SecurityConfig.java:68-122` | 加 `.headers(...)`：先 `Content-Security-Policy-Report-Only` 上線觀察，再加 Referrer-Policy / HSTS | 1 |
| PERF-17 | `TagServiceImpl.java:85-106` | 快取命中路徑的 N 次 `findById` 改 `findAllById`；回填改單次 `ZSet add(Set<TypedTuple>)` | 1 |
| PERF-19 | `IdempotencyService.java:58-63`、`V17__add_processed_events.sql:12-13` | 加 `@Scheduled` 分批刪 30 天前的 `processed_events`（索引已就緒） | 2 |
| SEC-15 | `.github/`（無 `dependabot.yml`）、root `pom.xml` | 加 `dependabot.yml`（maven+npm+actions）＋ CI 非阻塞 `dependency-check` job | 2 |

### 3.2 排期修（高成本、高影響）— 22 項

| 原始編號 | file:line | 修法 | 檔數 |
|---|---|---|---|
| PERF-01+PERF-13 | `ArticleFacadeImpl.java:114-119,186-207`、`ArticleMapper.java:149-150`、`SearchServiceImpl.java:202-220`、`AdminSearchController.java:43-48` | 重建索引整體重做：JOIN 化 + 明確選欄 + keyset 分批 bulk + create-new-index/alias 切換 + 端點改 202 非同步；同流程回填 `tag:autocomplete` | 8 |
| ARCH-02+PERF-30 | `RabbitMqConfig.java:141-152,233-248` ＋ 9 個 consumer | 抽共用 consumer wrapper（roadmap C1/C2），wrapper 內 ack/nack 讓 retry 真正接手；**同一個 PR 內設 `concurrency: 2-4`**，否則變 31 秒頭部阻塞 | 11 |
| ARCH-07 | 10 個事件 record、`ArticleVersionConsumer.java:41-57`、`ArticlePublishedConsumer.java:47-57` | 訂事件契約規範（統一放 `blog-infrastructure/event`、首欄 `UUID eventId`、只帶 UUID 不帶 Long PK）；version snapshot 接上 `IdempotencyService`（roadmap C3/C4） | 12+（待 D7） |
| ARCH-17→ARCH-03 | `ai-docs/architecture.md`（全檔 51 行）、新增 `blog-start/src/test/java/.../arch/` | 先補 13×13 允許矩陣 + 跨模組載體放置表 + 「什麼算對外面」的定義，再落地 5 條 ArchUnit 規則 | 2 + 5 測試（待 D8） |
| SEC-03+SEC-26 | `AuthController.java:116-154`、`AuthService.java`、`JwtAuthenticationFilter.java:56` | refresh 下移 `AuthService.refreshAccessToken`、以 DB 狀態為權威簽發並回填快取；實作 token 輪替 + 重用偵測 → `SessionRevoker`；filter 補 `type=="access"` 檢查 | 5（待 D6） |
| PERF-02+PERF-33 | `ArticleResponseMapper.java:116-117,191-197`、`UserFacade.java:17-42`、`UserFacadeImpl.java:36-64`、`FileController.java:161` | `UserFacade` 加 `getUsersByIds` 批次 + 收斂 `SELECT *`；`userUuid` 放進 `user:auth:{id}` hash 隨 principal 帶入 | 5 |
| PERF-03 | `ReadingProgressService.java:84-113`、`ArticleQueryService.java:253` | `batchGetProgress` 改收 `Map<Long,UUID>`（刪掉重複的 `findByIds`）；N 次 HGETALL 改 pipeline 或單條 `findByUserIdAndArticleIdIn` | 3 |
| PERF-04 | `ArticleController.java:97-99`、`ArticleQuerySubService.java:98-117`、`ArticleMapper.java:149-150` | archive 改輕量投影（比照 `VersionMapper.listSummaries`）+ Redis 快取，或按年份分頁 | 3 |
| SEC-09 | `SecurityConfig.java:86` vs `VersionController.java:46`／`HighlightController.java:50`／`ReadingProgressController.java:47`／`ArticleController.java:179` | `GET /api/v1/articles/**` permitAll 收窄成明確清單；同步改 `security.md` 與 `SecurityConfigTest` | 3（待 D1） |
| SEC-16 | `ArticleMapper.java:273-274`、`CommentService.java:62,202`、`ArticleLikeController.java:62`、`HighlightService.java:34,54`、`ReadingProgressService.java:51,76` | `findIdByUuid` 加 status 條件，或提供 `findPubliclyVisibleIdByUuid` 讓所有互動路徑走它 | 6 |
| ARCH-04+ARCH-09+ARCH-26 | `SeriesService.java:11,44`、`BookmarkController.java:7,31-33,63-68`、`SeriesDetailResponse.java:4,26` | 能力提到 `ArticleFacade`（回 `facade.dto.ArticleSummaryInfo`，比照 recommend）；`BookmarkController` 的編排與分頁下沉為 `BookmarkService.getMyBookmarks` | 6 |
| ARCH-13+PERF-34 | `SeriesMapper.java:34-35,39-40,48-49,77-78,93-94,106,115-117` | nav / count / enrich 三類查詢改走 `ArticleFacade`（需新增 3 個 facade 方法）；`findPublic` 改 `LEFT JOIN LATERAL` | 4 |
| ARCH-08 | `FileController.java:122,228`、`AdminTagController.java:47`、`TagController.java:71,92`、`Tag.java:42-43` | 補 `FileMetadataResponse`／`TagResponse`，entity 回歸純持久化；順帶封住 `Tag.isNew` 正在洩漏的 `"new": false` | 6 |
| ARCH-16 | `application.yaml:91-95`、`ArticleEventPublisher.java` 6 個 catch 點、9 個 consumer 的 nack 路徑 | 加 `micrometer-registry-prometheus`＋`Counter`（純加法，不動業務邏輯）——否則 T3/T4 的漂移在生產只能等使用者回報 | 12 |
| ARCH-24 | `UserRegisteredEvent.java:16`、`UserPasswordResetRequestedEvent.java:14`、`AuthService.java:145-148,382-385,452-455` | 事件只帶 `userId`+`tokenId`，consumer 發信前回 DB 換 token（現況明文 reset token 落在 durable queue 與無 TTL 的 DLQ） | 5 |
| ARCH-14(中期) | `docs/api-contract/`、`.github/workflows/ci.yml` | CI 啟動後端抓 `/v3/api-docs` 與 checked-in 快照 diff；補寫入類端點的 `@ApiResponse` | 3+ |
| PERF-06+PERF-20 | `ArticleMapper.java:41,61,76,108,149,178,282`、`ArticleQueryService.java:226-227,275-280` | 列表/跨模組查詢改明確選欄的輕量投影；`ArticleFacade` 加 `findSeriesRefById`；`enrich` 先 filter 掉非 series 文章 | 5 |
| SEC-23 | 全 repo `@RateLimited` 零命中 | 抽 Redis INCR+TTL 的共用 AOP，先套 comment create / file upload / search | 4 |
| ARCH-23 | `VersioningService.java:10-12,51-56,283` | `ArticleMarkdownRenderer`／`ArticleTocCodec`／`RenderResult` 上移 `blog-infrastructure`（純搬移，article/version/comment 共用） | 6 |
| PERF-22 | `CommentService.java:219-221`、`CommentMapper.java:57-74` | replies 每 parent 取前 N（`ROW_NUMBER() OVER PARTITION`）＋「查看更多回覆」端點；`parent_uuid` 改 JOIN | 4 |
| PERF-27 | `TagFacadeImpl.java:37-63`、`ArticleCommandSubService.java:508-516` | tag 同步改單條 DELETE + 批次 `INSERT ... ON CONFLICT`；`findOrCreateTags` 改一條 `WHERE slug IN`；categories 同型。**與 T2/T3 修法重疊，建議同批** | 3 |
| SEC-14 | `AuthService.java:104-112` | email 重複改統一訊息（或靜默流程）；username/nickname 可用性檢查獨立限流 | 2（待 D5） |

### 3.3 順手修（低成本、低影響）— 25 項

| 原始編號 | file:line | 修法 | 檔數 |
|---|---|---|---|
| SEC-07 | `CommentController.java:83,93,104`、`CommentLikeController.java:35,43` | 改 `hasAuthority('COMMENT_WRITE'/'COMMENT_DELETE')`（enum 已定義卻零使用） | 2 |
| SEC-18 | `FileServiceImpl.java:583-590`、`ThumbnailConsumer.java:74,87` | `ext` 改由 Tika 偵測的 MIME 反查，完全不採信檔名 | 2 |
| SEC-21 | `SecurityConfig.java:78`、`blog-infrastructure/pom.xml:23-24` | 正式 profile 關閉 `springdoc.api-docs`／`swagger-ui`，或改 `hasRole('ADMIN')` | 2 |
| SEC-22 | `AuthService.java:344-362,404-421,295-308` | 三個 email 端點加 IP 層限流 + 全域每小時出信上限。**必須排在 SEC-01 之後**（否則會限錯 IP） | 1 |
| SEC-24 | `AdminTagController.java:47-50`、`ArticleController.java:295-298`、`UpdateTagRequest.java:21,26,31`、`RejectArticleRequest.java:21` | 補 `@Valid` + `@Size`；`color` 加 `@Pattern("^#[0-9a-fA-F]{6}$")` | 4 |
| SEC-25 | `RegisterRequest.java:48-50`、`UpdateProfileRequest.java:44`、`Create/UpdateArticleRequest` | `nickname` 補 `@Size(max=50)` 對齊 schema；`socialLinks` 補 `@Size(max=2000)`；`content` 依產品定義補上限 | 4 |
| SEC-27 | `ai-docs/security.md`（原則 2 表、actuator 路徑、files 描述） | 三處與 `SecurityConfig` 對齊 | 1（待 D2） |
| ARCH-06 | `Search/Recommend/Tag/ArticleRabbitMqConfig` 的 exchange bean 與常數 | exchange bean + routing key 常數移 `blog-infrastructure`（`ArticleEventsTopology`），全部 `@Qualifier` 引用，比照 series/version | 5 |
| ARCH-10 | `CrossModule{Version,Series,Comment,Reading}IT` + `ai-docs/testing-standards.md` | 改成反映事實的類名（`*PersistenceIT`），並把「跨模組斷言」責任明確歸給 E2E 寫進標準 | 5 |
| ARCH-12 | root `pom.xml`、10 個 module `pom.xml`、`AuthServiceIntegrationTest` | surefire `<includes>` 移到 root `<pluginManagement>` 刪 10 份複製；IT 改名對齊 `*IT` 慣例（現況：4 個模組新增 `*IT` 會永遠綠、永遠不跑） | 12 |
| ARCH-14(短期) | `docs/api-contract/gap-report.md` | 頂端標註「最後驗證日期 2026-05-15」並承認過期（現況是**主動誤導**：文件說沒有 gap） | 1 |
| ARCH-15 | `blog-common/.../errorcode/{Article,Common,File,Tag,User}ErrorCode`、3 個借用處 | 選定「模組內」慣例寫進 `architecture.md`，4 個 enum 純套件搬移（錯誤碼字串不變，前端零影響） | 8 |
| ARCH-18 | `VersionRabbitMqConfig.java:41-49` | 刪掉 `x-message-ttl: 600_000`（或把理由寫進 JavaDoc 並移除「對齊全站慣例」的錯誤說法）——現況滾動更新超過 10 分鐘就靜默丟快照 | 1 |
| ARCH-19 | `RabbitMqConfig.java:50-56` + 8 個模組 config | DLQ 三常數改 `public` + 提供共用 `dlqArgs()`，8 處改呼叫 | 9 |
| ARCH-20 | `RabbitMqConfig.java:166-179`、`RabbitMqConfigTest.java:154-161` | 刪零使用者的 `autoAckContainerFactory` bean 與其假覆蓋測試 | 2 |
| ARCH-21 | `application-e2e.yaml:5-6`、`ContextSmokeTest.java:60` | 讓 ContextSmokeTest 關掉 `allow-bean-definition-overriding`（否則守衛比 production 寬鬆，正好漏掉最容易犯的錯） | 1 |
| PERF-21 | `AutoSnapshotPolicy.java:31-52`、`VersioningService.java:80-97` | 加 `findLatestAutoMeta` 只回 `created_at, length(content)`；`shouldSnapshot` 與 `recordAutoSnapshot` 合併為一次呼叫 | 3 |
| PERF-23 | `FileServiceImpl.java:341-388` | 解綁/綁定各改一條批次 UPDATE（擁有權檢查寫進 WHERE，行為等價且更安全） | 2 |
| PERF-26 | `RecommendServiceImpl.java:78-105` | cache key 加 limit（或一律以 MAX=20 計算後 subList）；熱門文章加 SETNX 互斥鎖 | 1 |
| PERF-28 | `FileServiceImpl.java:241,250-262` | 比照同檔 `uploadFile` 的 `transactionTemplate` 模式，兩次 MinIO `removeObject` 移出交易 | 1 |
| PERF-29 | `ArticleQuerySubService.java:59-96`、`ArticleQueryService.java:202-261` | 純讀列表方法加 `@Transactional(readOnly = true)`。**詳情路徑刻意不開交易，勿動**（見 §6 T6） | 3 |
| PERF-31 | `ViewCountServiceImpl.java:93-121`、`ReadingProgressFlushJob.java:36-68` | `reading:dirty` 改 SSCAN/SPOP 分批；`findIdByUuid` 去重成一次 IN；upsert 改 `<foreach>` 多列 ON CONFLICT | 2 |
| PERF-32 | `JwtAuthenticationFilter.java:60-61,80-84` | 2 次 HGET 改 `multiGet`，miss 路徑 4 次寫改 `putAll`+`expire`（此 filter 在每個帶 token 的請求上執行） | 1 |
| PERF-35 | `SearchServiceImpl.java:89-93` | `fuzziness("AUTO")` 改 per-field（只對 title/summary 開）或 `cross_fields` | 1 |
| PERF-38 | `UserMapper.java:29-30,38-39` | 刪死碼（`SELECT *` + 前導 `%` ILIKE + 無分頁，且全 repo 零呼叫端） | 1 |

### 3.4 記錄不修（高成本、低影響）— 6 項

| 原始編號 | 為何可接受 |
|---|---|
| **ARCH-22** 五個 god class（`FileServiceImpl` 605 行、`ArticleCommandSubService` 570/12 依賴、`AuthService` 569 行、`ArticleFacadeImpl` 480/9、`VersioningService` 456） | `code-standards.md`「Ask before rewriting systems」。真正的風險（高依賴數 → 循環依賴溫床）在 ARCH-03 守衛 #3 落地後由測試兜底，重寫的迴歸風險 > 結構收益。改為「碰到該檔時順手拆一個 collaborator」。 |
| **SEC-19** EXIF/GPS 未剝除 | 需對所有上傳圖重新編碼（CPU + 畫質損失）。影響限於使用者自己上傳的照片，屬隱私非權限。**條件**：若採 SEC-18 的「Tika MIME 反查 + 重新編碼」方案，EXIF 順帶解掉，屆時一起做。 |
| **PERF-37** `article_versions` ≈ 正文的 50 倍體積（`retain: 50`） | 改存 diff/patch 是資料模型變更 + 還原邏輯重寫。目前語料規模下儲存成本可忽略。**條件**：在維運文件記錄此倍率並設磁碟告警（或見 D11 調降 retain）。 |
| **SEC-28 / DEP-01~05** 逐條人工核 CVE | 成本高（`dependency:tree` + NVD 比對）且結論數週即過期。**條件**：改由 SEC-15 的自動掃描持續產出，人工只處理 High 以上告警。 |
| **backlog `2026-07-29-article-file-binding-via-mq`** | 原始動機（article↔file 循環依賴）已由 PR #54 以更便宜的方式解決（`ArticleLookupFacade`，已驗證回邊消失）。改走 MQ 反而引入「上傳圖→存草稿→綁定生效」的最終一致性視窗，作者以外看不到圖。**建議關閉或降為 Info**（見 D9）。 |
| **ARCH-14** 補齊全部 88 端點的 `@Operation`/`@ApiResponse` | 41 個端點無描述、全 repo 零 `@ApiResponse`，但沒有自動校驗的話補完很快再次漂移。**條件**：先做 CI 的 `/v3/api-docs` diff（已列排期修），註解只補寫入類端點。 |

---

## 4. 需要 Yuan 拍板的決策（10 項，不自行決定）

**D1｜收窄 `GET /api/v1/articles/**` 的 permitAll（SEC-09）**
現況 `SecurityConfig.java:86` 讓 versions / highlights / progress / edit 四組端點在 URL 層等同 permitAll，只靠方法層 `@PreAuthorize` 撐著（實測仍 fail-closed，非現存漏洞）。
- 選項 A：收窄成明確清單（`GET /api/v1/articles`、`/archive`、`/slug/{slug}`、`/{uuid}`、`/{uuid}/comments`），其餘落 `authenticated()`。**代價**：任何前端未列舉到的公開讀取路徑會立刻 401，需與前端 repo 同步驗證；`security.md` 表與 `SecurityConfigTest` 要一起改。
- 選項 B：維持現狀，改以 ArchUnit 守衛 #2 保證「每個 handler 都有 `@PreAuthorize`」。**代價**：URL 層永遠沒有兜底，且守衛的白名單依賴 D2 先修好。
- **取捨**：A 是縱深防禦的正解但有前端迴歸風險；B 便宜但把賭注全押在單層。

**D2｜`security.md` Public Endpoints 表修訂（SEC-27）**
表與 `SecurityConfig` 三處對不上：`/api/admin/**` vs 實作的 `/api/v1/admin/**`；`/actuator/health/**`、`/actuator/info`、`/favicon.ico`、`/error` 未入表；`GET /api/v1/files/**` 描述為「Public file access」但實作已改為「permitAll 只代表可到達 Controller，授權在 `canRead`」。表下方明文要求「必須與 `permitAll()` 一對一對得上」，而 `judgment.md §5` 把 Public Endpoints 表的任何增減列為需拍板事項。
- 選項 A：只修文件對齊現況（1 檔，零風險）。
- 選項 B：連同 D1 一起改，文件與實作同時收窄。
- **取捨**：A 先止血、但如果 D1 選 A 就得再改一次；B 一次到位但綁定 D1 的風險。

**D3｜restore 是否應完全不還原 status（SEC-02）**
- 選項 A：**restore 只還原內容，status 一律維持不變**。最簡單、立刻堵住授權繞過，2 個檔案。**代價**：使用者從「已發布→改壞→還原」後仍是原狀態（多數情況正是想要的），但「誤把文章設成 DRAFT 後想用快照救回 PUBLISHED」的路徑消失。
- 選項 B：保留還原 status，但把 `validateStatusTransition(from, to, operatorRole)` 提到共用 `ArticleStatusPolicy`，restore 路徑呼叫它並一路傳 operatorRole。**代價**：4–5 個檔案，且必須同時收斂目前**兩套互相分歧的守衛真相**（`VALID_TRANSITIONS` vs `submitForReview:346-348` 的 ad-hoc 判斷允許 `REJECTED → PENDING_REVIEW` 但 map 裡沒有）。
- **附帶**：2026-07 的 Q1 定案把 AUTH-01/DATA-03 降級為「純一致性破口（非審核繞過）」。本輪證據顯示 REJECTED/PENDING_REVIEW → PUBLISHED 確為授權繞過，**該定性需要 Yuan 確認是否修正**。

**D4｜`article.published` queue 的去留（ARCH-05/PERF-14）**
search 與 recommend 已各自宣告自己的 queue 綁同一 routing key，這個 queue 沒有任何 consumer。
- 選項 A：確認為歷史遺留 → 刪 bean + 維運 `queue.delete`（最省，1 檔）。
- 選項 B：保留作稽核用途 → 加 `x-max-length`/`x-message-ttl`，補一個 consumer，並把 `contentText` 從 payload 拿掉。
- **取捨**：只有 Yuan 知道是否還有下游規劃；在確認前 A 不可逆。

**D5｜註冊端點的帳號枚舉（SEC-14）**
`forgotPassword`/`resendVerification` 都刻意靜默成功以防洩漏，註冊端點卻回三種不同的重複錯誤碼。
- 選項 A：email 重複改統一訊息 / 靜默流程。**代價**：前端無法即時告訴使用者「這個 email 已註冊」，註冊 UX 變差。
- 選項 B：維持現狀，改以獨立限流降低批量枚舉速率（但需 SEC-01 先修）。
- **取捨**：安全 vs 註冊轉換率，這是產品決定。

**D6｜refresh 以 DB 為權威 + token 輪替（SEC-03）**
- 選項 A：完整移植 `59c170d` 方向（下移 `AuthService`、DB 為權威、輪替 + 重用偵測）。**代價**：每次 refresh 多一條 DB 查詢；輪替會讓多分頁/多裝置的並發 refresh 需要寬限窗，否則誤登出。
- 選項 B：只做「以 DB 為權威」，不做輪替。**代價**：refresh token 外洩後 7 天內仍可無限換發。
- 選項 C：維持現狀，另外補「改 role 時 bump tokenVersion」的寫入端。**代價**：最便宜，但快取仍是權威，任何繞過 `SessionRevoker` 的 DB 變更 7 天內不生效。

**D7｜事件契約規範的強制程度（ARCH-07）**
規範草案：所有跨模組事件 record 一律放 `blog-infrastructure/event`、首欄 `UUID eventId`、只帶 UUID 不帶 Long PK。
- **代價**：目前 5 個事件帶 Long PK 上線，改成 UUID-only 會讓每個 consumer 多一次 lookup（與 PERF-02/20 的減少查詢方向相反，除非同時在 facade 提供批次 lookup）。
- 選項 A：全面套用（12+ 檔，一次到位）。選項 B：只補 `eventId`、暫時容忍 Long PK（先解 T5 冪等，位置與 ID 型別另案）。

**D8｜ArchUnit 守衛的嚴格度與例外清單（ARCH-17→ARCH-03）**
守衛規則的前置是 `architecture.md` 目前**不存在**的 13×13 允許矩陣。現有的 12 處跨模組 import（version→article ×5、series→article ×5、reading→article ×2）要判成「例外白名單」還是「必須先修掉」，決定了守衛是「今天就能全綠」還是「先紅一片」。
- 選項 A：先寫寬鬆規則（含例外清單）今天就綠，例外逐條消化。選項 B：先修 ARCH-04/13/23/26 再上嚴格規則。

**D9｜backlog `2026-07-29-article-file-binding-via-mq` 是否關閉**
架構報告評估必要性「中低」：前置動機已達成，剩餘只是整潔度收益，且改 MQ 會引入作者以外看不到圖的一致性視窗。建議關閉或降為 Info——但這是 Yuan 當初開的待辦，由 Yuan 決定。

**D10｜Jacoco 是否設覆蓋率門檻**
現況有 `prepare-agent` + report 且 CI 上傳 artifact，但無 `jacoco:check` 規則 → 覆蓋率只被觀測不被強制。在沒有覆蓋率目標共識前加門檻反而製造噪音。需要 Yuan 決定是否要目標值（以及 `blog-module-user` 0 個 IT、`blog-infrastructure` 0 個 IT 這兩個安全核心模組是否要單獨定標）。

**D11（次要）｜版本保留策略 `retain: 50`（PERF-37）** — 穩態下 `article_versions` ≈ `articles.content_md` 的 50 倍。維持、調降、或改存 diff？
**D12（次要）｜匿名是否可寫入全站熱門搜尋詞（SEC-05）** — 關掉會讓匿名搜尋不計入熱門榜，是產品功能取捨。

---

## 5. 基線 `findings.md` 的狀態變更

### 5.1 已修（附證據）

| 基線 ID | 證據（develop @ f0e3cb1） |
|---|---|
| **AUTH-03** | `SeriesService.java:274-277` `getSeriesDetail` 以 `.filter(a -> ArticleStatus.isPubliclyVisible(a.status()))` 過濾；`:60-62` size 夾 [1,100] |
| **AUTH-07** = **FILE-01** | `FileMetadata.java:59-61` `storagePath` 已 `@JsonIgnore`；`FileController.java:121-134` `getFileMetadata` 已套 `canRead` 授權矩陣、無權回 403 |
| **FILE-07** | `FileServiceImpl.java:225-227` 改回相對路徑 `/api/v1/files/{id}/content`（不再回裸 object URL）；`MinioConfig.java:83-100` `ensureBucket` 不設任何 public-read policy |
| roadmap 小勝利池「書籤/系列 2N+1」 | `ArticleQueryService` 改走 `findIdsByUuids` 批量；`SeriesService.java:332`、`BookmarkController.java:67` 皆走批次 API |
| roadmap「article↔file 循環依賴」 | `ArticleLookupFacadeImpl.java:36-37` 依賴閉包只有 `ArticleRepository`；`FileServiceImpl.java:93` 注入 `ArticleLookupFacade` 而非 `ArticleFacade`。零 `@Lazy`、未開 `allow-circular-references`——**靠重構解決而非規避** |

### 5.2 部分修 / 需重新定性

| 基線 ID | 變化 |
|---|---|
| **FILE-02** | **大幅緩解**：`FileServiceImpl.java:491-505` 5 分鐘 presigned、`FileController.java:188-196` 302 + `Cache-Control: private`；Tika magic-byte + 4 種 image allowlist 使 svg/html 無法上傳。**殘留 LOW**：MinIO 直出回應仍無 `X-Content-Type-Options`／`Content-Disposition`（應用層無法插手） |
| **DATA-11** | **部分修**：`ArticleTagEvent` 已補 `eventId`；`IdempotencyService` 使用者 1/9 → 3/9（`SeriesArticleDeletedConsumer:56`、`TagUsageConsumer:77`、`ViewCountConsumer:57`）。roadmap C3 = 4/10 事件、C4 = 2/3 計數型 consumer（version snapshot 仍缺，因 `ArticleContentChangedEvent` 無 `eventId`） |
| **AUTH-02** | **已收斂（WONTFIX 部分成立）**：`ArticleCommandSubService.java:56-61` `VALID_TRANSITIONS` + `:445-456` `validateStatusTransition` 已限制 `UpdateArticleRequest.status`，PENDING_REVIEW→PUBLISHED 強制 ADMIN。**但同一守衛未涵蓋 restore 路徑**（= AUTH-01） |
| **AUTH-01 / DATA-03** | **仍開放，且定性需修正**：2026-07 Q1 定案後降為「純一致性破口（非審核繞過）」；本輪 `ArticleFacadeImpl.java:427-429` + `VersioningService.java:256-296` 的證據顯示 REJECTED/PENDING_REVIEW → PUBLISHED 為**真實授權繞過**，嚴重度應上調（待 **D3** 拍板） |
| **RACE-16** | **重新定性 Info → MEDIUM**：`FileServiceImpl.java:241,250-262` 未變，但從連線池佔用角度（池僅 10 條、MinIO 無逾時設定）成本被低估 |
| **TEST-08** | **仍開放，且範圍應擴大**：不只 `CrossModuleVersionIT`，`CrossModule{Series,Comment,Reading}IT` **四個全部**把跨模組 facade 一併 mock（`CrossModuleVersionIT.java:117-126` 有 9 個 `@MockitoBean`）。名稱承諾「跨模組」，而跨模組的接縫正好是被 mock 掉的那一層 |
| **AUTH-07 / FILE-01（架構視角）** | 安全維度判「已修」，架構維度判「**修法本身是債**」：`@JsonIgnore` 把序列化關注點焊進 entity，代表 entity 已正式當成 API DTO；同一個坑漏掉的 `Tag.java:42-43` `isNew` 現在正以 `"new": false` 出現在 `GET /api/v1/tags` 的回應裡。見 §6 T5 |

### 5.3 惡化

| 項目 | 基線 → 現況 |
|---|---|
| backlog `2026-07-14` **M1**（SeriesMapper 直讀 `articles`） | **4 處 → 7 處**（`SeriesMapper.java:34-35,39-40,48-49,77-78,93-94,106,115-117`），且 `:106` 新增 `status = 'PUBLISHED'` 字面字串繞過 enum |
| roadmap「AuthService 待拆」 | **543 行 → 569 行 / 8 依賴** |
| roadmap「architecture.md 僅列 4/10 模組」 | 分母變大：**4/13**（`pom.xml:19-34` 現有 13 個模組；`architecture.md` 全檔仍 51 行、無允許矩陣） |
| roadmap「事件類位置不一致」 | 不只位置分裂（3 infra / 6 module / 1 `module/user/model/event`），且 `ArticleTagEvent` **兩地重複定義**，JavaDoc 對同一欄位寫成「公開 UUID」與「資料庫主鍵」兩種相反語意 |
| **新增的守衛缺口** | PR #58 加入的 `ContextSmokeTest` 在 CI **兩個 job 都不會跑**（job 1 未設 `-Dcontext.smoke=true`；job 2 的 `-Pe2e` 把 includes 覆寫成 `**/*E2E.java`）。等於防線寫好了但沒接上——比沒寫更危險，因為它製造了「已有防護」的錯覺 |

### 5.4 仍開放（零進度，逐條證據見原始報告）

`AUTH-04/05/06/08/09/10/11`、`RACE-01~17` 全部、`DATA-01/02/04~10/12~14`、`FILE-03/04/05/06`、`XSS-03`、`DEP-01~05`／`DEP-流程`、`TEST-01~12` 全部（含 TEST-10「un-gate red E2E 是最便宜的防迴歸手段」）、roadmap 工作包 **C1/C2**、backlog `2026-07-07-archunit-guards.md`（全 repo 零 ArchUnit 依賴）、backlog `2026-07-29-index-cache-rebuild-completeness.md`（兩項皆未修）、`2026-07-18-revocation-after-commit-guard.md` 的靜態 guard。

`XSS-01/02`、`DEP-06~17`、`FE-01~11` 屬前端 repo，本輪三個維度皆不適用。

---

## 6. 交叉維度的衝突與張力（修 A 會踩到 B）

**T1｜ARCH-02 × PERF-30 × PERF-15：MQ retry 必須三件一起改，否則越修越糟**
架構建議「9 個 consumer 改成 rethrow 讓 retry interceptor 接手」。但 `StatefulRetryOperationsInterceptor` 的退避是在 **consumer 執行緒上 sleep**，而 `concurrentConsumers` 未設 ⇒ 預設 1。單一毒訊息會讓該 queue 停擺 **1+5+25 ≈ 31 秒**（PERF-30）。更麻煩的是 `RabbitMqConfig.java:141-152` 從零手建 factory、未經 `Configurer`，所以在 yaml 裡加 `concurrency: 4` 會**靜默無效**（PERF-15）。
→ **順序不可拆**：先 PERF-15（factory 改 Configurer 起手），再 ARCH-02（wrapper + rethrow）＋ PERF-30（設 concurrency 2-4）**同一個 PR**。任何拆開的做法都會退化。

**T2｜SEC-02 × 基線 TEST-03：修 restore 前必須先改測試（T6 鐵律）**
`VersioningServiceTest.java:361-380` 與 `ArticleFacadeImplTest.java:686-698` 把「原樣寫 status」斷言成規格。依 CLAUDE.md 的 TDD 鐵律與基線 T6，先改測試（Red 定義正確行為）再改產品碼，否則修復會被固化測試擋下。同型的還有 SEC-06 × TEST-09、PERF-27 × TEST-02/TEST-06。

**T3｜SEC-27 → SEC-09 → ARCH-03 守衛 #2：鏈式依賴，不能跳步**
ArchUnit 守衛 #2 是「每個 Controller handler 必須有 `@PreAuthorize`，白名單為 `security.md` Public Endpoints 表」。但那張表現在與 `SecurityConfig` 三處對不上（SEC-27），而表本身又受 D1 的收窄決定影響（SEC-09）。
→ 順序：**先拍板 D2 修文件 → 拍板 D1 決定收窄與否 → 才寫得出守衛**。直接寫守衛會把錯誤的白名單固化成測試。

**T4｜SEC-01 → SEC-22：IP 層限流在 XFF 修好前是負收益**
SEC-22 建議給 forgot-password / resend-verification / verify-code 三個端點加 IP 層限流。但在 SEC-01 未修前，攻擊者換一個 `X-Forwarded-For` 就繞過，而**正常使用者（共用出口 IP 的公司/校園網路）反而會被誤限**。順序反了就是純粹的可用性損失。

**T5｜ARCH-08 vs SEC(AUTH-07/FILE-01)：兩份報告對同一基線條目給出相反結論**
安全維度判 AUTH-07/FILE-01 **已修**（`@JsonIgnore` 補上、`canRead` 授權到位）；架構維度判 **修法本身是問題**——把序列化關注點焊進 entity，等於正式承認 entity 就是 API DTO，往後每新增一個欄位都是未經審查的 API 契約變更，而防線是「記得加 `@JsonIgnore`」這種人為紀律。**`Tag.isNew` 就是已經漏掉的那一個**（`GET /api/v1/tags` 現在回著 `"new": false`）。
→ 登記簿採**雙軌記法**：AUTH-07/FILE-01 標 DONE（安全面，附證據），另立 ARCH-08 標 OPEN（架構面）。不要因為一面已修就把整條關掉。

**T6｜PERF-29 × code-standards Transaction+MQ：`readOnly=true` 不可盲加**
`ArticleServiceImpl.java:47-50,64-67` 的 JavaDoc 明確記錄 `getArticleByUuid`/`getArticleBySlug` **刻意不開交易**，因為緊接的 `recordView` 會送 MQ。給詳情路徑加 `@Transactional` 會直接踩 BUG-2026-001 的坑（`judgment.md §2` 第一列）。PERF-29 只能套在純讀、不發 MQ 的**列表**方法上。

**T7｜ARCH-07（事件只帶 UUID）× PERF-02/PERF-20（減少查詢）：方向相反**
事件契約規範要求「只帶 UUID，consumer 需要 PK 就自己用 facade 查」。這會給每個 consumer 增加一次 lookup，與性能維度「消滅 N+1、減少跨模組單筆查詢」的主線相反。
→ 若 D7 選全面套用，**必須同時在 `ArticleFacade`/`UserFacade` 提供批次 lookup**（正好是 PERF-02 要做的事），否則等於用架構整潔換來一批新的 N+1。

**T8｜PERF-01（reindex 改 JOIN users）× ARCH-13 守衛 #5（禁止跨模組 JOIN 業務表）：看似矛盾，實則需要規則寫清楚**
PERF-01 建議 reindex 改成 `articles ⨝ users ⨝ article_tags ⨝ tags` 一條查詢；ARCH-03 守衛 #5 則要禁止 mapper SQL 出現他模組業務表名。兩者不衝突（`architecture.md` 的二分表把 `users`/`tags` 列為 **reference data**，可直接 JOIN；`articles`/`comments` 是 **business data**，不可），但**守衛規則必須把 reference/business 二分寫進去**，否則 PERF-01 的修法一上就被自己的守衛判紅。這也是 ARCH-17（補允許矩陣）必須先於 ARCH-03 的又一個理由。

**T9｜SEC-04/PERF-05 夾 size 上限 × 前端 repo：跨 repo 行為變更**
把 size 夾成 [1,100] 會改變既有 API 行為。前端若有任何 `size > 100` 的呼叫會被靜默截斷（不是報錯，是少資料）。上線前需 grep 前端 repo `D:\end\workspace\vue\blog-web-v2-front-end` 的分頁參數，或先以 log 觀察實際流量分佈。

**T10｜SEC-16（`findIdByUuid` 濾 status）× 既有行為：草稿互動路徑會從 200 變 404**
加上 status 條件後，所有互動路徑（comment / like / highlight / reading progress / bookmark）對 DRAFT/PENDING 文章一律回 404。這是想要的安全行為，但**作者自己在編輯器預覽草稿時的路徑必須排除**，否則作者會失去對自己草稿的互動能力。需要在 facade 層區分「公開可見」與「呼叫者可見」。
