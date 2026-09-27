# 交接：SEC-04 第 2、3 段（前端改伺服器端分頁 → 降 MAX_SIZE）

- **建立日期**: 2026-09-25
- **狀態**: 第 0、1 段後端已完成（分支 `feat/article-list-server-side-query`），第 2 段待指派
- **上游交接**: `2026-09-06-handoff-sec04-arch30.md` §1
- **本檔讀者**: 接手第 2 段（前端 repo `blog-web-v2-front-end`）或第 3 段（本 repo）的 session

---

## 0. 一句話

後端 `GET /api/v1/articles` 已能在伺服器端完成篩選＋排序＋分頁；前端還在用 `size=1000` 拉全量後自己做。**前端改完之前，`PageQuery.MAX_SIZE` 不能從 1000 降到 100**，否則列表會靜默少資料。

---

## 1. 後端契約（已完成，純新增、向下相容）

`GET /api/v1/articles`（匿名可讀）

| 參數 | 語意 | 正規化 |
|---|---|---|
| `page` / `size` | 既有，`PageQuery` | size ∈ [1, 1000]，預設 10 |
| `tags` | 標籤 **slug**，**AND**（須同時帶有全部） | 逗號分隔或重複參數皆可；去空白、轉小寫、去重 |
| `categorySlug` | 分類 **slug**，**OR**；沿用既有參數名，單值呼叫語意不變 | 去空白、去重，**保留大小寫**（精確比對，同改版前） |
| `authorUuids` | 作者公開 UUID，OR | 去重；**格式錯誤回 400** |
| `publishedWithinDays` | 只取最近 N 天發布者 | ≤ 0 視為不篩選；上限 36500 |
| `sort` | `latest`（預設）/ `popular`（view_count）/ `commented`（comment_count） | 不分大小寫；未知值退回 `latest` |

- 上述三個多值參數任一**超過 20 個回 400**（錯誤碼 `A0210`），不截斷——截斷會讓 OR 靜默少回、AND 靜默多回
- 每種排序都附 `id DESC` tie-breaker：**同一份資料快照下順序確定**。翻頁之間資料若變動（新發布、瀏覽數每 5 分鐘回寫、留言增減），OFFSET 分頁仍可能重複或遺漏；infinite 模式建議前端以 uuid 去重。要徹底消除需改 keyset 分頁（見 §5）
- `latest` = `published_at DESC NULLS LAST`。**這是唯一的行為變更**：原本預設是 `created_at DESC`，Yuan 已同意改為發布時間（首頁「最新文章」原本吃的是開始寫草稿的時間）
- `total` 為符合篩選條件的總數，與 records 用同一組 WHERE
- 回應欄位不變（`ArticleSummaryResponse`）；**仍不含 categories**

程式位置：`ArticleListQuery`、`ArticleListSort`、`PublishedArticleCriteria`、`ArticleMapper.PUBLISHED_CRITERIA_WHERE`。

---

## 2. 第 2 段：前端要改的（附現況查證）

### 2.1 🔴 TagView 現在就是壞的（與 MAX_SIZE 無關，建議最先修）

`TagView.vue:48` 呼叫 `getArticles(1, 100, '全部', tagName.value)`，把 tag 當 `keyword` 傳；但 `real/articleService.ts:137` 的簽章是 `_keyword`，**直接丟棄**，TagView 本身也不做 client 過濾。結果：**生產環境每個 `/tags/xxx` 都顯示同一份「全站最新 100 篇」**。

沒被抓到的原因：mock 有實作 keyword 過濾（`articleMockService.ts:12-18`），`TagView.test.ts` 又整個 mock 掉 `articleService`。

修法：改傳 `tags=<route slug>`（注意現在的 `tagName` 是把 slug 的 `-` 換成空白，不能直接用）。

### 2.2 🔴 ArticleList 的分類篩選在生產環境必定清空列表

`real/articleService.ts:119-120` 寫死 `categories: []`（列表回應不含分類），而 `useArticleFilters.filterAndSort` 以 `a.categories` 比對——**選任何分類都會把所有文章篩掉**。另外分類選項是寫死的名稱陣列（`ArticleList.vue:142`），不是後端的 slug。

修法：選項改由 `GET /api/v1/categories` 取得，送 `categorySlug=<slug>`。

### 2.3 ArticleList 改伺服器端篩選／排序／分頁

| 前端現況 | 改為 |
|---|---|
| `getArticles(1, 1000, ...)` 一次拉全量 | 每頁 `size=12`（沿用 `PER_PAGE`） |
| `filterAndSort()` 在瀏覽器做 | 參數化：selTags → `tags`、selCats → `categorySlug`、selAuthors → `authorUuids`、dateRange → `publishedWithinDays`、sort → `sort`（鍵值相同） |
| `totalPages = ceil(filtered.length / PER_PAGE)` | 用回應的 `pages` / `total` |
| infinite 模式：`page * PER_PAGE < filtered.length` | 改為「已載入筆數 < total」並逐頁 append |
| 篩選變更時 `resetPage()` | 同時清空已載入的 records 並重新請求 |

注意事項：

- **selTags 目前存的是標籤 name**（`mapArticle` 的 `tags: raw.tags.map(t => t.name)`），後端要 slug——改用 `tagRefs` 或直接以 slug 為值
- **availableTags 目前由全量推導**（`ArticleList.vue:36-40`），改成分頁後只剩當頁 12 篇的標籤。改用既有的 `GET /api/v1/tags/hot?limit=12`（第 0 段已把此端點夾界在 20）
- **作者篩選**：Yuan 決定暫不做 author facet 端點。現況 `availableAuthors` 由全量推導、以 **nickname** 比對，但 nickname **不唯一**（`schema.md` users 表）。可選：(a) 拿掉作者篩選 UI；(b) 保留但改以 `authorUuid` 為值，選項來源另議。`mapArticle` 目前**沒有映射 `authorUuid`**（後端有給），要補
- mock 實作（`articleMockService.ts`）必須同步支援相同參數——2.1 的 bug 就是 mock／real 契約漂移造成的

### 2.4 AuthorView

`AuthorView.vue:46` 以 `size=200` 拉全量再以 nickname 過濾（`:33-34`），profile 寫死。改為 `authorUuids=<作者 uuid>` ＋ 分頁；統計數字（文章數、總瀏覽、總讚）目前是對全量加總，改分頁後需另外的來源或接受只顯示 total。

### 2.5 其餘呼叫點

`useHomePage.ts:30`（size 6）、Bookmarks／AdminReview／MyArticles／useComments（10–20）都在 100 以內，不受第 3 段影響。首頁「最新文章」會因預設排序改為 `published_at` 而改變順序（預期中的修正）。

---

## 3. 第 3 段：降 MAX_SIZE 到 100（第 2 段上線後才做）

- `PageQuery.MAX_SIZE` 1000 → 100，並更新其 JavaDoc（目前寫明「取 1000 是為了不改變現行前端行為」）
- `PageQueryTest` 的 `上限值為 1000，與 ArticleList 前端現行傳值對齊` 測試需同步改寫
- **待 Yuan 拍板**（上游交接 §1.4）：超過上限時維持「截斷」（現行、靜默）還是改「回 400」（誠實但屬破壞性契約變更）
- 上線前 grep 前端所有 `size` 傳值，確認沒有 > 100 的呼叫點殘留
- 更新 `findings.md` SEC-04 狀態

---

## 4. IT 驗證紀錄

撰寫時本機 Docker daemon 未啟動，以下 IT 先寫未跑；**2026-09-27 已全數在真實 PostgreSQL 上通過**：
CI（PR #72 run 36318717764／36320002388）與本機 Docker Desktop 各跑過一次。
第 2、3 段的後續分支（分頁 400、分類 slug／V23）的 IT 亦已於本機通過。

| 測試 | 驗證什麼 |
|---|---|
| `ArticleControllerIT` 新增 9 個 `listArticles_*` | tags AND、分類 OR 不重複、分類 slug 大小寫精確比對、作者篩選、日期區間、兩種計數排序、預設排序為 published_at、同鍵值依 id 的確定順序、超過上限回 400 |
| `ArticleControllerIT#getPublishedArticles_authorResolvedByBatchLookup` | 列表作者由單次批次查詢填入（PERF-02） |
| `UserFacadeIntegrationTest`（2 個） | 批次作者投影的真實 SQL、UUID 映射、不讀 password_hash |
| `TagControllerIT` 新增 3 個 | 匿名大 limit 只回 20、負 limit 回空 |

本機已補的替代驗證：`ArticleMapperCriteriaSqlTest` 以 MyBatis `Configuration` 渲染動態 SQL（不連 DB），驗證 `<script>` 合法、OGNL 求值、`<foreach>` 展開與 bind parameter 的順序與值、列表與 count 的 WHERE 一致；並做過一次反向驗證（故意改壞標籤條件，測試確實轉紅）。**它驗不到 PostgreSQL 的語法與語意**，那仍是 IT 的責任。

---

## 5. 已知但刻意未處理

- 單篇詳情仍逐筆查作者（2 條，固定成本，非 N+1）
- `view_count` / `comment_count` 未建排序索引（理由見 V22 註解：頻繁回寫會失去 HOT update）
- PERF-08 剩餘：`findRecentPublished`、`findAllPublished` 的 `ORDER BY published_at DESC` 預設 NULLS FIRST，用不上 V22 索引
- 每頁仍重算 `COUNT(*)`（PERF-09 剩餘）
- OFFSET 分頁在資料變動時的重複／遺漏：需 keyset 分頁才能根除
- `article_tags.tag_id` 無索引（PERF-07），帶 `tags` 篩選時會掃 `article_tags` 全表；部落格量級下不成問題，與 PERF-07 一併處理
- 「我的文章」與待審列表仍是 `ORDER BY created_at DESC`、無 tie-breaker；第 3 段降上限後這兩個列表才真正分頁，屆時一併補 `, id DESC`
- 分類 slug 無格式限制（`CreateCategoryRequest` 只有 `@NotBlank` / `@Size`）：含逗號的 slug 無法以多值參數篩選（逗號為分隔符），含大寫者前端（會先轉小寫）篩不到。是否對分類 slug 加格式驗證、以及現有資料是否有此類 slug（`SELECT slug FROM categories WHERE slug <> lower(slug) OR slug LIKE '%,%'`），**待 Yuan 決定**——屬 admin API 契約變更
- `flyway-convention.md` 的 Core Rule 寫 migration 放 `blog-start`，但實際（與同檔 Test Migrations 段）是 `blog-db-migration`——文件自相矛盾，本分支照實際位置放
