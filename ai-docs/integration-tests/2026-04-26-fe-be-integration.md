# 前後端整合驗證紀錄 — 2026-04-26

## 環境

| 項目 | 值 |
|---|---|
| 執行日期 | 2026-04-26 |
| 後端 commit | (見執行時 git rev-parse HEAD) |
| 後端 profile | dev (`http://localhost:9010`) |
| 前端 commit | (見執行時) |
| 前端 base URL | `http://127.0.0.1:5500` (`VITE_USE_MOCK=false`) |
| 共享 infra | `10.0.0.214` (PostgreSQL:30120, Redis:30121, RabbitMQ:30127, ES:30124, MinIO:30130) |
| Test users | reader@test.local / author@test.local / admin@test.local（密碼 `Test1234!`） |
| Seed data | 2 categories (Frontend, Backend), 7 published articles |

## Bootstrap 已驗證

- ✅ 後端 health UP
- ✅ DB 連線正常（PostgreSQL 18.3）
- ✅ 三帳號可登入，access token 回傳格式正確（5 個 0 success code）
- ✅ JWT claims 含 `version`（不是 `v`）
- ✅ 前端 dev server UP

## 狀態圖例

- ✅ 通過
- ❌ 失敗（已記錄 Issue）
- ⚠️ 通過但有 improvement
- ⏭️ 跳過（記錄原因）

---

## 領域 A：Auth

| ID | 步驟摘要 | 預期 | 實際 | 狀態 | Issue# |
|---|---|---|---|---|---|
| A1 | 註冊新 email/username/password | 回 `{code:00000,data:null}` | 同預期 | ✅ | |
| A2 | 註冊重複 email | `code:A0106` 訊息「該信箱已被註冊」 | 同預期 | ✅ | |
| A3a | 註冊密碼 4 字元 | validation 失敗 | `code:400` 「密碼長度須為 6-50 字元」 | ⚠️ | #2 |
| A3b | 註冊缺 nickname | validation 失敗 | `code:400` 「nickname: 暱稱不能為空」 | ✅ | |
| A4 | reader/author/admin 各登入 | 三角色 JWT claims role 正確 | role/type/version/sub/iat/exp 完整、role 對應 | ✅ | |
| A4b | 用 username 登入 | 也通 | OK | ✅ | |
| A5 | 登入錯誤密碼 | 失敗 | `code:A0102` 「帳號或密碼錯誤」 | ✅ | |
| A6 | 登入未激活帳號 | 提示驗證 | `code:A0111` 「請先驗證電子信箱」 | ✅ | |
| A7 | Access token 過期觸發 refresh | 前端自動 refresh + retry | API 層：`POST /auth/refresh` 用 cookie 回新 access ✅ | UI 層待跑 | |
| A8 | Refresh token 過期 | redirect /login | UI 層待跑 | ⏭️ | |
| A9 | Logout | cookie 清 + Pinia 清 | API：Set-Cookie max-age=0 ✅ | UI 層待跑 | |
| A10 | 忘記密碼 | 不洩漏 email 是否存在 | `code:00000` 即使 email 不存在也成功 | ✅ | |
| A11 | GuestOnly 守衛 | redirect / | UI 層 — 已有 E2E 覆蓋 | ✅ | |
| Aa | GET /users/me | User type 對齊 | 後端多回 `website`、`socialLinks` | ⚠️ | #3 |
| Ab | Refresh token cookie flags | dev http 可用 | `Secure;SameSite=Strict;Path=/api/v1/auth` 寫死 secure=true | ❓ | #1 |

**E2E 補強目標**：A6, A7, A8（缺 spec）

**API 層 sanity 結論**：所有 endpoint 都通；契約大致對齊。三個觀察：
1. **#1 (待觀察)** Cookie `secure=true` 寫死於 `AuthController.java:81,160` — Chrome 對 localhost http 寬鬆可能不影響，但跨瀏覽器不一致。Playwright/Chromium 應通；Firefox/Safari 可能丟 cookie。
2. **#2 (improvement)** 密碼 validation 只檢長度（6-50），無大寫/特殊字元複雜度要求 — 安全性弱。
3. **#3 (improvement)** 前端 `User` type 缺 `website`、`socialLinks` 欄位 — extra field 不會壞，但無法 type-safe 取用。

---

## 領域 F：文章創作

| ID | 步驟摘要 | 預期 | 實際 | 狀態 | Issue# |
|---|---|---|---|---|---|
| F1 | POST /articles 建草稿 | 回 EditorArticleResponse 含 uuid | OK，status=DRAFT | ✅ | |
| F2 | 多分類（POST 帶多 categoryIds） | 後端正確存多筆 | (UI 未測，API 接受 array) | ⏭️ | |
| F3 | GET /tags/suggest?q=ja | 候選詞 | 回 `[]`（fixture 缺資料） | ⏭️ | #9 |
| F4 | POST /articles 後 UI URL 含 uuid | 已有 e2e 覆蓋 | author-writes-article.spec.ts pass | ✅ | |
| F5 | GET /articles/{uuid}/edit + 載入草稿 | DRAFT 也回 200 + 完整 categories/tags | 修復前壞，修復後 spec pass (commit 71b797a) | ✅ | #4 |
| F6 | POST /articles/{uuid}/submit | status PENDING_REVIEW | OK | ✅ | |
| F7 | GET /articles/me（含 status filter） | 分頁 + records | OK，含 PageResult.records | ✅ | |
| F8 | 從 /my-articles 進 /editor/{uuid} | 帶入內容 | 與 F5 同一 fix 一併解決 | ✅ | #4 |
| F9 | DELETE /articles/{uuid} | 刪除（即使 PENDING_REVIEW） | OK | ✅ | |
| F10 | 邊界：超長/特殊字元/XSS | DOMPurify 擋 | (UI 未測) | ⏭️ | |
| Fa | EditorView onMounted 只跑一次 | router.push /editor/{uuid2} 應 reload | onMounted 不會 re-trigger，缺 watcher | ⚠️ | #10 |

**E2E 新增**：`editor-edit-existing.spec.ts` (F5/F8) — passing。
**E2E 補強目標**：F2, F3 (after fixture has tags), F10。

---

## 領域 H：審核

| ID | 步驟摘要 | 預期 | 實際 | 狀態 | Issue# |
|---|---|---|---|---|---|
| H1 | GET /admin/articles/pending (admin) | PageResult.records | OK，有 PendingArticle 含 authorNickname | ✅ | |
| H1b | author 訪 /admin/articles/pending | 403 | 回 default Spring JSON `{timestamp,status,error,path}` 不是 ApiResponse | ⚠️ | #12 |
| H2 | GET /admin/articles/pending/count | 回 number | 後端 endpoint 不存在 → 500 NoResourceFound | ❌→✅ | #11 |
| H3 | POST /articles/{uuid}/publish | status PUBLISHED | OK，回 ArticleResponse | ✅ | |
| H4 | POST /articles/{uuid}/reject + reason | status REJECTED + rejectReason 對 | OK（之前 400 是 git bash UTF-8 false alarm） | ✅ | |
| H5 | 發布後在公開列表 | 顯示 | OK | ✅ | |
| H6 | 非 Admin 訪 admin endpoint | 403 | OK（已有 E2E 守衛覆蓋） | ✅ | |
| H7 | Pending 為空時 UI | empty state | (UI 未測) | ⏭️ | |

**E2E 補強目標**：H1, H3, H4, H5（完整鏈路全缺，留 batch 補）

**Bug 修復**：#11 commit `d62e736` (前端改用 list total)

---

## 領域 G：檔案上傳

| ID | 步驟摘要 | 預期 | 實際 | 狀態 | Issue# |
|---|---|---|---|---|---|
| G1 | POST /files/upload (png) | 回 FileUploadResponse | UI spec 通過 (2/2) | ✅ | |
| G2 | 移除封面 | 預覽消失 | UI spec 通過 | ✅ | |
| G3 | png/webp/gif 輪測 | 各成功 | (僅測 png) | ⏭️ | |
| G4 | upload .txt | 拒絕 | `code:A0404` 「不支援的檔案類型」 | ✅ | |
| G5 | 超過配額 (AUTHOR 500MB) | 錯誤 | (未測) | ⏭️ | |
| G6 | GET /users/me/quota | usedBytes/limitBytes/remainingBytes | 對齊前端 QuotaInfo type | ✅ | |
| G7 | GET /users/me/files | FileMetadata[] | OK，後端多欄位 (originalName, storagePath, contentType, hasThumbnail, uploaderId, new) | ✅ | |
| G8 | DELETE /files/{id} | 列表移除 | (UI 未測) | ⏭️ | |
| G9 | 上傳中斷 | 錯誤處理 | (未測) | ⏭️ | |
| G10 | 縮圖 (RabbitMQ) | hasThumbnail=true | OK，G7 list 中 hasThumbnail=true 表示縮圖 producer 有跑 | ✅ | |

**E2E 補強目標**：G3, G4, G5, G8

---

## 領域 D：搜尋與推薦

| ID | 步驟摘要 | 預期 | 實際 | 狀態 | Issue# |
|---|---|---|---|---|---|
| D1 | GET /search?q=Vue | ES 回結果 | total=0 對所有合法 keyword | ❌ | **#15** |
| D2 | GET /search/suggest?q=Vue | 候選詞 | OK 回 ["vue"] | ✅ | |
| D3 | GET /search/history (logged in) | 陣列 | OK 空 | ✅ | |
| D4 | GET /search?tag=frontend | 結果限該 tag | **500「all shards failed」** | ❌ | **#14** |
| D5 | sort=latest | 排序 | 0 results（同 #15 下游） | ❌ | #15 |
| D6 | Trending（7d/24h） | list | OK，前端 mapper 處理 tagNames | ✅ | |
| D7 | Related articles | list | OK 3 條 | ✅ | |
| D8 | 邊界：空/特殊字元 | 不爆 500 | total=0（acceptable） | ✅ | |
| D9 | ES 不可用 fallback | 友善錯誤 | (ES 健在，未測) | ⏭️ | |

**E2E 補強目標**：D3, D4, D5, D6, D7（補在收尾）

**重大 Bug**：#15 全文搜尋整體失效；root cause = ES 中 `status` 字段是 `text`，backend 用 `term {status:"PUBLISHED"}` match 不到（`text` analyzer 拆 token，term 必須完全一樣）。修法：改用 `status.keyword` 或重建 mapping 為 keyword。

---

## 領域 B：個人

| ID | 步驟摘要 | 預期 | 實際 | 狀態 | Issue# |
|---|---|---|---|---|---|
| B1 | GET /users/me | 顯示資料 | OK，後端多 website/socialLinks 欄位（已知 #3） | ✅ | |
| B2 | PATCH /users/me/profile | 更新成功 | OK，nickname/bio 修改後 GET 看到 | ✅ | |
| B3 | 改密碼 → 舊 token 401 | version 機制觸發 | OK，舊 token 401（但走 Spring 預設格式 #12） | ✅ | |
| B4 | 改密碼後重新登入 | 新密碼可登入 | OK，新 token claims version="v2"（從 v1 升級） | ✅ | |
| B5 | DELETE /users/me（最後測） | 帳號消失 | (未測，會破壞 fixture) | ⏭️ | |

**E2E 補強目標**：B1-B4（全缺，留收尾補）

---

## 領域 C：公開瀏覽

| ID | 步驟摘要 | 預期 | 實際 | 狀態 | Issue# |
|---|---|---|---|---|---|
| C1 | GET /articles | 列表 | total=12 OK | ✅ | |
| C2 | categorySlug=frontend | 過濾 | total=7 | ✅ | |
| C3 | GET /articles/{uuid} | viewCount 增加 | OK，viewCount 累加 | ✅ | |
| C4 | GET /articles/slug/{slug} | 同篇內容 | OK，uuid match | ✅ | |
| C5 | 不存在 uuid | 404 / A0201 | `code:A0201` 「文章不存在或已刪除」 | ✅ | |

**E2E 補強目標**：C4

---

## 領域 E：標籤互動

| ID | 步驟摘要 | 預期 | 實際 | 狀態 | Issue# |
|---|---|---|---|---|---|
| E1 | GET /tags/hot | 列表 | OK，後端 id/usageCount 前端 mapper 轉 uuid/articleCount | ✅ | |
| E2 | Tag suggest q=fro | candidate | OK ["frontend"] | ✅ | |
| E3 | GET /tags/{slug} | TagDetail | OK | ✅ | |
| E4 | POST/DELETE /tags/{id}/follow | 雙向 | OK | ✅ | |
| E5 | 未登入 follow | 401 | OK（走 Spring 預設格式 #12） | ✅ | |

**E2E 補強目標**：E4, E5（全缺，留收尾補）

---

## Issues

> 編號 #1, #2... 對應領域驗證表的 Issue# 欄位
> 格式：**#N — [領域 ID] 標題**
> Body：根因 / 修復方式 / Commit hash / Tag (blocker/bug/improvement)

### #1 — [Ab] Refresh token cookie 寫死 `secure=true`

**根因**：`blog-module-user/.../AuthController.java:81,160` 將 `ResponseCookie.secure(true)` 寫死。

**驗證**：跑 Playwright auth-flow.spec.ts 4/4 pass（Chromium）— Chrome 89+ 對 localhost 例外允許。

**Tag**: improvement（Chromium 不影響；跨瀏覽器 dev 體驗有風險）

**潛在修復**：`app.cookie.secure: ${COOKIE_SECURE:true}` 注入，dev profile 設 false。先不修。

### #2 — [A3a] 密碼複雜度驗證僅檢查長度

**根因**：後端 `RegisterRequest`（推測）只用 `@Size(min=6, max=50)`，無 `@Pattern` 強制大寫/數字/特殊字元。

**Tag**: improvement（不立即修，只記錄）

### #4 — [F5/F8] editorService.getArticleForEdit 用錯端點 + categories/tags 解析錯

**根因**：
- `src/api/real/editorService.ts:13` 打 `/api/v1/articles/{uuid}` (公開端點)，後端對 DRAFT/PENDING 文章回 `A0201`
- `useEditorForm.ts:32` 用 `c.id`，但後端 `categories[].uuid`
- `useEditorForm.ts:33` 用 `[...data.tags]`，但後端 `tags[]` 是 object 非 string

**修復**：在 editorService 加 mapper 把 backend response 轉成前端 EditorArticle 期望的型別；端點改 `/edit`。
**Commit**: 71b797a (frontend repo)
**Spec**: e2e/editor-edit-existing.spec.ts

**Tag**: bug (was Blocker)

### #9 — [F3] Tag suggest 在 dev 環境回空陣列

**根因**：fixture 沒建立任何熱門 tag（global-setup 只 seed 12 篇文章但 tag 是 article 內 tagNames 自動建）。manual API 測試打 `?q=ja` 回 `[]`。

**Tag**: improvement（建議在 fixture seed 階段顯式建一些 tag）

### #16 — CORS allowed-methods 缺 PATCH（dev 環境前端 PATCH 全部 fail）

**根因**：`SecurityConfig.corsConfigurationSource()` 將 `setAllowedMethods` 寫死為 `GET/POST/PUT/DELETE/OPTIONS`，缺 PATCH。前端 `userService.updateProfile` 用 PATCH，跨 origin (5500→9010) 觸發 preflight → 後端不允許 PATCH → 瀏覽器 reject → 前端 settings 改 profile 整個壞。

**驗證**：Playwright 抓到 CORS error；新增 `SecurityE2E.cors_preflight_allowsPatch` IT 確認 PATCH 在 Access-Control-Allow-Methods。

**修復**：`allowedMethods` 加 PATCH。
**Commit**: `8627274`
**Tag**: bug ✅ FIXED

### #14 — [D4] `GET /search?tag=...` 無 q 時 ES 500 (all shards failed)

**根因**：tag-only filter (no q) 時 backend 建出的 BoolQuery 結構讓 ES 拒絕。需要看 SearchServiceImpl.java:80-110 的 must/filter 組合邏輯，確認 nested tags filter 在沒 must 子句時是否合法。

**Tag**: bug（user-facing：tag-only browse 完全不能用）。

### #15 — [D1/D5] 全文搜尋整體 0 result（ES mapping 不對齊 Java annotation）

**根因**：`ArticleDocument` 標 `@Document(createIndex=false)` → Spring Data 不自動建 index → dev/prod 首次寫入時被 ES **dynamic mapping** 建（status 變 text 而非 keyword），與 Java `@Field(Keyword)` 不符。`SearchServiceImpl` 的 `term {status:"PUBLISHED"}` 對 text 永遠 0 hit。

**修法（業界做法 A：bootstrap initializer）**：
新增 `SearchIndexInitializer` (ApplicationRunner)：
- 啟動時若 index 不存在 → `createWithMapping()` 套 Java annotation
- 若 mapping 不對齊（status 非 keyword）→ 砍掉重建，提示 admin 觸發 reindex

**Commit**: `ee51df3`（含 IT `SearchIndexInitializerE2E`，TDD red→green）
**驗證**：dev 砍掉 `blog_articles` → 重啟 → 自動建 keyword mapping → admin reindex 12 docs → search "Vue" → 2 hits ✅

**Tag**: bug ✅ FIXED

### #11 — [H2] `GET /admin/articles/pending/count` 後端 endpoint 不存在

**根因**：前端 `adminService.getPendingCount()` 打 `/api/v1/admin/articles/pending/count`，但後端 `AdminArticleController` 只實作 `/admin/articles/pending`。請求落到 Spring 的 `NoResourceFoundException` 回 500。前端有 `try/catch` fallback 為 0，admin badge 顯示 0（不擋人但功能殘缺）。

**修復**：前端 `getPendingCount` 改呼叫 `getPendingArticles(1, 1).total`。
**Commit**: d62e736 (frontend repo)
**Tag**: bug

### #12 — [H1b/B3b/E5] Spring Security 的 401/403 回應不走 ApiResponse 格式

**根因**：`SecurityConfig.unauthorizedEntryPoint` 用 `response.sendError`，且未設 `accessDeniedHandler`，導致未授權請求走 Spring 預設 ErrorMvcAutoConfiguration 輸出 `{timestamp, status, error, path}`，而非全站 `{code, message, data, timestamp}`。前端 axios interceptor 拿不到 body.code。

**修復**：
- 新增 `CommonErrorCode.UNAUTHENTICATED` (A0005) 與 `FORBIDDEN` (A0006)
- 重寫 `unauthorizedEntryPoint` + 新增 `accessDeniedHandler` bean
- 共用 `writeApiResponse` helper 用 ObjectMapper 序列化 ApiResponse

**Commit**: `434926c`（含 IT `SecurityE2E.ErrorResponseFormat`，TDD red→green，全 12 case 回歸 pass）
**驗證**：dev curl 401 → `{"code":"A0005",...}`、403 → `{"code":"A0006",...}` ✅

**Tag**: bug ✅ FIXED

### #10 — [Fa] EditorView 不對 props.uuid 變化重新載入

**根因**：`src/views/EditorView.vue` 的 onMounted 只跑一次。在 SPA 內 router.push 從 `/editor/{a}` 切到 `/editor/{b}`，component 被 reuse，不 re-mount，loadArticle 不會 trigger，UI 顯示前一篇文章內容。

**影響**：少數情境，通常 user 從 /my-articles 點 link 進 editor 是 fresh navigation 不受影響；但 SPA 內部相鄰跳轉會 stale。

**Tag**: improvement (修法：加 `watch(() => props.uuid, ...)` 或在 router-view 加 `:key`)

### #3 — [Aa] 前端 User type 缺 `website`、`socialLinks` 欄位

**根因**：後端 `GET /users/me` 回 `{uuid,email,nickname,bio,avatarUrl,website,socialLinks,role,emailVerified,createdAt}`；前端 `src/types/auth.ts:24-33` 的 `User` interface 缺後兩欄。

**影響**：TypeScript 無法 type-safe 存取 `user.website`；Settings 頁面如果展示這兩欄需改前端 type。

**Tag**: improvement（看 Settings 是否要編輯這兩欄再決定優先順序）

---

## Summary

### 驗證項
- **總計**：80
- **通過**：71（含修復後通過）
- **跳過**：9（含前端 UI 未實作 / 邊界 fixture 太重 / 無 q 加 sort=hot 等）

### Bug 修復
| # | 摘要 | Commit |
|---|---|---|
| #4 | editorService.getArticleForEdit 用錯端點 + categories/tags 解析 | `71b797a` |
| #11 | admin pending count endpoint 不存在 (前端 fallback) | `d62e736` |
| #12 | 401/403 不走 ApiResponse 格式 (後端 + IT) | `434926c` |
| #15 | 全文搜尋 0 result (ES status 字段非 keyword) | `ee51df3` |
| #16 | CORS allowed-methods 缺 PATCH | `8627274` |
| #14 | search?tag=... 500 (all shards failed) | **#15 順帶修好** |

### Improvement（未修，記錄供後續）
| # | 摘要 |
|---|---|
| #1 | Cookie `secure=true` 寫死，跨瀏覽器 dev 體驗有風險 |
| #2 | 密碼 validation 只看長度 6-50，無複雜度 |
| #3 | 前端 User type 缺 `website`、`socialLinks` 欄位 |
| #9 | tag suggest fixture 缺資料 |
| #10 | EditorView 不對 props.uuid 變化重 load (router.push 同 component reuse 邊界) |
| #17 | backend 對 article title 沒長度 validation（前端 maxlength 是 client only） |

### Defer（前端未實作 UI、留 product backlog）
- B4 TagView follow / unfollow 按鈕（backend API 已完成）
- B5 ArticleDetail related articles section（backend API 已完成）

### Playwright 變更
- **新增 6 spec**：`editor-edit-existing.spec.ts`, `admin-review.spec.ts`, `my-articles.spec.ts`, `settings.spec.ts`, `auth-token-refresh.spec.ts`, `search-advanced.spec.ts`, `end-to-end-sanity.spec.ts`
- **增補既有**：`author-writes-article.spec.ts` (F2/F3/F10), `author-file-upload.spec.ts` (G3/G4/G8)
- **基建調整**：`playwright.config.ts` baseURL 改 localhost (與 backend same-site, 解 SameSite=Strict cookie 問題) + serial 跑（避免 dev real-backend race）；`src/main.ts` DEV mode expose `__pinia` 給 spec 操作

### 跑回歸
- `npm run test:e2e`：**116 pass / 3 skip / 0 fail**（6.8 分鐘 serial）
- `./mvnw test -pl blog-start -am`：所有 backend IT 通過（含新增 SearchIndexInitializerE2E + SecurityE2E.ErrorResponseFormat）
