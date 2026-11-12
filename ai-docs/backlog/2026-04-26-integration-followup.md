# 前後端整合驗證 — Follow-up Backlog

**Generated**: 2026-04-26
**Source**: 階段一手動契約驗證 + 階段二修復後沉澱出的「不修但要追蹤」項目
**Related**: [`ai-docs/integration-tests/2026-04-26-fe-be-integration.md`](../integration-tests/2026-04-26-fe-be-integration.md)

> 這次整合驗證已修完所有 user-facing bug（#4, #11, #12, #14, #15, #16）。
> 本檔是「決定不修但需追蹤」的清單，分兩類：
>
> - **A. Defer** — 前端 UI 未實作的 backend feature，等 product 規劃整體體驗
> - **B. Improvement** — 已知技術債或邊界，這次評估後決定不修

---

## 推薦處理優先順序

依「安全性 > 跨人 dev 體驗 > 順手做 > 等 product 規劃」排：

1. **B-2** 密碼複雜度驗證（安全：prod 開放註冊前該做）
2. **B-1** Cookie `secure` 改 config 注入（影響跨瀏覽器 dev 體驗）
3. **B-6** Backend article title 加 `@Size`（一行修，順手）
4. **B-3** 前端 User type 補 `website` / `socialLinks`（搭 A-1 一起做）
5. **A-1** TagView follow / unfollow UI（要 product 設計 follow feed 整體）
6. **A-2** ArticleDetail related articles section（要 product 設計卡片風格）
7. **B-5** EditorView 對 `props.uuid` watcher（影響面小，nice-to-have）
8. **B-4** Tag suggest fixture seed 多樣 tags（dev 體驗微優化）

---

## A. Defer：前端 UI 未實作（明顯規劃中 feature）

### A-1. TagView 加 follow / unfollow 按鈕

- **Status**: pending
- **Priority**: 中
- **Estimated effort**: button 30 分；含 followed feed 設計 1-2 天
- **Affected files**:
  - 前端：`src/views/TagView.vue`、`src/views/ArticleDetail.vue`（標籤旁可選）
  - 前端：`src/api/real/tagService.ts`（`followTag/unfollowTag` 已寫好可直接用）
  - 後端：✅ `POST/DELETE /api/v1/tags/{id}/follow` 已完整含 IT

**現況**
- Backend follow/unfollow API 完整可用、有 IT
- Frontend service 已寫好
- 但 TagView 沒任何 follow toggle button
- 等於使用者沒有觸發點

**期望**
- TagView 標題旁加「追蹤 / 已追蹤」toggle
- 未登入點擊 → 提示登入或 redirect `/login`
- （延伸）Profile 頁顯示我追蹤的 tags 清單
- （延伸）Home 加 follow feed section（追蹤 tag 的最新文章）

**為何不在這次修**：純 product feature 不是 bug。涉及 follow feed 的整體 UX 設計需 product 介入。

**Spec 缺口**：`e2e/tag-interaction.spec.ts`（E4 follow / E5 未登入提示）

---

### A-2. ArticleDetail 加「相關文章」section

- **Status**: pending
- **Priority**: 中
- **Estimated effort**: 1-2 小時（含設計討論）
- **Affected files**:
  - 前端：`src/views/ArticleDetail.vue`、新增 `src/components/article/RelatedArticlesSection.vue`
  - 前端：`src/api/real/recommendService.ts`（`getRelatedArticles` 已寫好可直接用）
  - 後端：✅ `GET /api/v1/recommend/related/{uuid}` 已完整（ES content-similarity，已測能回 3 篇）

**現況**
- Backend related API 完整、有 IT
- Frontend service 已寫好
- ArticleDetail 沒呼叫、沒對應 section

**期望**
- 文章內容下方加「相關文章」card list（3-5 篇，與 article-list 同卡片風格）

**為何不在這次修**：純 UI 設計，涉及推薦排版位置、是否與「同作者文章」併排等 product 決策。

**Spec 缺口**：`e2e/recommend.spec.ts`（D7 related 在 ArticleDetail 顯示）

---

## B. Improvement：已知技術債（這次不修）

### B-1. Cookie `secure=true` 寫死於 backend

- **Status**: pending
- **Priority**: 中（跨瀏覽器 dev 體驗）
- **Estimated effort**: 15 分
- **Affected files**:
  - 後端：`blog-module-user/src/main/java/.../controller/AuthController.java:81,160`
  - 後端：`blog-start/src/main/resources/application-dev.yaml`（加 `app.cookie.secure=false`）

**現況**
- `ResponseCookie.secure(true)` 寫死於 login + logout 兩個 ResponseCookie builder
- Chrome 89+ 對 `localhost` 例外允許 http + Secure cookie，所以本次驗證沒撞到
- Firefox / Safari 在 http (dev) 會 reject Secure cookie

**期望**
- `@Value("${app.cookie.secure:true}")` 注入
- `application-dev.yaml` 設 `app.cookie.secure: false`
- Prod 預設 true（fail-safe）

**Issue 編號**：integration-tests checklist 中 `#1`

---

### B-2. 密碼複雜度驗證薄弱

- **Status**: pending
- **Priority**: 高（安全性，prod 開放註冊前該補）
- **Estimated effort**: 規則討論 30 分；實作 1 小時
- **Affected files**:
  - 後端：`blog-module-user/.../model/dto/request/RegisterRequest.java`
  - 後端：`ChangePasswordRequest.java`、`ResetPasswordRequest.java`
  - 前端：註冊頁 / 改密碼頁的 client-side validation

**現況**
- 後端只用 `@Size(min=6, max=50)` 驗證 password
- 無 `@Pattern` 強制大寫 / 數字 / 特殊字元
- 弱密碼 `abc123` 也能註冊

**期望**
- 先確定 password policy（建議：min 8、含至少一個英文 + 一個數字、可選擴充）
- 後端加 `@Pattern` 或 custom validator
- 前端註冊 / 改密碼即時提示複雜度狀態（已滿足 ✓ / 未滿足 ✗）
- 統一文案：避免「6-50 字元」與新規則衝突

**Issue 編號**：integration-tests checklist 中 `#2`

---

### B-3. 前端 User type 缺 `website`、`socialLinks`

- **Status**: pending
- **Priority**: 低（搭 A-1 backend follow feed 或 settings 編輯 socialLinks 時一起做）
- **Estimated effort**: 純 type 5 分；Settings socialLinks UI 1-2 小時
- **Affected files**:
  - 前端：`src/types/auth.ts:24-33`（補 `website?: string; socialLinks?: ... | null`）
  - 前端：`src/views/SettingsView.vue` 的「社群連結」section（目前只 localStorage 沒對接 backend）
  - 前端：`src/api/real/userService.ts` updateProfile（補 socialLinks 欄位）

**現況**
- 後端 `GET /users/me` 回 `{..., website, socialLinks, ...}`
- 前端 `User` interface 沒這兩欄 → TypeScript 無法 type-safe 讀取
- Settings 「社群連結」只 `localStorage.setItem`，不真的 PATCH 給後端

**期望**
- 補 type
- Settings 改成真的 PATCH website / socialLinks 給後端
- ArticleDetail 作者卡片 / Author profile 頁顯示 social icons

**Issue 編號**：integration-tests checklist 中 `#3`

---

### B-4. Tag suggest fixture seed 多樣 tags

- **Status**: pending
- **Priority**: 低（dev 體驗微優化）
- **Estimated effort**: 10 分
- **Affected files**:
  - 前端：`e2e/global-setup.ts` seed 階段加 tag list

**現況**
- dev 環境只有 `frontend` / `backend` 兩個 tag（從 12 篇 seed articles 的 tagNames）
- 前端 dev 跑 tag suggest 體驗稀薄

**期望**
- global-setup 顯式建一些常見 tag（vue, react, java, python, go, typescript, nodejs, docker, kubernetes...）

**Issue 編號**：integration-tests checklist 中 `#9`

---

### B-5. EditorView 不對 `props.uuid` 變化重新 load

- **Status**: pending
- **Priority**: 低（影響面少）
- **Estimated effort**: 5-10 分（加 watcher + 一個 spec）
- **Affected files**:
  - 前端：`src/views/EditorView.vue`（加 `watch(() => props.uuid, ...)` 或 router 加 `:key`）

**現況**
- EditorView 的 `onMounted` 只跑一次
- SPA 內若 `router.push` 從 `/editor/{a}` 切到 `/editor/{b}`，組件被 Vue Router reuse、不重新 mount、`loadArticle` 不再 trigger
- UI 仍顯示前一篇文章資料

**期望**
- 加 `watch(() => props.uuid, async () => { ... reset state + loadArticle() ... })`
- 或在 `<RouterView :key="$route.fullPath">` 強制 re-mount editor 路由

**為何不在這次修**：實際 user flow 通常從 my-articles 點 link 進編輯（fresh nav）不會撞到。SPA 內相鄰跳轉才有問題。

**Issue 編號**：integration-tests checklist 中 `#10`

---

### B-6. Backend article title 沒長度 validation

- **Status**: pending
- **Priority**: 中（一行修順手做）
- **Estimated effort**: 15 分
- **Affected files**:
  - 後端：`blog-module-article/.../model/dto/request/CreateArticleRequest.java`
  - 後端：`UpdateArticleRequest.java`
  - 後端：`blog-module-article/src/test/.../ArticleServiceTest.java`（補 IT case）

**現況**
- 本次補 `e2e/author-writes-article.spec.ts` 的 F10 時發現
- 後端 `CreateArticleRequest.title` 沒 `@Size` annotation
- 傳 200 / 1000 字 title 都會收
- 前端 `<input maxlength="120">` 是 client-only 限制，繞過 後端全收

**期望**
- 後端加 `@Size(min=1, max=120)` 對齊前端 UX 限制
- Validation message 對前端 toast 友善（如「標題長度須為 1-120 字元」）
- IT case：title 121 字應回 code "400"

**Issue 編號**：integration-tests checklist 中 `#17`

---

## 註

- 本檔不在 git 追蹤（`ai-docs/` 是 gitignored 的個人筆記）
- 處理任一項時建議：先補對應 e2e spec → 修 → spec green → commit → 把該項從本檔移除
- 每個 backlog 完成後可 cross-ref 對應 checklist `#N` 標 closed
