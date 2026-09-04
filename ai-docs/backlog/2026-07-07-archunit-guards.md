# Backlog: ArchUnit 架構守衛測試(源自 BUG-2026-001 預防措施)

**狀態**:TODO
**來源**:`ai-docs/bug-reports/2026-03-21-p0-security-txmq-fixes.md` Preventive Measures(程式類,2026-07-07 制度化時分流至此)

## 待實作項目

1. **Transaction+MQ 守衛**:ArchUnit 測試驗證所有 `@Transactional` 方法(含 class-level)不得呼叫 `rabbitTemplate.convertAndSend()`。
   - 規範依據:`ai-docs/code-standards.md` §「Transaction + MQ 時序」
   - 建議位置:`blog-common` 或 `blog-start` 的 architecture test 套件
2. **@PreAuthorize 守衛**:ArchUnit 測試驗證所有 Controller 中非公開端點(不在 `ai-docs/security.md` Public Endpoints 表內)的 handler method 都有 `@PreAuthorize`。
   - 規範依據:`ai-docs/security.md` 原則 1、7
3. **跨模組業務表守衛（守衛 #5）** — ✅ **DONE**（2026-09-05，隨 ARCH-13 修復一併引入）
   - `blog-start/src/test/java/dowob/xyz/blog/architecture/CrossModuleBoundaryTest.java`
   - ArchUnit 依賴本次首度引入（`blog-start/pom.xml`，`archunit-junit5:1.3.0`），項目 1、2 現在有現成基礎設施可用
   - 規範依據：`ai-docs/architecture.md` §Cross-Module Boundary Rules
   - 實作時修正了原始草稿的一個缺陷：`@Select({...})` 陣列型 annotation value（MyBatis
     動態 SQL `<script>`/`<foreach>` 慣用形式，本 repo 現有 11 處）若只用
     `String.valueOf(a.get("value").orElse(""))` 讀取，拿到的是 `[Ljava.lang.String;@...`
     而非 SQL 內容，對這 11 處查詢完全失明。改為偵測 `Object[]` 並以空白 join 攤平後再比對。
   - 反向驗證同時注入文字區塊型與陣列型兩種違規（`SeriesMapper` 暫時方法），
     確認守衛對兩種形式都能抓到並在失敗訊息中指名，驗證後已刪除，回歸綠燈。
     詳見 `.superpowers/sdd/2026-09-04-cross-module-set-predicate-plan/task-7-report.md`。

> 項目 2（`@PreAuthorize` 守衛）仍**阻塞**：白名單來源 `security.md` Public Endpoints 表
> 與 `SecurityConfig` 三處對不上（SEC-27），且受 D1 收窄決策影響。
> 順序須為：拍板 D2 修文件 → 拍板 D1 → 才寫得出守衛。見 `audits/2026-09-04/triage.md` T3。

## 驗收條件

- 三個測試在現有 codebase 上全綠(如有既有違規,先修違規)
- 故意注入一處違規時測試必須紅
- 測試命名與 `@DisplayName` 遵守 `ai-docs/testing-standards.md`
