# blog-web-v2 安全審查報告（develop @ f0e3cb1）

- **審查對象**：`origin/develop`（f0e3cb1，唯讀 worktree `.worktrees/review-develop`）
- **方法**：靜態分析（rg / git show / 讀檔）。未執行 Maven、未跑任何測試、未修改任何檔案。
- **基線**：`git show docs/audit-findings-registry:ai-docs/findings.md`（2026-07 稽核登記簿）
- **維度**：安全（認證授權 / Token 鏈 / 輸入面 / 輸出面 / IDOR / 限流 / 設定秘密 / 相依）

---

## 1. 摘要（≤10 行）

- 共 **28 條** finding：**CRITICAL 0 / HIGH 3 / MEDIUM 13 / LOW 12**。無「匿名直接取得他人資料」等可立即利用的認證繞過。
- **最該先修的三件事**：
  1. **SEC-01（HIGH）** `X-Forwarded-For` 全面被信任（`AuthController:170-176` 手動取最左節 + `application.yaml:2` `forward-headers-strategy: framework` 無信任代理層數）→ 登入/註冊 IP 限流與瀏覽去重**整層可繞過**，密碼噴灑無節流。這正是未合併 commit `59c170d` 的 A4（`ClientIpResolver`）**至今未被 develop 涵蓋**的那一項。
  2. **SEC-02（HIGH）** `VersioningService.restore` → `ArticleFacadeImpl.applyRestoreContent:427-429` 直接 `setStatus`，**不過 `validateStatusTransition`**。作者可把被 ADMIN 駁回（REJECTED）或送審中（PENDING_REVIEW）的文章，用一份舊的 PUBLISHED 快照直接還原成 PUBLISHED——**繞過「PENDING_REVIEW→PUBLISHED 僅 ADMIN」的明文守衛**。基線把 AUTH-01/DATA-03 重新定性為「純一致性破口」，此次判定**該重新定性不完整，確實存在授權繞過**。
  3. **SEC-03（HIGH）** `/refresh` 仍留在 Controller 且以 **Redis 快取**（非 DB）為權威（`AuthController:116-154`），無 refresh token 輪替、無重用偵測；加上角色變更沒有任何撤銷路徑（filter 的 role 直接取自 JWT claim），降權最長 7 天不生效。此為 `59c170d` A3 **未被涵蓋**的部分。
- `59c170d` 四項比對結論：**A1/A2 已被 develop 以更好的方式涵蓋**（`SessionRevoker` + `afterCommit`，優於原 commit）；**A3 部分涵蓋**（role 已有寫入端，降級為 USER 的缺陷已消失，但權威來源仍是快取）；**A4 完全未涵蓋**。
- 基線對照：AUTH-03 / AUTH-07 / FILE-01 / FILE-07 **已修**（附證據）；AUTH-01/02/04/05/06/08~11、FILE-02~06、XSS-03、DEP-01~05 及 DEP-流程 **仍開放**；XSS-01/02、DEP-06~17、FE-*、TEST-* 屬前端 repo 或非本維度，**不適用**。

---

## 2. Findings（按 severity 排序）

### HIGH

---

#### SEC-01（HIGH）｜`X-Forwarded-For` 無條件被信任 → 全部 IP 層限流可繞過
- **file:line**
  - `blog-module-user/src/main/java/dowob/xyz/blog/module/user/controller/AuthController.java:170-176`
  - `blog-start/src/main/resources/application.yaml:1-2`（`server.forward-headers-strategy: framework`）
  - `blog-module-article/src/main/java/dowob/xyz/blog/module/article/controller/ArticleController.java:305-319`（JavaDoc 宣稱「可避免手動解析 header 被偽造的安全風險」）
- **違反 / 情境**：`ai-docs/judgment.md §2`（危險模式：文件自稱安全不算數）。攻擊情境：每次請求帶不同的 `X-Forwarded-For: 1.2.3.<n>`，即可讓
  `RedisKeyConstant.getLoginIpKey(ip)`（LOGIN_IP_MAX=20/15min）與 `getRegisterIpKey(ip)`（10/60min）每次都落在新 key 上，限流形同不存在。
  帳號層鎖定（`login:fail:{userId}` 5 次/15 分）只擋「同一帳號」暴力破解，**擋不住把同一組密碼噴灑到上千個帳號**；配合 SEC-14 的註冊枚舉 oracle 即可先枚舉再噴灑。
  同一問題也讓 `ArticleViewSubService:28` 的瀏覽去重（`setIfAbsent`）失效 → 瀏覽數與 trending 排行可被任意灌水。
  **關鍵事實澄清**：`forward-headers-strategy: framework` 啟用的 `ForwardedHeaderFilter` **不做任何信任代理驗證**，任何 client 都能改寫 `getRemoteAddr()`。`ArticleController` 的 JavaDoc 說法為錯誤假設。
- **建議修法**：移植 `59c170d` 的 `ClientIpResolver`（rightmost-untrusted 演算法 + 可配置 `trustedProxyCount`），把 `AuthController.resolveClientIp` 與 `ArticleController.getClientIp` 統一改走它；`forward-headers-strategy` 改為 `native` 並在 ingress/nginx 層強制覆寫 XFF，或設定 Tomcat `RemoteIpValve` 的 `internalProxies`。
- **基線對應**：**新**（findings.md 未登記；對應未合併 commit `59c170d` 的 A4）。

---

#### SEC-02（HIGH）｜版本還原繞過文章狀態守衛（REJECTED / PENDING_REVIEW → PUBLISHED）
- **file:line**
  - `blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/VersioningService.java:256-296`（`restore`：只檢查版本 owner，未做狀態轉換檢查）
  - `blog-module-article/src/main/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImpl.java:427-429`（`if (data.status() != null) article.setStatus(...)`，無 `validateStatusTransition`）
  - 對照守衛：`blog-module-article/.../ArticleCommandSubService.java:445-456`（`VALID_TRANSITIONS` + 「PENDING_REVIEW→PUBLISHED/REJECTED 僅 ADMIN」）
  - 端點權限：`blog-module-version/.../VersionController.java:110-116`（僅 `@PreAuthorize("isAuthenticated()")`）
- **違反 / 情境**：`ai-docs/security.md` 原則 1（雙層保護）、原則 4（能力與擁有權分離；狀態轉換屬能力判斷）。
  攻擊路徑：AUTHOR 的文章曾為 PUBLISHED（自動快照因此帶 `status=PUBLISHED`）→ ADMIN 駁回成 REJECTED（或作者送審中 PENDING_REVIEW）→ 作者呼叫
  `POST /api/v1/articles/{a}/versions/{v}/restore` → `applyRestoreContent` 直接把 status 寫回 PUBLISHED。
  `VALID_TRANSITIONS` 明定 `REJECTED → {DRAFT}`、`PENDING_REVIEW → PUBLISHED` 需 ADMIN，兩條都被繞過。
  另注意：狀態守衛目前有**兩套互相分歧的真相**——`VALID_TRANSITIONS`（給 `updateArticle`）與 `submitForReview:346-348` 的 ad-hoc 判斷（允許 `REJECTED → PENDING_REVIEW`，但 map 裡沒有）。守衛未收斂到單一點正是本繞過成立的根因。
- **建議修法**：`applyRestoreContent` 不得接受任意 status——還原只還原內容，status 維持不變；若產品要求還原 status，必須把 `validateStatusTransition(from, to, operatorRole)` 提到共用位置（例如 `ArticleStatusPolicy`）並在 restore 路徑呼叫，同時把 operatorRole 一路傳下去。
- **基線對應**：對應 **AUTH-01 / DATA-03**，但**嚴重度需上調**：基線 Q1 定案後把它降級為「純一致性破口（非審核繞過）」，本次證據顯示 REJECTED/PENDING_REVIEW → PUBLISHED 的路徑確為授權繞過，該定性應修正。

---

#### SEC-03（HIGH）｜Refresh 鏈：以快取為權威、無輪替、無重用偵測；角色變更無撤銷路徑
- **file:line**
  - `blog-module-user/src/main/java/dowob/xyz/blog/module/user/controller/AuthController.java:116-154`
  - `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/security/JwtAuthenticationFilter.java:56`（`role` 直接取自 JWT claim，非任何權威來源）
  - `blog-module-user/src/main/java/dowob/xyz/blog/module/user/service/AuthService.java:220-236`（login 寫 auth hash）
- **違反 / 情境**：`ai-docs/security.md` 原則 5（Stateful JWT / 即時撤銷）、`ai-docs/architecture.md`（Controller 不做資料存取）。
  (a) `/refresh` 從 `user:auth:{id}` hash 讀 version/status/role 後直接簽發，**從不查 DB**。任何未經 `SessionRevoker` 的 DB 狀態變更（尤其**角色升/降級**——目前沒有任何端點會在改 role 時 bump tokenVersion）在快取 TTL（7 天）內完全不生效：被降權的帳號可持續刷出帶舊角色的 access token。
  (b) **refresh token 不輪替**：同一 token 7 天內可無限次換發 access token，且無重用偵測（reuse detection）。外洩後只能靠使用者主動改密碼救援。
  (c) 業務邏輯留在 Controller 且直接操作 `StringRedisTemplate`，與 `59c170d` A3 下移到 `AuthService.refreshAccessToken` 的方向相反，使「權威狀態」散在兩處。
- **建議修法**：把 refresh 下移到 `AuthService.refreshAccessToken`，以 `userRepository.findById` 的 DB 狀態（tokenVersion / role / status）為權威簽發並回填快取（即 `59c170d` 的實作）；同時實作 refresh token 輪替（簽發新 token、從 ZSet 移除舊 token），並在偵測到已移除的 token 再次被用時 `SessionRevoker.revokeAllSessions`。
- **基線對應**：**新**（對應 `59c170d` A3 未涵蓋的部分；findings.md「已驗證安全/一致」段落聲稱 token-version/refresh 覆蓋強，本次判定該結論僅適用於 version 一環）。

---

### MEDIUM

---

#### SEC-04（MEDIUM）｜公開端點分頁參數無上下界 → 匿名放大查詢 / 500
- **file:line**：`ArticleController.java:75-84,213-220`、`CommentController.java:71-80`、`SearchController.java:56-65`、`VersionController.java:57-66`、`BookmarkController.java:55-63`、`AdminArticleController.java:48-53`、`TagController.java:57-73`
- **違反 / 情境**：`ai-docs/security.md` Public Endpoints 表 2026-07-18 註（「`listPublic` 的 `size` 夾 [1,100] 防匿名放大查詢」）。該防護只做在 `SeriesService.java:48,62`（`MAX_PAGE_SIZE=100`），**其餘公開端點全部沒有**。
  `GET /api/v1/articles?size=1000000`、`GET /api/v1/search?size=100000` 匿名可打；`page=0` 使 `PageRequest.of(-1, size)` 直接拋 `IllegalArgumentException` → 500；`TagServiceImpl.java:106` 的 `subList(0, Math.min(limit, size))` 傳負 limit 也是 500。
- **建議修法**：抽一個共用 `PageParams.clamp(page, size, max)`（比照 `SeriesService`），在所有 Controller 入口套用；或改用 `@Min/@Max` + `@Validated`。
- **基線對應**：**新**。

---

#### SEC-05（MEDIUM）｜匿名可寫入全站「熱門搜尋詞」，並由公開端點放送給所有訪客
- **file:line**：`blog-module-search/src/main/java/dowob/xyz/blog/module/search/service/SearchServiceImpl.java:275-291`（`recordSearch` 無認證要求）、`:150-159`（`suggest` 讀同一把 ZSet）、`SearchController.java:56-65,77-81`（兩者皆 permitAll）
- **違反 / 情境**：未認證使用者呼叫 `GET /api/v1/search?q=<任意字串>` 即把 `q` 寫進全域 `search:hot` ZSet；重複呼叫可推高分數避免被 500 成員上限（`removeRange(key, 0, size-501)` 移除的是**最低分**）修剪掉。結果由公開的 `/api/v1/search/suggest` 回給所有訪客 → 內容注入 / 版面污損；若任何消費端（RSS、email digest、非 Vue 頁面）以 HTML 方式渲染建議詞即成 XSS。`q` 也沒有任何長度上限（無 `@Size`），單詞可極長。
- **建議修法**：`recordSearch` 只在 `userId != null` 時寫入熱門詞（或只收「有命中結果」的查詢）；對 `q` 加 `@Size(max=64)` 與字元白名單；`suggest` 回傳前再做一次長度/字元過濾。
- **基線對應**：**新**。

---

#### SEC-06（MEDIUM）｜`PENDING_VERIFICATION` 帳號取得完整寫入 authorities
- **file:line**：`JwtAuthenticationFilter.java:94`（狀態白名單含 `PENDING_VERIFICATION`）、`:101-103`（不分狀態發完整 `role.getPermissions()`）
- **違反 / 情境**：findings.md Q3 定案「未驗證信箱帳號不可寫入」。目前 `AuthService.login:210-212` 會擋下 PENDING 帳號登入，故**現況難以取得 token**（可利用性低），但 filter 這層的縱深防禦缺口仍在：任何未來新增的「註冊即發 token」「換信箱後轉 PENDING」路徑都會立刻變成可寫入。
- **建議修法**：filter 對 `PENDING_VERIFICATION` 只授予 `ROLE_*`，不加任何 `Permission`（或只加唯讀 permission）。
- **基線對應**：**AUTH-06 仍開放**（`JwtAuthenticationFilter.java:92` → 現為 `:94`）。

---

#### SEC-07（MEDIUM）｜comment 寫入/刪除/按讚只有 `isAuthenticated()`，未接回細粒度 Permission
- **file:line**：`CommentController.java:83,93,104`、`CommentLikeController.java:35,43`
- **違反 / 情境**：`ai-docs/security.md` 原則 1 + 原則 3（Permission 用於細粒度方法保護）。`Permission.COMMENT_WRITE` / `COMMENT_DELETE` 在 `Role.java:29-32` 已定義卻**零使用**——未來若新增「禁言」角色（去掉 COMMENT_WRITE）將完全無效。
- **建議修法**：改為 `@PreAuthorize("hasAuthority('COMMENT_WRITE')")` / `hasAuthority('COMMENT_DELETE')`；刪除端點的 ADMIN 刪他人留言由 service 層 `isAdmin` 分支處理（現況已有，`CommentService.java:181-186`）。
- **基線對應**：**AUTH-04 仍開放**。

---

#### SEC-08（MEDIUM）｜完全未設定任何 security headers（無 CSP、無明示 HSTS/Referrer-Policy）
- **file:line**：`SecurityConfig.java:68-122`（整段 `authorizeHttpRequests` 鏈中**沒有 `.headers(...)`**；grep `headers|contentSecurityPolicy|frameOptions` 於該檔零命中）
- **違反 / 情境**：findings.md T7「XSS 縱深防禦相乘弱點」。Spring Security 預設仍會送 `X-Content-Type-Options: nosniff` 與 `X-Frame-Options: DENY`，但 **CSP 為零**，前端 DOMPurify 一旦被繞過（基線 DEP-08 列了 12 條 bypass）就沒有第二道防線。
- **建議修法**：在 `SecurityConfig` 加 `.headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; img-src 'self' data: <minio-origin>; script-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'"))
  .referrerPolicy(...).httpStrictTransportSecurity(...))`；先以 `Content-Security-Policy-Report-Only` 上線觀察。
- **基線對應**：**XSS-03 仍開放**（證據更新為 `SecurityConfig.java:68-122`）。

---

#### SEC-09（MEDIUM）｜`GET /api/v1/articles/**` 的 permitAll 蓋掉 versions / highlights / progress 的 URL 層保護
- **file:line**：`SecurityConfig.java:86`（`.requestMatchers(HttpMethod.GET, "/api/v1/articles/**").permitAll()`）
  vs `VersionController.java:46`（`/api/v1/articles/{articleUuid}/versions`）、`HighlightController.java:50`、`ReadingProgressController.java:47`、`ArticleController.java:179`（`/{uuid}/edit`）
- **違反 / 情境**：`ai-docs/security.md` 原則 1「兩層缺一不可」。這四組端點目前**只靠方法層** `@PreAuthorize` 撐著（實測邏輯上仍 fail-closed，非現存漏洞），但 URL 層對它們等同 permitAll：任何人新增一個 `GET /api/v1/articles/{uuid}/xxx` 而忘記加 `@PreAuthorize`，就直接變成匿名可讀，且沒有任何 URL 層兜底。`SecurityConfigTest.java` 也沒有任何一條測試守住「versions 需認證」。
- **建議修法**：把 permitAll 收窄成明確清單（`GET /api/v1/articles`、`/archive`、`/slug/{slug}`、`/{uuid}`、`/{uuid}/comments`），其餘落入 `anyRequest().authenticated()`；同步更新 `security.md` Public Endpoints 表與 `SecurityConfigTest`。
- **基線對應**：**新**。

---

#### SEC-10（MEDIUM）｜個人資料 `website` / `avatarUrl` 無協定白名單 → 可存入 `javascript:` / `data:` URL
- **file:line**：`blog-module-user/.../dto/request/UpdateProfileRequest.java:36-51`（僅 `@Size`）、`UserService.java:84-99`（原樣 `setWebsite` / `setAvatarUrl`）
- **違反 / 情境**：後端 markdown 渲染器有嚴格協定白名單（`ArticleMarkdownRenderer.java:132,138` `allowUrlProtocols("http","https")`），但**個人資料 URL 完全沒有對等防線**。這些欄位會被跨模組 `LEFT JOIN users` 帶進留言/文章作者區塊（`architecture.md` 明示的 reference data JOIN），前端若以 `:href="user.website"` / `:src="user.avatarUrl"` 綁定，`javascript:`（Vue 不會過濾 `:href`）即成 stored XSS。
- **建議修法**：加自訂 `@SafeUrl`（只允許 `http`/`https`，或 `avatarUrl` 只允許本站 `/api/v1/files/{id}/content` 相對路徑）；`socialLinks` 亦應驗證為合法 JSON 並限制大小。
- **基線對應**：**新**。

---

#### SEC-11（MEDIUM）｜縮圖無最大像素防護 → decompression bomb 打爆 consumer
- **file:line**：`blog-module-file/.../consumer/ThumbnailConsumer.java:77-97`（`Thumbnails.of(inputStream).width(300)` 直接全解碼，前面只有 `contentType.startsWith("image/")` 檢查）
- **違反 / 情境**：5MB 內的 PNG 可壓出數十億像素；`FileServiceImpl.java:170-183` 上傳時只用 `ImageReader.getWidth/getHeight` 讀 header（不解碼），沒有據此拒絕。→ consumer OOM，MQ 重投遞後反覆 OOM。
- **建議修法**：上傳時就用已讀到的 `width * height` 設上限（如 ≤ 50M 像素）並回 `INVALID_FILE_TYPE`；consumer 端再加一次 `ImageIO` header 檢查作為縱深。
- **基線對應**：**FILE-04 仍開放**。

---

#### SEC-12（MEDIUM）｜`application.yaml` 內建可用的預設憑證（正式包也帶著）
- **file:line**：`blog-start/src/main/resources/application.yaml:14-15`（`postgres` / `password`）、`:22-23`（`guest` / `guest`）、`:74-75`（`minioadmin` / `minioadmin`）；`application-demo.yaml:12,18,38` 同樣
- **違反 / 情境**：這些是 `${ENV:default}` 形式的**可用**預設值，不是佔位符。生產環境若少注入任一環境變數，應用會**靜默**連上預設憑證（或以預設憑證連上真實服務），不會啟動失敗。ES 的 demo 檔至少用了 `__CHANGE_ME_...` 佔位符，其餘沒有。
- **建議修法**：正式路徑一律改成無預設（`${DATABASE_PASSWORD}`）讓缺值時 fail-fast；本機開發值移到 gitignored 的 `application-local.yaml`（`application-dev.yaml` 已是正確做法，可比照）。
- **基線對應**：**新**。

---

#### SEC-13（MEDIUM）｜`JWT_PRIVATE_KEY` 未設時靜默降級為每次啟動隨機金鑰，無 fail-fast
- **file:line**：`JwtService.java:50-51`（`@Value("${jwt.private-key:}")`）、`:74-85`（空值即 `KeyPairGenerator` 動態產生）；`application.yaml` **完全沒有 `jwt.private-key` 這一行**
- **違反 / 情境**：`ai-docs/security.md` 原則 6 說明此為刻意的 dev 行為，但**沒有任何 profile 守衛**。生產 K3s 若 Secret 掛載失敗/打錯名稱，每個 pod 各自產生金鑰 → 多副本間 token 互不認（間歇 401），且重啟即全站登出；問題會表現成「偶發登入失效」而非啟動失敗，極難診斷。
- **建議修法**：加 `@Profile("!dev & !test")` 的啟動檢查（或 `ApplicationRunner`）：非 dev profile 而 `jwt.private-key` 為空時直接 `throw`，讓部署階段就失敗。
- **基線對應**：**新**。

---

#### SEC-14（MEDIUM）｜註冊回三種不同的重複錯誤碼 → 帳號枚舉 oracle
- **file:line**：`AuthService.java:104-112`（`EMAIL_DUPLICATED` / `USERNAME_DUPLICATED` / `NICKNAME_DUPLICATED` 分別拋出）
- **違反 / 情境**：`forgotPassword` / `resendVerification` 都刻意「靜默成功」以防洩漏信箱是否註冊（`AuthService.java:337-341` JavaDoc 明示），但註冊端點把同一份資訊直接回吐。配合 SEC-01（IP 限流可繞）可大量枚舉，再接密碼噴灑。
- **建議修法**：至少 email 的重複改為統一錯誤訊息（或改成「若信箱可用會寄出驗證信」的靜默流程）；username/nickname 的即時可用性檢查可保留但獨立限流。
- **基線對應**：**新**。

---

#### SEC-15（MEDIUM）｜CI 無依賴弱點掃描，repo 無 dependabot
- **file:line**：`.github/workflows/ci.yml:1-60`（只有 `./mvnw verify` + 測試報告上傳）；`git ls-files .github` 只有 `workflows/ci.yml`（無 `dependabot.yml`）；root `pom.xml` 無 `dependency-check-maven` / `spotbugs` plugin
- **違反 / 情境**：基線 DEP 段共列出 17 條依賴 finding（前端多條 High），但**沒有任何自動守門**，同類問題只會靠人工稽核才被發現。
- **建議修法**：加 `.github/dependabot.yml`（maven + npm + github-actions）；CI 加一個非阻塞的 `dependency-check` / `mvn versions:display-dependency-updates` job，High 以上再升級為 gate。
- **基線對應**：**DEP-流程 仍開放**。

---

#### SEC-16（MEDIUM）｜草稿/未發布文章可被互動，並提供 200/404 存在性 oracle
- **file:line**：`blog-module-article/.../mapper/ArticleMapper.java:273-274`（`findIdByUuid` 的 SQL 完全不濾 status）；消費端 `CommentService.java:62,202`、`ArticleLikeController.java:62`、`HighlightService.java:34,54`、`ReadingProgressService.java:51,76`
- **違反 / 情境**：`ArticleQuerySubService.checkReadPermission:175-183` 對「讀文章」做得很嚴（非公開回 `ARTICLE_NOT_FOUND`，無列舉洩漏），但**互動路徑完全繞過它**：任何已認證使用者拿到草稿 UUID 就能留言/按讚/畫重點，並藉由 200 vs 404 確認該 UUID 是否存在。且會預先污染計數器。
- **建議修法**：`findIdByUuid` 增加 `AND status IN (<publicly visible>)`，或在 `ArticleFacade` 提供 `findPubliclyVisibleIdByUuid`，讓所有互動路徑走它。
- **基線對應**：**AUTH-05 / DATA-12 仍開放**。

---

### LOW

---

#### SEC-17（LOW）｜multipart 未設限：框架預設 1MB 與程式 5MB 檢查互相矛盾；且大小檢查在全量讀入之後
- **file:line**：`FileServiceImpl.java:130-145`（先 `file.getBytes()` 再比 `MAX_FILE_SIZE`）；`application.yaml` 全檔無 `spring.servlet.multipart.*`
- **情境**：實際生效的是框架預設 1MB → 1~5MB 的圖片會以框架例外被擋（錯誤語意不對），而程式碼的 5MB 分支永遠走不到；同時「先全量讀進記憶體再檢查大小」讓每個並發上傳都吃滿一份 heap。
- **建議修法**：明示 `spring.servlet.multipart.max-file-size: 5MB` / `max-request-size: 6MB` 與程式常數對齊；改用 `file.getSize()` 先擋再讀 bytes。
- **基線對應**：**FILE-05 仍開放**。

---

#### SEC-18（LOW）｜使用者可控副檔名直接進 MinIO object key 與 thumbnail `outputFormat`
- **file:line**：`FileServiceImpl.java:583-590`（`extractExtension`：取最後一個點之後全部、`toLowerCase()`、**無白名單、未濾 `/` 與 `..`、未濾 null byte**）、`:156-157`（`String.format("%s/%d/%02d/%02d/%s.%s", ..., ext)`）；`ThumbnailConsumer.java:74,87`（同一個 ext 餵給 `outputFormat`）
- **情境**：檔名 `a.png/../../avatars/x.jpg` 會讓 object key 帶上使用者可控的路徑片段。S3/MinIO 對 key 通常不做路徑正規化，故**目前判定為 PLAUSIBLE 而非確認可利用**；但這是零成本可封閉的攻擊面，且 `outputFormat` 收到未知字串會讓 consumer 直接拋例外進 DLQ。
- **建議修法**：`ext` 由 Tika 偵測出的 MIME 反查（`image/jpeg→jpg` 等）決定，完全不採信檔名。
- **基線對應**：**FILE-03 仍開放**。

---

#### SEC-19（LOW）｜圖片無 EXIF / GPS metadata 剝除
- **file:line**：`FileServiceImpl.java:160-167`（原樣 `putObject`，中間無任何 metadata 處理）
- **情境**：手機拍攝的文章插圖／頭像帶 GPS 座標，經 presigned URL 對外提供 → 洩漏拍攝地點。隱私問題而非權限問題。
- **建議修法**：上傳時以 Thumbnailator/ImageIO 重新編碼（順帶解掉 SEC-18 的 ext 問題），或用 metadata-extractor 顯式剝除 EXIF。
- **基線對應**：**FILE-06 仍開放**。

---

#### SEC-20（LOW）｜密碼重設 token 可累積且重設後不失效
- **file:line**：`AuthService.java:370-386`（`forgotPassword` 每次都 `save` 一筆新 `PASSWORD_RESET`，**不刪舊的**——對照 `resendVerification:432-436` 有 `deleteByUserIdAndType`）；`:495`（`resetPassword` 只 `delete(resetToken)` 這一筆）
- **情境**：每日 5 次上限內可同時持有 5 個有效 reset token（各 15 分鐘）。帳號救援情境下（`resetPassword` JavaDoc 自稱「帳號救援的核心安全保證」），攻擊者若先前已觸發過 forgot-password，受害者重設密碼後攻擊者手上的另一個 token 在剩餘 TTL 內**仍可再次重設密碼**。
- **建議修法**：`forgotPassword` 比照 `resendVerification` 先 `deleteByUserIdAndType(userId, "PASSWORD_RESET")`；`resetPassword` 成功後刪除該使用者所有 `PASSWORD_RESET` token。
- **基線對應**：**新**。

---

#### SEC-21（LOW）｜Swagger UI 與 `/v3/api-docs` 在所有環境 permitAll
- **file:line**：`SecurityConfig.java:78`；`blog-infrastructure/pom.xml:23-24`（`springdoc-openapi-starter-webmvc-ui` 為 compile scope，無 profile 隔離）
- **情境**：正式站匿名可取得完整 API 清單、參數與 DTO schema——包含 admin 端點路徑。非漏洞，但把偵查成本降到零。
- **建議修法**：正式 profile 設 `springdoc.api-docs.enabled: false` / `swagger-ui.enabled: false`，或把該兩條路徑改為 `hasRole("ADMIN")`。
- **基線對應**：**新**。

---

#### SEC-22（LOW）｜忘記密碼／重寄驗證信／驗證碼校驗只有 per-email 限流，無 IP 層
- **file:line**：`AuthService.java:344-362`（forgot：min/day 皆以 email 為 key）、`:404-421`（resend 同）、`:295-308`（verify-email-code 失敗計數以 email 為 key）；對照 login/register 有 IP 層（`:185-188`、`:98-102`）
- **情境**：攻擊者拿一份信箱清單，每個信箱各打 1 次/分鐘、5 次/天，即可對任意規模的收件人做郵件轟炸與「帳號是否存在」的側信道（雖然回應統一成功，但寄信行為本身可被受害者觀察到）。整體出信量無任何全域上限。
- **建議修法**：對這三個端點加上與 login 相同的 IP 層限流（修好 SEC-01 後才有意義），並加一個全域每小時出信量上限。
- **基線對應**：**新**。

---

#### SEC-23（LOW）｜除 auth 外所有寫入端點皆無限流
- **file:line**：`grep -rn "RateLimit|rate:|RATE_LIMIT" --include=*.java`（排除測試與 `AuthService`/`RedisKeyConstant`）→ **零命中**
- **情境**：已認證使用者可無限速建立留言、文章、上傳檔案（僅受配額限制）、打 highlight；匿名可無限速打 `/api/v1/search`（每次都進 ES 並寫 Redis，見 SEC-05）。
- **建議修法**：抽一個共用的 `@RateLimited` AOP（Redis INCR + TTL，比照 `checkIpRateLimit`），先套在 comment create、file upload、search 三處。
- **基線對應**：**新**。

---

#### SEC-24（LOW）｜兩個 admin 端點缺 `@Valid`，且其 DTO 完全無約束
- **file:line**：`AdminTagController.java:47-50`（`@RequestBody UpdateTagRequest`，無 `@Valid`）、`ArticleController.java:295-298`（`@RequestBody RejectArticleRequest`，無 `@Valid`）；`UpdateTagRequest.java:21,26,31`（`color`/`icon`/`description` 三個裸 `String`）、`RejectArticleRequest.java:21`（裸 `reason`）
- **情境**：`tag.color` 通常被前端綁進 `:style`，未驗證的字串即 CSS 注入面；`description` / `reason` 無長度上限。權限為 ADMIN，故影響有限。
- **建議修法**：補 `@Valid` + `@Size` + `color` 加 `@Pattern("^#[0-9a-fA-F]{6}$")`。
- **基線對應**：**新**。

---

#### SEC-25（LOW）｜輸入長度約束與 DB 欄位不一致（nickname / socialLinks / content）
- **file:line**：`RegisterRequest.java:48-50`（`nickname` 只有 `@NotBlank`，**無 `@Size`**）vs `ai-docs/schema.md:27`（`nickname VARCHAR(50) NOT NULL`）；`UpdateProfileRequest.java:44`（`socialLinks` 無任何約束）vs `schema.md:32`（`TEXT`）；`CreateArticleRequest`/`UpdateArticleRequest` 的 `content` 無 `@Size`
- **情境**：51 字元暱稱註冊 → DB 拋例外 → `GlobalExceptionHandler:243` 回 500 而非 400（可用來探測欄位邊界）；`socialLinks` / `content` 可寫入到請求大小上限為止的資料，屬資源濫用面。
- **建議修法**：`RegisterRequest.nickname` 補 `@Size(max=50)`（與 `UpdateProfileRequest.java:23` 對齊）；`socialLinks` 補 `@Size(max=2000)`；`content` 依產品定義補上限。
- **基線對應**：**新**（`content` 一項對應 `ai-docs/backlog/2026-07-25-toc-security-followup.md §2`）。

---

#### SEC-26（LOW）｜`/refresh` 在 Redis auth hash miss 時回 `ACCOUNT_SUSPENDED`
- **file:line**：`AuthController.java:136-145`（`status` 為 null 時 `!"ACTIVE".equals(null)` 成立 → 拋 `ACCOUNT_SUSPENDED`）
- **情境**：filter 在 hash miss 時會查 DB 回填（`JwtAuthenticationFilter.java:66-84`），refresh 卻沒有對等的 fallback。auth hash 被逐出或 TTL 到期時，正常使用者會收到「帳號已停權」——錯誤的安全訊號，也讓真正的停權事件難以從日誌區分。
- **建議修法**：隨 SEC-03 一併解決（改以 DB 為權威即自然消失）。
- **基線對應**：**新**。

---

#### SEC-27（LOW/Info）｜`security.md` Public Endpoints 表與 `SecurityConfig` 三處對不上
- **file:line**：
  1. `security.md` 原則 2 寫 `/api/admin/**`，實作是 `SecurityConfig.java:112` 的 `/api/v1/admin/**`；
  2. `SecurityConfig.java:75,79` 的 `/actuator/health/**`、`/actuator/info`、`/favicon.ico`、`/error` 未出現在 Public Endpoints 表；
  3. 表中 `GET /api/v1/files/**` 描述為「Public file access」，但 `SecurityConfig.java:88-100` 的註解與 `FileController.java:121-134` 已改為「permitAll 只代表可到達 Controller，授權在 `canRead`」——描述會誤導後續維護者。
- **違反**：`security.md` 表下方明文「此表必須與 `SecurityConfig.java` 的 `permitAll()` 規則一對一對得上」。
- **建議修法**：更新 `security.md` 三處；此為文件變更但屬 `judgment.md §5`「Public Endpoints 表的任何增減」範疇，**應由 Yuan 拍板**。
- **基線對應**：**新**。

---

#### SEC-28（LOW / verify）｜後端相依版本盤點
- **file:line**：`pom.xml:47`（`jjwt 0.12.3`）、`:49`（`tika 3.1.0`）、`:50`（`flexmark 0.64.8`）、`:52`（`owasp-java-html-sanitizer 20240325.1`）、`blog-infrastructure/pom.xml:105-108`（`okhttp 4.12.0` 硬編版本、未進 `dependencyManagement`）；`testcontainers` 各模組皆未指定版本、root 亦無 `testcontainers.version` property（靠 spring-boot-starter-parent 管理）
- **情境 / 判定**：
  - `tika-core 3.1.0`：基線 DEP-03 指出 CVE-2025-54988 位於 parsers 模組，本專案只用 `tika-core` 做 magic-byte 偵測 → **PLAUSIBLE 不受影響**，但仍建議升 3.2.2+。
  - `jjwt 0.12.3`：無已知 CVE，僅版本略舊 → **verify**。
  - `okhttp 4.12.0` 硬編在子模組：版本收斂風險（MinIO SDK 另有自己的傳遞依賴）→ 建議移進 root `dependencyManagement`。
  - Spring Boot 3.5.9 / Spring Security 6.5.x：**未在本次靜態審查中確認 CVE 狀態**（未跑 `dependency:tree`／未查 NVD），標 verify，由 SEC-15 的自動掃描補上。
- **基線對應**：**DEP-01 / DEP-02 / DEP-03 / DEP-04 / DEP-05 仍開放（verify）**。

---

## 3. 基線 findings.md 對照表（本維度逐筆判定）

### AUTH（授權 / IDOR）

| ID | 狀態 | 證據（develop） |
|----|------|----------------|
| AUTH-01 | **仍開放（且嚴重度需上調）** | `VersioningService.java:256-296` + `ArticleFacadeImpl.java:427-429`：restore 直接 `setStatus`，不過 `validateStatusTransition`。基線把它降級為「純一致性破口」的定性不完整——REJECTED/PENDING_REVIEW → PUBLISHED 為真實授權繞過。見 SEC-02 |
| AUTH-02 | **已收斂（WONTFIX 部分成立）** | `ArticleCommandSubService.java:56-61` `VALID_TRANSITIONS` + `:445-456` `validateStatusTransition` 已限制 `UpdateArticleRequest.status` 為合法轉換值，且 PENDING_REVIEW→PUBLISHED 強制 ADMIN。**但同一守衛未涵蓋 restore 路徑（AUTH-01）** |
| AUTH-03 | **已修** | `SeriesService.java:274-277`：`getSeriesDetail` 以 `.filter(a -> ArticleStatus.isPubliclyVisible(a.status()))` 過濾；`:60-62` size 夾 [1,100] |
| AUTH-04 | **仍開放** | `CommentController.java:83,93,104`、`CommentLikeController.java:35,43` 仍為 `isAuthenticated()`；`Role.java:29-32` 的 `COMMENT_WRITE/DELETE` 全 repo 零使用。見 SEC-07 |
| AUTH-05 | **仍開放** | `ArticleMapper.java:273-274` `findIdByUuid` 的 SQL 無 status 條件。見 SEC-16 |
| AUTH-06 | **仍開放** | `JwtAuthenticationFilter.java:94` 仍把 `PENDING_VERIFICATION` 納入放行狀態，`:101-103` 不分狀態發完整 permission。見 SEC-06 |
| AUTH-07 | **已修** | `FileMetadata.java:59-61` `storagePath` 已標 `@JsonIgnore`；`FileController.java:121-134` `getFileMetadata` 已套 `canRead` 授權矩陣、無權回 403 |
| AUTH-08 | **仍開放** | `BookmarkController.java:35-52` 仍直接把 `articleUuid` 交給 service，未對 `findIdByUuid` 回傳 null 做前置檢查（與 AUTH-05 同源） |
| AUTH-09 | **仍開放** | `HighlightService.java:34,54` 仍先 `findIdByUuid` 再走 owner 檢查，「不存在」與「屬他人」錯誤碼不同 |
| AUTH-10 | **仍開放（Info）** | `AdminTagController.java:46,60`、`AdminArticleController.java:48`、`AdminCategoryController.java:45,58,72`、`AdminSearchController.java:44,61` 皆用 `hasAuthority('SYSTEM_CONFIG')`；`VersionController.java:58,76,93,111,128,145` 全用 `isAuthenticated()` 靠 service 兜。命名不一致，但因 URL 層 `/api/v1/admin/** → hasRole('ADMIN')`（`SecurityConfig.java:112`）雙層仍成立 |
| AUTH-11 | **仍開放（Info）** | `CommentService.java:181-186` admin 刪他人留言只在 `role` 字串上區分，未記 operator id；`FileController.java:226-233` 仍收 `Pageable` |

### FILE（檔案上傳安全）

| ID | 狀態 | 證據（develop） |
|----|------|----------------|
| FILE-01 | **已修** | 同 AUTH-07：`FileController.java:126-132` 先取 metadata 再 `canRead`，403；`FileMetadata.java:59-61` `storagePath` `@JsonIgnore` |
| FILE-02 | **大幅緩解，殘留 LOW** | `FileServiceImpl.java:491-505` 已改為 5 分鐘 presigned URL、`FileController.java:188-196` 302 導向 + `Cache-Control: private`；配合 Tika magic-byte + allowlist（`application.yaml:84-88` 僅 4 種 image/*），svg/html 無法上傳。**殘留**：MinIO 直出回應仍無 `X-Content-Type-Options`／`Content-Disposition`（應用層無法插手） |
| FILE-03 | **仍開放** | `FileServiceImpl.java:583-590` `extractExtension` 無白名單、未濾 `..`/`/`；`ThumbnailConsumer.java:74,87` 同樣採信。見 SEC-18 |
| FILE-04 | **仍開放** | `ThumbnailConsumer.java:84-88` 無像素上限。見 SEC-11 |
| FILE-05 | **仍開放** | `FileServiceImpl.java:130-145` 先讀後檢；`application.yaml` 無 `spring.servlet.multipart.*`。見 SEC-17 |
| FILE-06 | **仍開放** | `FileServiceImpl.java:160-167` 無 EXIF 處理。見 SEC-19 |
| FILE-07 | **已修** | 不再回傳裸 object URL：`FileServiceImpl.java:225-227` 回相對路徑 `/api/v1/files/{id}/content`；`MinioConfig.java:83-100` `ensureBucket` **不設任何 public-read policy**（bucket 維持私有），內容一律走 presigned |

### XSS（內容淨化）

| ID | 狀態 | 證據（develop） |
|----|------|----------------|
| XSS-01 | **不適用** | `front-end/composables/useMarkdownRenderer.ts` 屬前端 repo（`D:\end\workspace\vue\blog-web-v2-front-end`），不在本 repo |
| XSS-02 | **不適用** | 同上 |
| XSS-03 | **仍開放（後端側）** | `SecurityConfig.java:68-122` 整段無 `.headers(...)`，CSP 為零。見 SEC-08 |
| （後端 sanitizer 佐證） | **維持良好** | `ArticleMarkdownRenderer.java:120-142` OWASP allowlist、`:132,138` 只放行 http/https、`:124` heading id 以 `^heading-[\p{L}\p{N}-]{1,64}$` 收斂；`CommentMarkdownRenderer` 同型 |

### DEP（相依健康）

| ID | 狀態 | 證據（develop） |
|----|------|----------------|
| DEP-01 | **仍開放** | root `pom.xml` 無 `testcontainers.version` property（各模組不指定版本，靠 parent 管理） |
| DEP-02 | **仍開放** | `blog-infrastructure/pom.xml:105-108` `okhttp 4.12.0` 硬編於子模組 |
| DEP-03 | **仍開放（verify）** | `pom.xml:49` `tika 3.1.0`；本專案僅用 `tika-core` 做 MIME 偵測（`FileServiceImpl.java:28,102`），PLAUSIBLE 不受 CVE-2025-54988 影響 |
| DEP-04 | **仍開放（verify）** | `pom.xml:47` `jjwt 0.12.3` |
| DEP-05 | **仍開放（verify）** | `pom.xml:38` Spring Boot 3.5.9（Spring Security 由 parent 帶入，未在本次確認） |
| DEP-06~17 | **不適用** | 全數為前端 `package.json` / `package-lock.json`，不在本 repo |
| DEP-流程 | **仍開放** | `.github/` 只有 `workflows/ci.yml`；無 dependabot、無 audit gate。見 SEC-15 |

### FE-* / TEST-* / RACE-* / DATA-*
**不適用本維度**（FE-* 為前端 repo；TEST-*、RACE-*、DATA-* 由測試品質／併發／資料一致性維度負責）。
唯一交叉點已記於 SEC-02（AUTH-01=DATA-03 的授權面）與 SEC-16（AUTH-05=DATA-12）。

---

## 4. 檢查過但無 finding 的區域（方法與結論）

| 區域 | 方法 | 結論 |
|------|------|------|
| Controller ↔ SecurityConfig ↔ security.md 三方對照 | `git ls-files "*Controller.java"` 列出 21 個 Controller，逐檔 grep `@RequestMapping/@*Mapping/@PreAuthorize/@AuthenticationPrincipal`，與 `SecurityConfig.java:73-115` 及 `security.md` Public Endpoints 表逐條比對 | 無「JavaDoc 自稱公開但 SecurityConfig 沒有對應 permitAll」的情形。URL 規則順序正確（`/api/v1/admin/**` 在 `anyRequest()` 之前，且與各 GET permitAll 路徑無交集）。唯二問題是 SEC-09（articles/** 過寬）與 SEC-27（文件漂移） |
| `@AuthenticationPrincipal` 無 `@PreAuthorize`（security.md 原則 7） | 逐檔核對 8 個命中方法的三項豁免條件 | **全部合規**：`ArticleController:111-115,129-133`、`CommentController:71-79`、`FileController:121-134,155-160`、`SearchController:56-65`、`SeriesController:83-88`、`TagController:106-114` —— 皆滿足「SecurityConfig 明確 permitAll ＋ 參數可為 null 且僅用於個人化 ＋ JavaDoc 標註公開/匿名」 |
| admin 端點是否全部受 ADMIN 保護 | 4 個 `Admin*Controller` 逐一檢查 | 全部落在 `/api/v1/admin/**`（URL 層 `hasRole("ADMIN")`）＋ 方法層 `hasAuthority('SYSTEM_CONFIG')`（僅 ADMIN 擁有，`Role.java:50` `EnumSet.allOf`）。雙層成立 |
| 憑證進 URL（security.md 原則 8） | `grep -rniE "@RequestParam[^)]*(password\|token\|secret\|code)"` 排除測試 | **零命中**。`UserController.java:108-113` `deleteAccount` 已改用 `@Valid @RequestBody DeleteAccountRequest`；`AuthController.java:221-225` `verifyEmail` 已改 POST + body。BUG-2026-001 U-1 確認已修 |
| `AccessDeniedException` 重拋（security.md 原則 9） | 讀 `GlobalExceptionHandler.java:120-122` | ✓ `throw e;` 原樣重拋；catch-all（`:243-248`）只回固定「系統內部錯誤」，不洩漏 stack trace；型別轉換/JSON 解析錯誤（`:166-180`）皆回通用訊息 |
| 撤銷時序（security.md 原則 10） | 讀 `SessionRevoker.java:47-68` 與三個呼叫點 | ✓ `changePassword`（`UserService.java:152`）、`deleteAccount`（`:176`）、`resetPassword`（`AuthService.java:493`）皆走 `SessionRevoker`，內部以 `TransactionSynchronization.afterCommit` 延遲清理，無交易時立即執行。**優於未合併 commit 59c170d 的 A1/A2 實作**（後者是 commit 前直接刪，正是 BUG-2026-002 的形狀） |
| JWT 演算法與金鑰 | 讀 `JwtService.java:74-124,262-302` | ✓ ES256、公鑰由私鑰推導、驗證用 `verifyWith(publicKey)`、`validateRefreshToken` 有 `type=="refresh"` 檢查 |
| refresh token 當 access token 使用 | 追 `JwtAuthenticationFilter.java:52-56` → `Role.fromRoleName(null)`（`Role.java:102-104` 對 null 拋 `BusinessException`）→ `:115-118` catch → 不設 authentication | ✓ **fail-closed**。但 filter 本身**沒有** `type=="access"` 檢查，是靠 role/version claim 缺失間接擋下（縱深不足，已在 SEC-03 建議中一併提出補 type 檢查） |
| Cookie 屬性 | `AuthController.java:95-101,196-202` | ✓ `httpOnly(true)` + `secure(${app.cookie.secure:true})` + `sameSite("Strict")` + `path("/api/v1/auth")`；logout 以 `maxAge(0)` 清除。CSRF 停用在此配置下不構成問題（Bearer header + SameSite=Strict cookie） |
| SQL Injection | `grep -rn '\${'` 於所有 `*.java` / `*.xml`（排除 target），過濾 `@Value`/pom/log4j2 | **零命中 MyBatis 字串插值**。所有 mapper 皆用 `#{}` 參數綁定；唯一 mapper XML（`ArticleRecommendMapper.xml`）亦然。無動態 ORDER BY 拼接（`SearchServiceImpl.java:113-119` 的 sort 是白名單 if/else） |
| ES 查詢注入與未發布內容洩漏 | 讀 `SearchServiceImpl.java:83-135` | ✓ 全程 typed builder（`BoolQuery.Builder` / `Query.of`），無字串拼接；`:96-99` 硬性 `filter(term(status=PUBLISHED))`，草稿不會出現在搜尋結果 |
| IDOR：article / version / series / comment / file | 逐一讀 owner 檢查 | ✓ `ArticleQuerySubService.checkReadPermission:175-183`（非公開回 NOT_FOUND，無列舉洩漏）、`getArticleForEdit:51-57`（嚴格作者）、`ArticleCommandSubService.checkWritePermission:406-415` / `checkAuthorOnly:427-431`；`VersioningService` 六處 owner 檢查（`:195,221,263,319,345,367`）＋ `assertVersionBelongsToArticle:387`；`SeriesService:136,160,187,196,225`（addArticle 雙重檢查，不能掛他人文章）；`CommentService:136,181`；`FileServiceImpl.canRead:454-470` + `resolveOwnedArticleUuid:416-426`（上傳時宣稱他人文章一律存 null，fail-safe）。**本次未發現新的 IDOR** |
| 角色/權限對照表 | 讀 `Role.java:24-52` | ✓ 與 `security.md` §3 表格完全一致（USER 2 項 / AUTHOR 7 項 / ADMIN `allOf`）；`SecurityUtils.java:29-70` 對 null / `AnonymousAuthenticationToken` / 未認證一律回 null|false，無提權路徑 |
| CORS | `SecurityConfig.java:174-184` | 無萬用字元來源（`allowedOrigins` 由 `app.cors.allowed-origins` 注入，預設僅 localhost）；`allowCredentials(true)` 配具名來源合法。`setAllowedHeaders("*")` 偏寬但非漏洞 |
| Actuator 暴露面 | `application.yaml:90-100` + `SecurityConfig.java:75` | ✓ 僅 `health,info` 暴露、`show-details: never`；permitAll 範圍與暴露清單一致，無 `/actuator/env`、`/actuator/heapdump` 等 |
| 程式碼內硬編秘密 | `grep -rniE "(password\|secret\|apikey\|token)\s*=\s*\"[A-Za-z0-9+/=_-]{8,}\""` 排除測試 | **零命中**（Java 原始碼乾淨；問題只在 yaml 的 `${ENV:default}` 預設值，見 SEC-12） |
| 驗證碼與 token 熵 | `AuthService.java:551-553`（`SecureRandom` 6 位碼）、`:126,373,430`（`UUID.randomUUID()`） | ✓ 驗證碼用 `SecureRandom`；email/reset token 為 UUIDv4（122 bit）。6 位碼熵低但有 `EMAIL_VERIFY_CODE_MAX_ATTEMPTS=5` + 10 分鐘 TTL + 重寄限流兜底，判定可接受 |
| 帳號枚舉（登入/忘記密碼路徑） | `AuthService.java:190-193`（email/username 都查不到一律 `USER_PASSWORD_ERROR`）、`:337-341`、`:404-408`、`:315-318` | ✓ 登入、忘記密碼、重寄驗證信、驗證碼校驗四處皆已統一回應。**唯一破口在註冊端點**（SEC-14） |

---

## 5. 交付備註

- 本報告全程唯讀：未修改 worktree 內任何檔案、未執行 Maven、未派 subagent。
- 標記 **PLAUSIBLE** 者：SEC-18（MinIO object key 路徑片段是否可被利用取決於 MinIO 對 key 的正規化行為，未實測）、SEC-28 的 Spring Boot / Spring Security CVE 狀態（未跑 `dependency:tree`、未查 NVD）。
- **需 Yuan 拍板（`judgment.md §5`）**：SEC-09（收窄 `GET /api/v1/articles/**` permitAll，牽動前端）、SEC-27（`security.md` Public Endpoints 表修訂）、SEC-02 的修法方向（restore 是否應完全不還原 status——屬產品語意決定）。
