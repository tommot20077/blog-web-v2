# Backlog: ArchUnit 架構守衛測試(源自 BUG-2026-001 預防措施)

**狀態**:部分完成 — 三個守衛中，項目 3（跨模組業務表守衛）已完成；項目 1（Transaction+MQ 守衛）待實作；項目 2（`@PreAuthorize` 守衛）阻塞中（見下）
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
   - 實作時修正了原始草稿的一個缺陷，且該缺陷比最初認知的更嚴重（見下方更正）：
     MyBatis 的 `@Select`/`@Update` 宣告的是 `String[] value()`（陣列型 annotation
     member）。依 JVMS §4.7.16.1，陣列型 annotation member **無論原始碼寫成單一
     字串 `@Select("...")` 還是陣列字面值 `@Select({...})`，classfile 一律以陣列
     編碼**——ArchUnit 讀到的 `value()` 因此永遠是 `String[]`，不存在「單一字串」
     這種另一型態。原始草稿誤以為 `@Select("...")` 是純量、只有 `@Select({...})`
     才是陣列，因而只用 `String.valueOf(a.get("value").orElse(""))` 讀值，這使守衛對
     **本 repo 每一個** `@Select`/`@Update`（不分寫法，不只是陣列字面值那 11 處）都
     完全失明，拿到的是 `[Ljava.lang.String;@...` 這種物件位址字串，不含任何 SQL
     內容。改為偵測 `Object[]` 並以空白 join 攤平後再比對，修正涵蓋全部
     `@Select`/`@Update`。
   - **更正（2026-09-05）**：上一版本條目曾誤述「ArchUnit 對 `@Select("...")` 單一
     字串 shorthand 回傳純量 `String`，僅 `@Select({...})` 陣列字面值回傳
     `Object[]`」，並據此認為需要兩種不同的反向驗證路徑。此說法經以本專案實際
     ArchUnit 1.3.0 jar 實測**證偽**：兩種寫法都回傳 `String[]`。原本注入的兩種
     反向驗證違規（文字區塊型單元素陣列、陣列字面值三元素陣列）走的其實是
     **同一條** `instanceof Object[]` 分支，差別只在陣列長度（1 vs 3），並非兩條
     不同程式路徑。其真正的證據價值在於證明「多元素攤平＋以空白 join」正確，
     而不是證明存在「單一字串 vs 陣列」兩種不同型態。2026-09-05 已補第三種
     反向驗證形式（單一字串內含換行的 text block，同時驗證 D2 的空白容忍度），
     三種違規都能被抓到並在失敗訊息中指名，驗證後已刪除，回歸綠燈。
     給未來寫類似守衛的人的通用教訓：**任何宣告為陣列型別的 annotation member，
     讀出來一律是陣列，與呼叫端語法（單一字串或陣列字面值）無關。**
     詳見 `.superpowers/sdd/2026-09-04-cross-module-set-predicate-plan/task-7-report.md`
     （原始實作與驗證）與 `task-7_5-report.md`（技術結論更正、D1/D2 假陰性修補與
     三形式反向驗證）。

> 項目 2（`@PreAuthorize` 守衛）仍**阻塞**：白名單來源 `security.md` Public Endpoints 表
> 與 `SecurityConfig` 三處對不上（SEC-27），且受 D1 收窄決策影響。
> 順序須為：拍板 D2 修文件 → 拍板 D1 → 才寫得出守衛。見 `audits/2026-09-04/triage.md` T3。

## 驗收條件

- 三個測試在現有 codebase 上全綠(如有既有違規,先修違規)
- 故意注入一處違規時測試必須紅
- 測試命名與 `@DisplayName` 遵守 `ai-docs/testing-standards.md`
