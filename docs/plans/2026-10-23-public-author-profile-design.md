# 設計：公開作者頁與作者多選篩選

- **日期**: 2026-10-23
- **狀態**: Yuan 已逐段確認（規則 / 契約 / 後端 / 前端 / 測試），待拆實作計畫
- **涉及 repo**: 本 repo（後端）＋ `blog-web-v2-front-end`
- **交付順序**: 後端 PR 合併 → 前端整份重抓 `openapi.json` → 前端 PR
- **相關**: SEC-04 第 2 段（`ai-docs/backlog/2026-09-25-sec04-stage2-frontend-handoff.md`）的 ArticleList 改造併入本案前端 PR

---

## 0. 背景

前端 `AuthorView`（`/author/:handle`）是無入口的半成品：全站零連結、個人資料寫死在 `PROFILES`（僅 `yuanluca`）、後端無公開作者資料端點，且以 `size=200` 拉全量——SEC-04 第 3 段降 `MAX_SIZE` 後會直接 400。ArticleList 左欄的作者篩選以 nickname 從全量文章推導，改伺服器端分頁後選項只剩當頁作者。

---

## 1. 業務規則（Yuan 確認）

| 用途 | 誰 |
|---|---|
| **作者頁可開** | 所有 `status = ACTIVE` 的使用者。沒有已發布文章者照常顯示個人資料，文章區顯示「尚未發表文章」、統計為 0 |
| **作者頁 404** | 不存在、`DELETED`、未驗證信箱——三者回同一個回應，不可區分 |
| **作者下拉搜得到** | ACTIVE 且至少 1 篇 `PUBLISHED` 文章 |

- 作者頁 URL 以 **uuid** 識別。`username` 是登入識別符（`AuthService` 以 email 或 username 登入），不得外露。
- 職稱行顯示 `location`（不新增 headline 欄位）。
- 設定頁須明示「以下資料會顯示在你的公開個人頁」——原本讀者填寫時不知道會公開。

---

## 2. 架構決策：依資料歸屬拆分（方案 A）

依 `architecture.md`：`users` 為 reference data 可直接 JOIN；以 `articles` 欄位做 filter / sort / count 的集合述詞必須由 article 模組以 facade 提供（2026-09-04 方案 C）。

| 端點 | owner | 理由 |
|---|---|---|
| `GET /api/v1/users/{uuid}/profile` | user | 個人資料屬 user；`social_links` 的寫入驗證與公開解析同在一個模組，規則只有一份 |
| `GET /api/v1/authors` | article | 「有已發布文章的人」是文章概念；`articles JOIN users`（reference data）合規 |

否決：B（全放 article——article 需解析 `social_links`，規則一分為二）、C（全放 user——須從 facade 取全體作者文章數再自行過濾，傳輸量與作者數成正比）。

---

## 3. API 契約

### 3.1 `GET /api/v1/users/{uuid}/profile`（匿名可讀）

```jsonc
{
  "uuid": "…",
  "nickname": "…",
  "avatarUrl": null,
  "bio": null,
  "location": null,
  "website": null,            // 僅 http(s)；既有不合格資料回 null
  "socialLinks": { "github": null, "twitter": null, "linkedin": null },
  "joinedAt": "…",            // users.created_at
  "stats": { "publishedArticleCount": 0, "totalViewCount": 0, "totalLikeCount": 0 }
}
```

- 不回傳 email、username、role、通知偏好
- 404 → `A0101 USER_NOT_FOUND`
- 統計只計 PUBLISHED；`view_count` 由 Redis 每 5 分鐘回寫，最多落後約 5 分鐘
- 作者頁文章列表沿用 `GET /api/v1/articles?authorUuids={uuid}&page=&size=`，不新增端點

### 3.2 `GET /api/v1/authors?keyword=`（匿名可讀）

```jsonc
[{ "uuid": "…", "nickname": "…", "avatarUrl": null, "publishedArticleCount": 42 }]
```

- `keyword` 選填，trim；nickname 不分大小寫、任意位置比對；`%` `_` 跳脫為字面；超過 50 字 → 400
- **固定最多 20 筆，不開放 `limit` 參數**（與 `authorUuids` 上限 20 對齊）
- 排序：已發布文章數 DESC → nickname ASC → uuid ASC

### 3.3 既有端點變更

| 端點 | 變更 | 相容性 |
|---|---|---|
| 推薦文章（`RecommendArticleResponse`） | 加 `authorUuid` | 純新增 |
| `GET /api/v1/search`（`SearchResultResponse`） | 加 `authorUuid` | 純新增 |
| `PATCH /api/v1/users/me/profile` | `website` 僅 `http(s)://`、≤255；`socialLinks` 必須為 JSON 物件、key 限 `github`/`twitter`/`linkedin`、值為空字串或符合平台格式：GitHub `^[A-Za-z0-9-]{1,39}$`、X `^[A-Za-z0-9_]{1,15}$`、LinkedIn `^[A-Za-z0-9-]{3,100}$` | ⚠️ 原本接受的內容會改回 400；既有資料不遷移，公開輸出時略過不合格值 |

### 3.4 公開端點登記（`judgment.md` §5，Yuan 已同意）

`SecurityConfig` 以 `UUID_PATH_VARIABLE` 規則新增 `GET /api/v1/users/{UUID}/profile` 與 `GET /api/v1/authors` 兩條 `permitAll`；同步 `security.md` Public Endpoints 表與 `SecurityConfigTest`。

---

## 4. 後端實作

**無 migration、無新索引**（`idx_articles_author_id` 已存在）。

### user 模組
- `SocialLinks` 值物件：平台格式的**唯一真相**；`strictParse`（寫入，不合格丟例外）／`lenientParse`（公開輸出，略過不合格、永不拋）
- `@ValidSocialLinks`（委派 `SocialLinks.strictParse`）、`@HttpUrl` 套在 `UpdateProfileRequest`
- `UserController#GET /{uuid}/profile`：無 `@PreAuthorize`、無 `@AuthenticationPrincipal`，JavaDoc 標「匿名可存取」（`security.md` 原則 7）
- `UserService.getPublicProfile(uuid)`：非 ACTIVE → `USER_NOT_FOUND`；統計經 `ArticleFacade`

### article 模組
- `ArticleFacade.getPublishedStatsByAuthor(Long authorId)` → `AuthorArticleStats(count, views, likes)`；方法名內建可見性（集合述詞規範 §3.3）
- `AuthorController#GET /api/v1/authors` → `AuthorQueryService.searchPublishedAuthors(keyword)`
- Mapper：`articles JOIN users`，`a.status='PUBLISHED' AND u.status='ACTIVE'`，可選 `ILIKE`（escape），`GROUP BY` 作者，排序後取 20
- 界限寫進程式（`judgment.md` §9）：20 筆與 50 字為常數；JavaDoc 註明每次搜尋對全部已發布文章 GROUP BY，**已發布文章超過約 5 萬篇**時改為在 users 維護反正規化計數

### recommend / search
- recommend：`ArticleRecommendMapper` 多選 `u.uuid`，facade DTO 與回應加欄位。`recommend:related:*` 舊快取最多 1 小時回 `null`，前端以純文字顯示
- search：ES 文件不動；查詢後以單次 `UserFacade.getAuthorInfoByIds` 把該頁作者 id 轉 uuid（非 N+1）

### 刻意不做
- users 反正規化 `published_article_count`（規模到了再做）
- 作者頁快取
- ES 文件內存有 `author.username`（登入識別符）——目前未輸出，但不應存；另立待辦

---

## 5. 前端實作

| 元件 | 內容 |
|---|---|
| `authorService`（dispatcher + real + mock） | `searchAuthors(keyword)`、`getProfile(uuid)`；mock 必須同樣支援 keyword 篩選與 404 |
| `AuthorMultiSelect.vue` | 可搜尋多選：300ms debounce、序號守衛丟棄舊回應、上限 20、完整鍵盤操作與 `combobox`/`listbox` ARIA（不重蹈 FE-05） |
| `AuthorName.vue` | 有 `authorUuid` → 連到 `/author/{uuid}`；否則純文字。8 個入口共用 |
| `src/utils/socialLinks.ts` | 與後端相同的平台 regex、固定前綴＋`encodeURIComponent` 組網址、`isHttpUrl` |
| ArticleList | 伺服器端分頁（每頁 12）；日期、分類、作者、排序轉參數；頁數取自 `total`/`pages`；infinite 逐頁 append 並以 uuid 去重；作者區塊改用 `AuthorMultiSelect`；Tags 區塊改用 `GET /tags/hot` |
| AuthorView | 路由改 `/author/:uuid`、刪除 `PROFILES`；404 顯示「找不到這位作者」；`location` 為職稱行；`joinedAt` → `YYYY · Qn`；website 再以 `isHttpUrl` 過濾；無文章顯示「尚未發表文章」 |
| SettingsView | 社群連結與網站即時格式驗證；公開說明與「查看我的公開頁」連結 |
| 作者入口（8 處） | ArticleDetail、ArticleCard、TrendingSection、BookmarksView、系列頁、SearchView、推薦文章、CommentItem → 全改用 `AuthorName` |
| 型別 | `ArticleItem`（`mapArticle` 補 `authorUuid`）、推薦、搜尋加 `authorUuid?: string \| null` |
| 文件 | `api-contract.md` 補兩個新端點慣例 |

---

## 6. 測試策略（TDD；輸出存 `logs/`）

### 後端
- `SocialLinksTest`：strict 拒絕非物件、未知 key、格式不符、超長；接受空字串；lenient 對亂碼與 `null` 不拋、只保留合格值
- `UpdateProfileRequest` 驗證：`javascript:`、`ftp://` 擋下，`https://` 通過
- `UserServiceTest#getPublicProfile`：ACTIVE 正常；DELETED／未驗證／不存在皆同一個 `USER_NOT_FOUND`
- `SearchServiceImplTest`：每頁 `getAuthorInfoByIds` 恰呼叫 1 次並正確映射
- IT（真 Postgres）——作者搜尋：只有草稿的作者不出現、DELETED 不出現、大小寫不敏感、`keyword="_"` 不匹配全部、排序與 tie-break、種 21 位只回 20、keyword 超長 400
- IT——`getPublishedStatsByAuthor`：只計 PUBLISHED；零篇回 0 而非 null
- IT——作者頁回應 JSON key **白名單**比對（防日後有人在 DTO 加 `email`）
- IT——推薦回應含 `authorUuid`
- `SecurityConfigTest`：匿名可達兩個新端點；反向：匿名 `GET /users/me/profile` → 401、匿名 `PATCH /users/{uuid}/profile` → 401
- 反向驗證（`judgment.md` §8）：故意拿掉 escape、拿掉 `status='PUBLISHED'` 條件，確認對應 IT 轉紅

### 前端
- `socialLinks.test.ts`：與後端**同一組**合格/不合格案例
- `AuthorMultiSelect.test.ts`：debounce 只送 1 次、舊回應被丟棄、上限 20、鍵盤、無結果
- `authorMockService.test.ts`：keyword、404
- `ArticleList.test.ts`：參數映射、`total` 頁數、infinite 去重、篩選變更重置
- `AuthorView.test.ts`：404、無文章、`javascript:` website 不渲染、社群網址已編碼
- `AuthorName.test.ts`：有 uuid 為連結、無則純文字
- `useSettings`/`SettingsView`：格式錯誤無法儲存並顯示提示
- E2E（真後端）：文章頁點作者 → 作者頁；文章列表以作者下拉篩選
