# SP-C：Article Service Split — 設計文件

> **Status:** Drafted by brainstorm with Yuan, awaiting user spec review.
>
> **Roadmap reference:** `docs/superpowers/specs/2026-05-03-architecture-decoupling-roadmap.md` §5.4
>
> **Worktree:** `.worktrees/refactor-sp-c-article-service-split`
>
> **Branch:** `refactor/sp-c-article-service-split` (from `develop@c5b5484` 含 SP-A/SP-B/SP-D merge)

---

## 1. Goal & 範圍

對齊 roadmap §5.4。SP-C 是 4 個 sub-projects 第 4 個（最後一個），承接 SP-A/B/D 已穩定的跨模組邊界，**內部拆解** `ArticleServiceImpl` 1020 行 god class。

**核心原則**：保留 `ArticleService` interface 對外 contract 不變（24 method 全保留），impl 內部拆分為 3 個 package-private sub-service + 2 個 helper class。對外 caller 零改動。

### 範圍 IN

1. **ArticleServiceImpl 拆解**：1020 行 → ~150-200 行薄 facade
2. **新建 3 個 package-private sub-service**：
   - `ArticleCommandSubService`（11 method：6 Command + 4 Counter + 1 cross-module write）
   - `ArticleQuerySubService`（13 method：7 Query + 6 cross-module read）
   - `ArticleViewSubService`（1 method：`recordView`，從 private `processArticleView` 提升）
3. **新建 2 個 helper class**：
   - `ArticleEntityFinder`（共用 `findByUuidOrThrow`，給 Command + Query 共用）
   - `ArticleResponseMapper`（9 個 entity → DTO 轉換 method，Query 唯一 caller）
4. **17 個 private helper 重新分配**到對應 sub-service / helper class
5. **ArticleServiceTest 1981 行重整**為分層 test：facade test + 3 個 sub-service test + 2 個 helper class test

### 範圍 OUT

- ❌ **ArticleService interface 縮減**：24 method 全保留（保 SP-B/D 已收的 caller 不變）
- ❌ **`ArticleQueryService` decorator**（read 層 enrich `liked`/`bookmarked`/`series`）不動 — 既有 SP-X 議題，與 SP-C 內部 split 邏輯獨立
- ❌ **`ArticleFacadeImpl`** 對 `ArticleService` 的依賴不動（SP-B/D 確立的 facade routing pattern）
- ❌ **View tracking 擴展**（如 heatmap、recommendation tracking）— 屬 future work，SP-C 後 ArticleViewSubService 已是擴展 entry point
- ❌ **Architectural reform（roadmap §5.4 提的 ArticleService 拆 3 interface）** — 屬未來 SP，本 SP 為 internal cleanup

### Done definition

1. ✅ `ArticleServiceImpl` 行數 1020 → ~150-200 行（薄 facade）
2. ✅ `ArticleServiceImpl` inject 從 11 個 → **3 個**（只 inject 3 個 sub-service）
3. ✅ 3 個 sub-service + 2 個 helper class 全為 package-private（不對外暴露）
4. ✅ `ArticleService` interface 24 method 不變，所有跨模組 caller（comment / reading / series / version + ArticleFacadeImpl）零改動
5. ✅ `ArticleControllerIT.java` 952 行不變仍綠（HTTP endpoint 行為等價）
6. ✅ 5 個 affected modules tests 全綠：article（含 sub-service test 新增）+ comment + reading + series + version

---

## 2. 設計決策摘要

| 決策點 | 選擇 | 理由 |
|------|------|------|
| 拆分顆粒度 | **Internal cleanup with sub-services**（B 方案） | 保 interface 對外 contract，避免 break SP-B/D 已收的 5 個跨模組 caller；god class 內部真拆，達成 roadmap §5.4 「god class 消失」目標 |
| Sub-service 數量 | **3 個（Command / Query / View）** | 對齊 roadmap §5.4 + SP-B/D 確立的 facade 協調 atomic flow pattern；4 類 method 中 Counter（4）歸 Command write、Cross-module read（6）歸 Query、Cross-module write（1）歸 Command |
| `processArticleView` 是否獨立 sub-service | **是（ArticleViewSubService）** | view tracking 是獨立 concern（Redis 防刷 + async event），未來擴展（heatmap / recommendation tracking）有清楚 entry point；testable 單純度高 |
| 共用 helper class | **2 個（ArticleEntityFinder + ArticleResponseMapper）** | DRY — `findByUuidOrThrow` 跨 Command/Query 共用；9 個 entity → DTO 轉換 helper 集中於 ArticleResponseMapper 同職責管理 |
| package 結構 | **`service/internal/`** 子 package | sub-service / helper 全 package-private 避免外部誤用；對齊 Java 慣例（Spring Boot starter 普遍用 `internal/` package 標記不對外） |
| Migration 策略 | **Bottom-up 7 tasks，每 task 完成後 codebase 全綠** | 增量 ship，避免一次性拆 1020 行造成 review 困難；對齊 SP-A/B/D 7-task 切法 |
| Test 結構 | **分層：facade test + 3 sub-service test + 2 helper test** | ArticleServiceTest 既有 1981 行直接改寫單一 file 太大；分散到 6 個 test file 各自 cohesion 高 |
| ArticleServiceImpl 是否「純 1-line delegate」 | **多數純 delegate，2 個 method 含協調邏輯** | `getArticleByUuid` / `getArticleBySlug` 必須協調 query → view 順序；對齊 SP-D `applyRestoreContent` atomic 協調 pattern |

---

## 3. 既有結構盤點（audit findings）

來源：SP-C brainstorm explorer agent 完整 audit。

### 3.1 ArticleService interface（24 method，4 類）

| 類別 | 數量 | Method 列表 |
|---|---|---|
| **Command**（write 業務邏輯）| 6 | createArticle / updateArticle / deleteArticle / publishArticle / rejectArticle / submitForReview |
| **Query**（read with enrich）| 7 | getArticleByUuid / getArticleBySlug / getArticleForEdit / getPublishedArticles / getPublishedArticlesByCategorySlug / getMyArticles / getPendingArticles |
| **Counter**（simple write，1 行 mapper.update）| 4 | incrementCommentCount / decrementCommentCount / incrementLikeCount / decrementLikeCount |
| **Cross-module**（給 ArticleFacade 暴露）| 7 | findIdByUuid / findById / findByUuid / findByIds / findBySeriesIdOrderByPosition / getArticleSummariesByIds / updateSeriesAssignment（write）|

### 3.2 ArticleServiceImpl 1020 行分布

**Public method 24 個**（@Override）— 對應 interface 24 method，1:1 實作。

| 範圍 | 行數 |
|---|---|
| Command 6 method | createArticle 52 + updateArticle 82 + deleteArticle 23 + publishArticle 35 + rejectArticle 15 + submitForReview 8 = **215 行** |
| Query 7 method | getArticleByUuid 4 + getArticleBySlug 5 + getArticleForEdit 7 + getPublishedArticles 11 + getPublishedArticlesByCategorySlug 12 + getMyArticles 18 + getPendingArticles 11 = **68 行** |
| Counter 4 method | 4 × 3 行 = **12 行** |
| Cross-module 6 method | 6 + 11 + 3 + 6 + 3 + 3 + 7 = **39 行** |
| Private helper 17 個 | 21 + 4 + 8 + 12 + 7 + 3 + 3 + 3 + 13 + 24 + 15 + 21 + 10 + 9 + 12 + 6 + 15 = **186 行** |
| imports + class scaffolding + Javadoc | ≈ 500 行 |
| **合計** | **1020 行** |

### 3.3 17 個 Private helper 詳細

| # | Helper | 行 | 行數 | 職責 |
|---|---|---|---|---|
| 1 | processArticleView | 375-395 | 21 | View tracking（Redis 防刷 + publishViewed event） |
| 2 | findByUuidOrThrow | 585-588 | 4 | 撈 Article entity，不存在 throw ARTICLE_NOT_FOUND |
| 3 | checkWritePermission | 597-604 | 8 | author / admin 權限檢查 |
| 4 | validateStatusTransition | 617-628 | 12 | publishArticle / submitForReview 狀態流程驗證 |
| 5 | generateSlug | 636-642 | 7 | createArticle / updateArticle 自動產生 slug |
| 6 | resolveAuthorUuid | 650-652 | 3 | userFacade.getUserUuid wrapper |
| 7 | resolveAuthorNickname | 660-662 | 3 | userFacade.getUserNickname wrapper |
| 8 | convertToHtml | 672-674 | 3 | markdownRenderer.render wrapper |
| 9 | extractSummary | 686-698 | 13 | content → summary 抽取（前 N 字） |
| 10 | toResponse | 710-733 | 24 | Article → ArticleResponse |
| 11 | toEditorResponse | 745-759 | 15 | Article → ArticleEditorResponse |
| 12 | toSummaryResponse | 767-787 | 21 | Article → ArticleSummaryResponse |
| 13 | syncCategories | 800-809 | 10 | 同步 article_categories（依靠 categoryMapper） |
| 14 | toTagSummaryResponses | 822-830 | 9 | List<Tag> → List<TagSummaryResponse> |
| 15 | batchToTagResponsesMap | 842-853 | 12 | Map<articleId, tags> 批次轉換（給 list 用） |
| 16 | toCategoryResponses | 861-866 | 6 | List<Category> → List<CategoryResponse> |
| 17 | batchToCategoryResponsesMap | 874-888 | 15 | 批次 category 轉換 |

### 3.4 ArticleServiceImpl 11 個 inject

| # | Field | 用途 | 拆後歸屬 |
|---|---|---|---|
| 1 | ArticleRepository | CRUD | Command + Query + EntityFinder |
| 2 | ArticleMapper | 複雜分頁查詢 | Query |
| 3 | UserFacade | 作者資訊 | ResponseMapper |
| 4 | ArticleEventPublisher | publish events | Command + ViewSub |
| 5 | ViewCountService | read view count（給 toResponse 用） | ResponseMapper |
| 6 | StringRedisTemplate | Redis 防刷 setIfAbsent | ViewSub |
| 7 | CategoryMapper | 分類同步 | Command |
| 8 | CategoryRepository | 分類查詢 | Command |
| 9 | TagFacade | 標籤跨模組 | Command |
| 10 | TransactionTemplate | 事務控制 | Command |
| 11 | ArticleMarkdownRenderer | Markdown → HTML | Command |

### 3.5 ArticleQueryService（既有 decorator，不在 SP-C scope）

`blog-module-article/.../service/ArticleQueryService.java` 283 行，6 method，**inject ArticleService**（單向，無循環）。

職責：read 層 enrich layer — 委派 read 給 ArticleService，再透過 `ReadingFacade` / `SeriesFacade` 補 `liked` / `bookmarked` / `series` 導覽資訊。

**SP-C 不動 ArticleQueryService**：
- 它 inject ArticleService → SP-C 後仍 OK（ArticleService interface 不變）
- 它與 sub-service split 邏輯獨立 — Decorator pattern 與 internal split 屬不同議題
- 未來如 SP-X 處理「ArticleQueryService 跨模組 inject」時再評估

---

## 4. 拆分後架構

```
blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/
├── ArticleService.java                  (interface 不變，24 method)
├── ArticleServiceImpl.java              (薄 facade，~150-200 行)
├── ArticleQueryService.java             (既有 decorator 不動)
└── internal/                            (新建 子 package — package-private 範圍)
    ├── ArticleCommandSubService.java    (11 method)
    ├── ArticleQuerySubService.java      (13 method)
    ├── ArticleViewSubService.java       (1 method)
    ├── ArticleEntityFinder.java         (共用 — 1 method)
    └── ArticleResponseMapper.java       (Query 用 — 9 method)
```

### Dependency graph

```
                         ArticleService (interface)
                                  ↑ implements
                         ArticleServiceImpl (薄 facade)
                                  │ inject 3 個 sub-service
                                  ▼
            ┌─────────────────────┴─────────────────────┐
            │                     │                     │
ArticleCommandSubService   ArticleQuerySubService   ArticleViewSubService
            │                     │                     │
            │                     │                     │
            └────────┬────────────┘                     │
                     │                                  │
            ┌────────┴────────┐                         │
            ▼                 ▼                         ▼
   ArticleEntityFinder  ArticleResponseMapper      (StringRedisTemplate
   (共用 helper)        (Query 用 helper)         + ArticleEventPublisher)
```

**關鍵設計屬性：**
- 3 個 sub-service 之間**零互依**（協調由 ArticleServiceImpl 負責 — 對齊 SP-D applyRestoreContent atomic flow pattern）
- 2 個 helper class 純 dumb mapper / finder（無業務邏輯，testable）
- Sub-service 對 helper class 的依賴**單向**（Command/Query → EntityFinder；Query → ResponseMapper）

---

## 5. 3 sub-service 詳細邊界

### 5.1 ArticleCommandSubService（write，11 method）

```java
package dowob.xyz.blog.module.article.service.internal;

@Service
@RequiredArgsConstructor
class ArticleCommandSubService {
    // Inject 8 個（從 ArticleServiceImpl 11 個減去 view-only 與 read-only）：
    private final ArticleRepository articleRepository;
    private final ArticleEventPublisher articleEventPublisher;
    private final CategoryMapper categoryMapper;
    private final CategoryRepository categoryRepository;
    private final TagFacade tagFacade;
    private final ArticleMarkdownRenderer markdownRenderer;
    private final TransactionTemplate transactionTemplate;
    private final ArticleEntityFinder entityFinder;          // 共用 helper

    // ─── 11 public（package-private）method ───
    // Command 6 個：
    Article createArticle(...);
    Article updateArticle(...);
    void deleteArticle(...);
    Article publishArticle(...);
    Article rejectArticle(...);
    Article submitForReview(...);

    // Counter 4 個（純 mapper.update）：
    void incrementCommentCount(Long articleId);
    void decrementCommentCount(Long articleId);
    void incrementLikeCount(Long articleId);
    void decrementLikeCount(Long articleId);

    // Cross-module write 1 個：
    void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition);

    // ─── 5 個 private helper（從 ArticleServiceImpl 移過來）───
    private void checkWritePermission(...);
    private void validateStatusTransition(...);
    private String generateSlug(...);
    private String extractSummary(...);
    private void syncCategories(...);
    // convertToHtml inline 為 markdownRenderer.render(...)（既有 wrapper 太薄不必保留）
}
```

### 5.2 ArticleQuerySubService（read，13 method）

```java
package dowob.xyz.blog.module.article.service.internal;

@Service
@RequiredArgsConstructor
class ArticleQuerySubService {
    // Inject 5 個：
    private final ArticleRepository articleRepository;
    private final ArticleMapper articleMapper;
    private final UserFacade userFacade;                     // (給 toEditorResponse path 用，部分 method 不需轉 response)
    private final ViewCountService viewCountService;
    private final ArticleEntityFinder entityFinder;          // 共用 helper
    private final ArticleResponseMapper responseMapper;       // 共用 helper

    // ─── 13 public method ───
    // Query 7 個：
    ArticleResponse getArticleByUuid(UUID uuid, Long currentUserId);
    ArticleResponse getArticleBySlug(String slug, Long currentUserId);
    ArticleEditorResponse getArticleForEdit(UUID uuid, Long operatorId, Role role);
    PageResult<ArticleSummaryResponse> getPublishedArticles(...);
    PageResult<ArticleSummaryResponse> getPublishedArticlesByCategorySlug(...);
    PageResult<ArticleSummaryResponse> getMyArticles(...);
    PageResult<ArticleSummaryResponse> getPendingArticles(...);

    // Cross-module read 6 個：
    Long findIdByUuid(UUID articleUuid);
    Optional<Article> findById(Long articleId);
    Optional<Article> findByUuid(UUID articleUuid);
    List<Article> findByIds(List<Long> articleIds);
    List<Article> findBySeriesIdOrderByPosition(Long seriesId);
    List<ArticleSummaryResponse> getArticleSummariesByIds(List<Long> articleIds);

    // 注意：getArticleByUuid / getArticleBySlug 不觸發 view tracking
    //       view 由 ArticleServiceImpl 協調 ArticleViewSubService.recordView
}
```

### 5.3 ArticleViewSubService（read 副作用，1 method）

```java
package dowob.xyz.blog.module.article.service.internal;

@Service
@RequiredArgsConstructor
class ArticleViewSubService {
    private final StringRedisTemplate stringRedisTemplate;
    private final ArticleEventPublisher articleEventPublisher;

    private static final String VIEW_KEY_PREFIX = "view:article:";
    private static final Duration VIEW_DEDUP_TTL = Duration.ofMinutes(5);

    /**
     * 記錄 article view（Redis 防刷 + 異步 ViewCountEvent）。
     *
     * <p>從 RequestContextHolder 取 clientIp 作為防刷 key 的一部分。
     * Redis setIfAbsent 5 分鐘 TTL → firstVisit 才 publish event。</p>
     *
     * @param articleUuid 文章 UUID
     */
    void recordView(UUID articleUuid) {
        String clientIp = resolveClientIp();
        String viewKey = VIEW_KEY_PREFIX + articleUuid + ":" + clientIp;
        Boolean firstVisit = stringRedisTemplate.opsForValue()
            .setIfAbsent(viewKey, "1", VIEW_DEDUP_TTL);
        if (Boolean.TRUE.equals(firstVisit)) {
            articleEventPublisher.publishViewed(articleUuid);
        }
    }

    private String resolveClientIp() {
        // 既有 processArticleView 的 IP 解析邏輯（HttpServletRequest）
        ...
    }
}
```

---

## 6. Helper class 設計

### 6.1 ArticleEntityFinder（共用 entity lookup）

```java
package dowob.xyz.blog.module.article.service.internal;

@Component
@RequiredArgsConstructor
class ArticleEntityFinder {
    private final ArticleRepository articleRepository;

    /**
     * 撈 Article entity，不存在 throw ARTICLE_NOT_FOUND。
     *
     * <p>Command + Query sub-service 共用此 helper（避免重複實作）。</p>
     */
    Article findByUuidOrThrow(UUID articleUuid) {
        return articleRepository.findByUuid(articleUuid)
            .orElseThrow(() -> new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));
    }
}
```

職責單一 — 唯一一個 method，1 行邏輯。

### 6.2 ArticleResponseMapper（Query 用 entity → DTO 轉換）

```java
package dowob.xyz.blog.module.article.service.internal;

@Component
@RequiredArgsConstructor
class ArticleResponseMapper {
    private final UserFacade userFacade;
    private final ViewCountService viewCountService;     // toResponse 需 view count

    // ─── 主要 mapper（3 個 — entity → 不同 DTO 形式）───
    ArticleResponse toResponse(Article article, ...);
    ArticleEditorResponse toEditorResponse(Article article);
    ArticleSummaryResponse toSummaryResponse(Article article);

    // ─── 子轉換 mapper（4 個 — 給 toResponse 用）───
    List<TagSummaryResponse> toTagSummaryResponses(List<Tag> tags);
    Map<Long, List<TagSummaryResponse>> batchToTagResponsesMap(...);
    List<CategoryResponse> toCategoryResponses(List<Category> categories);
    Map<Long, List<CategoryResponse>> batchToCategoryResponsesMap(...);

    // ─── 子查詢 helper（2 個 — userFacade wrapper，1 行 method）───
    UUID resolveAuthorUuid(Long authorId) { return userFacade.getUserUuid(authorId); }
    String resolveAuthorNickname(Long authorId) { return userFacade.getUserNickname(authorId); }
}
```

職責：Article entity 與子 entity（Tag / Category）轉換為各種 response DTO。**Query sub-service 唯一 caller**（Command 不負責 response 構造）。

---

## 7. ArticleServiceImpl 薄 facade 改寫

```java
package dowob.xyz.blog.module.article.service;

@Service
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {
    private final ArticleCommandSubService commandSubService;
    private final ArticleQuerySubService querySubService;
    private final ArticleViewSubService viewSubService;
    // 從 11 inject 縮成 3 個

    // ─── Command 6 method（純 1-line delegate）───
    @Override public Article createArticle(...) { return commandSubService.createArticle(...); }
    @Override public Article updateArticle(...) { return commandSubService.updateArticle(...); }
    @Override public void deleteArticle(...) { commandSubService.deleteArticle(...); }
    @Override public Article publishArticle(...) { return commandSubService.publishArticle(...); }
    @Override public Article rejectArticle(...) { return commandSubService.rejectArticle(...); }
    @Override public Article submitForReview(...) { return commandSubService.submitForReview(...); }

    // ─── Query 7 method ───
    // 5 個純 delegate：
    @Override public ArticleEditorResponse getArticleForEdit(...) { return querySubService.getArticleForEdit(...); }
    @Override public PageResult<ArticleSummaryResponse> getPublishedArticles(...) { return querySubService.getPublishedArticles(...); }
    @Override public PageResult<ArticleSummaryResponse> getPublishedArticlesByCategorySlug(...) { return querySubService.getPublishedArticlesByCategorySlug(...); }
    @Override public PageResult<ArticleSummaryResponse> getMyArticles(...) { return querySubService.getMyArticles(...); }
    @Override public PageResult<ArticleSummaryResponse> getPendingArticles(...) { return querySubService.getPendingArticles(...); }

    // 2 個含協調邏輯（query → view 順序）：
    @Override
    public ArticleResponse getArticleByUuid(UUID uuid, Long currentUserId) {
        ArticleResponse resp = querySubService.getArticleByUuid(uuid, currentUserId);
        viewSubService.recordView(uuid);
        return resp;
    }

    @Override
    public ArticleResponse getArticleBySlug(String slug, Long currentUserId) {
        ArticleResponse resp = querySubService.getArticleBySlug(slug, currentUserId);
        viewSubService.recordView(resp.getUuid());
        return resp;
    }

    // ─── Counter 4 method（純 1-line delegate）───
    @Override public void incrementCommentCount(Long articleId) { commandSubService.incrementCommentCount(articleId); }
    @Override public void decrementCommentCount(Long articleId) { commandSubService.decrementCommentCount(articleId); }
    @Override public void incrementLikeCount(Long articleId) { commandSubService.incrementLikeCount(articleId); }
    @Override public void decrementLikeCount(Long articleId) { commandSubService.decrementLikeCount(articleId); }

    // ─── Cross-module 7 method（純 1-line delegate — 6 read + 1 write）───
    @Override public Long findIdByUuid(UUID articleUuid) { return querySubService.findIdByUuid(articleUuid); }
    @Override public Optional<Article> findById(Long articleId) { return querySubService.findById(articleId); }
    @Override public Optional<Article> findByUuid(UUID articleUuid) { return querySubService.findByUuid(articleUuid); }
    @Override public List<Article> findByIds(List<Long> articleIds) { return querySubService.findByIds(articleIds); }
    @Override public List<Article> findBySeriesIdOrderByPosition(Long seriesId) { return querySubService.findBySeriesIdOrderByPosition(seriesId); }
    @Override public List<ArticleSummaryResponse> getArticleSummariesByIds(List<Long> articleIds) { return querySubService.getArticleSummariesByIds(articleIds); }
    @Override public void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition) { commandSubService.updateSeriesAssignment(articleId, seriesId, seriesPosition); }
}
```

**關鍵屬性：**
- 22/24 method 純 1-line delegate，2/24 含「協調」邏輯（getArticleByUuid / getArticleBySlug 協調 query → view）
- inject 從 11 個 縮為 3 個（只 inject 3 sub-service）
- 行數從 1020 → ~150-200 行

---

## 8. Migration 策略（Bottom-up 7 tasks）

每個 task 完成後 codebase 都 functional（增量 ship，避免大爆炸）。

| Task | 內容 | 風險 | 預期 commit |
|---|---|---|---|
| **T1** | 建 `ArticleEntityFinder` + `ArticleResponseMapper` helper class — `ArticleServiceImpl` 內 caller 改 inject + call 新 class（既有 method body 主流程不變，只把 helper 邏輯外移） | 低 | `refactor(article): 抽出 ArticleEntityFinder + ArticleResponseMapper helper class` |
| **T2** | 建 `ArticleViewSubService` — `processArticleView` 提升為 `recordView`，`ArticleServiceImpl.getArticleByUuid` / `getArticleBySlug` 改 call viewSubService | 低 | `refactor(article): 提升 processArticleView 為 ArticleViewSubService.recordView` |
| **T3** | 建 `ArticleCommandSubService` — 11 method + 5 private helper 搬遷，`ArticleServiceImpl` 對應 method 改 1-line delegate | 中 | `refactor(article): 拆 ArticleCommandSubService（Command + Counter + cross write）` |
| **T4** | 建 `ArticleQuerySubService` — 13 method 搬遷，`ArticleServiceImpl` 對應 method 改 1-line delegate | 中 | `refactor(article): 拆 ArticleQuerySubService（Query + cross read）` |
| **T5** | `ArticleServiceTest` 1981 行重整 — 切分到對應 sub-service test，既有 facade test 縮減為「驗委派 + 協調順序」 | 高 | `test(article): ArticleServiceTest 重整為分層 test（facade + 3 sub-service + 2 helper）` |
| **T6** | `ArticleServiceImpl` 殘留 cleanup — 移除 unused inject（11 → 3 個只剩 sub-service） | 低 | `refactor(article): ArticleServiceImpl 清未使用 inject + class scaffolding 收緊` |
| **T7** | 全模組驗收 — grep + tests | 低 | （無 commit）|

**為何 Bottom-up:**
- T1（helper 抽出）→ T2（簡單 sub-service 切出）→ T3/T4（複雜 sub-service 切出）→ T5（test 重整）→ T6（清理）
- 每階段 dependency 已就位，無需 forward-reference

---

## 9. Test 策略（分層 test）

### 9.1 既有 ArticleServiceTest 1981 行的處理

ArticleServiceTest 既有結構是「測 ArticleServiceImpl」— mock 11 個 inject。SP-C 後 ArticleServiceImpl 只 inject 3 sub-service，既有 mock 結構不適用。

**重整策略：**

```
ArticleServiceTest（簡化）— ~200 行
├── 驗 facade 委派行為：mock 3 sub-service，verify call 委派
└── 驗 getArticleByUuid / getArticleBySlug 協調順序（用 InOrder，對齊 SP-D pattern）

ArticleCommandSubServiceTest（新）— ~800 行
├── 從 ArticleServiceTest 搬出 createArticle / updateArticle / deleteArticle / publishArticle / rejectArticle / submitForReview / 4 Counter / updateSeriesAssignment 細節
└── mock 對應 inject (ArticleRepository / TagFacade / ArticleEventPublisher / CategoryMapper / ...)

ArticleQuerySubServiceTest（新）— ~600 行
├── 從 ArticleServiceTest 搬出 getArticleByUuid (no view) / getArticleBySlug (no view) / getArticleForEdit / 列表 query / 6 Cross-module read 細節
└── mock 對應 inject + ArticleEntityFinder / ArticleResponseMapper

ArticleViewSubServiceTest（新）— ~100 行
├── Redis setIfAbsent 防刷邏輯（first visit / repeat visit）
└── publishViewed event 觸發條件（only on first visit）

ArticleResponseMapperTest（新）— ~200 行
└── 9 個 mapper method 各自轉換正確性

ArticleEntityFinderTest（新）— ~30 行
└── findByUuidOrThrow 正常 + ARTICLE_NOT_FOUND 兩條 path
```

合計 ~1930 行 — 與既有 1981 行近似但分散 + 各自 cohesion 高。

### 9.2 ArticleControllerIT 不動

既有 952 行 IT 測 HTTP endpoint 黑盒行為，與 internal split 解耦。SP-C 後仍應全綠。如有 fail，表示行為不等價（需修正）。

### 9.3 跨模組 IT

不需新 cross-module IT（純 internal refactor，邏輯不變）。既有 `CrossModuleArticleIT` / `CrossModuleSeriesIT` / `CrossModuleVersionIT` / `CrossModuleCommentIT` / `CrossModuleReadingIT` 等驗證跨模組行為等價。

---

## 10. 模組依賴變化

### Before SP-C

```
ArticleService (interface) ── ArticleServiceImpl (1020 行 god class)
                               ├── 11 inject（Repository/Mapper/Facade/Service/Publisher/Template/Renderer）
                               └── 17 private helper（混雜 Command/Query/View 職責）
```

### After SP-C

```
ArticleService (interface — 不變)
   ↑
ArticleServiceImpl (薄 facade，~150-200 行)
   ├── ArticleCommandSubService (write 11 method)
   ├── ArticleQuerySubService (read 13 method)
   └── ArticleViewSubService (view 1 method)
        │
        各 sub-service 對應 inject + 共用 helper:
        ├── ArticleEntityFinder (Command/Query 共用)
        └── ArticleResponseMapper (Query 用)
```

**對外 caller 完全無變化**（24 method interface 不變）。

---

## 11. Done definition（最終）

| # | 驗收項 | 驗證方式 |
|---|---|---|
| 1 | ArticleServiceImpl 行數 1020 → ~150-200 | `wc -l ArticleServiceImpl.java` |
| 2 | ArticleServiceImpl inject 數 11 → 3 | grep `private final` count |
| 3 | 3 sub-service + 2 helper class 全 package-private | grep public class declaration（應為 0）|
| 4 | ArticleService interface 24 method 不變 | git diff 對 ArticleService.java 為 empty |
| 5 | ArticleControllerIT 952 行不變仍綠 | mvn test |
| 6 | ArticleServiceTest 重整為分層（6 test files） | git ls-files 比對 |
| 7 | 5 affected modules tests 全綠 | mvn test 全 5 模組 |
| 8 | 全 codebase 無新 cross-module 引用 | grep `internal/` package 跨模組 import = 0 |

---

## 12. Limitations / Future Work

### 12.1 SP-C 後仍存在的 architectural issue

| 議題 | 位置 | 屬於 |
|---|---|---|
| ArticleQueryService decorator 跨模組 inject | BookmarkController + SeriesService | SP-X — 是否擴 ArticleFacade 或新建 ArticleQueryFacade |
| ArticleService interface 24 method 公開範圍 | ArticleService.java | 未來 SP — 可考慮拆 ArticleCommandService / ArticleQueryService / ArticleViewService 對外 interface（architectural reform） |
| `getArticleSummariesByIds` 同時存在於 ArticleService 與 ArticleQueryService（SP-B audit 提到的 spec gap） | 全 codebase | 與 SP-X 一併處理 |

### 12.2 SP-C 後可擴展的 entry point

- **ArticleViewSubService 已是擴展 entry point** — 將來可加 `recordHeatmapPosition` / `recordRecommendationContext` 等 view-related feature
- **ArticleResponseMapper 可重構** — 若 Tag / Category 轉換邏輯爆炸，可拆 ArticleTagResponseMapper / ArticleCategoryResponseMapper
- **ArticleCommandSubService transaction 邊界** — 既有 transaction 管理在 method 層，未來可考慮 transactional helper / explicit transaction template

---

## 13. Implementation Plan 預估（7 tasks）

| # | Task | 依賴 |
|---|---|---|
| T1 | ArticleEntityFinder + ArticleResponseMapper helper class | — |
| T2 | ArticleViewSubService + ArticleServiceImpl 委派 view tracking | T1 |
| T3 | ArticleCommandSubService + ArticleServiceImpl 委派 Command/Counter/CrossWrite | T1 |
| T4 | ArticleQuerySubService + ArticleServiceImpl 委派 Query/CrossRead | T1, T3（Command 完成後 Query 才能定 entity finder 行為） |
| T5 | ArticleServiceTest 1981 行重整為分層 test（含新 5 個 sub-service / helper test） | T2-T4 |
| T6 | ArticleServiceImpl unused inject cleanup | T2-T5 |
| T7 | 全模組 grep verify + sanity test（純驗證） | T1-T6 |

預估 6 commits（T7 純驗證不產 commit）。對齊 SP-A/B/D 7-task 切法。

---

## 14. 風險評估

| Task | 風險 | 主要風險點 |
|---|---|---|
| T1 | 低 | 純 helper extraction，既有 method body 不變 |
| T2 | 低 | 單一 method（recordView）抽出 |
| T3 | **中** | 11 method 業務邏輯搬遷，可能踩 hidden coupling（如 transaction boundary、event publish 順序）|
| T4 | **中** | 13 method 搬遷，可能踩 view tracking 觸發點（既有 `getArticleByUuid` 內部 call `processArticleView`，需確認 ArticleQueryService decorator 行為等價）|
| T5 | **高** | 1981 行 test 重整 — 是 SP-C 最大風險。Test mock 結構從「mock 11 inject」改「mock 3 sub-service」，breaking change 規模大。需逐個 test class 切分驗證 |
| T6 | 低 | 純 cleanup |
| T7 | 低 | 純驗證 |

整體：roadmap §5.4 評估「最後做風險最高」反映的就是 **T3/T4/T5 三 task 連環風險**。透過 B 方案（保留 interface 不變）+ Bottom-up migration 已將風險降到「中」級別 — 對外無 break，內部增量改寫。

---

## 15. 參考

- Roadmap: `docs/superpowers/specs/2026-05-03-architecture-decoupling-roadmap.md` §5.4
- SP-A 設計（Series MQ Decouple）: `docs/superpowers/specs/2026-05-03-sp-a-series-mq-decouple-design.md`
- SP-B 設計（Article Facade Routing）: `docs/superpowers/specs/2026-05-03-sp-b-article-facade-routing-design.md`
- SP-D 設計（Events and Helpers Cleanup）: `docs/superpowers/specs/2026-05-03-sp-d-events-helpers-cleanup-design.md`
- SP-D 確立的 facade 協調 atomic flow pattern（applyRestoreContent）— SP-C ArticleServiceImpl getArticleByUuid 協調 query → view 順序對齊
