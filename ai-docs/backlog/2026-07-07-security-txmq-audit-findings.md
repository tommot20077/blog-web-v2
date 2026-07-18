# Backlog: 制度使用測試發現的疑似違規(2026-07-07)

**狀態**:✅ **全數修畢,可關閉**(2026-07-16)。#1 = 07-14 檔的 C2 → PR #46;#2 = H7 → PR #46;#3 = H5 → PR #49(Yuan 裁定選項 a);#4 早於 07-07 完成。細節與後續一律以 `2026-07-14-full-review-findings.md` 的「📌 交接記錄」為單一入口。
**來源**:2026-07-07 制度落地時的 fresh-model 稽核(唯讀掃描,未修任何程式碼)。以下為該次稽核回報,尚未經第二次驗證。

## 1. HIGH:`applyRestoreContent` 在交易內發 MQ(BUG-2026-001 FIN-2 同型)

`blog-module-article/.../facade/ArticleFacadeImpl.java:381-406`:方法標註 `@Transactional`,`articleRepository.save()`(:396)後在**同一未 commit 交易內**呼叫 `articleEventPublisher.publishContentChanged(...)`(:402)與 `publishUpdated(...)`(:404),兩者直接 `rabbitTemplate.convertAndSend`(`ArticleEventPublisher.java:62`、`:96`)。
→ 修法:比照 `ArticleCommandSubService` 的正確模式(`transactionTemplate` 收斂 DB,MQ 移到 commit 後 best-effort)。TDD。

## 2. MEDIUM(需判斷):讀路徑 `@Transactional` 內發 viewed 事件

`ArticleServiceImpl.java:46,59`(`getArticleByUuid`/`getArticleBySlug`,`@Transactional` 非 readOnly)→ `ArticleViewSubService.java:30` → `publishViewed`(`ArticleEventPublisher.java:203`)。作用域內無 DB 寫入(只碰 Redis),「commit 失敗但 MQ 已送」風險實際不成立,但字面違反規範。
→ 待判斷:這兩個 `@Transactional` 是否必要(lazy-loading?);若不必要,移除註解即同時消掉字面違規。

## 3. ~~MEDIUM:`/api/v1/series/**` URL 層與 JavaDoc 矛盾(需 Yuan 決策)~~ → ✅ DONE(PR #49,2026-07-16)

`SecurityConfig.java:73-102` **沒有** `/api/v1/series/**` 的 `permitAll`,該路徑落入 `anyRequest().authenticated()`;但 `SeriesController` JavaDoc(:35-36)宣稱「公開列表/公開詳情」,且 `getSeriesDetail(slug, null)` 的匿名分支現況是死路徑。
→ 二選一:(a) SecurityConfig 補 `GET /api/v1/series/**` permitAll + security.md Public Endpoints 表同步 + `SeriesController.get()` 依原則 7 豁免條件補 JavaDoc;(b) 系列本來就要登入 → 改 JavaDoc + 補 `@PreAuthorize("isAuthenticated()")`。**上線前必須擇一**。

> **✅ Yuan 於 2026-07-16 裁定採 (a)**,已由 PR #49 實作(僅開放 GET,寫入維持認證);`security.md` 與前端 `api-contract.md` 已同步。決定性證據:前端公開頁 `/tags` 打 `GET /api/v1/series` 拿 401,被 `Promise.allSettled` 靜默降級 → 匿名訪客看不到系列區塊。**#49 stacked 在 #47(H3 過濾)之上,不可先於 #47 合併**。詳見 `2026-07-14-full-review-findings.md` 的 H5 節。

## 4. ~~LOW:security.md Public Endpoints 表列了不存在的規則~~ → DONE(2026-07-07)

已依證據修正(`SecurityConfigTest.java:235`「已收窄 permitAll」證明是刻意收緊):`security.md` 表移除 `users/**`、補上 `categories/**`、`recommend/**`;前端 `api-contract.md` 同步並加註 `users/**` 需認證。

## 驗收條件(整體)

- 每項先由第二個 agent 驗證發現屬實再動手(驗證不自驗)
- 修復後 `grep` + 呼叫鏈追蹤複查全綠;#1 需補迴歸測試
- security.md Public Endpoints 表與 `SecurityConfig.java` 一對一對得上
