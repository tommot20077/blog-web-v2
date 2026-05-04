# SP-C: Article Service Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `ArticleServiceImpl` 1020 行 god class 拆成 1 個薄 facade + 3 個 package-private sub-service + 2 個 helper class，對外 `ArticleService` interface 24 method 完全不變。

**Architecture:** Internal cleanup with sub-services（Bottom-up migration）。`ArticleServiceImpl` 變薄 facade（~150-200 行），internally delegate 給 `ArticleCommandSubService` (write 11) / `ArticleQuerySubService` (read 13) / `ArticleViewSubService` (recordView 1)。共用邏輯抽到 `ArticleEntityFinder` + `ArticleResponseMapper` helper class。全部 5 個新 class 放 `service/` 同包用 Java native package-private 限制範圍。`getArticleByUuid` / `getArticleBySlug` 由 facade 協調 query → view 順序（對齊 SP-D `applyRestoreContent` atomic flow pattern）。

**Tech Stack:** Spring Boot, Spring Data JDBC, JUnit 5, Mockito (`InOrder` for invocation-order verification), Lombok `@RequiredArgsConstructor`.

**Spec:** `docs/superpowers/specs/2026-05-03-sp-c-article-service-split-design.md`

**Worktree:** `D:\end\workspace\java\blog-web-v2\.worktrees\refactor-sp-c-article-service-split`
**Branch:** `refactor/sp-c-article-service-split` (from `develop@c5b5484`)

---

## Implementer Note: Method Body Extraction Pattern

本 plan 部分 task（特別 T1 / T3 / T4 / T5）的 method body 標註為「**從 ArticleServiceImpl L###-### 搬過來**」。這是 SP-C「搬既有 1020 行 code 到新位置」refactor 的工作 pattern。Implementer 執行此類 step 時必須：

1. **Read tool 取既有 code**：用 Read tool 取得 `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java` 指定行範圍的完整 method body
2. **Paste 到新位置**：將完整 body paste 到新 sub-service / helper class
3. **改 access modifier**：將 `public` 改為無 modifier（package-private）；private helper 保留 private 或改為 package-private 視 sub-service 內部需求
4. **更新 helper call sites**：method body 內既有 helper 呼叫對應改：
   - `findByUuidOrThrow(uuid)` → `entityFinder.findByUuidOrThrow(uuid)`
   - `toResponse(article, ...)` → `responseMapper.toResponse(article, tags, categories, liked, bookmarked, lastReadProgress, seriesNav)`
   - `toEditorResponse(article)` → `responseMapper.toEditorResponse(article, tagNames, categoryUuids)`
   - `toSummaryResponse(article)` → `responseMapper.toSummaryResponse(article, tags, categories)`
   - `toTagSummaryResponses(...)` → `responseMapper.toTagSummaryResponses(...)`
   - `batchToTagResponsesMap(...)` → `responseMapper.batchToTagResponsesMap(...)`
   - `toCategoryResponses(...)` → `responseMapper.toCategoryResponses(...)`
   - `batchToCategoryResponsesMap(...)` → `responseMapper.batchToCategoryResponsesMap(...)`
   - `resolveAuthorUuid(authorId)` → `responseMapper.resolveAuthorUuid(authorId)`
   - `resolveAuthorNickname(authorId)` → `responseMapper.resolveAuthorNickname(authorId)`
   - `processArticleView(article, ..., clientIp)` → 拆為 `entityFinder.findByUuidOrThrow + querySubService.checkReadPermission` 等（依 task 而定）

Implementer 不需要重新發明 method body — 只需將 existing logic 搬遷 + 修 helper call sites。每個搬遷 step 後對應 test（搬到 sub-service test file）的 verify 為「行為等價」的證據。

---

## File Structure

### 新建檔案 (5 個 production + 5 個 test)

| File | 職責 |
|---|---|
| `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleEntityFinder.java` | 共用 — `findByUuidOrThrow(UUID): Article`，給 Command + Query 共用 |
| `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleResponseMapper.java` | Query 用 — Article entity → ArticleResponse / EditorArticleResponse / ArticleSummaryResponse 9 個 mapper method |
| `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleViewSubService.java` | View tracking — `recordView(UUID, ArticleStatus)`，含 `isPubliclyVisible` guard + Redis 防刷 + publishViewed event |
| `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleCommandSubService.java` | Write — 11 method (6 Command + 4 Counter + 1 cross-module write) + 5 private helper |
| `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleQuerySubService.java` | Read — 13 method (7 Query + 6 cross-module read)，含 read-permission guard |
| `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleEntityFinderTest.java` | 1 method × 2 cases (existing / not-found) |
| `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleResponseMapperTest.java` | 9 mapper method 驗證 |
| `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleViewSubServiceTest.java` | recordView 4 visibility cases + Redis 防刷 + publishViewed |
| `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleCommandSubServiceTest.java` | 11 method 業務邏輯（從既有 ArticleServiceTest 搬過來）|
| `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleQuerySubServiceTest.java` | 13 method 業務邏輯（從既有 ArticleServiceTest 搬過來）|

### 修改檔案 (2 個)

| File | 改動 |
|---|---|
| `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java` | 從 1020 行 → ~200 行薄 facade，inject 11 → 3 個 sub-service，多數 method 純 1-line delegate，`getArticleByUuid` / `getArticleBySlug` 含 query → view 協調 |
| `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleServiceTest.java` | 從 1981 行 → ~200 行 facade test，重點驗委派 + 協調順序（既有 sub-service 細節搬到對應 sub-service test）|

### 不動檔案

- `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleService.java`（24 method interface 不變）
- `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleQueryService.java`（既有 decorator 不動）
- `blog-module-article/src/main/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImpl.java`（仍 inject ArticleService）
- `blog-module-article/src/test/java/dowob/xyz/blog/module/article/controller/ArticleControllerIT.java`（952 行黑盒 IT 不變仍綠）

---

## Task 1: ArticleEntityFinder + ArticleResponseMapper helper class

**Files:**
- Create: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleEntityFinder.java`
- Create: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleResponseMapper.java`
- Create: `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleEntityFinderTest.java`
- Create: `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleResponseMapperTest.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java`（加 inject + 改 caller 用新 helper）

- [ ] **Step 1: 寫 ArticleEntityFinderTest（TDD red — 2 cases）**

```bash
Write blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleEntityFinderTest.java
```

```java
package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleEntityFinderTest {

    @Mock private ArticleRepository articleRepository;

    private ArticleEntityFinder finder;

    @BeforeEach
    void setUp() {
        finder = new ArticleEntityFinder(articleRepository);
    }

    @Test
    @DisplayName("article 存在 → return Article entity")
    void findByUuidOrThrow_existing_returnsArticle() {
        UUID uuid = UUID.randomUUID();
        Article article = new Article();
        article.setId(100L);
        article.setUuid(uuid);
        when(articleRepository.findByUuid(uuid)).thenReturn(Optional.of(article));

        Article result = finder.findByUuidOrThrow(uuid);

        assertThat(result).isSameAs(article);
    }

    @Test
    @DisplayName("article 不存在 → throw ARTICLE_NOT_FOUND")
    void findByUuidOrThrow_notFound_throws() {
        UUID uuid = UUID.randomUUID();
        when(articleRepository.findByUuid(uuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> finder.findByUuidOrThrow(uuid))
            .isInstanceOf(BusinessException.class)
            .extracting(t -> ((BusinessException) t).getCode())
            .isEqualTo(ArticleErrorCode.ARTICLE_NOT_FOUND.getCode());
    }
}
```

- [ ] **Step 2: Run tests — verify RED**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleEntityFinderTest 2>&1 | tee logs/t1-finder-red.log | grep -E "Tests run:|BUILD|ERROR" | tail -5
```

Expected: 編譯失敗 — `ArticleEntityFinder` class 還不存在。

- [ ] **Step 3: 建 ArticleEntityFinder（package-private，1 method — GREEN）**

```bash
Write blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleEntityFinder.java
```

```java
package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Article entity 查找共用 helper。
 *
 * <p>給 Command / Query sub-service 共用 — 避免 findByUuidOrThrow 在多個 sub-service 重複實作。</p>
 *
 * <p><strong>Package-private</strong>：僅 article 模組 service package 內可訪問，不對外暴露。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
class ArticleEntityFinder {

    private final ArticleRepository articleRepository;

    /**
     * 撈 Article entity，不存在 throw ARTICLE_NOT_FOUND。
     *
     * @param articleUuid 文章公開 UUID
     * @return Article entity
     * @throws BusinessException ARTICLE_NOT_FOUND 若 uuid 對應 article 不存在
     */
    Article findByUuidOrThrow(UUID articleUuid) {
        return articleRepository.findByUuid(articleUuid)
            .orElseThrow(() -> new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));
    }
}
```

- [ ] **Step 4: Run tests — verify GREEN**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleEntityFinderTest 2>&1 | tee logs/t1-finder-green.log | grep -E "Tests run:|BUILD" | tail -5
```

Expected: 2 cases pass。

- [ ] **Step 5: 寫 ArticleResponseMapperTest（TDD red — 7 cases）**

```bash
Write blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleResponseMapperTest.java
```

⚠ test 結構：每個 mapper method 驗證主要欄位轉換正確（不需 cover 全部 20 欄位 — 抽樣覆蓋 5-7 個重要欄位 + null safety case）。

```java
package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.response.*;
import dowob.xyz.blog.module.article.service.ViewCountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArticleResponseMapperTest {

    @Mock private UserFacade userFacade;
    @Mock private ViewCountService viewCountService;

    private ArticleResponseMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ArticleResponseMapper(userFacade, viewCountService);
    }

    @Nested
    @DisplayName("toResponse — 主要 entity → ArticleResponse")
    class ToResponse {

        @Test
        @DisplayName("正常 article → 對齊 22 欄位 + view count 取自 viewCountService")
        void toResponse_completesAllFields() {
            UUID uuid = UUID.randomUUID();
            UUID authorUuid = UUID.randomUUID();
            Article article = new Article();
            article.setId(100L);
            article.setUuid(uuid);
            article.setAuthorId(5L);
            article.setTitle("Title");
            article.setContent("# md");
            article.setContentHtml("<h1>md</h1>");
            article.setSummary("summary");
            article.setStatus(ArticleStatus.PUBLISHED);
            article.setSlug("slug");
            article.setLikeCount(10);
            article.setCommentCount(5);
            article.setCreatedAt(LocalDateTime.now());
            article.setPublishedAt(LocalDateTime.now());

            when(userFacade.getUserUuid(5L)).thenReturn(authorUuid);
            when(userFacade.getUserNickname(5L)).thenReturn("Yuan");
            when(viewCountService.getViewCount(100L)).thenReturn(1234L);

            ArticleResponse resp = mapper.toResponse(article, List.of(), List.of(),
                false, false, null, null);

            assertThat(resp.getUuid()).isEqualTo(uuid);
            assertThat(resp.getTitle()).isEqualTo("Title");
            assertThat(resp.getContent()).isEqualTo("# md");
            assertThat(resp.getContentHtml()).isEqualTo("<h1>md</h1>");
            assertThat(resp.getSummary()).isEqualTo("summary");
            assertThat(resp.getAuthorUuid()).isEqualTo(authorUuid);
            assertThat(resp.getAuthorNickname()).isEqualTo("Yuan");
            assertThat(resp.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
            assertThat(resp.getViewCount()).isEqualTo(1234L);
            assertThat(resp.getSlug()).isEqualTo("slug");
            assertThat(resp.getLikeCount()).isEqualTo(10);
            assertThat(resp.getCommentCount()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("toEditorResponse — entity → editor response（不含 view count）")
    class ToEditorResponse {

        @Test
        @DisplayName("editor response 含原始 content 與 tag/category names")
        void toEditorResponse_includesRawFields() {
            Article article = new Article();
            article.setUuid(UUID.randomUUID());
            article.setTitle("Edit");
            article.setContent("raw md");
            article.setStatus(ArticleStatus.DRAFT);
            article.setSlug("edit-slug");

            EditorArticleResponse resp = mapper.toEditorResponse(article, List.of("Java"), List.of());

            assertThat(resp.getTitle()).isEqualTo("Edit");
            assertThat(resp.getContent()).isEqualTo("raw md");
            assertThat(resp.getStatus()).isEqualTo(ArticleStatus.DRAFT);
            assertThat(resp.getTagNames()).containsExactly("Java");
        }
    }

    @Nested
    @DisplayName("toSummaryResponse — entity → summary（list 用，省略 content）")
    class ToSummaryResponse {

        @Test
        @DisplayName("summary 不含 content 與 contentHtml")
        void toSummaryResponse_excludesContent() {
            UUID uuid = UUID.randomUUID();
            Article article = new Article();
            article.setId(200L);
            article.setUuid(uuid);
            article.setAuthorId(3L);
            article.setTitle("List title");
            article.setSummary("summary");
            article.setStatus(ArticleStatus.PUBLISHED);

            when(userFacade.getUserUuid(3L)).thenReturn(UUID.randomUUID());
            when(userFacade.getUserNickname(3L)).thenReturn("Author");
            when(viewCountService.getViewCount(200L)).thenReturn(50L);

            ArticleSummaryResponse resp = mapper.toSummaryResponse(article, List.of(), List.of());

            assertThat(resp.getTitle()).isEqualTo("List title");
            assertThat(resp.getSummary()).isEqualTo("summary");
            assertThat(resp.getViewCount()).isEqualTo(50L);
        }
    }

    @Nested
    @DisplayName("子轉換 mapper")
    class ChildMappers {

        @Test
        @DisplayName("toTagSummaryResponses — null 或空列表 → 空 list")
        void toTagSummaryResponses_null_returnsEmpty() {
            assertThat(mapper.toTagSummaryResponses(null)).isEmpty();
        }

        @Test
        @DisplayName("toCategoryResponses — null → 空 list")
        void toCategoryResponses_null_returnsEmpty() {
            assertThat(mapper.toCategoryResponses(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Author resolver 1-line wrapper")
    class AuthorResolver {

        @Test
        @DisplayName("resolveAuthorUuid — delegate to userFacade")
        void resolveAuthorUuid_delegates() {
            UUID expected = UUID.randomUUID();
            when(userFacade.getUserUuid(7L)).thenReturn(expected);

            assertThat(mapper.resolveAuthorUuid(7L)).isEqualTo(expected);
        }

        @Test
        @DisplayName("resolveAuthorNickname — delegate to userFacade")
        void resolveAuthorNickname_delegates() {
            when(userFacade.getUserNickname(7L)).thenReturn("Yuan");

            assertThat(mapper.resolveAuthorNickname(7L)).isEqualTo("Yuan");
        }
    }
}
```

- [ ] **Step 6: Run tests — verify RED**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleResponseMapperTest 2>&1 | tee logs/t1-mapper-red.log | grep -E "Tests run:|BUILD|ERROR" | tail -5
```

Expected: 編譯失敗 — `ArticleResponseMapper` class 還不存在。

- [ ] **Step 7: 建 ArticleResponseMapper（package-private，9 method — GREEN）**

```bash
Write blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleResponseMapper.java
```

```java
package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.service.ViewCountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Article entity → response DTO 轉換 helper（Query sub-service 用）。
 *
 * <p>9 個 method：3 個主轉換（toResponse / toEditorResponse / toSummaryResponse）+
 * 4 個子轉換（tag / category，含 batch 版）+ 2 個 author resolver。</p>
 *
 * <p><strong>Package-private</strong>：僅 article 模組 service package 內可訪問。
 * Command sub-service 不負責 response 構造（write 流程不需 response）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
class ArticleResponseMapper {

    private final UserFacade userFacade;
    private final ViewCountService viewCountService;

    // 主要 mapper 3 個（從 ArticleServiceImpl L710-787 搬過來）

    /**
     * Article entity → ArticleResponse。完整 response（給 getArticleByUuid / getArticleBySlug 用）。
     */
    ArticleResponse toResponse(Article article, List<TagSummaryResponse> tags,
                                List<CategoryResponse> categories,
                                Boolean liked, Boolean bookmarked, BigDecimal lastReadProgress,
                                SeriesNavigationResponse seriesNav) {
        // 從既有 ArticleServiceImpl.toResponse (L710-733) 完整搬過來
        // 24 行邏輯，含 viewCountService.getViewCount(article.getId()) 取 view count
        // builder 設值：uuid/title/content/contentHtml/summary/coverImageUrl/authorUuid/authorNickname/
        //              status/viewCount/createdAt/updatedAt/categories/slug/likeCount/commentCount/
        //              publishedAt/tags/rejectReason/liked/bookmarked/lastReadProgress/seriesNav
        return ArticleResponse.builder()
            .uuid(article.getUuid())
            .title(article.getTitle())
            .content(article.getContent())
            .contentHtml(article.getContentHtml())
            .summary(article.getSummary())
            .coverImageUrl(article.getCoverImageUrl())
            .authorUuid(resolveAuthorUuid(article.getAuthorId()))
            .authorNickname(resolveAuthorNickname(article.getAuthorId()))
            .status(article.getStatus())
            .viewCount(viewCountService.getViewCount(article.getId()))
            .createdAt(article.getCreatedAt())
            .updatedAt(article.getUpdatedAt())
            .categories(categories)
            .slug(article.getSlug())
            .likeCount(article.getLikeCount())
            .commentCount(article.getCommentCount())
            .publishedAt(article.getPublishedAt())
            .tags(tags)
            .rejectReason(article.getRejectReason())
            .liked(liked)
            .bookmarked(bookmarked)
            .lastReadProgress(lastReadProgress)
            .seriesNav(seriesNav)
            .build();
    }

    /**
     * Article entity → EditorArticleResponse（給 getArticleForEdit 用，作者編輯器需要原始內容）。
     */
    EditorArticleResponse toEditorResponse(Article article, List<String> tagNames, List<UUID> categoryUuids) {
        // 從既有 ArticleServiceImpl.toEditorResponse (L745-759) 搬過來，15 行
        return EditorArticleResponse.builder()
            .uuid(article.getUuid())
            .title(article.getTitle())
            .content(article.getContent())
            .summary(article.getSummary())
            .coverImageUrl(article.getCoverImageUrl())
            .slug(article.getSlug())
            .status(article.getStatus())
            .tagNames(tagNames)
            .categoryUuids(categoryUuids)
            .build();
    }

    /**
     * Article entity → ArticleSummaryResponse（給 list 用，省略 content / contentHtml）。
     */
    ArticleSummaryResponse toSummaryResponse(Article article, List<TagSummaryResponse> tags,
                                              List<CategoryResponse> categories) {
        // 從既有 ArticleServiceImpl.toSummaryResponse (L767-787) 搬過來，21 行
        return ArticleSummaryResponse.builder()
            .uuid(article.getUuid())
            .title(article.getTitle())
            .summary(article.getSummary())
            .coverImageUrl(article.getCoverImageUrl())
            .authorUuid(resolveAuthorUuid(article.getAuthorId()))
            .authorNickname(resolveAuthorNickname(article.getAuthorId()))
            .status(article.getStatus())
            .viewCount(viewCountService.getViewCount(article.getId()))
            .createdAt(article.getCreatedAt())
            .updatedAt(article.getUpdatedAt())
            .categories(categories)
            .slug(article.getSlug())
            .likeCount(article.getLikeCount())
            .commentCount(article.getCommentCount())
            .publishedAt(article.getPublishedAt())
            .tags(tags)
            .build();
    }

    // 子轉換 4 個（tag / category，含 batch）

    List<TagSummaryResponse> toTagSummaryResponses(List<TagInfo> tagInfos) {
        // 從既有 toTagSummaryResponses (L822-830) 搬過來，9 行
        if (tagInfos == null) return List.of();
        return tagInfos.stream()
            .map(t -> TagSummaryResponse.builder()
                .uuid(t.id())
                .name(t.name())
                .slug(t.slug())
                .build())
            .toList();
    }

    Map<Long, List<TagSummaryResponse>> batchToTagResponsesMap(Map<Long, List<TagInfo>> batchTags) {
        // 從既有 batchToTagResponsesMap (L842-853) 搬過來，12 行
        if (batchTags == null) return Map.of();
        return batchTags.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                e -> toTagSummaryResponses(e.getValue())
            ));
    }

    List<CategoryResponse> toCategoryResponses(List<Category> categories) {
        // 從既有 toCategoryResponses (L861-866) 搬過來，6 行
        if (categories == null) return List.of();
        return categories.stream()
            .map(c -> CategoryResponse.builder()
                .uuid(c.getUuid())
                .name(c.getName())
                .slug(c.getSlug())
                .build())
            .toList();
    }

    Map<Long, List<CategoryResponse>> batchToCategoryResponsesMap(Map<Long, List<Category>> batchCategories) {
        // 從既有 batchToCategoryResponsesMap (L874-888) 搬過來，15 行
        if (batchCategories == null) return Map.of();
        return batchCategories.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                e -> toCategoryResponses(e.getValue())
            ));
    }

    // Author resolver 2 個（userFacade wrapper）

    UUID resolveAuthorUuid(Long authorId) {
        return userFacade.getUserUuid(authorId);
    }

    String resolveAuthorNickname(Long authorId) {
        return userFacade.getUserNickname(authorId);
    }
}
```

⚠ method body 細節（builder fields、helper 內部 collector）以「從 ArticleServiceImpl L710-888 搬過來」為原則。implementer 應從既有 method **複製** body 後改 access modifier 為 package-private。

- [ ] **Step 8: Run tests — verify GREEN**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleResponseMapperTest 2>&1 | tee logs/t1-mapper-green.log | grep -E "Tests run:|BUILD" | tail -5
```

Expected: ~7 cases pass。

- [ ] **Step 9: 修 ArticleServiceImpl — 加 inject helper class + 改 caller**

於 `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java` 既有 11 inject 之後加：

```java
private final ArticleEntityFinder entityFinder;
private final ArticleResponseMapper responseMapper;
```

⚠ T1 階段 inject 13 個（11 既有 + 2 新 helper）。T5 將清理為只 inject 3 個 sub-service。

修改既有 method body：
- 既有 `findByUuidOrThrow(UUID)` private method — 移除（外部移到 ArticleEntityFinder），所有 caller 改 `entityFinder.findByUuidOrThrow(uuid)`
- 既有 `toResponse / toEditorResponse / toSummaryResponse` private method — 移除，caller 改 `responseMapper.toXxx(...)`
- 既有 `toTagSummaryResponses / batchToTagResponsesMap / toCategoryResponses / batchToCategoryResponsesMap` private — 移除，caller 改 `responseMapper.toXxx(...)`
- 既有 `resolveAuthorUuid / resolveAuthorNickname` private — 移除，caller 改 `responseMapper.resolveAuthorXxx(...)`

⚠ ArticleServiceImpl 行數此時減約 100-130 行（17 helper 中的 9 個移走）。

- [ ] **Step 10: install + Run all article 模組 tests verify 無 regression**

```bash
./mvnw.cmd -pl blog-module-article test 2>&1 | tee logs/t1-tests.log | grep -E "Tests run:|BUILD" | tail -10
```

Expected:
- ArticleEntityFinderTest 2 cases pass
- ArticleResponseMapperTest ~7 cases pass
- 既有 ArticleServiceTest 1981 行邏輯 pass（caller 換成 helper class call，行為等價）

- [ ] **Step 11: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleEntityFinder.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleResponseMapper.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleEntityFinderTest.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleResponseMapperTest.java
git commit -m "$(cat <<'EOF'
refactor(article): 抽出 ArticleEntityFinder + ArticleResponseMapper helper class

SP-C T1 — ArticleServiceImpl 既有 17 private helper 中的 9 個搬到 helper class：
- ArticleEntityFinder：findByUuidOrThrow（共用，給 Command + Query 用）
- ArticleResponseMapper：toResponse / toEditorResponse / toSummaryResponse / 子轉換 4 個 / author resolver 2 個

均為 package-private（同 service package 限制範圍）。ArticleServiceImpl 對應 caller 改用
新 helper class，行數減約 100 行。既有 ArticleServiceTest 1981 行行為等價仍綠。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: ArticleViewSubService（recordView with isPubliclyVisible guard）

**Files:**
- Create: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleViewSubService.java`
- Create: `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleViewSubServiceTest.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java`（getArticleByUuid + getArticleBySlug 改 call viewSubService.recordView）

- [ ] **Step 1: 寫 ArticleViewSubServiceTest（TDD red — 6 cases）**

```bash
Write blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleViewSubServiceTest.java
```

```java
package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArticleViewSubServiceTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ArticleEventPublisher articleEventPublisher;
    @Mock private ValueOperations<String, String> valueOperations;

    private ArticleViewSubService viewSubService;

    @BeforeEach
    void setUp() {
        viewSubService = new ArticleViewSubService(stringRedisTemplate, articleEventPublisher);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Nested
    @DisplayName("Published-only guard — 4 visibility states")
    class PublishedOnlyGuard {

        @Test
        @DisplayName("PUBLISHED → 進入 Redis 防刷 + publishViewed event")
        void recordView_publishedArticle_publishesEvent() {
            UUID uuid = UUID.randomUUID();
            when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true);

            viewSubService.recordView(uuid, ArticleStatus.PUBLISHED, "1.2.3.4");

            verify(valueOperations).setIfAbsent(anyString(), eq("1"), any(Duration.class));
            verify(articleEventPublisher).publishViewed(uuid);
        }

        @Test
        @DisplayName("DRAFT → guard short-circuit，不 Redis 查也不 publish")
        void recordView_draftArticle_doesNotPublish() {
            viewSubService.recordView(UUID.randomUUID(), ArticleStatus.DRAFT, "1.2.3.4");

            verifyNoInteractions(stringRedisTemplate, articleEventPublisher);
        }

        @Test
        @DisplayName("PENDING_REVIEW → guard short-circuit")
        void recordView_pendingReviewArticle_doesNotPublish() {
            viewSubService.recordView(UUID.randomUUID(), ArticleStatus.PENDING_REVIEW, "1.2.3.4");

            verifyNoInteractions(stringRedisTemplate, articleEventPublisher);
        }

        @Test
        @DisplayName("ARCHIVED → guard short-circuit")
        void recordView_archivedArticle_doesNotPublish() {
            viewSubService.recordView(UUID.randomUUID(), ArticleStatus.ARCHIVED, "1.2.3.4");

            verifyNoInteractions(stringRedisTemplate, articleEventPublisher);
        }

        @Test
        @DisplayName("REJECTED → guard short-circuit")
        void recordView_rejectedArticle_doesNotPublish() {
            viewSubService.recordView(UUID.randomUUID(), ArticleStatus.REJECTED, "1.2.3.4");

            verifyNoInteractions(stringRedisTemplate, articleEventPublisher);
        }
    }

    @Nested
    @DisplayName("Redis 防刷")
    class RedisDedup {

        @Test
        @DisplayName("PUBLISHED + repeat visit (Redis already has key) → 不發 event")
        void recordView_repeatVisit_doesNotPublish() {
            UUID uuid = UUID.randomUUID();
            when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(false);    // already exists → not first visit

            viewSubService.recordView(uuid, ArticleStatus.PUBLISHED, "1.2.3.4");

            verify(valueOperations).setIfAbsent(anyString(), eq("1"), any(Duration.class));
            verify(articleEventPublisher, never()).publishViewed(any());
        }
    }
}
```

⚠ test 暫時假設 `recordView` signature 為 `recordView(UUID, ArticleStatus, String clientIp)` — 由 caller 傳入 clientIp（既有 ArticleService.getArticleByUuid 已 4 參數含 clientIp，可直傳）。

- [ ] **Step 2: Run tests — verify RED**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleViewSubServiceTest 2>&1 | tee logs/t2-red.log | grep -E "Tests run:|BUILD|ERROR" | tail -5
```

Expected: 編譯失敗 — `ArticleViewSubService` class 還沒 implement。

- [ ] **Step 3: 建 ArticleViewSubService（package-private，1 method）**

```bash
Write blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleViewSubService.java
```

```java
package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Article view tracking sub-service。
 *
 * <p>負責：</p>
 * <ol>
 *   <li>Published-only guard — 只有 PUBLISHED 狀態才記 view（draft / pending / archived 不發）</li>
 *   <li>Redis 防刷 — 同 IP 5 分鐘內首次訪問才 publish event</li>
 *   <li>非同步 ViewCountEvent — 透過 ArticleEventPublisher.publishViewed 觸發</li>
 * </ol>
 *
 * <p><strong>Package-private</strong>：僅 article 模組 service package 內可訪問。
 * 由 ArticleServiceImpl 在 query 流程後協調呼叫（getArticleByUuid / getArticleBySlug）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
class ArticleViewSubService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ArticleEventPublisher articleEventPublisher;

    private static final String VIEW_KEY_PREFIX = "view:";
    private static final Duration VIEW_DEDUP_TTL = Duration.ofMinutes(5);

    /**
     * 記錄 article view（含 published-only guard + Redis 防刷 + 異步 ViewCountEvent）。
     *
     * <p>對齊既有 processArticleView 行為：只有 status 為公開可見時才 publish event，
     * 避免 author / admin 看自己的 draft 時錯誤觸發 view counter。</p>
     *
     * @param articleUuid 文章 UUID
     * @param status      文章狀態（用於 published-only guard）
     * @param clientIp    客戶端 IP（用於 Redis 防刷 key），由 caller 從 RequestContextHolder 取
     */
    void recordView(UUID articleUuid, ArticleStatus status, String clientIp) {
        if (!status.isPubliclyVisible()) {
            return;
        }
        String viewKey = VIEW_KEY_PREFIX + articleUuid + ":" + clientIp;
        Boolean firstVisit = stringRedisTemplate.opsForValue()
            .setIfAbsent(viewKey, "1", VIEW_DEDUP_TTL);
        if (Boolean.TRUE.equals(firstVisit)) {
            articleEventPublisher.publishViewed(articleUuid);
        }
    }
}
```

- [ ] **Step 4: Run tests — verify GREEN**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleViewSubServiceTest 2>&1 | tee logs/t2-green.log | grep -E "Tests run:|BUILD" | tail -5
```

Expected: 6 cases pass。

- [ ] **Step 5: 修 ArticleServiceImpl — 加 inject + 改 getArticleByUuid / getArticleBySlug 用 viewSubService**

於 `ArticleServiceImpl.java`：

加 inject（既有 13 → 14）：
```java
private final ArticleViewSubService viewSubService;
```

修 `getArticleByUuid` method（既有 L322-331）：

```java
// Before
@Override
public ArticleResponse getArticleByUuid(UUID articleUuid, Long viewerId, Role viewerRole, String clientIp) {
    Article article = entityFinder.findByUuidOrThrow(articleUuid);
    return processArticleView(article, viewerId, viewerRole, clientIp);
}

// After
@Override
public ArticleResponse getArticleByUuid(UUID articleUuid, Long viewerId, Role viewerRole, String clientIp) {
    Article article = entityFinder.findByUuidOrThrow(articleUuid);

    // Read-permission check (既有 processArticleView 邏輯內的 throw 部分)
    boolean isAdmin = Role.ADMIN == viewerRole;
    boolean isAuthor = java.util.Objects.equals(article.getAuthorId(), viewerId);
    if (!article.getStatus().isPubliclyVisible() && !isAdmin && !isAuthor) {
        throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
    }

    // Build response (既有 processArticleView 內的 return toResponse)
    ArticleResponse resp = responseMapper.toResponse(article, /* tags */ null, /* categories */ null,
        /* liked */ null, /* bookmarked */ null, /* lastReadProgress */ null, /* seriesNav */ null);
    // 註：tags/categories/liked/bookmarked 等 enrich 邏輯仍在 ArticleServiceImpl，由
    // ArticleQueryService decorator 之後 enrich。此 task 維持既有行為。

    // View tracking via sub-service
    viewSubService.recordView(articleUuid, article.getStatus(), clientIp);

    return resp;
}
```

⚠ 注意：實際既有 `processArticleView` 還有 enrich tags / categories 等邏輯（從 `toResponse` 內呼叫）。T2 階段優先**用 viewSubService 取代 view tracking 部分**，enrich 邏輯仍由 ArticleServiceImpl 既有 method body 處理（T4 才搬到 QuerySubService）。

修 `getArticleBySlug` 同樣 pattern。

⚠ T2 階段 ArticleServiceImpl 仍含 read-permission check + enrich logic — 這些屬 query 流程，T4 將搬到 QuerySubService。本 task 只專注「view tracking 抽出」。

- [ ] **Step 6: Run all article 模組 tests verify 無 regression**

```bash
./mvnw.cmd -pl blog-module-article test 2>&1 | tee logs/t2-article-tests.log | grep -E "^\[INFO\] Tests run:" | tail -5
```

Expected: 既有 ArticleServiceTest 1981 行 + 新 ArticleViewSubServiceTest 6 全綠。

- [ ] **Step 7: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleViewSubService.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleViewSubServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(article): 提升 processArticleView 為 ArticleViewSubService.recordView

SP-C T2 — view tracking 邏輯從 ArticleServiceImpl private helper 抽到獨立 sub-service：
- 簽名 recordView(UUID, ArticleStatus, String clientIp)
- 內部 isPubliclyVisible guard（對齊既有 processArticleView，draft/pending 不發 event）
- 6 unit tests cover 5 visibility states + repeat visit dedup

ArticleServiceImpl.getArticleByUuid / getArticleBySlug 改 call viewSubService.recordView
取代既有 processArticleView 中的 view tracking 部分。
read-permission check + enrich logic 仍留在 ArticleServiceImpl（T4 將搬至 QuerySubService）。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: ArticleCommandSubService（11 method + 5 private helper）

**Files:**
- Create: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleCommandSubService.java`
- Create: `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleCommandSubServiceTest.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java`（11 method 改 1-line delegate to commandSubService）
- Modify: `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleServiceTest.java`（既有 11 method 對應的 test 從這裡搬到新 ArticleCommandSubServiceTest）

- [ ] **Step 1: 建 ArticleCommandSubServiceTest（從既有 ArticleServiceTest 搬 11 method 對應的 test — TDD red）**

```bash
Write blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleCommandSubServiceTest.java
```

⚠ 從既有 `ArticleServiceTest.java` 搬以下 @Nested class（含內部 test methods）到新 file：
- L185 CreateArticleTests
- L419 UpdateArticleTests
- L649 DeleteArticleTests
- L759 PublishArticleTests
- L916 RejectArticleTests
- L951 SubmitForReviewTests
- L1468 IncrementCommentCountTests
- L1560 DecrementCommentCountTests
- L1626 IncrementLikeCountTests
- L1707 DecrementLikeCountTests
- （updateSeriesAssignment 對應的 test 在 L1930 OtherCrossModuleTests，搬該部分）

新 test class 的 setUp 改用 ArticleCommandSubService 而非 ArticleServiceImpl：

```java
package dowob.xyz.blog.module.article.service;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArticleCommandSubServiceTest {

    @Mock private ArticleRepository articleRepository;
    @Mock private ArticleEventPublisher articleEventPublisher;
    @Mock private CategoryMapper categoryMapper;
    @Mock private CategoryRepository categoryRepository;
    @Mock private TagFacade tagFacade;
    @Mock private ArticleMarkdownRenderer markdownRenderer;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ArticleEntityFinder entityFinder;

    private ArticleCommandSubService commandSubService;

    @BeforeEach
    void setUp() {
        commandSubService = new ArticleCommandSubService(
            articleRepository, articleEventPublisher, categoryMapper, categoryRepository,
            tagFacade, markdownRenderer, transactionTemplate, entityFinder
        );

        // transactionTemplate.execute → 立即執行 callback
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
            ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
    }

    // 從 ArticleServiceTest 搬 10 個 @Nested class 過來，將 articleService.xxx 改 commandSubService.xxx
    @Nested class CreateArticleTests { /* 從 L185-418 搬過來 */ }
    @Nested class UpdateArticleTests { /* 從 L419-648 搬過來 */ }
    @Nested class DeleteArticleTests { /* 從 L649-758 搬過來 */ }
    @Nested class PublishArticleTests { /* 從 L759-915 搬過來 */ }
    @Nested class RejectArticleTests { /* 從 L916-950 搬過來 */ }
    @Nested class SubmitForReviewTests { /* 從 L951-1045 搬過來 */ }
    @Nested class IncrementCommentCountTests { /* 從 L1468-1559 搬過來 */ }
    @Nested class DecrementCommentCountTests { /* 從 L1560-1625 搬過來 */ }
    @Nested class IncrementLikeCountTests { /* 從 L1626-1706 搬過來 */ }
    @Nested class DecrementLikeCountTests { /* 從 L1707-1764 搬過來 */ }
    @Nested class UpdateSeriesAssignmentTests { /* 從 L1930+ OtherCrossModuleTests 搬 updateSeriesAssignment 部分 */ }
}
```

⚠ 從既有 ArticleServiceTest 搬時：
- `articleService.createArticle(...)` 改 `commandSubService.createArticle(...)`
- `articleService.findByUuidOrThrow(...)` 改 `entityFinder.findByUuidOrThrow(...)`（mock entityFinder 取代既有 articleRepository.findByUuid mock）
- toResponse 相關 mock 移除（Command 不負責 toResponse）
- 不再需要的 mock fields（StringRedisTemplate, ViewCountService, UserFacade）移除

- [ ] **Step 2: Run tests — verify RED**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleCommandSubServiceTest 2>&1 | tee logs/t3-red.log | grep -E "Tests run:|BUILD|ERROR" | tail -5
```

Expected: 編譯失敗 — `ArticleCommandSubService` class 還不存在。

- [ ] **Step 3: 建 ArticleCommandSubService（package-private，11 method + 5 private helper — GREEN）**

```bash
Write blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleCommandSubService.java
```

⚠ 內容 = 從 ArticleServiceImpl L135-577（Command 6 method）+ L896-928（Counter 4 method）+ L988-994（updateSeriesAssignment）+ L597-642 / L686-698 / L800-809（5 private helper）**完整搬過來**。

```java
package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.mapper.CategoryMapper;
import dowob.xyz.blog.module.category.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Article command sub-service — 11 method (write 業務邏輯)。
 *
 * <p><strong>Package-private</strong>：僅 article 模組 service package 內可訪問。
 * 由 ArticleServiceImpl 委派 Command / Counter / Cross-module write 流程。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
class ArticleCommandSubService {

    private final ArticleRepository articleRepository;
    private final ArticleEventPublisher articleEventPublisher;
    private final CategoryMapper categoryMapper;
    private final CategoryRepository categoryRepository;
    private final TagFacade tagFacade;
    private final ArticleMarkdownRenderer markdownRenderer;
    private final TransactionTemplate transactionTemplate;
    private final ArticleEntityFinder entityFinder;

    // ─── Command 6 method（write 業務邏輯）───

    EditorArticleResponse createArticle(Long authorId, CreateArticleRequest request) {
        // 從 ArticleServiceImpl.createArticle (L135-186) 完整搬過來
        // 52 行邏輯：generateSlug + extractSummary + convertToHtml + 持久化 + tag/category sync + publishCreated
        // ... method body
    }

    EditorArticleResponse updateArticle(Long operatorId, Role operatorRole, UUID articleUuid,
                                         UpdateArticleRequest request) {
        // 從 ArticleServiceImpl.updateArticle (L198-279) 完整搬過來
        // 82 行邏輯：findByUuidOrThrow → checkWritePermission → mutate fields → save → tag/category sync
        // 注意：findByUuidOrThrow 改 call entityFinder.findByUuidOrThrow
        // ... method body
    }

    void deleteArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
        // 從 ArticleServiceImpl.deleteArticle (L289-311) 完整搬過來
        // 23 行邏輯：含 publishDeleted with rich payload (seriesId, categoryIds, tagIds)
        // ... method body
    }

    ArticleResponse publishArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
        // 從 ArticleServiceImpl.publishArticle (L477-511) 搬過來，35 行
    }

    ArticleResponse rejectArticle(Long operatorId, Role operatorRole, UUID articleUuid, String reason) {
        // 從 ArticleServiceImpl.rejectArticle (L524-538) 搬過來，15 行
    }

    ArticleResponse submitForReview(Long operatorId, Role operatorRole, UUID articleUuid) {
        // 從 ArticleServiceImpl.submitForReview (L570-577) 搬過來，8 行
    }

    // ─── Counter 4 method（純 mapper.update，無業務邏輯）───

    void incrementCommentCount(Long articleId) {
        // 從 ArticleServiceImpl.incrementCommentCount (L896-898) 搬過來，3 行
        articleRepository.incrementCommentCount(articleId);
    }

    void decrementCommentCount(Long articleId) {
        // 從 ArticleServiceImpl.decrementCommentCount (L906-908) 搬過來，3 行
        articleRepository.decrementCommentCount(articleId);
    }

    void incrementLikeCount(Long articleId) {
        // L916-918 搬過來
        articleRepository.incrementLikeCount(articleId);
    }

    void decrementLikeCount(Long articleId) {
        // L926-928 搬過來
        articleRepository.decrementLikeCount(articleId);
    }

    // ─── Cross-module write 1 method ───

    void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition) {
        // 從 ArticleServiceImpl.updateSeriesAssignment (L988-994) 搬過來，7 行
        articleRepository.updateSeriesAssignment(articleId, seriesId, seriesPosition);
    }

    // ─── 5 個 private helper（從 ArticleServiceImpl 移過來）───

    private void checkWritePermission(Long operatorId, Role operatorRole, Article article) {
        // L597-604 完整搬過來，8 行
    }

    private void validateStatusTransition(ArticleStatus from, ArticleStatus to) {
        // L617-628 搬過來，12 行
    }

    private String generateSlug(String title) {
        // L636-642 搬過來，7 行
    }

    private String extractSummary(String content) {
        // L686-698 搬過來，13 行
    }

    private void syncCategories(Long articleId, List<UUID> categoryUuids) {
        // L800-809 搬過來，10 行
    }

    // 注意：convertToHtml(String) 既有只是 markdownRenderer.render 包裝（3 行），
    // 此 task 改 inline 為 markdownRenderer.render(content) 直接呼叫。
}
```

⚠ implementer 須從既有 ArticleServiceImpl method body **完整複製** body（不可摘要）。method body 內任何 `findByUuidOrThrow(...)` 呼叫改成 `entityFinder.findByUuidOrThrow(...)`；`toResponse(...)` 改成 `responseMapper.toResponse(...)`；`processArticleView(...)` 不出現在 Command（Command 不需 view tracking）。

- [ ] **Step 4: Run tests — verify GREEN**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleCommandSubServiceTest 2>&1 | tee logs/t3-green.log | grep -E "Tests run:|BUILD" | tail -5
```

Expected: 10 個 @Nested class 全綠（~100+ tests pass）。

- [ ] **Step 5: 修 ArticleServiceImpl — 11 method 改 1-line delegate**

```java
// 加 inject（既有 14 → 15）
private final ArticleCommandSubService commandSubService;

// Command 6 method 改 delegate
@Override
public EditorArticleResponse createArticle(Long authorId, CreateArticleRequest request) {
    return commandSubService.createArticle(authorId, request);
}

@Override
public EditorArticleResponse updateArticle(Long operatorId, Role operatorRole, UUID articleUuid,
                                           UpdateArticleRequest request) {
    return commandSubService.updateArticle(operatorId, operatorRole, articleUuid, request);
}

@Override
public void deleteArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
    commandSubService.deleteArticle(operatorId, operatorRole, articleUuid);
}

@Override
public ArticleResponse publishArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
    return commandSubService.publishArticle(operatorId, operatorRole, articleUuid);
}

@Override
public ArticleResponse rejectArticle(Long operatorId, Role operatorRole, UUID articleUuid, String reason) {
    return commandSubService.rejectArticle(operatorId, operatorRole, articleUuid, reason);
}

@Override
public ArticleResponse submitForReview(Long operatorId, Role operatorRole, UUID articleUuid) {
    return commandSubService.submitForReview(operatorId, operatorRole, articleUuid);
}

// Counter 4 method 改 delegate
@Override
public void incrementCommentCount(Long articleId) { commandSubService.incrementCommentCount(articleId); }

@Override
public void decrementCommentCount(Long articleId) { commandSubService.decrementCommentCount(articleId); }

@Override
public void incrementLikeCount(Long articleId) { commandSubService.incrementLikeCount(articleId); }

@Override
public void decrementLikeCount(Long articleId) { commandSubService.decrementLikeCount(articleId); }

// Cross-module write 1 改 delegate
@Override
public void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition) {
    commandSubService.updateSeriesAssignment(articleId, seriesId, seriesPosition);
}
```

⚠ 既有 11 method 的完整 body（含 215 行 Command + 12 行 Counter + 7 行 cross-write）全部從 ArticleServiceImpl 移除（搬到 CommandSubService）— ArticleServiceImpl 行數約減 235 行。

⚠ 5 個 private helper（checkWritePermission / validateStatusTransition / generateSlug / extractSummary / syncCategories）也從 ArticleServiceImpl 移除（搬到 CommandSubService）— 再減 50 行。

- [ ] **Step 6: 修 ArticleServiceTest — 移除已搬走的 11 method 對應 test**

從既有 ArticleServiceTest 移除以下 @Nested class（已搬到 ArticleCommandSubServiceTest）：
- CreateArticleTests / UpdateArticleTests / DeleteArticleTests
- PublishArticleTests / RejectArticleTests / SubmitForReviewTests
- IncrementCommentCountTests / DecrementCommentCountTests / IncrementLikeCountTests / DecrementLikeCountTests
- updateSeriesAssignment 相關 test

ArticleServiceTest 從 1981 行 → 約 1100 行（仍含 Query 7 + Cross-module read 6 + 內部 helper 等）。

- [ ] **Step 7: install + Run all article 模組 tests**

```bash
./mvnw.cmd -pl blog-module-article test 2>&1 | tee logs/t3-article-tests.log | grep -E "^\[INFO\] Tests run:" | tail -5
```

Expected:
- ArticleCommandSubServiceTest 10 nested class ~約 100+ tests pass
- ArticleServiceTest 縮減後 ~50+ tests pass
- ArticleViewSubServiceTest 6 + ArticleEntityFinderTest 2 + ArticleResponseMapperTest 7 仍綠

- [ ] **Step 8: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleCommandSubService.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleCommandSubServiceTest.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(article): 拆 ArticleCommandSubService（11 method + 5 helper）

SP-C T3 — ArticleServiceImpl 既有 11 個 write method 邏輯搬到獨立 sub-service：
- 6 Command（create/update/delete/publish/reject/submitForReview，含 215 行業務邏輯）
- 4 Counter（純 mapper.update）
- 1 Cross-module write（updateSeriesAssignment）
- 5 private helper（checkWritePermission / validateStatusTransition / generateSlug /
  extractSummary / syncCategories）

ArticleServiceImpl 對應 11 method 改 1-line delegate，行數減約 285 行。
ArticleCommandSubServiceTest 從既有 ArticleServiceTest 搬 10 個 @Nested 過來（mock 改用
CommandSubService inject + entityFinder）— 行為等價驗證仍綠。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: ArticleQuerySubService（13 method + read-permission guard）

**Files:**
- Create: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleQuerySubService.java`
- Create: `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleQuerySubServiceTest.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java`（13 method 改 delegate；getArticleByUuid / getArticleBySlug 含協調邏輯）
- Modify: `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleServiceTest.java`（13 method 對應 test 搬走）

- [ ] **Step 1: 建 ArticleQuerySubServiceTest（從既有 ArticleServiceTest 搬 13 method 對應 test — TDD red）**

```bash
Write blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleQuerySubServiceTest.java
```

⚠ 從既有 ArticleServiceTest 搬以下 @Nested class（已知 line refs）：
- L1046 GetArticleByUuidTests（注意：此 test 之前測「getArticleByUuid 含 view tracking」— SP-C T2 後 view tracking 拆出，T4 階段測試的 getArticleByUuid 只測 read-permission + response build，**不測 view event**；view event 測試已在 ArticleViewSubServiceTest）
- L1068 GetArticleBySlugTests
- L1164 GetArticleForEditTests
- L1254 GetPublishedArticlesTests
- L1295 GetPublishedArticlesByCategorySlugTests
- L1335 GetMyArticlesTests
- L1377 GetPendingArticlesTests
- L1765 FindIdByUuidTests
- L1897 FindByUuidTests
- L1930 OtherCrossModuleTests（findById / findByIds / findBySeriesIdOrderByPosition / getArticleSummariesByIds 部分）

新 test class setUp：

```java
package dowob.xyz.blog.module.article.service;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArticleQuerySubServiceTest {

    @Mock private ArticleRepository articleRepository;
    @Mock private ArticleMapper articleMapper;
    @Mock private ArticleEntityFinder entityFinder;
    @Mock private ArticleResponseMapper responseMapper;

    private ArticleQuerySubService querySubService;

    @BeforeEach
    void setUp() {
        querySubService = new ArticleQuerySubService(
            articleRepository, articleMapper, entityFinder, responseMapper
        );
    }

    // 從 ArticleServiceTest 搬 10 個 @Nested 過來（注意 GetArticleByUuid / Slug 移除 view event 相關 verify）
    @Nested class GetArticleByUuidTests { /* ... */ }
    @Nested class GetArticleBySlugTests { /* ... */ }
    @Nested class GetArticleForEditTests { /* ... */ }
    // ...
}
```

- [ ] **Step 2: Run tests — verify RED**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleQuerySubServiceTest 2>&1 | tee logs/t4-red.log | grep -E "Tests run:|BUILD|ERROR" | tail -5
```

Expected: 編譯失敗 — `ArticleQuerySubService` class 還不存在。

- [ ] **Step 3: 建 ArticleQuerySubService（package-private，13 method — GREEN）**

```bash
Write blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleQuerySubService.java
```

⚠ 內容 = 從 ArticleServiceImpl L322-466 + L548-558（Query 7 method）+ L937-1017（Cross-module read 6 method）完整搬過來。

```java
package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Article query sub-service — 13 method (read 業務邏輯)。
 *
 * <p><strong>Package-private</strong>：僅 article 模組 service package 內可訪問。</p>
 *
 * <p>不負責 view tracking — 由 ArticleServiceImpl 在 query 後協調呼叫 ArticleViewSubService.recordView。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
class ArticleQuerySubService {

    private final ArticleRepository articleRepository;
    private final ArticleMapper articleMapper;
    private final ArticleEntityFinder entityFinder;
    private final ArticleResponseMapper responseMapper;

    // ─── Query 7 method ───

    /**
     * 取單篇 article（給 controller getArticleByUuid 用）。
     *
     * <p>含 read-permission check：非公開狀態（draft / pending / archived / rejected）僅 author / admin 可看，
     * 其他 viewer throw ARTICLE_NOT_FOUND（避免洩漏 article 存在事實）。</p>
     */
    ArticleResponse getArticleByUuid(UUID articleUuid, Long viewerId, Role viewerRole) {
        Article article = entityFinder.findByUuidOrThrow(articleUuid);
        checkReadPermission(article, viewerId, viewerRole);
        return responseMapper.toResponse(article, /* tags / categories / liked / bookmarked / lastRead / seriesNav from enrich */);
        // ⚠ tags / categories enrich 邏輯從既有 ArticleServiceImpl.toResponse 內部 call 處搬過來
    }

    /**
     * 取單篇 article by slug。同 getArticleByUuid 但用 slug 查找。
     */
    ArticleResponse getArticleBySlug(String slug, Long viewerId, Role viewerRole) {
        Article article = articleRepository.findBySlug(slug)
            .orElseThrow(() -> new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));
        checkReadPermission(article, viewerId, viewerRole);
        return responseMapper.toResponse(article, /* enrich */);
    }

    /**
     * 取編輯器 response（給 controller getArticleForEdit 用，僅 author / admin）。
     */
    EditorArticleResponse getArticleForEdit(UUID articleUuid, Long requesterId) {
        // 從 ArticleServiceImpl.getArticleForEdit (L341-347) 搬過來，7 行
    }

    /**
     * 已發布文章分頁列表（首頁用）。
     */
    PageResult<ArticleSummaryResponse> getPublishedArticles(int page, int size) {
        // 從 ArticleServiceImpl.getPublishedArticles (L405-415) 搬過來，11 行
    }

    /**
     * 依 category slug 過濾的已發布文章分頁列表。
     */
    PageResult<ArticleSummaryResponse> getPublishedArticlesByCategorySlug(String categorySlug, int page, int size) {
        // 從 ArticleServiceImpl.getPublishedArticlesByCategorySlug (L426-437) 搬過來，12 行
    }

    /**
     * 我的文章分頁列表（作者後台用，含 status filter）。
     */
    PageResult<ArticleSummaryResponse> getMyArticles(Long authorId, int page, int size, ArticleStatus status) {
        // 從 ArticleServiceImpl.getMyArticles (L449-466) 搬過來，18 行
    }

    /**
     * 待審核文章分頁列表（admin 後台用）。
     */
    PageResult<ArticleSummaryResponse> getPendingArticles(int page, int size) {
        // 從 ArticleServiceImpl.getPendingArticles (L548-558) 搬過來，11 行
    }

    // ─── Cross-module read 6 method ───

    Long findIdByUuid(UUID articleUuid) {
        // 從 ArticleServiceImpl.findIdByUuid (L937-939) 搬過來，3 行
    }

    Optional<Article> findById(Long articleId) {
        // 從 ArticleServiceImpl.findById (L1015-1017) 搬過來，3 行
    }

    Optional<Article> findByUuid(UUID articleUuid) {
        // 從 ArticleServiceImpl.findByUuid (L975-977) 搬過來，3 行
    }

    List<Article> findByIds(List<Long> articleIds) {
        // 從 ArticleServiceImpl.findByIds (L961-966) 搬過來，6 行
    }

    List<Article> findBySeriesIdOrderByPosition(Long seriesId) {
        // 從 ArticleServiceImpl.findBySeriesIdOrderByPosition (L1004-1006) 搬過來，3 行
    }

    List<ArticleSummaryResponse> getArticleSummariesByIds(List<Long> articleIds) {
        // 從 ArticleServiceImpl.getArticleSummariesByIds (L948-958) 搬過來，11 行
    }

    // ─── Private helper ───

    private void checkReadPermission(Article article, Long viewerId, Role viewerRole) {
        boolean isAdmin = Role.ADMIN == viewerRole;
        boolean isAuthor = Objects.equals(article.getAuthorId(), viewerId);
        if (!article.getStatus().isPubliclyVisible() && !isAdmin && !isAuthor) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
        }
    }
}
```

⚠ implementer 須從既有 ArticleServiceImpl method body **完整複製**，所有 `findByUuidOrThrow(...)` 呼叫改 `entityFinder.findByUuidOrThrow(...)`，所有 `toResponse / toSummaryResponse / toEditorResponse` 改 `responseMapper.toXxx(...)`。

⚠ checkReadPermission helper 從既有 `processArticleView` L378-381 抽出（見 audit 完整 method body）— `getArticleByUuid` 與 `getArticleBySlug` 共用 read-permission guard。

- [ ] **Step 4: Run tests — verify GREEN**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleQuerySubServiceTest 2>&1 | tee logs/t4-green.log | grep -E "Tests run:|BUILD" | tail -5
```

Expected: 10 個 @Nested class 全綠（~50+ tests pass）。

- [ ] **Step 5: 修 ArticleServiceImpl — 13 method 改 delegate；getArticleByUuid / Slug 含協調邏輯**

```java
// 加 inject（既有 15 → 16）
private final ArticleQuerySubService querySubService;

// Query 5 個純 delegate
@Override
public EditorArticleResponse getArticleForEdit(UUID articleUuid, Long requesterId) {
    return querySubService.getArticleForEdit(articleUuid, requesterId);
}

@Override
public PageResult<ArticleSummaryResponse> getPublishedArticles(int page, int size) {
    return querySubService.getPublishedArticles(page, size);
}

// ... getPublishedArticlesByCategorySlug / getMyArticles / getPendingArticles 同樣純 delegate

// 2 個含協調邏輯（query → view 順序）
@Override
public ArticleResponse getArticleByUuid(UUID articleUuid, Long viewerId, Role viewerRole, String clientIp) {
    ArticleResponse resp = querySubService.getArticleByUuid(articleUuid, viewerId, viewerRole);
    viewSubService.recordView(articleUuid, resp.getStatus(), clientIp);
    return resp;
}

@Override
public ArticleResponse getArticleBySlug(String slug, Long viewerId, Role viewerRole, String clientIp) {
    ArticleResponse resp = querySubService.getArticleBySlug(slug, viewerId, viewerRole);
    viewSubService.recordView(resp.getUuid(), resp.getStatus(), clientIp);
    return resp;
}

// Cross-module read 6 個純 delegate
@Override
public Long findIdByUuid(UUID articleUuid) { return querySubService.findIdByUuid(articleUuid); }
// ... 其他 5 個同樣 delegate
```

- [ ] **Step 6: 修 ArticleServiceTest — 移除已搬走的 13 method 對應 test**

從既有 ArticleServiceTest 移除 10 個 @Nested class（已搬到 QuerySubServiceTest）。剩餘 ~200 行：facade test 部分（驗委派 + 協調順序）。

- [ ] **Step 7: install + Run all article 模組 tests**

```bash
./mvnw.cmd -pl blog-module-article test 2>&1 | tee logs/t4-article-tests.log | grep -E "^\[INFO\] Tests run:" | tail -5
```

Expected: 全綠（既有 + 新 sub-service test 共 ~150-200+ tests）。

- [ ] **Step 8: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleQuerySubService.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleQuerySubServiceTest.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(article): 拆 ArticleQuerySubService（13 method + read-permission guard）

SP-C T4 — ArticleServiceImpl 既有 13 個 read method 邏輯搬到獨立 sub-service：
- 7 Query（含 getArticleByUuid / getArticleBySlug 的 read-permission guard，
  從既有 processArticleView L378-381 抽出 checkReadPermission helper）
- 6 Cross-module read（findIdByUuid / findById / findByUuid / findByIds /
  findBySeriesIdOrderByPosition / getArticleSummariesByIds）

ArticleServiceImpl getArticleByUuid / getArticleBySlug 改為「協調 query → view」pattern：
ArticleResponse resp = querySubService.getXxx(...);
viewSubService.recordView(uuid, resp.getStatus(), clientIp);
return resp;

對齊 SP-D applyRestoreContent 的 facade 協調 atomic flow pattern。

ArticleQuerySubServiceTest 從既有 ArticleServiceTest 搬 10 個 @Nested class 過來。
ArticleServiceTest 縮減為 ~200 行（純 facade test）。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: ArticleServiceImpl 變薄 facade + ArticleServiceTest 重整為純 facade test

**Files:**
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java`（11 inject → 3，行數 1020 → ~200）
- Modify: `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleServiceTest.java`（從 1981 行縮為 ~200 行純 facade test）

T3/T4 已將大部分 test 搬走，本 task 同時完成 ArticleServiceImpl 薄 facade 重寫 + ArticleServiceTest 重整（兩者必須同步完成，避免中間態 setUp inject 數不一致導致編譯不過）。

- [ ] **Step 1: ArticleServiceTest 完全重寫為 facade test（~200 行）**

```bash
Write blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleServiceTest.java
```

```java
package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticleServiceImpl facade test — 重點驗證 sub-service 委派 + getArticleByUuid/Slug 協調順序。
 *
 * <p>SP-C 後 ArticleServiceImpl 變薄 facade：</p>
 * <ul>
 *   <li>Command/Counter/CrossWrite 11 method：純 1-line delegate to commandSubService</li>
 *   <li>Query/CrossRead 11 method：純 delegate to querySubService（5 個 query 純 delegate + 6 cross-read）</li>
 *   <li>getArticleByUuid / getArticleBySlug 2 個含協調邏輯：query → view 順序</li>
 * </ul>
 *
 * <p>Sub-service 業務邏輯細節由各 sub-service test 驗證。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArticleServiceTest {

    @Mock private ArticleCommandSubService commandSubService;
    @Mock private ArticleQuerySubService querySubService;
    @Mock private ArticleViewSubService viewSubService;

    private ArticleServiceImpl articleService;

    @BeforeEach
    void setUp() {
        articleService = new ArticleServiceImpl(commandSubService, querySubService, viewSubService);
    }

    @Nested
    @DisplayName("純 delegate — sample 6 method 驗委派")
    class PureDelegate {

        @Test
        @DisplayName("createArticle → commandSubService.createArticle")
        void createArticle_delegates() {
            articleService.createArticle(1L, /* CreateArticleRequest */ null);
            verify(commandSubService).createArticle(eq(1L), any());
        }

        @Test
        @DisplayName("getArticleForEdit → querySubService.getArticleForEdit")
        void getArticleForEdit_delegates() {
            UUID uuid = UUID.randomUUID();
            articleService.getArticleForEdit(uuid, 1L);
            verify(querySubService).getArticleForEdit(uuid, 1L);
        }

        @Test
        @DisplayName("incrementCommentCount → commandSubService.incrementCommentCount")
        void incrementCommentCount_delegates() {
            articleService.incrementCommentCount(100L);
            verify(commandSubService).incrementCommentCount(100L);
        }

        @Test
        @DisplayName("findIdByUuid → querySubService.findIdByUuid")
        void findIdByUuid_delegates() {
            UUID uuid = UUID.randomUUID();
            articleService.findIdByUuid(uuid);
            verify(querySubService).findIdByUuid(uuid);
        }

        @Test
        @DisplayName("updateSeriesAssignment → commandSubService.updateSeriesAssignment")
        void updateSeriesAssignment_delegates() {
            articleService.updateSeriesAssignment(100L, 200L, 5);
            verify(commandSubService).updateSeriesAssignment(100L, 200L, 5);
        }

        @Test
        @DisplayName("getPublishedArticles → querySubService.getPublishedArticles")
        void getPublishedArticles_delegates() {
            articleService.getPublishedArticles(1, 10);
            verify(querySubService).getPublishedArticles(1, 10);
        }
    }

    @Nested
    @DisplayName("getArticleByUuid 協調 query → view 順序")
    class GetArticleByUuidCoordination {

        @Test
        @DisplayName("query 完成後 call viewSubService.recordView，傳 status + clientIp")
        void getArticleByUuid_coordinatesQueryThenView() {
            UUID uuid = UUID.randomUUID();
            ArticleResponse resp = mock(ArticleResponse.class);
            when(resp.getStatus()).thenReturn(ArticleStatus.PUBLISHED);
            when(querySubService.getArticleByUuid(uuid, 1L, Role.USER)).thenReturn(resp);

            ArticleResponse result = articleService.getArticleByUuid(uuid, 1L, Role.USER, "1.2.3.4");

            assertThat(result).isSameAs(resp);

            // InOrder verify：query 必須先於 view
            InOrder inOrder = inOrder(querySubService, viewSubService);
            inOrder.verify(querySubService).getArticleByUuid(uuid, 1L, Role.USER);
            inOrder.verify(viewSubService).recordView(uuid, ArticleStatus.PUBLISHED, "1.2.3.4");
        }

        @Test
        @DisplayName("query 拋 ARTICLE_NOT_FOUND → 不 call viewSubService")
        void getArticleByUuid_queryThrows_doesNotRecordView() {
            UUID uuid = UUID.randomUUID();
            when(querySubService.getArticleByUuid(any(), any(), any()))
                .thenThrow(new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));

            assertThatThrownBy(() -> articleService.getArticleByUuid(uuid, 1L, Role.USER, "1.2.3.4"))
                .isInstanceOf(BusinessException.class);

            verifyNoInteractions(viewSubService);
        }
    }

    @Nested
    @DisplayName("getArticleBySlug 協調 query → view 順序")
    class GetArticleBySlugCoordination {

        @Test
        @DisplayName("query 完成後 call viewSubService.recordView 傳 resp.getUuid() + status + clientIp")
        void getArticleBySlug_coordinatesQueryThenView() {
            UUID uuid = UUID.randomUUID();
            ArticleResponse resp = mock(ArticleResponse.class);
            when(resp.getUuid()).thenReturn(uuid);
            when(resp.getStatus()).thenReturn(ArticleStatus.PUBLISHED);
            when(querySubService.getArticleBySlug("slug", 1L, Role.USER)).thenReturn(resp);

            ArticleResponse result = articleService.getArticleBySlug("slug", 1L, Role.USER, "1.2.3.4");

            assertThat(result).isSameAs(resp);

            InOrder inOrder = inOrder(querySubService, viewSubService);
            inOrder.verify(querySubService).getArticleBySlug("slug", 1L, Role.USER);
            inOrder.verify(viewSubService).recordView(uuid, ArticleStatus.PUBLISHED, "1.2.3.4");
        }
    }
}
```

⚠ setUp 假設 3 inject（commandSubService / querySubService / viewSubService）— 必須與下一 step 的 ArticleServiceImpl 重寫同步完成。

- [ ] **Step 2: ArticleServiceImpl 完全重寫為薄 facade**

```bash
Write blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java
```

```java
package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ArticleService 薄 facade impl — 委派給 3 個 package-private sub-service。
 *
 * <p>SP-C 拆分後此 class 從 1020 行 god class 變為 ~200 行薄 facade：</p>
 * <ul>
 *   <li>Command 6 + Counter 4 + CrossWrite 1 = 11 method 委派 commandSubService（純 1-line delegate）</li>
 *   <li>Query 5 (純 delegate) + CrossRead 6 = 11 method 委派 querySubService（純 1-line delegate）</li>
 *   <li>getArticleByUuid / getArticleBySlug 2 method 含協調邏輯：query → view 順序</li>
 * </ul>
 *
 * @author Yuan
 * @version 2.0 (SP-C god class split)
 */
@Service
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {

    private final ArticleCommandSubService commandSubService;
    private final ArticleQuerySubService querySubService;
    private final ArticleViewSubService viewSubService;

    // ─── Command 6 method（純 1-line delegate）───

    @Override
    public EditorArticleResponse createArticle(Long authorId, CreateArticleRequest request) {
        return commandSubService.createArticle(authorId, request);
    }

    @Override
    public EditorArticleResponse updateArticle(Long operatorId, Role operatorRole, UUID articleUuid,
                                                UpdateArticleRequest request) {
        return commandSubService.updateArticle(operatorId, operatorRole, articleUuid, request);
    }

    @Override
    public void deleteArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
        commandSubService.deleteArticle(operatorId, operatorRole, articleUuid);
    }

    @Override
    public ArticleResponse publishArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
        return commandSubService.publishArticle(operatorId, operatorRole, articleUuid);
    }

    @Override
    public ArticleResponse rejectArticle(Long operatorId, Role operatorRole, UUID articleUuid, String reason) {
        return commandSubService.rejectArticle(operatorId, operatorRole, articleUuid, reason);
    }

    @Override
    public ArticleResponse submitForReview(Long operatorId, Role operatorRole, UUID articleUuid) {
        return commandSubService.submitForReview(operatorId, operatorRole, articleUuid);
    }

    // ─── Query 7 method ───

    @Override
    public ArticleResponse getArticleByUuid(UUID articleUuid, Long viewerId, Role viewerRole, String clientIp) {
        ArticleResponse resp = querySubService.getArticleByUuid(articleUuid, viewerId, viewerRole);
        viewSubService.recordView(articleUuid, resp.getStatus(), clientIp);
        return resp;
    }

    @Override
    public ArticleResponse getArticleBySlug(String slug, Long viewerId, Role viewerRole, String clientIp) {
        ArticleResponse resp = querySubService.getArticleBySlug(slug, viewerId, viewerRole);
        viewSubService.recordView(resp.getUuid(), resp.getStatus(), clientIp);
        return resp;
    }

    @Override
    public EditorArticleResponse getArticleForEdit(UUID articleUuid, Long requesterId) {
        return querySubService.getArticleForEdit(articleUuid, requesterId);
    }

    @Override
    public PageResult<ArticleSummaryResponse> getPublishedArticles(int page, int size) {
        return querySubService.getPublishedArticles(page, size);
    }

    @Override
    public PageResult<ArticleSummaryResponse> getPublishedArticlesByCategorySlug(String categorySlug, int page, int size) {
        return querySubService.getPublishedArticlesByCategorySlug(categorySlug, page, size);
    }

    @Override
    public PageResult<ArticleSummaryResponse> getMyArticles(Long authorId, int page, int size, ArticleStatus status) {
        return querySubService.getMyArticles(authorId, page, size, status);
    }

    @Override
    public PageResult<ArticleSummaryResponse> getPendingArticles(int page, int size) {
        return querySubService.getPendingArticles(page, size);
    }

    // ─── Counter 4 method（純 1-line delegate）───

    @Override
    public void incrementCommentCount(Long articleId) { commandSubService.incrementCommentCount(articleId); }

    @Override
    public void decrementCommentCount(Long articleId) { commandSubService.decrementCommentCount(articleId); }

    @Override
    public void incrementLikeCount(Long articleId) { commandSubService.incrementLikeCount(articleId); }

    @Override
    public void decrementLikeCount(Long articleId) { commandSubService.decrementLikeCount(articleId); }

    // ─── Cross-module 7 method（6 read + 1 write，純 1-line delegate）───

    @Override
    public Long findIdByUuid(UUID articleUuid) { return querySubService.findIdByUuid(articleUuid); }

    @Override
    public Optional<Article> findById(Long articleId) { return querySubService.findById(articleId); }

    @Override
    public Optional<Article> findByUuid(UUID articleUuid) { return querySubService.findByUuid(articleUuid); }

    @Override
    public List<Article> findByIds(List<Long> articleIds) { return querySubService.findByIds(articleIds); }

    @Override
    public List<Article> findBySeriesIdOrderByPosition(Long seriesId) {
        return querySubService.findBySeriesIdOrderByPosition(seriesId);
    }

    @Override
    public List<ArticleSummaryResponse> getArticleSummariesByIds(List<Long> articleIds) {
        return querySubService.getArticleSummariesByIds(articleIds);
    }

    @Override
    public void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition) {
        commandSubService.updateSeriesAssignment(articleId, seriesId, seriesPosition);
    }
}
```

⚠ 此 ArticleServiceImpl 約 200 行（含 imports + Javadoc + 24 method delegate）— 對齊 spec Done definition #1。

⚠ 既有所有 11 inject 全部移除（已搬到 sub-service），改為 3 個 sub-service inject。

- [ ] **Step 3: install + Run all article 模組 tests**

```bash
./mvnw.cmd -pl blog-module-article test 2>&1 | tee logs/t5-article-tests.log | grep -E "^\[INFO\] Tests run:" | tail -5
```

Expected: 所有 test 全綠：
- ArticleEntityFinderTest 2
- ArticleResponseMapperTest ~7
- ArticleViewSubServiceTest 6
- ArticleCommandSubServiceTest ~100+
- ArticleQuerySubServiceTest ~50+
- ArticleServiceTest ~9（純 facade test）
- ArticleControllerIT 13（IT 不變仍綠 — 對外 API 行為等價）

- [ ] **Step 4: Run cross-module IT 確認跨模組 caller 行為等價**

```bash
./mvnw.cmd -pl blog-module-comment,blog-module-reading,blog-module-series,blog-module-version test 2>&1 | tee logs/t5-cross-tests.log | grep -E "^\[INFO\] Tests run:" | tail -5
```

Expected: 4 個跨模組模組 tests 全綠（comment 67 + reading 68 + series 36 + version 63 = 234 tests 全綠）。

- [ ] **Step 5: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(article): ArticleServiceImpl 變薄 facade + ArticleServiceTest 重整為純 facade test

SP-C T5 — god class 消失：
- ArticleServiceImpl 從 1020 行 → ~200 行
- inject 從 11 個 → 3 個（commandSubService / querySubService / viewSubService）
- 22/24 method 純 1-line delegate；getArticleByUuid / getArticleBySlug 含協調 query → view 順序
- 17 private helper 全部搬走（9 → ResponseMapper/EntityFinder，5 → CommandSubService，1 → ViewSubService，
  2 inline / 移除）

ArticleServiceTest 從 1981 行 → ~200 行（純 facade test）：
- 6 cases 驗 sample method 委派
- 2 cases 驗 getArticleByUuid coordination order（用 Mockito InOrder verify query → view）
- 1 case 驗 query throw 時 view 不執行

業務邏輯細節已搬到對應 sub-service test：
- ArticleCommandSubServiceTest（11 method × 多 cases）
- ArticleQuerySubServiceTest（13 method × 多 cases）
- ArticleViewSubServiceTest（recordView 6 cases）
- ArticleResponseMapperTest（9 mapper × cases）
- ArticleEntityFinderTest（findByUuidOrThrow 2 cases）

ArticleControllerIT 952 行 + 4 個跨模組模組 IT（comment / reading / series / version）行為等價仍綠。

Done definition：
- ArticleServiceImpl 行數 ~200（target ~150-200 ✅）
- inject 數 3（target 3 ✅）
- 5 個新 class 全 package-private（不對外暴露 ✅）
- ArticleService interface 24 method 不變（caller 零改動 ✅）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: 全模組 grep verify + sanity test

**Files:** （無修改，純驗證）

- [ ] **Step 1: 全 codebase grep verify SP-C done definition**

```bash
# 1. ArticleServiceImpl 行數驗 ~150-200
wc -l blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java
```

Expected: 約 200 行（spec target 150-200）。

```bash
# 2. ArticleServiceImpl inject 數量
grep -c "private final" blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java
```

Expected: **3**（只 inject 3 sub-service）。

```bash
# 3. 5 新 class 全 package-private（無 public class declaration）
grep -E "^public class (ArticleEntityFinder|ArticleResponseMapper|ArticleViewSubService|ArticleCommandSubService|ArticleQuerySubService)" blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/*.java
```

Expected: **0 行命中**（5 個新 class 全部用 `class Xxx` 不帶 `public`）。

```bash
# 4. ArticleService interface 24 method 不變
git diff develop -- blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleService.java
```

Expected: empty（無 diff）。

```bash
# 5. ArticleControllerIT 952 行不變
git diff develop -- blog-module-article/src/test/java/dowob/xyz/blog/module/article/controller/ArticleControllerIT.java
```

Expected: empty 或僅 trivial 變化。

如有 grep 顯示問題，回頭找對應 task 修。

- [ ] **Step 2: 跑 SP-C affected 5 個模組 tests**

```bash
./mvnw.cmd -pl blog-module-article,blog-module-comment,blog-module-reading,blog-module-series,blog-module-version test 2>&1 | tee logs/t6-all.log | grep -E "^\[INFO\] Tests run:" | tail -10
```

Expected: 全綠：
- article: ~400+ tests（含 5 個新 sub-service / helper test）
- comment: 67
- reading: 68
- series: 36
- version: 63

合計 ~600+ tests / 0 failures / 0 errors。

- [ ] **Step 3: BUILD SUCCESS verify**

```bash
tail -5 logs/t6-all.log | grep "BUILD"
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: （可選）保留 verification log 給 PR description**

```bash
echo "=== SP-C Done verification ===" > logs/sp-c-done.log
echo "ArticleServiceImpl 行數（target ~150-200）:" >> logs/sp-c-done.log
wc -l blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java >> logs/sp-c-done.log
echo "" >> logs/sp-c-done.log
echo "ArticleServiceImpl inject 數（target 3）:" >> logs/sp-c-done.log
grep -c "private final" blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java >> logs/sp-c-done.log
echo "" >> logs/sp-c-done.log
echo "5 個新 class public declaration（target 0）:" >> logs/sp-c-done.log
grep -E "^public class (ArticleEntityFinder|ArticleResponseMapper|ArticleViewSubService|ArticleCommandSubService|ArticleQuerySubService)" blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/*.java >> logs/sp-c-done.log
```

⚠ logs/ 在 .gitignore 內，不會被 commit。

⚠ 本 task 通常無 commit — 純驗證。如 grep 找到殘留，回頭 patch + 補 commit。

---

## Self-Review Checklist

實作完成後請勾選：

- [ ] T1: ArticleEntityFinder + ArticleResponseMapper helper class 抽出，9 helper 移走
- [ ] T2: ArticleViewSubService.recordView(UUID, ArticleStatus, String) + 6 unit tests（4 visibility + 1 repeat-visit dedup + 1 published-publish）
- [ ] T3: ArticleCommandSubService 11 method（含 215 行 Command + Counter 4 + cross-write 1 + 5 helper）+ test 從 ArticleServiceTest 搬 10 個 @Nested
- [ ] T4: ArticleQuerySubService 13 method（含 read-permission guard）+ test 從 ArticleServiceTest 搬 10 個 @Nested
- [ ] T5: ArticleServiceImpl 變薄 facade（1020 → ~200 行，11 → 3 inject）+ ArticleServiceTest 重整為純 facade test
- [ ] T6: grep verify 全部通過 + 5 模組 tests 全綠（~600+ tests）

- [ ] 全 codebase `grep public class (ArticleEntityFinder|ArticleResponseMapper|...)` 為 0
- [ ] ArticleService interface 24 method 不變（git diff develop empty）
- [ ] ArticleControllerIT 952 行不變仍綠
- [ ] CrossModuleArticleIT / CrossModuleCommentIT / CrossModuleSeriesIT / CrossModuleVersionIT / CrossModuleReadingIT 全綠

---

## 結語

SP-C 完成後：
- ArticleServiceImpl god class 消失（1020 行 → ~200 行薄 facade）
- 5 個職責清晰的新 class（package-private 不對外暴露）
- ArticleService 對外 contract 24 method 完全不變（caller 零改動）
- 業務邏輯分散到 sub-service，各自獨立測試

預期 commit count：5（T1, T2, T3, T4, T5 合併 — T6 純驗證）。比 SP-A/B/D 略少（T5 同時完成 ArticleServiceImpl 薄 facade 重寫 + ArticleServiceTest 重整，避免中間態編譯不過）。

SP-C 完成代表 architecture decoupling roadmap 4 個 sub-projects 全部結束。
