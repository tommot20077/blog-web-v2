# SP-D: Events and Helpers Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 version 模組對 article 模組的跨模組 inject（`ArticleRepository` + `ArticleEventPublisher`）全部改走 `ArticleFacade`；抽取 `SecurityUtils.isAdmin` 消除 controller 重複；補 `HighlightService` null-check。

**Architecture:** 在 SP-B 已建立的 `ArticleFacade` 基礎上擴充 1 個 read method (`findContentById`) + 1 個 atomic write method (`applyRestoreContent`)，後者內部接管 mutate / save / syncArticleTags / publish events 完整流程，避免 caller 看到 Article entity。

**Tech Stack:** Spring Boot, Spring Data JDBC, JUnit 5, Mockito (`InOrder` for invocation order verification), Lombok `@RequiredArgsConstructor`.

**Spec:** `docs/superpowers/specs/2026-05-03-sp-d-events-helpers-cleanup-design.md`

**Worktree:** `D:\end\workspace\java\blog-web-v2\.worktrees\refactor-sp-d-events-helpers-cleanup`
**Branch:** `refactor/sp-d-events-helpers-cleanup` (from `develop@e2df4c1`)

---

## File Structure

### 新建檔案 (3)

| 檔案 | 職責 |
|---|---|
| `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/dto/ArticleContentData.java` | Read DTO — 給 stash + content length 比較用（9 欄位含 content） |
| `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/dto/ArticleRestoreData.java` | Write DTO — 給 atomic restore 用（7 欄位 + tags） |
| `blog-common/src/test/java/dowob/xyz/blog/common/util/SecurityUtilsTest.java` 可能擴充（既有；T5 新增 isAdmin tests） | — |

### 修改檔案

| 檔案 | 改動 |
|---|---|
| `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/ArticleFacade.java` | T1：加 2 method 宣告 + Javadoc 區塊 |
| `blog-module-article/src/main/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImpl.java` | T2：加 inject (`ArticleRepository`, `TagFacade`, `ArticleEventPublisher`) + 2 method 實作 |
| `blog-module-article/src/test/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImplTest.java` | T2：擴 setUp + 加 @Nested unit tests |
| `blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/AutoSnapshotPolicy.java` | T3：inject swap (Repository → Facade) + content length 改用 record accessor |
| `blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/AutoSnapshotPolicyTest.java` | T3：mock 改 ArticleFacade + stub 改 ArticleContentData |
| `blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/VersioningService.java` | T4：inject swap + 8 處 call + atomic restore + `snapshotFromArticle` rename + restore return type 改 void |
| `blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/VersioningServiceTest.java` | T4：mock 改 + stub 改 + verify atomic 取代 publish events verify |
| `blog-module-version/src/main/java/dowob/xyz/blog/module/version/controller/VersionController.java` | T4 + T5：T4 對齊 restore void；T5 移除 isAdmin helper |
| `blog-module-series/src/main/java/dowob/xyz/blog/module/series/controller/SeriesController.java` | T5：移除 isAdmin helper + 改用 SecurityUtils |
| `blog-common/src/main/java/dowob/xyz/blog/common/util/SecurityUtils.java` | T5：加 isAdmin 2 個 overload |
| `blog-common/src/test/java/dowob/xyz/blog/common/util/SecurityUtilsTest.java` | T5：擴充 isAdmin tests |
| `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/HighlightService.java` | T6：補 null-check + import ArticleErrorCode |
| `blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/service/HighlightServiceTest.java` | T6：加 2 個 null-check test |

---

## Task 1: ArticleFacade interface 加 2 method 宣告 + 2 個 DTO

**Files:**
- Create: `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/dto/ArticleContentData.java`
- Create: `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/dto/ArticleRestoreData.java`
- Modify: `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/ArticleFacade.java`（加 2 個 method 宣告 + Javadoc）

- [ ] **Step 1: 建 ArticleContentData record**

```bash
Write blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/dto/ArticleContentData.java
```

```java
package dowob.xyz.blog.infrastructure.facade.dto;

import java.util.UUID;

/**
 * 跨模組 article content 完整內容 DTO。
 *
 * <p>比 ArticleData 多含 title / slug / content / summary / coverImageUrl 5 個欄位，
 * 適用 VersioningService.snapshotFromArticle / AutoSnapshotPolicy.shouldSnapshot 等
 * 需要「完整 article 內容快照」的場景。</p>
 *
 * <p>由 ArticleFacadeImpl 從 Article entity 轉換。</p>
 *
 * @param id              文章資料庫主鍵
 * @param uuid            文章公開 UUID
 * @param authorId        作者資料庫主鍵
 * @param title           文章標題
 * @param slug            URL slug
 * @param content         markdown 原文
 * @param summary         摘要
 * @param coverImageUrl   封面圖 URL（nullable）
 * @param status          文章狀態（PUBLISHED / DRAFT / PENDING_REVIEW / etc.）— String 對齊 ArticleData 避免 cross-module enum import
 *
 * @author Yuan
 * @version 1.0
 */
public record ArticleContentData(
    Long id,
    UUID uuid,
    Long authorId,
    String title,
    String slug,
    String content,
    String summary,
    String coverImageUrl,
    String status
) {}
```

- [ ] **Step 2: 建 ArticleRestoreData record**

```bash
Write blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/dto/ArticleRestoreData.java
```

```java
package dowob.xyz.blog.infrastructure.facade.dto;

import java.util.List;
import java.util.UUID;

/**
 * 跨模組 article restore mutation DTO（給 VersioningService.restore 用）。
 *
 * <p>包含 caller (VersioningService) 從 ArticleVersion 取出 + markdown 渲染後
 * 要寫回 article 的全部欄位。tags 一併傳，由 ArticleFacade.applyRestoreContent
 * 內部呼叫 syncArticleTags 處理（必須在 publish events 之前）。</p>
 *
 * @param title          文章標題
 * @param slug           URL slug
 * @param content        markdown 原文
 * @param summary        摘要
 * @param coverImageUrl  封面圖 URL（nullable）
 * @param status         文章狀態（對齊 ArticleStatus.name() — 例：PUBLISHED / DRAFT；nullable 表示不更新狀態）
 * @param contentHtml    markdownRenderer.render(content) 由 caller 算好（避免 facade 跨模組依賴 markdown renderer）
 * @param tags           標籤 UUID 列表（null 視為空清單）
 *
 * @author Yuan
 * @version 1.0
 */
public record ArticleRestoreData(
    String title,
    String slug,
    String content,
    String summary,
    String coverImageUrl,
    String status,
    String contentHtml,
    List<UUID> tags
) {}
```

- [ ] **Step 3: Read ArticleFacade interface 既有結構**

```bash
Read blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/ArticleFacade.java
```

確認既有 imports + method 區塊配置（SP-B 加的 method 區塊用 `// ─── SP-B 新增 X ───` 註記分隔）。

- [ ] **Step 4: 更新 ArticleFacade interface — imports + 加 2 method 宣告**

於 `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/ArticleFacade.java`：

**imports** 區塊加：
```java
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreData;
```

**class Javadoc** 的「方法分類」清單加一筆（在 SP-B simple write 區塊之後）：
```
*   <li><b>SP-D 新增 read</b>（findContentById）：完整 content 內容 DTO，給 version 模組 snapshot / restore stash 流程用。</li>
*   <li><b>SP-D 新增 atomic write</b>（applyRestoreContent）：version 模組 restore 用，內部接管 mutate / save / syncArticleTags / publish events 順序。</li>
```

**method 宣告區** 在最後加：

```java
    // ─── SP-D 新增 1 read ───

    /**
     * DB id → ArticleContentData（給 VersioningService stash 流程 + AutoSnapshotPolicy 用）。
     *
     * <p>比 findById（return ArticleData）多含 title / slug / content / summary / coverImageUrl 等欄位。</p>
     *
     * @param articleId 文章資料庫主鍵
     * @return ArticleContentData Optional；查無時 empty
     */
    Optional<ArticleContentData> findContentById(Long articleId);

    // ─── SP-D 新增 1 atomic write ───

    /**
     * 還原 article 內容到指定版本（atomic）。
     *
     * <p>內部完整流程：</p>
     * <ol>
     *   <li>撈 Article entity（不存在 throw ARTICLE_NOT_FOUND）</li>
     *   <li>mutate 7 個欄位（title / slug / content / summary / coverImageUrl / status / contentHtml）</li>
     *   <li>save Article</li>
     *   <li>syncArticleTags — 必須在 publish events 之前</li>
     *   <li>publishContentChanged(article, RESTORED)</li>
     *   <li>若 article.status == PUBLISHED：publishUpdated(article)</li>
     * </ol>
     *
     * <p>caller 不需要再 inject ArticleEventPublisher / TagFacade write methods，
     * 也不會看到 Article entity。</p>
     *
     * @param articleId 文章資料庫主鍵
     * @param data      還原所需資料（含 tags）
     * @throws dowob.xyz.blog.common.exception.BusinessException ARTICLE_NOT_FOUND 若 articleId 對應 article 不存在
     */
    void applyRestoreContent(Long articleId, ArticleRestoreData data);
```

⚠ 用 Edit 對 `ArticleFacade.java` 做兩次編輯：一次插入 imports，一次插入 class Javadoc 與 method 區塊。

- [ ] **Step 5: 編譯驗證**

```bash
./mvnw.cmd -pl blog-infrastructure compile 2>&1 | tee logs/t1-compile.log | grep -E "BUILD|ERROR" | tail -10
```

Expected: BUILD SUCCESS。新 record + 新 method 宣告應 compile 通過（無 implementor 還沒做不影響 interface 編譯）。

- [ ] **Step 6: Commit**

```bash
git add blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/dto/ArticleContentData.java \
        blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/dto/ArticleRestoreData.java \
        blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/ArticleFacade.java
git commit -m "$(cat <<'EOF'
feat(infra): ArticleFacade 加 findContentById + applyRestoreContent 與 2 DTO

SP-D 第一步 — 在 SP-B 建立的 ArticleFacade 基礎上擴展 version 模組所需的 contract：

- ArticleContentData record（9 欄位含 content）— stash / content length 比較用
- ArticleRestoreData record（7 欄位 + tags）— atomic restore 用
- ArticleFacade.findContentById：read，return Optional<ArticleContentData>
- ArticleFacade.applyRestoreContent：atomic write，內部接管 mutate/save/syncTags/publish events

實作（ArticleFacadeImpl）由 T2 完成。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: ArticleFacadeImpl applyRestoreContent atomic + findContentById + unit tests (TDD)

**Files:**
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImpl.java`
- Modify: `blog-module-article/src/test/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImplTest.java`

- [ ] **Step 1: Read ArticleFacadeImpl 既有結構**

```bash
Read blog-module-article/src/main/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImpl.java
```

確認 class declaration（`@Component @RequiredArgsConstructor`）+ 既有 4 個 final field（`ArticleMapper articleMapper / UserFacade userFacade / ArticleRecommendMapper recommendMapper / ArticleService articleService`）。

- [ ] **Step 2: Read ArticleFacadeImplTest 既有結構**

```bash
Read blog-module-article/src/test/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImplTest.java
```

確認既有 4 個 @Mock + setUp 構造方式。SP-D 要加新 inject（`ArticleRepository`, `TagFacade`, `ArticleEventPublisher`）— setUp 構造會多 3 個參數。

- [ ] **Step 3: 寫 ArticleFacadeImplTest 新 test class（TDD red）**

於 `ArticleFacadeImplTest.java` 既有 `@Nested` 結構之後加 2 個新 @Nested class：

```java
    @Nested
    @DisplayName("findContentById（SP-D 新增）")
    class FindContentById {

        @Test
        @DisplayName("article 存在 → return Optional 含 ArticleContentData")
        void findContentById_existing_returnsContentData() {
            // given
            Long articleId = 100L;
            UUID articleUuid = UUID.randomUUID();
            UUID authorUuid = UUID.randomUUID();
            Article article = new Article();
            article.setId(articleId);
            article.setUuid(articleUuid);
            article.setAuthorId(5L);
            article.setTitle("Test Title");
            article.setSlug("test-slug");
            article.setContent("# Hello");
            article.setSummary("summary");
            article.setCoverImageUrl("https://cdn.example/cover.jpg");
            article.setStatus(ArticleStatus.PUBLISHED);
            when(articleRepository.findById(articleId)).thenReturn(Optional.of(article));

            // when
            Optional<ArticleContentData> result = facade.findContentById(articleId);

            // then
            assertThat(result).isPresent();
            ArticleContentData data = result.get();
            assertThat(data.id()).isEqualTo(articleId);
            assertThat(data.uuid()).isEqualTo(articleUuid);
            assertThat(data.authorId()).isEqualTo(5L);
            assertThat(data.title()).isEqualTo("Test Title");
            assertThat(data.slug()).isEqualTo("test-slug");
            assertThat(data.content()).isEqualTo("# Hello");
            assertThat(data.summary()).isEqualTo("summary");
            assertThat(data.coverImageUrl()).isEqualTo("https://cdn.example/cover.jpg");
            assertThat(data.status()).isEqualTo("PUBLISHED");
        }

        @Test
        @DisplayName("article 不存在 → return Optional.empty")
        void findContentById_notFound_returnsEmpty() {
            when(articleRepository.findById(999L)).thenReturn(Optional.empty());

            Optional<ArticleContentData> result = facade.findContentById(999L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("article.status 為 null → ArticleContentData.status 為 null")
        void findContentById_statusNull_statusFieldNull() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(5L);
            article.setStatus(null);
            when(articleRepository.findById(100L)).thenReturn(Optional.of(article));

            Optional<ArticleContentData> result = facade.findContentById(100L);

            assertThat(result).isPresent();
            assertThat(result.get().status()).isNull();
        }
    }

    @Nested
    @DisplayName("applyRestoreContent atomic（SP-D 新增）")
    class ApplyRestoreContent {

        private Long articleId;
        private UUID articleUuid;
        private Article existing;

        @BeforeEach
        void setUpArticle() {
            articleId = 100L;
            articleUuid = UUID.randomUUID();
            existing = new Article();
            existing.setId(articleId);
            existing.setUuid(articleUuid);
            existing.setStatus(ArticleStatus.DRAFT);
            when(articleRepository.findById(articleId)).thenReturn(Optional.of(existing));
            when(articleRepository.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("article 不存在 → throw ARTICLE_NOT_FOUND")
        void applyRestoreContent_articleNotFound_throws() {
            when(articleRepository.findById(999L)).thenReturn(Optional.empty());
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, "DRAFT", "<p>c</p>", List.of()
            );

            assertThatThrownBy(() -> facade.applyRestoreContent(999L, data))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("A0201");
        }

        @Test
        @DisplayName("PUBLISHED article 還原 → publishContentChanged(RESTORED) + publishUpdated 都發")
        void applyRestoreContent_publishedArticle_publishesBoth() {
            ArticleRestoreData data = new ArticleRestoreData(
                "New Title", "new-slug", "# New", "New summary", "https://cdn/new.jpg",
                "PUBLISHED", "<p>New</p>", List.of(UUID.randomUUID(), UUID.randomUUID())
            );

            facade.applyRestoreContent(articleId, data);

            assertThat(existing.getTitle()).isEqualTo("New Title");
            assertThat(existing.getSlug()).isEqualTo("new-slug");
            assertThat(existing.getContent()).isEqualTo("# New");
            assertThat(existing.getSummary()).isEqualTo("New summary");
            assertThat(existing.getCoverImageUrl()).isEqualTo("https://cdn/new.jpg");
            assertThat(existing.getStatus()).isEqualTo(ArticleStatus.PUBLISHED);
            assertThat(existing.getContentHtml()).isEqualTo("<p>New</p>");

            verify(articleRepository).save(existing);
            verify(tagFacade).syncArticleTags(eq(articleUuid), eq(data.tags()));
            verify(articleEventPublisher).publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
            verify(articleEventPublisher).publishUpdated(existing);
        }

        @Test
        @DisplayName("DRAFT article 還原 → 只發 publishContentChanged，不發 publishUpdated")
        void applyRestoreContent_draftArticle_publishesOnlyContentChanged() {
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, "DRAFT", "<p>c</p>", List.of()
            );

            facade.applyRestoreContent(articleId, data);

            assertThat(existing.getStatus()).isEqualTo(ArticleStatus.DRAFT);
            verify(articleEventPublisher).publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
            verify(articleEventPublisher, never()).publishUpdated(any());
        }

        @Test
        @DisplayName("status 為 null → 不更新 article.status")
        void applyRestoreContent_statusNull_doesNotChangeStatus() {
            ArticleStatus before = existing.getStatus();
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, null, "<p>c</p>", List.of()
            );

            facade.applyRestoreContent(articleId, data);

            assertThat(existing.getStatus()).isEqualTo(before);
        }

        @Test
        @DisplayName("tags 為 null → syncArticleTags 用空清單")
        void applyRestoreContent_tagsNull_syncWithEmptyList() {
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, "DRAFT", "<p>c</p>", null
            );

            facade.applyRestoreContent(articleId, data);

            verify(tagFacade).syncArticleTags(articleUuid, List.of());
        }

        @Test
        @DisplayName("invocation 順序：save → syncArticleTags → publishEvents")
        void applyRestoreContent_invocationOrder_saveThenSyncTagsThenPublish() {
            ArticleRestoreData data = new ArticleRestoreData(
                "T", "s", "c", "sum", null, "PUBLISHED", "<p>c</p>", List.of()
            );

            facade.applyRestoreContent(articleId, data);

            InOrder inOrder = inOrder(articleRepository, tagFacade, articleEventPublisher);
            inOrder.verify(articleRepository).save(existing);
            inOrder.verify(tagFacade).syncArticleTags(eq(articleUuid), anyList());
            inOrder.verify(articleEventPublisher).publishContentChanged(existing, ArticleContentChangedEvent.Action.RESTORED);
            inOrder.verify(articleEventPublisher).publishUpdated(existing);
        }
    }
```

對應加 imports（在 test class 既有 imports 區段加）：

```java
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreData;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.mockito.InOrder;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
```

於 class top-level 加新 @Mock fields + 改 setUp constructor 多 3 個參數：

```java
    @Mock private ArticleRepository articleRepository;
    @Mock private TagFacade tagFacade;
    @Mock private ArticleEventPublisher articleEventPublisher;
```

`setUp` 改：

```java
    @BeforeEach
    void setUp() {
        facade = new ArticleFacadeImpl(
            articleMapper, userFacade, recommendMapper, articleService,
            articleRepository, tagFacade, articleEventPublisher
        );
    }
```

對應加 imports（`ArticleRepository / TagFacade / ArticleEventPublisher`）。

- [ ] **Step 4: Run tests — verify RED**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleFacadeImplTest 2>&1 | tee logs/t2-red.log | grep -E "Tests run:|BUILD|ERROR" | tail -10
```

Expected: 編譯失敗 — `ArticleFacadeImpl` constructor 還沒 7 個參數，`findContentById / applyRestoreContent` 還沒實作。

- [ ] **Step 5: 修 ArticleFacadeImpl — 加 inject 與 2 個 method**

於 `ArticleFacadeImpl.java` 加 imports：

```java
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreData;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
```

於既有 4 個 final field 之後加：

```java
    private final ArticleRepository articleRepository;
    private final TagFacade tagFacade;
    private final ArticleEventPublisher articleEventPublisher;
```

於 class 最後（既有 method 之後）加 2 個 method 實作：

```java
    @Override
    public Optional<ArticleContentData> findContentById(Long articleId) {
        return articleRepository.findById(articleId).map(this::toContentData);
    }

    @Override
    @Transactional
    public void applyRestoreContent(Long articleId, ArticleRestoreData data) {
        Article article = articleRepository.findById(articleId)
            .orElseThrow(() -> new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));

        article.setTitle(data.title());
        article.setSlug(data.slug());
        article.setContent(data.content());
        article.setSummary(data.summary());
        article.setCoverImageUrl(data.coverImageUrl());
        if (data.status() != null) {
            article.setStatus(ArticleStatus.valueOf(data.status()));
        }
        article.setContentHtml(data.contentHtml());

        Article saved = articleRepository.save(article);

        tagFacade.syncArticleTags(saved.getUuid(),
            data.tags() != null ? data.tags() : List.of());

        articleEventPublisher.publishContentChanged(saved, ArticleContentChangedEvent.Action.RESTORED);
        if (saved.getStatus() == ArticleStatus.PUBLISHED) {
            articleEventPublisher.publishUpdated(saved);
        }
    }

    private ArticleContentData toContentData(Article a) {
        return new ArticleContentData(
            a.getId(),
            a.getUuid(),
            a.getAuthorId(),
            a.getTitle(),
            a.getSlug(),
            a.getContent(),
            a.getSummary(),
            a.getCoverImageUrl(),
            a.getStatus() != null ? a.getStatus().name() : null
        );
    }
```

- [ ] **Step 6: Run tests — verify GREEN**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleFacadeImplTest 2>&1 | tee logs/t2-green.log | grep -E "Tests run:|BUILD" | tail -5
```

Expected: ArticleFacadeImplTest 全部 cases pass（既有 + 9 個新 = 約 26 個）。BUILD SUCCESS。

- [ ] **Step 7: Run article module 全 tests 確認無 regression**

```bash
./mvnw.cmd -pl blog-module-article test 2>&1 | tee logs/t2-article-tests.log | grep -E "^\[INFO\] Tests run:" | tail -5
```

Expected: article 模組 所有 tests 全綠（254 + 9 ≈ 263）。

- [ ] **Step 8: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImpl.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/facade/ArticleFacadeImplTest.java
git commit -m "$(cat <<'EOF'
feat(article): ArticleFacadeImpl applyRestoreContent atomic + findContentById + 9 unit tests

實作 SP-D 新增的 2 個 facade method：
- findContentById：純 delegate，Article entity → ArticleContentData record
- applyRestoreContent：atomic flow（mutate / save / syncArticleTags / publish events）

順序敏感性以 Mockito InOrder 驗證：save → syncArticleTags → publishContentChanged → publishUpdated。

新加 inject：ArticleRepository / TagFacade / ArticleEventPublisher（皆同模組或 infrastructure，無跨模組 anti-pattern）。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: AutoSnapshotPolicy 改 inject ArticleFacade

**Files:**
- Modify: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/AutoSnapshotPolicy.java`
- Modify: `blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/AutoSnapshotPolicyTest.java`

- [ ] **Step 1: Read AutoSnapshotPolicy 既有結構**

```bash
Read blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/AutoSnapshotPolicy.java
```

確認 inject 與 1 處 `articleRepo.findById(articleId)` 用法（含 `currentLength(Article)` overload）。

- [ ] **Step 2: 修 AutoSnapshotPolicy**

於 `AutoSnapshotPolicy.java`：

**imports 改**：
```java
// 移除：
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
// 加：
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
```

**field 改**：
```java
// 既有
private final ArticleRepository articleRepo;
// 改為
private final ArticleFacade articleFacade;
```

**`shouldSnapshot` 方法 body 改**：

```java
public boolean shouldSnapshot(Long articleId) {
    ArticleContentData article = articleFacade.findContentById(articleId).orElse(null);
    if (article == null) return false;

    AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.authorId());
    if (!cfg.enabled()) return false;

    Optional<ArticleVersion> lastOpt = versionRepo.findLatestByArticleAndType(articleId, TYPE_AUTO);
    if (lastOpt.isEmpty()) return true;

    ArticleVersion last = lastOpt.get();
    Duration sinceLast = Duration.between(last.getCreatedAt(), LocalDateTime.now());
    if (sinceLast.getSeconds() < cfg.intervalSeconds()) return false;

    if (cfg.diffChars() > 0) {
        int currentLen = article.content() != null ? article.content().length() : 0;
        int lastLen = last.getContent() != null ? last.getContent().length() : 0;
        int diff = Math.abs(currentLen - lastLen);
        if (diff < cfg.diffChars()) return false;
    }

    return true;
}
```

⚠ 移除 `currentLength(Article)` 與 `currentLength(ArticleVersion)` 兩個 helper（原本各 3 行），改 inline length 計算。理由：record accessor 直接呼叫 `.content().length()` 易讀，不需要 helper。

- [ ] **Step 3: Read AutoSnapshotPolicyTest 既有結構**

```bash
Read blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/AutoSnapshotPolicyTest.java
```

確認 @Mock + 既有 stub 風格。

- [ ] **Step 4: 修 AutoSnapshotPolicyTest**

**imports 改**：
```java
// 移除：
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
// 加：
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
```

**@Mock 改**：
```java
@Mock private ArticleFacade articleFacade;     // 取代 @Mock ArticleRepository articleRepo
```

**setUp** 對應改 `new AutoSnapshotPolicy(articleFacade, versionRepo, preferenceResolver)`。

**所有 stub 改**（典型 pattern）：
```java
// 既有：
when(articleRepo.findById(articleId)).thenReturn(Optional.of(buildArticle(...)));
// 改：
when(articleFacade.findContentById(articleId)).thenReturn(Optional.of(buildContentData(...)));
```

加 helper（取代既有 buildArticle / 直接 Article setter）：

```java
private ArticleContentData buildContentData(Long id, Long authorId, String content) {
    return new ArticleContentData(
        id, java.util.UUID.randomUUID(), authorId,
        "T", "s", content, "sum", null, "PUBLISHED"
    );
}
```

⚠ 既有 test 的 `Article` instance 用法全部改 `ArticleContentData`。

- [ ] **Step 5: install + Run tests**

```bash
./mvnw.cmd -pl blog-module-article -am install -DskipTests
./mvnw.cmd -pl blog-module-version test -Dtest=AutoSnapshotPolicyTest 2>&1 | tee logs/t3-tests.log | grep -E "Tests run:|BUILD" | tail -5
```

Expected: AutoSnapshotPolicyTest 既有 4-6 個 tests 全綠。BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/AutoSnapshotPolicy.java \
        blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/AutoSnapshotPolicyTest.java
git commit -m "$(cat <<'EOF'
refactor(version): AutoSnapshotPolicy 改 inject ArticleFacade

- ArticleRepository → ArticleFacade（infrastructure 介面）
- findById(Article) → findContentById(ArticleContentData)
- 移除 currentLength(Article) helper（record accessor 內聯，無需重複實作）
- AutoSnapshotPolicyTest mock + stub 對應改

純跨模組 inject swap，行為等價，既有 tests 全綠。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: VersioningService 改 inject ArticleFacade

**Files:**
- Modify: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/VersioningService.java`
- Modify: `blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/VersioningServiceTest.java`
- Modify: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/controller/VersionController.java`（restore endpoint return 對齊 void）

- [ ] **Step 1: Read VersioningService 既有 8 處 articleRepo call**

```bash
Read blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/VersioningService.java
```

確認 8 處：L57 / L78 / L94（snapshot stash）+ L194 / L214（restore） + L252 / L300 / L325（權限驗證），加 `articleEventPublisher.publishContentChanged + publishUpdated` L225-228。

- [ ] **Step 2: 修 VersioningService — imports + fields**

於 `VersioningService.java`：

**imports 移除**：
```java
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent.Action;
```

**imports 加**：
```java
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreData;
```

**fields 改**：
```java
// 既有：
private final ArticleRepository articleRepo;
private final ArticleVersionRepository versionRepo;
private final VersionMapper versionMapper;
private final PreferenceResolver preferenceResolver;
private final ArticleMarkdownRenderer markdownRenderer;
private final ArticleEventPublisher articleEventPublisher;     // ← 移除
private final TagFacade tagFacade;

// 改為：
private final ArticleFacade articleFacade;                      // ← 新（取代 articleRepo）
private final ArticleVersionRepository versionRepo;
private final VersionMapper versionMapper;
private final PreferenceResolver preferenceResolver;
private final ArticleMarkdownRenderer markdownRenderer;
private final TagFacade tagFacade;
// （ArticleEventPublisher 不再需要）
```

- [ ] **Step 3: 修 snapshotFromArticle → snapshotFromContent**

於 `VersioningService.java` L362（既有 method）：

```java
// 既有：
protected ArticleVersion snapshotFromArticle(Article article, String type, String note) {
    ArticleVersion v = new ArticleVersion();
    v.setUuid(UUID.randomUUID());
    v.setArticleId(article.getId());
    v.setAuthorId(article.getAuthorId());
    v.setType(type);
    v.setTitle(article.getTitle());
    v.setSlug(article.getSlug());
    v.setContent(article.getContent());
    v.setSummary(article.getSummary());
    v.setCoverImageUrl(article.getCoverImageUrl());
    ArticleStatus st = article.getStatus();
    v.setStatus(st != null ? st.name() : null);
    v.setTags(tagFacade.findTagIdsByArticleUuid(article.getUuid()));
    v.setNote(note);
    v.setCreatedAt(LocalDateTime.now());
    return v;
}

// 改為：
protected ArticleVersion snapshotFromContent(ArticleContentData article, String type, String note) {
    ArticleVersion v = new ArticleVersion();
    v.setUuid(UUID.randomUUID());
    v.setArticleId(article.id());
    v.setAuthorId(article.authorId());
    v.setType(type);
    v.setTitle(article.title());
    v.setSlug(article.slug());
    v.setContent(article.content());
    v.setSummary(article.summary());
    v.setCoverImageUrl(article.coverImageUrl());
    v.setStatus(article.status());                                  // 已是 String
    v.setTags(tagFacade.findTagIdsByArticleUuid(article.uuid()));
    v.setNote(note);
    v.setCreatedAt(LocalDateTime.now());
    return v;
}
```

⚠ method name 改為 `snapshotFromContent`（caller 全部跟著改）；不再需要 `ArticleStatus` import（改成 String 直接 set）。如果 imports 有 `ArticleStatus` 但其他地方仍需用（restore 內部？），看實際清理。

- [ ] **Step 4: 修 8 處 call sites — read 用 facade**

於 `VersioningService.java`：

**L57 (recordAutoSnapshot)**：
```java
// 既有：
Article article = articleRepo.findById(articleId).orElse(null);
if (article == null) return;
AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.getAuthorId());
ArticleVersion v = snapshotFromArticle(article, TYPE_AUTO, null);
versionRepo.save(v);
versionMapper.retainAuto(articleId, cfg.retain());

// 改為：
ArticleContentData article = articleFacade.findContentById(articleId).orElse(null);
if (article == null) return;
AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.authorId());
ArticleVersion v = snapshotFromContent(article, TYPE_AUTO, null);
versionRepo.save(v);
versionMapper.retainAuto(articleId, cfg.retain());
```

**L78 (recordManualSnapshot)**：
```java
// 改為：
ArticleContentData article = articleFacade.findContentById(articleId)
    .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));
ArticleVersion v = snapshotFromContent(article, TYPE_MANUAL, note);
return versionRepo.save(v);
```

**L94 (freezePublished)**：
```java
// 改為：
ArticleContentData article = articleFacade.findContentById(articleId).orElse(null);
if (article == null) return;
int count = versionMapper.countPublished(articleId);
String note = "Published v" + (count + 1);
ArticleVersion v = snapshotFromContent(article, TYPE_PUBLISHED, note);
versionRepo.save(v);
versionMapper.deleteAutoByArticle(articleId);
```

**L252 (listByArticle)** — 改用既有 `findByUuid`（return ArticleData，已有 authorId / id）：
```java
ArticleData article = articleFacade.findByUuid(articleUuid)
    .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));
if (!isAdmin && !article.authorId().equals(currentUserId)) {
    throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
}
int offset = Math.max(0, (page - 1) * size);
List<VersionSummaryResponse> rows = versionMapper
    .listSummaries(article.id(), typeFilter, size, offset);
long total = versionMapper.countSummaries(article.id(), typeFilter);
return PageResult.of(page, size, total, rows);
```

⚠ 需 import `ArticleData`（既有 SP-B 加的 record）。

**L300 (findArticleIdByUuidOrThrow)**：
```java
ArticleData article = articleFacade.findByUuid(articleUuid)
    .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));
if (!isAdmin && !article.authorId().equals(currentUserId)) {
    throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
}
return article.id();
```

**L325 (assertVersionBelongsToArticle)**：
```java
ArticleVersion v = versionRepo.findByUuid(versionUuid)
    .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
ArticleData article = articleFacade.findByUuid(articleUuid)
    .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));
if (!v.getArticleId().equals(article.id())) {
    throw new BusinessException(VersionErrorCode.VERSION_ARTICLE_MISMATCH);
}
```

- [ ] **Step 5: 修 restore() — atomic flow 取代 articleRepo.save + manual publish**

於 `VersioningService.java` L194-230 整個 restore method body：

```java
@Transactional
public void restore(UUID versionUuid, Long currentUserId, boolean isAdmin) {     // ← return type Article → void
    ArticleVersion v = versionRepo.findByUuid(versionUuid)
        .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
    if (!isAdmin && !v.getAuthorId().equals(currentUserId)) {
        throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
    }

    ArticleContentData article = articleFacade.findContentById(v.getArticleId())
        .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));

    /* 1. stash 當前 article 狀態為 AUTO snapshot */
    ArticleVersion stash = snapshotFromContent(article, TYPE_AUTO, null);
    versionRepo.save(stash);
    AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.authorId());
    versionMapper.retainAuto(article.id(), cfg.retain());

    /* 2. atomic restore via ArticleFacade（mutate / save / syncArticleTags / publish events 全內部接管） */
    ArticleRestoreData restoreData = new ArticleRestoreData(
        v.getTitle(),
        v.getSlug(),
        v.getContent(),
        v.getSummary(),
        v.getCoverImageUrl(),
        v.getStatus(),
        markdownRenderer.render(v.getContent()),
        v.getTags() != null ? v.getTags() : List.of()
    );
    articleFacade.applyRestoreContent(article.id(), restoreData);
}
```

⚠ method signature 從 `public Article restore(...)` 改為 `public void restore(...)`（VersionController.restore endpoint 不使用回傳值）。

⚠ 移除 imports：`ArticleStatus`（可能仍他處用）、`Action`（不再 publish）。

- [ ] **Step 6: 修 VersionController — 對齊 restore void**

```bash
Read blog-module-version/src/main/java/dowob/xyz/blog/module/version/controller/VersionController.java
```

於 restore endpoint method body（既有 L111-122 區段）：

```java
// 既有：
versioningService.restore(versionUuid, currentUserId, isAdmin());
return ApiResponse.success();
```

audit 已確認 endpoint 直接呼叫 `versioningService.restore(...)` 並丟棄 return value（不存 local variable，不放進 response）— 改 service return type 為 void 後 controller 程式碼**不需改動**，仍能正常編譯。

⚠ 但 T5 之後會改 `isAdmin()` 為 `SecurityUtils.isAdmin()`（在 T5 處理，本 task 暫不改）。

- [ ] **Step 7: 修 VersioningServiceTest — mock + stub + verify 全改**

```bash
Read blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/VersioningServiceTest.java
```

**imports 改**：
```java
// 移除：
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
// 加：
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreData;
```

**@Mock 改**：
```java
@Mock private ArticleFacade articleFacade;        // 取代 ArticleRepository articleRepo
// 移除 @Mock ArticleEventPublisher articleEventPublisher
```

**setUp** 對應改 constructor 6 個參數（依本檔 field 定義順序）：
```java
service = new VersioningService(
    articleFacade,        // 取代 articleRepo
    versionRepo, versionMapper, preferenceResolver, markdownRenderer, tagFacade
    // ArticleEventPublisher 不再需要
);
```

⚠ 既有 test 用 `@InjectMocks` — 改用 `@InjectMocks` 仍 OK，但要刪除 `@Mock ArticleEventPublisher` 並加 `@Mock ArticleFacade`。

**Stub 改（typical patterns）**：

```java
// 既有：
when(articleRepo.findById(articleId)).thenReturn(Optional.of(article));
// 改為：
when(articleFacade.findContentById(articleId)).thenReturn(Optional.of(contentData));

// 既有：
when(articleRepo.findByUuid(uuid)).thenReturn(Optional.of(article));
// 改為：
when(articleFacade.findByUuid(uuid)).thenReturn(Optional.of(articleData));

// 既有：
when(articleRepo.save(any(Article.class))).thenAnswer(inv -> inv.getArgument(0));
// 移除 — restore 不再直接 articleRepo.save，改 articleFacade.applyRestoreContent
```

**Verify 改**：

```java
// 既有（restore test）：
verify(articleEventPublisher).publishContentChanged(eq(article), eq(Action.RESTORED));
verify(tagFacade).syncArticleTags(any(), any());
verify(articleRepo).save(article);
// 改為：
verify(articleFacade).applyRestoreContent(eq(articleId), any(ArticleRestoreData.class));
// （atomic 內部 verify 已在 ArticleFacadeImplTest 完成 — VersioningServiceTest 只 verify call 即可）
```

加 helper：
```java
private ArticleContentData contentData(Long id, Long authorId, String content) {
    return new ArticleContentData(
        id, java.util.UUID.randomUUID(), authorId,
        "T", "slug", content, "sum", null, "PUBLISHED"
    );
}

private ArticleData articleData(Long id, java.util.UUID uuid, Long authorId) {
    return new ArticleData(id, uuid, authorId, "PUBLISHED", null, null);
}
```

- [ ] **Step 8: install + Run tests**

```bash
./mvnw.cmd -pl blog-module-article -am install -DskipTests
./mvnw.cmd -pl blog-module-version test 2>&1 | tee logs/t4-version-tests.log | grep -E "Tests run:|BUILD" | tail -10
```

Expected: version 模組 tests（含 IT）全綠。

⚠ 如果 IT（CrossModuleVersionIT）跑出問題，可能因為改 restore void 後 IT 預期返回值。修 IT 對應斷言。

- [ ] **Step 9: Commit**

```bash
git add blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/VersioningService.java \
        blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/VersioningServiceTest.java \
        blog-module-version/src/main/java/dowob/xyz/blog/module/version/controller/VersionController.java
# CrossModuleVersionIT 如有改也加進來
git commit -m "$(cat <<'EOF'
refactor(version): VersioningService 改 inject ArticleFacade（含 8 處 call + atomic restore）

- ArticleRepository → ArticleFacade（移除跨模組 Repository inject）
- ArticleEventPublisher 移除（atomic facade 內部接管 publish events）
- 8 處 articleRepo 切換：4 stash 用 findContentById（ArticleContentData）/
  3 權限驗證用 findByUuid（既有 ArticleData 已夠）/
  1 save 改 applyRestoreContent atomic
- snapshotFromArticle(Article) rename snapshotFromContent(ArticleContentData)
- restore() return type Article → void（VersionController 已忽略 return value）

VersioningServiceTest mock + stub + verify 對應改寫；既有 tests 全綠。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: SecurityUtils.isAdmin + VersionController/SeriesController 改用

**Files:**
- Modify: `blog-common/src/main/java/dowob/xyz/blog/common/util/SecurityUtils.java`
- Modify: `blog-common/src/test/java/dowob/xyz/blog/common/util/SecurityUtilsTest.java`
- Modify: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/controller/VersionController.java`
- Modify: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/controller/SeriesController.java`

- [ ] **Step 1: 寫 SecurityUtilsTest 新 isAdmin tests（TDD red）**

於 `blog-common/src/test/java/dowob/xyz/blog/common/util/SecurityUtilsTest.java`：

加 imports（如尚無）：
```java
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
```

於 class 內加 2 個 @Nested test class：

```java
    @Nested
    @DisplayName("isAdmin(Authentication)（帶參版）")
    class IsAdminWithAuthentication {

        @Test
        @DisplayName("authentication 為 null → false")
        void isAdmin_null_returnsFalse() {
            assertThat(SecurityUtils.isAdmin((Authentication) null)).isFalse();
        }

        @Test
        @DisplayName("AnonymousAuthenticationToken → false")
        void isAdmin_anonymous_returnsFalse() {
            Authentication auth = new AnonymousAuthenticationToken(
                "key", "anon",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
            );
            assertThat(SecurityUtils.isAdmin(auth)).isFalse();
        }

        @Test
        @DisplayName("已認證但 ROLE_USER → false")
        void isAdmin_user_returnsFalse() {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                "user", "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            assertThat(SecurityUtils.isAdmin(auth)).isFalse();
        }

        @Test
        @DisplayName("ROLE_ADMIN → true")
        void isAdmin_admin_returnsTrue() {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                "admin", "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            );
            assertThat(SecurityUtils.isAdmin(auth)).isTrue();
        }
    }

    @Nested
    @DisplayName("isAdmin()（無參版 — 從 SecurityContextHolder 取）")
    class IsAdminNoArg {

        @org.junit.jupiter.api.AfterEach
        void clearContext() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("SecurityContext 為空 → false")
        void isAdmin_emptyContext_returnsFalse() {
            SecurityContextHolder.clearContext();
            assertThat(SecurityUtils.isAdmin()).isFalse();
        }

        @Test
        @DisplayName("SecurityContext 有 ROLE_ADMIN → true")
        void isAdmin_adminInContext_returnsTrue() {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                "admin", "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
            assertThat(SecurityUtils.isAdmin()).isTrue();
        }

        @Test
        @DisplayName("SecurityContext 有 ROLE_USER → false")
        void isAdmin_userInContext_returnsFalse() {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                "user", "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
            assertThat(SecurityUtils.isAdmin()).isFalse();
        }
    }
```

- [ ] **Step 2: Run tests — verify RED**

```bash
./mvnw.cmd -pl blog-common test -Dtest=SecurityUtilsTest 2>&1 | tee logs/t5-red.log | grep -E "Tests run:|BUILD|ERROR" | tail -5
```

Expected: 編譯失敗 — `SecurityUtils.isAdmin()` 與 `isAdmin(Authentication)` 還沒實作。

- [ ] **Step 3: 修 SecurityUtils — 加 2 個 isAdmin overload**

於 `blog-common/src/main/java/dowob/xyz/blog/common/util/SecurityUtils.java`：

加 imports：
```java
import org.springframework.security.core.context.SecurityContextHolder;
```

於 class 既有 `resolveRole` method 之後加：

```java
    /**
     * 從 SecurityContextHolder 直接取 — controller 內最簡呼叫方式。
     *
     * @return 當前 thread 的 authentication 是否為 ROLE_ADMIN
     */
    public static boolean isAdmin() {
        return isAdmin(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * 帶參版 — unit test / 自行傳 Authentication 的 caller 用。
     *
     * @param authentication Spring Security 認證物件（可為 null / AnonymousAuthenticationToken / 認證 token）
     * @return 是否為 ROLE_ADMIN（null / 匿名 / 未認證 / 其他角色一律 false）
     */
    public static boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
```

- [ ] **Step 4: Run tests — verify GREEN**

```bash
./mvnw.cmd -pl blog-common test -Dtest=SecurityUtilsTest 2>&1 | tee logs/t5-green.log | grep -E "Tests run:|BUILD" | tail -5
```

Expected: SecurityUtilsTest 全 cases pass（既有 3 + 新 7 = 10）。BUILD SUCCESS。

- [ ] **Step 5: 修 VersionController — 移除 private isAdmin + 改用 SecurityUtils**

於 `blog-module-version/src/main/java/dowob/xyz/blog/module/version/controller/VersionController.java`：

加 import：
```java
import dowob.xyz.blog.common.util.SecurityUtils;
```

移除 imports（如僅 isAdmin helper 用過）：
```java
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
```

⚠ 確認其他 method 是否還用 SecurityContextHolder / SimpleGrantedAuthority — 如還用就保留。

移除 L161-164：
```java
private boolean isAdmin() {
    return SecurityContextHolder.getContext().getAuthentication().getAuthorities()
            .contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
}
```

所有 `isAdmin()` call 改 `SecurityUtils.isAdmin()`（grep 確認 caller 數）：
```bash
grep -n "isAdmin()" blog-module-version/src/main/java/dowob/xyz/blog/module/version/controller/VersionController.java
```

每處改：
```java
// 既有：
versioningService.xxx(..., isAdmin());
// 改為：
versioningService.xxx(..., SecurityUtils.isAdmin());
```

- [ ] **Step 6: 修 SeriesController — 同樣處理**

於 `blog-module-series/src/main/java/dowob/xyz/blog/module/series/controller/SeriesController.java`：

加 import：
```java
import dowob.xyz.blog.common.util.SecurityUtils;
```

移除 L128-131 private isAdmin + 不再用的 imports（`SecurityContextHolder`, `SimpleGrantedAuthority` 如僅 isAdmin 用）。

所有 `isAdmin()` call 改 `SecurityUtils.isAdmin()`。

- [ ] **Step 7: install + Run tests**

```bash
./mvnw.cmd -pl blog-common -am install -DskipTests
./mvnw.cmd -pl blog-module-version,blog-module-series test 2>&1 | tee logs/t5-controllers-tests.log | grep -E "Tests run:|BUILD" | tail -10
```

Expected: version + series 模組 tests 全綠（含 IT）。

- [ ] **Step 8: 全模組驗 isAdmin 重複 helper = 0**

```bash
grep -rn "private boolean isAdmin" blog-module-*/src/main/java/ 2>&1 | head -5
```

Expected: 0 行命中。

- [ ] **Step 9: Commit**

```bash
git add blog-common/src/main/java/dowob/xyz/blog/common/util/SecurityUtils.java \
        blog-common/src/test/java/dowob/xyz/blog/common/util/SecurityUtilsTest.java \
        blog-module-version/src/main/java/dowob/xyz/blog/module/version/controller/VersionController.java \
        blog-module-series/src/main/java/dowob/xyz/blog/module/series/controller/SeriesController.java
git commit -m "$(cat <<'EOF'
refactor(common): SecurityUtils 加 isAdmin，移除 controller 重複 helper

- SecurityUtils.isAdmin() 無參版（從 SecurityContextHolder 取）+ isAdmin(Authentication) 帶參版
- VersionController + SeriesController 移除 private boolean isAdmin() 重複實作
- SecurityUtilsTest 加 7 個新 cases（無參版 3 + 帶參版 4）

全 codebase grep "private boolean isAdmin" = 0。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: HighlightService null-check 補

**Files:**
- Modify: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/HighlightService.java`
- Modify: `blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/service/HighlightServiceTest.java`

- [ ] **Step 1: 寫 HighlightServiceTest 新 null-check tests（TDD red）**

於 `blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/service/HighlightServiceTest.java`：

加 imports（如尚無）：
```java
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

於 class 內加 2 個 test methods（位置：可置於既有 nested 結構之外，或新建 @Nested ArticleNotFound class）：

```java
    @Test
    @DisplayName("create：article 不存在 → throw ARTICLE_NOT_FOUND")
    void create_articleNotFound_throwsArticleNotFound() {
        UUID articleUuid = UUID.randomUUID();
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(null);

        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("text");
        req.setColor("yellow");

        assertThatThrownBy(() -> service.create(articleUuid, 1L, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("getByArticle：article 不存在 → throw ARTICLE_NOT_FOUND")
    void getByArticle_articleNotFound_throwsArticleNotFound() {
        UUID articleUuid = UUID.randomUUID();
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(null);

        assertThatThrownBy(() -> service.getByArticle(articleUuid, 1L))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getCode());
    }
```

⚠ 確認 `ArticleErrorCode.ARTICLE_NOT_FOUND.getCode()` 回傳 String "A0201"（spec §3.5 已確認此 code 存在）。

- [ ] **Step 2: Run tests — verify RED**

```bash
./mvnw.cmd -pl blog-module-reading test -Dtest=HighlightServiceTest 2>&1 | tee logs/t6-red.log | grep -E "Tests run:|BUILD|ERROR" | tail -5
```

Expected: 2 個新 cases FAIL（NullPointerException 或 FK 錯誤之類），既有 cases 仍綠。

- [ ] **Step 3: 修 HighlightService — 補 null-check**

於 `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/HighlightService.java`：

加 import：
```java
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
```

⚠ `BusinessException` 已既有 import (L3)。

修 `create()` method（既有 L31-47）：

```java
@Transactional
public HighlightResponse create(UUID articleUuid, Long userId, CreateHighlightRequest req) {
    Long articleId = articleFacade.findIdByUuid(articleUuid);
    if (articleId == null) {
        throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
    }

    UserHighlight h = new UserHighlight();
    h.setUuid(UUID.randomUUID());
    h.setUserId(userId);
    h.setArticleId(articleId);
    h.setSnippet(req.getSnippet());
    h.setPrefix(req.getPrefix() != null ? req.getPrefix() : "");
    h.setSuffix(req.getSuffix() != null ? req.getSuffix() : "");
    h.setColor(req.getColor());
    h.setNote(req.getNote());

    UserHighlight saved = repo.save(h);
    return toResponse(saved);
}
```

修 `getByArticle()` method（既有 L49-53）：

```java
public List<HighlightResponse> getByArticle(UUID articleUuid, Long userId) {
    Long articleId = articleFacade.findIdByUuid(articleUuid);
    if (articleId == null) {
        throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
    }
    return repo.findByUserIdAndArticleIdOrderByCreatedAtAsc(userId, articleId)
            .stream().map(this::toResponse).toList();
}
```

- [ ] **Step 4: Run tests — verify GREEN**

```bash
./mvnw.cmd -pl blog-module-reading test -Dtest=HighlightServiceTest 2>&1 | tee logs/t6-green.log | grep -E "Tests run:|BUILD" | tail -5
```

Expected: HighlightServiceTest 全 cases pass（既有 9 + 新 2 = 11）。

- [ ] **Step 5: Run reading 模組全 tests 確認無 regression**

```bash
./mvnw.cmd -pl blog-module-reading test 2>&1 | tee logs/t6-reading-tests.log | grep -E "^\[INFO\] Tests run:" | tail -3
```

Expected: reading 模組 全 tests 全綠（66 + 2 ≈ 68）。

- [ ] **Step 6: Commit**

```bash
git add blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/HighlightService.java \
        blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/service/HighlightServiceTest.java
git commit -m "$(cat <<'EOF'
fix(reading): HighlightService 補 articleId null-check

article 不存在時 articleFacade.findIdByUuid 回傳 null，未 check 直接用會撞
FK constraint 拋出 500（pre-existing bug，SP-B final review 發現）。

- create() / getByArticle() 加 null-check + throw BusinessException(ARTICLE_NOT_FOUND)
- 對齊 ArticleLikeController.resolveArticleId() 的處理 pattern
- 加 2 個 unit test 各覆蓋 article 不存在 path

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: 全模組驗收 + sanity test

**Files:** （無修改，純驗證）

- [ ] **Step 1: 全 codebase grep verify Done definition**

```bash
# 1. 跨模組 ArticleRepository inject 在 version 模組 = 0
grep -rn "private final ArticleRepository\|ArticleRepository articleRepo" blog-module-version/src/main/java/ 2>&1 | head -5
```

Expected: 0 行。

```bash
# 2. ArticleEventPublisher 跨模組 inject 在 version 模組 = 0
grep -rn "private final ArticleEventPublisher\|ArticleEventPublisher articleEventPublisher" blog-module-version/src/main/java/ 2>&1 | head -5
```

Expected: 0 行。

```bash
# 3. private boolean isAdmin 全模組 = 0
grep -rn "private boolean isAdmin" blog-module-*/src/main/java/ 2>&1 | head -5
```

Expected: 0 行。

```bash
# 4. ArticleFacade 在 AutoSnapshotPolicy / VersioningService inject 確認
grep -rn "private final ArticleFacade" blog-module-version/src/main/java/ 2>&1 | head -5
```

Expected: 2 行（AutoSnapshotPolicy + VersioningService）。

如有 grep 顯示殘留，回頭找對應 task 修。

- [ ] **Step 2: 跑 SP-D affected 5 個模組 tests**

```bash
./mvnw.cmd -pl blog-common,blog-infrastructure,blog-module-article,blog-module-version,blog-module-series,blog-module-reading test 2>&1 | tee logs/t7-all.log | grep -E "^\[INFO\] Tests run:" | tail -10
```

Expected: 全綠：
- common：~10+（含 7 新 SecurityUtils tests）
- infrastructure：76+
- article：260+（254 + 9 新 ArticleFacadeImplTest）
- version：~30+（既有 + IT）
- series：36
- reading：68（66 + 2 新）

- [ ] **Step 3: BUILD SUCCESS verify**

```bash
tail -5 logs/t7-all.log | grep "BUILD"
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: （可選）保留 verification log 給 PR description**

```bash
echo "=== SP-D Done verification ===" > logs/sp-d-done.log
echo "ArticleRepository 跨模組 inject (version 應為 0):" >> logs/sp-d-done.log
grep -rn "private final ArticleRepository" blog-module-version/src/main/java/ >> logs/sp-d-done.log
echo "" >> logs/sp-d-done.log
echo "ArticleEventPublisher 跨模組 inject (應為 0):" >> logs/sp-d-done.log
grep -rn "private final ArticleEventPublisher" blog-module-version/src/main/java/ >> logs/sp-d-done.log
echo "" >> logs/sp-d-done.log
echo "private boolean isAdmin (應為 0):" >> logs/sp-d-done.log
grep -rn "private boolean isAdmin" blog-module-*/src/main/java/ >> logs/sp-d-done.log
echo "" >> logs/sp-d-done.log
echo "ArticleFacade 在 version 模組 inject (應為 2 處):" >> logs/sp-d-done.log
grep -rn "private final ArticleFacade" blog-module-version/src/main/java/ >> logs/sp-d-done.log
```

⚠ logs/ 在 .gitignore 內，不會被 commit。

⚠ 本 task 通常無 commit — 純驗證。如 grep 找到殘留，回頭 patch + 補 commit。

---

## Self-Review Checklist

實作完成後請勾選：

- [ ] T1：ArticleFacade 加 findContentById + applyRestoreContent 宣告 + 2 個 DTO（ArticleContentData / ArticleRestoreData）
- [ ] T2：ArticleFacadeImpl 實作 findContentById（純 delegate）+ applyRestoreContent（atomic mutate / save / syncTags / publish events）+ 9 unit tests（含 InOrder verify）
- [ ] T3：AutoSnapshotPolicy inject 改 ArticleFacade，shouldSnapshot 用 ArticleContentData，AutoSnapshotPolicyTest mock 改
- [ ] T4：VersioningService inject 改 ArticleFacade（移除 ArticleRepository + ArticleEventPublisher），8 處 call 切換，restore atomic 用 applyRestoreContent，snapshotFromArticle rename snapshotFromContent，restore return type Article → void
- [ ] T5：SecurityUtils 加 isAdmin 2 overload + 7 unit tests，VersionController + SeriesController 移除 private isAdmin helper 改用 SecurityUtils
- [ ] T6：HighlightService.create / getByArticle 補 null-check + throw ARTICLE_NOT_FOUND，加 2 unit test
- [ ] T7：grep verify 全部通過 + 5 模組 tests 全綠

- [ ] 全 codebase `grep "private final ArticleRepository"` 在 version 模組 = 0
- [ ] 全 codebase `grep "private final ArticleEventPublisher"` 在 version 模組 = 0
- [ ] 全 codebase `grep "private boolean isAdmin"` = 0
- [ ] HighlightService null-check 兩處覆蓋
- [ ] CrossModuleVersionIT 仍綠（restore atomic flow IT 驗證）

---

## 結語

SP-D 完成後，version 模組對 article 模組的跨模組依賴僅剩 `ArticleFacade` interface（infrastructure 層），跨模組 Repository / EventPublisher / Article entity 全清。SP-D 後續：

- **SP-C** — ArticleServiceImpl 1018 行 god class 拆分；ArticleFacade 外部契約穩定，內部 split 對 caller 不可見
- **SP-X**（無時程） — ArticleQueryService 跨模組（BookmarkController + SeriesService）

預期 commit count：6（T1, T2, T3, T4, T5, T6 — T7 純驗證不產 commit）。
