# Testing Standards

## Coverage

*   Tests must cover the feature.
*   **Pristine Output**: Logs during tests should be clean unless testing error handling.
*   **No Exceptions**: "Not applicable" is not an excuse. Unit, Integration, E2E tests are required unless explicitly waived by Yuan.

## Test Method Naming

Chinese characters are **forbidden** in test method names.
Method names must use English `camelCase`. Use `@DisplayName("繁體中文說明")` to
provide a human-readable Traditional Chinese description.

**Correct**:
```java
@Test
@DisplayName("文章不存在時回傳 empty")
void whenArticleNotFound_returnsEmpty() { ... }
```

**Forbidden**:
```java
@Test
void 文章不存在時回傳empty() { ... }
```
