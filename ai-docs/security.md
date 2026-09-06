# Security & Authorization Rules

## Core Principles

### 1. Two Layers of Protection — Both Are Required

| Layer | Mechanism | Granularity |
|-------|-----------|-------------|
| URL layer | `HttpSecurity.requestMatchers()` | Coarse-grained, by URL pattern |
| Method layer | `@PreAuthorize` | Fine-grained, by Permission / Role |

The URL layer protects `/api/v1/admin/**` (ADMIN only). The method layer uses `hasAuthority('XXX')` to guard specific operations.
**Both layers must be applied. Never rely on only one.**

### 2. URL Rule Order Must Not Be Reversed

In `SecurityConfig`, **more specific rules must come before `anyRequest()`**:

```
/api/v1/admin/**  → hasRole("ADMIN")    ← must be first
anyRequest()      → authenticated()     ← must be last
```

If the order is wrong, `anyRequest()` matches first and shadows the admin rule, creating a security hole.

### 3. Role vs Fine-Grained Permission

*   **Role** (`ROLE_XXX`) is used for coarse-grained URL protection (e.g., `/api/v1/admin/**`).
*   **Permission** (e.g., `ARTICLE_CREATE`) is used for fine-grained method protection (e.g., `@PreAuthorize`).
*   The static mapping between roles and permissions is defined in the `Role` enum — nowhere else.

**Permission boundaries per role:**

| Role | Spring Role | Permissions |
|------|-------------|-------------|
| USER | ROLE_USER | COMMENT_WRITE, COMMENT_DELETE |
| AUTHOR | ROLE_AUTHOR | All USER permissions + ARTICLE_CREATE, ARTICLE_EDIT, ARTICLE_DELETE, ARTICLE_PIN, FILE_UPLOAD |
| ADMIN | ROLE_ADMIN | All permissions (`EnumSet.allOf`) |

### 4. Capability vs Ownership Separation

*   `@PreAuthorize("hasAuthority('ARTICLE_EDIT')")` only checks whether the caller *can* edit articles.
*   Whether *this article belongs to the caller* is determined in the **Service layer**. ADMIN automatically bypasses ownership checks.
*   **Never perform ownership checks in the Controller layer.**

### 5. Stateful JWT

Every Access Token carries a `version` claim. Redis key `user:auth:{userId}` stores the current valid version.

**Filter validation flow:**
1. Extract `Authorization: Bearer <jwt>` header.
2. Verify JWT signature.
3. Extract `userId`, `tokenVersion`, `role`.
4. Look up Redis `user:auth:{userId}` for version and status; fall back to DB on cache miss and backfill.
5. `tokenVersion` must **exactly match** the Redis version (`Objects.equals`).
6. User status must be `ACTIVE` or `PENDING_VERIFICATION`.
7. Build `Authentication` with principal = `userId (Long)`.

**Instant logout**: update the token version in Redis — existing tokens become invalid immediately without waiting for JWT expiry.

### 6. Key Management

*   JWT uses **ECDSA ES256** (never RSA).
*   The private key is stored as a **PKCS8 PEM** string in a K8s Secret, injected via the `JWT_PRIVATE_KEY` environment variable.
*   When `JWT_PRIVATE_KEY` is not set (local / dev), `JwtService` generates a fresh key pair on startup (tokens invalidated on restart).
*   **The public key is derived from the private key** — no need to store it separately.

### 7. @AuthenticationPrincipal ⇒ @PreAuthorize (CRITICAL)

> 來源:BUG-2026-001(FIN-3+4):`UserController` 3 個端點 + `AuthController.logout()` 缺 `@PreAuthorize`,違反雙層保護。

*   任何使用 `@AuthenticationPrincipal` 的端點,**必須**同時標註 `@PreAuthorize`(至少 `isAuthenticated()`,有細粒度權限時用 `hasAuthority('XXX')`)。
*   URL 層(`SecurityConfig`)已涵蓋不是豁免理由——雙層保護缺一不可(見原則 1)。
*   **唯一豁免:選填認證的公開端點**(匿名可看、登入者看到個人化資訊,如「已按讚」標記)。豁免必須**三件同時成立**,缺一即違規:
    1. 該路徑在 `SecurityConfig` 明確 `permitAll()`(對得上 Public Endpoints 表);
    2. `@AuthenticationPrincipal` 參數允許為 `null` 且只用於選填個人化;
    3. 方法 JavaDoc 明確標註「匿名可存取」。
*   Review 檢查法:`grep -rl "@AuthenticationPrincipal" --include="*Controller.java"` 逐檔確認每個方法「有 `@PreAuthorize`」**或**「完整符合上述三條豁免」;豁免判定必須交叉核對 `SecurityConfig`,不能只看 JavaDoc 自稱公開。

### 8. 憑證絕不進 URL (CRITICAL)

> 來源:BUG-2026-001(U-1):`deleteAccount()` 用 `@RequestParam String password`,密碼進入 URL query string,被 access log 與瀏覽器歷史記錄。

*   密碼、token、任何憑證**必須**放 `@RequestBody`(配 DTO + `@Valid`),**禁止** `@RequestParam` / path variable / query string。
*   DELETE 請求需要憑證時一樣用 request body,不因 HTTP 動詞妥協。

### 9. AccessDeniedException Must Be Re-thrown

In `GlobalExceptionHandler`, `AccessDeniedException` **must be re-thrown** — it must not be swallowed by a catch-all handler (which would return HTTP 200 instead of 403):

```java
@ExceptionHandler(AccessDeniedException.class)
public void handleAccessDeniedException(AccessDeniedException e) throws AccessDeniedException {
    throw e;  // Let Spring Security return HTTP 403
}
```

### 10. Session/快取撤銷必須在交易提交後執行 (CRITICAL)

Redis 不參與 Spring 交易。若在 `@Transactional` 內、commit 前就清除 session/快取鍵，併發請求可能在
提交前因 cache miss 回填「尚未提交的舊狀態」，使交易後的清理被覆蓋 —— 撤銷靜默失效（見
[BUG-2026-002](bug-reports/2026-07-18-session-revocation-timing-refresh-role.md)）。撤銷這類非交易資源
必須掛在 `TransactionSynchronization.afterCommit`，保證併發回填讀到的必為已提交的新版本。

✅ 正確：於交易同步進行中時延遲至 `afterCommit`，無交易時立即執行

```java
if (TransactionSynchronizationManager.isSynchronizationActive()) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            doRevoke(userId);
        }
    });
} else {
    doRevoke(userId);
}
```

❌ 禁止：在 `@Transactional` 方法內、commit 前直接清除撤銷鍵

```java
@Transactional
public void changePassword(...) {
    userRepository.save(user);
    redisTemplate.delete(authKey); // 交易尚未提交，併發回填會覆蓋此刪除
}
```

---

## Public Endpoints (No Authentication Required)

> 下表**依 `SecurityConfig` 中的宣告順序排列**,每一列對應一條 `permitAll()`。

| Method | Path | Description |
|--------|------|-------------|
| ANY | `/actuator/health/**`, `/actuator/info` | K3s liveness/readiness probe 與服務資訊 |
| ANY | `/swagger-ui/**`, `/v3/api-docs/**`, `/swagger-ui.html` | API documentation |
| ANY | `/favicon.ico`, `/error` | favicon 與 Servlet 容器的錯誤轉發路徑 |
| ANY | `/api/v1/auth/**` | Login, registration, refresh 等。**例外**:`POST /api/v1/auth/logout` 已在上一條規則設為 `authenticated()` |
| GET | `/api/v1/articles` | 已發布文章列表 |
| GET | `/api/v1/articles/archive` | 文章封存(依年月彙整) |
| GET | `/api/v1/articles/slug/{slug}` | 依 slug 取已發布文章 |
| GET | `/api/v1/articles/{uuid}` | 依 uuid 取文章(`{uuid}` 以 UUID 形狀比對,非 UUID 的字面量子路徑不落在此規則內) |
| GET | `/api/v1/articles/{uuid}/comments` | 文章留言列表(原則 7 豁免的匿名可讀端點) |
| GET | `/api/v1/tags/**` | Tag queries |
| GET | `/api/v1/files/**` | **不代表檔案公開**——permitAll 只代表「請求可到達 Controller」,實際授權在 `FileService#canRead()`(AVATAR 與已發布文章圖片對匿名開放;草稿圖片與未綁定檔案僅上傳者與 ADMIN 可讀) |
| GET | `/api/v1/categories/**` | Category queries |
| GET | `/api/v1/series/**` | Public series browsing(列表與詳情;寫入仍需認證) |
| GET | `/api/v1/recommend/**` | Recommendations |
| GET | `/api/v1/search` | Search |
| GET | `/api/v1/search/suggest` | Search suggestions |

> **Actuator 只有上表兩條是公開的，其餘限 ADMIN**（2026-09-06）：
> `management.endpoints.web.exposure.include` 自 `health,info` 增為 `health,info,metrics`
> （用於觀察 `BatchedQuery` 的輸入分佈，是 `findings.md` **ARCH-16** 的第一步）。
> `SecurityConfig` 於 actuator 的 permitAll 之後緊接
> `.requestMatchers("/actuator/**").hasRole("ADMIN")`，故 `/actuator/metrics`
> 未認證回 401、一般登入使用者回 403。**少了這條，暴露 metrics 等於讓任何已登入使用者
> 讀到端點清單與呼叫量**——這正是 BUG-2026-001 FIN-3+4「新端點沒順手確認授權面」的形態。
> 守衛：`SecurityConfigTest` 的 `unauthenticatedGetActuatorMetrics_shouldReturn401`、
> `authenticatedNonAdminGetActuatorMetrics_shouldReturn403`、
> `adminGetActuatorMetrics_shouldPassAuthorization`，
> 以及 `unauthenticatedGetActuatorHealth_shouldNotBeUnauthorized`
> （最後一條防止日後收緊時誤傷 K3s probe，那會讓 pod 反覆重啟）。

**`/api/v1/articles/**` 底下明確「不公開」的端點**(SEC-09 收窄後由 URL 層 + 方法層雙重把關):

| Method | Path | 方法層守衛 |
|--------|------|-----------|
| GET | `/api/v1/articles/me` | `@PreAuthorize("isAuthenticated()")` |
| GET | `/api/v1/articles/{uuid}/edit` | `@PreAuthorize("hasAuthority('ARTICLE_EDIT')")` |
| GET | `/api/v1/articles/{uuid}/versions`、`/versions/{versionUuid}` | `@PreAuthorize("isAuthenticated()")` |
| GET | `/api/v1/articles/{uuid}/highlights` | `@PreAuthorize("isAuthenticated()")` |
| GET | `/api/v1/articles/{uuid}/progress` | `@PreAuthorize("isAuthenticated()")` |

> 此表必須與 `blog-infrastructure/.../config/SecurityConfig.java` 的 `permitAll()` 規則**一對一對得上**;改任一邊都要同步另一邊與前端 `ai-docs/api-contract.md`。
> (2026-07-07 依 `SecurityConfig.java:75-102` 校正:移除 `GET /api/v1/users/**`——已收窄,見 `SecurityConfigTest.java:235`「已收窄 permitAll」;補上 categories、recommend。)
> (2026-07-16 依 Yuan 裁定新增 `GET /api/v1/series/**`:此前 SecurityConfig 無 series 規則,該路徑落入 `anyRequest().authenticated()`,與 `SeriesController` JavaDoc 自稱「公開」矛盾(backlog H5 / 07-07 #3),且使公開的前端 `/tags` 頁對匿名訪客靜默隱藏系列區塊。**僅開放 GET**,寫入維持認證;守衛見 `SecurityConfigTest` 的 `unauthenticatedListSeries_shouldReturn200` / `unauthenticatedGetSeriesDetail_shouldReturn200` / `unauthenticatedPostSeries_shouldReturn401`。)
> (2026-07-18 commit `147e4cf`:series 對匿名開放後的讀取端點強化。permitAll 面**不變**,僅收斂匿名可見內容——(1) 公開列表 `findPublic`/`countPublic` 改以 `EXISTS(status='PUBLISHED')` 判可見性,只含草稿的系列不再曝光給匿名;(2) `getSeriesDetail` 一律只回 PUBLISHED 文章、`articleCount` 為實際公開數,零篇已發布仍回 200 空清單(series 視為存在,列表另行策展);(3) `listPublic` 的 `size` 夾 [1,100] 防匿名放大查詢。此三項是「原則 7 選填認證公開端點」豁免下、匿名可見面的縱深控制。守衛 `SeriesControllerIT.list_seriesWithOnlyDraft_excludedFromPublicList` / `getSlug_anonymous_neverExposesDraftArticle` / `getSlug_anonymous_seriesWithoutPublished_returnsEmpty` / `list_oversizedPageSize_clampedTo100`。)

> (2026-09-04 SEC-09 + SEC-27 一次校正,依 `SecurityConfig.java` 逐條核對:
> **(1) SEC-09 收窄** `GET /api/v1/articles/**` → 明確列舉 5 條公開讀取端點。原本整段 permitAll 使
> me / edit / versions / highlights / progress 在 URL 層等同公開,只靠方法層 `@PreAuthorize` 兜底,
> 違反原則 1「兩層防護缺一不可」。收窄前實測仍 fail-closed(方法層有擋),故非現存漏洞,而是補上縱深防禦。
> 公開清單經前端 repo(`blog-web-v2-front-end` develop)逐一核對匿名呼叫路徑後確認:list / archive /
> slug / uuid 由首頁、文章列表、標籤頁、作者頁、封存頁、文章詳情匿名呼叫;comments 由
> `useComments` 無條件呼叫(不受 `isAuthenticated` 保護);highlights 與 progress 的 GET 在前端分別由
> `useArticleHighlights.canLoad` / `usePersistedReadingProgress.canPersist` 以 `authStore.isAuthenticated`
> 擋住,versions / edit / me 僅在 `requiresAuth` 路由下呼叫,收窄不影響匿名瀏覽。
> 守衛:`SecurityConfigTest` 的 `unauthenticatedListArticles_shouldReturn200` /
> `unauthenticatedGetArchive_shouldReturn200` / `unauthenticatedGetArticleBySlug_shouldReturn200` /
> `unauthenticatedGetArticleByUuid_shouldReturn200` / `unauthenticatedListComments_shouldReturn200`
> 與 `unauthenticatedGetMyArticles_shouldReturn401` / `unauthenticatedGetArticleForEdit_shouldReturn401` /
> `unauthenticatedListVersions_shouldReturn401` / `unauthenticatedGetVersionDetail_shouldReturn401` /
> `unauthenticatedListHighlights_shouldReturn401` / `unauthenticatedGetProgress_shouldReturn401`,
> 另有 5 條 `authenticated*_shouldReturn200` 正向案例確保沒擋掉合法使用者。
> **(2) SEC-27 文件校正**:`/api/admin/**` → `/api/v1/admin/**`(全檔);補上實作早已存在但未入表的
> `/actuator/health/**`、`/actuator/info`、`/favicon.ico`、`/error`、`/swagger-ui.html`;
> `GET /api/v1/files/**` 的描述由「Public file access」改為 PR #54 之後的實際語意
> (permitAll 只代表可到達 Controller,授權在 `canRead`);移除與任何 `permitAll()` 都對不上的
> 「Static resources / css, js, images」列——本專案是純 API 後端,靜態資源由 nginx 供應,
> `SecurityConfig` 只有 `/favicon.ico` 與 `/error` 兩條,以此二者取代之(**未改變任何端點的公開與否**)。)

> (2026-09-04 內部駁回評語不再對匿名揭露。permitAll 面**不變**,僅收斂匿名可見內容:
> `rejectReason` 是 admin 駁回文章時寫的內部審核評語,原本由 `ArticleResponseMapper` 無條件填入
> `ArticleResponse` / `ArticleSummaryResponse` / `EditorArticleResponse`,且全 repo 無 `@JsonInclude`、
> 公開端點無遮蔽,而只有 `submitForReview` 一處會清空 → 被駁回的文章一旦重新發布,評語就隨
> `GET /api/v1/articles/{uuid}`(匿名可存取)外流。兩層防禦:(1) `ArticleResponseMapper#resolveRejectReason`
> 依 `SecurityContext` 只對作者本人與 ADMIN 揭露,其餘一律 null(fail-closed,非 HTTP 執行緒亦然);
> (2) `clearRejectReasonIfNotRejected` 在 6 個狀態離開 REJECTED 的轉換點清空。
> 遮蔽層純看身分、不看 status,故線上既有的「非 REJECTED 卻帶 reject_reason」髒資料屬 DB 殘留而非洩漏面
> (回填 migration 另案)。守衛 `ArticleResponseMapperTest` 的 anonymous / 非作者一般使用者 / 作者 / ADMIN
> 四身分案例,與 `ArticleControllerIT` 的匿名整份 body `not(containsString(...))` 斷言。)

All other endpoints require **authentication** (`authenticated()`).
`/api/v1/admin/**` additionally requires the **ADMIN role**.
