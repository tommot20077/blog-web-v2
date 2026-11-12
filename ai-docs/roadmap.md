# Roadmap（2026-07 全端體檢定案）

> **來源**：2026-07-02 全端架構體檢（後端 + 前端 + 契約對齊）後與 Yuan 討論定案。
> **維護規則**：每輪完成後更新對應區塊狀態；新增決策附討論日期。

---

## 總評摘要

- 兩 repo 工程成熟度高於 side project 平均：模組邊界（編譯期零 repository/entity 跨模組引用）、測試文化（後端 ~1,430 測試方法、前端 146 unit test 檔 + 50 E2E spec、PR 級真後端整合測試）、安全設計（ES256 stateful JWT、memory-only access token + HttpOnly refresh cookie）皆為強項。
- 最大特徵：**「基礎設施已建好但沒用滿」**——IdempotencyService 僅 1/9 consumer 使用、MQ retry interceptor 被手動 try/catch 抵銷、前端 OpenAPI 稽核工具未進 CI、version 模組前端 service 已寫但 UI 零引用。
- 無 Critical 架構問題；唯一 Critical 為安全語意漏洞（見輪 0-A）。

---

## 輪次規劃

### 輪 0──地基修復（進行中）

**工作包 A（後端，安全）**
- [ ] A1：`changePassword` 同步撤銷 refresh token（刪 Redis refresh ZSet）
- [ ] A2：`resetPassword` 同步撤銷 refresh token
- [ ] A3：`/auth/refresh` 驗證 token version（現況完全不驗，`AuthController.refresh`）
- [ ] A4：`X-Forwarded-For` 不再無條件信任第一節（可偽造繞過 IP 限流）

**工作包 C（後端，MQ 語意）**──決策：採「retry 接手」策略
- [ ] C1：抽共用 consumer wrapper，收斂 9 份 try/ack/catch/nack 樣板
- [ ] C2：consumer 改拋例外，讓既有 stateful retry interceptor（3 次指數退避）生效
- [ ] C3：所有事件補 `eventId` 欄位
- [ ] C4：計數型 consumer（view count、tag usage、version snapshot）接上 `IdempotencyService`

### 輪 1──創作者統計後端

- 新增日粒度聚合表（如 `article_stats_daily`）+ consumer / rollup job + 統計端點
- 前端 `StatsView`（/my-stats）由假資料切換為真實 API（UI 已完成）
- 穿插：SEO 層次一──文章頁 `useHead`（title/OG/JSON-LD）+ 後端 RSS / sitemap 端點

### 輪 2──站內通知中心

- 前置：為 comment / reading 模組補事件發佈端（現況兩模組不發事件）
- notification 模組：migration、domain、consumer（冪等）、未讀數、列表/已讀 API
- 前端：鈴鐺 + 下拉 + 通知頁；V18 五個通知偏好欄位在此生效
- 二期選項：SSE/WebSocket 即時推播

### 輪 3──伺服器端 meta 注入

- 文章路由的 document 請求由後端回傳「注入該文章 OG/meta 的 SPA shell」，解決社群分享卡片（社群爬蟲不執行 JS）
- SSR 不排期，留作日後流量驗證後的選項

### 並行軌（穿插進行）

- **工作包 B（前端）**：`ApiError { code, httpStatus }` 保留後端錯誤碼、10 個 real service 停止吞錯、composable 統一 loading/error/data 三態
- 小勝利池：版本歷史 UI（後端與前端 service 皆完成，僅缺 UI）、分享按鈕（現 disabled）、`FileController` 回傳 DTO（現洩漏 `FileMetadata.storagePath`）、書籤/系列 2N+1 修復（`ArticleQuerySubService.getArticleSummariesByIds`）、文件斷鏈補齊（CLAUDE.md 引用之 code-standards / git-convention / testing-standards / security 四文件不存在；architecture.md 僅列 4/10 模組；schema.md 枚舉漂移）

---

## 決策紀錄（2026-07-02）

| # | 議題 | 決策 |
|---|------|------|
| D1 | 修復 vs 新功能順序 | A+C 必修先行，B 與功能並行 |
| D2 | MQ 失敗語意 | retry 接手（棄 fail-fast-to-DLQ 與混合制）；搭配 eventId + 冪等使 redelivery 無害 |
| D3 | 新功能選擇 | 統計、通知兩道都做；統計先（中規模、成果立刻可見），通知後（大規模、驗證 MQ 語意） |
| D4 | SEO 路線 | 層次一（useHead + RSS/sitemap）近期；伺服器端 meta 注入排輪 3；SSR 不排期 |

設計原則：輪 0 → 輪 1 → 輪 2 構成驗證鏈——C 包修好的 MQ 語意先被統計 consumer 小規模實戰，再被通知中心大規模使用。

---

## 體檢發現索引（未排入上述輪次者）

| 嚴重度 | 發現 | 位置 |
|--------|------|------|
| Medium | 可觀測性空白：無 micrometer/tracing，事件發送失敗靜默（漂移不可見） | `ArticleEventPublisher`、application.yaml actuator |
| Medium | Redis 為認證硬依賴無降級；Search 無 ES 故障降級 | `JwtAuthenticationFilter`、`SearchServiceImpl` |
| Medium | reading/series/version 繞過 facade 直接 import article 內部 service | `BookmarkController`、`SeriesService`、`VersioningService` |
| Medium | 前端 ArticleList 一次抓 1000 筆 client-side 過濾；後端列表缺 `categories` 欄位 | `ArticleList.vue:25`、`ArticleSummaryResponse` |
| Medium | 前端無 ESLint；自建 OpenAPI 稽核未進 CI | `package.json`、`ci.yml` |
| Low-Med | JwtService 手刻 90 行 EC 數學；AuthService 543 行待拆 | `JwtService.java:116-203` |
| Low | prod 缺 `jwt.private-key` 時靜默動態生成金鑰（應 fail-fast） | `JwtService.java:76-81` |
| Low | 事件類位置不一致（部分在 infrastructure、部分在 article 模組） | `ArticleDeletedEvent` 等 |
| Low | rate limiting 僅覆蓋 auth，寫操作（comment/like）無 throttle | — |
| Low | 遺留物：前端 `old-src/`、`stores/counter.ts`、`HomeView.vue` 空殼 | — |
