# Bug Reports Index

## Statistics

### Category Distribution

| Category | Count | % |
|----------|-------|---|
| Careless Oversight | 2 | 40% |
| Knowledge Gap | 0 | 0% |
| Architectural Flaw | 2 | 40% |
| Requirement Misunderstanding | 0 | 0% |
| Race Condition | 1 | 20% |
| Legacy Tech Debt | 0 | 0% |
| **Total** | **5** | **100%** |

> 計數以「報告 × 分類」為單位（一份報告可掛多個分類），故總數大於報告份數（3）。

### Module Hotspots

| Module | Bug Count | Most Common Category |
|--------|-----------|---------------------|
| blog-module-article | 2 | Architectural Flaw |
| blog-module-user | 2 | Careless Oversight, Race Condition |
| blog-infrastructure | 2 | Race Condition, Architectural Flaw |
| blog-module-comment | 1 | Careless Oversight, Architectural Flaw |
| blog-module-reading | 1 | Careless Oversight, Architectural Flaw |
| blog-module-series | 1 | Careless Oversight, Architectural Flaw |

### Discovery Stage

| Stage | Count | % |
|-------|-------|---|
| code-review-caught | 3 | 100% |
| dev / staging / production | 0 | 0% |

> **這個 100% 不是品質指標，是觀測天花板。** `findings.md` **ARCH-16** 記載本專案
> 無 metrics／tracing，DATA-01／DATA-04／DATA-10 等已知一致性問題在生產
> **沒有任何方式能被主動發現，只能等使用者回報**。因此生產缺陷不會變成 bug report——
> 不是因為沒有，而是偵測不到。要讓這欄有意義，先補 ARCH-16。

### Severity Distribution

| Severity | Count | % |
|----------|-------|---|
| HIGH | 2 | 67% |
| MEDIUM | 1 | 33% |
| LOW | 0 | 0% |

## Report List

| ID | Date | Module | Category | Severity | Summary | Link |
|----|------|--------|----------|----------|---------|------|
| BUG-2026-001 | 2026-03-21 | user, article, infrastructure, db-migration | Careless Oversight, Architectural Flaw | HIGH | P0 安全修復（密碼洩漏、@PreAuthorize 遺漏）+ Transaction+MQ 時序統一 + article.tagged Producer 補齊 | [Report](2026-03-21-p0-security-txmq-fixes.md) |
| BUG-2026-002 | 2026-07-18 | user, infrastructure | Race Condition | HIGH | session 撤銷在交易提交前清 Redis，併發回填未提交舊版本使撤銷失效 + refresh 角色回填遺漏 | [Report](2026-07-18-session-revocation-timing-refresh-role.md) |
| BUG-2026-003 | 2026-09-06 | article, comment, reading, series, infrastructure | Careless Oversight, Architectural Flaw | MEDIUM | 集合述詞方法把界限寫進 JavaDoc 而非程式，未沿用同一個類裡既有的 IN 切批防線；連帶查出分頁 size 全 repo 零夾界 | [Report](2026-09-06-in-clause-bind-parameter-batching.md) |

## Review Log

### 2026-09-06 Review

- Reports analysed: 3（BUG-2026-001／002／003）
- Key findings:
  - **Pattern 1（重複 2 次且距離縮短）**：正確模式已存在於同一 codebase，新程式碼卻沒沿用。
    BUG-001 是跨模組（File 已有 TransactionTemplate 模式，Article／User 沒沿用），
    BUG-003 是**同一個類**（`filterPublishedUuids` 已有切批防線）。
    BUG-001 當時下的對策「把最佳實踐文件化並推廣」**沒能擋住它**。
  - **Pattern 2（貫穿三份）**：防線長期停留在文件層。BUG-001 的兩條 ArchUnit 測試
    自 2026-03-21 起懸置近 6 個月；其 TODO 理由（repo 無 ArchUnit 依賴）
    自守衛 #5 上線後即不成立，但無人回頭檢查。
  - **Pattern 3**：100% code-review-caught 反映的是觀測天花板（ARCH-16），非品質指標。
  - **Pattern 4**：BUG-001 兩條為裸 TODO，無 backlog 目的地，違反 `maintenance.md` §1。
- Improvements applied:
  - 落地守衛 #6 `TransactionMqBoundaryTest`：`@Transactional` 涵蓋的方法內不得直接
    `rabbitTemplate.convertAndSend`；含反向驗證，證明方法層與**類層**兩種標註形式都抓得到。
  - 落地守衛 #7 `EndpointAuthorizationTest`：取用 `@AuthenticationPrincipal` 的 handler
    必須有 `@PreAuthorize`；原則 7 的 8 個豁免以白名單記錄，**每項綁定 `security.md`
    Public Endpoints 表的對應路徑**，並加防腐測試禁止死條目累積。
  - `judgment.md` 新增 §8「寫新程式碼前先找同類的既有防線」（對應 Pattern 1）
    與 §9「界限要寫進程式，不是只寫進 JavaDoc」（對應 BUG-003 根因）。
  - BUG-001 兩條裸 TODO → DONE；BUG-003 與其 backlog 中「ArchUnit 不可行」的
    過寬說法已修正（不可行的只有 controller 參數名那條）。
  - 本表新增 Discovery Stage 欄與觀測天花板註記。
- Outstanding TODO：BUG-002 revocation guard（backlog 已連結）、
  BUG-003 mapper 端切批守衛（backlog 已連結，評估結論已修正為「很可能可行」）。
