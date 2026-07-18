# Backlog: ArchUnit 架構守衛測試(源自 BUG-2026-001 預防措施)

**狀態**:TODO
**來源**:`ai-docs/bug-reports/2026-03-21-p0-security-txmq-fixes.md` Preventive Measures(程式類,2026-07-07 制度化時分流至此)

## 待實作項目

1. **Transaction+MQ 守衛**:ArchUnit 測試驗證所有 `@Transactional` 方法(含 class-level)不得呼叫 `rabbitTemplate.convertAndSend()`。
   - 規範依據:`ai-docs/code-standards.md` §「Transaction + MQ 時序」
   - 建議位置:`blog-common` 或 `blog-start` 的 architecture test 套件
2. **@PreAuthorize 守衛**:ArchUnit 測試驗證所有 Controller 中非公開端點(不在 `ai-docs/security.md` Public Endpoints 表內)的 handler method 都有 `@PreAuthorize`。
   - 規範依據:`ai-docs/security.md` 原則 1、7

## 驗收條件

- 兩個測試在現有 codebase 上全綠(如有既有違規,先修違規)
- 故意注入一處違規時測試必須紅
- 測試命名與 `@DisplayName` 遵守 `ai-docs/testing-standards.md`
