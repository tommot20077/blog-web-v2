# Article Series Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 實作 Article Series 模組（系列文 1/N 進度），同 PR 順帶執行多個架構重整：AuthorSummary 移到 common、ArticleLike 搬到 reading 模組、article_likes 改名 user_article_likes、ReadingFacade 加 batchIsLiked。

**Architecture:** 新增 `blog-module-series` 獨立模組（沿用批 2 模式）。Series 透過 1:N 關係綁 articles（`articles.series_id` + `series_position`）。新增 `SeriesFacade` interface 在 `blog-infrastructure`，讓 `ArticleQueryService` 透過 facade 取得 `seriesNav`（prev/next）— 與 ReadingFacade 同 pattern，無循環依賴。Phase 0 refactor 把 ArticleLike 從 article 模組搬到 reading 模組，使「使用者-文章狀態」概念集中在一處。

**Tech Stack:** Spring Boot, Spring Data JDBC, MyBatis, PostgreSQL, Flyway, JUnit 5, Mockito, Spring Security Test, Testcontainers (PostgreSQL + Redis).

**Spec:** `docs/superpowers/specs/2026-05-02-article-series-design.md`

---

## File Map

### 新增檔案

```
blog-db-migration/
└─ src/main/resources/db/migration/V15__add_article_series_and_rename_likes.sql

blog-common/                                              [NEW PACKAGE]
└─ src/main/java/dowob/xyz/blog/common/api/dto/
   └─ AuthorSummary.java                                  (FROM blog-module-comment)

blog-infrastructure/
└─ src/main/java/dowob/xyz/blog/infrastructure/facade/
   ├─ SeriesFacade.java                                   NEW (interface)
   ├─ ReadingFacade.java                                  MODIFY (加 batchIsLiked / isLiked)
   └─ dto/SeriesNavigation.java                           NEW

blog-module-series/                                       [NEW MODULE]
├─ pom.xml
├─ src/main/java/dowob/xyz/blog/module/series/
│  ├─ controller/SeriesController.java
│  ├─ service/SeriesService.java
│  ├─ repository/SeriesRepository.java
│  ├─ mapper/SeriesMapper.java
│  ├─ model/
│  │   ├─ Series.java
│  │   ├─ SeriesWithAuthor.java                          (MyBatis row)
│  │   └─ dto/
│  │       ├─ request/{CreateSeriesRequest, UpdateSeriesRequest, AddArticleToSeriesRequest}.java
│  │       └─ response/{SeriesSummaryResponse, SeriesDetailResponse, MyProgress}.java
│  ├─ exception/SeriesErrorCode.java
│  └─ facade/SeriesFacadeImpl.java
└─ src/test/
   ├─ java/dowob/xyz/blog/module/series/
   │  ├─ config/SeriesTestApplication.java
   │  ├─ service/SeriesServiceTest.java
   │  ├─ controller/SeriesControllerIT.java
   │  └─ integration/CrossModuleSeriesIT.java
   └─ resources/{application-test.yaml, db/testdata/R__series_test_seed.sql}

blog-module-reading/                                      [REFACTOR + EXPAND]
├─ src/main/java/dowob/xyz/blog/module/reading/
│  ├─ controller/ArticleLikeController.java               MOVED FROM article 模組
│  ├─ service/ArticleLikeService.java                     MOVED FROM article 模組
│  ├─ repository/ArticleLikeRepository.java               MOVED FROM article 模組
│  ├─ mapper/ArticleLikeMapper.java                       NEW (從 ArticleMapper 抽出 batch is-liked SQL)
│  ├─ model/ArticleLike.java                              MOVED + @Table 改 user_article_likes
│  └─ facade/ReadingFacadeImpl.java                       MODIFY (加 batchIsLiked / isLiked)
└─ src/test/java/dowob/xyz/blog/module/reading/
   ├─ service/ArticleLikeServiceTest.java                 MOVED FROM article 模組
   └─ controller/ArticleLikeControllerIT.java             MOVED FROM article 模組

blog-module-article/                                      [SHRINK]
├─ src/main/java/dowob/xyz/blog/module/article/
│  ├─ controller/ArticleLikeController.java               DELETED
│  ├─ service/
│  │   ├─ ArticleLikeService.java                         DELETED
│  │   ├─ ArticleQueryService.java                        MODIFY (用 ReadingFacade.batchIsLiked + SeriesFacade)
│  │   └─ ArticleServiceImpl.java                         MODIFY (deleteArticle 連動 series.article_count)
│  ├─ repository/ArticleLikeRepository.java               DELETED
│  ├─ mapper/ArticleMapper.java                           MODIFY (移除 findLikedArticleIdsByUser)
│  ├─ model/
│  │   ├─ ArticleLike.java                                DELETED
│  │   └─ Article.java                                    MODIFY (加 seriesId + seriesPosition)
│  └─ model/dto/response/
│      ├─ ArticleResponse.java                            MODIFY (加 seriesNav)
│      └─ ArticleSummaryResponse.java                     MODIFY (加 seriesUuid/Title/Position)

blog-module-comment/
└─ src/main/java/dowob/xyz/blog/module/comment/model/dto/response/
   └─ AuthorSummary.java                                  DELETED (改 import blog-common 版)

ai-docs/schema.md                                         MODIFY (V15 三件)

pom.xml (root)                                            MODIFY (加 blog-module-series)
blog-start/pom.xml                                        MODIFY (加 dependency)
```

---

## Pre-Flight Notes

1. **Worktree**：`.worktrees/feature-article-series/`，base 在 develop（含批 1+2 全部）
2. **Maven**：`./mvnw.cmd`
3. **測試輸出**：`./mvnw.cmd test ... 2>&1 | tee logs/<task>.log`
4. **Surefire 報告**：失敗時讀 `<module>/target/surefire-reports/TEST-*.xml`
5. **Commit 慣例**：Conventional Commits + 繁中描述 + Co-Authored-By 行
6. **既有 patterns**：
   - Spring Data JDBC entity 用 `@Table` + `@Column` + `@CreatedDate`/`@LastModifiedDate`
   - UUID 必設 `setUuid(UUID.randomUUID())`
   - `BusinessException` 路徑：`dowob.xyz.blog.common.exception.BusinessException`
   - GlobalExceptionHandler：BusinessException → HTTP 400 + body code；AccessDeniedException → HTTP 403 + body code "A0006"
   - IT 需要 `R__seed.sql` 在 `src/test/resources/db/testdata/` 提供 FK users
   - application-test.yaml 必須有 `mybatis.type-handlers-package: dowob.xyz.blog.infrastructure.config`
   - 對齊 batch 2 ReadingFacade pattern：facade interface 在 infrastructure，impl 在 owning module
   - **MyBatisConfig 的 @MapperScan 必須加新模組的 mapper package（從批 1/2 學到）**

---

## Task 1: AuthorSummary 移到 blog-common

**Files:**
- Create: `blog-common/src/main/java/dowob/xyz/blog/common/api/dto/AuthorSummary.java`
- Delete: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/dto/response/AuthorSummary.java`
- Modify: comment 模組所有 import `AuthorSummary` 的檔案改 import `blog-common` 版

- [ ] **Step 1: Create blog-common 版**

```java
package dowob.xyz.blog.common.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 跨模組共享的作者輕量資訊 DTO。
 *
 * <p>用於各模組（comment / series 等）需要顯示作者基本資訊的場景，
 * 避免每個模組各自定義同樣形狀的 DTO。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthorSummary {
    private UUID uuid;
    private String nickname;
    private String avatarUrl;
}
```

- [ ] **Step 2: 找出 comment 模組所有 import**

```bash
cd D:/end/workspace/java/blog-web-v2/.worktrees/feature-article-series
grep -rn "dowob.xyz.blog.module.comment.model.dto.response.AuthorSummary" blog-module-comment/src/ 2>&1 | head -20
```

預期：CommentResponse / 部分 mapper 或 service 會 import。逐個改 import 為 `dowob.xyz.blog.common.api.dto.AuthorSummary`。

- [ ] **Step 3: 刪除 comment 模組舊檔**

```bash
rm blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/dto/response/AuthorSummary.java
```

- [ ] **Step 4: 編譯 + 跑 comment 模組測試**

```bash
./mvnw.cmd -pl blog-module-comment -am test 2>&1 | tee logs/t1-author-summary.log
```

Expected: 既有 67 tests 全綠（純 import refactor，無邏輯變動）。

- [ ] **Step 5: Commit**

```bash
git add blog-common/src/main/java/dowob/xyz/blog/common/api/dto/AuthorSummary.java \
        blog-module-comment/src/
git commit -m "$(cat <<'EOF'
refactor(common): AuthorSummary 移到 blog-common/api/dto/

- 跨模組共享 DTO 不該綁特定模組
- comment 模組改 import；準備給 series 模組使用
- 純 import refactor，無邏輯變動

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: V15 Migration

**Files:**
- Create: `blog-db-migration/src/main/resources/db/migration/V15__add_article_series_and_rename_likes.sql`

- [ ] **Step 1: Write V15 SQL**

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
    author_id       BIGINT       NOT NULL REFERENCES users(id),
    article_count   INTEGER      NOT NULL DEFAULT 0,
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

ALTER TABLE user_article_likes
    RENAME CONSTRAINT uq_article_likes_user_article
    TO uq_user_article_likes_user_article;
```

- [ ] **Step 2: 跑既有 article IT 驗證 migration（暫不會通過 — 因為 ArticleLike entity 還是 @Table("article_likes")）**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleControllerIT 2>&1 | tee logs/v15-validate.log
```

Expected: **預期失敗**（Article + ArticleLike entity 還沒對齊新 schema）。先看 Flyway migration 訊息，確認 V15 套用成功；測試失敗是 entity 沒改 — Task 3 會修。

如果想驗證 V15 SQL 沒錯：手動連 dev DB 跑 `V15` SQL 看是否成功；或單獨跑 `flyway:migrate` goal。

- [ ] **Step 3: Commit**

```bash
git add blog-db-migration/src/main/resources/db/migration/V15__add_article_series_and_rename_likes.sql
git commit -m "$(cat <<'EOF'
feat(series): V15 migration — series 新建 + ALTER articles + rename article_likes

- 新建 series 表（含 article_count 反正規化欄位）
- articles 加 series_id (FK ON DELETE SET NULL) + series_position
- article_likes 改名 user_article_likes（命名一致化）
- partial index idx_articles_series_position 供 series 內 articles 排序撈
- ALTER TABLE RENAME CONSTRAINT 處理 UNIQUE constraint 同步

⚠ 此 commit 後既有 ArticleLike entity (@Table("article_likes")) 將失敗，
  Task 3 搬移到 reading 模組時同步修正 @Table 值。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: ArticleLike 搬到 reading 模組

**Files:**
- Move: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/ArticleLike.java` → `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/model/ArticleLike.java`
- Move: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/repository/ArticleLikeRepository.java` → `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/repository/ArticleLikeRepository.java`
- Move: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleLikeService.java` → `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/ArticleLikeService.java`
- Move: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/controller/ArticleLikeController.java` → `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/controller/ArticleLikeController.java`
- Move: 兩個 test 檔（ArticleLikeServiceTest, ArticleLikeControllerIT）
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/mapper/ArticleLikeMapper.java`（從 ArticleMapper 抽出 batch query）
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/mapper/ArticleMapper.java`（移除 findLikedArticleIdsByUser）

- [ ] **Step 1: 建立新位置檔案（複製 + 改 package + 改 @Table）**

對每個檔案：
- 改第一行 `package` 為 reading 模組
- 改所有跨模組 import（如有用到 ArticleService 等繼續引用 article 模組）
- ArticleLike entity 的 `@Table("article_likes")` 改 `@Table("user_article_likes")`

ArticleLike.java（新位置）：

```java
package dowob.xyz.blog.module.reading.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user_article_likes")     // OLD: "article_likes"
public class ArticleLike {
    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("article_id")
    private Long articleId;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;
}
```

ArticleLikeRepository.java（新位置，幾乎不變）：

```java
package dowob.xyz.blog.module.reading.repository;

import dowob.xyz.blog.module.reading.model.ArticleLike;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ArticleLikeRepository extends CrudRepository<ArticleLike, Long> {
    Optional<ArticleLike> findByUserIdAndArticleId(Long userId, Long articleId);
    void deleteByUserIdAndArticleId(Long userId, Long articleId);
}
```

ArticleLikeService.java（新位置；既有邏輯保留）：

```java
package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.reading.mapper.ArticleLikeMapper;        // NEW
import dowob.xyz.blog.module.reading.model.ArticleLike;
import dowob.xyz.blog.module.reading.repository.ArticleLikeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ArticleLikeService {

    private final ArticleLikeRepository likeRepo;
    private final ArticleLikeMapper articleLikeMapper;        // CHANGED FROM ArticleMapper
    private final ArticleService articleService;

    @Transactional
    public void likeArticle(Long userId, Long articleId) {
        if (likeRepo.findByUserIdAndArticleId(userId, articleId).isPresent()) return;

        ArticleLike like = new ArticleLike();
        like.setUserId(userId);
        like.setArticleId(articleId);
        like.setCreatedAt(LocalDateTime.now());
        likeRepo.save(like);
        articleService.incrementLikeCount(articleId);
    }

    @Transactional
    public void unlikeArticle(Long userId, Long articleId) {
        if (likeRepo.findByUserIdAndArticleId(userId, articleId).isEmpty()) return;
        likeRepo.deleteByUserIdAndArticleId(userId, articleId);
        articleService.decrementLikeCount(articleId);
    }

    public boolean isLiked(Long userId, Long articleId) {
        return likeRepo.findByUserIdAndArticleId(userId, articleId).isPresent();
    }

    public Set<Long> batchIsLiked(Long userId, List<Long> articleIds) {
        if (userId == null || articleIds == null || articleIds.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(articleLikeMapper.findLikedArticleIdsByUser(userId, articleIds));
    }
}
```

ArticleLikeController.java（新位置；URL 不變 `/api/v1/articles/{uuid}/like`）：

```java
package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.reading.service.ArticleLikeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/articles/{articleUuid}/like")
@RequiredArgsConstructor
@Tag(name = "Article Like")
public class ArticleLikeController {

    private final ArticleLikeService likeService;
    private final ArticleService articleService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "按讚文章（idempotent）")
    public ApiResponse<Void> like(@AuthenticationPrincipal Long userId,
                                    @PathVariable UUID articleUuid) {
        Long articleId = articleService.findIdByUuid(articleUuid);
        likeService.likeArticle(userId, articleId);
        return ApiResponse.success();
    }

    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "取消按讚（idempotent）")
    public ApiResponse<Void> unlike(@AuthenticationPrincipal Long userId,
                                      @PathVariable UUID articleUuid) {
        Long articleId = articleService.findIdByUuid(articleUuid);
        likeService.unlikeArticle(userId, articleId);
        return ApiResponse.success();
    }
}
```

- [ ] **Step 2: 抽出 ArticleLikeMapper（從 ArticleMapper.findLikedArticleIdsByUser 移動）**

`blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/mapper/ArticleLikeMapper.java`:

```java
package dowob.xyz.blog.module.reading.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Article Like MyBatis Mapper - batch is-liked 查詢。
 *
 * <p>從 ArticleMapper 抽出，跟著 ArticleLike service 一起搬到 reading 模組。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface ArticleLikeMapper {

    @Select({
        "<script>",
        "SELECT article_id FROM user_article_likes",   // 改新表名
        " WHERE user_id = #{userId}",
        "   AND article_id IN",
        "<foreach collection='articleIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "</script>"
    })
    List<Long> findLikedArticleIdsByUser(@Param("userId") Long userId,
                                          @Param("articleIds") List<Long> articleIds);
}
```

- [ ] **Step 3: 移除 ArticleMapper 中對應方法 + 既有 ArticleLikeService 的 ArticleMapper inject**

於 `blog-module-article/src/main/java/dowob/xyz/blog/module/article/mapper/ArticleMapper.java`：
- 找到 `findLikedArticleIdsByUser` 方法宣告，刪除整個 method（包含 javadoc 跟 @Select 註解）

- [ ] **Step 4: 搬移測試檔（ArticleLikeServiceTest 跟 ArticleLikeControllerIT）**

```bash
# 搬移檔案位置 + 改 package 在每個檔案 first line + 改 import 路徑
# ArticleLikeServiceTest 中 inject 從 ArticleMapper 改成 ArticleLikeMapper
```

兩個 test 檔的內容大致照舊；只需改：
- package 宣告
- import path（從 article 模組改成 reading 模組）
- ArticleLikeServiceTest 中 mock 從 `ArticleMapper articleMapper` 改成 `ArticleLikeMapper articleLikeMapper`

ArticleLikeControllerIT 需要繼承 `ReadingTestApplication`（既有 reading 模組 IT 用的 test app）。

- [ ] **Step 5: 更新 MyBatisConfig @MapperScan**

`blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/config/MyBatisConfig.java`：

```java
@MapperScan(basePackages = {
        "dowob.xyz.blog.module.user.mapper",
        "dowob.xyz.blog.module.article.mapper",
        "dowob.xyz.blog.module.tag.mapper",
        "dowob.xyz.blog.module.file.mapper",
        "dowob.xyz.blog.module.comment.mapper",
        "dowob.xyz.blog.module.reading.mapper"     // 已含（含 ArticleLikeMapper）
})
```

reading 模組 mapper package 已存在，無需改。

- [ ] **Step 6: 跑 reading + article 模組測試**

```bash
./mvnw.cmd -pl blog-module-reading,blog-module-article -am test 2>&1 | tee logs/t3-articlelike-move.log
```

Expected: 全綠。ArticleLikeServiceTest 8 + ArticleLikeControllerIT 5 全部從 article 模組跑變成 reading 模組跑。

- [ ] **Step 7: 移除 article 模組舊檔**

```bash
rm blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/ArticleLike.java
rm blog-module-article/src/main/java/dowob/xyz/blog/module/article/repository/ArticleLikeRepository.java
rm blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleLikeService.java
rm blog-module-article/src/main/java/dowob/xyz/blog/module/article/controller/ArticleLikeController.java
rm blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleLikeServiceTest.java
rm blog-module-article/src/test/java/dowob/xyz/blog/module/article/controller/ArticleLikeControllerIT.java
```

跑 article 模組測試確認無 dangling references：

```bash
./mvnw.cmd -pl blog-module-article test 2>&1 | tee logs/t3-article-after-remove.log
```

可能 fail：ArticleQueryService 還引用 ArticleLikeService → Task 5 處理。本 task 暫時可能編譯失敗於 ArticleQueryService，暫時 inline comment 該行（`// TODO Task 5`）讓編譯通過，或讓 article 編譯失敗、Task 5 修正。

簡化做法：**本 task 不刪除 article 模組舊檔**（讓 Task 5 改完 ArticleQueryService 後再刪）。改成 step 7 略過，留給 Task 5。

- [ ] **Step 8: Commit**

```bash
git add blog-module-reading/src/ \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/mapper/ArticleMapper.java
git commit -m "$(cat <<'EOF'
refactor(reading): ArticleLike 從 article 模組搬到 reading 模組

- ArticleLike entity / repository / service / controller 搬移
- @Table("article_likes") → @Table("user_article_likes")
- 從 ArticleMapper 抽出 ArticleLikeMapper（batch is-liked SQL）
- ArticleLikeService 改 inject ArticleLikeMapper（不再依賴 ArticleMapper）
- URL 路徑保留 /api/v1/articles/{uuid}/like（前端不破壞）
- 既有 13 個測試（8 unit + 5 IT）跟著搬到 reading 模組

⚠ ArticleQueryService 仍 inject ArticleLikeService — Task 5 改用 ReadingFacade。
   article 模組舊 ArticleLike 檔案 Task 5 後刪除。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: ReadingFacade 加 batchIsLiked / isLiked

**Files:**
- Modify: `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/ReadingFacade.java`
- Modify: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/facade/ReadingFacadeImpl.java`
- Modify: `blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/facade/ReadingFacadeImplTest.java`（既有，加 2 tests）

- [ ] **Step 1: 加 ReadingFacade interface 兩個方法**

```java
// blog-infrastructure/.../facade/ReadingFacade.java
public interface ReadingFacade {
    // 既有方法保留 ...

    /**
     * 批次查詢使用者按讚過的文章 ID。
     * @param userId 使用者 ID（null 代表未登入，回 emptySet）
     * @param articleIds 候選 article id list
     * @return 已按讚的 article id 集合
     */
    Set<Long> batchIsLiked(Long userId, List<Long> articleIds);

    /**
     * 查詢單篇文章是否被當前使用者按讚。
     */
    boolean isLiked(Long userId, Long articleId);
}
```

- [ ] **Step 2: 實作 ReadingFacadeImpl 兩個方法（delegate 到 ArticleLikeService）**

```java
// blog-module-reading/.../facade/ReadingFacadeImpl.java（既有，加方法）
@Service
@RequiredArgsConstructor
public class ReadingFacadeImpl implements ReadingFacade {

    private final BookmarkService bookmarkService;
    private final ReadingProgressService readingProgressService;
    private final ArticleLikeService articleLikeService;        // NEW

    // 既有方法 ...

    @Override
    public Set<Long> batchIsLiked(Long userId, List<Long> articleIds) {
        return articleLikeService.batchIsLiked(userId, articleIds);
    }

    @Override
    public boolean isLiked(Long userId, Long articleId) {
        return articleLikeService.isLiked(userId, articleId);
    }
}
```

- [ ] **Step 3: 加 2 unit tests 到 ReadingFacadeImplTest**

```java
@Test
void batchIsLiked_delegatesToArticleLikeService() {
    when(articleLikeService.batchIsLiked(eq(1L), eq(List.of(10L, 20L))))
        .thenReturn(Set.of(10L));

    Set<Long> result = facade.batchIsLiked(1L, List.of(10L, 20L));

    assertThat(result).containsExactly(10L);
    verify(articleLikeService).batchIsLiked(1L, List.of(10L, 20L));
}

@Test
void isLiked_delegatesToArticleLikeService() {
    when(articleLikeService.isLiked(1L, 10L)).thenReturn(true);

    assertThat(facade.isLiked(1L, 10L)).isTrue();
    verify(articleLikeService).isLiked(1L, 10L);
}
```

⚠ 既有測試類別中 mock list 要加 `@Mock ArticleLikeService articleLikeService`。

- [ ] **Step 4: Run**

```bash
./mvnw.cmd -pl blog-module-reading test -Dtest=ReadingFacadeImplTest 2>&1 | tee logs/t4-readingfacade.log
```

Expected: 既有 + 2 新 tests pass。

- [ ] **Step 5: Commit**

```bash
git add blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/ReadingFacade.java \
        blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/facade/ReadingFacadeImpl.java \
        blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/facade/ReadingFacadeImplTest.java
git commit -m "$(cat <<'EOF'
feat(reading): ReadingFacade 加 batchIsLiked / isLiked

- 把 ArticleLike 升級成 facade-level read 方法
- 配合 Task 5：ArticleQueryService 統一從 ReadingFacade 取 liked / bookmarked / progress
- 2 個新 unit test 覆蓋 delegate 行為

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: ArticleQueryService 改用 ReadingFacade.batchIsLiked

**Files:**
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleQueryService.java`
- Modify: `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleQueryServiceTest.java`
- Delete (finally): `blog-module-article/src/main/java/dowob/xyz/blog/module/article/{model,repository,service,controller}/ArticleLike*.java`（Task 3 留下的）

- [ ] **Step 1: ArticleQueryService 移除 ArticleLikeService inject，改 ReadingFacade**

找到 ArticleQueryService 的 inject 區，改：

```java
// OLD
private final ArticleLikeService articleLikeService;

// NEW（移除上面，因為 readingFacade 已含 batchIsLiked）
// ReadingFacade 既有 inject 已足夠
```

`enrichLikedList` / 其他方法中 `articleLikeService.batchIsLiked(...)` 改成 `readingFacade.batchIsLiked(...)`。

`enrichSingle` 中 `articleLikeService.isLiked(...)` 改成 `readingFacade.isLiked(...)`。

- [ ] **Step 2: 移除 article 模組殘留的 ArticleLike 檔案**

```bash
rm blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/ArticleLike.java
rm blog-module-article/src/main/java/dowob/xyz/blog/module/article/repository/ArticleLikeRepository.java
rm blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleLikeService.java
rm blog-module-article/src/main/java/dowob/xyz/blog/module/article/controller/ArticleLikeController.java
```

- [ ] **Step 3: 修 ArticleQueryServiceTest（mock 改用 ReadingFacade）**

找到測試類別 mock 區：
- 移除 `@Mock ArticleLikeService articleLikeService`（如果有）
- 確保 `@Mock ReadingFacade readingFacade` 已存在

測試方法中 stubbing：
- `when(articleLikeService.batchIsLiked(...))` → `when(readingFacade.batchIsLiked(...))`
- `when(articleLikeService.isLiked(...))` → `when(readingFacade.isLiked(...))`

- [ ] **Step 4: Run article 模組所有測試**

```bash
./mvnw.cmd -pl blog-module-article -am test 2>&1 | tee logs/t5-article-after-refactor.log
```

Expected: 既有測試全綠。

- [ ] **Step 5: Run reading 模組測試（驗證搬家結果）**

```bash
./mvnw.cmd -pl blog-module-reading test 2>&1 | tee logs/t5-reading-verify.log
```

Expected: ArticleLikeServiceTest 8 + ArticleLikeControllerIT 5 + 既有 reading 模組 38 全綠。

- [ ] **Step 6: Commit**

```bash
git add blog-module-article/src/
git commit -m "$(cat <<'EOF'
refactor(article): ArticleQueryService 改用 ReadingFacade.batchIsLiked / isLiked

- 統一三個 enrich 欄位（liked / bookmarked / progress）走 ReadingFacade
- 移除 article 模組殘留 ArticleLike entity / repo / service / controller
- ArticleQueryServiceTest 對應 mock 改用 ReadingFacade

完成 ArticleLike 搬家收尾；article 模組責任 shrink，reading 模組責任 expand。

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: blog-module-series 模組骨架

**Files:**
- Create: `blog-module-series/pom.xml`
- Modify: `pom.xml` (root)
- Modify: `blog-start/pom.xml`
- Modify: `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/config/MyBatisConfig.java`

- [ ] **Step 1: 建模組目錄與 pom.xml**

```bash
cd D:/end/workspace/java/blog-web-v2/.worktrees/feature-article-series
mkdir -p blog-module-series/src/main/java/dowob/xyz/blog/module/series/{controller,service,repository,mapper,model/dto/request,model/dto/response,exception,facade,config}
mkdir -p blog-module-series/src/test/java/dowob/xyz/blog/module/series/{config,service,controller,integration}
mkdir -p blog-module-series/src/test/resources/db/testdata
```

`blog-module-series/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <parent>
        <artifactId>blog</artifactId>
        <groupId>dowob.xyz</groupId>
        <version>1.0</version>
    </parent>
    <modelVersion>4.0.0</modelVersion>

    <artifactId>blog-module-series</artifactId>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <includes>
                        <include>**/*Test.java</include>
                        <include>**/*Tests.java</include>
                        <include>**/*IT.java</include>
                    </includes>
                </configuration>
            </plugin>
        </plugins>
    </build>

    <dependencies>
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>blog-infrastructure</artifactId>
        </dependency>
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>blog-module-article</artifactId>
        </dependency>

        <!-- Test Dependencies -->
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>blog-db-migration</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.redis</groupId>
            <artifactId>testcontainers-redis</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: 註冊到 root pom.xml `<modules>`**

於 `pom.xml` 找 `<modules>`，在 `blog-module-reading` 之後 / `blog-start` 之前加：

```xml
<module>blog-module-series</module>
```

於 root pom `<dependencyManagement>` 的 Internal Modules 區塊加：

```xml
<dependency>
    <groupId>dowob.xyz</groupId>
    <artifactId>blog-module-series</artifactId>
    <version>${blog.version}</version>
</dependency>
```

- [ ] **Step 3: 加 dependency 到 blog-start/pom.xml**

```xml
<dependency>
    <groupId>dowob.xyz</groupId>
    <artifactId>blog-module-series</artifactId>
</dependency>
```

放在 `blog-module-reading` 之後。

- [ ] **Step 4: 更新 MyBatisConfig @MapperScan**

於 `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/config/MyBatisConfig.java` 的 `@MapperScan basePackages` 加：

```java
"dowob.xyz.blog.module.series.mapper"
```

⚠ **批 1/2 都踩過這坑（缺新模組 mapper package 導致 E2E 失敗）**。本 step 必做。

- [ ] **Step 5: 驗證編譯**

```bash
./mvnw.cmd -pl blog-module-series -am compile 2>&1 | tee logs/t6-series-skeleton.log
```

Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add blog-module-series/pom.xml pom.xml blog-start/pom.xml \
        blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/config/MyBatisConfig.java
git commit -m "$(cat <<'EOF'
feat(series): 新增 blog-module-series 模組骨架

- 註冊到 root pom modules + dependencyManagement
- 依賴 blog-infrastructure / blog-module-article
- 加入 blog-start dependency
- MyBatisConfig @MapperScan 加入 series.mapper 避免 E2E 失敗（批 1/2 教訓）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Series entity + Article entity 加 series 欄位 + Repository

**Files:**
- Create: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/model/Series.java`
- Create: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/repository/SeriesRepository.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/Article.java`

- [ ] **Step 1: Series.java entity**

```java
package dowob.xyz.blog.module.series.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 系列文 (Series) 實體（series 表映射）。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("series")
public class Series {
    @Id
    private Long id;

    private UUID uuid;            // setUuid before save

    private String title;

    private String slug;

    private String description;

    @Column("cover_image_url")
    private String coverImageUrl;

    @Column("author_id")
    private Long authorId;

    @Column("article_count")
    private Integer articleCount;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: SeriesRepository.java**

```java
package dowob.xyz.blog.module.series.repository;

import dowob.xyz.blog.module.series.model.Series;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SeriesRepository extends CrudRepository<Series, Long> {
    Optional<Series> findByUuid(UUID uuid);
    Optional<Series> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
```

- [ ] **Step 3: Article entity 加 seriesId / seriesPosition**

於 `blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/Article.java` 加 2 個欄位（依 entity 既有風格擺在合適位置）：

```java
@Column("series_id")
private Long seriesId;            // nullable

@Column("series_position")
private Integer seriesPosition;   // nullable
```

- [ ] **Step 4: 編譯驗證**

```bash
./mvnw.cmd -pl blog-module-series,blog-module-article -am compile 2>&1 | tee logs/t7-entities.log
```

Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add blog-module-series/src/main/java/dowob/xyz/blog/module/series/model/Series.java \
        blog-module-series/src/main/java/dowob/xyz/blog/module/series/repository/SeriesRepository.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/Article.java
git commit -m "$(cat <<'EOF'
feat(series): Series entity + Repository + Article 加 series 欄位

- Series entity 對齊既有慣例（@CreatedDate / @LastModifiedDate / @Column）
- SeriesRepository CrudRepository + findByUuid / findBySlug / existsBySlug
- Article entity 加 seriesId / seriesPosition (nullable)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: SeriesErrorCode + DTOs

**Files:**
- Create: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/exception/SeriesErrorCode.java`
- Create: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/model/dto/request/{CreateSeriesRequest, UpdateSeriesRequest, AddArticleToSeriesRequest}.java`
- Create: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/model/dto/response/{SeriesSummaryResponse, SeriesDetailResponse, MyProgress}.java`
- Create: `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/dto/SeriesNavigation.java`

- [ ] **Step 1: SeriesErrorCode.java**

```java
package dowob.xyz.blog.module.series.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SeriesErrorCode implements IErrorCode {

    SERIES_NOT_FOUND("S0101", "Series 不存在"),
    SERIES_ACCESS_DENIED("S0102", "不可變更他人的 Series"),
    ARTICLE_NOT_PUBLISHED("S0103", "文章必須為 PUBLISHED 才能加入 Series"),
    SLUG_ALREADY_USED("S0104", "Slug 已被使用"),
    ARTICLE_NOT_IN_SERIES("S0105", "文章不屬於此 Series"),
    ARTICLE_IN_OTHER_SERIES("S0106", "文章已屬於另一個 Series");

    private final String code;
    private final String message;
}
```

- [ ] **Step 2: CreateSeriesRequest.java**

```java
package dowob.xyz.blog.module.series.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateSeriesRequest {
    @NotBlank @Size(max = 255)
    private String title;

    @NotBlank @Size(max = 255)
    @Pattern(regexp = "^[a-z0-9-]+$")
    private String slug;

    private String description;

    @Size(max = 512)
    private String coverImageUrl;
}
```

- [ ] **Step 3: UpdateSeriesRequest.java**

```java
package dowob.xyz.blog.module.series.model.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Series 部分更新請求；所有欄位 optional，僅當非 null 才更新。
 */
@Data
public class UpdateSeriesRequest {
    @Size(max = 255)
    private String title;

    @Size(max = 255)
    @Pattern(regexp = "^[a-z0-9-]+$")
    private String slug;

    private String description;

    @Size(max = 512)
    private String coverImageUrl;
}
```

- [ ] **Step 4: AddArticleToSeriesRequest.java**

```java
package dowob.xyz.blog.module.series.model.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddArticleToSeriesRequest {
    @NotNull
    @Min(1)
    private Integer position;
}
```

- [ ] **Step 5: SeriesSummaryResponse.java**

```java
package dowob.xyz.blog.module.series.model.dto.response;

import dowob.xyz.blog.common.api.dto.AuthorSummary;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class SeriesSummaryResponse {
    private UUID uuid;
    private String title;
    private String slug;
    private String description;
    private String coverImageUrl;
    private AuthorSummary author;
    private Integer articleCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 6: MyProgress.java**

```java
package dowob.xyz.blog.module.series.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MyProgress {
    private Integer readCount;
    private Integer totalCount;
    private UUID nextUnreadArticleUuid;
}
```

- [ ] **Step 7: SeriesDetailResponse.java**

```java
package dowob.xyz.blog.module.series.model.dto.response;

import dowob.xyz.blog.common.api.dto.AuthorSummary;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class SeriesDetailResponse {
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
```

- [ ] **Step 8: SeriesNavigation.java（在 infrastructure，給 SeriesFacade interface 用）**

```java
package dowob.xyz.blog.infrastructure.facade.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Series 內文章導覽資訊（給 ArticleResponse.seriesNav 用）。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeriesNavigation {
    private UUID seriesUuid;
    private String seriesTitle;
    private String seriesSlug;
    private Integer position;
    private Integer totalCount;
    private SeriesArticleRef prev;
    private SeriesArticleRef next;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeriesArticleRef {
        private UUID uuid;
        private String title;
        private String slug;
    }
}
```

- [ ] **Step 9: 編譯**

```bash
./mvnw.cmd -pl blog-module-series,blog-infrastructure -am compile 2>&1 | tee logs/t8-dtos.log
```

Expected: BUILD SUCCESS.

- [ ] **Step 10: Commit**

```bash
git add blog-module-series/src/main/java/dowob/xyz/blog/module/series/exception/ \
        blog-module-series/src/main/java/dowob/xyz/blog/module/series/model/dto/ \
        blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/dto/SeriesNavigation.java
git commit -m "$(cat <<'EOF'
feat(series): SeriesErrorCode + DTOs

- SeriesErrorCode S0101-S0106
- CreateSeriesRequest / UpdateSeriesRequest / AddArticleToSeriesRequest
- SeriesSummaryResponse / SeriesDetailResponse / MyProgress
- SeriesNavigation 在 infrastructure（給 SeriesFacade interface 用）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: SeriesMapper

**Files:**
- Create: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/model/SeriesWithAuthor.java`
- Create: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/mapper/SeriesMapper.java`

- [ ] **Step 1: SeriesWithAuthor.java（MyBatis row mapping for JOIN users）**

```java
package dowob.xyz.blog.module.series.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class SeriesWithAuthor {
    private Long id;
    private UUID uuid;
    private String title;
    private String slug;
    private String description;
    private String coverImageUrl;
    private Long authorId;
    private Integer articleCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private UUID authorUuid;
    private String authorNickname;
    private String authorAvatarUrl;
}
```

- [ ] **Step 2: SeriesMapper.java**

```java
package dowob.xyz.blog.module.series.mapper;

import dowob.xyz.blog.module.series.model.SeriesWithAuthor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SeriesMapper {

    /** 列表（公開）：只列 article_count > 0 的 series，最新優先 */
    @Select("""
            SELECT s.id, s.uuid, s.title, s.slug, s.description, s.cover_image_url,
                   s.author_id, s.article_count, s.created_at, s.updated_at,
                   u.uuid AS author_uuid, u.nickname AS author_nickname, u.avatar_url AS author_avatar_url
              FROM series s LEFT JOIN users u ON s.author_id = u.id
             WHERE s.article_count > 0
             ORDER BY s.created_at DESC
             LIMIT #{size} OFFSET #{offset}
            """)
    List<SeriesWithAuthor> findPublic(@Param("size") int size, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM series WHERE article_count > 0")
    long countPublic();

    /** 單篇 by slug（公開） */
    @Select("""
            SELECT s.id, s.uuid, s.title, s.slug, s.description, s.cover_image_url,
                   s.author_id, s.article_count, s.created_at, s.updated_at,
                   u.uuid AS author_uuid, u.nickname AS author_nickname, u.avatar_url AS author_avatar_url
              FROM series s LEFT JOIN users u ON s.author_id = u.id
             WHERE s.slug = #{slug}
            """)
    SeriesWithAuthor findBySlugWithAuthor(@Param("slug") String slug);

    /** 反正規化 +1 */
    @Update("UPDATE series SET article_count = article_count + 1 WHERE id = #{id}")
    int incrementArticleCount(@Param("id") Long id);

    /** 反正規化 -1（守衛 > 0） */
    @Update("UPDATE series SET article_count = article_count - 1 WHERE id = #{id} AND article_count > 0")
    int decrementArticleCount(@Param("id") Long id);

    /**
     * 撈 series 內某 article 的 prev/next（按 series_position 排序）。
     *
     * @return List(0)=prev, List(1)=current, List(2)=next；可能不足
     */
    @Select("""
            SELECT id, uuid, title, slug, series_position
              FROM articles
             WHERE series_id = #{seriesId} AND status = 'PUBLISHED'
             ORDER BY series_position
            """)
    List<NavRow> findArticlesForNav(@Param("seriesId") Long seriesId);

    @lombok.Data
    class NavRow {
        private Long id;
        private java.util.UUID uuid;
        private String title;
        private String slug;
        private Integer seriesPosition;
    }
}
```

⚠ NavRow 用 inner class 簡化；如果跑起來 MyBatis row mapping 出問題，移到獨立 model class。

- [ ] **Step 3: 編譯**

```bash
./mvnw.cmd -pl blog-module-series -am compile 2>&1 | tee logs/t9-mapper.log
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add blog-module-series/src/main/java/dowob/xyz/blog/module/series/mapper/ \
        blog-module-series/src/main/java/dowob/xyz/blog/module/series/model/SeriesWithAuthor.java
git commit -m "$(cat <<'EOF'
feat(series): SeriesMapper（MyBatis JOIN users + 反正規化 update + nav 查詢）

- findPublic / countPublic：公開列表（只列 article_count > 0）
- findBySlugWithAuthor：詳情
- incrementArticleCount / decrementArticleCount：反正規化計數
- findArticlesForNav：撈 series 內 PUBLISHED articles 給 SeriesFacade.getSeriesNavigation 用

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: SeriesService.createSeries / updateSeries / deleteSeries (TDD)

**Files:**
- Create: `blog-module-series/src/test/java/dowob/xyz/blog/module/series/service/SeriesServiceTest.java`
- Create: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/service/SeriesService.java`

- [ ] **Step 1: SeriesServiceTest（先寫 5 個 CRUD test）**

```java
package dowob.xyz.blog.module.series.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.series.exception.SeriesErrorCode;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.model.dto.request.CreateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.request.UpdateSeriesRequest;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeriesServiceTest {

    @Mock private SeriesRepository repo;
    @Mock private SeriesMapper mapper;
    // ... 其他 mock 在後面 task 補（ArticleService / ReadingFacade）...
    @InjectMocks private SeriesService service;

    private final Long userId = 1L;
    private final Long seriesId = 100L;
    private final UUID seriesUuid = UUID.randomUUID();

    @Test
    void createSeries_validRequest_savesWithUuidAndDefaults() {
        when(repo.existsBySlug("vue-101")).thenReturn(false);
        when(repo.save(any(Series.class))).thenAnswer(inv -> {
            Series s = inv.getArgument(0);
            s.setId(seriesId);
            return s;
        });

        CreateSeriesRequest req = new CreateSeriesRequest();
        req.setTitle("Vue 101");
        req.setSlug("vue-101");
        req.setDescription("intro");

        service.createSeries(userId, req);

        ArgumentCaptor<Series> captor = ArgumentCaptor.forClass(Series.class);
        verify(repo).save(captor.capture());
        Series saved = captor.getValue();
        assertThat(saved.getUuid()).isNotNull();
        assertThat(saved.getAuthorId()).isEqualTo(userId);
        assertThat(saved.getTitle()).isEqualTo("Vue 101");
        assertThat(saved.getSlug()).isEqualTo("vue-101");
        assertThat(saved.getArticleCount()).isEqualTo(0);
    }

    @Test
    void createSeries_duplicateSlug_throwsS0104() {
        when(repo.existsBySlug("vue-101")).thenReturn(true);

        CreateSeriesRequest req = new CreateSeriesRequest();
        req.setTitle("Vue 101");
        req.setSlug("vue-101");

        assertThatThrownBy(() -> service.createSeries(userId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SLUG_ALREADY_USED.getMessage());

        verify(repo, never()).save(any());
    }

    @Test
    void updateSeries_byOwner_updatesAllFields() {
        Series existing = new Series();
        existing.setId(seriesId); existing.setUuid(seriesUuid);
        existing.setAuthorId(userId);
        existing.setTitle("old"); existing.setSlug("old-slug");
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing));
        when(repo.existsBySlug("new-slug")).thenReturn(false);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("new");
        req.setSlug("new-slug");

        service.updateSeries(seriesUuid, userId, false, req);

        ArgumentCaptor<Series> captor = ArgumentCaptor.forClass(Series.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("new");
        assertThat(captor.getValue().getSlug()).isEqualTo("new-slug");
    }

    @Test
    void updateSeries_byNonOwnerNonAdmin_throwsS0102() {
        Series existing = new Series();
        existing.setId(seriesId); existing.setUuid(seriesUuid);
        existing.setAuthorId(999L);    // 別人的
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing));

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("hijack");

        assertThatThrownBy(() -> service.updateSeries(seriesUuid, userId, false, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_ACCESS_DENIED.getMessage());

        verify(repo, never()).save(any());
    }

    @Test
    void updateSeries_byAdmin_succeeds() {
        Series existing = new Series();
        existing.setId(seriesId); existing.setUuid(seriesUuid);
        existing.setAuthorId(999L);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("admin override");

        service.updateSeries(seriesUuid, userId, /* isAdmin */ true, req);

        verify(repo).save(any());
    }

    @Test
    void deleteSeries_byOwner_setsArticlesSeriesIdNull() {
        Series existing = new Series();
        existing.setId(seriesId); existing.setUuid(seriesUuid);
        existing.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing));

        service.deleteSeries(seriesUuid, userId, false);

        verify(repo).deleteById(seriesId);
        // ON DELETE SET NULL 由 DB 處理；service 不需顯式 update articles
    }
}
```

- [ ] **Step 2: Run red**

```bash
./mvnw.cmd -pl blog-module-series test -Dtest=SeriesServiceTest 2>&1 | tee logs/t10-red.log
```

Expected: 編譯失敗（SeriesService 不存在）。

- [ ] **Step 3: 實作 SeriesService.java（先做 CRUD 部分，add/remove article 後面 task）**

```java
package dowob.xyz.blog.module.series.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.series.exception.SeriesErrorCode;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.model.dto.request.CreateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.request.UpdateSeriesRequest;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SeriesService {

    private final SeriesRepository repo;
    private final SeriesMapper mapper;

    @Transactional
    public Series createSeries(Long userId, CreateSeriesRequest req) {
        if (repo.existsBySlug(req.getSlug())) {
            throw new BusinessException(SeriesErrorCode.SLUG_ALREADY_USED);
        }
        Series s = new Series();
        s.setUuid(UUID.randomUUID());
        s.setTitle(req.getTitle());
        s.setSlug(req.getSlug());
        s.setDescription(req.getDescription());
        s.setCoverImageUrl(req.getCoverImageUrl());
        s.setAuthorId(userId);
        s.setArticleCount(0);
        return repo.save(s);
    }

    @Transactional
    public Series updateSeries(UUID seriesUuid, Long userId, boolean isAdmin, UpdateSeriesRequest req) {
        Series s = repo.findByUuid(seriesUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        if (!isAdmin && !s.getAuthorId().equals(userId)) {
            throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
        }
        if (req.getTitle() != null) s.setTitle(req.getTitle());
        if (req.getSlug() != null && !req.getSlug().equals(s.getSlug())) {
            if (repo.existsBySlug(req.getSlug())) {
                throw new BusinessException(SeriesErrorCode.SLUG_ALREADY_USED);
            }
            s.setSlug(req.getSlug());
        }
        if (req.getDescription() != null) s.setDescription(req.getDescription());
        if (req.getCoverImageUrl() != null) s.setCoverImageUrl(req.getCoverImageUrl());
        return repo.save(s);
    }

    @Transactional
    public void deleteSeries(UUID seriesUuid, Long userId, boolean isAdmin) {
        Series s = repo.findByUuid(seriesUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        if (!isAdmin && !s.getAuthorId().equals(userId)) {
            throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
        }
        repo.deleteById(s.getId());
    }
}
```

- [ ] **Step 4: Run green**

```bash
./mvnw.cmd -pl blog-module-series test -Dtest=SeriesServiceTest 2>&1 | tee logs/t10-green.log
```

Expected: 6 tests pass。

- [ ] **Step 5: Commit**

```bash
git add blog-module-series/src/main/java/dowob/xyz/blog/module/series/service/SeriesService.java \
        blog-module-series/src/test/java/dowob/xyz/blog/module/series/service/SeriesServiceTest.java
git commit -m "$(cat <<'EOF'
feat(series): SeriesService CRUD 實作 + TDD

- createSeries / updateSeries / deleteSeries
- slug 衝突檢查 (S0104)
- 擁有者隔離 (S0102) + Admin override
- 6 個 unit test

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: SeriesService.addArticleToSeries / removeArticleFromSeries (TDD)

**Files:**
- Modify: `blog-module-series/src/test/java/dowob/xyz/blog/module/series/service/SeriesServiceTest.java`（加 5 tests）
- Modify: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/service/SeriesService.java`（加 2 methods + inject ArticleService）

- [ ] **Step 1: 加 5 個 add/remove test**

於 SeriesServiceTest 既有測試類別內加：

```java
@Mock private dowob.xyz.blog.module.article.service.ArticleService articleService;

private final UUID articleUuid = UUID.randomUUID();
private final Long articleId = 200L;

@Test
void addArticleToSeries_publishedArticle_savesAndIncrementsCount() {
    Series series = new Series();
    series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
    when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

    dowob.xyz.blog.module.article.model.Article article = new dowob.xyz.blog.module.article.model.Article();
    article.setId(articleId); article.setUuid(articleUuid);
    article.setAuthorId(userId);
    article.setStatus(dowob.xyz.blog.common.api.enums.ArticleStatus.PUBLISHED);
    article.setSeriesId(null);
    when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(article));

    service.addArticleToSeries(seriesUuid, articleUuid, userId, false, 3);

    verify(articleService).updateSeriesAssignment(articleId, seriesId, 3);
    verify(mapper).incrementArticleCount(seriesId);
}

@Test
void addArticleToSeries_draftArticle_throwsS0103() {
    Series series = new Series();
    series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
    when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

    dowob.xyz.blog.module.article.model.Article article = new dowob.xyz.blog.module.article.model.Article();
    article.setId(articleId); article.setUuid(articleUuid);
    article.setAuthorId(userId);
    article.setStatus(dowob.xyz.blog.common.api.enums.ArticleStatus.DRAFT);
    when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(article));

    assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userId, false, 1))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(SeriesErrorCode.ARTICLE_NOT_PUBLISHED.getMessage());

    verify(articleService, never()).updateSeriesAssignment(any(), any(), any());
}

@Test
void addArticleToSeries_articleAlreadyInOtherSeries_throwsS0106() {
    Series series = new Series();
    series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
    when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

    dowob.xyz.blog.module.article.model.Article article = new dowob.xyz.blog.module.article.model.Article();
    article.setId(articleId); article.setUuid(articleUuid);
    article.setAuthorId(userId);
    article.setStatus(dowob.xyz.blog.common.api.enums.ArticleStatus.PUBLISHED);
    article.setSeriesId(999L);    // 在別的 series
    when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(article));

    assertThatThrownBy(() -> service.addArticleToSeries(seriesUuid, articleUuid, userId, false, 1))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(SeriesErrorCode.ARTICLE_IN_OTHER_SERIES.getMessage());
}

@Test
void addArticleToSeries_sameSeriesUpdatesPosition() {
    Series series = new Series();
    series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
    when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

    dowob.xyz.blog.module.article.model.Article article = new dowob.xyz.blog.module.article.model.Article();
    article.setId(articleId); article.setUuid(articleUuid);
    article.setAuthorId(userId);
    article.setStatus(dowob.xyz.blog.common.api.enums.ArticleStatus.PUBLISHED);
    article.setSeriesId(seriesId);    // 已在這個 series
    article.setSeriesPosition(2);
    when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(article));

    service.addArticleToSeries(seriesUuid, articleUuid, userId, false, 5);

    verify(articleService).updateSeriesAssignment(articleId, seriesId, 5);
    verify(mapper, never()).incrementArticleCount(any());    // 已在同 series，不增加 count
}

@Test
void removeArticleFromSeries_decrementsCount() {
    Series series = new Series();
    series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
    when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

    dowob.xyz.blog.module.article.model.Article article = new dowob.xyz.blog.module.article.model.Article();
    article.setId(articleId); article.setUuid(articleUuid);
    article.setSeriesId(seriesId);
    article.setSeriesPosition(3);
    when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(article));

    service.removeArticleFromSeries(seriesUuid, articleUuid, userId, false);

    verify(articleService).updateSeriesAssignment(articleId, null, null);
    verify(mapper).decrementArticleCount(seriesId);
}

@Test
void removeArticleFromSeries_articleNotInThisSeries_throwsS0105() {
    Series series = new Series();
    series.setId(seriesId); series.setUuid(seriesUuid); series.setAuthorId(userId);
    when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(series));

    dowob.xyz.blog.module.article.model.Article article = new dowob.xyz.blog.module.article.model.Article();
    article.setId(articleId); article.setUuid(articleUuid);
    article.setSeriesId(null);    // 不在任何 series
    when(articleService.findByUuid(articleUuid)).thenReturn(Optional.of(article));

    assertThatThrownBy(() -> service.removeArticleFromSeries(seriesUuid, articleUuid, userId, false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(SeriesErrorCode.ARTICLE_NOT_IN_SERIES.getMessage());
}
```

- [ ] **Step 2: 在 ArticleService interface 加 findByUuid + updateSeriesAssignment 方法**

`blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleService.java`：

```java
java.util.Optional<dowob.xyz.blog.module.article.model.Article> findByUuid(java.util.UUID uuid);
void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition);
```

`ArticleServiceImpl.java`：

```java
@Override
public Optional<Article> findByUuid(UUID uuid) {
    return articleRepository.findByUuid(uuid);
}

@Override
@Transactional
public void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition) {
    articleRepository.findById(articleId).ifPresent(a -> {
        a.setSeriesId(seriesId);
        a.setSeriesPosition(seriesPosition);
        articleRepository.save(a);
    });
}
```

- [ ] **Step 3: SeriesService 加 add/remove methods**

```java
// SeriesService.java 加：

private final ArticleService articleService;     // inject

@Transactional
public void addArticleToSeries(UUID seriesUuid, UUID articleUuid, Long userId, boolean isAdmin, int position) {
    Series s = repo.findByUuid(seriesUuid)
            .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
    if (!isAdmin && !s.getAuthorId().equals(userId)) {
        throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
    }

    Article article = articleService.findByUuid(articleUuid)
            .orElseThrow(() -> new BusinessException(SeriesErrorCode.ARTICLE_NOT_IN_SERIES));    // re-use；嚴格說應該另外定義 ArticleNotFound
    if (article.getStatus() != ArticleStatus.PUBLISHED) {
        throw new BusinessException(SeriesErrorCode.ARTICLE_NOT_PUBLISHED);
    }
    if (!isAdmin && !article.getAuthorId().equals(userId)) {
        throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
    }
    if (article.getSeriesId() != null && !article.getSeriesId().equals(s.getId())) {
        throw new BusinessException(SeriesErrorCode.ARTICLE_IN_OTHER_SERIES);
    }

    boolean isNewMember = (article.getSeriesId() == null);
    articleService.updateSeriesAssignment(article.getId(), s.getId(), position);

    if (isNewMember) {
        mapper.incrementArticleCount(s.getId());
    }
}

@Transactional
public void removeArticleFromSeries(UUID seriesUuid, UUID articleUuid, Long userId, boolean isAdmin) {
    Series s = repo.findByUuid(seriesUuid)
            .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
    if (!isAdmin && !s.getAuthorId().equals(userId)) {
        throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
    }

    Article article = articleService.findByUuid(articleUuid)
            .orElseThrow(() -> new BusinessException(SeriesErrorCode.ARTICLE_NOT_IN_SERIES));
    if (article.getSeriesId() == null || !article.getSeriesId().equals(s.getId())) {
        throw new BusinessException(SeriesErrorCode.ARTICLE_NOT_IN_SERIES);
    }

    articleService.updateSeriesAssignment(article.getId(), null, null);
    mapper.decrementArticleCount(s.getId());
}
```

注意 import：`Article`, `ArticleStatus`, `ArticleService` from article 模組。

- [ ] **Step 4: Run**

```bash
./mvnw.cmd -pl blog-module-article -am install -DskipTests
./mvnw.cmd -pl blog-module-series test -Dtest=SeriesServiceTest 2>&1 | tee logs/t11-green.log
```

Expected: 12 tests pass（6 + 6 new）。

- [ ] **Step 5: Commit**

```bash
git add blog-module-series/src/main/java/dowob/xyz/blog/module/series/service/SeriesService.java \
        blog-module-series/src/test/java/dowob/xyz/blog/module/series/service/SeriesServiceTest.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/
git commit -m "$(cat <<'EOF'
feat(series): addArticleToSeries / removeArticleFromSeries 實作 + TDD

- 1:N 衝突檢查 (S0106)
- 文章必須 PUBLISHED (S0103)
- 文章不在 series 阻擋 (S0105)
- 反正規化 article_count（同 series 改 position 不重複扣）
- ArticleService 新增 findByUuid + updateSeriesAssignment
- 6 個新 unit test

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: SeriesService.getSeriesDetail (TDD)

**Files:**
- Modify: `SeriesServiceTest.java`（加 1 test）
- Modify: `SeriesService.java`（加 getSeriesDetail + inject ReadingFacade）

- [ ] **Step 1: 加 1 test**

```java
@Mock private dowob.xyz.blog.infrastructure.facade.ReadingFacade readingFacade;

@Test
void getSeriesDetail_authenticated_includesMyProgress() {
    // 假資料：series + 3 articles
    SeriesWithAuthor row = new SeriesWithAuthor();
    row.setId(seriesId); row.setUuid(seriesUuid);
    row.setTitle("Vue 101"); row.setSlug("vue-101");
    row.setArticleCount(3);
    row.setAuthorUuid(UUID.randomUUID()); row.setAuthorNickname("user");
    when(mapper.findBySlugWithAuthor("vue-101")).thenReturn(row);

    // 3 articles
    List<dowob.xyz.blog.module.article.model.Article> articles = List.of(
        article(1L, UUID.randomUUID(), "A", 1),
        article(2L, UUID.randomUUID(), "B", 2),
        article(3L, UUID.randomUUID(), "C", 3)
    );
    when(articleService.findBySeriesIdOrderByPosition(seriesId)).thenReturn(articles);

    // 已登入：A 已讀完，B 未讀完，C 未讀
    Map<Long, BigDecimal> progressMap = Map.of(
        1L, new BigDecimal("0.98"),
        2L, new BigDecimal("0.50")
    );
    when(readingFacade.batchGetProgress(eq(userId), any())).thenReturn(progressMap);

    SeriesDetailResponse resp = service.getSeriesDetail("vue-101", userId);

    assertThat(resp.getMyProgress()).isNotNull();
    assertThat(resp.getMyProgress().getReadCount()).isEqualTo(1);     // 只有 A 達 0.95
    assertThat(resp.getMyProgress().getTotalCount()).isEqualTo(3);
    assertThat(resp.getMyProgress().getNextUnreadArticleUuid())
        .isEqualTo(articles.get(1).getUuid());     // B（第一個未讀完的）
}

private dowob.xyz.blog.module.article.model.Article article(Long id, UUID uuid, String title, int position) {
    var a = new dowob.xyz.blog.module.article.model.Article();
    a.setId(id); a.setUuid(uuid); a.setTitle(title);
    a.setSeriesPosition(position);
    return a;
}
```

⚠ test 中需要的 import 自行加（BigDecimal / Map / List 等）。

- [ ] **Step 2: ArticleService 加 findBySeriesIdOrderByPosition**

```java
// ArticleService interface
java.util.List<dowob.xyz.blog.module.article.model.Article> findBySeriesIdOrderByPosition(Long seriesId);

// ArticleServiceImpl
@Override
public List<Article> findBySeriesIdOrderByPosition(Long seriesId) {
    return articleMapper.findBySeriesIdOrderByPosition(seriesId);
}
```

`ArticleMapper.java`：

```java
@Select("""
        SELECT * FROM articles WHERE series_id = #{seriesId}
         ORDER BY series_position
        """)
List<Article> findBySeriesIdOrderByPosition(@Param("seriesId") Long seriesId);
```

- [ ] **Step 3: SeriesService.getSeriesDetail**

```java
private final ReadingFacade readingFacade;       // 新加 inject

@Transactional(readOnly = true)
public SeriesDetailResponse getSeriesDetail(String slug, Long currentUserId) {
    SeriesWithAuthor row = mapper.findBySlugWithAuthor(slug);
    if (row == null) {
        throw new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND);
    }

    List<Article> articles = articleService.findBySeriesIdOrderByPosition(row.getId());

    SeriesDetailResponse resp = toDetailResponse(row, articles);

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

private SeriesDetailResponse toDetailResponse(SeriesWithAuthor row, List<Article> articles) {
    SeriesDetailResponse r = new SeriesDetailResponse();
    r.setUuid(row.getUuid());
    r.setTitle(row.getTitle());
    r.setSlug(row.getSlug());
    r.setDescription(row.getDescription());
    r.setCoverImageUrl(row.getCoverImageUrl());
    r.setArticleCount(row.getArticleCount());
    r.setCreatedAt(row.getCreatedAt());
    r.setUpdatedAt(row.getUpdatedAt());
    r.setAuthor(new dowob.xyz.blog.common.api.dto.AuthorSummary(
        row.getAuthorUuid(), row.getAuthorNickname(), row.getAuthorAvatarUrl()));
    // articles 部分：簡化用 ArticleSummaryResponse 但這需要 ArticleService 提供 mapper
    // 詳細 mapping 在 controller 層再做
    return r;
}
```

⚠ articles 在 response 中要轉 ArticleSummaryResponse，這個 mapping logic 跟 ArticleQueryService 有重疊。先暫時放空 list，等 Task 14 一起處理：

```java
// 暫時 placeholder
r.setArticles(List.of());
```

或更乾淨：在 SeriesService 注入 `ArticleQueryService.getArticleSummariesByIds(articleIds)` 取得 enriched summaries（但這會造成 series 模組 → article 模組 → SeriesFacade 循環風險）。

最簡解法：暫不 enrich articles 子列表（前端可以另外查 article 詳情）；下個 Task 14 修正。

- [ ] **Step 4: Run**

```bash
./mvnw.cmd -pl blog-module-article -am install -DskipTests
./mvnw.cmd -pl blog-module-series test -Dtest=SeriesServiceTest 2>&1 | tee logs/t12-green.log
```

Expected: 13 tests pass。

- [ ] **Step 5: Commit**

```bash
git add blog-module-series/src/main/java/dowob/xyz/blog/module/series/service/SeriesService.java \
        blog-module-series/src/test/java/dowob/xyz/blog/module/series/service/SeriesServiceTest.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/mapper/ArticleMapper.java
git commit -m "$(cat <<'EOF'
feat(series): SeriesService.getSeriesDetail（含 ReadingFacade 整合）

- 撈 series + articles + 我的進度（已登入時）
- progress >= 0.95 視為已讀完
- nextUnreadArticleUuid 取第一個未讀完的
- ArticleService 新增 findBySeriesIdOrderByPosition
- 1 個新 unit test

⚠ articles sub-list 暫為空，Task 14 補完整 ArticleSummaryResponse mapping

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: SeriesFacade interface + SeriesFacadeImpl

**Files:**
- Create: `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/SeriesFacade.java`
- Create: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/facade/SeriesFacadeImpl.java`

- [ ] **Step 1: SeriesFacade interface**

```java
package dowob.xyz.blog.infrastructure.facade;

import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;

import java.util.Optional;

/**
 * Series 跨模組 read facade。
 *
 * <p>給 ArticleQueryService 等跨模組讀取 series 導覽資訊用。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface SeriesFacade {

    /**
     * 取得文章在 series 中的導覽（prev/next）。
     *
     * @param articleId 文章資料庫主鍵
     * @return 若文章在 series 中回傳導覽；否則 Optional.empty()
     */
    Optional<SeriesNavigation> getSeriesNavigation(Long articleId);
}
```

- [ ] **Step 2: SeriesFacadeImpl**

```java
package dowob.xyz.blog.module.series.facade;

import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SeriesFacadeImpl implements SeriesFacade {

    private final ArticleService articleService;
    private final SeriesRepository seriesRepo;
    private final SeriesMapper seriesMapper;

    @Override
    public Optional<SeriesNavigation> getSeriesNavigation(Long articleId) {
        Optional<Article> articleOpt = articleService.findById(articleId);
        if (articleOpt.isEmpty()) return Optional.empty();
        Article article = articleOpt.get();
        if (article.getSeriesId() == null) return Optional.empty();

        Optional<Series> seriesOpt = seriesRepo.findById(article.getSeriesId());
        if (seriesOpt.isEmpty()) return Optional.empty();
        Series series = seriesOpt.get();

        // 撈 series 內所有 PUBLISHED articles（按 position 排序）
        List<SeriesMapper.NavRow> navList = seriesMapper.findArticlesForNav(series.getId());

        int currentIdx = -1;
        for (int i = 0; i < navList.size(); i++) {
            if (navList.get(i).getId().equals(articleId)) {
                currentIdx = i;
                break;
            }
        }
        if (currentIdx < 0) return Optional.empty();

        SeriesNavigation nav = new SeriesNavigation();
        nav.setSeriesUuid(series.getUuid());
        nav.setSeriesTitle(series.getTitle());
        nav.setSeriesSlug(series.getSlug());
        nav.setPosition(article.getSeriesPosition());
        nav.setTotalCount(navList.size());

        if (currentIdx > 0) {
            SeriesMapper.NavRow prev = navList.get(currentIdx - 1);
            nav.setPrev(new SeriesNavigation.SeriesArticleRef(prev.getUuid(), prev.getTitle(), prev.getSlug()));
        }
        if (currentIdx < navList.size() - 1) {
            SeriesMapper.NavRow next = navList.get(currentIdx + 1);
            nav.setNext(new SeriesNavigation.SeriesArticleRef(next.getUuid(), next.getTitle(), next.getSlug()));
        }
        return Optional.of(nav);
    }
}
```

⚠ ArticleService 加 `Optional<Article> findById(Long id)` 方法。

- [ ] **Step 3: ArticleService.findById**

```java
// interface
Optional<Article> findById(Long id);

// impl
@Override
public Optional<Article> findById(Long id) {
    return articleRepository.findById(id);
}
```

- [ ] **Step 4: 編譯**

```bash
./mvnw.cmd -pl blog-module-series,blog-infrastructure -am compile 2>&1 | tee logs/t13-facade.log
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/SeriesFacade.java \
        blog-module-series/src/main/java/dowob/xyz/blog/module/series/facade/SeriesFacadeImpl.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/
git commit -m "$(cat <<'EOF'
feat(series): SeriesFacade interface + SeriesFacadeImpl

- ReadingFacade pattern：infrastructure 定 interface，series 模組提供 impl
- getSeriesNavigation 給 ArticleQueryService 取 prev/next 用
- ArticleService 加 findById（給 facade 用）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: ArticleQueryService 加 SeriesFacade enrich + ArticleResponse 加欄位

**Files:**
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/dto/response/ArticleResponse.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/dto/response/ArticleSummaryResponse.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleQueryService.java`
- Modify: `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleQueryServiceTest.java`

- [ ] **Step 1: 加欄位到 DTO**

`ArticleResponse.java`：

```java
private dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation seriesNav;
```

`ArticleSummaryResponse.java`：

```java
private java.util.UUID seriesUuid;
private String seriesTitle;
private Integer seriesPosition;
```

- [ ] **Step 2: ArticleQueryService inject SeriesFacade + enrichSingle 加 seriesNav**

```java
private final SeriesFacade seriesFacade;     // NEW

private void enrichSingle(ArticleResponse resp) {
    // 既有：liked / bookmarked / lastReadProgress
    // ...

    // NEW: seriesNav
    if (resp.getId() != null) {
        seriesFacade.getSeriesNavigation(resp.getId())
            .ifPresent(resp::setSeriesNav);
    }
}
```

對於 `enrichList` 中的 ArticleSummaryResponse — series 三欄位（seriesUuid / seriesTitle / seriesPosition）不在這裡 enrich，而是在 ArticleMapper 的 list 查詢 SQL 加 LEFT JOIN series 直接撈。

修改 ArticleMapper 的 paginated list query（找 ArticleMapper 的 `getPublishedArticles` 或對應的 SQL），加 LEFT JOIN series：

```sql
SELECT a.*, ..., s.uuid AS series_uuid, s.title AS series_title, a.series_position AS series_position
FROM articles a
LEFT JOIN series s ON a.series_id = s.id
WHERE ...
```

mapping：MyBatis 自動把 `series_uuid`, `series_title`, `series_position` 對應到 ArticleSummaryRow / ArticleSummaryResponse。

⚠ 此處改動較深 — 涉及 ArticleMapper 既有 SQL 改寫。實作時要對照原始 SQL 細節。

- [ ] **Step 3: 加 3 個 ArticleQueryService 測試**

```java
@Mock private dowob.xyz.blog.infrastructure.facade.SeriesFacade seriesFacade;

@Test
void enrichSingle_articleInSeries_includesSeriesNav() {
    // mock seriesFacade.getSeriesNavigation 回 SeriesNavigation 含 prev / next
    // 跑 getArticleByUuid → resp.getSeriesNav() != null
}

@Test
void enrichSingle_seriesPositionFirst_prevIsNull() {
    // mock seriesFacade 回 nav with prev=null next=B
    // 跑 → resp.seriesNav.prev == null
}

@Test
void enrichSingle_seriesPositionLast_nextIsNull() {
    // mock seriesFacade 回 nav with prev=B next=null
    // 跑 → resp.seriesNav.next == null
}
```

詳細 mock 細節寫到 implementer 自行依既有 ArticleQueryServiceTest 風格補齊。

- [ ] **Step 4: Run article 模組所有測試**

```bash
./mvnw.cmd -pl blog-module-article -am install -DskipTests
./mvnw.cmd -pl blog-module-article test 2>&1 | tee logs/t14-enrich.log
```

Expected: 既有 + 3 新 tests pass.

- [ ] **Step 5: Commit**

```bash
git add blog-module-article/src/
git commit -m "$(cat <<'EOF'
feat(article): ArticleQueryService 加 SeriesFacade enrich + DTO 加 series 欄位

- ArticleResponse 加 seriesNav (SeriesNavigation)
- ArticleSummaryResponse 加 seriesUuid / seriesTitle / seriesPosition
- enrichSingle 加 SeriesFacade.getSeriesNavigation 整合
- ArticleMapper paginated SQL 加 LEFT JOIN series 一次性拉 series 輕量資訊
- 3 個新 unit test 覆蓋 prev/next/middle 三種 position

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: ArticleService.deleteArticle 連動 series.article_count

**Files:**
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java`

⚠ 設計選擇：series 模組依賴 article（forward），article 不能反過來注入 series。所以連動方法要透過 SeriesFacade.notifyArticleDeleted（同步呼叫，與 ReadingFacade pattern 一致）。

- [ ] **Step 1: SeriesFacade 加方法**

`blog-infrastructure/.../facade/SeriesFacade.java`:

```java
/**
 * 通知 series 模組：某 article 已被硬刪除，需要更新 series.article_count。
 *
 * @param seriesId article 原本所屬的 series id（必為非 null）
 */
void notifyArticleDeletedFromSeries(Long seriesId);
```

`SeriesFacadeImpl.java`:

```java
@Override
public void notifyArticleDeletedFromSeries(Long seriesId) {
    seriesMapper.decrementArticleCount(seriesId);
}
```

- [ ] **Step 2: ArticleServiceImpl.deleteArticle 加連動**

找到 deleteArticle method，在物理刪除前讀取 article.seriesId，刪除後通知 series：

```java
@Override
@Transactional
public void deleteArticle(UUID articleUuid, Long userId, boolean isAdmin) {
    Article article = ...;        // 既有邏輯：找文章 + 權限檢查
    Long seriesId = article.getSeriesId();    // 保留以便後續通知

    // 既有刪除邏輯 ...
    articleRepository.deleteById(article.getId());

    if (seriesId != null) {
        seriesFacade.notifyArticleDeletedFromSeries(seriesId);
    }
}

private final SeriesFacade seriesFacade;     // NEW inject
```

- [ ] **Step 3: Run**

```bash
./mvnw.cmd -pl blog-module-article -am install -DskipTests
./mvnw.cmd -pl blog-module-article,blog-module-series test 2>&1 | tee logs/t15-delete.log
```

Expected: 全綠。

- [ ] **Step 4: Commit**

```bash
git add blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/SeriesFacade.java \
        blog-module-series/src/main/java/dowob/xyz/blog/module/series/facade/SeriesFacadeImpl.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java
git commit -m "$(cat <<'EOF'
feat(article): deleteArticle 連動 series.article_count

- SeriesFacade 加 notifyArticleDeletedFromSeries（給 article 模組同步通知）
- ArticleServiceImpl.deleteArticle 在物理刪除後呼叫 SeriesFacade

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 16: SeriesController + IT

**Files:**
- Create: `blog-module-series/src/test/java/dowob/xyz/blog/module/series/config/SeriesTestApplication.java`
- Create: `blog-module-series/src/test/resources/application-test.yaml`
- Create: `blog-module-series/src/test/resources/db/testdata/R__series_test_seed.sql`
- Create: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/controller/SeriesController.java`
- Create: `blog-module-series/src/test/java/dowob/xyz/blog/module/series/controller/SeriesControllerIT.java`

- [ ] **Step 1: SeriesTestApplication**

```java
package dowob.xyz.blog.module.series.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
        scanBasePackages = {
                "dowob.xyz.blog.common",
                "dowob.xyz.blog.infrastructure",
                "dowob.xyz.blog.module.article",
                "dowob.xyz.blog.module.reading",
                "dowob.xyz.blog.module.series"
        },
        exclude = {
                RabbitAutoConfiguration.class,
                ElasticsearchDataAutoConfiguration.class,
                ElasticsearchClientAutoConfiguration.class,
                ElasticsearchRestClientAutoConfiguration.class,
                ReactiveElasticsearchRepositoriesAutoConfiguration.class
        })
@EnableJdbcRepositories(basePackages = {
        "dowob.xyz.blog.module.article.repository",
        "dowob.xyz.blog.module.reading.repository",
        "dowob.xyz.blog.module.series.repository"
})
@MapperScan(basePackages = {
        "dowob.xyz.blog.module.article.mapper",
        "dowob.xyz.blog.module.reading.mapper",
        "dowob.xyz.blog.module.series.mapper"
})
@EnableScheduling
public class SeriesTestApplication {
}
```

- [ ] **Step 2: application-test.yaml + R__series_test_seed.sql**

直接複製 reading 模組的版本，調整路徑即可。

`application-test.yaml`：

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration,classpath:db/testdata
mybatis:
  configuration:
    map-underscore-to-camel-case: true
    use-generated-keys: true
  type-aliases-package: dowob.xyz.blog.module
  type-handlers-package: dowob.xyz.blog.infrastructure.config
minio:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket-name: test-bucket
```

`R__series_test_seed.sql`：

```sql
INSERT INTO users (id, uuid, email, password_hash, nickname, username, role, status, email_verified, created_at, updated_at)
VALUES
    (1, gen_random_uuid(), 'user1@series-test.com', 'hash', 'User1', 'series-user1', 'AUTHOR', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, gen_random_uuid(), 'user2@series-test.com', 'hash', 'User2', 'series-user2', 'AUTHOR', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, gen_random_uuid(), 'admin@series-test.com', 'hash', 'Admin', 'series-admin', 'ADMIN', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

SELECT setval('users_id_seq', GREATEST(3, (SELECT MAX(id) FROM users)));
```

- [ ] **Step 3: SeriesController.java**

```java
package dowob.xyz.blog.module.series.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.model.dto.request.AddArticleToSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.request.CreateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.request.UpdateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.response.SeriesDetailResponse;
import dowob.xyz.blog.module.series.model.dto.response.SeriesSummaryResponse;
import dowob.xyz.blog.module.series.service.SeriesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/series")
@RequiredArgsConstructor
@Tag(name = "Series")
public class SeriesController {

    private final SeriesService seriesService;

    @GetMapping
    @Operation(summary = "Series 列表（公開）")
    public ApiResponse<PageResult<SeriesSummaryResponse>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(seriesService.listPublic(page, size));
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Series 詳情（含我的進度）")
    public ApiResponse<SeriesDetailResponse> get(
            @PathVariable String slug,
            @AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(seriesService.getSeriesDetail(slug, currentUserId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ARTICLE_CREATE')")
    @Operation(summary = "建立 Series")
    public ApiResponse<Series> create(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateSeriesRequest req) {
        return ApiResponse.success(seriesService.createSeries(userId, req));
    }

    @PutMapping("/{uuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "更新 Series")
    public ApiResponse<Series> update(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UpdateSeriesRequest req) {
        boolean isAdmin = isAdmin();
        return ApiResponse.success(seriesService.updateSeries(uuid, userId, isAdmin, req));
    }

    @DeleteMapping("/{uuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "刪除 Series")
    public ApiResponse<Void> delete(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal Long userId) {
        boolean isAdmin = isAdmin();
        seriesService.deleteSeries(uuid, userId, isAdmin);
        return ApiResponse.success();
    }

    @PutMapping("/{uuid}/articles/{articleUuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "加文章到 Series / 改 position")
    public ApiResponse<Void> addArticle(
            @PathVariable UUID uuid,
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody AddArticleToSeriesRequest req) {
        boolean isAdmin = isAdmin();
        seriesService.addArticleToSeries(uuid, articleUuid, userId, isAdmin, req.getPosition());
        return ApiResponse.success();
    }

    @DeleteMapping("/{uuid}/articles/{articleUuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "從 Series 移除文章")
    public ApiResponse<Void> removeArticle(
            @PathVariable UUID uuid,
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long userId) {
        boolean isAdmin = isAdmin();
        seriesService.removeArticleFromSeries(uuid, articleUuid, userId, isAdmin);
        return ApiResponse.success();
    }

    private boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
```

⚠ SeriesService.listPublic 還沒實作，補上：

```java
public PageResult<SeriesSummaryResponse> listPublic(int page, int size) {
    int offset = Math.max(0, (page - 1) * size);
    List<SeriesWithAuthor> rows = mapper.findPublic(size, offset);
    long total = mapper.countPublic();
    List<SeriesSummaryResponse> records = rows.stream().map(this::toSummaryResponse).toList();
    return PageResult.of(page, size, total, records);
}

private SeriesSummaryResponse toSummaryResponse(SeriesWithAuthor row) {
    SeriesSummaryResponse r = new SeriesSummaryResponse();
    r.setUuid(row.getUuid()); r.setTitle(row.getTitle()); r.setSlug(row.getSlug());
    r.setDescription(row.getDescription()); r.setCoverImageUrl(row.getCoverImageUrl());
    r.setArticleCount(row.getArticleCount()); r.setCreatedAt(row.getCreatedAt());
    r.setUpdatedAt(row.getUpdatedAt());
    r.setAuthor(new dowob.xyz.blog.common.api.dto.AuthorSummary(
        row.getAuthorUuid(), row.getAuthorNickname(), row.getAuthorAvatarUrl()));
    return r;
}
```

- [ ] **Step 4: SeriesControllerIT.java（10 IT）**

對齊 batch 2 BookmarkControllerIT pattern。詳細 setup 同 ReadingTestApplication 結構。10 個 case 的命名跟覆蓋如 §8.B。

簡化 IT 骨架，實作時依 BookmarkControllerIT 風格寫完整版：

```java
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class SeriesControllerIT {
    // ... container setup ...
    // ... MockitoBean: ConnectionFactory, RabbitTemplate, TagFacade, UserFacade, UserAuthService ...

    @Test void post_validRequest_returns200() { ... }
    @Test void post_invalidSlugFormat_returns400() { ... }
    @Test void post_duplicateSlug_returnsS0104() { ... }
    @Test void post_unauthenticated_returns401() { ... }
    @Test void put_byOwner_returns200() { ... }
    @Test void put_byNonOwner_returnsS0102() { ... }
    @Test void delete_byOwner_unlinkArticles() { ... }
    @Test void putArticle_validRequest_returns200() { ... }
    @Test void putArticle_articleInOtherSeries_returnsS0106() { ... }
    @Test void getSlug_authenticated_includesMyProgress() { ... }
}
```

- [ ] **Step 5: Run**

```bash
./mvnw.cmd -pl blog-module-series -am install -DskipTests
./mvnw.cmd -pl blog-module-series test -Dtest=SeriesControllerIT 2>&1 | tee logs/t16-it.log
```

Expected: 10 IT pass。

- [ ] **Step 6: Commit**

```bash
git add blog-module-series/src/
git commit -m "$(cat <<'EOF'
feat(series): SeriesController + IT (10 tests)

- 7 端點：list / get(slug) / create / update / delete / addArticle / removeArticle
- SeriesTestApplication 啟動 article + reading + series
- 10 IT 覆蓋 happy path / 驗證 / S0102 / S0104 / S0106 / 401

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 17: 跨模組整合 IT

**Files:**
- Create: `blog-module-series/src/test/java/dowob/xyz/blog/module/series/integration/CrossModuleSeriesIT.java`

- [ ] **Step 1: 3 IT**

```java
@SpringBootTest(classes = SeriesTestApplication.class, ...)
@AutoConfigureMockMvc
@Testcontainers
class CrossModuleSeriesIT {

    @Test
    @DisplayName("POST /series + add article → ArticleResponse 含 seriesNav")
    void seriesNav_propagatesAfterAddingArticle() throws Exception { ... }

    @Test
    @DisplayName("DELETE /articles/{uuid} → series.article_count -1")
    void deleteArticle_decrementsSeriesCount() throws Exception { ... }

    @Test
    @DisplayName("DELETE /series/{uuid} → articles.series_id NULL")
    void deleteSeries_unlinksArticles() throws Exception { ... }
}
```

- [ ] **Step 2: Run**

```bash
./mvnw.cmd -pl blog-module-series test -Dtest=CrossModuleSeriesIT 2>&1 | tee logs/t17-cross.log
```

Expected: 3 IT pass。

- [ ] **Step 3: Run all batch 3 affected modules**

```bash
./mvnw.cmd -pl blog-module-article,blog-module-reading,blog-module-comment,blog-module-series -am test 2>&1 | tee logs/t17-all.log
```

Expected: all green.

- [ ] **Step 4: Commit**

```bash
git add blog-module-series/src/test/java/dowob/xyz/blog/module/series/integration/
git commit -m "$(cat <<'EOF'
test(series): 跨模組整合 IT (3 tests)

- POST /series + add article → ArticleResponse seriesNav 反映
- DELETE article → series.article_count 連動 -1
- DELETE series → articles.series_id NULL

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 18: schema.md 更新 V15

**Files:**
- Modify: `ai-docs/schema.md`

- [ ] **Step 1: 加 V15 三件事到 schema.md**

於 schema.md 適當位置（建議 user_reading_progress 之後）加：

```markdown
### series

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | 公開識別 |
| title | VARCHAR(255) | NOT NULL | |
| slug | VARCHAR(255) | NOT NULL UNIQUE | |
| description | TEXT | NULL | |
| cover_image_url | VARCHAR(512) | NULL | MinIO URL |
| author_id | BIGINT | NOT NULL REFERENCES users(id) | NO ACTION |
| article_count | INTEGER | NOT NULL DEFAULT 0 | 反正規化 |
| created_at, updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `series_pkey` (auto)
- `series_uuid_key` (auto, UNIQUE)
- `series_slug_key` (auto, UNIQUE)
- `idx_series_author` on (author_id)

**V15 新增**

---

### articles（V15 增欄位）

新增欄位：
- `series_id BIGINT NULL REFERENCES series(id) ON DELETE SET NULL`
- `series_position INTEGER NULL`

新增索引：
- `idx_articles_series_position` partial on (series_id, series_position) WHERE series_id IS NOT NULL

---

### user_article_likes（V15 改名）

舊名 `article_likes`。Schema 不變，僅命名一致化（與 user_bookmarks / user_highlights / user_reading_progress 對齊）。

UNIQUE constraint 改名：
- `uq_article_likes_user_article` → `uq_user_article_likes_user_article`
```

於 Migration Index 末尾加：

```markdown
- **V15**: 新建 series + articles 加 series_id/position + article_likes 改名 user_article_likes
```

- [ ] **Step 2: Commit**

```bash
git add ai-docs/schema.md
git commit -m "$(cat <<'EOF'
docs(schema): 更新 schema.md 加入 V15 三件事

- 新建 series 表（含 article_count 反正規化）
- articles 加 series_id / series_position
- article_likes 改名 user_article_likes（命名一致化）
- Migration Index 補 V15

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review Checklist

實作完成後請勾選：

- [ ] V15 migration 套用成功（Testcontainers）
- [ ] AuthorSummary 在 blog-common；comment 模組改 import 後 67 tests 全綠
- [ ] ArticleLike 完全搬到 reading 模組（article 模組無殘留）
- [ ] ReadingFacade 含 batchIsLiked / isLiked
- [ ] ArticleQueryService 統一從 ReadingFacade 取 liked / bookmarked / progress
- [ ] blog-module-series 模組正確註冊（root pom + blog-start + MyBatisConfig.@MapperScan）
- [ ] Series CRUD 13 unit + 10 IT 全綠
- [ ] ArticleQueryService 加 SeriesFacade enrich seriesNav，3 unit pass
- [ ] DELETE article 連動 series.article_count
- [ ] 跨模組整合 IT 3 個全綠
- [ ] schema.md V15 三件事寫入

---

## 後續批次

- 批 4 — Draft History / Versioning（編輯器版本歷史）
- 可選批 5+ — Bookmark 分類、`GET /me/highlights`、Series 章節、Tags index 整合 Series
