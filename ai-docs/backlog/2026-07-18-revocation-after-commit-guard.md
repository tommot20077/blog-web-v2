# Backlog: session/快取撤銷「交易後執行」的自動化 guard

- **建立日期**: 2026-07-18
- **來源**: [BUG-2026-002](../bug-reports/2026-07-18-session-revocation-timing-refresh-role.md)（Race Condition, HIGH）
- **類型**: program-type 預防措施（測試 / 靜態檢查）

## 動機

BUG-2026-002 的根因是在 `@Transactional` 內、commit 前變更 Redis 撤銷鍵，併發回填會用未提交的
舊狀態覆蓋清理，使撤銷靜默失效。已於 `SessionRevoker` 修為 `afterCommit`，並在 `ai-docs/security.md`
增訂規則。但目前僅靠人工 review 把關，需要自動化 guard 防止同類問題再次滑入。

## 待辦項目

1. **併發回歸測試**：撰寫整合測試模擬「交易未提交時的併發 cache-miss 回填」，斷言撤銷後舊 token
   版本不會殘留於 auth hash（驗證 `afterCommit` 語意，而非僅驗證單執行緒下鍵被刪）。
2. **靜態 guard（併入既有 ArchUnit 待辦）**：偵測「在 `@Transactional` 方法內直接呼叫
   `redisTemplate.delete(...)` / `opsForHash().put(...)` 撤銷或改寫 auth/refresh 鍵」的可疑模式，
   引導改走 `SessionRevoker` 單一入口。可併入 [2026-07-07-archunit-guards.md](2026-07-07-archunit-guards.md)。
3. **auth hash 欄位一致性**：確保所有寫入 `user:auth:{id}` 的路徑（login / filter 回填）欄位集合一致
   （version / status / role），避免再度出現「某路徑漏寫 role」的分歧。

## 驗收標準

- [ ] 存在一支併發情境的整合測試，能在「撤銷在 commit 前執行」的舊實作下失敗、在 `afterCommit` 實作下通過。
- [ ] ArchUnit（或等效）規則能標記「@Transactional 內直接寫 auth/refresh Redis 鍵」的違規。
- [ ] 有測試斷言 login 與 filter 回填寫入 auth hash 的欄位集合一致。
