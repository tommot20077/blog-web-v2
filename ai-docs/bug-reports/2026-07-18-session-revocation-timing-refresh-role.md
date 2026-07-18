---
id: BUG-2026-002
date: 2026-07-18
commit: 9344e08
modules: [blog-module-user, blog-infrastructure]
category: [Race Condition]
severity: HIGH
impact: code-review-caught
---

# Bug Review: 修正 session 撤銷時機與 refresh 角色回填快取

## Summary

| Item | Detail |
|------|--------|
| ID | BUG-2026-002 |
| Date | 2026-07-18 |
| Commit | `9344e08` |
| Modules | blog-module-user, blog-infrastructure |
| Category | Race Condition |
| Severity | HIGH |
| Impact | code-review-caught（PR #45 `/code-review high` 階段攔截，未進 staging/prod） |

## Symptoms

改密碼 / 重設密碼 / 刪帳號後，被盜或應被登出的 **Access Token 在併發情境下仍可能通過驗證**，
撤銷形同失效：

- `SessionRevoker.revokeAllSessions` 由 `@Transactional` 方法呼叫，Redis 清理在交易 **提交前** 執行。
- 併發請求（如使用者其他裝置持舊 token）在此空窗觸發 `JwtAuthenticationFilter` 的 cache-miss 回填，
  以 READ_COMMITTED 讀到 **尚未提交的舊 tokenVersion（v1）** 寫回 auth hash。
- 主交易提交新版本（v2）後，auth hash 停留 v1 → 舊 token（v1）匹配快取 → 通過驗證，直到 7 天 TTL。
- `deleteAccount` 因不遞增 tokenVersion，缺少版本背板，撤銷完全仰賴此清理，受害更嚴重。

附帶缺陷（同 commit 修復）：`JwtAuthenticationFilter` 回填 auth hash 時只寫 `version`/`status` 不寫
`role`，導致 `/refresh` 於回填後每次讀不到 role 而永久落 DB fallback（角色降級修復不完整）。

## Root Cause Analysis

核心是 **交易與非交易資源（Redis）的提交時序** 沒有對齊：Redis 不參與 Spring 交易，若在交易內、
commit 前就變更 Redis，任何併發讀取都可能在提交前把「即將被作廢的舊狀態」回填快取，使交易後的清理
被覆蓋。正解是把 Redis 清理掛在 `TransactionSynchronization.afterCommit`，保證回填讀到的必為已提交
的新版本。

副因是 auth hash 的寫入邏輯散落多處（login 寫三欄、filter 回填只寫兩欄），role 以特例方式補在 login，
未一致化，因此任何 cache-miss 回填路徑都會遺漏 role。

### Before Fix

```java
// SessionRevoker — 交易提交前即清 Redis（併發回填會覆蓋）
public void revokeAllSessions(Long userId) {
    redisTemplate.delete(RedisKeyConstant.getUserAuthKey(userId));
    redisTemplate.delete(RedisKeyConstant.getUserRefreshKey(userId));
}

// JwtAuthenticationFilter — 回填遺漏 role
redisTemplate.opsForHash().put(redisKey, RedisKeyConstant.FIELD_VERSION, currentVersion);
redisTemplate.opsForHash().put(redisKey, RedisKeyConstant.FIELD_STATUS, currentStatus);
redisTemplate.expire(redisKey, RedisKeyConstant.USER_AUTH_TTL_DAYS, TimeUnit.DAYS);
```

### After Fix

```java
// SessionRevoker — 交易進行中則延遲至 afterCommit
public void revokeAllSessions(Long userId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                doRevoke(userId);
            }
        });
    } else {
        doRevoke(userId);
    }
}

private void doRevoke(Long userId) {
    redisTemplate.delete(RedisKeyConstant.getUserAuthKey(userId));
    redisTemplate.delete(RedisKeyConstant.getUserRefreshKey(userId));
}

// JwtAuthenticationFilter — 回填一併寫入 role
redisTemplate.opsForHash().put(redisKey, RedisKeyConstant.FIELD_VERSION, currentVersion);
redisTemplate.opsForHash().put(redisKey, RedisKeyConstant.FIELD_STATUS, currentStatus);
redisTemplate.opsForHash().put(redisKey, RedisKeyConstant.FIELD_ROLE, userDetail.role());
redisTemplate.expire(redisKey, RedisKeyConstant.USER_AUTH_TTL_DAYS, TimeUnit.DAYS);
```

## Affected Files

- `blog-module-user/src/main/java/dowob/xyz/blog/module/user/service/SessionRevoker.java`
- `blog-module-user/src/test/java/dowob/xyz/blog/module/user/service/SessionRevokerTest.java`（新增）
- `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/security/JwtAuthenticationFilter.java`
- `blog-infrastructure/src/test/java/dowob/xyz/blog/infrastructure/security/JwtAuthenticationFilterTest.java`

## Timeline

| Event | Time / Commit |
|-------|--------------|
| Introduced | `963a1f8`（改密碼/重設密碼撤銷所有 session）將「就地更新 version」改為「刪 auth hash 靠 filter 回填」，引入回填 race；`deleteAccount` 的刪除路徑則為既有 |
| Discovered | 2026-07-18，PR #45 `/code-review high` 逐行審查 `SessionRevoker` 交易邊界時發現 |
| Fixed | `9344e08` |

## Preventive Measures

| Measure | Status |
|---------|--------|
| 於 `ai-docs/security.md` 增訂規則：與 DB 交易綁定的 session/快取撤銷必須走 `afterCommit`，禁止在 `@Transactional` 內、commit 前變更 Redis | DONE（2026-07-18, ai-docs/security.md） |
| 建立 guard/測試，偵測「在 `@Transactional` 內直接寫 Redis 撤銷鍵」與「auth hash 寫入欄位不一致」 | TODO（backlog: ai-docs/backlog/2026-07-18-revocation-after-commit-guard.md） |

## Lesson Learned

非交易資源（Redis）的作廢必須發生在 DB 交易 **提交之後**，否則併發回填會用「即將作廢的舊狀態」覆蓋清理，
讓撤銷靜默失效。
