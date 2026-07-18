# Security & Authorization Rules

## Core Principles

### 1. Two Layers of Protection — Both Are Required

| Layer | Mechanism | Granularity |
|-------|-----------|-------------|
| URL layer | `HttpSecurity.requestMatchers()` | Coarse-grained, by URL pattern |
| Method layer | `@PreAuthorize` | Fine-grained, by Permission / Role |

The URL layer protects `/api/admin/**` (ADMIN only). The method layer uses `hasAuthority('XXX')` to guard specific operations.
**Both layers must be applied. Never rely on only one.**

### 2. URL Rule Order Must Not Be Reversed

In `SecurityConfig`, **more specific rules must come before `anyRequest()`**:

```
/api/admin/**  → hasRole("ADMIN")    ← must be first
anyRequest()   → authenticated()     ← must be last
```

If the order is wrong, `anyRequest()` matches first and shadows the admin rule, creating a security hole.

### 3. Role vs Fine-Grained Permission

*   **Role** (`ROLE_XXX`) is used for coarse-grained URL protection (e.g., `/api/admin/**`).
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

| Method | Path | Description |
|--------|------|-------------|
| ANY | `/api/v1/auth/**` | Login, registration, etc. |
| GET | `/api/v1/articles/**` | Public article browsing |
| GET | `/api/v1/tags/**` | Tag queries |
| GET | `/api/v1/files/**` | Public file access |
| GET | `/api/v1/categories/**` | Category queries |
| GET | `/api/v1/series/**` | Public series browsing(列表與詳情;寫入仍需認證) |
| GET | `/api/v1/recommend/**` | Recommendations |
| GET | `/api/v1/search` | Search |
| GET | `/api/v1/search/suggest` | Search suggestions |
| ANY | `/swagger-ui/**`, `/v3/api-docs/**` | API documentation |
| ANY | Static resources | css, js, images, etc. |

> 此表必須與 `blog-infrastructure/.../config/SecurityConfig.java` 的 `permitAll()` 規則**一對一對得上**;改任一邊都要同步另一邊與前端 `ai-docs/api-contract.md`。
> (2026-07-07 依 `SecurityConfig.java:75-102` 校正:移除 `GET /api/v1/users/**`——已收窄,見 `SecurityConfigTest.java:235`「已收窄 permitAll」;補上 categories、recommend。)
> (2026-07-16 依 Yuan 裁定新增 `GET /api/v1/series/**`:此前 SecurityConfig 無 series 規則,該路徑落入 `anyRequest().authenticated()`,與 `SeriesController` JavaDoc 自稱「公開」矛盾(backlog H5 / 07-07 #3),且使公開的前端 `/tags` 頁對匿名訪客靜默隱藏系列區塊。**僅開放 GET**,寫入維持認證;守衛見 `SecurityConfigTest` 的 `unauthenticatedListSeries_shouldReturn200` / `unauthenticatedGetSeriesDetail_shouldReturn200` / `unauthenticatedPostSeries_shouldReturn401`。)
> (2026-07-18 commit `147e4cf`:series 對匿名開放後的讀取端點強化。permitAll 面**不變**,僅收斂匿名可見內容——(1) 公開列表 `findPublic`/`countPublic` 改以 `EXISTS(status='PUBLISHED')` 判可見性,只含草稿的系列不再曝光給匿名;(2) `getSeriesDetail` 一律只回 PUBLISHED 文章、`articleCount` 為實際公開數,零篇已發布仍回 200 空清單(series 視為存在,列表另行策展);(3) `listPublic` 的 `size` 夾 [1,100] 防匿名放大查詢。此三項是「原則 7 選填認證公開端點」豁免下、匿名可見面的縱深控制。守衛 `SeriesControllerIT.list_seriesWithOnlyDraft_excludedFromPublicList` / `getSlug_anonymous_neverExposesDraftArticle` / `getSlug_anonymous_seriesWithoutPublished_returnsEmpty` / `list_oversizedPageSize_clampedTo100`。)

All other endpoints require **authentication** (`authenticated()`).
`/api/admin/**` additionally requires the **ADMIN role**.
