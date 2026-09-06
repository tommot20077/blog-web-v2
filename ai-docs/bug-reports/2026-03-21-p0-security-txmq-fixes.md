---
id: BUG-2026-001
date: 2026-03-21
commit: 52fe4ab
modules: [blog-module-user, blog-module-article, blog-infrastructure, blog-db-migration]
category: [Careless Oversight, Architectural Flaw]
severity: HIGH
impact: code-review-caught
---

# Bug Review: P0 安全修復、Transaction+MQ 時序統一

## Summary

| Item | Detail |
|------|--------|
| ID | BUG-2026-001 |
| Date | 2026-03-21 |
| Commit | `52fe4ab` |
| Modules | blog-module-user, blog-module-article, blog-infrastructure, blog-db-migration |
| Category | Careless Oversight, Architectural Flaw |
| Severity | HIGH |
| Impact | code-review-caught |

## Symptoms

此 commit 修復 5 個 P0 問題，由兩次系統性 code review（2026-03-19 + 2026-03-21）發現：

1. **U-1（CRITICAL 安全）**：`deleteAccount()` 使用 `@RequestParam String password`，密碼出現在 URL query string，被 HTTP access log、瀏覽器歷史記錄
2. **FIN-3+4（HIGH 安全）**：`UserController` 3 個端點 + `AuthController.logout()` 缺少 `@PreAuthorize`，違反雙層保護規範
3. **FIN-2（HIGH 資料一致性）**：6 處在 `@Transactional` 內直接 `rabbitTemplate.convertAndSend()`，DB commit 失敗但 MQ 已發送時產生資料不一致
4. **FIN-1（CRITICAL 功能失效）**：`article.tagged` 事件有 Consumer 但無 Producer，標籤使用計數功能完全失效
5. **D-1（CRITICAL 資料完整性）**：`article_likes` / `comments` 的 `article_id` / `user_id` 缺少 `NOT NULL` 約束

## Root Cause Analysis

### 問題 1：deleteAccount 密碼洩漏（Careless Oversight）

設計 `deleteAccount` API 時，直接使用 `@RequestParam` 傳遞密碼，未意識到 DELETE 請求的 query parameter 會暴露在 URL 中。同一 Controller 的 `changePassword()` 已正確使用 `@RequestBody`，屬於不一致的設計遺漏。

### Before Fix

```java
@DeleteMapping("/me")
public ApiResponse<Void> deleteAccount(@AuthenticationPrincipal Long userId,
                                        @RequestParam String password) {
    userService.deleteAccount(userId, password);
    return ApiResponse.success();
}
```

### After Fix

```java
@PreAuthorize("isAuthenticated()")
@DeleteMapping("/me")
public ApiResponse<Void> deleteAccount(@AuthenticationPrincipal Long userId,
                                        @Valid @RequestBody DeleteAccountRequest request) {
    userService.deleteAccount(userId, request.getPassword());
    return ApiResponse.success();
}
```

### 問題 2：Transaction+MQ 時序（Architectural Flaw）

專案早期未建立統一的 Transaction+MQ 規範。File 模組後來正確實作了 `TransactionTemplate` + best-effort 模式，但 Article 和 User 模組仍沿用 `@Transactional` 內直接發 MQ 的錯誤模式。缺乏跨模組的架構約束導致模式不一致。

### Before Fix

```java
@Transactional
public void register(...) {
    User savedUser = userRepository.save(user);
    verificationTokenRepository.save(verificationToken);
    rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event); // DB 未 commit 就發 MQ
}
```

### After Fix

```java
public void register(...) {
    User savedUser = transactionTemplate.execute(status -> {
        User saved = userRepository.save(user);
        verificationTokenRepository.save(verificationToken);
        return saved;
    });

    try {
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, event); // DB 已 commit
    } catch (Exception e) {
        log.warn("MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
    }
}
```

### 問題 3：article.tagged 無 Producer（Careless Oversight）

`TagUsageConsumer` 和 `TagRabbitMqConfig` 完整定義了 Consumer 端，但開發時忘記在 `ArticleServiceImpl` 中加入 Producer 發送端。標籤使用計數功能從未運作過。

## Affected Files

**新增（3）**：
- `blog-db-migration/.../V12__add_not_null_constraints.sql`
- `blog-infrastructure/.../event/ArticleTagEvent.java`
- `blog-module-user/.../dto/request/DeleteAccountRequest.java`

**修改（43）**：
- `ArticleServiceImpl.java` — TransactionTemplate 重構 + ArticleTagEvent Producer
- `AuthService.java` — TransactionTemplate 重構 + routing key 常數化
- `UserController.java` — @RequestBody + @PreAuthorize
- `AuthController.java` — @PreAuthorize
- `ArticleRabbitMqConfig.java` — ROUTING_KEY_TAGGED 常數
- 其餘 38 檔為 JavaDoc 規範化和測試修改

## Timeline

| Event | Time / Commit |
|-------|--------------|
| Introduced | 初始開發階段（V1 schema, 各模組 Service 實作） |
| Discovered (Round 1) | 2026-03-19 — 第一次系統性 code review（67 項 finding） |
| Partial fixes | 2026-03-19 ~ 2026-03-21 — 12 commits 部分修復 |
| Discovered (Round 2) | 2026-03-21 — 第二次 5-Agent 協作 code review（40 項 finding） |
| Fixed | `52fe4ab` — 2026-03-21 |

## Preventive Measures

| Measure | Status |
|---------|--------|
| 更新 `ai-docs/code-standards.md` 加入 TransactionTemplate + best-effort MQ 模式為必遵規範 | DONE（2026-07-07，`ai-docs/code-standards.md` §「Transaction + MQ 時序」） |
| 更新 `ai-docs/security.md` 明確規定所有使用 `@AuthenticationPrincipal` 的端點必須加 `@PreAuthorize` | DONE（2026-07-07，`ai-docs/security.md` 原則 7、8） |
| 新增 ArchUnit 測試：驗證所有 `@Transactional` 方法不含 `rabbitTemplate.convertAndSend()` 呼叫 | **DONE**（2026-09-06，守衛 #6 `TransactionMqBoundaryTest`）。當初標 TODO 的理由是本 repo 無 ArchUnit 依賴，該理由自守衛 #5 上線後即不成立；本次 `/review-bugs` 發現此 TODO 已懸置近 6 個月 |
| 新增 ArchUnit 測試：驗證所有 Controller 非公開端點都有 `@PreAuthorize` | **DONE**（2026-09-06，守衛 #7 `EndpointAuthorizationTest`）。原則 7 的 8 個豁免端點以白名單記錄，每項綁定 `security.md` Public Endpoints 表的對應路徑，並有防腐測試禁止死條目 |
| Code review checklist 加入 Producer/Consumer 配對檢查 | DONE（2026-07-07，併入 `ai-docs/code-standards.md` §「Transaction + MQ 時序」的配對規則） |

## Lesson Learned

當專案有一個模組（File）已建立正確模式時，應立即將該模式文件化並推廣至所有模組——未文件化的最佳實踐等於沒有最佳實踐。
