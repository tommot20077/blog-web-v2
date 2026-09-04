# Findings 登記簿

> **用途**：批次修復前的問題總帳。只記錄、不動工。每筆含 ID / 嚴重度 / 證據 file:line / 情境 / 修法方向 / 狀態。
> **狀態圖例**：`OPEN`（待修）/ `FIX`（修復中）/ `DONE`（已修）/ `WONTFIX`（確認為刻意設計）/ `NEEDS-DECISION`（待 Yuan 業務判斷）。
> **編號慣例**：每輪稽核以維度前綴 + 流水號登記；**同一現象不重複登記**——後輪若重述前輪已登記的問題，一律就地更新該筆的狀態與證據，並在標題後標 `→ <新編號>` 指向本輪報告的條目編號。

## 稽核輪次

| 輪次 | 日期 | 範圍 | 維度（前綴） |
|---|---|---|---|
| 第一輪 | 2026-07-10 | 實作層三路唯讀稽核，承接架構層體檢（見 `roadmap.md`） | AUTH / RACE / DATA |
| 第二輪 | 2026-07 | 檔案上傳 / 內容淨化 / 依賴 / 前端 / 測試品質 | FILE / XSS / DEP / FE / TEST |
| 第三輪 | 2026-09-04 | `origin/develop` @ `f0e3cb1`（**含 PR #53–#59 全部合併後**）後端三維度靜態稽核 | SEC / ARCH / PERF |

---

## 2026-09-04 第三輪稽核（develop @ f0e3cb1）

> **範圍**：只涵蓋本 repo 的後端（`blog-*` 全部 14 個模組 + `blog-db-migration` + `.github/` + `docs/api-contract/` + `ai-docs/`）。前端 repo（`D:\end\workspace\vue\blog-web-v2-front-end`）不在本輪範圍，故 `XSS-01/02`、`DEP-06~17`、`FE-*` 本輪不重新判定。
> **方法**：三路唯讀靜態分析（rg / git / 讀檔）。未執行 Maven、未跑測試、未修改任何產品碼。
> **決策文件**：`be-triage-2026-09-04.md`（四象限行動清單、Yuan 待拍板事項、跨維度張力）。本檔只當登記簿，不重複 triage 的排序與取捨。

**三維度統計**

| 維度 | 前綴 | 原始條數 | HIGH | MEDIUM | LOW | 本檔新登記 | 更新既有 |
|---|---|---|---|---|---|---|---|
| 安全（認證授權 / Token 鏈 / 輸入輸出面 / IDOR / 限流 / 設定秘密 / 相依） | SEC | 28 | 3 | 13 | 12 | 16 | 11（就地更新 AUTH/FILE/XSS/DEP 對應筆） |
| 架構（模組邊界 / 事件契約 / 分層 / 守衛 / 啟動設定 / 測試架構 / 契約真相） | ARCH | 28 | 3 | 14 | 11 | 26 | 2 併入 SEC 條目 |
| 性能（資料存取 / SQL 與索引 / 交易邊界 / 快取 / MQ / ES / 檔案 / 連線池） | PERF | 38 | 4 | 25 | 9 | 30 | 8 併入其他條目 |
| **合計** | | **94** | **10** | **47** | **26** | **72** | |

原始 94 條扣除 **11 組跨維度重述**（同一現象的不同視角）後，**實際 83 條**：HIGH 10 / MEDIUM 47 / LOW 26。無 CRITICAL、無可匿名直接利用的認證繞過。

**11 組合併**（保留各維度視角，只登記一筆）：
`ARCH-28→SEC-01`（ClientIp）、`ARCH-27→SEC-02`（restore）、`PERF-05→SEC-04`（分頁）、`PERF-36→SEC-05`（recordSearch）、`PERF-25→SEC-11`（decompression bomb）、`PERF-24→SEC-17`（multipart）、`PERF-14→ARCH-05`（孤兒 queue）、`PERF-30→ARCH-02`（MQ retry）、`SEC-26→SEC-03`（refresh 鏈）、`PERF-13→PERF-01`（reindex）、`PERF-34→ARCH-13`（SeriesMapper）。

**本輪未發現新問題的區域**（三份報告的「檢查過但無 finding」交集，作為覆蓋佐證）：SQL Injection（全 mapper `#{}` 綁定、零 `${}` 插值）；ES 查詢注入與未發布內容洩漏（typed builder + 硬性 `status=PUBLISHED` filter）；IDOR（article/version/series/comment/file 六路 owner 檢查逐一複核）；Cookie 屬性（HttpOnly+Secure+SameSite=Strict）；撤銷時序（`SessionRevoker` 的 `afterCommit` 實作**優於**未合併 commit `59c170d`）；`@AuthenticationPrincipal` 無 `@PreAuthorize` 的 8 處全部合規；admin 端點雙層保護成立；Transaction+MQ 時序全站正確（BUG-2026-001 FIN-2 未復發）；Maven 模組依賴圖無環；`blog-common` 為乾淨 shared kernel；對外 ID 全 UUID 化（24 個 response DTO 零 `Long id`）；`ai-docs/schema.md` 與 V1–V21 完全同步（20 條 `CREATE INDEX` 逐條對得上）；測試紀律（1511 個 `@Test`、零 `@Disabled`、零 `Thread.sleep`、零中文測試方法名）。

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

### 第三輪新增（2026-09-04）

- **T9 守衛全線缺席（寫了不跑 / 跑了不驗）**：ARCH-03（ArchUnit 為零；`ContextSmokeTest` 被 `-Dcontext.smoke` gate 關掉且 CI 兩個 job 都不設它）、ARCH-25＝TEST-10（red E2E 從不進 CI）、ARCH-12（surefire `<includes>` 複製 10 份、4 個模組沒有 → 新增 `*IT.java` 會永遠綠永遠不跑）、ARCH-20（`autoAckContainerFactory` 零使用者卻有測試斷言它 = 假覆蓋）、ARCH-21（`allow-bean-definition-overriding` 讓唯一的 context 守衛比 production 寬鬆）、ARCH-10（四個 `CrossModule*IT` 把跨模組接縫全 mock 掉）、SEC-15（CI 無依賴掃描、無 dependabot）、ARCH-14（自建 OpenAPI 稽核腳本無 runner）。**前置**：ARCH-17（`architecture.md` 沒有允許矩陣，寫不出可對照的規則）。此主題是 `institution-notes.md`「文件擋不住不讀文件的人，測試才擋得住」的直接對立面。
- **T10 讀路徑放大器鏈**：SEC-04＝PERF-05（五組列表端點 `size`/`page` 無上下界）× PERF-02（作者解析 2N 條 `SELECT * FROM users`）× PERF-03（`batchGetProgress` 是 N 次 Redis RTT + 整頁第二次 `SELECT *`）× PERF-06/PERF-20（一律 `SELECT *` 拖三個 TEXT 欄位）× 索引缺口家族（PERF-07~11）。單獨都是 MEDIUM，相乘後 `GET /api/v1/articles?size=1000` 匿名一發 ≈ 2005 條 SQL。**先關放大器（PERF-05）再逐條解 N+1**，爆炸半徑立刻收斂。
- **T11 MQ 契約與重試的整體失能**：ARCH-01（`ArticleTagEvent` 兩地重複定義且語意分歧）、ARCH-02＝PERF-30（retry interceptor 是死碼；若照建議修活但不設 concurrency 會變 31 秒頭部阻塞）、PERF-15（手刻 factory 使 `spring.rabbitmq.listener.simple.*` 靜默失效）、ARCH-05＝PERF-14（孤兒 queue 堆積全站語料）、ARCH-06（同一 exchange 有 4 份真相）、ARCH-07（事件規格三重不一致）、ARCH-18/19（TTL 與 DLQ 常數不一致）、ARCH-24（明文 token 進 durable queue 與 DLQ）。**修法有強制順序**：PERF-15 → ARCH-02 + PERF-30 同一 PR，見 triage §6 T1。
- **T12 設定面的靜默預設**：SEC-12（`application.yaml` 的 `${ENV:default}` 是**可用**憑證而非佔位符，缺環境變數不會啟動失敗）、SEC-13（`jwt.private-key` 未設即每 pod 各生一把金鑰 → 表現為「偶發登入失效」而非啟動失敗）、SEC-17＝PERF-24（框架 1MB 預設與程式 5MB 檢查互相矛盾）、PERF-15（Hikari / prefetch / concurrency 全空白）、PERF-16（排程池預設 1，3 個 job 串行）。共同病灶：**失敗模式是靜默降級而非 fail-fast**，全部要到生產才會以難診斷的形式出現。

---

## 業務判斷已定案（2026-07-10）

- **Q1 作者發文無需強制審核**（作者可自助發布）。影響：
  - AUTH-02 → **WONTFIX**（`DRAFT→PUBLISHED` 自助發布為刻意設計；但 `UpdateArticleRequest.status` 的 mass-assignment 仍應限制為合法轉換，見 AUTH-02 註）。
  - AUTH-01 / DATA-03 → **仍須修**，但重新定性：不是「審核繞過」，而是 restore 靜默把 PUBLISHED 降級時**無 ES 刪除 / series 不清 / count 不減**的一致性破口。
- **Q2 刪除帳號採「匿名化」**（我方建議，Yuan 可推翻）：洗 `users` 列 PII（nickname→「已刪除使用者」、avatar/bio/website/social/location→null、email→墓碑）使既有 JOIN 自動匿名；保留已發布文章、刪除草稿/PENDING；清孤兒檔案（併 DATA-07）；發 `UserDeletedEvent` 供 search reindex。DATA-02 修法據此。
- **Q3 未驗證信箱帳號不可寫入**。AUTH-06 → **確認為 bug 須修**：PENDING_VERIFICATION 不應取得寫入類 authorities/permission。

## 待 Yuan 拍板（2026-09-04 第三輪，選項與取捨見 `be-triage-2026-09-04.md` §4）

| ID | 議題 | 相關 finding |
|----|------|--------------|
| Q4 | 是否收窄 `GET /api/v1/articles/**` 的 `permitAll`（牽動前端；`judgment.md §5`） | SEC-09 |
| Q5 | `security.md` Public Endpoints 表的三處修訂（`judgment.md §5` 明列需拍板） | SEC-27 |
| Q6 | restore 是否應**完全不還原 status**，或把狀態守衛提到共用 policy 並傳 operatorRole | SEC-02（＝AUTH-01/DATA-03，且 Q1 的「純一致性破口」定性可能需修正） |
| Q7 | `article.published` queue 是歷史遺留（刪）還是保留作稽核（加 TTL + 補 consumer） | ARCH-05 |
| Q8 | 註冊端點的重複錯誤碼是否改為統一訊息（安全 vs 註冊 UX） | SEC-14 |
| Q9 | refresh 鏈的修法深度：DB 為權威 / 加 token 輪替 / 只補 role 變更時 bump tokenVersion | SEC-03 |
| Q10 | 事件契約規範的強制程度（是否全面改成「只帶 UUID 不帶 Long PK」——會與消滅 N+1 的方向相反） | ARCH-07 |
| Q11 | ArchUnit 守衛的嚴格度與例外清單（現有 12 處跨模組 import 判白名單還是先修掉） | ARCH-17 → ARCH-03 |
| Q12 | backlog `2026-07-29-article-file-binding-via-mq` 是否關閉或降為 Info（前置動機已由 PR #54 解決） | 見 §backlog |
| Q13 | 是否設 Jacoco 覆蓋率門檻與目標值；`blog-module-user` / `blog-infrastructure`（0 個 IT）是否單獨定標 | ARCH-12 相關 |
| Q14 | 版本保留策略 `retain: 50`（`article_versions` ≈ 正文的 50 倍體積）是否調降或改存 diff | PERF-37 |
| Q15 | 匿名是否可寫入全站熱門搜尋詞（關掉會讓匿名搜尋不計入熱門榜） | SEC-05 |

---

## AUTH — 授權 / IDOR（無 Critical；ownership 紀律整體良好）

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| AUTH-01 | Medium**→High（定性待 Q6）** | version restore 靜默還原快照 status → **SEC-02**。2026-09-04 複核：Q1 後的「純一致性破口（非審核繞過）」定性**不完整**——`applyRestoreContent` 直接 `setStatus` 不過 `validateStatusTransition`，作者可把 REJECTED / PENDING_REVIEW 用舊 PUBLISHED 快照還原成 PUBLISHED，是**真實授權繞過**。另 restore 會發一則保證被丟棄的 `ArticleContentChangedEvent.RESTORED`（原 ARCH-27，consumer 為 no-op） | `VersioningService.java:256-296`、`ArticleFacadeImpl.java:427-429`、對照守衛 `ArticleCommandSubService.java:445-456`、端點 `VersionController.java:110-116`、事件 `ArticleVersionConsumer.java:54-56` | OPEN / NEEDS-DECISION（Q6）|
| AUTH-02 | Low | `UpdateArticleRequest.status` mass-assignment。**2026-09-04：已收斂**——`VALID_TRANSITIONS` + `validateStatusTransition` 已限制為合法轉換且 PENDING_REVIEW→PUBLISHED 強制 ADMIN。**但同一守衛未涵蓋 restore 路徑（見 AUTH-01）**，且 `submitForReview` 另有一套 ad-hoc 判斷（允許 `REJECTED→PENDING_REVIEW`，map 裡沒有）→ 守衛有兩份分歧真相 | `ArticleCommandSubService.java:56-61,346-348,445-456`、`UpdateArticleRequest.java:43` | WONTFIX（部分）／守衛未收斂仍記於 AUTH-01 |
| AUTH-03 | Medium | 公開 `GET /series/{slug}` 洩漏非 PUBLISHED 文章 | **已修證據**：`SeriesService.java:274-277` 以 `.filter(a -> ArticleStatus.isPubliclyVisible(a.status()))` 過濾；`:60-62` size 夾 [1,100] | **DONE**（2026-09-04 複核）|
| AUTH-04 | Medium | comment 寫入/按讚/刪除僅 `isAuthenticated()`，未接回 COMMENT_WRITE/DELETE permission → **SEC-07**。`Role.java:29-32` 的兩個 Permission 全 repo 零使用，未來新增「禁言」角色將完全無效 | `CommentController.java:83,93,104`、`CommentLikeController.java:35,43` | OPEN |
| AUTH-05 | Low | 草稿文章可被互動（like/bookmark/highlight/comment）+ 200/404 存在性 oracle → **SEC-16**。`checkReadPermission` 對「讀文章」做得很嚴，但互動路徑完全繞過它 | `ArticleMapper.java:273-274`（findIdByUuid 不濾 status）；消費端 `CommentService.java:62,202`、`ArticleLikeController.java:62`、`HighlightService.java:34,54`、`ReadingProgressService.java:51,76` | OPEN |
| AUTH-06 | Medium | PENDING_VERIFICATION 帳號取得完整寫入權（Q3 定案：不應可寫入）→ **SEC-06**。現況 `AuthService.login:210-212` 擋下 PENDING 登入故可利用性低，但 filter 這層的縱深缺口仍在 | `JwtAuthenticationFilter.java:94`（原 `:92`）、`:101-103` | OPEN（確認須修）|
| AUTH-07 | Low | 公開 `GET /files/{id}` 回傳含 `storagePath` + uploaderId | **已修證據**：`FileMetadata.java:59-61` `storagePath` 已 `@JsonIgnore`；`FileController.java:121-134` 已套 `canRead` 授權矩陣、無權回 403。**注意**：架構面判定此修法留下 entity-as-DTO 的分層債且同坑漏掉 `Tag.isNew`，另立 **ARCH-08** 追蹤，不因本筆 DONE 而關閉 | **DONE（安全面）**／架構面見 ARCH-08 |
| AUTH-08 | Low | BookmarkController 缺 article null 檢查 → 500 而非 404。2026-09-04 複核仍在；且該 Controller 同時做跨模組編排與分頁計算（**ARCH-09**） | `BookmarkController.java:40-41,50-51` | OPEN |
| AUTH-09 | Info | highlight/comment 對「不存在」vs「屬他人」回不同錯誤碼（列舉 oracle） | `HighlightService.java:34,54`（仍先 `findIdByUuid` 再走 owner 檢查） | OPEN |
| AUTH-10 | Info | 權限命名不一致（Admin* 用 `hasAuthority('SYSTEM_CONFIG')`；version 六個端點用 `isAuthenticated()` 靠 service 兜）。因 URL 層 `/api/v1/admin/** → hasRole('ADMIN')` 雙層仍成立 | `AdminTagController.java:46,60`、`AdminArticleController.java:48`、`AdminCategoryController.java:45,58,72`、`AdminSearchController.java:44,61`、`VersionController.java:58,76,93,111,128,145`、`SecurityConfig.java:112` | OPEN |
| AUTH-11 | Info | admin 改/刪他人留言未記 operator id（無稽核軌跡）；getUserFiles 收 Pageable 未套用 | `CommentService.java:181-186`、`FileController.java:226-233` | OPEN |

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
| RACE-16 | Info**→Medium（2026-09-04 重新定性）** | `@Transactional` 橫跨兩次 MinIO removeObject（deleteFile）。性能維度從**資源佔用**角度重估：Hikari 池僅 10 條（預設值，未設定）且 MinIO client 無逾時 ⇒ MinIO 故障可分鐘級佔住連線。同檔 `uploadFile` 已示範正確的 `transactionTemplate` 做法 → **PERF-28** | `FileServiceImpl.java:241`、`:250-262` | OPEN |
| RACE-17 | Info | TrendingRefreshJob 鎖過期（120s）+ 共用 tmpKey → 可能發布殘缺排行。2026-09-04 補：排程池為預設**單執行緒**，該 job 持鎖期間另兩個 flush job 整批延後 → **PERF-16**；其 3 次全表掃可合併為 1 次 → **PERF-08** | `TrendingRefreshJob.java:54,74,77-78,105-107,125-128` | OPEN |

---

## DATA — 資料一致性 / 跨儲存

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| DATA-01 | High | `tags.usage_count` 只增不減 + 同文章每次編輯/發布重複累加 → 膨脹致 tag 永久無法刪 | `TagUsageConsumer.java:66-74`、`ArticleCommandSubService.java:107,199,274`、`TagServiceImpl.java:207` | OPEN |
| DATA-02 | High | deleteAccount 只改 status，DELETED 使用者內容在所有公開讀取路徑殘留 | `UserService.java:171-185`、`ArticleMapper.java:41`、`CommentMapper.java:38` | OPEN（Q2 定案：匿名化）|
| DATA-03 | High | restore 將 PUBLISHED 靜默降 DRAFT → 無 ES 刪除、series 不清、count 不減（含 AUTH-01）。**2026-09-04：定性需修正**，見 AUTH-01（授權繞過面，→ SEC-02） | `VersioningService.java:256-296`、`ArticleFacadeImpl.java:427-429` | OPEN / NEEDS-DECISION（Q6）|
| DATA-04 | Medium-High | ES 刪除依賴 best-effort MQ，且 `reindexAll` 只 saveAll 不刪殘留 → 幽靈文件 reindex 也修不掉。**2026-09-04：零進展**，且新增發現重建本身是 1+3N 的 N+1、全站正文三份副本進堆、同步阻塞 request thread → **PERF-01 + PERF-13**（同一次重寫解決三者） | `ArticleEventPublisher.java:149-169`、`SearchServiceImpl.java:202-220`、`ArticleFacadeImpl.java:114-119,186-207`、`AdminSearchController.java:43-48` | OPEN |
| DATA-05 | Medium | ES 文件 viewCount/likeCount 硬編 0（增量索引），`sort=hot` 失效、DTO 回傳假計數 | `ArticleSearchListener.java:141-142`、`SearchServiceImpl.java:115-117,251-252` | OPEN |
| DATA-06 | Medium | comment_count（每刪-1）與留言區 totalAll（top-level 墓碑保留）規則分歧，永不收斂 | `CommentService.java:187-188`、`CommentMapper.java:81-83` | OPEN |
| DATA-07 | Medium | 刪文章不清理 MinIO 圖片 + file_metadata（無訂閱 article.deleted）→ 儲存與配額永久洩漏 | `ArticleCommandSubService.java:215-237`、`FileRabbitMqConfig` | OPEN |
| DATA-08 | Medium | ReadingProgressFlushJob lost-update 視窗 → hash TTL 3 天後進度回退。2026-09-04：未變；性能面另增 **PERF-31**（`SMEMBERS` 無界、每 entry 2 條 SQL、`findIdByUuid` 重複查同一篇） | `ReadingProgressFlushJob.java:36,46-64`、`RedisKeyConstant.java:370` | OPEN（同 RACE-13） |
| DATA-09 | Low | ViewCount flush 崩潰視窗遺失該批；`article:views:*` 24h TTL job 停擺則蒸發。2026-09-04：未變；**PERF-16**（單執行緒排程池）會延長此視窗 | `ViewCountServiceImpl.java:93-121`、`RedisKeyConstant.java:118` | OPEN |
| DATA-10 | Low | series.article_count 靠 best-effort MQ；deleteSeries 留 series_position 髒值、無 recount。2026-09-04：漂移在生產完全不可見（**ARCH-16** 無 metrics） | `ArticleCommandSubService.java:235-236`、`SeriesService.java:113-121` | OPEN |
| DATA-11 | Low | 計數型 consumer 缺冪等（僅 series 有）；ArticleTagEvent 無 eventId | **部分修**：`ArticleTagEvent` 已補 `eventId`（`infrastructure/event/ArticleTagEvent.java:23`）；`IdempotencyService` 使用者 1/9 → **3/9**（`SeriesArticleDeletedConsumer.java:56`、`TagUsageConsumer.java:77`、`ViewCountConsumer.java:57`）。**仍缺**：version snapshot（`ArticleContentChangedEvent` 無 eventId）＋其餘 6 個事件 → **ARCH-07**；副作用：`processed_events` 只增不刪 → **PERF-19** | 部分修（工作包 C：C3 = 4/10 事件、C4 = 2/3 consumer）|
| DATA-12 | Low | 對 DRAFT/PENDING 文章可留言/按讚，預先污染計數（recordView 反而有檢查——三計數器不一致） | `CommentService.java:62,202`、`ArticleLikeController.java:62`、`ArticleMapper.java:273-274` | OPEN（同 AUTH-05，→ SEC-16） |
| DATA-13 | Low | `recommend:related:*` 快取內嵌已刪文章至多 1 小時。2026-09-04：未變（唯一 evict 只刪該篇自己的 key）；同一快取另有 stampede 與「key 不含 limit ⇒ 命中即降級」問題 → **PERF-26** | `RecommendServiceImpl.java:70,78-105`、`ArticlePublishedConsumer.java:54-55` | OPEN |
| DATA-14 | Low | thumbnail 競態產生 MinIO 孤兒（刪除早於 consumer 設 hasThumbnail） | `ThumbnailConsumer.java:100-103`、`FileServiceImpl.java:204` | OPEN |

---

## FILE — 檔案上傳安全（無可利用直接漏洞；核心防線紮實）

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| FILE-01 | Medium | 公開未授權 `GET /files/{id}` metadata 洩漏 storagePath + uploaderId（= AUTH-07） | **已修證據**：`FileController.java:126-132` 先取 metadata 再 `canRead`（無權 403）；`FileMetadata.java:59-61` `storagePath` `@JsonIgnore` | **DONE（安全面）**／架構面見 ARCH-08 |
| FILE-02 | Low-Medium | MinIO 同源 inline serving 無 nosniff/無 Content-Disposition | **大幅緩解**：`FileServiceImpl.java:491-505` 改 5 分鐘 presigned、`FileController.java:188-196` 302 導向 + `Cache-Control: private`；Tika magic-byte + `application.yaml:84-88` 僅 4 種 `image/*` allowlist ⇒ svg/html 無法上傳。**殘留 LOW**：MinIO 直出回應仍無 `X-Content-Type-Options`／`Content-Disposition`（應用層無法插手，需 ingress 層處理） | OPEN（殘留 LOW）|
| FILE-03 | Low | 使用者可控副檔名進 storage key 與 thumbnail outputFormat（無白名單/未濾 `..`、null byte）→ **SEC-18**（PLAUSIBLE：MinIO 是否對 key 正規化未實測） | `FileServiceImpl.java:583-590,156-157`、`ThumbnailConsumer.java:74,87` | OPEN |
| FILE-04 | Medium | 縮圖無最大像素防護 → decompression bomb 致 worker OOM（DoS）→ **SEC-11 ＝ PERF-25**。性能面補：`concurrentConsumers=1` ⇒ 單一惡意圖片停擺整條 `file.thumbnail` queue | `FileServiceImpl.java:170-183`（header 讀了寬高卻未據此拒絕）、`ThumbnailConsumer.java:84-88` | OPEN |
| FILE-05 | Low | size 檢查在全量讀入記憶體之後；multipart 未設定 → 實際生效框架預設 1MB，與程式 5MB 矛盾 → **SEC-17 ＝ PERF-24**。2026-09-04 確認未變 | `FileServiceImpl.java:130-145`、`application.yaml`（`rg multipart` 零命中） | OPEN |
| FILE-06 | Low | 無 EXIF/GPS metadata 剝除 → 洩漏照片拍攝地點（隱私）→ **SEC-19** | `FileServiceImpl.java:160-167` | OPEN |
| FILE-07 | Medium | 回傳裸 object URL（無 presigned），bucket 幾必 public-read | **已修證據**：`FileServiceImpl.java:225-227` 改回相對路徑 `/api/v1/files/{id}/content`；`MinioConfig.java:83-100` `ensureBucket` **不設任何 public-read policy**（bucket 維持私有），內容一律走 presigned | **DONE** |

---

## XSS — 內容淨化（無可直接執行 script 的 stored-XSS；sanitizer 皆正確接線）

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| XSS-01 | Medium | 閱讀器 markdown 渲染器 DOMPurify 放寬 `ADD_TAGS: ['iframe']` → 可植入釣魚/clickjacking iframe | `front-end/composables/useMarkdownRenderer.ts:26-40` | OPEN（前端 repo，第三輪不適用）|
| XSS-02 | Low-Medium | 同上放寬 `ADD_ATTR: ['style']` → UI-redress/clickjacking overlay、CSS 資料外洩 | `front-end/composables/useMarkdownRenderer.ts:38` | OPEN（前端 repo，第三輪不適用）|
| XSS-03 | Medium | 兩 repo 皆無 CSP（缺後盾）→ **SEC-08**。2026-09-04 後端側複核：`SecurityConfig` 整段 `authorizeHttpRequests` 鏈**完全沒有 `.headers(...)`**（`headers\|contentSecurityPolicy\|frameOptions` 於該檔零命中）；Spring Security 預設仍送 nosniff + X-Frame-Options，但 **CSP 為零** | `front-end/nginx.conf:34-37`、`SecurityConfig.java:68-122` | OPEN |
| （後端 sanitizer 佐證） | — | 2026-09-04 複核**維持良好**：`ArticleMarkdownRenderer.java:120-142` OWASP allowlist、`:132,138` 只放行 http/https、`:124` heading id 以 `^heading-[\p{L}\p{N}-]{1,64}$` 收斂；`CommentMarkdownRenderer` 同型。**破口在個人資料 URL**（`website`/`avatarUrl` 無協定白名單）→ **SEC-10** | — | 佐證 |

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
| DEP-03 | Medium(verify) | 後端 tika-core 3.1.0 舊 → 升 3.2.2+。**2026-09-04 複核**：本專案僅用 `tika-core` 做 magic-byte 偵測（`FileServiceImpl.java:28,102`），CVE-2025-54988 位於 parsers 模組 ⇒ **PLAUSIBLE 不受影響**，仍建議升 | `pom.xml:49` | OPEN(verify) |
| DEP-04/05 | Low/Info(verify) | jjwt 0.12.3 略舊（無已知 CVE）；Spring Boot 3.5.9 / Spring Security 6.5.x 的 CVE 狀態**未在第三輪確認**（未跑 `dependency:tree`、未查 NVD）→ 交由 SEC-15 的自動掃描接手（triage 判定為「記錄不修」） | `pom.xml:47`、`:38` | OPEN(verify) |
| DEP-01/02 | Low | `testcontainers.version` property 未定義（各模組不指定版本，靠 parent 管理）；okhttp 4.12.0 硬編於子模組、未進 root `dependencyManagement` | `pom.xml`、`blog-infrastructure/pom.xml:105-108` | OPEN |
| DEP-流程 | Medium | 兩 repo 皆無 dependabot；CI 不跑 npm audit / dependency-check → **SEC-15**。2026-09-04 複核：`git ls-files .github` 只有 `workflows/ci.yml`；root `pom.xml` 無 `dependency-check-maven` / `spotbugs` | `.github/workflows/ci.yml:1-60`、`.github/`（兩 repo） | OPEN |

---

## FE — 前端執行期品質（cleanup/async 紀律佳；風險在 error 韌性與 a11y）

> **2026-09-04 第三輪**：FE-01~11 與 FE-脆弱全數位於前端 repo（`D:\end\workspace\vue\blog-web-v2-front-end`），不在本輪後端稽核範圍，狀態**未重新判定**，一律維持前輪結論。

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
| TEST-08 | Medium**→範圍擴大** | `CrossModule*IT` 把跨模組 facade 全 mock。**2026-09-04：不只 version，四個全部如此**——名稱承諾「跨模組」，而跨模組的接縫正好是被 mock 掉的那一層；實際是「用真 Postgres 跑單模組」（SQL/約束驗證仍有價值）。真正覆蓋跨模組的只剩 79 個 E2E，且是單一 job、失敗即全紅的粗粒度防線 → **ARCH-10** | `CrossModuleVersionIT.java:117-126`（9 個 `@MockitoBean`）、`CrossModuleSeriesIT.java:107-114`、`CrossModuleCommentIT.java:100-107`、`CrossModuleReadingIT.java:105-111` | OPEN |
| TEST-09 | Medium | AUTH-06 未守：測試只斷言 PENDING 可認證，未斷言其 authority 被降級（Q3 定案不可寫） | `JwtAuthenticationFilterTest.java:259-288` | OPEN |
| TEST-10 | Medium | 唯一寫對正確契約的 red E2E（雙擊按讚冪等 200）被排除在 CI 外，僅 `-Pred-e2e` 跑。**2026-09-04：零進度** → **ARCH-25**。附帶新發現：`-Pe2e` 把 includes 覆寫成 `**/*E2E.java`，連帶讓 PR #58 新增的 `ContextSmokeTest`（`*Test.java`）在 e2e job 也被排除 → **ARCH-03** | `blog-start/pom.xml:175-177,183-199`、`.github/workflows/ci.yml:74`、`e2e/red/{P0AuthLifecycle,P0AuthorReview,P0ReaderInteraction}RedE2E.java` | OPEN（un-gate 便宜）|
| TEST-11 | Low | 部分 facade 委派/stub 測試為套套邏輯（斷言 mock 自己的回傳），低信號 | `ArticleFacadeImplTest.java:517-555` | OPEN |
| TEST-12 | Low | recordAutoSnapshot 吞 FK violation 被固化（T2 統一時一併重審） | `VersioningServiceTest.java:121-162` | OPEN |

---

## SEC — 安全（2026-09-04 第三輪；28 條中 16 條為新問題，其餘 12 條就地更新於 AUTH/FILE/XSS/DEP）

> 未列於本表的 SEC 編號＝重述前輪已登記項，狀態已就地更新：SEC-02→AUTH-01/DATA-03、SEC-06→AUTH-06、SEC-07→AUTH-04、SEC-08→XSS-03、SEC-11→FILE-04、SEC-15→DEP-流程、SEC-16→AUTH-05/DATA-12、SEC-17→FILE-05、SEC-18→FILE-03、SEC-19→FILE-06、SEC-28→DEP-01~05。SEC-26 併入 SEC-03。

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| SEC-01 | High | `X-Forwarded-For` 無條件被信任 → 登入/註冊 IP 限流與瀏覽去重整層可繞過（`forward-headers-strategy: framework` 的 `ForwardedHeaderFilter` **不做任何信任代理驗證**；`ArticleController` JavaDoc 自稱「可避免偽造」為錯誤假設）。**架構視角**：橫切關注點被複製進兩個 Controller 的 private method，兩份對「可信 IP」的假設互相矛盾（原 ARCH-28）。修法＝移植未合併 commit `59c170d` 的 `ClientIpResolver` | `AuthController.java:170-176`、`ArticleController.java:305-319`、`application.yaml:1-2`、`ArticleViewSubService.java:28` | OPEN |
| SEC-03 | High | Refresh 鏈以 Redis 快取為權威（從不查 DB）、無 token 輪替、無重用偵測；角色變更無任何撤銷路徑（filter 的 role 直接取自 JWT claim，降權最長 7 天不生效）。業務邏輯留在 Controller 直接操作 `StringRedisTemplate`。**併入 SEC-26**：auth hash miss 時 `/refresh` 誤回 `ACCOUNT_SUSPENDED`（filter 有 DB 回填 fallback，refresh 沒有），改以 DB 為權威即自然消失 | `AuthController.java:116-154`（含 `:136-145`）、`JwtAuthenticationFilter.java:56`、`AuthService.java:220-236` | OPEN / NEEDS-DECISION（Q9）|
| SEC-04 | Medium**（合併後實為 High）** | 公開端點分頁參數無上下界 → 匿名放大查詢 / 500。`page=0` 使 `PageRequest.of(-1, size)` 拋 `IllegalArgumentException` → 500；`TagServiceImpl.java:106` 的 `subList` 傳負 limit 亦 500。防護只做在 `SeriesService.java:48,62`（`MAX_PAGE_SIZE=100`）。**性能視角**（原 PERF-05）：它是 T10 整條放大器鏈的開關——`?size=1000` 匿名 ≈ 2005 條 SQL + 1000 次 Redis RTT | `ArticleController.java:75-84,213-220`、`CommentController.java:71-80`、`SearchController.java:56-65`、`VersionController.java:57-66`、`BookmarkController.java:55-63`、`AdminArticleController.java:48-53`、`TagController.java:57-73` | OPEN |
| SEC-05 | Medium | 匿名可寫入全站「熱門搜尋詞」ZSet 並由公開 `/search/suggest` 放送給所有訪客（`q` 無 `@Size`，重複呼叫可推高分數避開 500 成員修剪）→ 內容注入／版面污損；任何以 HTML 渲染建議詞的消費端即成 XSS。**性能視角**（原 PERF-36）：每次搜尋在同步讀路徑上做 2（匿名）～5（登入）次 Redis 寫入 | `SearchServiceImpl.java:275-291,133-135,150-159`、`SearchController.java:56-65,77-81` | OPEN / NEEDS-DECISION（Q15）|
| SEC-09 | Medium | `GET /api/v1/articles/**` 的 permitAll 蓋掉 versions / highlights / progress / edit 的 URL 層保護——目前只靠方法層 `@PreAuthorize` 撐著（邏輯上仍 fail-closed，非現存漏洞），但任何人新增 `GET /api/v1/articles/{uuid}/xxx` 而忘記加註解就直接變匿名可讀，且 `SecurityConfigTest` 無任何一條守住「versions 需認證」。違反 `security.md` 原則 1「兩層缺一不可」 | `SecurityConfig.java:86` vs `VersionController.java:46`、`HighlightController.java:50`、`ReadingProgressController.java:47`、`ArticleController.java:179` | OPEN / NEEDS-DECISION（Q4）|
| SEC-10 | Medium | 個人資料 `website` / `avatarUrl` 無協定白名單 → 可存入 `javascript:` / `data:` URL。後端 markdown 渲染器有嚴格協定白名單，個人資料 URL 卻完全沒有對等防線；這些欄位會經跨模組 `LEFT JOIN users` 帶進留言/文章作者區塊，前端 `:href` 綁定即成 stored XSS（Vue 不過濾 `:href`）。`socialLinks` 亦無驗證與大小限制 | `UpdateProfileRequest.java:36-51`、`UserService.java:84-99`、對照 `ArticleMarkdownRenderer.java:132,138` | OPEN |
| SEC-12 | Medium | `application.yaml` 內建**可用**的預設憑證（非佔位符）：`postgres/password`、`guest/guest`、`minioadmin/minioadmin`，正式包也帶著。生產少注入任一環境變數會**靜默**連上預設憑證而非啟動失敗。ES 的 demo 檔至少用 `__CHANGE_ME_...`，其餘沒有；`application-dev.yaml` 是正確做法可比照 | `application.yaml:14-15,22-23,74-75`、`application-demo.yaml:12,18,38` | OPEN |
| SEC-13 | Medium | `JWT_PRIVATE_KEY` 未設時靜默降級為每次啟動隨機產生金鑰，且**無任何 profile 守衛**（`application.yaml` 完全沒有 `jwt.private-key` 這一行）。生產 K3s 若 Secret 掛載失敗，每個 pod 各生一把 → 多副本 token 互不認（間歇 401）、重啟即全站登出；表現成「偶發登入失效」而非啟動失敗，極難診斷 | `JwtService.java:50-51,74-85` | OPEN |
| SEC-14 | Medium | 註冊回三種不同的重複錯誤碼（`EMAIL_/USERNAME_/NICKNAME_DUPLICATED`）→ 帳號枚舉 oracle。`forgotPassword`/`resendVerification` 都刻意靜默成功以防洩漏，註冊端點卻把同一份資訊直接回吐；配合 SEC-01 可大量枚舉再接密碼噴灑 | `AuthService.java:104-112`、對照 `:337-341` | OPEN / NEEDS-DECISION（Q8）|
| SEC-20 | Low | 密碼重設 token 可累積且重設後不失效：`forgotPassword` 每次都 `save` 新 token 不刪舊的（對照 `resendVerification` 有 `deleteByUserIdAndType`），`resetPassword` 只刪當前那一筆。攻擊者先前觸發過 forgot-password 的話，受害者重設密碼後其手上的另一個 token 在剩餘 15 分鐘 TTL 內**仍可再次重設密碼** | `AuthService.java:370-386`、`:495`、對照 `:432-436` | OPEN |
| SEC-21 | Low | Swagger UI 與 `/v3/api-docs` 在所有環境 permitAll（`springdoc` 為 compile scope、無 profile 隔離）→ 正式站匿名可取得完整 API 清單、參數與 DTO schema，含 admin 端點路徑。非漏洞，但把偵查成本降到零 | `SecurityConfig.java:78`、`blog-infrastructure/pom.xml:23-24` | OPEN |
| SEC-22 | Low | 忘記密碼／重寄驗證信／驗證碼校驗只有 per-email 限流，無 IP 層（對照 login/register 有）。攻擊者拿信箱清單即可做郵件轟炸；整體出信量無全域上限。**順序依賴**：修好 SEC-01 之前加 IP 限流反而會誤限共用出口 IP 的正常使用者 | `AuthService.java:344-362,404-421,295-308`、對照 `:185-188,98-102` | OPEN |
| SEC-23 | Low | 除 auth 外所有寫入端點皆無限流（`RateLimit\|rate:\|RATE_LIMIT` 於非測試 Java 零命中）：已認證者可無限速建立留言/文章/上傳/highlight；匿名可無限速打 `/search`（每次都進 ES 並寫 Redis，見 SEC-05） | 全 repo grep 零命中 | OPEN |
| SEC-24 | Low | 兩個 admin 端點缺 `@Valid`，且其 DTO 完全無約束。`tag.color` 通常被前端綁進 `:style` ⇒ CSS 注入面；`description`/`reason` 無長度上限。權限為 ADMIN 故影響有限 | `AdminTagController.java:47-50`、`ArticleController.java:295-298`、`UpdateTagRequest.java:21,26,31`、`RejectArticleRequest.java:21` | OPEN |
| SEC-25 | Low | 輸入長度約束與 DB 欄位不一致：`RegisterRequest.nickname` 無 `@Size` vs `schema.md:27` `VARCHAR(50)` ⇒ 51 字元註冊拋例外 → `GlobalExceptionHandler:243` 回 **500 而非 400**（可探測欄位邊界）；`socialLinks` / `content` 無上限屬資源濫用面 | `RegisterRequest.java:48-50`、`UpdateProfileRequest.java:44`、`Create/UpdateArticleRequest`；`content` 一項對應 backlog `2026-07-25-toc-security-followup.md §2` | OPEN |
| SEC-27 | Low/Info | `security.md` Public Endpoints 表與 `SecurityConfig` 三處對不上：(1) 表寫 `/api/admin/**`、實作是 `/api/v1/admin/**`；(2) `/actuator/health/**`、`/actuator/info`、`/favicon.ico`、`/error` 未入表；(3) `GET /api/v1/files/**` 描述為「Public file access」但實作已改為「permitAll 只代表可到達 Controller，授權在 `canRead`」。表下方明文要求一對一對得上 | `ai-docs/security.md`（原則 2 表）、`SecurityConfig.java:75,79,88-100,112`、`FileController.java:121-134` | OPEN / NEEDS-DECISION（Q5，`judgment.md §5`）|

---

## ARCH — 架構（2026-09-04 第三輪；基線無 ARCH 專章，前輪架構發現位於 `roadmap.md` 體檢發現索引與 `backlog/`）

> ARCH-27（`ArticleContentChangedEvent.RESTORED` 在唯一 consumer 為 no-op）併入 **AUTH-01**；ARCH-28（ClientIp 兩份實作）併入 **SEC-01**。

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| ARCH-01 | High | `ArticleTagEvent` 事件契約**重複定義**：producer 發 infrastructure 版、consumer 收 tag 版，兩者無編譯期關聯，且 JavaDoc 對同一個 `articleId` 已寫成相反語意（「公開 UUID」vs「資料庫主鍵」；真相是 UUID）。今天能跑是靠 `DefaultJackson2JavaTypeMapper` 預設 `TypePrecedence.INFERRED`；一旦有人改成 `TYPE_ID` 或加 `ClassMapper`，全部 tag usage 計數會靜默進 DLQ（PLAUSIBLE，未實測） | `infrastructure/event/ArticleTagEvent.java:23`、`module/tag/event/ArticleTagEvent.java:21`、`ArticleEventPublisher.java:4,183-186`、`TagUsageConsumer.java:7,70-72`、`RabbitMqConfig.java:79-82` | OPEN |
| ARCH-02 | High | **9/9 consumer 的手動 try/ack/nack 抵銷了 MQ 重試基礎設施**，`StatefulRetryOperationsInterceptor` 永遠不會被觸發，而 config JavaDoc 仍宣稱「重試最多 3 次、指數退避 1s→5s」。ES/Redis 抖動 1 秒 ⇒ search index / tag 計數 / version 快照第一次失敗就直接進 DLQ。維運看到 DLQ 會誤以為「退避重試過 3 次」，實際是「試了一次」。**性能前置**（原 PERF-30）：若改成 rethrow 而不同時設 concurrency，stateful retry 在 consumer 執行緒上 sleep ⇒ 單一毒訊息讓該 queue 停擺約 **31 秒**頭部阻塞 | `RabbitMqConfig.java:141-152,233-248,33-38`；9 個 consumer 逐點清單見 `be-review-architecture.md` ARCH-02 | OPEN（＝roadmap C1/C2，零進度）|
| ARCH-03 | High | **架構守衛全線缺席**：ArchUnit 全 repo 零命中（14 個 pom 無 `com.tngtech.archunit`）；唯一能攔 Spring 組裝期缺陷的 `ContextSmokeTest` 被 `@EnabledIfSystemProperty(context.smoke)` gate 關閉，而 CI 兩個 job 都不設它（job 1 未帶；job 2 的 `-Pe2e` 把 includes 覆寫成 `**/*E2E.java`）。那個躲過 6 個任務、2 輪安全複審與 400+ 綠燈測試的循環依賴，**今天仍然沒有任何自動防線** | `ContextSmokeTest.java:58`、`.github/workflows/ci.yml:29,74`、`blog-start/pom.xml:170-179`、backlog `2026-07-07-archunit-guards.md`、`institution-notes.md:13` | OPEN（前置＝ARCH-17；NEEDS-DECISION Q11）|
| ARCH-04 | Medium | `ArticleQueryService` 成為第二個「事實 facade」：series / reading 跨模組注入 article 模組的 **concrete `@Service`** 而非 facade 介面（`SeriesService.java:11` 原始碼自帶 `// SP-X: ArticleQueryService 跨模組 inject 議題` 註解——問題已被知道但未解）。其依賴閉包含 `ReadingFacade` + `SeriesFacade` ⇒ 兩條路徑各**只差一個依賴就成環**；本次確認尚未成環，但缺乏守衛使它是「今天正確」而非「不會退化」 | `SeriesService.java:11,44`、`BookmarkController.java:7,33,67`、`ArticleQueryService.java:43-49`、`ArticleLookupFacade.java:41-45` | OPEN |
| ARCH-05 | Medium | `article.published` queue 已宣告並綁定、有 producer 持續發送，但**全 repo 無任何 consumer**（9 個 `@RabbitListener` 都不訂它；search/recommend 各有自己的 queue 綁同一 routing key）。durable、無 TTL、無 max-length ⇒ 訊息數與磁碟單調成長，撞 broker alarm 後**進而阻塞所有 publisher**，且不會有任何錯誤日誌。**性能視角**（原 PERF-14）：payload 含 `stripMarkdown` 後的整篇正文 ⇒ 堆積的是「全站語料副本」；本專案 MQ 送出在 request 執行緒內（`publisher-confirm-type: correlated` + `mandatory=true`），broker 一阻塞，發文的 HTTP 請求會直接掛住 | `ArticleRabbitMqConfig.java:35,96-99,106-112`、`ArticleEventPublisher.java:115-134`、`ArticlePublishedEvent.java:30-40` | OPEN / NEEDS-DECISION（Q7）|
| ARCH-06 | Medium | 共用 exchange `article.events` 的拓撲契約有 **4 份真相 + 3 份字面字串**（另有 2 個模組用相反做法：import 生產者 config + `@Qualifier`）。目前 4 處參數一致故能跑；任一處改成 `DirectExchange` 或 `durable=false`，`RabbitAdmin` 會拿到 `PRECONDITION_FAILED (406)` 使**該次宣告整批中止**，症狀是「某些模組的訊息路由不到」而非明確錯誤 | `ArticleRabbitMqConfig.java:86-89`、`SearchRabbitMqConfig.java:35,113-115`、`RecommendRabbitMqConfig.java:31,60-62`、`TagRabbitMqConfig.java:28,45-47`；對照 `SeriesRabbitMqConfig.java:3`、`VersionRabbitMqConfig.java:3,31-34` | OPEN |
| ARCH-07 | Medium | 事件 payload 規格**三重不一致**：`eventId` 覆蓋 4/10；5 個事件帶內部 Long PK 上線（違反「所有對外 ID 皆 UUID」的精神）；事件類位置分裂為 3 種慣例（3 in `infrastructure/event`、6 in `module/*/event`、1 in `module/user/model/event`）。後果：無 `eventId` 者 redelivery 無法去重（version 快照重複寫、recommend 重複計分）；Long PK 讓下游模組依賴 article 的主鍵空間 | 10 個事件 record 全清單見 `be-review-architecture.md` ARCH-07；`ArticleVersionConsumer.java:41-57`、`ArticlePublishedConsumer.java:47-57` | OPEN（＝DATA-11／roadmap C3 4/10、C4 2/3；NEEDS-DECISION Q10）|
| ARCH-08 | Medium | Controller 直接回傳 persistence entity，並用 `@JsonIgnore` 貼在 entity 上當補丁。**與安全維度不同的定性**：AUTH-07/FILE-01 的 `storagePath` 已被遮蔽（安全面 DONE），但這個修法本身把「序列化關注點」焊進 entity ⇒ 每新增一個欄位都是未經審查的 API 契約變更，防線只剩「記得加 `@JsonIgnore`」。**`Tag.isNew` 就是漏掉的那一個**：`@Data` 產生 `isNew()`，Jackson 推導出屬性名 `new`，且沒有 `@JsonIgnore` ⇒ `GET /api/v1/tags`、`/tags/hot`、`PUT /api/v1/admin/tags/{id}` 的回應都帶 `"new": false` | `FileController.java:122,228`、`AdminTagController.java:47`、`TagController.java:71,92`、`FileMetadata.java:41,59-61`、`Tag.java:42-43`；正確對照 `VersionSummaryResponse.java:14` | OPEN |
| ARCH-09 | Medium | Controller 層做跨模組編排與分頁計算：`BookmarkController` 同時注入 3 個依賴、自行算 offset、把 `findIdByUuid` 回傳的**內部 Long 主鍵在 web 層流動**（只是碰巧沒被序列化出去），且該編排無交易邊界也無服務層測試點。null 檢查缺失即 AUTH-08 | `BookmarkController.java:31-33,40,50,63-68` | OPEN |
| ARCH-10 | Medium | 名為 `CrossModule*IT` 的整合測試把所有跨模組 facade 都 mock 掉了（見 TEST-08 的擴大判定） | `CrossModuleVersionIT.java:117-126`、`CrossModuleSeriesIT.java:107-114`、`CrossModuleCommentIT.java:100-107`、`CrossModuleReadingIT.java:105-111` | OPEN（＝TEST-08）|
| ARCH-11 | Medium | `GlobalExceptionHandler`（全 repo **唯一**的 `@RestControllerAdvice`）無 `DataIntegrityViolationException` / `OptimisticLockingFailureException` 兜底 → 基線 RACE-01/03/04/08/15、RACE-06 整類「使用者操作回 500」缺單點修法。在 service 層各自修要動 6 個地方；補兩個 handler 就能把整類降級為可被前端處理的錯誤（更具體的 handler 優先匹配，順序無虞）。**本輪成本/效益比最高的單點** | `GlobalExceptionHandler.java:33,48-232,243-248` | OPEN（＝交叉主題 T2 的一半）|
| ARCH-12 | Medium | surefire `<includes>` 複製在 10 個 module pom、root 無 `pluginManagement`，而 4 個模組**沒有**這段設定。surefire 預設 includes **不含 `**/*IT.java`** ⇒ 只要有人在 `blog-infrastructure`（SecurityConfig/JwtService 所在）或 `blog-module-user`（認證核心）新增一個 `XxxIT.java`，它會**永遠綠、永遠不跑**且無任何訊號——而這兩個正是整合覆蓋最薄的模組（user 32 main/0 IT；infrastructure 32 main/7 test class/0 IT）。附帶：`AuthServiceIntegrationTest` 命名不合 `*IT` 慣例（碰巧結尾是 `Test.java` 才被收） | root `pom.xml`（無 surefire）；10 份複製與 4 個缺漏模組清單見 `be-review-architecture.md` ARCH-12 | OPEN |
| ARCH-13 | Medium | `SeriesMapper` 直接讀 `articles` 業務表，**從基線的 4 處增為 7 處**（`architecture.md` 邊界表明列 `articles` 為業務 Data，跨模組須走 owner service；只有 reference data 可 JOIN）。`:106` 另把 `status = 'PUBLISHED'` 寫成字面字串繞過 `ArticleStatus` enum。諷刺點：**同模組的寫路徑是正確的**（走 `articleFacade.updateSeriesAssignment`），只有讀路徑破例。**性能視角**（原 PERF-34）：`findPublic` 對同一條件同時跑 COUNT 子查詢與 EXISTS 子查詢 | `SeriesMapper.java:31-44,34-35,39-40,48-49,77-78,93-94,106,115-117` | OPEN（＝backlog `2026-07-14` M1，**惡化**）|
| ARCH-14 | Medium | API 契約真相斷鏈：88 個端點中 **41 個（47%）無 `@Operation`**（分裂是整模組的：article/file/search/tag/recommend 五個模組零註解）；全 repo **零 `@ApiResponse`** ⇒ OpenAPI 完全沒有錯誤回應定義；`docs/api-contract/backend-endpoints.md` 停在 2026-05-09、`gap-report.md` 停在 2026-05-15 且寫著「No unresolved current required fixes remain」——**主動誤導**；稽核腳本 `docs/api-contract/scripts/*.test.js` 無 `package.json` 也無 CI 步驟，根本無法執行 | `.github/workflows/ci.yml`、`docs/api-contract/`、21 個 Controller | OPEN |
| ARCH-15 | Medium | 錯誤碼放置有**兩套互斥慣例**（5 個放 `blog-common` shared kernel / 4 個放模組內），且 search / tag / file 三個模組借用 `UserErrorCode.USER_NOT_FOUND` ⇒ 錯誤碼不再能從 code 反推來源模組。`blog-common` 被塞進模組專屬 enum ⇒ 任何模組加錯誤碼都要改 common、所有模組重編譯 | `blog-common/.../errorcode/{Article,Common,File,Tag,User}ErrorCode.java`；`comment/reading/series/version` 的 `exception/*ErrorCode.java`；`SearchController.java:97,116`、`TagController.java:132,153`、`FileController.java:262,265,283` | OPEN |
| ARCH-16 | Medium | 可觀測性空白：無 micrometer / opentelemetry / tracing 依賴（pom 零命中），`exposure.include` 只有 `health,info`。系統刻意選了「MQ 發送失敗不影響主流程」的 best-effort 架構（正確的可用性取捨），但**沒有配套**：一致性漂移只出現在 log 裡，沒有計數器、沒有告警、DLQ 深度不可見 ⇒ DATA-01/04/10 這些已知問題在生產**沒有任何方式能被主動發現** | `application.yaml:91-95`、`blog-start/pom.xml:29-32`、`ArticleEventPublisher.java:66-68,100-102,132-134,166-168,187-189,207-209`、`RabbitMqConfig.java:203-216` | OPEN |
| ARCH-17 | Medium | `architecture.md` 只有 51 行、只列 **4/13** 模組、且**沒有模組→模組允許矩陣**。現行文件能回答「comment 能不能 JOIN users」，但回答不了 ARCH-04/07/15/23/26 的任何一個實際邊界問題。**沒有規範真相，就沒有 ArchUnit 能寫的規則，也沒有 reviewer 能引用的條文**——這是 ARCH-03 的前置依賴 | `ai-docs/architecture.md:5,20-51`（全檔 51 行）、`pom.xml:19-34` | OPEN（ARCH-03 前置）|
| ARCH-18 | Low | `version.snapshot` queue 獨有 `x-message-ttl: 600_000`，而 JavaDoc 宣稱「**對齊全站 DLQ 慣例**」（其餘 7 個 queue 皆無 TTL）。consumer 停機或積壓超過 10 分鐘，版本快照事件會被 broker 判定過期丟進 DLQ——使用者編輯的自動快照無聲消失，且日誌上看起來跟「處理失敗」一樣。滾動更新時很容易觸發 | `VersionRabbitMqConfig.java:41-49`；對照 7 個無 TTL 的 queue 宣告 | OPEN |
| ARCH-19 | Low | DLQ 拓撲常數複製在 8 個模組，而 infrastructure 的正本是 `private static final`（其中 6 個模組還各寫一份同名 `dlqArgs()`）。改 DLQ 名稱要動 9 個檔；漏改一個，該 queue 的失敗訊息會被路由到不存在的 exchange 而**靜默丟棄**（RabbitMQ 對無法路由的 dead-letter 直接丟棄，不報錯） | `RabbitMqConfig.java:50-56` + 8 個模組 config（清單見報告） | OPEN |
| ARCH-20 | Low | `autoAckContainerFactory` 是零使用者的 bean，卻有單元測試斷言它的設定 ⇒ **假覆蓋**：測試綠燈給人「AUTO ack 路徑有測」的印象，實際該路徑在生產從未被走過。bean 自己的 JavaDoc 還警告「一旦拋出異常，預設行為是無限 Requeue」——一顆有陷阱又沒人用的地雷 | `RabbitMqConfig.java:166-179`、`RabbitMqConfigTest.java:154-161` | OPEN |
| ARCH-21 | Low | `application-e2e.yaml` 開啟 `allow-bean-definition-overriding: true`，使**唯一的 context 守衛比 production 寬鬆**。`ContextSmokeTest` 存在的唯一目的是證明 context 組得起來，但重複 bean 定義（modular monolith 最容易犯的失誤）在 e2e profile 下會被靜默接受、在生產啟動時炸——守衛的洞形狀正好是這個架構最容易犯的錯 | `application-e2e.yaml:5-6`、`ContextSmokeTest.java:60` | OPEN |
| ARCH-22 | Low | 五個 god class（>400 行 / ≥8 個注入依賴）：`FileServiceImpl` 605/8、`ArticleCommandSubService` 570/12（全 repo 最高）、`AuthService` 569/8（基線 543，**變長**）、`ArticleFacadeImpl` 480/9、`VersioningService` 456/8。`ArticleFacadeImpl` 的 9 個依賴正是它成為胖 Bean、引發 article↔file 循環依賴的原因 | 各檔行數與依賴數見報告 | OPEN（triage 判定「記錄不修」：`code-standards.md`「Ask before rewriting systems」，風險由 ARCH-03 守衛 #3 兜底）|
| ARCH-23 | Low | version 模組直接 import article 的 Markdown 渲染元件，JavaDoc 用「跨模組注入的既有慣例」正當化違規（`judgment.md §2`「這裡先照舊模式寫」的訊號）。Markdown 渲染其實是**跨模組共用能力**（article/version 都要，comment 已另有一份 `CommentMarkdownRenderer` ⇒ 同一能力兩份實作），卻留在 article 模組裡；article 的渲染管線變更會直接改變歷史版本的還原結果，且無 facade 契約可約束 | `VersioningService.java:10-12,51-56,283` | OPEN |
| ARCH-24 | Low | MQ payload 帶**明文驗證碼 / 密碼重設 token** 進入 durable queue 與 DLQ。token 明文落在 RabbitMQ 磁碟訊息、management UI queue 預覽、以及失敗後長期滯留的 DLQ（`user.*` 兩個 queue 無 TTL）；任何有 broker 讀權限的人（維運、備份還原者）都能取得可直接完成密碼重設的 token。**與 SEC-01~28 無重疊**，建議交叉複核嚴重度 | `UserRegisteredEvent.java:16`、`UserPasswordResetRequestedEvent.java:14`、`AuthService.java:145-148,382-385,452-455`、`UserRabbitMqConfig.java:32,42,56-57` | OPEN |
| ARCH-25 | Low | red E2E 仍被 CI 排除（見 TEST-10），且 `-Pe2e` 的 includes 覆寫連帶排除 `ContextSmokeTest` | `blog-start/pom.xml:175-177,183-199`、`.github/workflows/ci.yml:74` | OPEN（＝TEST-10）|
| ARCH-26 | Low | series 的 API response DTO **內嵌 article 的 API response DTO** ⇒ article 改 `ArticleSummaryResponse` 任一欄位就同時改變 `GET /api/v1/series/{slug}` 的回應 schema，而 series 模組的作者不會知道；跨 repo 契約因此有一條隱形傳染路徑。正確對照組已存在：recommend 用 `infrastructure.facade.dto.ArticleSummaryInfo` | `SeriesDetailResponse.java:4,26`、`SeriesService.java:330-332`；對照 `RecommendArticleResponse.java` | OPEN（ARCH-04 的下游後果）|
| ARCH-29 | Medium | **（稽核後衍生發現：2026-09-04 對 PR #66 的 code review，非第三輪原始 28 條）** `ARCHIVED` 狀態在應用層**完全不可達**：全 repo 無任何 production 碼寫入 `ARCHIVED`，`VALID_TRANSITIONS` 裡 `PUBLISHED → ARCHIVED` 與 `ARCHIVED → DRAFT` 兩條邊沒有任何入口可觸發（`GET /api/v1/articles/archive` 是唯讀的年月歸檔投影，名字像但語意無關）。PR #66 移除 restore 的靜默降級（SEC-02 ＝ AUTH-01/DATA-03）後，**已發布文章只剩硬刪（CASCADE、不可逆）一途**，等同「下架」這個業務動作在系統上不存在 | 補入口前：`ArticleCommandSubService.java` 的 `VALID_TRANSITIONS`（兩條邊零 caller）、`ArticleController.java`（無 archive/unarchive 端點）、`ArticleFacade.applyRestoreContent` JavaDoc「SEC-02：不會改動 article.status」 | **DONE**（2026-09-04，commit `f7e795f` + `bff32c7`）<br>補上兩個 ADMIN-only 入口：`POST /api/v1/admin/articles/{uuid}/archive`（PUBLISHED→ARCHIVED）與 `POST /api/v1/admin/articles/{uuid}/unarchive`（ARCHIVED→DRAFT）。<br>兩者皆經 `validateStatusTransition` 這個唯一守衛，**未改動 `VALID_TRANSITIONS` 內容**；置於 `/api/v1/admin/**` 以同時取得 URL 層 `hasRole("ADMIN")` 與方法層 `@PreAuthorize("hasAuthority('SYSTEM_CONFIG')")` 雙層防護（`security.md` 原則 1；刻意不比照 `POST /api/v1/articles/{uuid}/reject` 那條只有方法層的既存弱點，見 AUTH-10）。<br>連鎖副作用：新增 `article.archived` routing key + search 模組 `queue.search.index.archive` consumer 移除 ES 索引（**刻意不共用 `article.deleted`**——該 key 另有 `SeriesArticleDeletedConsumer` 遞減 `series.article_count`，共用會誤扣且日後真刪再扣一次而漂移）；其餘公開讀取路徑（列表／分類／歸檔投影／series 詳情與列表與導覽／trending 回填與重算／相關文章三層）經逐條查證**全為白名單等值 `status = 'PUBLISHED'`**，改狀態即自動排除，已由 IT 逐條斷言。<br>**殘留（刻意未處理）**：`recommend:related:*` 快取最長 1h 仍可能內嵌已下架文章（同 **DATA-13**，硬刪路徑今日亦同）；收藏列表 `ArticleQueryService.getArticleSummariesByIds` 不濾 status（reading 模組邊界）|

---

## PERF — 性能（2026-09-04 第三輪；基線無 PERF 專章）

> PERF-05→SEC-04、PERF-13→PERF-01、PERF-14→ARCH-05、PERF-24→SEC-17/FILE-05、PERF-25→SEC-11/FILE-04、PERF-30→ARCH-02、PERF-34→ARCH-13、PERF-36→SEC-05，均已併入對應條目，不在本表重複登記。

| ID | 嚴重度 | 標題 | 證據 | 狀態 |
|----|--------|------|------|------|
| PERF-01 | High | ES 全量重建是 **1+3N 的 N+1**（每篇各發 tags / authorUsername / authorNickname 三條 SQL，同一作者每篇重查），全站正文**三份副本同時進堆**（`List<Article>` 含 `content_md`+`content_html`+`toc`、`List<ArticleIndexData>`、`List<ArticleDocument>`），且整段在 HTTP request 執行緒內**同步**跑完、無逾時保護。附帶 `stripMarkdown` 的 10 條 `replaceAll` pattern 未預編譯。**併入 PERF-13**：`saveAll` 前無 `deleteAll()`、無 alias 切換、無分批（backlog 描述的缺口確認 100% 未修，`tag:autocomplete` 無回填亦同） | `ArticleFacadeImpl.java:114-119,186-207,214-227`、`ArticleMapper.java:149-150`、`SearchServiceImpl.java:202-220`、`AdminSearchController.java:43-48`、`TagNormalizationServiceImpl.java:74`、`TagServiceImpl.java:69-80` | OPEN（＝DATA-04 + backlog `2026-07-29-index-cache-rebuild-completeness.md`）|
| PERF-02 | High | 文章列表作者解析 N+1：每篇 2 條 `SELECT * FROM users`（含 `password_hash`），**`UserFacade` 介面根本沒有批次方法**。同一份檔案裡 tags / categories 都正確批次化，`enrich()` 連 liked/bookmarked/progress/series 都批次化了——**作者是唯一漏網的**。`size=10` 的公開列表約 25 條 SQL，其中 20 條出自這裡 | `ArticleResponseMapper.java:62-63,116-117,191-197`、`UserFacade.java:17-42`、`UserFacadeImpl.java:36-64`、`ArticleQuerySubService.java:59-96,167-173` | OPEN |
| PERF-03 | High | `batchGetProgress` 名為批次、實為 **N 次序列 Redis round-trip ＋ 整頁文章的第二次 `SELECT *`**：`findByIds` 只為取 6 個純量卻拉回含三個 TEXT 欄位的完整列（列表查詢剛剛才撈過同一批 row）；`for` 迴圈內每篇一次 `HGETALL`，無 pipeline。同模組的 `batchIsLiked`/`batchIsBookmarked` 都是單條 SQL，形成刺眼反差。每個登入使用者的每次列表請求都觸發 | `ReadingProgressService.java:84-113`（`:89`、`:95-106`）、`ArticleQueryService.java:253`、`ArticleData.java:29-36` | OPEN |
| PERF-04 | High | `GET /api/v1/articles/archive` 匿名、**無分頁**、全表 `SELECT *`、`ORDER BY published_at`（該欄無索引）、無快取。單一匿名 GET 就把全站文章語料撈進堆，只為輸出 5 個欄位；接著 `findTagsByArticleUuids` 用 `<foreach>` 組出含全部文章 UUID 的 `IN (...)`，文章數成長後 SQL 文字本身就是問題 | `ArticleController.java:97-99`、`ArticleQuerySubService.java:98-117`、`ArticleMapper.java:149-150,217-224`、`ai-docs/schema.md` §articles Indexes | OPEN |
| PERF-06 | Medium | 列表與詳情一律 `SELECT *`，把 `content_md`/`content_html`/`toc` 三個 TEXT 欄位拖進所有讀取路徑，而列表 DTO 完全不含這三欄。同專案已有正確範本（`VersionMapper` 明確選欄 + `length(content)`）卻沒套到 article。與 PERF-03/PERF-20 相乘：同一批 row 的大 TEXT 在單次請求裡最多被搬三趟 | `ArticleMapper.java:41,61,76,108,149,178,282`、`ArticleQuerySubService.java:129,150`；對照 `VersionMapper.java:52-66` | OPEN |
| PERF-07 | Medium | `article_tags.tag_id` 與 `user_tag_follows.tag_id` **無索引**（兩表的 PK 都以另一欄前導 ⇒ B-tree 用不上）→ 相關文章推薦（每篇文章詳情頁 cache miss 都跑）與標籤刪除前檢查皆**全表掃**，且 `ORDER BY view_count` 該欄亦無索引。中間表缺反向索引是教科書級的 junction-table 漏洞 | `ArticleRecommendMapper.xml:20-30`、`ArticleTagRepository.java:61-62`、`UserTagFollowRepository.java:59-60`、`ai-docs/schema.md` §article_tags/§user_tag_follows | OPEN |
| PERF-08 | Medium | `articles.published_at` 無索引，但三個熱路徑都以它篩選或排序：`findPublishedAfter`（`TrendingRefreshJob` **每 30 分鐘呼叫 3 次**，24h/7d/30d，且 30d 本身就是另兩者的超集）、`findRecentPublished`、`findAllPublished`（archive + reindex） | `ArticleRecommendMapper.java:68-82,100-107`、`TrendingRefreshJob.java:59-63,74,105-107`、`ArticleMapper.java:149`、V1–V21 `CREATE INDEX` 全清單 | OPEN |
| PERF-09 | Medium | 公開列表缺 `(status, created_at DESC)` 複合索引（現況是兩個**分離**的單欄索引，planner 只能二選一），且每頁重算 `COUNT(*)`。深 OFFSET 時退化明顯 | `ArticleMapper.java:41,49-50,108,116-117`、`ArticleQuerySubService.java:61-62,92-93`、`ai-docs/schema.md` §articles | OPEN |
| PERF-10 | Medium | 5 張以 `ON DELETE CASCADE` 指向 `articles(id)` 的子表**沒有 `article_id` 前導索引**（`user_article_likes`/`user_bookmarks`/`user_highlights`/`user_reading_progress` 的唯一索引皆 user 前導；`comments` 只有 partial）→ 刪一篇文章觸發 5 次 seq scan。這五張正好是成長最快的互動表。做對的對照：`article_versions` 有 `(article_id, created_at DESC)` | `ai-docs/schema.md` 對應表 Indexes（與 V12–V16 交叉驗證）、`ArticleCommandSubService.java:215-237` | OPEN |
| PERF-11 | Medium | `comments.article_id` 沒有非-partial 索引 → 每次留言列表的 `countByArticle` 全表掃（`WHERE article_id=? AND (parent_id IS NULL OR deleted_at IS NULL)` 的 OR 分支中，`deleted_at IS NULL` 那半無索引可用）。同一次請求裡另外兩條查詢都能命中 partial 索引，唯獨這條不行 | `CommentMapper.java:81-83`、`CommentService.java:250`、`ai-docs/schema.md` §comments | OPEN |
| PERF-12 | Medium | ES `search()` 未做 `_source` 過濾，**整篇 `content` 隨每筆 hit 回傳後被丟棄**（`toSearchResult` 完全沒用到它），隨每頁筆數線性放大；另 `from+size` 超過 `index.max_result_window`（預設 10000）會直接拋例外 → 500 | `SearchServiceImpl.java:110-123,299-313`、`ArticleDocument.java:58-59`、`:112` | OPEN |
| PERF-15 | Medium | 連線池 / prefetch / concurrency **全無設定**（`Prefetch\|Concurrent\|hikari\|pool-size` 於全 repo 只命中一個無關的測試方法名），且 `rabbitListenerContainerFactory` 從零手建、**未經 `SimpleRabbitListenerContainerFactoryConfigurer`** ⇒ `spring.rabbitmq.listener.simple.*` 一律**靜默失效**（yaml 的 `acknowledge-mode: manual` 之所以有效，是因為程式碼自己又設了一次）。實際生效的是 Hikari `max-pool-size=10`、`concurrentConsumers=1`、`prefetchCount=250`。**最危險的是第二點**：未來有人加 `prefetch: 10` 或 `concurrency: 4` 會「設了沒反應」且無錯誤訊息 | `application*.yaml`、`RabbitMqConfig.java:141-152`（`:149`） | OPEN |
| PERF-16 | Medium | 排程執行緒池為預設**單執行緒**（無 `TaskSchedulerCustomizer` / `ThreadPoolTaskScheduler` / `spring.task.scheduling.pool.size`），3 個 job 串行；其中最重的 `TrendingRefreshJob` 一次跑 3 條全表掃並持有 120s 鎖，它一慢，兩個 flush job 整批延後（放大 DATA-09 的遺失視窗） | `BlogWebV2Application.java:15`、`TrendingRefreshJob.java:54,74,77-78`、`ViewCountFlushJob.java:33`、`ReadingProgressFlushJob.java:34` | OPEN |
| PERF-17 | Medium | `TagServiceImpl.getHotTags()` **快取命中路徑仍是 N 次 `findById`**（各一條完整 `SELECT * FROM tags`），回填路徑也是逐筆 ZADD。熱門標籤是多頁面共用的公開元件，「命中快取」卻仍發 N 條 SQL——快取只省下一次 `ORDER BY`，沒省下任何 round-trip | `TagServiceImpl.java:85-106`（`:90,93-98,103-105`） | OPEN |
| PERF-18 | Medium | `tag:hot` 過期後被 `ZINCRBY` 以**無 TTL 的殘缺狀態重建**：Redis `ZINCRBY` 對不存在的 key 會建立新 key 且不帶 TTL ⇒ 只要 1h TTL 到期後、下一次 `getHotTags()` 之前有任何文章被打標籤，ZSet 就以「只有那一個 tag、score=1」重生且**永不過期**，`getHotTags` 從此永遠走 cache-hit、回傳錯誤榜單、再也不從 DB 重建（PLAUSIBLE：程式路徑已確認，Redis 行為未實測）。另 `tag:detail:*` 的 24h 快取內含 `usageCount` 卻不隨 `usage_count` 更新失效 | `TagServiceImpl.java:103-105,152-161,197,214-215`、`TagUsageConsumer.java:88-90` | OPEN（同源於 T3 與 backlog cache-rebuild）|
| PERF-19 | Medium | `processed_events` 只增不刪、**無任何 cleanup job**（主程式碼只有 INSERT，`DELETE` 僅出現在測試）。表隨事件量單調成長，UNIQUE 索引 `(event_id, consumer_name)` 隨之膨脹，每則事件的 dedup 檢查都得走越來越大的索引。目前 3/9 consumer 使用它，依 roadmap 工作包 C 鋪滿後成長速率乘以 3。索引 `idx_processed_events_processed_at` 已就緒（schema.md 註明「給未來 cleanup 用」） | `IdempotencyService.java:58-63`、`V17__add_processed_events.sql:12-13` | OPEN |
| PERF-20 | Medium | 每篇文章詳情都為了 6 個純量欄位**再撈一次整列 `SELECT *`**（`getSeriesNavigation` 無條件呼叫 → `findById`，不屬於任何 series 的文章也照付）；列表則無條件查 series，之後才逐筆判斷 `getSeriesPosition() != null`——判斷依據其實在呼叫前就已在記憶體 | `ArticleQueryService.java:226-227,275-280`、`SeriesFacadeImpl.java:41-47`、`ArticleFacadeImpl.java:294-296`、`ArticleData.java:29-36` | OPEN |
| PERF-21 | Medium | version 自動快照為了比長度而把**整份版本內容撈回 app**（`findLatestByArticleAndType` 拉回含 TEXT `content` 的整列，卻只用 `content.length()`），且同一份資料在一次事件內查兩遍（`shouldSnapshot` 與 `recordAutoSnapshot` 各查一次 article 與 config）。編輯器自動存檔期間，每次存檔付 3 條查詢，真要快照時再付 4 條（其中 2 條重複）。同專案 `VersionMapper.java:54` 已示範 `length(content) AS content_length` | `AutoSnapshotPolicy.java:31-52`、`VersioningService.java:80-97`、`ArticleVersionConsumer.java:47-52`、`ArticleCommandSubService.java:120,213` | OPEN |
| PERF-22 | Medium | 留言 replies 一次全撈、**無上限**（top-level 有分頁但 size 無上界；replies 完全沒有），且 SQL 對每一列跑相關子查詢取 `parent_uuid`。單一熱門討論串的所有回覆（含 `content` + `content_html` 兩個 TEXT）一次載入 ⇒ 單次回應可達數十 MB | `CommentService.java:219-221`、`CommentMapper.java:57-74`（`:60`） | OPEN |
| PERF-23 | Medium | `FileServiceImpl.bindToArticle` 在單一交易內逐檔 `findById` + `save`（N+1 寫）。一篇圖多的文章 ⇒ 2M+1 條 SQL 佔著同一條 Hikari 連線（池只有 10 條）。觸發於**每次文章 create/update** | `FileServiceImpl.java:341-388`（`:360-367,373-388`）、`ArticleCommandSubService.java:128,216` | OPEN |
| PERF-26 | Medium | `recommend:related:*` 無互斥鎖（cache stampede），且 **cache key 未含 `limit`** ⇒ 若首個填充快取的請求帶 `limit=1`，接下來 1 小時內所有 `limit=20` 的請求都只拿得到 1 筆且不會重算。miss 路徑要跑全站第二貴的讀路徑（`article_tags` 全表掃 + ES `more_like_this` + 無索引 `published_at`），卻只有 1h TTL 保護且掛在每個文章詳情頁上 | `RecommendServiceImpl.java:70,78-105`（`:79,86`）、`RecommendController.java:51` | OPEN（key 語意面關聯 DATA-13）|
| PERF-27 | Medium | 文章存檔時的關聯同步全部逐筆化：tags 刪光重插（`1 + M + K + K` 條 SQL 在同一交易內，無條件刪除重插製造無謂 dead tuple）、`findOrCreateTags` 逐個 `findBySlug`、`syncCategories` 迴圈內逐個 `findByUuid` + insert。落在寫入交易的關鍵區段內 | `TagFacadeImpl.java:37-45,48-57,60-63`、`TagNormalizationServiceImpl.java:61`、`ArticleCommandSubService.java:96-104,185-198,508-516` | OPEN（與 T2/T3 同一段程式，建議同批）|
| PERF-28 | Medium | `FileServiceImpl.deleteFile` 的 `@Transactional` 橫跨兩次 MinIO 網路呼叫 → MinIO 延遲/故障時 DB 交易與一條 Hikari 連線被佔住（client 無逾時設定 ⇒ 可能分鐘級）。同檔 `uploadFile` 已示範正確做法 | `FileServiceImpl.java:241,250-262` | OPEN（＝RACE-16，重新定性為 Medium）|
| PERF-29 | Medium | 列表讀取路徑無 `@Transactional(readOnly = true)` ⇒ 每條 statement 各自借還連線，且喪失 readOnly 提示與同一快照的一致性（全 repo 63 個 `@Transactional` 只有 13 個標 readOnly）。與 PERF-02/03 相乘：`size=100` 的登入列表約 208 次連線借還，池只有 10 條。**不要盲改**：`ArticleServiceImpl.java:47-50,64-67` 的 JavaDoc 明確說明詳情路徑刻意不開交易（緊接的 `recordView` 會送 MQ），只能加在純讀、不發 MQ 的列表方法上 | `ArticleQuerySubService.java:59-96,123-143,167-173`、`ArticleQueryService.java:60-110,202-261`、`ArticleServiceImpl.java:76-90` | OPEN |
| PERF-31 | Low | 兩個 flush job 逐筆發語句，且 `reading:dirty` 用 `SMEMBERS` 一次全取（無界）；每 entry 2 條 SQL，且 `findIdByUuid` 對同一篇文章的不同使用者是重複查詢。5 分鐘週期、目前規模影響小，屬擴展性備忘 | `ViewCountServiceImpl.java:93-121`（`:110`）、`ReadingProgressFlushJob.java:36,39-68` | OPEN（同一段程式的正確性問題見 DATA-08/RACE-13）|
| PERF-32 | Low | `JwtAuthenticationFilter` 每個已認證請求 2 次 HGET（可併為 1 次 `multiGet`），cache miss 時 3 次 `put` + 1 次 `expire`（可降為 `putAll` + `expire`）。此 filter 在**每一個**帶 token 的請求上執行 | `JwtAuthenticationFilter.java:60-61,80-84` | OPEN |
| PERF-33 | Low | `/api/v1/files/{id}/content` 每張圖多一條 `SELECT * FROM users`（`getFileMetadata` 同理）⇒ 登入使用者瀏覽含 10 張圖的文章 = 10 次完全相同的 user 查詢。端點其他部分做得很好（302 + presigned、單次 metadata 查詢供授權與簽名共用、`private, max-age=240`），註解甚至記錄了同型優化的動機——同一個道理還剩這一條沒收 | `FileController.java:126,161,278-284` | OPEN（隨 PERF-02 一併處理）|
| PERF-35 | Low(PLAUSIBLE) | ES 全文查詢對整篇 `content` 套 `fuzziness("AUTO")` ⇒ 對每個 term 展開編輯距離內的變體再比對，套在整篇正文的 Text 欄位上比只套 title/summary 昂貴得多。實際成本取決於語料與分詞器（IK 由 index template 套用，本 repo 看不到該設定）。**無 wildcard / 無 regex / 無 leading-`*`**，這點是好的 | `SearchServiceImpl.java:89-93` | OPEN |
| PERF-37 | Low | 自動快照把整篇 `content` 複製進 `article_versions`，`retain: 50` ⇒ 穩態下 `article_versions` 的體積 ≈ `articles.content_md` 的 **50 倍**（另加 MANUAL/PUBLISHED） | `application.yaml:116-121`、`VersioningService.java:80-97`、`ai-docs/schema.md` §article_versions | OPEN / NEEDS-DECISION（Q14；triage 判定「記錄不修」＋設磁碟告警）|
| PERF-38 | Low | `UserMapper` 兩個方法是 `SELECT *` + 前導萬用字元 `ILIKE` + 無上限（`users.role` 亦無索引），且**目前是死碼**（全 repo 含測試零呼叫端）。不構成現存風險，記錄為「若未來接上管理員使用者搜尋就會直接踩雷」 | `UserMapper.java:29-30,38-39` | OPEN（建議直接刪除）|

---

## 已驗證安全/一致（覆蓋佐證，摘要）

- **AUTH**：article CUD/read（checkWritePermission / checkReadPermission，非 PUBLISHED 回 NOT_FOUND 無列舉洩漏）、comment edit/delete ownership、reading 全模組複合鍵刪除、series attach 雙重檢查（不能掛他人文章）、version promote/delete/restore ownership + assertVersionBelongsToArticle、preference 僅吃 principal、user /me/* 無 target 參數、register 硬編 Role.USER、search history 綁 userId、search/recommend 全鏈路強制 PUBLISHED。
- **RACE**：article_likes/comment_likes/bookmarks/tag_follows UNIQUE 約束存在；unlike/unbookmark 以 affected rows 決定 decrement；article/comment/series 計數為原子 SQL 帶 underflow 守衛；tag follow 用 ON CONFLICT；IdempotencyService 用 ON CONFLICT + REQUIRES_NEW（at-most-once 為刻意）；文章 slug 隨機後綴 + UNIQUE；瀏覽去重 SETNX；ViewCount 用 GETDEL；MQ 皆 commit 後發送。
- **DATA**：article like_count/comment_count（單則層級）增減對稱；文章硬刪 DB 級聯完整（V12–V16 ON DELETE CASCADE）；檔案配額即時 SUM 無漂移；檔案上傳/刪除 MinIO↔DB 補償；trending 每 30 分自 DB 重建 + 讀取端過濾 PUBLISHED；tag/category 刪除前置防護；軟刪留言讀取過濾統一。
- **FILE**：Content-Type 走 Tika magic-byte 真實偵測（非採信 client 宣告）+ allowlist 僅 jpeg/png/webp/gif；無 SSRF（無遠端 URL 抓取）；上傳需 `FILE_UPLOAD`、刪除需擁有權/admin；DB↔MinIO 補償完整。
- **XSS**：後端 ArticleMarkdownRenderer / CommentMarkdownRenderer 皆 OWASP allowlist（strip on*、限 http/https），有測試佐證；留言顯示雙重淨化（OWASP + 前端 DOMPurify 預設嚴格）；搜尋高亮 strip 全標籤後只注入字面 `<mark>`；markdown `javascript:`/entity 混淆連結前後端皆擋。
- **FE**：listener/observer 全數 teardown（useCursor/useSearch/CodeMirror 等）；search/highlight/reading-progress 有 loadRequestId stale-guard；按讚 optimistic isPending 防雙擊；401 refresh 佇列穩健、access token memory-only。
- **TEST**：token-version/refresh 安全路徑覆蓋強（WP-A 兩個 fossil 已修正並有正向測試）；MyBatis SQL/約束/cascade 跑真 postgres Testcontainer（非 mock）；ownership 紀律、單列計數對稱、IdempotencyService+series consumer 皆有測；無 @Disabled/Thread.sleep/順序假設。

### 2026-09-04 第三輪複核結果（對上列斷言）

- **複核通過、且證據更強**：SQL Injection（全部 13 個 Mapper 與唯一的 XML 皆 `#{}` 綁定，`${}` 零命中）；ES 查詢注入與草稿洩漏（typed builder + 硬性 `status=PUBLISHED` filter）；IDOR 六路 owner 檢查；Cookie 屬性；`AccessDeniedException` 原樣重拋；JWT 演算法與金鑰（ES256、`verifyWith(publicKey)`、refresh token 有 `type` 檢查）；CORS 無萬用字元；Actuator 只暴露 health/info；程式碼內硬編秘密零命中；Transaction+MQ 時序（63 個 `@Transactional` 逐一比對，無違例，BUG-2026-001 FIN-2 未復發）；Maven 模組依賴圖無環；`blog-common` shared kernel 純度；對外 ID 全 UUID 化（24 個 response DTO 零 `Long id`）；`schema.md` 與 V1–V21 完全同步（20 條 `CREATE INDEX` 逐條對得上，可作為索引真相版本）。
- **撤銷時序的正面更新**：`SessionRevoker` 以 `TransactionSynchronization.afterCommit` 延遲清理（無交易時立即執行），三個呼叫點全走它——**優於未合併 commit `59c170d` 的 A1/A2 實作**（後者是 commit 前直接刪，正是 BUG-2026-002 的形狀）。
- **`59c170d` 四項比對**：A1/A2 **已被 develop 以更好的方式涵蓋**；A3 **部分涵蓋**（role 已有寫入端，但權威來源仍是快取 → SEC-03）；A4 **完全未涵蓋** → SEC-01。
- **斷言需要收窄的一項**：原文「token-version/refresh 安全路徑覆蓋強」——**只適用於 version 一環**。refresh 的權威來源、輪替與重用偵測皆無覆蓋（SEC-03）。
- **正面對照組（性能面已驗證做對）**：`enrich()` 的 liked/bookmarked/series 批次；`CommentMapper` 全套 JOIN + IN 批次 ＋ V13/V14 partial 複合索引（留言列表 5-6 條固定查詢，與筆數無關）；`VersionMapper.listSummaries` 明確選欄 + `length(content)`（全 repo 最佳投影範例）；`/files/{id}/content` 走 302 + presigned（圖片位元組不經應用伺服器）；`ViewCountServiceImpl` 用 SCAN 不用 KEYS、GETDEL 不用 GET+DEL；`TrendingRefreshJob` 的 ZSet 單次批次寫入 + `rename` 原子切換；recommend 模組的摘要組裝直接 JOIN `users`（與 PERF-02 形成同 repo 內的正反對照）。
- **架構面正面事實**：PR #54 的 article↔file 循環依賴**確認已真正打斷**（`ArticleLookupFacadeImpl` 依賴閉包只有 `ArticleRepository`，零 `@Lazy`、未開 `allow-circular-references`——靠重構而非規避）；main 原始碼跨模組 `import` 只有 12 處，且**沒有任何模組 import 他模組的 `repository` 或 entity `model`**；8 個模組的 queue 全部接上 DLQ，無孤兒 queue；`@RestControllerAdvice` 全 repo 唯一，無多重 advice 順序問題。

---

## 建議修復批次（依 ROI；實際排程待 Yuan 定）

> **施工鐵律（T6）**：DATA-01/02/03 修復前，必須先改 TEST-01/02/03（Red 定義正確行為）。否則固化測試會擋下修復。
> **2026-09-04 起，第三輪（SEC/ARCH/PERF）的排序與四象限取捨改由 `be-triage-2026-09-04.md` 主導**，本節維持第一/二輪的批次不變，兩者的交集已在該文件 §3 合併。第三輪額外的施工順序約束（不遵守會越修越糟）：
> - **PERF-15 → ARCH-02 + PERF-30 必須同一個 PR**：手刻 factory 使 yaml 失效，所以 concurrency 設了沒反應；而 retry 修活卻不設 concurrency 會把「不重試」換成 31 秒頭部阻塞。
> - **SEC-27 → SEC-09 → ARCH-03 守衛 #2 鏈式依賴**：守衛的白名單來源是 `security.md` Public Endpoints 表，該表現在與 `SecurityConfig` 對不上；先寫守衛只會把錯誤白名單固化成測試。
> - **SEC-01 → SEC-22**：XFF 未修前加 IP 層限流是負收益（攻擊者換一個 header 就繞過，共用出口 IP 的正常使用者反而被誤限）。
> - **ARCH-17 → ARCH-03**：`architecture.md` 沒有允許矩陣就寫不出可對照的 ArchUnit 規則。
> - **SEC-02 → 先改 TEST-03**（同 T6 鐵律）；**SEC-06 → 先加 TEST-09**；**PERF-27 → 先改 TEST-02/06**。

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
