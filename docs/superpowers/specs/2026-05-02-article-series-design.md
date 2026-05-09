# Article Series 模組設計（批 3）

- **作者**：Yuan + Claude
- **日期**：2026-05-02
- **分支**：`feature/article-series`（worktree `.worktrees/feature-article-series`）
- **批次**：後端 Roadmap 批 3（共 4 批）
- **依賴**：批 1 + 批 2 已 merged 進 develop
- **同 PR 範圍**：除 Series 主功能外，順帶執行多個 architecture 重整 task（見 §1）

---

## 1. 範圍

### 涵蓋

- **新模組** `blog-module-series`：完整 Series CRUD + 跨模組 Facade
- **新 facade** `SeriesFacade` interface（在 `blog-infrastructure`）+ `SeriesFacadeImpl`（在 series 模組）
- 修改 `ArticleQueryService`：注入 `SeriesFacade`，補 `seriesNav`（prev/next）到 `ArticleResponse`
- 修改 `Article` entity：加 `seriesId` + `seriesPosition`
- 修改 `ArticleSummaryResponse` / `ArticleResponse`：加 series 欄位
- `articles.series_id` 連動：刪除 article 時 -1 series.article_count
- DB Migration `V15`（series 表 + ALTER articles + rename article_likes）
- TDD 測試（44 tests：26 unit + 18 IT）

### 順帶 Refactor（同 batch / 同 PR）

batch 1/2 累積的架構修整一起做：

1. **`AuthorSummary` 移到 `blog-common/api/dto/`** — 跨模組共享 DTO 應在 common，不該在 comment 模組裡
2. **`ArticleLike` 從 `blog-module-article` 搬到 `blog-module-reading`** — 概念上「使用者-文章狀態」應跟 bookmark / progress 同模組
3. **`ReadingFacade` 加 `batchIsLiked` / `isLiked`** — ArticleQueryService.enrich 三個欄位（liked / bookmarked / progress）統一從 ReadingFacade 取
4. **`article_likes` 改名 `user_article_likes`** — 跟 `user_bookmarks` / `user_highlights` / `user_reading_progress` 命名一致

### 不涵蓋

- Series 軟刪除（採硬刪除 + articles.series_id SET NULL）
- M:N article × series 關係（採 1:N）
- DRAFT 文章入 series（只 PUBLISHED）
- 浮點 / LexoRank 排序（採整數 position）
- Series 自動編號 next-position（前端決定 position）
- Series 章節（Series 內再分章）
- Series 完成成就 / 通知系統
- Public series like / bookmark
- Category 重構（緊耦合 article CRUD，留在 article 模組）
- ViewCount 重構（緊耦合 article CRUD，留在 article 模組）

### 邊界

- Series CRUD 限擁有者（`series.author_id == currentUserId`）+ Admin 可介入
- POST/PUT/DELETE 需 `@PreAuthorize("hasAuthority('ARTICLE_CREATE')")`
- GET `/series` / `/series/{slug}` 公開
- 跨模組依賴：`blog-module-series → blog-module-article`（讀 articles）+ `→ ReadingFacade`（讀 user progress）
- ArticleQueryService 用 `SeriesFacade.getSeriesNavigation` 補 nav（read-only，無循環）
- URL 路徑保留 `/api/v1/articles/{uuid}/like`（refactor 不破壞前端）

---

## 2. 設計決策摘要

| 決策點 | 選擇 | 理由 |
|------|------|------|
| Series ↔ Article 關係 | 1:N（articles.series_id FK + position）| 個人部落格規模，文章不會跨多 series |
| Series 順序 | 整數 position | 重排罕見；renumber 對小 N 便宜 |
| Series 元資料 | uuid / title / slug / description / cover_image_url / author_id / article_count | 對齊 article 慣例 |
| Series 擁有權 | 任何 AUTHOR | 跟 article 一致，Admin 可介入 |
| Series 跟 batch 2 整合 | 完整（A+B+C）| API 含 myProgress + ArticleResponse 含 seriesNav |
| Series 含 DRAFT 文章？ | 不可（只 PUBLISHED）| 簡單，避免訪客看到不一致 |
| Series 刪除 | 硬刪除 + articles.series_id SET NULL | 文章不被誤刪 |
| 1:N 衝突處理 | 拋 S0106 強制先移除原 series | 明確語意，避免靜默覆蓋 |
| `cover_image_url` 型別 | VARCHAR(512)（純 URL 字串）| 對齊 articles.cover_image_url |
| description 長度 | TEXT 不限 | 簡介可長 |
| article_count 反正規化 | 是（service 層 +1/-1）| 對齊 articles.like_count pattern |
| 模組位置 | `blog-module-series` 獨立模組 | 跟批 1/2 一致；避免 article 模組變 god-module |
| AuthorSummary | 移到 `blog-common/api/dto/` | 純 DTO，跨多模組用，不該綁特定模組 |
| ArticleLike | 搬到 `blog-module-reading` | 概念對齊 bookmark/progress |
| article_likes 改名 | `user_article_likes` | 命名一致 |
| URL `/articles/{uuid}/like` | 保留（不改）| REST resource path，跟模組分配無關 |
| V15 同時做 series + rename | 是（單一 migration）| 個人專案 + Testcontainers 驗證，risk 低 |
| Phase 0 refactor 與 series 同 PR | 是（單一 PR）| series 整合測試需要 refactor 完成的狀態 |

---

## 3. 資料模型

### V15 Migration

檔案：`blog-db-migration/src/main/resources/db/migration/V15__add_article_series_and_rename_likes.sql`

```sql
-- V15__add_article_series_and_rename_likes.sql

-- ─────────────────────────────────────────────
-- Part 1: 新建 series 表
-- ─────────────────────────────────────────────
CREATE TABLE series (
    id              BIGSERIAL    PRIMARY KEY,
    uuid            UUID         NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    title           VARCHAR(255) NOT NULL,
    slug            VARCHAR(255) NOT NULL UNIQUE,
    description     TEXT         NULL,
    cover_image_url VARCHAR(512) NULL,
    author_id       BIGINT       NOT NULL REFERENCES users(id),    -- NO ACTION
    article_count   INTEGER      NOT NULL DEFAULT 0,                -- 反正規化
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_series_author ON series(author_id);

-- ─────────────────────────────────────────────
-- Part 2: articles 表加 series 關聯
-- ─────────────────────────────────────────────
ALTER TABLE articles ADD COLUMN series_id       BIGINT  NULL REFERENCES series(id) ON DELETE SET NULL;
ALTER TABLE articles ADD COLUMN series_position INTEGER NULL;

CREATE INDEX idx_articles_series_position
    ON articles(series_id, series_position)
    WHERE series_id IS NOT NULL;

-- ─────────────────────────────────────────────
-- Part 3: article_likes 改名 user_article_likes
-- ─────────────────────────────────────────────
ALTER TABLE article_likes RENAME TO user_article_likes;

-- PG 對 PK index 跟著 table 名 auto-rename，但 named UNIQUE constraint 需明確 rename
ALTER TABLE user_article_likes
    RENAME CONSTRAINT uq_article_likes_user_article
    TO uq_user_article_likes_user_article;
```

### Spring Data JDBC Entities

```java
// blog-module-series/.../model/Series.java
@Table("series")
public class Series {
    @Id Long id;
    UUID uuid;                          // setUuid before save
    String title;
    String slug;
    String description;
    @Column("cover_image_url") String coverImageUrl;
    @Column("author_id") Long authorId;
    @Column("article_count") Integer articleCount;
    @CreatedDate @Column("created_at") LocalDateTime createdAt;
    @LastModifiedDate @Column("updated_at") LocalDateTime updatedAt;
}

// blog-module-article/.../model/Article.java（既有，加 2 個欄位）
@Column("series_id")        Long seriesId;          // nullable
@Column("series_position")  Integer seriesPosition; // nullable

// blog-module-reading/.../model/ArticleLike.java（從 article 搬過來，table 改名）
@Table("user_article_likes")     // OLD: "article_likes"
public class ArticleLike { ... 既有欄位不變 ... }
```

### 索引命名（對齊 flyway-convention.md）

| 索引 | 類型 | 覆蓋查詢 |
|------|------|---------|
| `series_pkey` | PK auto | id 查詢 |
| `series_uuid_key` | UNIQUE auto | uuid 查詢 |
| `series_slug_key` | UNIQUE auto | slug 查詢（GET /series/{slug}）|
| `idx_series_author` | btree | author 的 series 列表 |
| `idx_articles_series_position` | partial btree | series 內 articles 按 position 撈 |
| `user_article_likes_pkey` | PK auto-renamed | （從 article_likes_pkey）|
| `uq_user_article_likes_user_article` | UNIQUE renamed | （從 uq_article_likes_user_article）|

---

## 4. API 端點

| Method | Path | Auth | RBAC | 描述 |
|--------|------|------|------|------|
| GET | `/api/v1/series?page=&size=&authorUuid=` | 公開 | — | series 列表（只列 article_count > 0 的）|
| GET | `/api/v1/series/{slug}` | 公開 | — | 詳情 + articles + myProgress（已登入時）|
| POST | `/api/v1/series` | 登入 | `hasAuthority('ARTICLE_CREATE')` | 建 series |
| PUT | `/api/v1/series/{uuid}` | 登入 | service 檢查 owner / Admin | 改 series 元資料 |
| DELETE | `/api/v1/series/{uuid}` | 登入 | service 檢查 owner / Admin | 硬刪除 series |
| PUT | `/api/v1/series/{uuid}/articles/{articleUuid}` | 登入 | service 檢查 owner / Admin | 加文章 / 改 position |
| DELETE | `/api/v1/series/{uuid}/articles/{articleUuid}` | 登入 | service 檢查 owner / Admin | 從 series 移除文章 |

ArticleLike 路徑保留 `/api/v1/articles/{articleUuid}/like`（POST/DELETE）— refactor 不破壞前端。

---

## 5. DTO

### Request

```java
public class CreateSeriesRequest {
    @NotBlank @Size(max = 255)
    private String title;

    @NotBlank @Size(max = 255)
    @Pattern(regexp = "^[a-z0-9-]+$")
    private String slug;

    private String description;       // optional, TEXT 不限長

    @Size(max = 512)
    private String coverImageUrl;
}

public class UpdateSeriesRequest {
    @Size(max = 255)
    private String title;             // optional

    @Size(max = 255)
    @Pattern(regexp = "^[a-z0-9-]+$")
    private String slug;              // optional

    private String description;

    @Size(max = 512)
    private String coverImageUrl;
}

public class AddArticleToSeriesRequest {
    @NotNull @Min(1)
    private Integer position;
}
```

### Response

```java
public class SeriesSummaryResponse {     // 列表用
    private UUID uuid;
    private String title;
    private String slug;
    private String description;
    private String coverImageUrl;
    private AuthorSummary author;     // from blog-common/api/dto/
    private Integer articleCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

public class SeriesDetailResponse {       // GET /series/{slug} 回傳
    private UUID uuid;
    private String title;
    private String slug;
    private String description;
    private String coverImageUrl;
    private AuthorSummary author;
    private Integer articleCount;
    private List<ArticleSummaryResponse> articles;     // 按 series_position 排序
    private MyProgress myProgress;                      // null = 未登入
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

public class MyProgress {
    private Integer readCount;        // progress >= 0.95 的篇數
    private Integer totalCount;
    private UUID nextUnreadArticleUuid;   // 第一個還沒讀完的；全讀完則 null
}
```

### ArticleResponse 擴充（單篇詳情頁）

```java
private SeriesNavigation seriesNav;       // null if not in series

public class SeriesNavigation {
    private UUID seriesUuid;
    private String seriesTitle;
    private String seriesSlug;
    private Integer position;            // 1-based
    private Integer totalCount;
    private SeriesArticleRef prev;        // null if first
    private SeriesArticleRef next;        // null if last
}

public class SeriesArticleRef {
    private UUID uuid;
    private String title;
    private String slug;
}
```

### ArticleSummaryResponse 擴充（列表）

```java
private UUID seriesUuid;          // null = 不在 series
private String seriesTitle;
private Integer seriesPosition;    // null if not in series
```

⚠ 列表用的 summary **不含完整 nav**（避免 N+1），只標示「這篇在某 series 的第 N 篇」。

### AuthorSummary 移到 common

```java
// blog-common/src/main/java/dowob/xyz/blog/common/api/dto/AuthorSummary.java（NEW）
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthorSummary {
    private UUID uuid;
    private String nickname;
    private String avatarUrl;
}

// blog-module-comment/.../dto/response/AuthorSummary.java DELETE
// 改 import dowob.xyz.blog.common.api.dto.AuthorSummary
```

---

## 6. 錯誤碼

新增 `SeriesErrorCode`（在 `blog-module-series/.../exception/`）：

| Code | Message | 觸發 |
|------|---------|-----|
| `S0101` | Series 不存在 | UUID/slug 找不到 |
| `S0102` | 不可變更他人的 Series | 非擁有者非 Admin |
| `S0103` | 文章必須為 PUBLISHED | 加 DRAFT 文章 |
| `S0104` | Slug 已被使用 | 建/改 slug 衝突 |
| `S0105` | 文章不屬於此 Series | 改/刪 series 中沒有的文章 |
| `S0106` | 文章已屬於另一個 Series | 1:N 限制 |

---

## 7. 模組整合

### 最終模組結構

```
blog-module-series/                         [NEW MODULE]
├─ controller/SeriesController.java         (7 endpoints)
├─ service/SeriesService.java
├─ repository/SeriesRepository.java
├─ mapper/SeriesMapper.java
├─ model/
│   ├─ Series.java
│   └─ dto/{request,response}/
├─ exception/SeriesErrorCode.java
└─ facade/SeriesFacadeImpl.java             (provider for cross-module reads)

blog-module-reading/                         [REFACTOR + EXPAND]
├─ service/
│   ├─ ArticleLikeService.java              MOVED FROM article 模組
│   └─ ...其他既有
├─ controller/
│   ├─ ArticleLikeController.java           MOVED FROM article 模組
│   └─ ...
├─ model/
│   ├─ ArticleLike.java                     MOVED + @Table 改 user_article_likes
│   └─ 其他既有
└─ facade/ReadingFacadeImpl.java            EXPAND (加 batchIsLiked, isLiked)

blog-module-article/                         [SHRINK]
├─ controller/
│   └─ ArticleLikeController.java           DELETED (搬到 reading)
├─ service/
│   ├─ ArticleQueryService.java             MODIFY (用 ReadingFacade.batchIsLiked + SeriesFacade.getSeriesNavigation)
│   └─ ArticleLikeService.java              DELETED (搬到 reading)
└─ model/
    └─ ArticleLike.java                     DELETED

blog-infrastructure/facade/
├─ ReadingFacade.java                        MODIFY (加 batchIsLiked / isLiked)
├─ SeriesFacade.java                         NEW
└─ dto/
    └─ SeriesNavigation.java                 NEW (給 SeriesFacade 介面用)

blog-common/api/dto/                         [NEW PACKAGE]
└─ AuthorSummary.java                        MOVED FROM blog-module-comment

blog-module-comment/
└─ AuthorSummary.java                        DELETED (改 import blog-common 版)
```

### 跨模組依賴方向

```
blog-module-series ─── inject ───→ blog-module-article (查 articles by series_id, ArticleService)
                   ─── inject ───→ ReadingFacade (取 my progress for getSeriesDetail)

blog-module-article (ArticleQueryService)
  ├─ ReadingFacade.batchIsLiked / batchIsBookmarked / batchGetProgress (read-only)
  └─ SeriesFacade.getSeriesNavigation (read-only)

→ 無循環。Facade pattern 一致，跟批 2 對稱。
```

### ReadingFacade 介面擴充

```java
public interface ReadingFacade {
    // 既有
    Set<Long> batchIsBookmarked(Long userId, List<Long> articleIds);
    BigDecimal getProgress(Long userId, UUID articleUuid);
    Map<Long, BigDecimal> batchGetProgress(Long userId, List<Long> articleIds);
    boolean isBookmarked(Long userId, Long articleId);

    // NEW（從 ArticleLikeService 升級成 facade method）
    Set<Long> batchIsLiked(Long userId, List<Long> articleIds);
    boolean isLiked(Long userId, Long articleId);
}
```

### SeriesFacade 介面（新增）

```java
package dowob.xyz.blog.infrastructure.facade;

public interface SeriesFacade {
    /** 取文章在 series 中的導覽（給 ArticleQueryService 用） */
    Optional<SeriesNavigation> getSeriesNavigation(Long articleId);
}
```

### Series 跟 ReadingFacade 整合

`SeriesService.getSeriesDetail(slug, currentUserId)`：

```java
@Transactional(readOnly = true)
public SeriesDetailResponse getSeriesDetail(String slug, Long currentUserId) {
    Series series = repo.findBySlug(slug).orElseThrow(...);
    List<Article> articles = articleService.findBySeriesIdOrderByPosition(series.getId());

    SeriesDetailResponse resp = mapper.toDetailResponse(series, articles);

    if (currentUserId != null) {
        List<Long> articleIds = articles.stream().map(Article::getId).toList();
        Map<Long, BigDecimal> progressMap = readingFacade.batchGetProgress(currentUserId, articleIds);

        BigDecimal threshold = new BigDecimal("0.95");
        int readCount = (int) progressMap.values().stream()
            .filter(p -> p.compareTo(threshold) >= 0).count();

        UUID nextUnread = articles.stream()
            .filter(a -> {
                BigDecimal p = progressMap.get(a.getId());
                return p == null || p.compareTo(threshold) < 0;
            })
            .map(Article::getUuid)
            .findFirst().orElse(null);

        resp.setMyProgress(new MyProgress(readCount, articles.size(), nextUnread));
    }
    return resp;
}
```

### 反正規化計數機制

```java
// SeriesMapper.java
@Update("UPDATE series SET article_count = article_count + 1 WHERE id = #{id}")
int incrementArticleCount(@Param("id") Long id);

@Update("UPDATE series SET article_count = article_count - 1 WHERE id = #{id} AND article_count > 0")
int decrementArticleCount(@Param("id") Long id);
```

### Article 刪除連動 series.article_count

`ArticleServiceImpl.deleteArticle` 加邏輯：在物理刪除前，若 article.seriesId 非 null → 呼叫 SeriesFacade.decrementArticleCount（或同模組 service-to-service）。

⚠ 但 `ArticleService` 跟 `SeriesFacade` 是反方向（series 模組依賴 article，不能反過來注入會循環）。
解法：article 模組透過 `SeriesFacade` interface（在 infrastructure）取得 implementation。SeriesFacade 加一個 `decrementArticleCount(Long seriesId)` 方法，或讓 series 模組監聽 `ArticleDeletedEvent`（事件驅動）。

**選 event 路線最乾淨**：
- ArticleService.deleteArticle 發 `ArticleDeletedEvent { articleId, seriesId? }`
- SeriesService 監聽，若 seriesId 非 null → decrementArticleCount

但這次 spec 簡化用 SeriesFacade.notifyArticleDeleted 也接受（同步呼叫）。設計時擇一，實作時 case-by-case。

---

## 8. TDD 測試案例

### A. SeriesService（13 unit）

| # | 測試 |
|---|------|
| 1 | `createSeries_validRequest_savesWithUuidAndDefaults` |
| 2 | `createSeries_duplicateSlug_throwsS0104` |
| 3 | `updateSeries_byOwner_updatesAllFields` |
| 4 | `updateSeries_byNonOwnerNonAdmin_throwsS0102` |
| 5 | `updateSeries_byAdmin_succeeds` |
| 6 | `deleteSeries_byOwner_setsArticlesSeriesIdNull` |
| 7 | `addArticleToSeries_publishedArticle_savesAndIncrementsCount` |
| 8 | `addArticleToSeries_draftArticle_throwsS0103` |
| 9 | `addArticleToSeries_articleAlreadyInOtherSeries_throwsS0106` |
| 10 | `addArticleToSeries_sameSeriesUpdatesPosition` |
| 11 | `removeArticleFromSeries_decrementsCount` |
| 12 | `removeArticleFromSeries_articleNotInThisSeries_throwsS0105` |
| 13 | `getSeriesDetail_authenticated_includesMyProgress` |

### B. SeriesController IT（10 IT）

```
- POST /series 200 valid
- POST /series 400 invalid slug format
- POST /series 400 duplicate slug (S0104)
- POST /series 401 unauthenticated
- PUT /series/{uuid} 200 by owner
- PUT /series/{uuid} 400 by non-owner (S0102)
- DELETE /series/{uuid} 200 + articles unlinked
- PUT /series/{uuid}/articles/{articleUuid} 200
- PUT 400 if article in other series (S0106)
- GET /series/{slug} 含 myProgress（已登入）
```

### C. ArticleQueryService 擴充（3 unit）

```
- enrichSingle_articleInSeries_includesSeriesNav
- enrichSingle_seriesPositionFirst_prevIsNull
- enrichSingle_seriesPositionLast_nextIsNull
```

### D. ArticleLike 搬家後 regression（既有 13 tests 直接搬）

- ArticleLikeServiceTest（8 unit）
- ArticleLikeControllerIT（5 IT）

### E. ReadingFacade 擴充（2 unit）

加到 ReadingFacadeImplTest：
- `batchIsLiked_returnsCorrectIdSet`
- `isLiked_returnsTrueIfLiked`

### F. 跨模組整合 IT（3 IT）

```
- POST /series + add article → GET /articles/{uuid} 回 seriesNav 含 prev/next
- DELETE /articles/{uuid} → series.article_count -1
- DELETE /series/{uuid} → articles.series_id NULL
```

### 統計

| 模組 | unit | IT |
|------|------|------|
| SeriesService | 13 | — |
| SeriesController | — | 10 |
| ArticleQueryService 擴充 | 3 | — |
| ArticleLike 搬家 regression | 8 | 5 |
| ReadingFacade 擴充 | 2 | — |
| 跨模組整合 | — | 3 |
| **共** | **26** | **18** |

**總計 44 tests。**

---

## 9. 實作順序

| Task | 主題 | 類型 |
|------|------|------|
| **Phase 0 — Refactor 鋪路** |
| T1 | AuthorSummary 移到 `blog-common/api/dto/`（comment 改 import） | refactor |
| T2 | V15 migration（series + ALTER articles + rename article_likes） | schema |
| T3 | ArticleLike entity / repo / service / controller / IT 從 article 搬到 reading 模組 | refactor |
| T4 | ReadingFacade 加 `batchIsLiked` / `isLiked` + impl | refactor |
| T5 | ArticleQueryService 改用 ReadingFacade.batchIsLiked | refactor |
| **Phase 1 — Series 骨架** |
| T6 | `blog-module-series` 模組骨架（pom + dirs + root pom + blog-start） | skeleton |
| T7 | Series entity + Repository + Article entity 加 seriesId/seriesPosition | entity |
| T8 | SeriesErrorCode + DTOs（含 SeriesNavigation 在 infrastructure） | DTO |
| T9 | SeriesMapper（MyBatis JOIN users + paginated list + 反正規化 update） | mapper |
| **Phase 2 — Series 核心邏輯（TDD）** |
| T10 | SeriesService.createSeries / updateSeries / deleteSeries（5 unit） | TDD |
| T11 | SeriesService.addArticleToSeries / removeArticleFromSeries（5 unit） | TDD |
| T12 | SeriesService.getSeriesDetail（含 ReadingFacade，3 unit） | TDD |
| **Phase 3 — Series Facade 跨模組整合** |
| T13 | SeriesFacade interface + SeriesFacadeImpl | facade |
| T14 | ArticleQueryService 加 SeriesFacade enrich seriesNav（3 tests）+ ArticleResponse 加欄位 | enrich |
| T15 | ArticleService.deleteArticle 連動 series.article_count（透過 SeriesFacade or event） | regression |
| **Phase 4 — IT 與收尾** |
| T16 | SeriesController + IT（10 IT） | IT |
| T17 | 跨模組整合 IT（3 IT） | IT |
| T18 | schema.md 更新 V15 | docs |

每步嚴格 TDD：紅 → 綠 → Refactor。測試輸出存 `./logs/`。

---

## 10. 後續批次（提醒）

完成本批後：

- **批 4** — Draft History / Versioning（編輯器版本歷史）
- **可選批 5+** — Bookmark 分類 / Highlight 個人後台 / Series 章節 / Tag index 整合 series

---

## 附錄：相關文件更新清單

實作完成時須同步更新：

- [ ] `ai-docs/schema.md` 補 V15 的 series 表 + articles ALTER + article_likes rename
- [ ] auto memory：批 3 完成記錄
