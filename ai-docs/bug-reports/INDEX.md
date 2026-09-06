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

_No `/review-bugs` review has been performed yet._
