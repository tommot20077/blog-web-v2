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
- **T6 測試把 bug 固化成規格（施工順序關鍵）**：TEST-01（DATA-02）、TEST-02（DATA-01/RACE-07）、TEST-03（DATA-03/AUTH-01）三個單元測試把現行錯誤行為斷言為 spec。**依 TDD 必須先改這些測試（Red 定義正確行為）再改產品碼**，否則修復會被固化測試擋下。另 TEST-10：唯一寫對「正確契約」的 red E2E（雙擊按讚應冪等 200）被排除在 CI gate 外——**un-gate 它是最便宜的防迴歸手段**。
- **T7 XSS 縱深防禦相乘弱點**：XSS-01/02（閱讀器 DOMPurify 配置放寬回 iframe/style）× DEP-08（DOMPurify 3.3.3 有 12 條已知 sanitizer bypass）× XSS-03（無 CSP 後盾）× FILE-02（MinIO 同源 inline serving 無 nosniff，僅靠 allowlist 單點擋 svg/html）。單獨都非 Critical，但四者疊加使前端 XSS 邊界脆弱。修法互補：升 DOMPurify + 收緊配置 + 加 CSP + MinIO nosniff/attachment。
- **T8 後端沒用的防線**：ArticleMarkdownRenderer 的 OWASP sanitize 產出 `content_html`，但閱讀器實際渲染原始 `article.content`（前端重繪）——後端這道防線對螢幕上的文章本體是死的（XSS 架構事實，呼應契約稽核的 contentHtml 雙軌渲染）。決定文章渲染來源時應收斂。

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

## FILE — 檔案上傳安全（無可利用直接漏洞；核心防線紮實）

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| FILE-01 | Medium | 公開未授權 `GET /files/{id}` metadata 洩漏 storagePath + uploaderId（= AUTH-07） | `SecurityConfig.java:88`、`FileController.java:95-98`、`FileMetadata.java:53-54` | OPEN |
| FILE-02 | Low-Medium | MinIO 同源 inline serving 無 nosniff/無 Content-Disposition；stored-XSS 僅靠 allowlist 單點擋 svg/html | `FileServiceImpl.java:125,178`、`application.yaml:84-88`、`nginx.conf` | OPEN |
| FILE-03 | Low | 使用者可控副檔名進 storage key 與 thumbnail outputFormat（無白名單/未濾 `..`、null byte） | `FileServiceImpl.java:336-341,117-118`、`ThumbnailConsumer.java:74,87` | OPEN |
| FILE-04 | Medium | 縮圖無最大像素防護 → decompression bomb（小 byte 巨像素）致 worker OOM（DoS） | `FileServiceImpl.java:133-144`、`ThumbnailConsumer.java:84-88` | OPEN |
| FILE-05 | Low | size 檢查在全量讀入記憶體之後；multipart 限制未設定 → 實際生效框架預設 1MB，與程式 5MB 矛盾 | `FileServiceImpl.java:95,105`、`application.yaml`（無 multipart） | OPEN |
| FILE-06 | Low | 無 EXIF/GPS metadata 剝除 → 公開 URL 洩漏照片拍攝地點（隱私） | `FileServiceImpl.java:124` | OPEN |
| FILE-07 | Medium | 回傳裸 object URL（無 presigned），bucket 幾必 public-read；配 FILE-01 可繞過難枚舉保護 | `FileServiceImpl.java:178`、`MinioConfig.java:83-100` | OPEN |

---

## XSS — 內容淨化（無可直接執行 script 的 stored-XSS；sanitizer 皆正確接線）

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| XSS-01 | Medium | 閱讀器 markdown 渲染器 DOMPurify 放寬 `ADD_TAGS: ['iframe']` → 可植入釣魚/clickjacking iframe | `front-end/composables/useMarkdownRenderer.ts:26-40` | OPEN |
| XSS-02 | Low-Medium | 同上放寬 `ADD_ATTR: ['style']` → UI-redress/clickjacking overlay、CSS 資料外洩 | `front-end/composables/useMarkdownRenderer.ts:38` | OPEN |
| XSS-03 | Medium | 兩 repo 皆無 CSP（缺後盾）；nginx/SecurityConfig 都未設 | `front-end/nginx.conf:34-37`、`SecurityConfig.java:68-108` | OPEN |

---

## DEP — 依賴健康（前端有可利用高危；後端衛生相對良好）

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| DEP-06 | High | 前端 axios@1.13.6 多 CVE（SSRF/prototype-pollution/auth-bypass，CVSS≤8.7）→ 升 ≥1.18.1 | `front-end/package.json` | OPEN |
| DEP-07 | High | 前端 vite@7.3.1 路徑遍歷/任意檔讀（dev server 面）→ 升 ≥7.3.5 | `front-end/package.json` | OPEN |
| DEP-08 | Medium | 前端 dompurify@3.3.3 有 12 條 sanitizer bypass → 升 ≥3.4.11（與 XSS-01/02 相乘，見 T7） | `front-end/package.json` | OPEN |
| DEP-09 | Medium | 前端 markdown-it@14.1.1 ReDoS（smartquotes 二次方）→ 升修補版 | `front-end/package.json` | OPEN |
| DEP-10~11 | Medium | postcss <8.5.10 (XSS)、@unhead/vue<2.1.13（protocol 繞過）→ 升修補版 | `front-end/package.json` | OPEN |
| DEP-12~17 | High/Med/Low | transitive：form-data/js-cookie/ws/linkify-it（High）、follow-redirects（Med）、esbuild（Low）→ `npm audit fix` | `front-end/package-lock.json` | OPEN |
| DEP-03 | Medium(verify) | 後端 tika-core 3.1.0 舊（CVE-2025-54988 在 parsers 模組，本專案僅 tika-core 很可能不受影響）→ 升 3.2.2+ | `pom.xml` | OPEN(verify) |
| DEP-04/05 | Low/Info(verify) | 後端 jjwt 0.12.3 略舊（無已知 CVE）；Spring Security 6.5.x 確認版本 | `pom.xml` | OPEN(verify) |
| DEP-01/02 | Low | 後端 `testcontainers.version` property 未定義（convergence 風險）；okhttp 4.12.0 未集中管理 | `pom.xml`、`blog-infrastructure/pom.xml:107` | OPEN |
| DEP-流程 | Medium | 兩 repo 皆無 dependabot；CI 不跑 npm audit / dependency-check（高危依賴無守門） | `.github/`（兩 repo） | OPEN |

---

## FE — 前端執行期品質（cleanup/async 紀律佳；風險在 error 韌性與 a11y）

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| FE-01 | High | 無全域 error handler → 元件 render 出錯即白畫面；unhandledrejection 無回報 | `front-end/main.ts`（無 errorHandler） | OPEN |
| FE-02 | Medium | 編輯器無未存檔守衛（無 onBeforeRouteLeave/beforeunload）→ 切頁/關頁草稿靜默消失 | `front-end/views/EditorView.vue`、`composables/useEditorForm.ts:21-23` | OPEN |
| FE-03 | Medium | useTheme 與 useAppearance 各持一份 isDark → 桌面切主題手機圖示 desync | `front-end/composables/useTheme.ts:3-25`、`useAppearance.ts:19-39` | OPEN |
| FE-04 | Medium | toast 無 role/aria-live 不被螢幕閱讀器朗讀；關閉鈕無 aria-label | `front-end/components/ui/ToastContainer.vue:8-22`、`ToastItem.vue:21-46` | OPEN |
| FE-05 | Medium | 篩選 checkbox 鍵盤不可操作（label click + input readonly + .prevent） | `front-end/views/ArticleList.vue:142-152,174-184` | OPEN |
| FE-06 | Low | useComments 分頁/排序無 stale-response guard → 快速翻頁舊回應覆蓋新 | `front-end/composables/useComments.ts:18-33` | OPEN |
| FE-07 | Low | useRelatedArticles fetch 無取消（靠 ArticleDetail 強制 remount 緩解） | `front-end/composables/useRelatedArticles.ts:9-24` | OPEN |
| FE-08 | Low | useGlobalReveal MutationObserver 每次 DOM 變動全文件 querySelectorAll（jank） | `front-end/composables/useReveal.ts:41-52` | OPEN |
| FE-09 | Low | 留言 textarea / 編輯器標題僅 placeholder，無 label（a11y） | `front-end/components/comment/CommentForm.vue:45-52`、`EditorView.vue:116-122` | OPEN |
| FE-10 | Low | 頭像預覽 blob URL 無 onUnmounted revoke（微洩漏） | `front-end/views/SettingsView.vue:43-44,65-71` | OPEN |
| FE-11 | Low | useWeather await 後於 unmount 寫 ref（無害） | `front-end/composables/useWeather.ts:9-28` | OPEN |
| FE-脆弱 | Info | ArticleDetail 靠 `<router-view :key="route.path">` remount 才正確；移除 key 會無聲重現 stale-article bug | `front-end/App.vue:57,63-66`、`composables/useArticleDetail.ts:20` | 注意 |

---

## TEST — 測試品質（廣度佳、無 disabled/sleep；缺口在固化 bug 與 over-mock）

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| TEST-01 | High | `deleteAccount` 測試把「只設 DELETED」固化為 spec（擋 DATA-02 匿名化修法） | `UserServiceTest.java:312-324` | OPEN（修 DATA-02 前先改）|
| TEST-02 | High | TagUsageConsumer 測試把「盲目 +1」固化（擋 DATA-01/RACE-07 修法） | `TagUsageConsumerTest.java:58-76` | OPEN（修 DATA-01 前先改）|
| TEST-03 | High | restore 測試把「原樣寫 status + 只發 content-changed」固化（擋 DATA-03/AUTH-01；缺 PUBLISHED→DRAFT 降級案例） | `VersioningServiceTest.java:361-380`、`ArticleFacadeImplTest.java:686-698` | OPEN（修 DATA-03 前先改）|
| TEST-04 | High | like「idempotent」測試只走 fast-path，DIVE/rollback-only 分支全未覆蓋（純 mock 無法重現 RACE-01，給假信心） | `ArticleLikeServiceTest.java:46-57`、`CommentLikeServiceTest.java:53-67` | OPEN |
| TEST-05 | Medium | bookmark 雙擊（RACE-04）unit+IT 皆無守衛測試 | `BookmarkServiceTest.java:44-71` | OPEN |
| TEST-06 | Medium | `Tag.decrementUsage` 單元測試存在但主程式零呼叫（假對稱掩蓋 DATA-01 只增不減） | `TagTest.java:35-45` | OPEN |
| TEST-07 | Medium | 計數型 consumer 冪等/redelivery 僅 series 有測；tag/view 無（DATA-11） | `SeriesArticleDeletedConsumerTest.java:65`、`TagUsageConsumer`（無） | OPEN |
| TEST-08 | Medium | restore 跨儲存連鎖 CrossModuleVersionIT mock 掉 SeriesFacade+MQ，ES/series 副作用未整合測 | `CrossModuleVersionIT.java:118` | OPEN |
| TEST-09 | Medium | AUTH-06 未守：測試只斷言 PENDING 可認證，未斷言其 authority 被降級（Q3 定案不可寫） | `JwtAuthenticationFilterTest.java:259-288` | OPEN |
| TEST-10 | Medium | 唯一寫對正確契約的 red E2E（雙擊按讚冪等 200）被排除在 CI 外，僅 `-Pred-e2e` 跑 | `blog-start/pom.xml:141-177`、`e2e/red/*` | OPEN（un-gate 便宜）|
| TEST-11 | Low | 部分 facade 委派/stub 測試為套套邏輯（斷言 mock 自己的回傳），低信號 | `ArticleFacadeImplTest.java:517-555` | OPEN |
| TEST-12 | Low | recordAutoSnapshot 吞 FK violation 被固化（T2 統一時一併重審） | `VersioningServiceTest.java:121-162` | OPEN |

---

## 已驗證安全/一致（覆蓋佐證，摘要）

- **AUTH**：article CUD/read（checkWritePermission / checkReadPermission，非 PUBLISHED 回 NOT_FOUND 無列舉洩漏）、comment edit/delete ownership、reading 全模組複合鍵刪除、series attach 雙重檢查（不能掛他人文章）、version promote/delete/restore ownership + assertVersionBelongsToArticle、preference 僅吃 principal、user /me/* 無 target 參數、register 硬編 Role.USER、search history 綁 userId、search/recommend 全鏈路強制 PUBLISHED。
- **RACE**：article_likes/comment_likes/bookmarks/tag_follows UNIQUE 約束存在；unlike/unbookmark 以 affected rows 決定 decrement；article/comment/series 計數為原子 SQL 帶 underflow 守衛；tag follow 用 ON CONFLICT；IdempotencyService 用 ON CONFLICT + REQUIRES_NEW（at-most-once 為刻意）；文章 slug 隨機後綴 + UNIQUE；瀏覽去重 SETNX；ViewCount 用 GETDEL；MQ 皆 commit 後發送。
- **DATA**：article like_count/comment_count（單則層級）增減對稱；文章硬刪 DB 級聯完整（V12–V16 ON DELETE CASCADE）；檔案配額即時 SUM 無漂移；檔案上傳/刪除 MinIO↔DB 補償；trending 每 30 分自 DB 重建 + 讀取端過濾 PUBLISHED；tag/category 刪除前置防護；軟刪留言讀取過濾統一。
- **FILE**：Content-Type 走 Tika magic-byte 真實偵測（非採信 client 宣告）+ allowlist 僅 jpeg/png/webp/gif；無 SSRF（無遠端 URL 抓取）；上傳需 `FILE_UPLOAD`、刪除需擁有權/admin；DB↔MinIO 補償完整。
- **XSS**：後端 ArticleMarkdownRenderer / CommentMarkdownRenderer 皆 OWASP allowlist（strip on*、限 http/https），有測試佐證；留言顯示雙重淨化（OWASP + 前端 DOMPurify 預設嚴格）；搜尋高亮 strip 全標籤後只注入字面 `<mark>`；markdown `javascript:`/entity 混淆連結前後端皆擋。
- **FE**：listener/observer 全數 teardown（useCursor/useSearch/CodeMirror 等）；search/highlight/reading-progress 有 loadRequestId stale-guard；按讚 optimistic isPending 防雙擊；401 refresh 佇列穩健、access token memory-only。
- **TEST**：token-version/refresh 安全路徑覆蓋強（WP-A 兩個 fossil 已修正並有正向測試）；MyBatis SQL/約束/cascade 跑真 postgres Testcontainer（非 mock）；ownership 紀律、單列計數對稱、IdempotencyService+series consumer 皆有測；無 @Disabled/Thread.sleep/順序假設。

---

## 建議修復批次（依 ROI；實際排程待 Yuan 定）

> **施工鐵律（T6）**：DATA-01/02/03 修復前，必須先改 TEST-01/02/03（Red 定義正確行為）。否則固化測試會擋下修復。

**便宜的縱深防禦/守門（低風險、可先做）**
- 前端 `npm audit fix` + 升 axios≥1.18.1 / dompurify≥3.4.11 / vite≥7.3.5（DEP-06~17）；兩 repo 補 dependabot + CI audit gate。
- un-gate red E2E 進正常 CI（TEST-10）；加 CSP（XSS-03）；MinIO nosniff+attachment（FILE-02）。

**正確性主線（依序）**
1. **T2 冪等反模式**（RACE-01/03/04/08/15）：統一改 ON CONFLICT + GlobalExceptionHandler 補 DIVE/OptimisticLock 兜底。一次消除一整類 500。
2. **T3 計數器**（DATA-01 tag 重設計最優先〔先改 TEST-02/06〕、DATA-06、RACE-07/09）。
3. **RACE-02** nickname unique migration（新 Flyway V19 + 更新 schema.md）。
4. **T1 status 一致性**（AUTH-01 + DATA-03，先改 TEST-03）——自助發布已定案合法，僅修 restore 副作用連鎖。
5. **T4 刪除連鎖**（DATA-02 匿名化〔先改 TEST-01〕、DATA-04、DATA-07）。
6. **AUTH-06**（PENDING 不可寫，Q3 定案；先加 TEST-09）、**AUTH-03/04**、**FILE-01/04**。
7. **RACE-05** editor expectedVersion（前後端協同）。
8. **前端體質**：FE-01 全域 error handler、FE-02 未存檔守衛、FE-03 主題 desync、FE-04/05/09 a11y。
9. **XSS-01/02** 收緊 useMarkdownRenderer DOMPurify 配置。
10. 其餘 Low/Info 依運維痛感穿插；DATA-11 併入工作包 C。
