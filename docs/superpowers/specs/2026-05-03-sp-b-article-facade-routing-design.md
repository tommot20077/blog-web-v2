# SP-B：Article Facade Routing — 設計文件

> **Spec date:** 2026-05-03
> **Branch:** `refactor/sp-b-article-facade-routing`
> **Roadmap parent:** `docs/superpowers/specs/2026-05-03-architecture-decoupling-roadmap.md`（§5.2）
> **Status:** Approved by Yuan, ready for implementation plan

---

## 1. Goal & 範圍

對齊 roadmap §5.2。SP-B 是 4 個 sub-projects 第二個，負責：

1. **解掉跨模組直接 inject `ArticleService` 的 anti-pattern**：3 個模組（comment / reading / series）共 9 個 class 改用 `ArticleFacade`
2. **徹底消除 SeriesFacadeImpl 的 `@Lazy ArticleService` setter injection**（SP-A 留下的循環依賴 workaround）
3. **擴充 ArticleFacade method 集合**：補 5 個 read + 5 個 write method 涵蓋跨模組實際需求

**範圍邊界（不在 SP-B 做）:**
- ❌ Version 模組：不 inject ArticleService，但 inject `ArticleRepository` —屬 SP-D 範圍
- ❌ ArticleService 內部結構縮減：拆 god class 屬 SP-C 範圍
- ❌ Write methods 改 event-driven：Counter / series assignment 是 simple write 不需 event 化（roadmap §4 原則 1 不適用此類別）
- ❌ ArticleQueryService 跨模組 inject 議題（BookmarkController 仍 inject ArticleQueryService）— 屬未來 SP-X
- ❌ Tag 模組 inject ArticleService — 經 grep 確認 tag 模組無此 inject

**Done definition:**
- 全 codebase grep `private final ArticleService\|ArticleService articleService` 只剩 article 模組內部
- 全 codebase grep `@Lazy.*ArticleService\|@Setter.*@Lazy` 結果為 0
- 5 affected modules（article / comment / reading / series + infrastructure）tests 全綠

---

## 2. 設計決策摘要

| 決策點 | 選擇 | 理由 |
|------|------|------|
| Write methods 是否進 facade | **是**（5 個 simple write） | counter / 欄位 set 是 simple write，符合「facade 封裝跨模組 API」精神；既有 TagFacade 也含 write methods（既有 pattern）|
| Write methods 是否改 event-driven | 不改 | counter / series 場景需要立即一致性（UX）；event 化 over-engineering；未必每個 write 都該 event 化 |
| ArticleFacadeImpl 結構 | 純 delegate（內部 inject ArticleService）| article 模組內部 inject ArticleService 不算跨模組 anti-pattern；簡單清楚 |
| ArticleService 公開範圍 | 不縮減（24 method 全保留 public） | article 模組內部 controller / ArticleQueryService 仍需要；跨模組約束靠 review 把關 |
| @Lazy 解決方式 | SeriesFacadeImpl inject ArticleFacade | ArticleFacade interface 在 infrastructure，跟 SeriesFacade interface 同層；Spring DI 不再形成循環 |
| 跨模組 IT 變化 | 不需新 cross IT | 純 inject refactor，邏輯不變；既有 IT mock 改 ArticleFacade 即可 |

---

## 3. 跨模組現況盤點（audit findings）

來源：SP-B brainstorm explorer agent 完整 audit。

### 3.1 既有 ArticleFacade methods（6 個）

```
findAllPublishedForIndex()                                     // search 索引初始化
getPublishedArticleBasicInfo(UUID)                              // recommend 撈 article 元資料
getArticlesByTagIds(List<UUID>, UUID, int)                      // recommend 推薦
getRecentPublishedArticles(UUID, int)                           // recommend 推薦
getPublishedArticlesByUuids(List<UUID>)                         // search 結果展開
getArticlesPublishedAfter(LocalDateTime)                        // recommend 趨勢
```

被 inject 模組：search（1 處）、recommend（1 處）。

### 3.2 跨模組對 ArticleService 的呼叫（15 個 method 類型）

| 模組 | inject 點 | 呼叫的 method |
|---|---|---|
| comment | CommentService (1) | findIdByUuid (×2)、incrementCommentCount、decrementCommentCount |
| reading | ArticleLikeController / BookmarkController / ArticleLikeService / HighlightService / ReadingProgressService / ReadingProgressFlushJob (6) | findIdByUuid (×6)、incrementLikeCount、decrementLikeCount、findByIds |
| series | SeriesService / SeriesFacadeImpl (2) | findById、findByUuid (×2)、updateSeriesAssignment (×2)、findBySeriesIdOrderByPosition |
| version | （無）| 無（用 ArticleRepository — SP-D 範圍）|

**去重後跨模組實際 method 類型：** 9 個
- Read（5）：findIdByUuid / findById / findByUuid / findByIds / findBySeriesIdOrderByPosition
- Write（5）：incrementCommentCount / decrementCommentCount / incrementLikeCount / decrementLikeCount / updateSeriesAssignment

### 3.3 SeriesFacadeImpl @Lazy 問題

**File:** `blog-module-series/src/main/java/dowob/xyz/blog/module/series/facade/SeriesFacadeImpl.java`

```java
@Setter(onMethod_ = {@Autowired, @Lazy})
private ArticleService articleService;
```

**用法:** `getSeriesNavigation` 內 `articleService.findById(articleId)`

**SP-A 為何留下:** SP-A 移除了 `notifyArticleDeletedFromSeries` 但 `getSeriesNavigation` 仍需要 ArticleService — 留待 SP-B 在 ArticleFacade 補 `findById` 後解。

---

## 4. ArticleFacade 新增 method 清單

### 4.1 Read methods（5 個）

```java
/** UUID → DB id（最高頻跨模組查詢，6 處 reading 用 + 2 處 comment 用） */
Long findIdByUuid(UUID articleUuid);

/** UUID → Article 完整 entity */
Optional<Article> findByUuid(UUID articleUuid);

/** DB id → Article 完整 entity（給 SeriesFacade.getSeriesNavigation 用 — 解 @Lazy） */
Optional<Article> findById(Long articleId);

/** 批次 id 查 article（給 ReadingProgressService 用） */
List<Article> findByIds(List<Long> articleIds);

/** 撈 series 內 article 排序好（給 SeriesService.getSeriesDetail 用） */
List<Article> findBySeriesIdOrderByPosition(Long seriesId);
```

### 4.2 Write methods（5 個）

```java
/** comment 模組創建 / 刪除 comment 時連動 article.comment_count */
void incrementCommentCount(Long articleId);
void decrementCommentCount(Long articleId);

/** reading 模組 like / unlike article 時連動 article.like_count */
void incrementLikeCount(Long articleId);
void decrementLikeCount(Long articleId);

/** series 模組 add / remove article from series 時 set article.series_id + series_position */
void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition);
```

### 4.3 為何 write methods 進 facade 是合理選擇

- 既有 TagFacade 已含 write methods（`deleteArticleTags` / `syncArticleTags` / `findOrCreateTags`）— 是專案 pattern
- 這 5 個 write 都是 **simple counter / 欄位 set**：無業務邏輯、無觸發鏈、caller 需要立即一致性
- 對應的 event-driven 替代方案會引入：6 個 new event + 6 個 consumer + 6 套 idempotency = 過度工程化
- 本專案規模（個人部落格）的 counter / series 場景不需要 eventual consistency

**真正該 event 化的 write 條件**（roadmap §4 原則 1 適用）:
- Caller 不需要立即一致性
- Method 觸發複雜業務邏輯（trigger N 個其他操作）
- 失敗不該影響 caller transaction

SP-B 的 5 個 write methods 都不符合上述條件，因此 facade 是對的工具。

---

## 5. ArticleFacadeImpl — 純 delegate

```java
@Service
@RequiredArgsConstructor
public class ArticleFacadeImpl implements ArticleFacade {

    private final ArticleService articleService;
    // 既有 6 個 method 的依賴（如 articleMapper / userFacade 等）也保留

    // ─── SP-B 新增 5 read 全部 delegate 到 articleService ───
    @Override
    public Long findIdByUuid(UUID articleUuid) {
        return articleService.findIdByUuid(articleUuid);
    }

    @Override
    public Optional<Article> findByUuid(UUID articleUuid) {
        return articleService.findByUuid(articleUuid);
    }

    @Override
    public Optional<Article> findById(Long articleId) {
        return articleService.findById(articleId);
    }

    @Override
    public List<Article> findByIds(List<Long> articleIds) {
        return articleService.findByIds(articleIds);
    }

    @Override
    public List<Article> findBySeriesIdOrderByPosition(Long seriesId) {
        return articleService.findBySeriesIdOrderByPosition(seriesId);
    }

    // ─── SP-B 新增 5 write 全部 delegate ───
    @Override
    public void incrementCommentCount(Long articleId) {
        articleService.incrementCommentCount(articleId);
    }
    // 其他 4 個 write delegate 同上模式
}
```

**設計選擇：**
- ArticleFacadeImpl **不放業務邏輯** — 純 delegate
- ArticleFacadeImpl 在 article 模組內部，inject ArticleService 是同模組依賴（不算跨模組 anti-pattern）
- 邏輯都在 ArticleService — facade 只是「對外 API 包裝層」

---

## 6. 3 模組 inject 改動（12 處）

### 6.1 Comment 模組（1 處）

**File:** `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/service/CommentService.java`

改動：
```java
// Before
private final ArticleService articleService;
// 內部呼叫
articleService.findIdByUuid(articleUuid);
articleService.incrementCommentCount(articleId);
articleService.decrementCommentCount(c.getArticleId());

// After
private final ArticleFacade articleFacade;
// 內部呼叫
articleFacade.findIdByUuid(articleUuid);
articleFacade.incrementCommentCount(articleId);
articleFacade.decrementCommentCount(c.getArticleId());
```

對應 `CommentServiceTest`：`@Mock ArticleService` → `@Mock ArticleFacade`，`when/verify` 同步調整。

### 6.2 Reading 模組（6 處）

#### ArticleLikeController.java
```java
private final ArticleService articleService;  // → ArticleFacade
articleService.findIdByUuid(articleUuid);     // → articleFacade.findIdByUuid
```

#### BookmarkController.java
```java
private final ArticleService articleService;
private final ArticleQueryService articleQueryService;  // 保留（ArticleQueryService 跨模組是 SP-X 範圍，不在 SP-B 動）
// articleService inject 改 ArticleFacade
articleService.findIdByUuid(articleUuid);  // → articleFacade.findIdByUuid（2 處）
```

#### ArticleLikeService.java
```java
private final ArticleService articleService;  // → ArticleFacade
articleService.incrementLikeCount(articleId);  // → articleFacade
articleService.decrementLikeCount(articleId);  // → articleFacade
```

#### HighlightService.java
```java
private final ArticleService articleService;  // → ArticleFacade
articleService.findIdByUuid(articleUuid);  // → articleFacade（2 處）
```

#### ReadingProgressService.java
```java
private final ArticleService articleService;  // → ArticleFacade
articleService.findIdByUuid(articleUuid);  // → articleFacade（2 處）
articleService.findByIds(articleIds);       // → articleFacade
```

#### ReadingProgressFlushJob.java
```java
private final ArticleService articleService;  // → ArticleFacade
articleService.findIdByUuid(articleUuid);  // → articleFacade
```

對應 IT / unit tests：`@Mock` 從 ArticleService → ArticleFacade，`when/verify` 同步調整。

### 6.3 Series 模組（2 處）

#### SeriesService.java
```java
private final ArticleService articleService;  // → ArticleFacade
articleService.findByUuid(articleUuid);  // → articleFacade（2 處 — addArticleToSeries / removeArticleFromSeries）
articleService.updateSeriesAssignment(...);  // → articleFacade（2 處）
articleService.findBySeriesIdOrderByPosition(seriesId);  // → articleFacade
```

#### SeriesFacadeImpl.java
```java
// Before
@Setter(onMethod_ = {@Autowired, @Lazy})
private ArticleService articleService;
// 內部呼叫
articleService.findById(articleId);

// After
private final ArticleFacade articleFacade;  // constructor inject (透過 @RequiredArgsConstructor)
// 內部呼叫
articleFacade.findById(articleId);
```

⚠ 移除 `@Setter` `@Lazy` `@Autowired` annotations。改用 `@RequiredArgsConstructor` 標準 constructor inject。

⚠ Imports 清理：`import org.springframework.context.annotation.Lazy;` `import org.springframework.beans.factory.annotation.Autowired;` `import lombok.Setter;` 不再需要。

---

## 7. @Lazy 移除驗證

**Before SP-B:**
```
ArticleServiceImpl ── (no inject after SP-A) ── SeriesFacade
SeriesFacadeImpl ── @Lazy setter ── ArticleService
                          ↑ 為了解循環依賴
```

**After SP-B:**
```
SeriesFacadeImpl ── constructor inject ── ArticleFacade (interface in infrastructure)
ArticleFacadeImpl ── constructor inject ── ArticleService (article 模組內部)
```

**為何不再循環:**
- SP-A 已移除 ArticleServiceImpl → SeriesFacade 的依賴
- SeriesFacadeImpl 改 inject ArticleFacade（interface）— 透過 facade 抽象層解耦
- ArticleFacadeImpl 在 article 模組，inject ArticleService 是同模組依賴
- Spring DI 啟動時 bean graph 是 DAG，無循環

**驗證:**
- 全 codebase grep `@Lazy` 結果為 0（main code）
- 全 codebase grep `@Setter.*@Lazy` 為 0
- Spring 啟動正常，無 `BeanCurrentlyInCreationException`

---

## 8. ArticleService 公開範圍策略

### 8.1 不縮減 ArticleService method visibility

**理由:**
- ArticleService 24 個 method 中許多被 article 模組內部 controller / ArticleQueryService 用（如 createArticle / publishArticle / getArticleByUuid 等）
- Java interface method 預設 public，無 package-private 等中間級別
- 強行縮減（拆 internal interface）會增加 SP-B 範圍

### 8.2 跨模組 inject 約束靠 review 把關

**規範:**
- PR review 時 grep `private final ArticleService` — 應該只剩 article 模組內部
- 違反時 reviewer reject

**未來強化選項（SP-X 可考慮）:**
- 拆 `ArticleInternalService`（package-private interface 給 article 內部用） + `ArticleFacade`（public interface 給跨模組用）
- 但這會擴大 method 重複定義，本 SP 不做

---

## 9. ArticleQueryService 跨模組議題（不在 SP-B 範圍）

**現狀：** `BookmarkController` (reading 模組) inject `ArticleQueryService`（article 模組內部 class）

**為何不在 SP-B 範圍:**
- SP-B 目標是「ArticleService 跨模組 inject 改 ArticleFacade」— scope 限縮在 ArticleService
- ArticleQueryService 跨模組是另一個獨立 issue（read facade 的職責邊界）
- 處理它涉及：是否把 `getArticleSummariesByIds` 加進 ArticleFacade / 新建 ArticleQueryFacade / 維持現狀
- 留給未來 SP-X（roadmap §8 後續批次預告）

**SP-B 對 ArticleQueryService 的處理:**
- 不動 — `BookmarkController` 仍 inject ArticleQueryService
- 但 BookmarkController 同時 inject 的 `ArticleService` 改 ArticleFacade（SP-B 範圍）

---

## 10. 測試策略

### 10.1 ArticleFacadeImpl unit tests（10 個新）

每個新 method 1 個 delegate verify test：

```java
@Test
void findIdByUuid_delegatesToArticleService() {
    UUID uuid = UUID.randomUUID();
    when(articleService.findIdByUuid(uuid)).thenReturn(100L);

    Long result = facade.findIdByUuid(uuid);

    assertThat(result).isEqualTo(100L);
    verify(articleService).findIdByUuid(uuid);
}
```

10 個 delegate test（5 read + 5 write）。

### 10.2 既有跨模組 unit + IT mock 改動

**Comment 模組:**
- `CommentServiceTest`：`@Mock ArticleService` → `@Mock ArticleFacade`，3 處 verify 同步改

**Reading 模組:**
- `ArticleLikeServiceTest` / `HighlightServiceTest` / `ReadingProgressServiceTest`：mock 改 ArticleFacade
- `ArticleLikeControllerIT` / `BookmarkControllerIT` / `ReadingProgressControllerIT`：`@MockitoBean ArticleService` → `@MockitoBean ArticleFacade`
- `ReadingProgressFlushJobTest`：mock 改 ArticleFacade

**Series 模組:**
- `SeriesServiceTest`：mock 改 ArticleFacade，5 處 verify 同步改
- `SeriesFacadeImpl` 沒對應 unit test（既有）— SeriesControllerIT 可驗 getSeriesNavigation 路徑無 @Lazy

### 10.3 不需新 cross-module IT

**理由：** SP-B 是純 inject refactor，邏輯不變。既有 cross-module IT（如 CrossModuleSeriesIT）執行同樣業務邏輯，跑完仍綠就證明 routing 改動 OK。

### 10.4 全模組 sanity

最終 commit 前跑：
```bash
./mvnw.cmd test
```

預期 BUILD SUCCESS。

---

## 11. 模組依賴變化

### Before SP-B

```
[Cross-module ArticleService inject - anti-pattern]

comment ──────────────→ ArticleService
reading (6 處) ───────→ ArticleService
series ───────────────→ ArticleService
                       (+ @Lazy from SeriesFacadeImpl)
```

### After SP-B

```
[Cross-module 統一走 ArticleFacade]

comment ──────────────→ ArticleFacade ──→ ArticleFacadeImpl ──→ ArticleService
reading (6 處) ───────→ ArticleFacade ──→ (article module internal)
series ───────────────→ ArticleFacade
SeriesFacadeImpl ─────→ ArticleFacade  (取代 @Lazy)

ArticleService 仍存在但**只給 article 模組內部用**：
- ArticleController
- AdminArticleController
- ArticleQueryService
- ArticleFacadeImpl (delegate)
```

---

## 12. Limitations / Future Work

1. **ArticleService 24 method 仍 public** — 跨模組約束靠 review 把關，未來可考慮拆 `ArticleInternalService` + `ArticleFacade` 強化邊界

2. **ArticleQueryService 跨模組 inject 議題未解** — `BookmarkController` 仍 inject ArticleQueryService。未來 SP-X 處理（可能加進 ArticleFacade 或新建 ArticleQueryFacade）

3. **Tag 模組無 inject ArticleService 確認過** — 經 grep 確認，SP-B 不需動 tag 模組

4. **Version 模組屬 SP-D 範圍** — 它 inject ArticleRepository 而非 ArticleService，不在 SP-B 範圍

5. **Write methods 暫不 event 化** — counter / series 場景需要立即一致性，event 化 over-engineering。未來真有高並發或最終一致性需求再評估

---

## 13. Implementation Plan 預估（~10-12 tasks）

```
T1   ArticleFacade interface 加 5 read method 宣告
T2   ArticleFacade interface 加 5 write method 宣告
T3   ArticleFacadeImpl delegate 10 個新 method + 10 個 unit tests
T4   Comment 模組改 inject ArticleFacade（CommentService + CommentServiceTest）
T5   Reading 模組改 inject ArticleFacade — Service layer
     （ArticleLikeService / HighlightService / ReadingProgressService + tests）
T6   Reading 模組改 inject ArticleFacade — Controller / Job layer
     （ArticleLikeController / BookmarkController / ReadingProgressFlushJob + IT）
T7   Series 模組 SeriesService 改 inject ArticleFacade + tests
T8   Series 模組 SeriesFacadeImpl 移除 @Lazy → ArticleFacade（含 imports 清理）
T9   全模組 grep verify：`private final ArticleService` 只剩 article 內部 + `@Lazy` = 0
T10  全模組 sanity test
T11  (optional) ai-docs/architecture.md 更新跨模組通訊規範
```

---

## 14. 後續批次預告

完成 SP-B 後接 **SP-D（events-and-helpers-cleanup）**：

- TagFacade.deleteArticleTags 改 event（用 SP-A 留的 categoryIds / tagIds payload）
- AutoSnapshotPolicy / VersioningService 改 inject ArticleFacade（用 SP-B 補的 method）
- SecurityUtils.isAdmin 提取到 infrastructure
