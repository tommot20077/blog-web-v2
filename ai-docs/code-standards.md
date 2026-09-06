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
    *   Catch-all `Exception` → **HTTP 500** (`HttpStatus.INTERNAL_SERVER_ERROR`)
    *   `ResponseStatusException` → preserve original status code via `e.getStatusCode()`
    *   `AccessDeniedException` → re-throw to let Spring Security handle (returns HTTP 403)
*   **Forbidden pattern**: `return ApiResponse.failed(...)` in an `@ExceptionHandler` — this is always HTTP 200.
*   **Correct pattern**: `return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failed(...))`

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
