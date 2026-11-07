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
| ADMIN | ROLE_ADMIN | All permissions (`EnumSet.allOf`), including the `ADMIN_*` family below (only ADMIN has them) |

**Admin permissions (`ADMIN_*`, A9／AUTH-10, Yuan 2026-11-07)** — every admin endpoint is guarded by a permission named for its duty, on top of the URL-layer `/api/v1/admin/** → hasRole('ADMIN')` (two layers). The generic `SYSTEM_CONFIG` (and the unused duplicate `TAG_MANAGE`, renamed `ADMIN_TAG`) no longer exist; the guard `EndpointAuthorizationTest` rejects any `/api/v1/admin/**` handler that is not `hasAuthority('ADMIN_*')` with an existing permission name.

| Permission | Endpoints |
|------------|-----------|
| `ADMIN_TAG` | `PUT`／`DELETE /api/v1/admin/tags/{id}` |
| `ADMIN_CATEGORY` | `POST /api/v1/admin/categories`, `PUT`／`DELETE /api/v1/admin/categories/{uuid}` |
| `ADMIN_ARTICLE_REVIEW` | `GET /api/v1/admin/articles/pending`, `POST /api/v1/articles/{uuid}/reject` (the only one outside the `/admin` URL layer — method layer is its sole guard) |
| `ADMIN_ARTICLE_ARCHIVE` | `POST /api/v1/admin/articles/{uuid}/archive`, `POST /api/v1/admin/articles/{uuid}/unarchive` |
| `ADMIN_SEARCH` | `POST /api/v1/admin/search/reindex`, `GET /api/v1/admin/search/status` |

`USER_MANAGE`, `USER_BAN`, `SYSTEM_ANNOUNCE` are reserved ADMIN-only permissions with no endpoint yet. The two version read endpoints (`GET /api/v1/articles/{uuid}/versions[/{versionUuid}]`) now require `ARTICLE_EDIT` like the four write endpoints; owner/admin checks stay in the service layer.

### 4. Capability vs Ownership Separation

*   `@PreAuthorize("hasAuthority('ARTICLE_EDIT')")` only checks whether the caller *can* edit articles.
*   Whether *this article belongs to the caller* is determined in the **Service layer**. ADMIN automatically bypasses ownership checks.
*   **Never perform ownership checks in the Controller layer.**

### 5. Stateful JWT

Every Access Token carries a `version` claim. The JWT `subject` is the user's uuid (tokens whose subject is not a UUID are invalid). Redis key `user:auth:{userUuid}` (Hash: `status`, `role`, `version`; legacy hashes that still carry an `id` field remain readable — the extra field is ignored) stores the current valid state.

**Filter validation flow:**
1. Extract `Authorization: Bearer <jwt>` header.
2. Verify JWT signature.
3. Extract `userUuid`, `tokenVersion`, `role`.
4. `AuthStateLoader` looks up Redis `user:auth:{userUuid}`; a missing/partial hash (any of `status`/`role`/`version` absent) is a cache miss → fall back to DB and backfill. `/auth/refresh` shares the same loader.
5. `tokenVersion` must **exactly match** the Redis version (`Objects.equals`).
6. User status must be `ACTIVE` or `PENDING_VERIFICATION`.
7. Build `Authentication` with principal = `AuthUser(uuid, role)`; `role` and the authorities both come from the token's `role` claim. (`/auth/refresh` accepts `ACTIVE` only.) Only `ACTIVE` users get the role's Permission authorities; `PENDING_VERIFICATION` users get the `ROLE_*` authority only, so every `hasAuthority('<PERMISSION>')` endpoint rejects them (Q3: unverified accounts must not write; AUTH-06, 2026-11-06). Comment create / edit / like / unlike require `COMMENT_WRITE`, comment delete requires `COMMENT_DELETE` (AUTH-04).

**Instant logout**: update the token version in Redis — existing tokens become invalid immediately without waiting for JWT expiry.

### 6. Key Management

*   JWT uses **ECDSA ES256** (never RSA).
*   The private key is stored as a **PKCS8 PEM** string in a K8s Secret, injected via the `JWT_PRIVATE_KEY` environment variable.
*   When `JWT_PRIVATE_KEY` is not set, `JwtService` generates a fresh key pair on startup (tokens invalidated on restart) **only under an explicit non-production profile** (`dev` / `demo` / `e2e` / `test`) and logs a WARN. In every other case — including no active profile and `prod` — startup **fails** with a message naming `JWT_PRIVATE_KEY`. The profile decision lives in one place: `RuntimeProfiles.isNonProduction(Environment)` (`blog-infrastructure`); `prod` combined with any non-production profile (`dev` / `demo` / `e2e` / `test`, in any order) is a misconfiguration: `RuntimeProfiles.requireNoProductionConflict(Environment)` makes both `JwtService` and `RequiredSecretsEnvironmentPostProcessor` **fail startup** (the non-production yaml would otherwise supply weak default credentials), listing the active profiles.
*   **No usable default credentials in the base `application.yaml`**: DB / RabbitMQ / MinIO(S3) username, password and keys resolve to empty when their environment variable is unset (`DATABASE_USERNAME`, `DATABASE_PASSWORD`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`). Outside a non-production profile, `RequiredSecretsEnvironmentPostProcessor` (`blog-start`) refuses to start and lists the missing variables. Development defaults belong only in `application-dev.yaml` / `application-demo.yaml` / `application-e2e.yaml` (or test resources). Redis, Elasticsearch and SMTP credentials are optional and stay empty by default.
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

### 11. Client IP 只能經 `ClientIpResolver` 取得 (CRITICAL)

> 來源:findings SEC-01、決策紀錄 §6.1「SEC-01 定案 (a)」。`X-Forwarded-For`(XFF)左側節點由 client 任意偽造,
> 直接取第一節或讓 Spring 無條件用 XFF 覆寫 remoteAddr,會使登入/註冊/忘記密碼/重寄驗證信/驗證碼 IP 限流、瀏覽數去重整層可繞過。

*   正式拓撲:Client → Cloudflare → nginx → k3s Ingress → app。第 1 層(origin 只放行 Cloudflare)、第 2 層(nginx `realip` 並以 `$remote_addr` **覆寫** XFF)在 infrastructure repo;本 repo 負責第 3 層。
*   **唯一入口**:`dowob.xyz.blog.infrastructure.web.ClientIpResolver#resolve(HttpServletRequest)`。controller 注入它,不得自行讀 `X-Forwarded-For`/`Forwarded`/`X-Real-IP`/`X-Client-IP`/`CF-Connecting-IP`/`True-Client-IP`,也不得呼叫 `getRemoteAddr()`/`getRemoteHost()`/`getRemoteAddress()`(守衛:`ClientIpAccessGuardTest`)。
*   **信任層數** `app.http.trusted-proxy-count`(環境變數 `APP_HTTP_TRUSTED_PROXY_COUNT`,**預設 0**):取 XFF **由右數第 N 節**。
    *   `0`:完全不信任 XFF,回傳 `getRemoteAddr()`(本機 docker、CI、任何無 proxy 的部署)。
    *   `>= 1`:取右數第 N 節;XFF 缺漏/空白、項目數少於 N、被選中的節點不是合法 IPv4/IPv6 字面值時,**退回 `getRemoteAddr()`**(方向是「多人共用一個限流桶」而非「被繞過」)。IPv6 正規化為與 Tomcat 相同的完整展開小寫形式。
    *   prod 依第 2 層(nginx 覆寫 XFF)的結果設 `1`;nginx 若仍是追加模式則需設更大值。**設太大 → 取到攻擊者可控節點(等於無防護);設太小 → 全站共用限流桶(退化但不被繞過)。拿不準時寧可設小。** 部署後以 `curl -H "X-Forwarded-For: 6.6.6.6" …` 實測:`6.6.6.6` 必須落在鏈頭,且算出的節點不得指到它。
*   **禁止**設定 `server.forward-headers-strategy`(`framework`/`native`)與 `server.tomcat.remoteip.*`:它們讓 XFF 無條件改寫 `remoteAddr`,使 `ClientIpResolver` 的退路失效(守衛:`ClientIpTrustConfigTest`)。
*   **count = 0 的啟動警示**:`ClientIpResolver` 在 count = 0 時於啟動記一行 WARN(所有請求以 `remoteAddr` 計;若部署在 proxy 後請設 `APP_HTTP_TRUSTED_PROXY_COUNT`)。prod 忘了設會讓全站共用 Ingress IP 當限流 key(任一攻擊者即可鎖死全站登入)且無其他徵兆,故只警示、不讓啟動失敗(dev/CI 本來就是 0)。
*   **scheme/host/port 的信任來源**:Cookie 的 Secure 旗標由 `app.cookie.secure` 控制,程式不依 request scheme/host 組絕對 URL。但 TLS 在 proxy 終結時 app 看到 `scheme=http`、`port=80`,Spring 的 `CorsUtils.isCorsRequest`(scheme+host+port 比對 `Origin`)會把同網域請求誤判為跨域而回 403。因此 `TrustedForwardedRequestFilter`(`@Order(HIGHEST_PRECEDENCE + 10)`,早於 Spring Security 過濾器鏈的 `CorsFilter`)**只在 `app.http.trusted-proxy-count > 0` 時**採用 `X-Forwarded-Proto`/`X-Forwarded-Host`/`X-Forwarded-Port`,以 `HttpServletRequestWrapper` 還原 `getScheme()`/`isSecure()`/`getServerName()`/`getServerPort()`;count = 0 完全忽略。規則與 client IP 一致:各 header 取**由右數第 N 個**值(多行視同逗號串接、項目數不足 N 則不採用),值做白名單驗證(proto 只接受 `http`/`https`;host 為 RFC 1123 主機名稱或 IPv4 加可選 port;port 為 1–65535),非法值忽略。**不處理任何 IP**:不讀 `X-Forwarded-For`/`Forwarded`,不改 `getRemoteAddr()`。部署要求:proxy 鏈上由 app 往外數第 N 層必須讓 `X-Forwarded-Proto` 的右數第 N 個值是 client 看到的 scheme(`https`);部署後以 `curl -H "Origin: https://<網域>" -H "X-Forwarded-Proto: https" …/api/v1/auth/login` 實測不得回 403 Invalid CORS request。
*   守衛:`ClientIpAccessGuardTest` 除 `X-Forwarded-For` 外,亦禁止 `Forwarded`/`X-Client-IP`/`X-Original-Forwarded-For` 等 header 字面值,以及 `ServerHttpRequest#getRemoteAddress`、`WebAuthenticationDetails#getRemoteAddress`;`X-Forwarded-Proto/Host/Port` 字面值只允許出現在 `TrustedForwardedRequestFilter`、`X-Forwarded-For` 只允許出現在 `ClientIpResolver`。字串拼接等動態組名無法靜態完全擋住(見該測試的類別 JavaDoc),由 code review 把關。

---

## Public Endpoints (No Authentication Required)

> 下表**依 `SecurityConfig` 中的宣告順序排列**,每一列對應一條 `permitAll()`。

| Method | Path | Description |
|--------|------|-------------|
| ANY | `/livez`, `/readyz` | K3s liveness/readiness probe（主 port）。actuator 搬到獨立 management port 後，由 `management.endpoint.health.probes.add-additional-paths=true` 在主 port 提供 |
| ANY（限 management port） | `/actuator/**`（含 health、info、prometheus） | **僅「從 management port 進來」的請求**匿名放行（抓取端不帶 JWT），前提是該 port 不對外，見下方說明；主 port 的同一路徑（包括 `/actuator/health/**`、`/actuator/info`）不放行，一律 ADMIN |
| ANY | `/swagger-ui/**`, `/v3/api-docs/**`, `/swagger-ui.html` | API documentation。僅在 springdoc 啟用時（dev／demo／`SPRINGDOC_ENABLED=true`）存在；正式環境關閉（SEC-21，Yuan 2026-11-04）：關閉時這些路徑**不在 permitAll**，匿名請求落入 `anyRequest().authenticated()` 回 401；已登入者打到則由 `GlobalExceptionHandler` 回 404 |
| ANY | `/favicon.ico`, `/error` | favicon 與 Servlet 容器的錯誤轉發路徑 |
| ANY | `/api/v1/auth/**` | Login, registration, refresh 等。**例外**:`POST /api/v1/auth/logout` 已在上一條規則設為 `authenticated()` |
| GET | `/api/v1/articles` | 已發布文章列表 |
| GET | `/api/v1/articles/archive` | 文章封存(依年月彙整) |
| GET | `/api/v1/articles/slug/{slug}` | 依 slug 取已發布文章 |
| GET | `/api/v1/articles/{uuid}` | 依 uuid 取文章(`{uuid}` 以 UUID 形狀比對,非 UUID 的字面量子路徑不落在此規則內) |
| GET | `/api/v1/articles/{uuid}/comments` | 文章留言列表(原則 7 豁免的匿名可讀端點) |
| GET | `/api/v1/comments/{uuid}/replies` | 頂層留言的回覆分頁(「載入更多」;原則 7 豁免的匿名可讀端點,與留言列表同一公開面;`{uuid}` 以 UUID 形狀比對,僅開放 GET;文章對檢視者不可讀、留言不存在、傳入回覆的 uuid 三者一律回 200 空頁,回應完全相同,與列表一致,不形成存在性探測) |
| GET | `/api/v1/tags/**` | Tag queries |
| GET | `/api/v1/files/**` | **不代表檔案公開**——permitAll 只代表「請求可到達 Controller」,實際授權在 `FileService#canRead()`(AVATAR 與已發布文章圖片對匿名開放;草稿圖片與未綁定檔案僅上傳者與 ADMIN 可讀) |
| GET | `/api/v1/categories/**` | Category queries |
| GET | `/api/v1/series/**` | Public series browsing(列表與詳情;寫入仍需認證) |
| GET | `/api/v1/users/{uuid}/profile` | 公開作者頁資料(`{uuid}` 以 UUID 形狀比對,故 `/api/v1/users/me/...` 等字面量子路徑仍需認證;僅開放 GET)。只回可公開欄位,不含 email、username、role、通知偏好;非 ACTIVE(不存在、DELETED、未驗證、BANNED)一律 404 `A0101`,彼此不可區分 |
| GET | `/api/v1/authors` | 作者下拉搜尋。只列 ACTIVE 且至少 1 篇 PUBLISHED 文章的作者,固定最多 20 筆,keyword 最多 50 字(超過回 400) |
| GET | `/api/v1/recommend/**` | Recommendations |
| GET | `/api/v1/search` | Search |
| GET | `/api/v1/search/suggest` | Search suggestions |

> (2026-11 公開作者功能,Yuan 已同意(設計 §3.4,`docs/plans/2026-10-23-public-author-profile-design.md`)新增 `GET /api/v1/users/{uuid}/profile` 與 `GET /api/v1/authors` 兩條 permitAll。兩者皆不取用 `@AuthenticationPrincipal`、回應不隨登入者而變,故不在原則 7 的豁免清單內,也不需要 `@PreAuthorize`。守衛:`SecurityConfigTest` 的 `unauthenticatedGetPublicProfile_shouldReturn200` / `unauthenticatedGetAuthors_shouldReturn200` 與反向 `unauthenticatedGetMeProfile_shouldReturn401` / `unauthenticatedPatchProfile_shouldReturn401` / `unauthenticatedPostAuthors_shouldReturn401`;`PublicAuthorControllerTest` 以 JSON 欄位白名單防止日後在 DTO 加 `email`／`username`／`role`。)

> **Actuator 的暴露面（ARCH-16／決策 C7，Yuan 2026-11-07 拍板；2026-11-07 實作）**：
> actuator 搬到獨立的 **management port**（`management.server.port`，預設 `9091`，可用環境變數
> `MANAGEMENT_SERVER_PORT` 覆寫），`exposure.include` 為 `health,info,metrics,prometheus`。
> `/actuator/prometheus` 是 Prometheus 文字格式匯出，內含 `mq.consumer.*`、`mq.publisher.failed`、`mq.dlq.depth` 等運維指標。
>
> - **主 port（`server.port`，對外）**：只有 `/livez`、`/readyz`（K3s probe）；沒有 `/actuator/**`。
>   `SecurityConfig` 對主 port 的 `/actuator/**` 維持 `hasRole("ADMIN")`（fail-closed 兜底）。
> - **management port（不對外）**：同一條 `SecurityFilterChain` 也套用在這個 port（E2E 實測）。
>   `SecurityConfig` 以請求的實際連線 port（`getLocalPort()`，非 Host header）等於 `local.management.port`
>   且路徑為 `/actuator/**` 時匿名放行，讓抓取端不需 JWT。若 management port 未設或**等於主 port**
>   （共用對外 port），此規則不匹配，退回 `ADMIN`——誤設不會把指標匿名公開到公網。
> - **「此 port 不對外」是安全前提，不是程式能保證的事**：部署時 management port **不得**被 ingress、Service
>   （LoadBalancer／NodePort）或 `docker -p` 對外發布；只允許叢集內部（Prometheus 抓取端）連線。
>   K3s probe 也可改打 management port 的 `/actuator/health/liveness`、`/actuator/health/readiness`，
>   但預設走主 port 的 `/livez`、`/readyz`（不依賴 management port 的網路規則）。
> - 守衛：`SecurityConfigManagementPortTest`（management port 放行、主 port 401、不順帶放行 API）、
>   `SecurityConfigManagementPortSamePortTest`（兩 port 相同時 fail-closed）、
>   `SecurityConfigTest` 的 `unauthenticatedGetActuatorMetrics_shouldReturn401`、
>   `unauthenticatedGetActuatorPrometheus_shouldReturn401`、`authenticatedNonAdminGetActuatorMetrics_shouldReturn403`、
>   `adminGetActuatorMetrics_shouldPassAuthorization`，以及
>   `unauthenticatedGetProbePaths_shouldNotBeUnauthorized`（防止日後收緊時誤傷 `/livez`、`/readyz`，那會讓 pod 反覆重啟）；
>   端到端見 `ActuatorManagementPortE2E`（management port 取得 Prometheus 輸出、主 port 取不到）。
> - **部署注意事項**：
>   1. management port 預設綁在**所有網卡**（未設 `management.server.address`），不得經由 Service（含 ClusterIP 以外的型別）、
>      ingress 或 host network 發布；Pod 若用 `hostNetwork: true`，9091 會直接暴露在節點上。
>   2. K3s probe **必須**改打主 port 的 `/livez`、`/readyz`（或 management port 的 `/actuator/health/liveness|readiness`）；
>      主 port 已沒有 `/actuator/health`，仍打舊路徑會得到 404（探針失敗），pod 會反覆重啟。
>   3. `spring.rabbitmq.connection-timeout`（預設 5s）與 `channel-rpc-timeout`（預設 10s）為全站 MQ 連線層逾時，
>      可用 `RABBITMQ_CONNECTION_TIMEOUT`、`RABBITMQ_CHANNEL_RPC_TIMEOUT` 覆寫。
> - 歷史：2026-09-06 曾將 `exposure.include` 自 `health,info` 增為 `health,info,metrics`（BatchedQuery 輸入分佈，ARCH-16 第一步），
>   並加上 `.requestMatchers("/actuator/**").hasRole("ADMIN")`，避免「新端點沒順手確認授權面」（BUG-2026-001 FIN-3+4）；該規則保留。

**`/api/v1/articles/**` 底下明確「不公開」的端點**(SEC-09 收窄後由 URL 層 + 方法層雙重把關):

| Method | Path | 方法層守衛 |
|--------|------|-----------|
| GET | `/api/v1/articles/me` | `@PreAuthorize("isAuthenticated()")` |
| GET | `/api/v1/articles/{uuid}/edit` | `@PreAuthorize("hasAuthority('ARTICLE_EDIT')")` |
| GET | `/api/v1/articles/{uuid}/versions`、`/versions/{versionUuid}` | `@PreAuthorize("hasAuthority('ARTICLE_EDIT')")`(A9;擁有者／ADMIN 檢查在 service 層) |
| GET | `/api/v1/articles/{uuid}/highlights` | `@PreAuthorize("isAuthenticated()")` |
| GET | `/api/v1/articles/{uuid}/progress` | `@PreAuthorize("isAuthenticated()")` |

> 此表必須與 `blog-infrastructure/.../config/SecurityConfig.java` 的 `permitAll()` 規則**一對一對得上**;改任一邊都要同步另一邊與前端 `ai-docs/api-contract.md`。
> (2026-11-07 D3,Yuan 拍板「回覆改為分頁(載入更多)」:新增 `GET /api/v1/comments/{uuid}/replies` 一條 permitAll。理由:列表 API 每則頂層留言只內嵌前 3 則回覆,其餘由此端點載入,而列表本身匿名可讀,若回覆分頁需登入,匿名讀者就看不到完整討論串。可見性與列表一致(文章可讀才可讀,SEC-16 的讀取面;不可讀時回 200 空頁而非 404,避免存在性探測)。守衛:`SecurityConfigTest` 的 `unauthenticatedListCommentReplies_shouldReturn200` 與反向 `unauthenticatedGetCommentByUuid_shouldReturn401` / `unauthenticatedListCommentRepliesWithNonUuid_shouldReturn401` / `unauthenticatedPostCommentReplies_shouldReturn401`;`EndpointAuthorizationTest.EXEMPT` 的 `CommentController#listReplies`;`CommentRepliesPagingIT` 驗證匿名可讀、不可讀／不存在／回覆 uuid 三者回應相同。)

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
