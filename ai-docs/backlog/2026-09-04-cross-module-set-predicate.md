# 設計：跨模組「集合述詞」規範 ＋ ARCH-13 / 收藏分頁落地

- **建立日期**: 2026-09-04
- **狀態**: 設計已定案（Yuan 於 2026-09-04 拍板「走 C」），待實作
- **來源**: `ai-docs/audits/2026-09-04/` 三維度稽核的 ARCH-13、PERF-34、ARCH-09、AUTH-08
- **類型**: 架構規範修訂 ＋ 對應落地
- **檔案位置理由**: 依 `maintenance.md` §3（新長內容寫 `ai-docs/` 新檔，不在 repo root 散檔）；
  `ai-docs/backlog/*` 為 AI 可直接寫入（`maintenance.md` §2 所有權表）

---

## 1. 問題陳述

`architecture.md` 的邊界規範只有一條軸：**reference data（`users` / `tags`）可 JOIN，
業務 data（`articles` / `comments`）必走 owner service**。三個「邊界判斷速查」的例子
全部是**單物件查詢**（取 `author_id`、取 `nickname`、更新 `comment_count`）。

但實際發生的違規全都不是單物件查詢，而是**跨邊界的集合運算**——用他模組的欄位做
filter / sort / paginate / count。service interface 回傳的是物件，不是還能再加條件的
relation，所以 `WHERE a.status = 'PUBLISHED'` 無處可傳。

**根因判定：規範不是「太嚴」，是對這一整類查詢「沒有定義」。**
ARCH-13 從基線 4 處惡化為 7 處，是規範缺口的症狀，不是紀律鬆弛的症狀。

---

## 2. 決策（Yuan 拍板：走 C）

### 2.1 Yuan 對規範目的的定義

Yuan 選定此規範要擋的是：
- **目標 2**：保留未來拆服務的可能
- **目標 4**：防止模組互相認識對方的內部結構

### 2.2 對目標 2 的誠實結論（重要，勿在後續 session 遺失）

**現行規範在目標 2 上收的是稅，不是保費——成本照付，保障沒買到。**

`grep -rn "REFERENCES articles" blog-db-migration/src/main/resources/db/migration/*.sql`
證實跨模組硬 FK 共 8 條，全部 `ON DELETE CASCADE`：

| 表 | 屬於模組 | 指向 |
|---|---|---|
| `comments` | comment | `articles(id)` |
| `user_article_likes` | reading | `articles(id)` |
| `user_bookmarks` | reading | `articles(id)` |
| `user_highlights` | reading | `articles(id)` |
| `user_reading_progress` | reading | `articles(id)` |
| `article_versions` | version | `articles(id)` |
| `article_tags` | tag | `articles(uuid)` |
| `article_categories` | article（模組內） | `articles(id)` |

且跨模組流通的貨幣是**資料庫主鍵 `Long`** 而非 UUID：`ArticleFacade.findIdByUuid`
回傳 PK，該 PK 在 `BookmarkController` 的 web 層流動（ARCH-09），並被寫進
`user_bookmarks.article_id`。刪文章依賴 Postgres CASCADE 橫跨 5 個模組連鎖刪除。

**拆服務的真正阻擋是這 8 個 FK 與共用 PK，不是 `SeriesMapper` 的 7 行 SQL。**
把 JOIN 換成 facade，對目標 2 一毫米都沒有前進。

要真正買到目標 2 必須走「事件複製 / 各模組自持副本」，而本 repo 有硬證據說現在
負擔不起：DATA-01（`tags.usage_count` 膨脹）、DATA-10（series count 漂移）、
DATA-04（ES 幽靈文件）皆為「副本對不上真相」，而 **ARCH-16 記載沒有任何 metrics
能發現它們，只能等使用者回報**。可觀測性補上之前，每多一份副本就多一個看不見的漂移源。

→ **本設計不假裝服務於目標 2。** 目標 2 的真正阻擋另立 findings 條目（見 §7）。

### 2.3 對目標 4 的軸修正

若罪名是「模組不該知道對方有哪些欄位」，則現行規範**已經允許 JOIN `users`**——
`CommentRepository` 明明知道 `nickname` / `avatar_url` 叫什麼。

所以二分表的真實軸不是「知不知道」，是**穩定度 ＋ 是否只透過 owner 控制的面**。

### 2.4 被否決的方案 B（DB view + JOIN）與否決理由

方案 B：`articles` 發布唯讀 view 作為契約面，跨模組允許 JOIN view（不得 JOIN base table）。
優點是收藏列表能一條 SQL 拿到精確 total，O(log n)。

**否決理由（決定性）**：收藏的可見性政策是 **viewer-dependent** 的
（`PUBLISHED` **或** 作者本人 **或** ADMIN），**view 包不住它**——述詞會落在 reading 模組
的 mapper 裡，於是 `ArticleVisibility.isReadableBy` 這份安全相關政策變成 Java 一份、
SQL 一份。

這會直接推翻 develop 最新 commit `d5ae18f`（「checkReadPermission 委派 ArticleVisibility，
可見性政策收斂為一份」）。而本 repo 反覆栽的坑正是「真相有兩份然後靜默漂移」——
ARCH-29 修復輪發現的那兩條「根本沒有 status 條件」的讀取路徑，**被漏掉的原因就是政策沒有集中**。

**取捨原則：O(n) 壞掉的方式是「慢」，看得見、量得到、可事後修；
政策分兩份壞掉的方式是「下架文章外洩」，看不見。**

### 2.5 採用方案 C（嚴守 facade，補完規範）

保持「跨模組不得 JOIN 他模組業務表」，但**把規範補完整**，讓集合述詞有合法路徑。

---

## 3. 規範修訂（`ai-docs/architecture.md`）

> **所有權**：`architecture.md` 為 ⚠️ 提案制（`maintenance.md` §2）。
> 本節屬「收緊 ＋ 補缺口」，非放寬，但仍須 Yuan 於 spec review 簽核後才合併。

### 3.1 新增條文：集合述詞（Set Predicate）

於「具體原則」新增第 5 點：

> - **集合述詞（Set Predicate）**：凡是用**他模組的欄位**做 filter / sort / paginate / count
>   的查詢，一律由 **owner 模組以 facade 方法提供**，caller 不得自行 JOIN 他模組業務表。
>   - 例：series 列表要「只列含至少一篇 PUBLISHED 文章的 series」→
>     走 `ArticleFacade.countPublishedBySeriesIds()`，不得 `EXISTS (SELECT 1 FROM articles ...)`
>   - 若該 facade 方法的傳輸量與內容量成正比（而非與頁大小成正比），
>     **必須在 JavaDoc 標註界限與重新評估的門檻**

### 3.2 修正二分表的軸描述

在 reference / 業務 data 表格下方補一句：

> 這條界線的判準不是「caller 知不知道對方的欄位名」（`CommentRepository` 本來就知道
> `users.nickname`），而是**該 schema 的穩定度**，以及**是否只透過 owner 控制的面存取**。

### 3.3 釐清 `*Facade` 的兩層契約（避免不一致）

> - **物件級讀取**（`findById` / `findByIds` / `findByUuid`）**維持 status-agnostic**，
>   由 caller 自行判斷可見性 ← 現行 `BookmarkController` JavaDoc 已如此聲明，不變
> - **集合述詞方法**由 owner 提供，**可見性直接內建，且必須寫進方法名**
>   （`filterReadableIds`、`countPublishedBySeriesIds`），使契約在呼叫端一眼可辨

### 3.4 誠實註記

> 本節規範買到的是**概念邊界**，**不是拆服務能力**。跨模組的 8 個 `ON DELETE CASCADE`
> 外鍵與共用 `Long` 主鍵才是拆分的真正阻擋，見 findings `ARCH-30`。

---

## 4. 落地一：ARCH-13 的 7 處

前置認知：**7 處並非同質**。逐條判定如下。

### 4.1 搬回 article 模組（3 處，與規範鬆緊無關——本來就放錯模組）

`SeriesMapper` 的這三個查詢**零個 series 欄位**，是純 articles 查詢：

| 現址 | 動作 |
|---|---|
| `SeriesMapper.java:77-78` `findPrevNav` | → `ArticleFacade.findPrevPublishedInSeries()` |
| `SeriesMapper.java:93-94` `findNextNav` | → `ArticleFacade.findNextPublishedInSeries()` |
| `SeriesMapper.java:106` `countPublishedInSeries` | → 併入 `ArticleFacade.countPublishedBySeriesIds()` |

`:106` 的 `status = 'PUBLISHED'` 字面字串隨之回到 `ArticleStatus` enum 所在模組，
繞過 enum 的問題一併消失。

呼叫端：`SeriesFacadeImpl.java:53,54,55`。

### 4.2 改簽名（1 處，不需 JOIN）

`SeriesMapper.java:115-117` `findSeriesByArticleIds` JOIN `articles` **只為了取 `a.series_id`**。
而呼叫端 `ArticleQueryService.java:227`（**article 模組自己**）手上就有 article，
`ArticleData` 也已帶 `seriesId` 欄位。

- `SeriesFacade.batchGetSeriesBasicInfo(List<Long> articleIds)`
  → `batchGetSeriesBasicInfo(Collection<Long> seriesIds)`，回 `Map<seriesId, SeriesBasicInfo>`
- `ArticleQueryService` 自行完成 articleId → seriesId → info 的映射
- `SeriesMapper` 的 SQL 變成 `SELECT ... FROM series WHERE id IN (...)`，**完全不碰 articles**

⚠️ 連動測試：`ArticleQueryServiceTest.java:593,607,613` 三處 mock 需同步改。

### 4.3 真跨界，走新 facade 方法（3 處）

| 現址 | 用途 |
|---|---|
| `SeriesMapper.java:34-35` | `article_count` 相關子查詢（投影） |
| `SeriesMapper.java:39-40` | `EXISTS` 決定哪些 series 進分頁（過濾） |
| `SeriesMapper.java:48-49` | 同一個 `EXISTS` 做 count |

三處合併為**一次** `ArticleFacade.countPublishedBySeriesIds(seriesIds)`：
- 回傳 `Map<Long, Integer>`，**只含 count > 0 者**
- key 集合 ＝ 過濾條件；value ＝ `article_count` 投影

`findPublic` / `countPublic` 改為三段（`SeriesMapper` 全程只碰 `series` 與 `users`）：

1. `SELECT id FROM series ORDER BY created_at DESC` — 自己的表，無邊界問題；
   series 為低基數實體（部落格量級為數十），全量取出可接受
2. `countPublishedBySeriesIds(那些 id)` → 以 Map 的 key 集合過濾（取代 `EXISTS`），
   value 作為 `article_count` 投影（取代相關子查詢）
3. 對已過濾且已排序的 id 清單取 sublist 得該頁 → 新增
   `SeriesMapper.findByIdsWithAuthor(pageIds)`（`series LEFT JOIN users`，
   `users` 為 reference data，合規）取回該頁完整欄位

`countPublic()` ＝ 第 2 步過濾後的集合大小，不再需要獨立 SQL。

**副作用：PERF-34 免費消失**——現行 `findPublic` 對同一條件同時跑 COUNT 子查詢與
EXISTS 子查詢，改為一次呼叫即得兩者。

### 4.4 新增的 facade 契約

於 `blog-infrastructure/.../facade/ArticleFacade.java`：

```java
/**
 * 集合述詞：一次取得多個 series 的 PUBLISHED 文章數。
 * 傳輸量與 seriesIds 大小成正比（series 為低基數實體，界限可接受）。
 * 只回傳 count > 0 的 entry。
 */
Map<Long, Integer> countPublishedBySeriesIds(Collection<Long> seriesIds);

/** series 內 PUBLISHED 且 position 小於 current 的最後一筆。 */
Optional<ArticleNavRef> findPrevPublishedInSeries(Long seriesId, Integer currentPosition);

/** series 內 PUBLISHED 且 position 大於 current 的第一筆。 */
Optional<ArticleNavRef> findNextPublishedInSeries(Long seriesId, Integer currentPosition);

/**
 * 集合述詞：回傳 candidateIds 中「對該 viewer 可見」者，維持輸入順序。
 * 可見性委派 blog-common 的 ArticleVisibility（單一真相）。
 * 傳輸量與 candidateIds 大小成正比 —— 見 §5 的界限說明。
 */
List<Long> filterReadableIds(List<Long> candidateIds, Long viewerId, boolean isAdmin);
```

新增 DTO `blog-infrastructure/.../facade/dto/ArticleNavRef.java`：

```java
public record ArticleNavRef(UUID uuid, String title, String slug) {}
```

（現有 `ArticleBasicInfo` 只有 `uuid` + `tagIds`，裝不下 nav 需要的 title / slug。）

---

## 5. 落地二：收藏列表分頁

### 5.1 缺陷（已驗證，非推測）

`BookmarkController.myBookmarks` 在 SQL 分頁、在應用層過濾，導致：

- **total 高估** → `PageResult.of()` 推導出的 `pages` 偏大
- **每頁筆數不一致** → 收藏 20 筆可能只渲染 13 篇

前端證據（`D:\backup\backup\程式\workspace\vue\blog-web-v2-front-end`）：
`BookmarksView.vue:25` 取 `result.pages`，`:128` 用它畫分頁器。
**症狀：分頁器顯示的頁數多於實際，尾頁為短頁或空頁。**

> 註：`BookmarkController` 現有 JavaDoc 論證「total ＝ 收藏總數是刻意的」，
> 該論證僅在「total 純展示」的前提下成立。Yuan 已確認前端用它算總頁數，
> **前提不成立，故該段 JavaDoc 需一併改寫**。

### 5.2 修法

1. 收藏 id 全量取出 → `ArticleFacade.filterReadableIds(allIds, viewerId, isAdmin)`
   → 精確 total ＝ 回傳 size；精確頁 ＝ 對已過濾清單取 sublist
2. 查詢次數與現況相同（3 次），其中兩次由「頁大小」改為「該使用者收藏數」為界
3. **payload 只有 id（8 bytes/筆），不是完整 `ArticleData`**
4. **可見性由 owner 模組套用**——比現況更好：現在是 reading 模組的 **web 層**在套

### 5.3 界限說明（規範 §3.1 要求的 JavaDoc 標註）

`filterReadableIds` 的傳輸量 ＝ 該使用者的收藏數 B。個人部落格 B 量級為數十至數百，
`WHERE id IN (B)` 走 PK 索引，成本可忽略。**重評門檻：任一使用者 B > 5,000。**

可選的索引改善（非必要，另記）：`user_bookmarks` 現有唯一索引為
`(user_id, article_id)`，`ORDER BY created_at DESC` 需額外排序；
若日後成為熱點，可加 `(user_id, created_at DESC)`。

### 5.4 順手修（同一批程式碼，不擴大範圍）

- **ARCH-09**：編排從 `BookmarkController` 搬進新的 `BookmarkQueryService`
  （web 層不再算 offset、內部 `Long` PK 不再在 web 層流動）
  → `BookmarkController.java:31-33,40,50,63-68`
- **AUTH-08**：補 article null 檢查，500 → 404
  → `BookmarkController.java:40-41,50-51`

### 5.5 前端影響

**零改動。** API 契約不變，只是 `total` / `pages` 的數字變正確。

---

## 6. 守衛與測試

### 6.1 TDD（CLAUDE.md 鐵律，逐項 Red → Green → Refactor）

每一項先寫 failing test：

| 項目 | 測試斷言 |
|---|---|
| `countPublishedBySeriesIds` | 含 DRAFT / ARCHIVED 的 series 不出現在 key 集合；count 不計非 PUBLISHED |
| prev / next nav | 首篇無 prev、末篇無 next、跳過非 PUBLISHED |
| `batchGetSeriesBasicInfo` 改簽名 | `ArticleQueryServiceTest:593,607,613` 三處 mock 同步 |
| `filterReadableIds` | 匿名 / 一般登入 / 作者本人 / ADMIN 四種 viewer × PUBLISHED / DRAFT / ARCHIVED |
| 收藏分頁 | 10 筆收藏其中 3 筆已下架 → total ＝ 7、pages 正確、每頁筆數一致 |

測試輸出一律 `2>&1 | tee logs/test-output.log`；單一失敗先讀
`<module>/target/surefire-reports/TEST-*.xml`，不重跑全套（CLAUDE.md Test Execution Rules）。

### 6.2 ArchUnit 守衛（ARCH-03 守衛 #5）

規範條文到位後即可寫：

> `..module.(*)..mapper..` 的 `@Select` / `@Update` SQL 字面值不得出現他模組業務表名
> （`articles` / `comments`）；reference data（`users` / `tags`）不在此限。

⚠️ 此守衛必須把 reference / business 二分寫進規則，否則會誤判 PERF-01
（reindex 改 JOIN `users`）為違規——見 `audits/2026-09-04/triage.md` **T8**。

---

## 7. 明確不做（範圍外，避免後續 session 誤擴）

| 項目 | 理由 |
|---|---|
| 8 個跨模組 FK / 共用 PK | 只登記為新 findings 條目 **ARCH-30**，不動。動它等於改整個刪除語意 |
| DB view / read model | 方案 B，已否決（§2.4） |
| 事件複製（各模組自持副本） | 方案 D，可觀測性（ARCH-16）補上前為負收益 |
| `comments` 的跨模組讀取 | `CommentService.listComments` 已走 `ArticleVisibility`（`4045b03`） |
| findings D5 ~ D12 | 八項未決策，與本設計無關 |
| **SEC-04 全域分頁夾界** | **見 §7.1，有前置依賴** |

### 7.1 SEC-04 的證據（本輪查證，供下一 session 直接引用，勿重推）

把 `size` 夾成 `[1,100]` **不是一行 clamp**，它會在前端造成資料靜默遺失：

| 前端呼叫點 | 傳入 size | 夾成 100 的後果 |
|---|---|---|
| `ArticleList.vue:25` | **1000** | 主文章列表只剩前 100 篇；而 `:48` 是 `Math.ceil(filtered.length / 12)` 的**前端分頁**，分頁器會宣稱「這就是全部」 |
| `AuthorView.vue:46` | **200** | 作者頁靜默少一半 |
| `TagView.vue:48` | 100 | 剛好卡邊界 |
| `BookmarksView.vue:17` | 10 | 安全 |
| `AdminReviewView.vue:17` / `MyArticlesView.vue:27` | 10 | 安全 |
| `useComments.ts:12` | 20 | 安全 |

**前置依賴：前端需先把 `ArticleList` 與 `AuthorView` 改為真正的伺服器端分頁。**
這是跨 repo 的功能變更，不是順手夾界。對應 `triage.md` **T9**。

### 7.2 待 Yuan 於 spec review 決定的單一項目

**收藏端點自身的 `size` 護欄**（`BookmarkController.java:55-63`）：
前端只傳 10，夾界安全；且設計 C 讓 `getArticleSummariesByIds` 的頁大小更需要護欄。
可作為本批的獨立一行改動，亦可不做。**預設：不做**（保持本批範圍純粹），Yuan 可推翻。

---

## 8. 驗收線索

- series 列表：僅含草稿的 series 不出現於匿名列表；`article_count` 與詳情端點口徑一致
- series 導覽：首篇 `prev` 為 null、末篇 `next` 為 null，非 PUBLISHED 文章被跳過
- 收藏列表：10 筆收藏其中 3 筆下架 → `total` ＝ 7、`pages` ＝ ceil(7/size)、每頁筆數一致
- 收藏列表：作者本人與 ADMIN 仍看得到自己收藏的非公開文章（與詳情端點一致）
- `grep -rn "FROM articles\|JOIN articles" blog-module-series/` **零命中**
- ArchUnit 守衛 #5 綠燈，且 PERF-01 的 `JOIN users` 不被誤判
