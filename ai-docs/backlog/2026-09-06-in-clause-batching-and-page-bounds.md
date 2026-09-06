# 落地：IN 子句切批（BatchedQuery）＋ 分頁 size 上界（PageQuery）

- **建立日期**: 2026-09-06
- **狀態**: 程式碼已落地、測試全綠；**規範修訂待 Yuan 簽核**
- **來源**: `feat/cross-module-set-predicate` 分支的架構討論（集合述詞規範的後續）
- **類型**: 安全加固 ＋ 規範提案

---

## 1. 觸發點

檢視集合述詞落地成果時發現：`ArticleFacadeImpl` 同一個類裡，
既有的 `filterPublishedUuids` **有切批**（`PUBLISHED_FILTER_BATCH_SIZE = 500`，
JavaDoc 明寫「避免單一 SQL 塞進上千個 bind 參數」），
但本輪新增的 `filterReadableIds` 與 `countPublishedBySeriesIds` **都沒有**。

兩者的界限只寫進了 JavaDoc（「重評門檻 B > 5,000」），沒有寫進程式。
**同型教訓**：`institution-notes.md` 的「自我驗證矩陣會系統性漏掉最難的那一格」。

## 2. 完整普查：15 個 `<foreach>` IN 展開點

> ⚠️ 第一次普查用 `foreach collection`（Java 單引號寫法）當 pattern，
> **漏掉 XML mapper 與 `CategoryMapper`**。正確 pattern 是 `<foreach`。

| # | 方法 | SQL 形狀 | 判定 |
|---|---|---|---|
| 1 | `ArticleMapper.findPublishedUuidsIn` | WHERE IN | 已切批（改走 BatchedQuery）|
| 2 | `ArticleMapper.countPublishedBySeriesIds` | WHERE IN | 補切批 |
| 3 | `ArticleMapper.findVisibilityRowsByIds` | WHERE IN | 補切批 |
| 4 | `ArticleMapper.findIdsByUuids` | WHERE IN | 補切批 |
| 5 | `ArticleMapper.findTagsByArticleUuids` | WHERE IN | 補切批 |
| 6 | `CategoryMapper.findCategoriesByArticleIds` | WHERE IN | 補切批 |
| 7 | XML `findByUuids` | WHERE IN | 補切批 |
| 8 | XML `findTagsByArticleUuids` | ORDER BY，無 LIMIT | 補切批（呼叫端 groupingBy）|
| 9 | **XML `findByTagIds`** | **DISTINCT ＋ ORDER BY view_count DESC ＋ LIMIT** | **禁止切批** |
| 10 | `CommentMapper.findRepliesByParentIds` | ORDER BY，無 LIMIT | 補切批（呼叫端 groupingBy）|
| 11 | `CommentMapper.findLikedCommentIdsByUser` | WHERE IN | 補切批（**輸入最大**：top-level ＋ 全部 replies）|
| 12 | `ArticleLikeMapper.findLikedArticleIdsByUser` | WHERE IN | 補切批 |
| 13 | `BookmarkMapper.findBookmarkedArticleIdsByUser` | WHERE IN | 補切批 |
| 14 | `SeriesMapper.findBasicInfoBySeriesIds` | WHERE IN | 補切批 |
| 15 | **`SeriesMapper.findByIdsWithAuthor`** | WHERE IN | **不切批**：輸入以 `SeriesService.MAX_PAGE_SIZE`（100）為界，低於 BATCH_SIZE（500），接上去是寫不出測試的死路徑；已於呼叫端註明界限來源 |

**#9 的理由（重要）**：各批的前 N 名合併 ≠ 全域前 N 名。切批會**靜默給出錯誤答案**，
比不切批更危險。它也剛好是唯一輸入有天然上界者（一篇文章的 tag 數）。

## 3. 落地內容

### 3.1 `BatchedQuery`（`blog-infrastructure/.../persistence/`）

```java
public static <I, O> List<O> queryInBatches(Collection<I> inputs, Function<List<I>, List<O>> query)
```

- `BATCH_SIZE = 500`，與既有 `PUBLISHED_FILTER_BATCH_SIZE` 對齊並取代之（常數收斂為單一來源）
- 空輸入不呼叫 query（迴圈不進入），避免 `IN ()`
- JavaDoc 寫死**適用範圍**：僅限跨批可合併形狀（集合聯集、key 不重疊的 Map 合併）；
  **禁用**於 `ORDER BY` ＋ `LIMIT` 或跨批聚合

### 3.2 `PageQuery`（`blog-common/.../api/request/`）

record ＋ compact constructor，把 size 上界變成**型別保證**而非呼叫端紀律：

```java
public record PageQuery(Integer page, Integer size) {
    public static final int MAX_SIZE = 1000;
    public PageQuery {
        page = (page == null || page < 1) ? 1 : page;
        size = (size == null) ? null : Math.min(Math.max(size, 1), MAX_SIZE);
    }
    public int sizeOrDefault(int fallback) { ... }
}
```

- **為何不可能漏**：record 欄位 final，所有建構路徑都必須經過 canonical constructor
  （其他 constructor 語法上強制 `this(...)` 委派），compact constructor 是唯一入口
- **為何用 `Integer` 而非 `int`**：區分「未提供」（null）與「明確傳 0」；用 `int` 會讓
  未提供綁成 0
- **為何預設值不由本型別決定**：8 個端點的 `defaultValue` 不一致（4 個 10、4 個 20），
  統一即 API 語意變更（`judgment.md` §5 須 Yuan 決定）。故只收上下界，預設留給端點
- **API 契約零變更**：query string 仍是 `?page=&size=`，Spring constructor binding 綁定
- **MAX_SIZE 取 1000 而非 100**：前端 `ArticleList.vue` 現行即傳 1000。
  SEC-04 的完整修法（夾到 100）前置依賴前端改真分頁，見該條目

已改造端點：Admin文章／文章列表／我的文章／留言／收藏／搜尋／系列／版本（8 處）

## 4. 規範提案（待 Yuan 簽核後寫入 `architecture.md`）

> `architecture.md` 為 ⚠️ 提案制（`maintenance.md` §2）

1. **三格分類表**（取代目前僅靠散文描述的 `Published*` / `Readable*` 區分）

   | 述詞性質 | 判準 | 跨模組作法 | 門檻觸發時的退路 |
   |---|---|---|---|
   | reference data 投影 | schema 穩定、只讀 | 直接 JOIN | 不變 |
   | 狀態字面量集合述詞 | 述詞**不含 viewer 參數** | owner facade | **可退到 owner 唯讀 view ＋ JOIN**，真相仍單份 |
   | viewer-dependent 政策 | 述詞**含 viewer 身分** | owner facade | **不得下推**，下推即政策第二份 |

2. **限定方案 B 的否決範圍**（`2026-09-04-cross-module-set-predicate.md` §2.4）

   > B（DB view ＋ JOIN）的否決理由是「可見性 viewer-dependent，view 包不住」。
   > **該理由只涵蓋第三格。** 第二格的述詞不含 viewer 參數，view 包得住，
   > 真相歸屬與 facade 方法等價。未來重評時不得把 B 整批視為已否決，
   > 須先判定該述詞落在哪一格。

3. **界限條文**（取代現行「必須在 JavaDoc 標註門檻」）

   > 集合述詞方法的輸入若無上界，**必須在實作端切批**（走 `BatchedQuery`），
   > 不得只在 JavaDoc 標註門檻。JavaDoc 標註是給 reviewer 看的，切批才是給執行期用的。
   > 切批**只對跨批可合併的查詢安全**；含 `ORDER BY` ＋ `LIMIT` 或跨批聚合者禁用。

4. **`NOT EXISTS` 優於 `NOT IN`**（預防性，repo 目前零命中）

   > `NOT IN` 遇子查詢含 NULL 時，三值邏輯下結果恆為空集合，且 planner 因須保留該語意
   > **無法**轉成 anti-join。一律寫 `NOT EXISTS`。

## 5. 未做 / 待決

| 項目 | 說明 |
|---|---|
| 可觀測性打點 | `spring-boot-starter-actuator` 已在 `blog-start/pom.xml:31`，**micrometer-core 1.15.7 已在 compile classpath**（`dependency:tree` 實測，ARCH-16 記載的「無依賴」僅指直接宣告）。要讓門檻自己叫，最小成本是 `exposure.include` 加 `metrics` ＋ 在集合述詞方法打 `DistributionSummary`。**主動告警**另需 prometheus registry ＋ k3s scrape |
| `= ANY(?)` 陣列參數 | 可一併解掉 bind 上限、planning time、prepared statement cache 失效（MyBatis `<foreach>` 讓每個長度都是不同 SQL 文本，配合 PG JDBC 預設 `prepareThreshold=5` 幾乎永遠湊不滿）。需 array TypeHandler（`UUIDTypeHandler` 可為範例）。**門檻觸發時的退路，現在不做** |
| SEC-04 夾到 100 | 前置依賴前端改真分頁，未動 |
| ArchUnit 守衛 | 想加「controller 不得手寫分頁 `@RequestParam`」，但現行 8 處都寫成 `@RequestParam(defaultValue="10") int size`——**參數名不在 annotation 裡，ArchUnit 讀 bytecode 看不到**，故無法機械化。只能靠上述規範文字 |

## 6. 踩到的既有地雷：`SearchController` 的 CRLF blob

`git diff` 對 `SearchController.java` 顯示 **121 行整檔變更**，但以 `git cat-file blob`
直接比對，實際內容差異只有 **10 行**。

根因：`.gitattributes` 有 `*.java text eol=lf`，git 比對時把工作區正規化成 LF；
而該檔的 **blob 仍是 CRLF**（`.gitattributes` 之前的遺留，從未 `git add --renormalize`），
於是每行都被判為不同。對照組 `SeriesController` 的 blob 已是 LF，diff 正常顯示 3 行。

**這不是本次改動造成的**——任何人碰這個檔案都會浮現。
`institution-notes.md`「2026-09-02 分支整理紀錄」已記載此問題，
並註明是否做一次 renormalize 由 Yuan 決定。本次**未自行 renormalize**。

## 7. 驗收證據

- `BatchedQueryTest` 9 綠，含兩個 off-by-one 邊界（輸入**剛好** 500、**剛好** 1000，
  兩者都不得多發一次空批）
- `PageQueryTest` 13 綠，其中 3 個是 **MockMvc 實測**：`?page=-5&size=99999` 打進 controller
  收到 `1:1000`——證明 Spring constructor binding 確實觸發 compact constructor，非推論
- 各模組切批守衛：`ReadingBatchingTest` 2、`SeriesBatchingTest` 1、
  `ArticleResponseMapperBatchingTest` 2、`ArticleFacadeBatchingTest` 1、
  `CommentBatchingTest` 1、`ArticleQueryServiceBatchingTest` 1、
  `ArticleFacadeSetPredicateTest` 新增 2
- 全套：blog-common / infrastructure / user / article / tag / search / recommend /
  series / reading / comment / version 全 SUCCESS；`blog-start` 的 ArchUnit
  **守衛 #5 綠**（`CrossModuleBoundaryTest`）
- 唯一 FAILURE 是 `blog-module-file` 的 `*IT`，根因
  `Could not find a valid Docker environment`（Testcontainers），與本次改動無關
