# Bug Reports Index

## Statistics

### Category Distribution

| Category | Count | % |
|----------|-------|---|
| Careless Oversight | 1 | 33% |
| Knowledge Gap | 0 | 0% |
| Architectural Flaw | 1 | 33% |
| Requirement Misunderstanding | 0 | 0% |
| Race Condition | 1 | 33% |
| Legacy Tech Debt | 0 | 0% |
| **Total** | **2** | **100%** |

### Module Hotspots

| Module | Bug Count | Most Common Category |
|--------|-----------|---------------------|
| blog-module-user | 2 | Careless Oversight, Race Condition |
| blog-infrastructure | 1 | Race Condition |
| blog-module-article | 1 | Architectural Flaw |

### Severity Distribution

| Severity | Count | % |
|----------|-------|---|
| HIGH | 2 | 100% |
| MEDIUM | 0 | 0% |
| LOW | 0 | 0% |

## Report List

| ID | Date | Module | Category | Severity | Summary | Link |
|----|------|--------|----------|----------|---------|------|
| BUG-2026-001 | 2026-03-21 | user, article, infrastructure, db-migration | Careless Oversight, Architectural Flaw | HIGH | P0 安全修復（密碼洩漏、@PreAuthorize 遺漏）+ Transaction+MQ 時序統一 + article.tagged Producer 補齊 | [Report](2026-03-21-p0-security-txmq-fixes.md) |
| BUG-2026-002 | 2026-07-18 | user, infrastructure | Race Condition | HIGH | session 撤銷在交易提交前清 Redis，併發回填未提交舊版本使撤銷失效 + refresh 角色回填遺漏 | [Report](2026-07-18-session-revocation-timing-refresh-role.md) |

## Review Log

_No `/review-bugs` review has been performed yet._
