# Code Standards

## Code Quality

*   **Simplicity**: Simple > Clever. Readability is paramount.
*   **Small Changes**: Atomic commits. Ask before rewriting systems.
*   **Consistency**: Follow existing local style over external "standards".
*   **No "Mock Mode"**: Use REAL libraries and REAL patterns.

## Null 與空值判斷

建議優先使用以下工具類簡化 null / 空值判斷，提升可讀性與一致性。

### `ObjectUtils`（`org.apache.commons.lang3.ObjectUtils`）— 通用物件判斷

*   `ObjectUtils.isEmpty(obj)` — 取代 `obj == null` 及集合、陣列、字串的空值判斷
*   `ObjectUtils.isNotEmpty(obj)` — 取代 `obj != null` 且非空的判斷
*   `ObjectUtils.defaultIfNull(obj, defaultValue)` — 取代 `obj != null ? obj : defaultValue`
*   `ObjectUtils.requireNonEmpty(obj, message)` — 前置條件檢查

### `StringUtils`（`org.apache.commons.lang3.StringUtils`）— 字串判斷

*   `StringUtils.isBlank(str)` — 取代 `str == null || str.trim().isEmpty()`
*   `StringUtils.isNotBlank(str)` — 取代 `str != null && !str.trim().isEmpty()`
*   `StringUtils.defaultIfBlank(str, defaultValue)` — 取代字串空值的三元運算

### `Objects`（`java.util.Objects`）— 搭配使用

*   `Objects.equals(a, b)` — null-safe 相等比較
*   `Objects.nonNull(obj)` / `Objects.isNull(obj)` — Stream filter 等函式參考場景
*   `Objects.requireNonNull(obj, message)` — 參數前置條件檢查（純 null 不涉及「空」時用這個）

### 選用時機

| 情境 | 建議工具 |
|---|---|
| 純 null 檢查 | `Objects` |
| null + 空值（空字串、空集合） | `ObjectUtils` |
| 字串 null + blank | `StringUtils` |

## Error Handling & HTTP Status Codes (CRITICAL)

*   **Always use `ResponseEntity`**: All `@ExceptionHandler` methods MUST return `ResponseEntity<ApiResponse<Void>>` with an explicit HTTP status. Returning `ApiResponse` directly results in HTTP 200 regardless of the error.
*   **Status code mapping**:
    *   `BusinessException` → **HTTP 400** (`HttpStatus.BAD_REQUEST`)
    *   `MethodArgumentNotValidException` / `BindException` → **HTTP 400** (`HttpStatus.BAD_REQUEST`)
    *   `SystemException` → **HTTP 500** (`HttpStatus.INTERNAL_SERVER_ERROR`)
    *   `DuplicateKeyException`（唯一鍵衝突，直接或位於 cause 鏈）→ **HTTP 409**，`CommonErrorCode.DATA_CONFLICT`（`A0007`），WARN。兜底用：service 層能辨識的衝突仍須自行轉成更具體的業務錯誤碼；回應不得含 SQL／約束名／欄位值，完整例外只進 log
    *   其餘 `DataIntegrityViolationException`（NOT NULL、外鍵、CHECK、欄位超長）→ **HTTP 500**（通用系統錯誤訊息），**ERROR** 記錄完整例外：重試無效，屬程式缺陷，不可偽裝成「資料衝突」
    *   `OptimisticLockingFailureException`（`@Version` 衝突）→ **HTTP 409**，`CommonErrorCode.OPTIMISTIC_LOCK_CONFLICT`（`A0008`），WARN
    *   Catch-all `Exception` → **HTTP 500** (`HttpStatus.INTERNAL_SERVER_ERROR`)；但 cause 鏈含唯一鍵衝突或樂觀鎖失敗者（Spring Data JDBC `save` 的 `DbActionExecutionException` 包裝形態）與直接拋出時一致，回 409
    *   `ResponseStatusException` → preserve original status code via `e.getStatusCode()`
    *   `AccessDeniedException` → re-throw to let Spring Security handle (returns HTTP 403)
*   **Forbidden pattern**: `return ApiResponse.failed(...)` in an `@ExceptionHandler` — this is always HTTP 200.
*   **Correct pattern**: `return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failed(...))`

### 錯誤碼放置與碼段（W7-C3，Yuan 2026-11-07 拍板）

*   **錯誤碼一律放 blog-common 的 `dowob.xyz.blog.common.api.errorcode`（`api/errorcode/`），每個模組一個 enum，碼段依模組劃分；字串屬前端契約，不可更改既有值。** 不得在業務模組內另建 `*ErrorCode`；守衛：`ErrorCodeCentralizationTest`（位置 + 全站字串不重複）。
*   新增錯誤碼：沿用該模組的碼段往下接續編號；**不得借用其他模組的 enum**（例如拿 `UserErrorCode.USER_NOT_FOUND` 表達非 user 語意）。新模組開新碼段與新 enum。
*   目前碼段（2026-11-07 以 grep 盤點；`A` 系列為「通用／使用者可修正」前綴，依序再分段）：

| Enum | 碼段 | 已用 | 備註 |
|---|---|---|---|
| `CommonErrorCode` | `A0001`～`A0009`、`B0001`、`B0100`、`B0200`～`B0201` | 13 | 跨模組通用（`A` 通用錯誤、`B` 系統錯誤） |
| `UserErrorCode` | `A0101`～`A0114` | 13 | `A0106` 已停用（Q8，2026-11-04），缺號不回填 |
| `ArticleErrorCode` | `A0201`～`A0210` | 10 | |
| `TagErrorCode` | `A0301`～`A0305` | 5 | |
| `FileErrorCode` | `A0401`～`A0407` | 7 | |
| `SearchErrorCode` | `A0501`～`A0502` | 2 | W7-f 新增（`A0501` 重建進行中）、W7-C2 補 `A0502`（principal 無法解析）；search 不再借用 `UserErrorCode` |
| `CommentErrorCode` | `C0101`～`C0106` | 6 | W7-C3 自 comment 模組搬入 |
| `ReadingErrorCode` | `R0201`～`R0202`、`R0301` | 3 | W7-C3 自 reading 模組搬入 |
| `SeriesErrorCode` | `S0101`～`S0107` | 7 | W7-C3 自 series 模組搬入 |
| `VersionErrorCode` | `V0101`～`V0108` | 8 | W7-C3 自 version 模組搬入 |

## OpenAPI 標註慣例（W7-C2／ARCH-14，Yuan 2026-11-07 拍板 C2）

> **springdoc 產生的 `openapi.json` 是前後端契約的唯一真相**（本 repo 不再維護手寫契約文件；前端快照依前端 repo `runbook-integration.md` §8 抓取）。因此每個端點的標註必須完整、且與實際行為一致。守衛：`OpenApiAnnotationGuardTest`（靜態）＋ `OpenApiDocsE2E`（實際產出）。

*   **每個 controller 一個 `@Tag(name = "<繁體中文模組名>")`**（例如 `文章`、`留言`、`搜尋管理`）。模組內若有 `Tag` 實體類別（tag 模組）以 FQN `@io.swagger.v3.oas.annotations.tags.Tag` 避免衝突。
*   **每個 handler 一個 `@Operation(summary = "...", description = "...")`**：
    *   `summary`：繁體中文、一句話、不寫實作細節（括號補充放 description）。
    *   `description`：開頭寫權限（`公開`／`登入`／`需 XXX 權限`／作者本人或管理員），再寫重要業務規則（冪等、時限、上限、存在性不洩漏等）。
*   **成功回應不是 200 要明確標示**：`@ApiResponse(responseCode = "202", description = "...")`；以 `@ResponseStatus` 宣告非 200 時守衛強制要有對應的 `@ApiResponse`。回傳 `ResponseEntity` 的 handler 無法靜態判斷，自行標示（例如 reindex 的 202／409）。
*   **業務錯誤標在 handler 上**，格式 `@ApiResponse(responseCode = "<HTTP 狀態>", description = "<錯誤碼> <意義>；<錯誤碼> <意義>")`：
    *   同一 HTTP 狀態的多個錯誤碼合併在同一個 `@ApiResponse`，以全形分號分隔。
    *   **錯誤碼必須實際追 service 程式碼**確認會丟哪些 `BusinessException`，不得憑印象。HTTP 狀態以實際為準：`BusinessException` 預設 400，只有 `HttpStatusBusinessException`、`AccessDeniedException`（403）、`ResponseStatusException` 會是其他狀態。
    *   專案的 `ApiResponse<T>` 與 swagger 的 `@ApiResponse` 同名，handler 檔內一律用 FQN `@io.swagger.v3.oas.annotations.responses.ApiResponse`。
    *   有 `@Valid`／`@RequestParam` 型別轉換的端點，400 列入 `A0001 參數校驗失敗`。
*   **共通錯誤不逐一手寫**，由 `blog-infrastructure` 的 `OpenApiConfig` 統一推導（手寫同狀態碼時不覆蓋）：
    *   **401**：handler 帶 `@PreAuthorize`；**403**：`@PreAuthorize` 含 `hasAuthority／hasRole` 系列。
    *   **429**：handler 或 class 帶 `@RateLimited`，並附 `Retry-After` header 說明。
    *   **500**：所有 operation（`B0001`）。
    *   採 `OperationCustomizer` 讀 `HandlerMethod` 的標註而非手寫，理由：共通錯誤由「實際生效的標註」推導，標註改了文件自動跟著變，不會漂移。
*   `@AuthenticationPrincipal AuthUser` 是 Spring Security 注入的登入主體，不是請求參數；`OpenApiConfig` 已讓 springdoc 忽略 `AuthUser`，新增類似的注入型別時同樣要忽略，否則會被展開成 query 參數。
*   request／response DTO 的 `@Schema` 目前不強制，有餘力再補欄位說明。

## Transaction + MQ 時序 (CRITICAL)

> 來源:BUG-2026-001(FIN-2)。`@Transactional` 內直接發 MQ,DB 尚未 commit;若 commit 失敗但 MQ 已送出,產生資料不一致。

*   **禁止**在 `@Transactional` 方法內呼叫 `rabbitTemplate.convertAndSend()`。
*   **必須**改用 `TransactionTemplate` 收斂 DB 操作,commit 成功後才以 best-effort 模式發送 MQ:

```java
// ❌ Forbidden — DB 未 commit 就發 MQ
@Transactional
public void register(...) {
    userRepository.save(user);
    rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event);
}

// ✅ Correct — TransactionTemplate + best-effort
public void register(...) {
    User saved = transactionTemplate.execute(status -> userRepository.save(user));
    try {
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event); // DB 已 commit
    } catch (Exception e) {
        log.warn("MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
    }
}
```

*   **交易作用域沿呼叫鏈傳遞,檢查不能只看同一個方法**:`XxxEventPublisher` 這類發送類本身沒有 `@Transactional`,但被 `@Transactional` 方法呼叫時一樣在交易內。Review 時從每個 `rabbitTemplate.convertAndSend()` 呼叫點**往上追呼叫者**,確認最外層不在交易作用域;字面 grep「同方法內同時出現 @Transactional 和 rabbitTemplate」抓不到這種違規。
*   **Producer/Consumer 必須配對**(BUG-2026-001 FIN-1):新增任一 Consumer/binding 時,同一個 PR 內必須有對應的 Producer 呼叫點,反之亦然。Review 時 grep routing key 確認兩端都存在。

## Documentation & Comments (CRITICAL)

*   **Language**: All JavaDoc and comments MUST be in **Traditional Chinese (繁體中文)**.
*   **Mandatory JavaDoc**:
    *   All Classes: Description, Author, Version.
    *   All Public Methods: Functionality, Parameters (@param), Return values (@return).
    *   All Member Variables: Purpose and meaning.
*   **No Single-line Comments**: Avoid `//`. Use JavaDoc `/** ... */` block style for everything to ensure visibility and standardize documentation.
