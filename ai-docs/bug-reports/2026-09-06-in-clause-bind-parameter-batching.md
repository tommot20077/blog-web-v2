---
id: BUG-2026-003
date: 2026-09-06
commit: 1cfb489
modules: [article, comment, reading, series, infrastructure]
category: [Careless Oversight, Architectural Flaw]
severity: MEDIUM
impact: code-review-caught
---

# Bug Review: IN 子句補切批，界限從 JavaDoc 落到程式

## Summary

| Item | Detail |
|------|--------|
| ID | BUG-2026-003 |
| Date | 2026-09-06 |
| Commit | `1cfb489` |
| Modules | article, comment, reading, series, infrastructure |
| Category | Careless Oversight, Architectural Flaw |
| Severity | MEDIUM |
| Impact | code-review-caught |

## Symptoms

無使用者可見症狀——本缺陷在觸發前於 code review 被攔下。

潛在症狀：跨模組集合述詞方法（`filterReadableIds`、`countPublishedBySeriesIds`）
把整包 id 展開成單一 `IN (?, ?, ...)`，每個元素佔一個 bind parameter。
PostgreSQL extended protocol 上限為 65535，超過即拋 `PSQLException`，
**整個請求 500**——不是降級、不是變慢，且無重試路徑。

未觸發的原因純粹是資料量：個人部落格的收藏數與 series 數遠低於門檻。
換言之，**防線失效的事實與資料量無關，只是後果尚未兌現**。

## Root Cause Analysis

### 防線比缺陷更早存在，而且就在同一個類裡

`992cd00`（幽靈清除改為即時回查）為 `ArticleFacadeImpl.filterPublishedUuids`
引入了切批防線，並在 JavaDoc 明寫理由：

> 「空輸入直接回空集合，避免產生 `IN ()`；輸入過大時切批查詢，
> **避免單一 SQL 塞進上千個 bind 參數**。」

`8c85b5f`（ArticleFacade 新增 4 個集合述詞方法）在**同一個類**新增
`filterReadableIds` 與 `countPublishedBySeriesIds`，兩者都收無上界輸入
（某使用者的全部收藏、全表 series 主鍵），**都沒有沿用那條防線**。

### 真正的根因：界限寫進了 JavaDoc，沒寫進程式

新方法並非沒有意識到界限問題——它們的 JavaDoc 明確標註了門檻：

> 「傳輸量與 candidateIds 大小成正比 …… **重評門檻：任一使用者 B > 5,000。**」

問題在於這個標註：

1. **是給 reviewer 看的，不是給執行期用的**。JavaDoc 不會在 5,000 筆時做任何事。
2. **與同類別既有常數差 10 倍**（`PUBLISHED_FILTER_BATCH_SIZE = 500` vs 門檻 5,000），
   兩份標準並存於同一個檔案卻無人察覺。

這與 `institution-notes.md` 記載的「自我驗證矩陣會系統性漏掉最難的那一格」
是**同型問題的第二次出現**：都是「自述證據看起來完備，但恰好在真正承載安全性的
那一點上有缺口」。

### 次生發現

普查時發現界限失效不只三處。全 repo 共 15 個 `<foreach>` 展開點，
其中「以頁大小為界」的 8 處，其上界是 client 傳入的 `size`——
而 **全 repo 對 `size` 零夾界**（`grep Math.min(size` / `size > 100` / `@Max`
僅命中 version 模組與分頁無關的偏好設定），前端 `ArticleList.vue` 現行即傳 1000。
故那 8 處的「上界」是假的，實質同樣無上界。

> 註：第一次普查用 `foreach collection` 當 pattern（Java 單引號寫法），
> **漏掉 XML mapper 與 `CategoryMapper`**，導致清單少了 4 處。
> 正確 pattern 是 `<foreach`。這個普查缺口本身也是本次教訓的一部分。

### Before Fix

```java
public List<Long> filterReadableIds(List<Long> candidateIds, Long viewerId, boolean isAdmin) {
    if (candidateIds == null || candidateIds.isEmpty()) {
        return List.of();
    }
    Set<Long> readable = articleMapper.findVisibilityRowsByIds(candidateIds).stream()
            .filter(row -> ArticleVisibility.isReadableBy(
                    row.status(), row.authorId(), viewerId, isAdmin))
            .map(ArticleMapper.ArticleVisibilityRow::id)
            .collect(Collectors.toSet());
    return candidateIds.stream().filter(readable::contains).toList();
}
```

### After Fix

```java
public List<Long> filterReadableIds(List<Long> candidateIds, Long viewerId, boolean isAdmin) {
    if (candidateIds == null) {
        return List.of();
    }
    Set<Long> readable = BatchedQuery
            .queryInBatches(candidateIds, articleMapper::findVisibilityRowsByIds).stream()
            .filter(row -> ArticleVisibility.isReadableBy(
                    row.status(), row.authorId(), viewerId, isAdmin))
            .map(ArticleMapper.ArticleVisibilityRow::id)
            .collect(Collectors.toSet());
    return candidateIds.stream().filter(readable::contains).toList();
}
```

### 修復時的關鍵判斷：切批不是無腦可套

15 處中有 **1 處禁止切批**：XML `findByTagIds` 帶
`DISTINCT ＋ ORDER BY view_count DESC ＋ LIMIT`——各批的前 N 名合併
**不等於**全域前 N 名。對它套切批會**靜默給出錯誤答案**，比不切批更危險。
（該處也剛好是唯一輸入有天然上界者：一篇文章的 tag 數。）

另有 1 處**不需要**切批：`SeriesMapper.findByIdsWithAuthor` 的輸入以
`SeriesService.MAX_PAGE_SIZE`（100）為界，低於 `BATCH_SIZE`（500），
接上去會是寫不出測試的死路徑；改為在呼叫端註明界限來源。

帶 `ORDER BY` 但無 `LIMIT` 的 2 處判定為安全：呼叫端皆以 `groupingBy` 消費，
且同一 key 的列必落在同一批，故唯一被消費的組內順序在切批後仍成立。

## Affected Files

**新增（`7e70b5d`）**

- `blog-infrastructure/.../persistence/BatchedQuery.java`
- `blog-infrastructure/.../persistence/BatchedQueryTest.java`

**修改（`1cfb489`）**

- `blog-module-article/.../facade/ArticleFacadeImpl.java`
- `blog-module-article/.../service/ArticleQueryService.java`
- `blog-module-article/.../service/ArticleResponseMapper.java`
- `blog-module-comment/.../service/CommentService.java`
- `blog-module-reading/.../service/ArticleLikeService.java`
- `blog-module-reading/.../service/BookmarkService.java`
- `blog-module-series/.../facade/SeriesFacadeImpl.java`
- `blog-module-series/.../service/SeriesService.java`（僅註明界限來源）

**新增測試（`1cfb489`）**

- `ArticleFacadeBatchingTest`、`ArticleQueryServiceBatchingTest`、
  `ArticleResponseMapperBatchingTest`、`CommentBatchingTest`、
  `ReadingBatchingTest`、`SeriesBatchingTest`，
  以及 `ArticleFacadeSetPredicateTest` 新增 2 個切批案例

## Timeline

| Event | Time / Commit |
|-------|--------------|
| 防線建立 | `992cd00` — `PUBLISHED_FILTER_BATCH_SIZE = 500` 與其 JavaDoc 理由 |
| Introduced | `8c85b5f` — 同類別新增 2 個集合述詞方法，未沿用防線，界限僅寫入 JavaDoc |
| 缺口擴大 | 集合述詞規範落地時，`filterReadableIds` 被 `BookmarkQueryService` 用於「全量收藏 id」路徑，輸入正式變為無上界 |
| Discovered | 2026-09-06，架構討論（「表要不要開放共用 join」）中檢視集合述詞落地成果時發現 |
| Fixed | `1cfb489` |

## Preventive Measures

| Measure | Status |
|---------|--------|
| 切批收斂為單一實作 `BatchedQuery`，`PUBLISHED_FILTER_BATCH_SIZE` 移除，500 只剩一個來源 | **DONE**（`7e70b5d` / `1cfb489`）|
| `BatchedQuery` JavaDoc 寫死「跨批可合併」的適用範圍與禁用條件，使下一個使用者不會把 `ORDER BY`+`LIMIT` 查詢套進去 | **DONE**（`7e70b5d`）|
| 分頁 `size` 上界改為型別保證（`PageQuery` record compact constructor），消除 8 處「假上界」 | **DONE**（`815fa85`）|
| `architecture.md` 補條文：無上界輸入必須切批（不得只標 JavaDoc）、切批僅限跨批可合併形狀、「以頁大小為界」不等於有上界、`NOT EXISTS` 優於 `NOT IN`、述詞三格分類與方案 B 否決範圍的限定 | **DONE**（2026-09-06，`ai-docs/architecture.md`）|
| 機械化守衛（防止新端點再度手寫無上界 IN） | **TODO**（backlog: `ai-docs/backlog/2026-09-06-in-clause-batching-and-page-bounds.md` §5）——已評估 ArchUnit 不可行：現行寫法為 `@RequestParam(defaultValue = "10") int size`，**參數名不在 annotation 內**，ArchUnit 讀 bytecode 取不到，無法比照守衛 #5 的做法 |

## Lesson Learned

**界限寫進 JavaDoc 是給 reviewer 看的，寫進程式才是給執行期用的**——
當同一個類裡已經存在一條防線，新增同類方法時沒有沿用它，
比從頭沒想到更值得記錄：因為證據就在隔壁，而且兩份標準（500 與 5,000）
並存了一整輪 review 都沒有人察覺。
