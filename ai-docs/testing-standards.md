# Testing Standards

## Coverage

*   Tests must cover the feature.
*   **Pristine Output**: Logs during tests should be clean unless testing error handling.
*   **Jacoco 棘輪**：`jacoco:check` 棘輪下限＝各模組 2026-11-05 現值減 2pp，只可上調不可下調；上調時改各模組 pom 的 `jacoco.minimum.line` / `jacoco.minimum.branch` 屬性（根 pom 預設 0，`mvn verify` 低於下限即失敗）。
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
