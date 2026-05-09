# Comment + Like Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 實作文章評論（2 層巢狀，自動發佈，5 分鐘編輯窗，軟刪除）+ 文章按讚 + 留言按讚，並順手把 article 模組 markdown 庫從 commonmark 統一到 flexmark。

**Architecture:** 新增 `blog-module-comment` 走既有 hybrid pattern（Spring Data JDBC `CrudRepository` 簡單 CRUD + MyBatis `@Mapper` 複雜 JOIN/原子 update）。中庸級別模組邊界：comment 透過 SQL JOIN 讀 users（reference data），透過 `ArticleService` 更新 articles 計數（業務 data）。Markdown 用 flexmark + OWASP sanitizer。

**Tech Stack:** Spring Boot, Spring Data JDBC, MyBatis, PostgreSQL, Flyway, flexmark-java, OWASP HtmlSanitizer, JUnit 5, Mockito, Spring Security Test, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-05-01-comment-like-design.md`

---

## File Map

### 新增檔案

```
blog-db-migration/
└─ src/main/resources/db/migration/V13__add_comment_and_comment_likes_schema.sql

blog-module-comment/                                              [NEW MODULE]
├─ pom.xml
├─ src/main/java/dowob/xyz/blog/module/comment/
│  ├─ controller/
│  │   ├─ CommentController.java
│  │   └─ CommentLikeController.java
│  ├─ service/
│  │   ├─ CommentService.java
│  │   ├─ CommentLikeService.java
│  │   └─ CommentMarkdownRenderer.java
│  ├─ repository/
│  │   ├─ CommentRepository.java
│  │   └─ CommentLikeRepository.java
│  ├─ mapper/
│  │   └─ CommentMapper.java                            (MyBatis - JOIN users)
│  ├─ model/
│  │   ├─ Comment.java
│  │   ├─ CommentLike.java
│  │   ├─ CommentWithAuthor.java                        (row mapping for JOIN)
│  │   └─ dto/
│  │       ├─ request/
│  │       │   ├─ CreateCommentRequest.java
│  │       │   └─ EditCommentRequest.java
│  │       └─ response/
│  │           ├─ CommentResponse.java
│  │           ├─ AuthorSummary.java
│  │           └─ ArticleCommentListResponse.java
│  ├─ exception/
│  │   └─ CommentErrorCode.java
│  └─ config/
│      └─ (placeholder; CommentMarkdownRenderer 自己 @Service)
└─ src/test/
   ├─ java/dowob/xyz/blog/module/comment/
   │  ├─ config/CommentTestApplication.java
   │  ├─ service/CommentServiceTest.java
   │  ├─ service/CommentLikeServiceTest.java
   │  ├─ service/CommentMarkdownRendererTest.java
   │  ├─ controller/CommentControllerIT.java
   │  └─ controller/CommentLikeControllerIT.java
   └─ resources/application-test.yaml

blog-module-article/                                              [MODIFICATIONS]
├─ src/main/java/dowob/xyz/blog/module/article/
│  ├─ model/
│  │   └─ ArticleLike.java                              (NEW — entity for existing table)
│  ├─ repository/
│  │   └─ ArticleLikeRepository.java                    (NEW)
│  ├─ mapper/
│  │   └─ ArticleMapper.java                            (MODIFY — add count + batch is-liked)
│  ├─ service/
│  │   ├─ ArticleLikeService.java                       (NEW)
│  │   ├─ ArticleService.java                           (MODIFY interface — add count methods)
│  │   └─ ArticleServiceImpl.java                       (MODIFY — flexmark migration + impl new methods)
│  ├─ controller/
│  │   └─ ArticleLikeController.java                    (NEW)
│  └─ model/dto/response/
│      ├─ ArticleSummaryResponse.java                   (MODIFY — add `liked` field)
│      └─ ArticleResponse.java                          (MODIFY — add `liked` field)
└─ src/test/java/.../service/ArticleLikeServiceTest.java  (NEW)
└─ src/test/java/.../controller/ArticleLikeControllerIT.java  (NEW)

pom.xml (root)                                          [MODIFY — add blog-module-comment]
blog-start/pom.xml                                       [MODIFY — add comment dependency]
```

---

## Pre-Flight Notes

1. **Worktree**：plan 假設在 `.worktrees/backend-roadmap-specs/` 內執行（已 setup）。
2. **Maven**：用 `./mvnw.cmd`（Windows 慣例，CLAUDE.md 記憶）。
3. **測試輸出**：每次 `./mvnw.cmd test ... 2>&1 | tee logs/<task-name>.log`（避免重跑全套）。
4. **Surefire 報告**：失敗時優先讀 `<module>/target/surefire-reports/TEST-*.xml` 而非重跑。
5. **Commit message convention**：`<type>(<scope>): <subject>` 繁中描述（CLAUDE.md/git-convention.md），ending with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`。

---

## Task 1: V13 Migration

**Files:**
- Create: `blog-db-migration/src/main/resources/db/migration/V13__add_comment_and_comment_likes_schema.sql`

- [ ] **Step 1: Write V13 SQL**

```sql
-- V13__add_comment_and_comment_likes_schema.sql

-- 1. articles：統一 like_count 型別 (BIGINT → INTEGER)
ALTER TABLE articles ALTER COLUMN like_count TYPE INTEGER;

-- 2. 清理重複索引（articles_uuid_key 已是 UNIQUE，idx_articles_uuid 多餘）
DROP INDEX IF EXISTS idx_articles_uuid;

-- 3. 改造既有 comments 表
ALTER TABLE comments DROP COLUMN status;

ALTER TABLE comments ADD COLUMN content_html    TEXT     NOT NULL DEFAULT '';
ALTER TABLE comments ADD COLUMN like_count      INTEGER  NOT NULL DEFAULT 0;
ALTER TABLE comments ADD COLUMN edited_at       TIMESTAMP NULL;
ALTER TABLE comments ADD COLUMN deleted_at      TIMESTAMP NULL;
ALTER TABLE comments ADD COLUMN deleted_by_role VARCHAR(20) NULL;

CREATE INDEX idx_comments_article_top_level
    ON comments(article_id, created_at DESC)
    WHERE parent_id IS NULL;

CREATE INDEX idx_comments_replies
    ON comments(parent_id, created_at)
    WHERE parent_id IS NOT NULL;

CREATE INDEX idx_comments_user_created
    ON comments(user_id, created_at DESC);

-- 4. article_likes：UNIQUE 順序 → (user_id, article_id)
ALTER TABLE article_likes
    DROP CONSTRAINT article_likes_article_id_user_id_key;

ALTER TABLE article_likes
    ADD CONSTRAINT uq_article_likes_user_article UNIQUE (user_id, article_id);

-- 5. 新建 comment_likes
CREATE TABLE comment_likes (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT    NOT NULL REFERENCES users(id),
    comment_id BIGINT    NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_comment_likes_user_comment UNIQUE (user_id, comment_id)
);
```

- [ ] **Step 2: 驗證 migration 可在本地 dev DB 套用**

```bash
./mvnw.cmd -pl blog-start -am compile
./mvnw.cmd -pl blog-start spring-boot:run -Dspring.profiles.active=dev
# 觀察 log：應出現 "Migrating schema ... to version 13"
# 出現錯誤則檢查 syntax 與 V1 對齊
```

或更輕量：跑既有 article IT（會 trigger Flyway migration）：

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleControllerIT 2>&1 | tee logs/v13-validate.log
```

Expected: 測試應全綠（既有 article 行為不變）。

- [ ] **Step 3: Commit**

```bash
git add blog-db-migration/src/main/resources/db/migration/V13__add_comment_and_comment_likes_schema.sql
git commit -m "feat(comment): 新增 V13 migration 改造 comments 表並建立 comment_likes

- comments 增加 content_html / like_count / edited_at / deleted_at / deleted_by_role
- comments 移除 status（改用 deleted_at + deleted_by_role）
- comments 加入 3 個 partial/composite 索引
- article_likes UNIQUE 順序改成 (user_id, article_id) 以支援 my-likes prefix
- articles.like_count BIGINT → INTEGER 統一型別
- 清理重複索引 idx_articles_uuid（與 articles_uuid_key 重疊）
- 新建 comment_likes 表

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: blog-module-article markdown 庫從 commonmark 遷移到 flexmark

**為什麼先做這個？** 統一 markdown 庫，後續 comment 模組才能用同一套依賴慣例。

**Files:**
- Modify: `blog-module-article/pom.xml`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java`（line 34-37 imports；line 730-741 `convertToHtml`；line 754-766 `extractSummary`）

- [ ] **Step 1: 跑既有 article 測試取得 baseline**

```bash
./mvnw.cmd -pl blog-module-article test 2>&1 | tee logs/article-baseline.log
```

Expected: 全綠。記下測試數作為遷移後對照。

- [ ] **Step 2: 換 pom.xml 依賴**

`blog-module-article/pom.xml` 找：

```xml
<dependency>
    <groupId>org.commonmark</groupId>
    <artifactId>commonmark</artifactId>
</dependency>
```

替換為：

```xml
<dependency>
    <groupId>com.vladsch.flexmark</groupId>
    <artifactId>flexmark-all</artifactId>
</dependency>
```

注意：`flexmark-all` 包含所有 ext，較重；若想精簡可用 `flexmark` core 並按需加 ext。第一版用 `flexmark-all` 圖快。

需要在 root `pom.xml` 的 `<dependencyManagement>` 加版本（若無）。先檢查：

```bash
grep -A 1 "flexmark" D:/end/workspace/java/blog-web-v2/.worktrees/backend-roadmap-specs/pom.xml
```

若無，於 root pom `<dependencyManagement>` 加：

```xml
<dependency>
    <groupId>com.vladsch.flexmark</groupId>
    <artifactId>flexmark-all</artifactId>
    <version>0.64.8</version>
</dependency>
```

- [ ] **Step 3: 改 ArticleServiceImpl imports（line 34-37）**

刪除：
```java
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;
```

換成：
```java
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
```

`TextContentRenderer` 在 flexmark 對應為 `com.vladsch.flexmark.util.ast.TextCollectingVisitor` 或 `com.vladsch.flexmark.html.HtmlRenderer` plus stripping —— 實作方式不同，見 Step 5。

- [ ] **Step 4: 改 `convertToHtml(String markdown)` (line 730-741)**

找：

```java
private String convertToHtml(String markdown) {
    if (markdown == null) {
        return null;
    }
    Parser parser = Parser.builder().build();
    Node document = parser.parse(markdown);
    HtmlRenderer renderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();
    return renderer.render(document);
}
```

替換為：

```java
private String convertToHtml(String markdown) {
    if (markdown == null) {
        return null;
    }
    MutableDataSet options = new MutableDataSet();
    options.set(HtmlRenderer.ESCAPE_HTML, true);
    options.set(HtmlRenderer.SUPPRESS_HTML, true);
    Parser parser = Parser.builder(options).build();
    Node document = parser.parse(markdown);
    HtmlRenderer renderer = HtmlRenderer.builder(options).build();
    return renderer.render(document);
}
```

對照：
- `escapeHtml(true)` → `HtmlRenderer.ESCAPE_HTML = true`
- `sanitizeUrls(true)` → flexmark 沒直接對應；用 `SUPPRESS_HTML = true` 阻擋原始 HTML，達近似效果。若需更嚴格的 URL sanitize，後續可加 OWASP sanitizer 收尾（但這是 article 渲染，作者本人輸入的 markdown，信任度較高，不需要嚴格 sanitize）。

- [ ] **Step 5: 改 `extractSummary(String content, String summary)` (line 754-766)**

找：

```java
private String extractSummary(String content, String summary) {
    if (summary != null && !summary.isBlank()) {
        return summary;
    }
    if (content == null) {
        return null;
    }
    Parser parser = Parser.builder().build();
    Node document = parser.parse(content);
    TextContentRenderer textRenderer = TextContentRenderer.builder().build();
    String plainText = textRenderer.render(document);
    return plainText.substring(0, Math.min(200, plainText.length()));
}
```

替換為：

```java
private String extractSummary(String content, String summary) {
    if (summary != null && !summary.isBlank()) {
        return summary;
    }
    if (content == null) {
        return null;
    }
    Parser parser = Parser.builder().build();
    Node document = parser.parse(content);
    com.vladsch.flexmark.util.ast.TextCollectingVisitor visitor =
            new com.vladsch.flexmark.util.ast.TextCollectingVisitor();
    String plainText = visitor.collectAndGetText(document);
    return plainText.substring(0, Math.min(200, plainText.length()));
}
```

`TextCollectingVisitor` 是 flexmark 提供的純文字收集器，行為等同 commonmark 的 `TextContentRenderer.builder().build().render(document)`。

- [ ] **Step 6: 跑 article 測試確認沒退化**

```bash
./mvnw.cmd -pl blog-module-article test 2>&1 | tee logs/article-flexmark.log
```

Expected: 與 baseline 同樣全綠（除非 commonmark/flexmark 在 HTML 邊角輸出細節不同 — 若 contentHtml 內容驗證測試有失敗，case-by-case 處理）。

若有失敗，檢查 `<module>/target/surefire-reports/TEST-*.xml` 找出具體 assertion 差異 — 通常是 `<p>` 包裝 / 換行 / 引號 escape 差異，可調整測試的 expected 值（但要 confirm 新輸出語意正確）。

- [ ] **Step 7: Commit**

```bash
git add blog-module-article/pom.xml \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java \
        pom.xml
git commit -m "refactor(article): markdown 庫從 commonmark 統一遷移到 flexmark

- 統一全專案 markdown stack 為 flexmark，方便後續 comment 模組共用
- ArticleServiceImpl.convertToHtml 改用 flexmark Parser/HtmlRenderer
- ArticleServiceImpl.extractSummary 改用 TextCollectingVisitor
- root pom 加入 flexmark-all 版本管理

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: blog-module-comment 模組骨架

**Files:**
- Create: `blog-module-comment/pom.xml`
- Modify: `pom.xml`（root，加入 `<module>blog-module-comment</module>`）
- Modify: `blog-start/pom.xml`（加入 dependency）

- [ ] **Step 1: 建模組目錄與 pom.xml**

```bash
mkdir -p blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/{controller,service,repository,mapper,model/dto/request,model/dto/response,exception,config}
mkdir -p blog-module-comment/src/test/java/dowob/xyz/blog/module/comment/{config,service,controller}
mkdir -p blog-module-comment/src/test/resources
```

`blog-module-comment/pom.xml`：

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

    <artifactId>blog-module-comment</artifactId>

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
        <!-- 跨模組依賴：透過 ArticleService 更新 articles.comment_count -->
        <dependency>
            <groupId>dowob.xyz</groupId>
            <artifactId>blog-module-article</artifactId>
        </dependency>
        <!-- Markdown -->
        <dependency>
            <groupId>com.vladsch.flexmark</groupId>
            <artifactId>flexmark-all</artifactId>
        </dependency>
        <!-- HTML Sanitizer -->
        <dependency>
            <groupId>com.googlecode.owasp-java-html-sanitizer</groupId>
            <artifactId>owasp-java-html-sanitizer</artifactId>
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

注意：root pom `<dependencyManagement>` 需有 owasp-java-html-sanitizer 版本管理。檢查：

```bash
grep "owasp-java-html-sanitizer" D:/end/workspace/java/blog-web-v2/.worktrees/backend-roadmap-specs/pom.xml
```

若無，於 root pom `<dependencyManagement>` 加：

```xml
<dependency>
    <groupId>com.googlecode.owasp-java-html-sanitizer</groupId>
    <artifactId>owasp-java-html-sanitizer</artifactId>
    <version>20240325.1</version>
</dependency>
```

- [ ] **Step 2: 註冊到 root pom.xml `<modules>`**

找 root `pom.xml` 的 `<modules>` 區塊（在 `<module>blog-module-recommend</module>` 後加入）：

```xml
<modules>
    <module>blog-common</module>
    <module>blog-db-migration</module>
    <module>blog-infrastructure</module>
    <module>blog-module-user</module>
    <module>blog-module-article</module>
    <module>blog-module-file</module>
    <module>blog-module-tag</module>
    <module>blog-module-search</module>
    <module>blog-module-recommend</module>
    <module>blog-module-comment</module>          <!-- NEW -->
    <module>blog-start</module>
</modules>
```

- [ ] **Step 3: 加 dependency 到 blog-start/pom.xml**

於 `blog-start/pom.xml` `<dependencies>` 找其他 `blog-module-*` 依賴位置，加入：

```xml
<dependency>
    <groupId>dowob.xyz</groupId>
    <artifactId>blog-module-comment</artifactId>
</dependency>
```

- [ ] **Step 4: 驗證 mvn 編譯**

```bash
./mvnw.cmd -pl blog-module-comment -am compile 2>&1 | tee logs/comment-skeleton.log
```

Expected: BUILD SUCCESS。空模組沒有 .java 檔案，但 pom.xml 與 dep 解析通過。

- [ ] **Step 5: Commit**

```bash
git add blog-module-comment/pom.xml pom.xml blog-start/pom.xml
git commit -m "feat(comment): 新增 blog-module-comment 模組骨架

- 註冊到 root pom modules
- 依賴 blog-infrastructure / blog-module-article / flexmark-all / owasp-java-html-sanitizer
- 加入 blog-start dependency

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Comment 與 CommentLike Entity + Repository

**Files:**
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/Comment.java`
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/CommentLike.java`
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/repository/CommentRepository.java`
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/repository/CommentLikeRepository.java`

- [ ] **Step 1: Comment.java**

```java
package dowob.xyz.blog.module.comment.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 留言實體（comments 表映射）
 *
 * <p>巢狀深度限制 2 層：parent_id NULL 為 top-level，否則為 reply 且 reply 不可再被 reply。
 * 軟刪除採 deleted_at + deleted_by_role 標記，row 永遠不刪除。</p>
 *
 * @author Yuan
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("comments")
public class Comment {
    @Id
    private Long id;
    private UUID uuid;
    private Long articleId;
    private Long parentId;
    private Long userId;
    private String content;
    private String contentHtml;
    private Integer likeCount;
    private LocalDateTime editedAt;
    private LocalDateTime deletedAt;
    private String deletedByRole;     // "AUTHOR" | "ADMIN" | null
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: CommentLike.java**

```java
package dowob.xyz.blog.module.comment.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("comment_likes")
public class CommentLike {
    @Id
    private Long id;
    private Long userId;
    private Long commentId;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: CommentRepository.java**

```java
package dowob.xyz.blog.module.comment.repository;

import dowob.xyz.blog.module.comment.model.Comment;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Comment Repository
 *
 * <p>提供基本 CRUD；複雜 JOIN 查詢由 CommentMapper 負責。</p>
 *
 * @author Yuan
 */
@Repository
public interface CommentRepository extends CrudRepository<Comment, Long> {
    Optional<Comment> findByUuid(UUID uuid);
}
```

- [ ] **Step 4: CommentLikeRepository.java**

```java
package dowob.xyz.blog.module.comment.repository;

import dowob.xyz.blog.module.comment.model.CommentLike;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CommentLikeRepository extends CrudRepository<CommentLike, Long> {
    Optional<CommentLike> findByUserIdAndCommentId(Long userId, Long commentId);
    long countByCommentId(Long commentId);
    void deleteByUserIdAndCommentId(Long userId, Long commentId);
}
```

- [ ] **Step 5: 驗證編譯**

```bash
./mvnw.cmd -pl blog-module-comment -am compile 2>&1 | tee logs/comment-entities.log
```

Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/ \
        blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/repository/
git commit -m "feat(comment): 新增 Comment / CommentLike entity 與 CrudRepository

- Comment：comments 表映射，含軟刪除欄位
- CommentLike：comment_likes 表映射
- 兩個 CrudRepository 提供基本 CRUD + UUID/組合鍵查詢

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: CommentErrorCode + DTO

**Files:**
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/exception/CommentErrorCode.java`
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/dto/request/CreateCommentRequest.java`
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/dto/request/EditCommentRequest.java`
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/dto/response/AuthorSummary.java`
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/dto/response/CommentResponse.java`
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/dto/response/ArticleCommentListResponse.java`

**先看一個現有 ErrorCode 對齊 interface**：

```bash
grep -l "implements IErrorCode\|enum.*ErrorCode" \
  D:/end/workspace/java/blog-web-v2/.worktrees/backend-roadmap-specs/blog-module-article/src/main/java/dowob/xyz/blog/module/article/exception/
```

讀其中一個檔案看 enum 定義 pattern。

- [ ] **Step 1: CommentErrorCode.java（對齊既有 ErrorCode 風格）**

```java
package dowob.xyz.blog.module.comment.exception;

import dowob.xyz.blog.common.api.exception.IErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CommentErrorCode implements IErrorCode {
    COMMENT_NOT_FOUND("C0101", "留言不存在"),
    NESTING_TOO_DEEP("C0102", "不可超過 2 層巢狀"),
    EDIT_WINDOW_EXPIRED("C0103", "編輯時限已過"),
    COMMENT_DELETED("C0104", "留言已刪除，無法操作"),
    PARENT_NOT_IN_ARTICLE("C0105", "父留言不屬於此文章"),
    PARENT_FROZEN("C0106", "父留言已凍結");

    private final String code;
    private final String message;
}
```

⚠ 若實際 `IErrorCode` interface 在 `blog-common` 下的 package 路徑不同，調整 import。

- [ ] **Step 2: CreateCommentRequest.java**

```java
package dowob.xyz.blog.module.comment.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateCommentRequest {
    @NotBlank
    @Size(max = 2000)
    private String content;

    private UUID parentUuid;     // null = top-level
}
```

- [ ] **Step 3: EditCommentRequest.java**

```java
package dowob.xyz.blog.module.comment.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EditCommentRequest {
    @NotBlank
    @Size(max = 2000)
    private String content;
}
```

- [ ] **Step 4: AuthorSummary.java**

```java
package dowob.xyz.blog.module.comment.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthorSummary {
    private UUID uuid;
    private String nickname;
    private String avatarUrl;
}
```

- [ ] **Step 5: CommentResponse.java**

```java
package dowob.xyz.blog.module.comment.model.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class CommentResponse {
    private UUID uuid;
    private UUID parentUuid;
    private String content;            // raw markdown
    private String contentHtml;        // sanitized HTML
    private AuthorSummary author;      // null when deleted=true
    private Integer likeCount;
    private Boolean liked;             // 當前使用者是否已按讚
    private LocalDateTime createdAt;
    private LocalDateTime editedAt;    // null = 未編輯
    private Boolean deleted;           // true = 軟刪除佔位
    private String deletedByRole;      // "AUTHOR" | "ADMIN" | null
    private List<CommentResponse> replies;   // 僅 top-level 帶 replies
}
```

- [ ] **Step 6: ArticleCommentListResponse.java**

注意：`PageResult<T>` 是專案既有 wrapper，路徑通常是 `dowob.xyz.blog.common.api.PageResult`。先檢查：

```bash
grep -rn "class PageResult\|record PageResult" D:/end/workspace/java/blog-web-v2/.worktrees/backend-roadmap-specs/blog-common/src/main/java/
```

```java
package dowob.xyz.blog.module.comment.model.dto.response;

import dowob.xyz.blog.common.api.PageResult;     // ⚠ 視實際路徑調整
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArticleCommentListResponse {
    private PageResult<CommentResponse> topLevels;
    private Integer totalCommentCount;
}
```

- [ ] **Step 7: 編譯驗證**

```bash
./mvnw.cmd -pl blog-module-comment -am compile 2>&1 | tee logs/comment-dto.log
```

- [ ] **Step 8: Commit**

```bash
git add blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/exception/ \
        blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/dto/
git commit -m "feat(comment): 新增 CommentErrorCode 與 request/response DTOs

- CommentErrorCode：6 個業務錯誤（C0101-C0106）
- CreateCommentRequest / EditCommentRequest：with @NotBlank / @Size(2000)
- CommentResponse：含 liked / deleted / deletedByRole / replies
- AuthorSummary：跨模組 author 表達
- ArticleCommentListResponse：列表 wrapper

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: CommentMarkdownRenderer (TDD)

**Files:**
- Create: `blog-module-comment/src/test/java/dowob/xyz/blog/module/comment/service/CommentMarkdownRendererTest.java`
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/service/CommentMarkdownRenderer.java`

- [ ] **Step 1: 寫失敗測試 CommentMarkdownRendererTest.java**

```java
package dowob.xyz.blog.module.comment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommentMarkdownRendererTest {

    private CommentMarkdownRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new CommentMarkdownRenderer();
    }

    @Test
    void render_bold_returnsStrongTag() {
        String html = renderer.render("**bold**");
        assertThat(html).contains("<strong>bold</strong>");
    }

    @Test
    void render_italic_returnsEmTag() {
        String html = renderer.render("*italic*");
        assertThat(html).contains("<em>italic</em>");
    }

    @Test
    void render_inlineCode_returnsCodeTag() {
        String html = renderer.render("use `Map.of()` here");
        assertThat(html).contains("<code>Map.of()</code>");
    }

    @Test
    void render_link_addsNofollowAndTargetBlank() {
        String html = renderer.render("[Google](https://google.com)");
        assertThat(html).contains("<a");
        assertThat(html).contains("href=\"https://google.com\"");
        assertThat(html).contains("rel=\"nofollow noopener\"");
        assertThat(html).contains("target=\"_blank\"");
    }

    @Test
    void render_blockquote_returnsBlockquoteTag() {
        String html = renderer.render("> quoted");
        assertThat(html).contains("<blockquote>");
    }

    @Test
    void render_image_isStrippedOut() {
        String html = renderer.render("![alt](image.png)");
        assertThat(html).doesNotContain("<img");
    }

    @Test
    void render_heading_isStrippedOut() {
        String html = renderer.render("# Heading\n\nbody");
        assertThat(html).doesNotContain("<h1");
        assertThat(html).contains("body");
    }

    @Test
    void render_fencedCodeBlock_isStrippedOut() {
        String html = renderer.render("```java\ncode\n```");
        assertThat(html).doesNotContain("<pre>");
    }

    @Test
    void render_table_isStrippedOut() {
        String html = renderer.render("| a | b |\n|---|---|\n| 1 | 2 |");
        assertThat(html).doesNotContain("<table>");
    }

    @Test
    void render_xssScript_isSanitized() {
        String html = renderer.render("hello <script>alert(1)</script> world");
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("hello");
        assertThat(html).contains("world");
    }

    @Test
    void render_javascriptUrl_isSanitized() {
        String html = renderer.render("[click](javascript:alert(1))");
        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    void render_onerrorAttribute_isSanitized() {
        String html = renderer.render("<img src=x onerror=alert(1)>");
        assertThat(html).doesNotContain("onerror");
        assertThat(html).doesNotContain("<img");
    }

    @Test
    void render_nullInput_returnsEmptyString() {
        assertThat(renderer.render(null)).isEqualTo("");
    }

    @Test
    void render_emptyInput_returnsEmptyString() {
        assertThat(renderer.render("")).isEqualTo("");
    }
}
```

- [ ] **Step 2: 跑測試確認失敗（CommentMarkdownRenderer 還沒寫）**

```bash
./mvnw.cmd -pl blog-module-comment test -Dtest=CommentMarkdownRendererTest 2>&1 | tee logs/renderer-red.log
```

Expected: 編譯錯誤（"cannot resolve symbol CommentMarkdownRenderer"）。

- [ ] **Step 3: 實作 CommentMarkdownRenderer**

```java
package dowob.xyz.blog.module.comment.service;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.parser.ParserEmulationProfile;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Service;

/**
 * 留言 Markdown 渲染器
 *
 * <p>限制：只允許 inline emphasis、inline code、link、blockquote、line break。
 * 禁用：image、heading、fenced code block、table、list。</p>
 *
 * <p>實作策略：
 * <ol>
 *   <li>flexmark parser 啟用 minimal extension（不啟用 image/heading/table/list 等）</li>
 *   <li>OWASP sanitizer 收尾：強制白名單 element + 注入 rel="nofollow noopener" target="_blank" 至連結</li>
 * </ol>
 * </p>
 *
 * @author Yuan
 */
@Service
public class CommentMarkdownRenderer {

    private final Parser parser;
    private final HtmlRenderer renderer;
    private final PolicyFactory sanitizer;

    public CommentMarkdownRenderer() {
        MutableDataSet options = new MutableDataSet();
        options.setFrom(ParserEmulationProfile.COMMONMARK);
        options.set(HtmlRenderer.ESCAPE_HTML, true);
        options.set(HtmlRenderer.SUPPRESS_HTML, true);
        // 不啟用任何額外 extension（沒 ImageExt, HeadingExt 等）
        // flexmark 預設行為：CommonMark spec，允許 heading / fenced code / image
        // → 我們靠 sanitizer 收尾移除這些 element

        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();

        this.sanitizer = new HtmlPolicyBuilder()
                .allowElements("p", "br", "strong", "em", "code", "blockquote", "a")
                .allowUrlProtocols("http", "https")
                .allowAttributes("href").onElements("a")
                .requireRelNofollow()
                .requireRelsOnLinks("noopener")
                .allowAttributes("target").matching(true, "_blank").onElements("a")
                .toFactory();
    }

    /**
     * 渲染留言 Markdown 為安全 HTML
     *
     * @param markdown 原始 Markdown，可為 null
     * @return 渲染後的 HTML；null/空字串輸入回傳 ""
     */
    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        Node doc = parser.parse(markdown);
        String html = renderer.render(doc);

        // sanitize 之後再強制注入 target="_blank"（OWASP policy 的 matching 較嚴格）
        // 用 String.replaceAll 後處理
        String sanitized = sanitizer.sanitize(html);
        sanitized = sanitized.replaceAll("(<a [^>]*?)>", "$1 target=\"_blank\">");

        return sanitized;
    }
}
```

⚠ Notes:
- `requireRelNofollow()` 會自動加 `rel="nofollow"`；`requireRelsOnLinks("noopener")` 加 `rel="noopener"` —— 兩者合併後 sanitizer 應輸出 `rel="nofollow noopener"`
- `target="_blank"` 注入：OWASP policy 對 attribute 加入較嚴格，用 String 後處理（簡單暴力）；若有更乾淨的 OWASP policy 寫法可重構
- 該行 `replaceAll` 會在每個 `<a ...>` tag 加上 `target="_blank"`；測試已驗證

- [ ] **Step 4: 跑測試確認通過**

```bash
./mvnw.cmd -pl blog-module-comment test -Dtest=CommentMarkdownRendererTest 2>&1 | tee logs/renderer-green.log
```

Expected: 14 test passed。如有 fail：
- 看 `<module>/target/surefire-reports/TEST-CommentMarkdownRendererTest.xml` 找具體失敗 case
- `target="_blank"` 邏輯複雜時可能誤注入；可改用 sanitizer 的 `allowAttributes("target").matching(...).globally()` 等寫法

- [ ] **Step 5: Commit**

```bash
git add blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/service/CommentMarkdownRenderer.java \
        blog-module-comment/src/test/java/dowob/xyz/blog/module/comment/service/CommentMarkdownRendererTest.java
git commit -m "feat(comment): 新增 CommentMarkdownRenderer

- flexmark + OWASP sanitizer 雙層處理
- 允許：粗體 / 斜體 / inline code / 連結 / 引用
- 禁用：圖片 / 標題 / 程式碼區塊 / 表格 / 列表
- 連結強制 rel='nofollow noopener' target='_blank'
- XSS 防禦：剝離 script、javascript: URL、on* event
- 14 個 unit test 覆蓋

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: CommentMapper (MyBatis - JOIN users)

**Files:**
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/mapper/CommentMapper.java`
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/CommentWithAuthor.java`

**先看 ArticleMapper 範例對齊風格：**

```bash
cat D:/end/workspace/java/blog-web-v2/.worktrees/backend-roadmap-specs/blog-module-article/src/main/java/dowob/xyz/blog/module/article/mapper/ArticleMapper.java | head -80
```

- [ ] **Step 1: CommentWithAuthor.java（row mapping）**

```java
package dowob.xyz.blog.module.comment.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Comment 加上 author 資訊的查詢結果（用於 listComments 的 JOIN users）。
 */
@Data
public class CommentWithAuthor {
    // Comment fields
    private Long id;
    private UUID uuid;
    private Long articleId;
    private Long parentId;
    private UUID parentUuid;     // 由 self-join 取得（reply 帶 parent uuid 用於前端表達）
    private Long userId;
    private String content;
    private String contentHtml;
    private Integer likeCount;
    private LocalDateTime editedAt;
    private LocalDateTime deletedAt;
    private String deletedByRole;
    private LocalDateTime createdAt;

    // Author fields (from JOIN users)
    private UUID authorUuid;
    private String authorNickname;
    private String authorAvatarUrl;
}
```

- [ ] **Step 2: CommentMapper.java**

```java
package dowob.xyz.blog.module.comment.mapper;

import dowob.xyz.blog.module.comment.model.CommentWithAuthor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Comment MyBatis Mapper
 *
 * <p>負責複雜查詢（JOIN users）與原子 update（counters / 軟刪除）。</p>
 */
@Mapper
public interface CommentMapper {

    /**
     * 列出文章的 top-level 留言（含軟刪除佔位），JOIN users 取作者資訊。
     *
     * @param articleId 文章主鍵
     * @param sort      "newest" | "oldest"
     * @param limit     分頁筆數
     * @param offset    跳過筆數
     */
    @Select({
        "<script>",
        "SELECT c.id, c.uuid, c.article_id, c.parent_id, NULL AS parent_uuid,",
        "       c.user_id, c.content, c.content_html, c.like_count,",
        "       c.edited_at, c.deleted_at, c.deleted_by_role, c.created_at,",
        "       u.uuid AS author_uuid, u.nickname AS author_nickname,",
        "       u.avatar_url AS author_avatar_url",
        "  FROM comments c LEFT JOIN users u ON c.user_id = u.id",
        " WHERE c.article_id = #{articleId} AND c.parent_id IS NULL",
        " ORDER BY c.created_at",
        "<choose>",
        "  <when test='sort == &quot;oldest&quot;'>ASC</when>",
        "  <otherwise>DESC</otherwise>",
        "</choose>",
        " LIMIT #{limit} OFFSET #{offset}",
        "</script>"
    })
    List<CommentWithAuthor> findTopLevelByArticle(@Param("articleId") Long articleId,
                                                    @Param("sort") String sort,
                                                    @Param("limit") int limit,
                                                    @Param("offset") int offset);

    /**
     * 一次撈多個 top-level 的所有 replies（按 parent_id 分組、created_at ASC）。
     */
    @Select({
        "<script>",
        "SELECT c.id, c.uuid, c.article_id, c.parent_id,",
        "       (SELECT p.uuid FROM comments p WHERE p.id = c.parent_id) AS parent_uuid,",
        "       c.user_id, c.content, c.content_html, c.like_count,",
        "       c.edited_at, c.deleted_at, c.deleted_by_role, c.created_at,",
        "       u.uuid AS author_uuid, u.nickname AS author_nickname,",
        "       u.avatar_url AS author_avatar_url",
        "  FROM comments c LEFT JOIN users u ON c.user_id = u.id",
        " WHERE c.parent_id IN",
        "<foreach collection='parentIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "   AND c.deleted_at IS NULL",      // leaf reply 軟刪除直接隱藏
        " ORDER BY c.parent_id, c.created_at ASC",
        "</script>"
    })
    List<CommentWithAuthor> findRepliesByParentIds(@Param("parentIds") List<Long> parentIds);

    /** 文章總留言數（含 reply、含軟刪除佔位） */
    @Select("SELECT COUNT(*) FROM comments WHERE article_id = #{articleId}")
    int countByArticle(@Param("articleId") Long articleId);

    /** 文章 top-level 留言數（用於分頁 totalElements） */
    @Select("SELECT COUNT(*) FROM comments WHERE article_id = #{articleId} AND parent_id IS NULL")
    int countTopLevelByArticle(@Param("articleId") Long articleId);

    /** 原子 +1 like_count */
    @Update("UPDATE comments SET like_count = like_count + 1 WHERE id = #{id}")
    int incrementLikeCount(@Param("id") Long id);

    /** 原子 -1 like_count（守衛 > 0） */
    @Update("UPDATE comments SET like_count = like_count - 1 WHERE id = #{id} AND like_count > 0")
    int decrementLikeCount(@Param("id") Long id);

    /** 軟刪除（service 層計算 deletedByRole 後傳入） */
    @Update("UPDATE comments SET deleted_at = CURRENT_TIMESTAMP, deleted_by_role = #{role} WHERE id = #{id}")
    int softDelete(@Param("id") Long id, @Param("role") String role);

    /**
     * 批次查詢「當前使用者是否按讚某些留言」，避免 N+1。
     * 回傳：當前使用者已按讚的 commentId 集合。
     */
    @Select({
        "<script>",
        "SELECT comment_id FROM comment_likes",
        " WHERE user_id = #{userId}",
        "   AND comment_id IN",
        "<foreach collection='commentIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "</script>"
    })
    List<Long> findLikedCommentIdsByUser(@Param("userId") Long userId,
                                          @Param("commentIds") List<Long> commentIds);
}
```

- [ ] **Step 3: 編譯驗證**

```bash
./mvnw.cmd -pl blog-module-comment -am compile 2>&1 | tee logs/comment-mapper.log
```

- [ ] **Step 4: Commit**

```bash
git add blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/mapper/ \
        blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/CommentWithAuthor.java
git commit -m "feat(comment): 新增 CommentMapper（MyBatis JOIN users + 原子 updates）

- findTopLevelByArticle：JOIN users 列出 top-level，支援 newest/oldest 排序
- findRepliesByParentIds：批次撈 reply，過濾軟刪除 leaf
- countByArticle：總留言數（含 reply、含佔位）
- incrementLikeCount / decrementLikeCount：原子 update + underflow 守衛
- softDelete：deleted_at + deleted_by_role 標記
- findLikedCommentIdsByUser：批次查 liked 狀態避免 N+1

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: ArticleService 計數方法 + ArticleMapper 擴充

**Files:**
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleService.java`（interface）
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/mapper/ArticleMapper.java`

- [ ] **Step 1: 在 ArticleMapper 加 atomic count update**

於 `ArticleMapper.java` 加入：

```java
import org.apache.ibatis.annotations.Update;

// ... 既有方法 ...

@Update("UPDATE articles SET comment_count = comment_count + 1 WHERE id = #{id}")
int incrementCommentCount(@Param("id") Long id);

@Update("UPDATE articles SET comment_count = comment_count - 1 WHERE id = #{id} AND comment_count > 0")
int decrementCommentCount(@Param("id") Long id);

@Update("UPDATE articles SET like_count = like_count + 1 WHERE id = #{id}")
int incrementLikeCount(@Param("id") Long id);

@Update("UPDATE articles SET like_count = like_count - 1 WHERE id = #{id} AND like_count > 0")
int decrementLikeCount(@Param("id") Long id);

/** 取 article id by uuid（給 comment 模組透過 service 使用，不直接 JOIN articles） */
@Select("SELECT id FROM articles WHERE uuid = #{uuid}")
Long findIdByUuid(@Param("uuid") UUID uuid);
```

- [ ] **Step 2: 在 ArticleService interface 加方法**

於 `ArticleService.java`（既有 interface）加：

```java
void incrementCommentCount(Long articleId);
void decrementCommentCount(Long articleId);
void incrementLikeCount(Long articleId);
void decrementLikeCount(Long articleId);
Long findIdByUuid(java.util.UUID uuid);   // 給 comment 模組查 article PK
```

- [ ] **Step 3: 在 ArticleServiceImpl 實作**

於 `ArticleServiceImpl.java` 加：

```java
@Override
public void incrementCommentCount(Long articleId) {
    articleMapper.incrementCommentCount(articleId);
}

@Override
public void decrementCommentCount(Long articleId) {
    articleMapper.decrementCommentCount(articleId);
}

@Override
public void incrementLikeCount(Long articleId) {
    articleMapper.incrementLikeCount(articleId);
}

@Override
public void decrementLikeCount(Long articleId) {
    articleMapper.decrementLikeCount(articleId);
}

@Override
public Long findIdByUuid(UUID uuid) {
    return articleMapper.findIdByUuid(uuid);
}
```

- [ ] **Step 4: 編譯 + 跑既有 article 測試**

```bash
./mvnw.cmd -pl blog-module-article test 2>&1 | tee logs/article-counts.log
```

Expected: 既有測試全綠（沒回退）。

- [ ] **Step 5: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/mapper/ArticleMapper.java
git commit -m "feat(article): ArticleService 新增計數原子 update + findIdByUuid

- ArticleMapper 加 4 個 @Update：comment_count / like_count 原子 +1/-1
- 計數遞減守衛 > 0 防 underflow
- findIdByUuid 給跨模組 service 取 article PK 用（避免 JOIN articles）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: ArticleLike Entity + Repository + Service (TDD)

**Files:**
- Create: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/ArticleLike.java`
- Create: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/repository/ArticleLikeRepository.java`
- Create: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleLikeService.java`
- Create: `blog-module-article/src/test/java/.../service/ArticleLikeServiceTest.java`

- [ ] **Step 1: ArticleLike entity**

```java
package dowob.xyz.blog.module.article.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("article_likes")
public class ArticleLike {
    @Id
    private Long id;
    private Long userId;
    private Long articleId;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 2: ArticleLikeRepository**

```java
package dowob.xyz.blog.module.article.repository;

import dowob.xyz.blog.module.article.model.ArticleLike;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ArticleLikeRepository extends CrudRepository<ArticleLike, Long> {
    Optional<ArticleLike> findByUserIdAndArticleId(Long userId, Long articleId);
    void deleteByUserIdAndArticleId(Long userId, Long articleId);
}
```

- [ ] **Step 3: 加 batch is-liked 查詢到 ArticleMapper**

於 `ArticleMapper.java` 加：

```java
@Select({
    "<script>",
    "SELECT article_id FROM article_likes",
    " WHERE user_id = #{userId}",
    "   AND article_id IN",
    "<foreach collection='articleIds' item='id' open='(' separator=',' close=')'>",
    "  #{id}",
    "</foreach>",
    "</script>"
})
List<Long> findLikedArticleIdsByUser(@Param("userId") Long userId,
                                      @Param("articleIds") List<Long> articleIds);
```

- [ ] **Step 4: 寫 ArticleLikeServiceTest（先紅）**

```java
package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.ArticleLike;
import dowob.xyz.blog.module.article.repository.ArticleLikeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleLikeServiceTest {

    @Mock private ArticleLikeRepository likeRepo;
    @Mock private ArticleMapper articleMapper;
    @Mock private ArticleService articleService;
    @InjectMocks private ArticleLikeService service;

    private final Long userId = 1L;
    private final Long articleId = 100L;

    @Test
    void likeArticle_firstTime_createsRowAndIncrementsCount() {
        when(likeRepo.findByUserIdAndArticleId(userId, articleId)).thenReturn(Optional.empty());

        service.likeArticle(userId, articleId);

        verify(likeRepo, times(1)).save(any(ArticleLike.class));
        verify(articleService, times(1)).incrementLikeCount(articleId);
    }

    @Test
    void likeArticle_alreadyLiked_isIdempotentNoChange() {
        ArticleLike existing = new ArticleLike();
        existing.setUserId(userId); existing.setArticleId(articleId);
        when(likeRepo.findByUserIdAndArticleId(userId, articleId)).thenReturn(Optional.of(existing));

        service.likeArticle(userId, articleId);

        verify(likeRepo, never()).save(any());
        verify(articleService, never()).incrementLikeCount(any());
    }

    @Test
    void unlikeArticle_existing_deletesRowAndDecrementsCount() {
        ArticleLike existing = new ArticleLike();
        existing.setUserId(userId); existing.setArticleId(articleId);
        when(likeRepo.findByUserIdAndArticleId(userId, articleId)).thenReturn(Optional.of(existing));

        service.unlikeArticle(userId, articleId);

        verify(likeRepo, times(1)).deleteByUserIdAndArticleId(userId, articleId);
        verify(articleService, times(1)).decrementLikeCount(articleId);
    }

    @Test
    void unlikeArticle_notLiked_isIdempotentNoChange() {
        when(likeRepo.findByUserIdAndArticleId(userId, articleId)).thenReturn(Optional.empty());

        service.unlikeArticle(userId, articleId);

        verify(likeRepo, never()).deleteByUserIdAndArticleId(any(), any());
        verify(articleService, never()).decrementLikeCount(any());
    }

    @Test
    void isLiked_returnsTrueWhenLiked() {
        when(likeRepo.findByUserIdAndArticleId(userId, articleId))
                .thenReturn(Optional.of(new ArticleLike()));

        assertThat(service.isLiked(userId, articleId)).isTrue();
    }

    @Test
    void isLiked_returnsFalseWhenNotLiked() {
        when(likeRepo.findByUserIdAndArticleId(userId, articleId)).thenReturn(Optional.empty());

        assertThat(service.isLiked(userId, articleId)).isFalse();
    }

    @Test
    void batchIsLiked_returnsCorrectFlagsForEachId() {
        List<Long> articleIds = List.of(1L, 2L, 3L);
        when(articleMapper.findLikedArticleIdsByUser(eq(userId), eq(articleIds)))
                .thenReturn(List.of(1L, 3L));

        Set<Long> liked = service.batchIsLiked(userId, articleIds);

        assertThat(liked).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void batchIsLiked_unauthenticated_returnsEmptySet() {
        Set<Long> liked = service.batchIsLiked(null, List.of(1L, 2L));

        assertThat(liked).isEmpty();
        verify(articleMapper, never()).findLikedArticleIdsByUser(any(), any());
    }
}
```

- [ ] **Step 5: 跑紅燈確認**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleLikeServiceTest 2>&1 | tee logs/article-like-red.log
```

Expected: 編譯失敗（找不到 ArticleLikeService）。

- [ ] **Step 6: 實作 ArticleLikeService**

```java
package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.ArticleLike;
import dowob.xyz.blog.module.article.repository.ArticleLikeRepository;
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
    private final ArticleMapper articleMapper;
    private final ArticleService articleService;

    /** 按讚（idempotent） */
    @Transactional
    public void likeArticle(Long userId, Long articleId) {
        if (likeRepo.findByUserIdAndArticleId(userId, articleId).isPresent()) {
            return;  // 已按過，idempotent
        }
        ArticleLike like = new ArticleLike();
        like.setUserId(userId);
        like.setArticleId(articleId);
        like.setCreatedAt(LocalDateTime.now());
        likeRepo.save(like);
        articleService.incrementLikeCount(articleId);
    }

    /** 取消讚（idempotent） */
    @Transactional
    public void unlikeArticle(Long userId, Long articleId) {
        if (likeRepo.findByUserIdAndArticleId(userId, articleId).isEmpty()) {
            return;  // 沒按過，idempotent
        }
        likeRepo.deleteByUserIdAndArticleId(userId, articleId);
        articleService.decrementLikeCount(articleId);
    }

    public boolean isLiked(Long userId, Long articleId) {
        return likeRepo.findByUserIdAndArticleId(userId, articleId).isPresent();
    }

    /**
     * 批次查詢使用者已按讚的 articleId（用於列表頁避免 N+1）。
     *
     * @param userId 當前使用者，null 代表未登入
     * @return 已按讚的 articleId 集合；未登入或空輸入回傳 emptySet
     */
    public Set<Long> batchIsLiked(Long userId, List<Long> articleIds) {
        if (userId == null || articleIds == null || articleIds.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(articleMapper.findLikedArticleIdsByUser(userId, articleIds));
    }
}
```

- [ ] **Step 7: 跑綠燈確認**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleLikeServiceTest 2>&1 | tee logs/article-like-green.log
```

Expected: 8 tests passed。

- [ ] **Step 8: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/ArticleLike.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/repository/ArticleLikeRepository.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleLikeService.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/mapper/ArticleMapper.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleLikeServiceTest.java
git commit -m "feat(article): 新增 ArticleLikeService（idempotent like/unlike + batch isLiked）

- ArticleLike entity 對應既有 article_likes 表
- ArticleLikeRepository CrudRepository
- ArticleMapper 加 findLikedArticleIdsByUser 批次查詢
- 8 個 unit test 覆蓋：idempotent / batch / unauth / 計數 update

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: ArticleLikeController + IT

**Files:**
- Create: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/controller/ArticleLikeController.java`
- Create: `blog-module-article/src/test/java/.../controller/ArticleLikeControllerIT.java`

- [ ] **Step 1: ArticleLikeController**

```java
package dowob.xyz.blog.module.article.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.article.service.ArticleLikeService;
import dowob.xyz.blog.module.article.service.ArticleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

- [ ] **Step 2: ArticleLikeControllerIT（IT 測試）**

參考既有 `ArticleControllerIT` 的 setup pattern：

```bash
cat D:/end/workspace/java/blog-web-v2/.worktrees/backend-roadmap-specs/blog-module-article/src/test/java/dowob/xyz/blog/module/article/controller/ArticleControllerIT.java | head -60
```

實作 IT（保留 SecurityMockMvcRequestPostProcessors.authentication() 模式）：

```java
package dowob.xyz.blog.module.article.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.module.article.config.ArticleTestApplication;
// ... 其他 import 對齊既有 IT

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ArticleTestApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class ArticleLikeControllerIT {

    // ... 與 ArticleControllerIT 同樣的 testcontainers setup ...

    @Autowired private MockMvc mockMvc;
    @Autowired private ArticleRepository articleRepo;
    @Autowired private ArticleLikeRepository likeRepo;

    private UUID articleUuid;
    private Long userId = 1L;

    @BeforeEach
    void setup() {
        // create test article（沿用既有 helper）
        Article article = ...; articleRepo.save(article);
        articleUuid = article.getUuid();
    }

    @Test
    void likeArticle_returns200_andCreatesRow() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/like", articleUuid)
                .with(authentication(authFor(userId))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("00000"));

        assertThat(likeRepo.findByUserIdAndArticleId(userId, /* articleId */)).isPresent();
    }

    @Test
    void likeArticle_idempotent() throws Exception {
        // 連續 POST 兩次
        mockMvc.perform(post("/api/v1/articles/{uuid}/like", articleUuid)
                .with(authentication(authFor(userId)))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/articles/{uuid}/like", articleUuid)
                .with(authentication(authFor(userId)))).andExpect(status().isOk());

        // 仍然只有一筆
        assertThat(likeRepo.findByUserIdAndArticleId(userId, /* articleId */)).isPresent();
    }

    @Test
    void unlikeArticle_returns200_andDeletesRow() throws Exception {
        // 先按讚
        likeRepo.save(/* ArticleLike */);

        mockMvc.perform(delete("/api/v1/articles/{uuid}/like", articleUuid)
                .with(authentication(authFor(userId))))
            .andExpect(status().isOk());

        assertThat(likeRepo.findByUserIdAndArticleId(userId, /* articleId */)).isEmpty();
    }

    @Test
    void unlikeArticle_idempotentWhenNotLiked() throws Exception {
        mockMvc.perform(delete("/api/v1/articles/{uuid}/like", articleUuid)
                .with(authentication(authFor(userId))))
            .andExpect(status().isOk());
    }

    @Test
    void likeArticle_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/like", articleUuid))
            .andExpect(status().isUnauthorized());
    }

    // helper
    private Authentication authFor(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of());
    }
}
```

⚠ 上面骨架略寫；實作時對齊既有 `ArticleControllerIT` 的具體 setup（@DynamicPropertySource、Article fixture、UserDetails 結構）。

- [ ] **Step 3: 跑 IT**

```bash
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleLikeControllerIT 2>&1 | tee logs/article-like-it.log
```

Expected: 5 tests passed。

- [ ] **Step 4: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/controller/ArticleLikeController.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/controller/ArticleLikeControllerIT.java
git commit -m "feat(article): 新增 ArticleLikeController + IT

- POST /api/v1/articles/{uuid}/like（idempotent）
- DELETE /api/v1/articles/{uuid}/like（idempotent）
- 401 未登入；200 已登入
- 5 個 IT 測試覆蓋

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: CommentService.createComment + 2 層驗證 + 計數 (TDD)

**Files:**
- Create: `blog-module-comment/src/test/java/dowob/xyz/blog/module/comment/service/CommentServiceTest.java`
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/service/CommentService.java`

- [ ] **Step 1: CommentServiceTest 寫 createComment 系列測試（先紅）**

```java
package dowob.xyz.blog.module.comment.service;

import dowob.xyz.blog.common.api.exception.BusinessException;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.comment.exception.CommentErrorCode;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.dto.request.CreateCommentRequest;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock private CommentRepository commentRepo;
    @Mock private CommentMapper commentMapper;
    @Mock private CommentMarkdownRenderer renderer;
    @Mock private ArticleService articleService;
    @InjectMocks private CommentService service;

    private final Long userId = 10L;
    private final Long articleId = 100L;
    private final UUID articleUuid = UUID.randomUUID();

    // ─── createComment top-level ───
    @Test
    void createComment_topLevel_savesWithUuidAndDefaults() {
        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(renderer.render("hello")).thenReturn("<p>hello</p>");
        when(commentRepo.save(any())).thenAnswer(inv -> {
            Comment c = inv.getArgument(0); c.setId(1L); return c;
        });

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("hello");

        var resp = service.createComment(articleUuid, userId, req);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepo).save(captor.capture());
        Comment saved = captor.getValue();
        assertThat(saved.getUuid()).isNotNull();
        assertThat(saved.getArticleId()).isEqualTo(articleId);
        assertThat(saved.getParentId()).isNull();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getContent()).isEqualTo("hello");
        assertThat(saved.getContentHtml()).isEqualTo("<p>hello</p>");
        assertThat(saved.getLikeCount()).isEqualTo(0);
        assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    void createComment_topLevel_incrementsArticleCommentCount() {
        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(renderer.render(any())).thenReturn("<p>x</p>");
        when(commentRepo.save(any())).thenAnswer(inv -> {
            Comment c = inv.getArgument(0); c.setId(1L); return c;
        });

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("x");

        service.createComment(articleUuid, userId, req);

        verify(articleService).incrementCommentCount(articleId);
    }

    // ─── createComment reply (2 層) ───
    @Test
    void createComment_replyToTopLevel_succeeds() {
        UUID parentUuid = UUID.randomUUID();
        Comment parent = new Comment();
        parent.setId(50L); parent.setUuid(parentUuid);
        parent.setArticleId(articleId); parent.setParentId(null);  // top-level

        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(commentRepo.findByUuid(parentUuid)).thenReturn(Optional.of(parent));
        when(renderer.render(any())).thenReturn("<p>reply</p>");
        when(commentRepo.save(any())).thenAnswer(inv -> {
            Comment c = inv.getArgument(0); c.setId(99L); return c;
        });

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("reply");
        req.setParentUuid(parentUuid);

        service.createComment(articleUuid, userId, req);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepo).save(captor.capture());
        assertThat(captor.getValue().getParentId()).isEqualTo(50L);
    }

    @Test
    void createComment_replyToReply_throwsNestingTooDeep() {
        UUID parentUuid = UUID.randomUUID();
        Comment reply = new Comment();
        reply.setId(50L); reply.setUuid(parentUuid);
        reply.setArticleId(articleId);
        reply.setParentId(40L);   // 已經是 reply

        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(commentRepo.findByUuid(parentUuid)).thenReturn(Optional.of(reply));

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("nested"); req.setParentUuid(parentUuid);

        assertThatThrownBy(() -> service.createComment(articleUuid, userId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.NESTING_TOO_DEEP.getMessage());
    }

    @Test
    void createComment_parentNotInSameArticle_throws() {
        UUID parentUuid = UUID.randomUUID();
        Comment parent = new Comment();
        parent.setId(50L); parent.setUuid(parentUuid);
        parent.setArticleId(999L);    // 不同文章
        parent.setParentId(null);

        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(commentRepo.findByUuid(parentUuid)).thenReturn(Optional.of(parent));

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("x"); req.setParentUuid(parentUuid);

        assertThatThrownBy(() -> service.createComment(articleUuid, userId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.PARENT_NOT_IN_ARTICLE.getMessage());
    }

    @Test
    void createComment_parentSoftDeleted_throwsParentFrozen() {
        UUID parentUuid = UUID.randomUUID();
        Comment parent = new Comment();
        parent.setId(50L); parent.setUuid(parentUuid);
        parent.setArticleId(articleId);
        parent.setParentId(null);
        parent.setDeletedAt(LocalDateTime.now());     // 軟刪除

        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(commentRepo.findByUuid(parentUuid)).thenReturn(Optional.of(parent));

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("x"); req.setParentUuid(parentUuid);

        assertThatThrownBy(() -> service.createComment(articleUuid, userId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.PARENT_FROZEN.getMessage());
    }
}
```

- [ ] **Step 2: 跑紅燈**

```bash
./mvnw.cmd -pl blog-module-comment test -Dtest=CommentServiceTest 2>&1 | tee logs/comment-create-red.log
```

Expected: 編譯失敗（CommentService 還沒寫）。

- [ ] **Step 3: 實作 CommentService.createComment（最小綠燈）**

```java
package dowob.xyz.blog.module.comment.service;

import dowob.xyz.blog.common.api.exception.BusinessException;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.comment.exception.CommentErrorCode;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.dto.request.CreateCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.response.CommentResponse;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository commentRepo;
    private final CommentMapper commentMapper;
    private final CommentMarkdownRenderer renderer;
    private final ArticleService articleService;

    @Transactional
    public CommentResponse createComment(UUID articleUuid, Long userId, CreateCommentRequest req) {
        Long articleId = articleService.findIdByUuid(articleUuid);
        if (articleId == null) {
            throw new BusinessException(CommentErrorCode.PARENT_NOT_IN_ARTICLE);
            // 也可以另開 ARTICLE_NOT_FOUND，本 spec 簡化
        }

        Long parentId = null;
        if (req.getParentUuid() != null) {
            Comment parent = commentRepo.findByUuid(req.getParentUuid())
                    .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));
            // 1. 跨文章檢查
            if (!parent.getArticleId().equals(articleId)) {
                throw new BusinessException(CommentErrorCode.PARENT_NOT_IN_ARTICLE);
            }
            // 2. 2 層深度檢查
            if (parent.getParentId() != null) {
                throw new BusinessException(CommentErrorCode.NESTING_TOO_DEEP);
            }
            // 3. 凍結檢查
            if (parent.getDeletedAt() != null) {
                throw new BusinessException(CommentErrorCode.PARENT_FROZEN);
            }
            parentId = parent.getId();
        }

        String contentHtml = renderer.render(req.getContent());

        Comment c = new Comment();
        c.setUuid(UUID.randomUUID());        // 必設，記憶 Bug #2 教訓
        c.setArticleId(articleId);
        c.setParentId(parentId);
        c.setUserId(userId);
        c.setContent(req.getContent());
        c.setContentHtml(contentHtml);
        c.setLikeCount(0);
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        Comment saved = commentRepo.save(c);

        articleService.incrementCommentCount(articleId);

        // mapper 暫時略，下個 task listComments 時補完整 CommentResponse mapping
        CommentResponse resp = new CommentResponse();
        resp.setUuid(saved.getUuid());
        return resp;
    }
}
```

- [ ] **Step 4: 跑綠燈**

```bash
./mvnw.cmd -pl blog-module-comment test -Dtest=CommentServiceTest 2>&1 | tee logs/comment-create-green.log
```

Expected: 6 tests passed。

- [ ] **Step 5: Commit**

```bash
git add blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/service/CommentService.java \
        blog-module-comment/src/test/java/dowob/xyz/blog/module/comment/service/CommentServiceTest.java
git commit -m "feat(comment): CommentService.createComment 實作 + 2 層巢狀驗證

- top-level 與 reply 都能建立
- 2 層深度限制（NESTING_TOO_DEEP）
- 跨文章 reply 阻擋（PARENT_NOT_IN_ARTICLE）
- 軟刪除 parent 阻擋（PARENT_FROZEN）
- 跨模組透過 ArticleService.incrementCommentCount 更新計數
- 6 個 unit test 覆蓋

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: CommentService.editComment + 5min 窗 (TDD)

**Files:**
- Modify: `blog-module-comment/src/test/java/dowob/xyz/blog/module/comment/service/CommentServiceTest.java`（加 edit 測試）
- Modify: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/service/CommentService.java`（加 editComment）

- [ ] **Step 1: 加 editComment 測試（先紅）**

於 `CommentServiceTest.java` 加入：

```java
@Test
void editComment_within5Min_updatesContentAndSetsEditedAt() {
    UUID commentUuid = UUID.randomUUID();
    Comment c = new Comment();
    c.setId(1L); c.setUuid(commentUuid); c.setUserId(userId);
    c.setContent("original"); c.setContentHtml("<p>original</p>");
    c.setCreatedAt(LocalDateTime.now().minusMinutes(2));   // 2 分鐘前
    c.setDeletedAt(null);

    when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
    when(renderer.render("edited")).thenReturn("<p>edited</p>");
    when(commentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    EditCommentRequest req = new EditCommentRequest();
    req.setContent("edited");

    service.editComment(commentUuid, userId, /* isAdmin */ false, req);

    ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
    verify(commentRepo).save(captor.capture());
    Comment saved = captor.getValue();
    assertThat(saved.getContent()).isEqualTo("edited");
    assertThat(saved.getContentHtml()).isEqualTo("<p>edited</p>");
    assertThat(saved.getEditedAt()).isNotNull();
}

@Test
void editComment_after5Min_throwsEditWindowExpired() {
    UUID commentUuid = UUID.randomUUID();
    Comment c = new Comment();
    c.setId(1L); c.setUuid(commentUuid); c.setUserId(userId);
    c.setCreatedAt(LocalDateTime.now().minusMinutes(10));    // 10 分鐘前

    when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

    EditCommentRequest req = new EditCommentRequest();
    req.setContent("late");

    assertThatThrownBy(() -> service.editComment(commentUuid, userId, false, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(CommentErrorCode.EDIT_WINDOW_EXPIRED.getMessage());
}

@Test
void editComment_byAdminAfter5Min_succeeds() {
    UUID commentUuid = UUID.randomUUID();
    Comment c = new Comment();
    c.setId(1L); c.setUuid(commentUuid); c.setUserId(999L);     // 不是 admin
    c.setCreatedAt(LocalDateTime.now().minusMinutes(10));

    when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
    when(renderer.render(any())).thenReturn("<p>x</p>");
    when(commentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    EditCommentRequest req = new EditCommentRequest();
    req.setContent("admin edit");

    service.editComment(commentUuid, /* adminUserId */ 1L, /* isAdmin */ true, req);

    verify(commentRepo).save(any());
}

@Test
void editComment_byOtherUser_throwsAccessDenied() {
    UUID commentUuid = UUID.randomUUID();
    Comment c = new Comment();
    c.setId(1L); c.setUuid(commentUuid); c.setUserId(999L);     // 別人留的
    c.setCreatedAt(LocalDateTime.now());

    when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

    EditCommentRequest req = new EditCommentRequest();
    req.setContent("hijack");

    assertThatThrownBy(() -> service.editComment(commentUuid, userId, false, req))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
}

@Test
void editComment_softDeletedComment_throws() {
    UUID commentUuid = UUID.randomUUID();
    Comment c = new Comment();
    c.setId(1L); c.setUuid(commentUuid); c.setUserId(userId);
    c.setCreatedAt(LocalDateTime.now());
    c.setDeletedAt(LocalDateTime.now());      // 軟刪除

    when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

    EditCommentRequest req = new EditCommentRequest();
    req.setContent("ghost");

    assertThatThrownBy(() -> service.editComment(commentUuid, userId, false, req))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining(CommentErrorCode.COMMENT_DELETED.getMessage());
}
```

- [ ] **Step 2: 跑紅燈**

```bash
./mvnw.cmd -pl blog-module-comment test -Dtest=CommentServiceTest 2>&1 | tee logs/comment-edit-red.log
```

Expected: 編譯失敗 / `editComment` 不存在。

- [ ] **Step 3: 實作 editComment**

於 `CommentService.java` 加：

```java
import dowob.xyz.blog.module.comment.model.dto.request.EditCommentRequest;
import org.springframework.security.access.AccessDeniedException;
import java.time.Duration;

private static final Duration EDIT_WINDOW = Duration.ofMinutes(5);

@Transactional
public CommentResponse editComment(UUID commentUuid, Long currentUserId, boolean isAdmin,
                                     EditCommentRequest req) {
    Comment c = commentRepo.findByUuid(commentUuid)
            .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));

    if (c.getDeletedAt() != null) {
        throw new BusinessException(CommentErrorCode.COMMENT_DELETED);
    }
    if (!isAdmin && !c.getUserId().equals(currentUserId)) {
        throw new AccessDeniedException("不是留言作者");
    }
    if (!isAdmin) {
        Duration sinceCreate = Duration.between(c.getCreatedAt(), LocalDateTime.now());
        if (sinceCreate.compareTo(EDIT_WINDOW) > 0) {
            throw new BusinessException(CommentErrorCode.EDIT_WINDOW_EXPIRED);
        }
    }

    String contentHtml = renderer.render(req.getContent());
    c.setContent(req.getContent());
    c.setContentHtml(contentHtml);
    c.setEditedAt(LocalDateTime.now());
    c.setUpdatedAt(LocalDateTime.now());
    commentRepo.save(c);

    CommentResponse resp = new CommentResponse();
    resp.setUuid(c.getUuid());
    return resp;
}
```

- [ ] **Step 4: 跑綠燈**

```bash
./mvnw.cmd -pl blog-module-comment test -Dtest=CommentServiceTest 2>&1 | tee logs/comment-edit-green.log
```

Expected: 11 tests passed（6 + 5）。

- [ ] **Step 5: Commit**

```bash
git add blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/service/CommentService.java \
        blog-module-comment/src/test/java/dowob/xyz/blog/module/comment/service/CommentServiceTest.java
git commit -m "feat(comment): CommentService.editComment 實作 + 5 分鐘編輯窗

- 5 分鐘窗對 author 生效，Admin 不受限
- 軟刪除留言阻擋（COMMENT_DELETED）
- 非作者非 Admin 拋 AccessDeniedException
- editedAt 標記編輯時間
- 5 個 unit test 覆蓋

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: CommentService.deleteComment（軟刪除）(TDD)

**Files:**
- Modify: `CommentServiceTest.java`
- Modify: `CommentService.java`

- [ ] **Step 1: 加 delete 測試**

```java
@Test
void deleteComment_byOwner_softDeletesAndMarksAuthor() {
    UUID commentUuid = UUID.randomUUID();
    Comment c = new Comment();
    c.setId(1L); c.setUuid(commentUuid); c.setUserId(userId);
    c.setArticleId(articleId);
    c.setDeletedAt(null);

    when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

    service.deleteComment(commentUuid, userId, /* isAdmin */ false);

    verify(commentMapper).softDelete(1L, "AUTHOR");
    verify(articleService).decrementCommentCount(articleId);
}

@Test
void deleteComment_byAdmin_softDeletesAndMarksAdmin() {
    UUID commentUuid = UUID.randomUUID();
    Comment c = new Comment();
    c.setId(1L); c.setUuid(commentUuid); c.setUserId(999L);
    c.setArticleId(articleId);
    c.setDeletedAt(null);

    when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

    service.deleteComment(commentUuid, /* admin */ 1L, true);

    verify(commentMapper).softDelete(1L, "ADMIN");
    verify(articleService).decrementCommentCount(articleId);
}

@Test
void deleteComment_byOther_throwsAccessDenied() {
    UUID commentUuid = UUID.randomUUID();
    Comment c = new Comment();
    c.setId(1L); c.setUuid(commentUuid); c.setUserId(999L);
    c.setDeletedAt(null);

    when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

    assertThatThrownBy(() -> service.deleteComment(commentUuid, userId, false))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verify(commentMapper, never()).softDelete(any(), any());
}

@Test
void deleteComment_alreadyDeleted_isIdempotent() {
    UUID commentUuid = UUID.randomUUID();
    Comment c = new Comment();
    c.setId(1L); c.setUuid(commentUuid); c.setUserId(userId);
    c.setDeletedAt(LocalDateTime.now());     // 已刪除

    when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

    service.deleteComment(commentUuid, userId, false);

    verify(commentMapper, never()).softDelete(any(), any());
    verify(articleService, never()).decrementCommentCount(any());
}
```

- [ ] **Step 2: 跑紅燈**

```bash
./mvnw.cmd -pl blog-module-comment test -Dtest=CommentServiceTest 2>&1 | tee logs/comment-delete-red.log
```

- [ ] **Step 3: 實作 deleteComment**

於 `CommentService.java` 加：

```java
@Transactional
public void deleteComment(UUID commentUuid, Long currentUserId, boolean isAdmin) {
    Comment c = commentRepo.findByUuid(commentUuid)
            .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));

    if (c.getDeletedAt() != null) {
        return;     // idempotent：已刪除直接 return
    }
    if (!isAdmin && !c.getUserId().equals(currentUserId)) {
        throw new AccessDeniedException("不是留言作者");
    }

    String role = isAdmin && !c.getUserId().equals(currentUserId) ? "ADMIN" : "AUTHOR";
    commentMapper.softDelete(c.getId(), role);
    articleService.decrementCommentCount(c.getArticleId());
}
```

- [ ] **Step 4: 跑綠燈**

```bash
./mvnw.cmd -pl blog-module-comment test -Dtest=CommentServiceTest 2>&1 | tee logs/comment-delete-green.log
```

Expected: 15 tests passed（11 + 4）。

- [ ] **Step 5: Commit**

```bash
git add blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/service/CommentService.java \
        blog-module-comment/src/test/java/dowob/xyz/blog/module/comment/service/CommentServiceTest.java
git commit -m "feat(comment): CommentService.deleteComment 軟刪除實作

- 軟刪除（deleted_at + deleted_by_role 標記）
- 區分 AUTHOR / ADMIN（Admin 刪自己的還是顯示 AUTHOR）
- 跨模組透過 ArticleService.decrementCommentCount 更新計數
- 已刪除留言再刪是 idempotent
- 4 個 unit test 覆蓋

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 14: CommentService.listComments + Mapper 整合 (TDD)

**Files:**
- Modify: `CommentServiceTest.java`（加 list 測試）
- Modify: `CommentService.java`（加 listComments）

⚠ list 測試不容易純 unit mock；建議走 IT 路線在 Task 15 一起測。但 unit test 仍可覆蓋 service 邏輯（mock CommentMapper 回傳值）。

- [ ] **Step 1: 加 list 測試（unit）**

```java
@Test
void listComments_topLevelNewestFirstByDefault() {
    when(commentMapper.findTopLevelByArticle(articleId, "newest", 20, 0))
            .thenReturn(/* 假資料：兩筆 CommentWithAuthor 含軟刪佔位 */);
    when(commentMapper.findRepliesByParentIds(any())).thenReturn(/* 一筆 reply */);
    when(commentMapper.countByArticle(articleId)).thenReturn(3);
    when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);

    var resp = service.listComments(articleUuid, /* userId */ null, /* sort */ "newest", 1, 20);

    assertThat(resp.getTopLevels().getRecords()).hasSize(2);
    assertThat(resp.getTotalCommentCount()).isEqualTo(3);
}

@Test
void listComments_softDeletedTopLevelShownAsTombstone() {
    // 假資料：一筆軟刪除的 top-level + 一筆 reply
    CommentWithAuthor deletedTop = new CommentWithAuthor();
    deletedTop.setId(1L); deletedTop.setUuid(UUID.randomUUID());
    deletedTop.setContent("original"); deletedTop.setContentHtml("<p>x</p>");
    deletedTop.setDeletedAt(LocalDateTime.now());
    deletedTop.setDeletedByRole("ADMIN");
    deletedTop.setAuthorNickname("user");
    deletedTop.setLikeCount(5);

    when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
    when(commentMapper.findTopLevelByArticle(any(), any(), anyInt(), anyInt()))
            .thenReturn(List.of(deletedTop));
    when(commentMapper.findRepliesByParentIds(any())).thenReturn(List.of());
    when(commentMapper.countByArticle(any())).thenReturn(1);

    var resp = service.listComments(articleUuid, null, "newest", 1, 20);

    var first = resp.getTopLevels().getRecords().get(0);
    assertThat(first.getDeleted()).isTrue();
    assertThat(first.getContent()).isEmpty();
    assertThat(first.getContentHtml()).isEmpty();
    assertThat(first.getAuthor()).isNull();          // 隱私保護
    assertThat(first.getLikeCount()).isEqualTo(5);  // 仍顯示
    assertThat(first.getDeletedByRole()).isEqualTo("ADMIN");
}

@Test
void listComments_includesLikedFlagForCurrentUser() {
    // 略 — mock commentMapper.findLikedCommentIdsByUser 回 [1L]
    // 驗證 CommentResponse.liked 為 true 對應 id=1L 的留言
}

@Test
void listComments_unauthenticated_likedFlagAlwaysFalse() {
    when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
    when(commentMapper.findTopLevelByArticle(any(), any(), anyInt(), anyInt()))
            .thenReturn(/* 一筆正常 comment */);
    when(commentMapper.findRepliesByParentIds(any())).thenReturn(List.of());
    when(commentMapper.countByArticle(any())).thenReturn(1);

    var resp = service.listComments(articleUuid, /* userId */ null, "newest", 1, 20);

    assertThat(resp.getTopLevels().getRecords().get(0).getLiked()).isFalse();
    verify(commentMapper, never()).findLikedCommentIdsByUser(any(), any());
}
```

- [ ] **Step 2: 跑紅燈**

```bash
./mvnw.cmd -pl blog-module-comment test -Dtest=CommentServiceTest 2>&1 | tee logs/comment-list-red.log
```

- [ ] **Step 3: 實作 listComments**

於 `CommentService.java` 加：

```java
import dowob.xyz.blog.common.api.PageResult;
import dowob.xyz.blog.module.comment.model.CommentWithAuthor;
import dowob.xyz.blog.module.comment.model.dto.response.ArticleCommentListResponse;
import dowob.xyz.blog.module.comment.model.dto.response.AuthorSummary;
import dowob.xyz.blog.module.comment.model.dto.response.CommentResponse;

import java.util.*;
import java.util.stream.Collectors;

public ArticleCommentListResponse listComments(UUID articleUuid, Long currentUserId,
                                                 String sort, int page, int size) {
    Long articleId = articleService.findIdByUuid(articleUuid);
    if (articleId == null) return new ArticleCommentListResponse(emptyPage(page, size), 0);

    String sortKey = "oldest".equals(sort) ? "oldest" : "newest";
    int offset = Math.max(0, (page - 1) * size);

    // 1. top-level 列表
    List<CommentWithAuthor> topLevels = commentMapper.findTopLevelByArticle(articleId, sortKey, size, offset);

    // 2. replies 一次撈完
    List<Long> topLevelIds = topLevels.stream().map(CommentWithAuthor::getId).toList();
    List<CommentWithAuthor> replies = topLevelIds.isEmpty()
            ? List.of()
            : commentMapper.findRepliesByParentIds(topLevelIds);

    // 3. liked 集合（一次 batch）
    Set<Long> likedIds = (currentUserId == null) ? Set.of()
            : new HashSet<>(commentMapper.findLikedCommentIdsByUser(currentUserId,
                  joinIds(topLevels, replies)));

    // 4. 組裝回應
    Map<Long, List<CommentResponse>> repliesByParent = replies.stream()
            .map(r -> toResponse(r, likedIds))
            .collect(Collectors.groupingBy(CommentResponse::_internalParentId));
    // ... 略；實務做法是另存 parent_id map，CommentResponse 不含 parent_id 內部欄位

    List<CommentResponse> topLevelDtos = topLevels.stream()
            .map(t -> {
                CommentResponse dto = toResponse(t, likedIds);
                dto.setReplies(repliesByParent.getOrDefault(t.getId(), List.of()));
                return dto;
            })
            .toList();

    int totalTopLevel = commentMapper.countTopLevelByArticle(articleId);  // ⚠ 需新增此 mapper 方法
    int totalAll = commentMapper.countByArticle(articleId);

    PageResult<CommentResponse> pageResult = PageResult.of(topLevelDtos, page, size, totalTopLevel);
    return new ArticleCommentListResponse(pageResult, totalAll);
}

private CommentResponse toResponse(CommentWithAuthor c, Set<Long> likedIds) {
    CommentResponse dto = new CommentResponse();
    dto.setUuid(c.getUuid());
    dto.setParentUuid(c.getParentUuid());

    boolean isDeleted = c.getDeletedAt() != null;
    dto.setDeleted(isDeleted);
    dto.setDeletedByRole(c.getDeletedByRole());

    if (isDeleted) {
        dto.setContent("");
        dto.setContentHtml("");
        dto.setAuthor(null);
    } else {
        dto.setContent(c.getContent());
        dto.setContentHtml(c.getContentHtml());
        AuthorSummary author = new AuthorSummary(c.getAuthorUuid(), c.getAuthorNickname(), c.getAuthorAvatarUrl());
        dto.setAuthor(author);
    }
    dto.setLikeCount(c.getLikeCount());
    dto.setLiked(likedIds.contains(c.getId()));
    dto.setCreatedAt(c.getCreatedAt());
    dto.setEditedAt(c.getEditedAt());
    return dto;
}
```

⚠ 注意：示範略寫 `_internalParentId` 與 `joinIds` helper —— 實際實作時請按 Java 慣例補完。

⚠ 需要在 `CommentMapper` 加 `countTopLevelByArticle`：

```java
@Select("SELECT COUNT(*) FROM comments WHERE article_id = #{articleId} AND parent_id IS NULL")
int countTopLevelByArticle(@Param("articleId") Long articleId);
```

- [ ] **Step 4: 跑綠燈**

```bash
./mvnw.cmd -pl blog-module-comment test -Dtest=CommentServiceTest 2>&1 | tee logs/comment-list-green.log
```

Expected: 19 tests passed。

- [ ] **Step 5: Commit**

```bash
git add blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/service/CommentService.java \
        blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/mapper/CommentMapper.java \
        blog-module-comment/src/test/java/dowob/xyz/blog/module/comment/service/CommentServiceTest.java
git commit -m "feat(comment): CommentService.listComments 實作（含軟刪除佔位 + batch liked）

- top-level + replies 雙查詢，避免 N+1
- 軟刪除 row 顯示為佔位（content/contentHtml 清空、author 設 null）
- 排序切換（newest 預設、oldest 可選）
- 當前使用者的 liked 狀態 batch 查詢
- 4 個 unit test 覆蓋

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 15: CommentController + IT

**Files:**
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/controller/CommentController.java`
- Create: `blog-module-comment/src/test/java/.../config/CommentTestApplication.java`
- Create: `blog-module-comment/src/test/resources/application-test.yaml`
- Create: `blog-module-comment/src/test/java/.../controller/CommentControllerIT.java`

- [ ] **Step 1: CommentTestApplication.java（對齊既有 ArticleTestApplication）**

```java
package dowob.xyz.blog.module.comment.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

@SpringBootApplication(
        scanBasePackages = {
                "dowob.xyz.blog.common",
                "dowob.xyz.blog.infrastructure",
                "dowob.xyz.blog.module.article",
                "dowob.xyz.blog.module.comment"
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
        "dowob.xyz.blog.module.comment.repository"
})
@MapperScan(basePackages = {
        "dowob.xyz.blog.module.article.mapper",
        "dowob.xyz.blog.module.comment.mapper"
})
public class CommentTestApplication {
}
```

- [ ] **Step 2: application-test.yaml（對齊既有）**

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
mybatis:
  configuration:
    map-underscore-to-camel-case: true
    use-generated-keys: true
  type-aliases-package: dowob.xyz.blog.module
```

- [ ] **Step 3: CommentController**

```java
package dowob.xyz.blog.module.comment.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.comment.model.dto.request.CreateCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.request.EditCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.response.ArticleCommentListResponse;
import dowob.xyz.blog.module.comment.model.dto.response.CommentResponse;
import dowob.xyz.blog.module.comment.service.CommentService;
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
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Comment")
public class CommentController {

    private final CommentService commentService;

    @GetMapping("/articles/{articleUuid}/comments")
    @Operation(summary = "列出文章留言（含軟刪除佔位）")
    public ApiResponse<ArticleCommentListResponse> list(
            @PathVariable UUID articleUuid,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "newest") String sort,
            @AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(commentService.listComments(articleUuid, currentUserId, sort, page, size));
    }

    @PostMapping("/articles/{articleUuid}/comments")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "建立留言（top-level 或 reply）")
    public ApiResponse<CommentResponse> create(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateCommentRequest req) {
        return ApiResponse.success(commentService.createComment(articleUuid, userId, req));
    }

    @PutMapping("/comments/{uuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "編輯留言（5 分鐘窗，Admin 不受限）")
    public ApiResponse<CommentResponse> edit(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody EditCommentRequest req) {
        boolean isAdmin = SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return ApiResponse.success(commentService.editComment(uuid, userId, isAdmin, req));
    }

    @DeleteMapping("/comments/{uuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "軟刪除留言")
    public ApiResponse<Void> delete(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal Long userId) {
        boolean isAdmin = SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
        commentService.deleteComment(uuid, userId, isAdmin);
        return ApiResponse.success();
    }
}
```

- [ ] **Step 4: CommentControllerIT**

⚠ 因篇幅限制只列測試名與骨架；實作時對齊既有 `ArticleControllerIT` testcontainers 結構：

```java
@SpringBootTest(classes = CommentTestApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class CommentControllerIT {

    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    @Container static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);
    @DynamicPropertySource static void setProps(DynamicPropertyRegistry reg) { /* ... */ }

    @Autowired MockMvc mockMvc;
    @Autowired ArticleRepository articleRepo;
    @Autowired CommentRepository commentRepo;
    @Autowired ObjectMapper om;

    private UUID articleUuid;
    private final Long userId = 1L;
    private final Long adminUserId = 2L;

    @BeforeEach
    void setup() {
        // create test article
        Article a = ...; articleRepo.save(a);
        articleUuid = a.getUuid();
    }

    @Test void list_anonymous_returns200WithEmpty() {}
    @Test void post_unauthenticated_returns401() {}
    @Test void post_validMarkdown_returnsCommentResponse() {}
    @Test void post_replyToReply_returnsBusinessError_C0102() {}
    @Test void post_softDeletedParent_returns_C0106() {}
    @Test void put_within5Min_returns200() {}
    @Test void put_after5Min_returnsBusinessError_C0103() {}
    @Test void put_byOther_returns403() {}
    @Test void delete_byOwner_returns200_andRowSoftDeleted() {}
    @Test void delete_byAdmin_returns200() {}
    @Test void delete_byNonOwnerNonAdmin_returns403() {}
    @Test void list_softDeletedTopLevel_shownAsTombstone() {}
    @Test void list_sortNewest_default() {}
    @Test void list_sortOldest_works() {}
    @Test void create_thenList_articleCommentCount_isOne() {}
    @Test void delete_thenList_articleCommentCount_decremented() {}
}
```

- [ ] **Step 5: 跑 IT**

```bash
./mvnw.cmd -pl blog-module-comment test -Dtest=CommentControllerIT 2>&1 | tee logs/comment-it.log
```

Expected: 16 tests passed。

- [ ] **Step 6: Commit**

```bash
git add blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/controller/CommentController.java \
        blog-module-comment/src/test/
git commit -m "feat(comment): CommentController + IT

- 4 個端點：list / create / edit / delete
- list 公開；其他需登入
- Admin 透過 SecurityContext authorities 判斷
- IT 覆蓋 16 個 case：auth、巢狀、編輯窗、軟刪除、排序、計數同步

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 16: CommentLikeService + Controller + IT

**Files:**
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/service/CommentLikeService.java`
- Create: `blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/controller/CommentLikeController.java`
- Create: `blog-module-comment/src/test/java/.../service/CommentLikeServiceTest.java`
- Create: `blog-module-comment/src/test/java/.../controller/CommentLikeControllerIT.java`

CommentLike 結構與 ArticleLike 對稱，差異是「軟刪除留言不可按讚」。

- [ ] **Step 1: CommentLikeServiceTest（紅）**

```java
@ExtendWith(MockitoExtension.class)
class CommentLikeServiceTest {

    @Mock private CommentLikeRepository likeRepo;
    @Mock private CommentRepository commentRepo;
    @Mock private CommentMapper commentMapper;
    @InjectMocks private CommentLikeService service;

    private final Long userId = 10L;
    private final UUID commentUuid = UUID.randomUUID();
    private final Long commentId = 100L;

    @Test
    void likeComment_firstTime_createsRowAndIncrementsCount() {
        Comment c = new Comment(); c.setId(commentId); c.setUuid(commentUuid); c.setDeletedAt(null);
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(likeRepo.findByUserIdAndCommentId(userId, commentId)).thenReturn(Optional.empty());

        service.likeComment(commentUuid, userId);

        verify(likeRepo).save(any(CommentLike.class));
        verify(commentMapper).incrementLikeCount(commentId);
    }

    @Test
    void likeComment_alreadyLiked_isIdempotent() {
        Comment c = new Comment(); c.setId(commentId); c.setUuid(commentUuid); c.setDeletedAt(null);
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(likeRepo.findByUserIdAndCommentId(userId, commentId)).thenReturn(Optional.of(new CommentLike()));

        service.likeComment(commentUuid, userId);

        verify(likeRepo, never()).save(any());
        verify(commentMapper, never()).incrementLikeCount(any());
    }

    @Test
    void likeComment_softDeleted_throwsCommentDeleted() {
        Comment c = new Comment(); c.setId(commentId); c.setUuid(commentUuid);
        c.setDeletedAt(LocalDateTime.now());
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.likeComment(commentUuid, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.COMMENT_DELETED.getMessage());
    }

    @Test
    void unlikeComment_existing_deletesAndDecrements() {
        Comment c = new Comment(); c.setId(commentId); c.setUuid(commentUuid);
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(likeRepo.findByUserIdAndCommentId(userId, commentId)).thenReturn(Optional.of(new CommentLike()));

        service.unlikeComment(commentUuid, userId);

        verify(likeRepo).deleteByUserIdAndCommentId(userId, commentId);
        verify(commentMapper).decrementLikeCount(commentId);
    }

    @Test
    void unlikeComment_notLiked_isIdempotent() {
        Comment c = new Comment(); c.setId(commentId); c.setUuid(commentUuid);
        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(likeRepo.findByUserIdAndCommentId(userId, commentId)).thenReturn(Optional.empty());

        service.unlikeComment(commentUuid, userId);

        verify(likeRepo, never()).deleteByUserIdAndCommentId(any(), any());
        verify(commentMapper, never()).decrementLikeCount(any());
    }
}
```

- [ ] **Step 2: 跑紅燈**

```bash
./mvnw.cmd -pl blog-module-comment test -Dtest=CommentLikeServiceTest 2>&1 | tee logs/clike-red.log
```

- [ ] **Step 3: 實作 CommentLikeService**

```java
package dowob.xyz.blog.module.comment.service;

import dowob.xyz.blog.common.api.exception.BusinessException;
import dowob.xyz.blog.module.comment.exception.CommentErrorCode;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.CommentLike;
import dowob.xyz.blog.module.comment.repository.CommentLikeRepository;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CommentLikeService {

    private final CommentLikeRepository likeRepo;
    private final CommentRepository commentRepo;
    private final CommentMapper commentMapper;

    @Transactional
    public void likeComment(UUID commentUuid, Long userId) {
        Comment c = commentRepo.findByUuid(commentUuid)
                .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));
        if (c.getDeletedAt() != null) {
            throw new BusinessException(CommentErrorCode.COMMENT_DELETED);
        }
        if (likeRepo.findByUserIdAndCommentId(userId, c.getId()).isPresent()) return;

        CommentLike like = new CommentLike();
        like.setUserId(userId);
        like.setCommentId(c.getId());
        like.setCreatedAt(LocalDateTime.now());
        likeRepo.save(like);
        commentMapper.incrementLikeCount(c.getId());
    }

    @Transactional
    public void unlikeComment(UUID commentUuid, Long userId) {
        Comment c = commentRepo.findByUuid(commentUuid)
                .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));
        if (likeRepo.findByUserIdAndCommentId(userId, c.getId()).isEmpty()) return;

        likeRepo.deleteByUserIdAndCommentId(userId, c.getId());
        commentMapper.decrementLikeCount(c.getId());
    }
}
```

- [ ] **Step 4: CommentLikeController**

```java
@RestController
@RequestMapping("/api/v1/comments/{uuid}/like")
@RequiredArgsConstructor
@Tag(name = "Comment Like")
public class CommentLikeController {

    private final CommentLikeService likeService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> like(@AuthenticationPrincipal Long userId, @PathVariable UUID uuid) {
        likeService.likeComment(uuid, userId);
        return ApiResponse.success();
    }

    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> unlike(@AuthenticationPrincipal Long userId, @PathVariable UUID uuid) {
        likeService.unlikeComment(uuid, userId);
        return ApiResponse.success();
    }
}
```

- [ ] **Step 5: CommentLikeControllerIT**

對齊 ArticleLikeControllerIT 結構：

```java
@SpringBootTest(classes = CommentTestApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class CommentLikeControllerIT {
    // ... container setup ...

    @Test void like_returns200() {}
    @Test void like_idempotent() {}
    @Test void like_softDeletedComment_returns_C0104() {}
    @Test void unlike_returns200() {}
    @Test void unlike_idempotent() {}
    @Test void like_unauthenticated_returns401() {}
}
```

- [ ] **Step 6: 跑全綠**

```bash
./mvnw.cmd -pl blog-module-comment test 2>&1 | tee logs/comment-all-green.log
```

Expected: 所有 unit + IT 通過。

- [ ] **Step 7: Commit**

```bash
git add blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/service/CommentLikeService.java \
        blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/controller/CommentLikeController.java \
        blog-module-comment/src/test/java/dowob/xyz/blog/module/comment/service/CommentLikeServiceTest.java \
        blog-module-comment/src/test/java/dowob/xyz/blog/module/comment/controller/CommentLikeControllerIT.java
git commit -m "feat(comment): CommentLikeService + Controller + IT

- POST /api/v1/comments/{uuid}/like（idempotent；軟刪除留言阻擋 C0104）
- DELETE /api/v1/comments/{uuid}/like（idempotent）
- 5 unit + 6 IT 測試覆蓋

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 17: ArticleSummaryResponse / ArticleResponse 加 `liked` 欄位

**Files:**
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/dto/response/ArticleSummaryResponse.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/dto/response/ArticleResponse.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java`（list 與 get 流程加 batch is-liked）

- [ ] **Step 1: 加 liked 欄位到兩個 Response DTO**

於 `ArticleSummaryResponse.java` 與 `ArticleResponse.java` 各加：

```java
private Boolean liked;
```

未登入或未按讚永遠為 `false`。

- [ ] **Step 2: 修改 ArticleServiceImpl 的 listPublishedArticles / getArticle 等**

找出產生 `ArticleSummaryResponse` 的方法（通常 `getPublishedArticles` / `getMyArticles` / `getArticleByUuid`），在轉 DTO 之後加：

```java
// 在 list 方法尾段：
List<Long> articleIds = result.getRecords().stream().map(ArticleSummaryResponse::getId).toList();
Long currentUserId = /* SecurityContext 取，未登入回 null */;
Set<Long> likedIds = articleLikeService.batchIsLiked(currentUserId, articleIds);
result.getRecords().forEach(r -> r.setLiked(likedIds.contains(r.getId())));
```

注意：`ArticleSummaryResponse` 可能沒 `id` 欄位（前端用 uuid）。若沒有，臨時 expose 或在 service 內維持 `Map<UUID, Long>` 對應。

對於 `getArticleByUuid` 走單筆查詢路徑：

```java
ArticleResponse resp = ...; // 既有邏輯
if (currentUserId != null) {
    resp.setLiked(articleLikeService.isLiked(currentUserId, articleId));
} else {
    resp.setLiked(false);
}
```

- [ ] **Step 3: 跑 article 既有測試確認沒退化**

```bash
./mvnw.cmd -pl blog-module-article test 2>&1 | tee logs/article-liked-field.log
```

可能需要修一些既有測試的 expected response（加 `liked: false`）。

- [ ] **Step 4: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/dto/response/ \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java \
        blog-module-article/src/test/
git commit -m "feat(article): ArticleResponse / ArticleSummaryResponse 加 liked 欄位

- 列表查詢用 batchIsLiked 一次撈完，避免 N+1
- 詳情查詢走 isLiked(userId, articleId)
- 未登入永遠 false
- 既有測試補 liked: false 的 expected

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 18: 跨模組整合測試（IT）

**Files:**
- Modify: `blog-module-comment/src/test/java/dowob/xyz/blog/module/comment/controller/CommentControllerIT.java`（補幾個整合 case）

- [ ] **Step 1: 加 Article-Comment-Like 整合 IT**

```java
@Test
void articleSummary_includesCommentCountAndLikeCountAfterActions() throws Exception {
    // 1. 建文章
    // 2. 建 3 筆留言（2 top-level + 1 reply）
    // 3. 按讚 1 篇
    // 4. GET /api/v1/articles
    // 5. 驗證 article.commentCount == 3, article.likeCount == 1
}

@Test
void deleteArticle_cascadeDeletesCommentsAndLikes() throws Exception {
    // 1. 建文章
    // 2. 建留言 + comment_like
    // 3. 按讚文章
    // 4. DELETE /api/v1/articles/{uuid}
    // 5. 驗證 comments / article_likes / comment_likes 全清空
}

@Test
void deleteComment_cascadeDeletesCommentLikes() throws Exception {
    // 1. 建留言 + comment_like
    // 2. service 硬刪除 comment（測試走 repository.deleteById 觸發 CASCADE）
    // 3. 驗證 comment_likes 也消失
}

@Test
void articleSummary_liked_reflectsCurrentUserState() throws Exception {
    // 1. 建文章
    // 2. user1 按讚
    // 3. 用 user1 GET /articles → liked: true
    // 4. 用 user2 GET /articles → liked: false
    // 5. 匿名 GET → liked: false
}
```

- [ ] **Step 2: 跑全套**

```bash
./mvnw.cmd test 2>&1 | tee logs/all-tests-final.log
```

Expected: 全綠。

- [ ] **Step 3: Commit**

```bash
git add blog-module-comment/src/test/java/dowob/xyz/blog/module/comment/controller/CommentControllerIT.java
git commit -m "test(comment): 加 Article-Comment-Like 跨模組整合 IT

- commentCount / likeCount 反正規化驗證
- 文章硬刪除連帶清 comments / likes（CASCADE）
- 留言硬刪除連帶清 comment_likes
- liked 欄位反映當前使用者狀態

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review Checklist

實作完成後請勾選：

- [ ] 所有 task 的 unit + IT 測試全綠
- [ ] V13 migration 已套用（`flyway_schema_history.installed_rank` 含 V13）
- [ ] `articles.like_count` 為 INTEGER 型別
- [ ] `idx_articles_uuid` 已被刪除
- [ ] `comments` 表有 5 個新欄位 + 3 個新索引
- [ ] `comment_likes` 表已建立
- [ ] `article_likes` UNIQUE 順序為 `(user_id, article_id)`
- [ ] `blog-module-article` 全部從 commonmark 切換到 flexmark，渲染測試無回退
- [ ] `blog-module-comment` 模組正確註冊到 root pom + blog-start
- [ ] CommentMarkdownRenderer：粗體/斜體/inline code/連結/引用支援；圖片/標題/程式碼區塊禁用
- [ ] CommentService：4 個業務方法（create / edit / delete / list）皆有 TDD 覆蓋
- [ ] ArticleLikeService / CommentLikeService 兩者 idempotent + 軟刪除留言阻擋
- [ ] ArticleSummaryResponse / ArticleResponse 包含 `liked` 欄位

---

## 後續 task（不在本 plan 內）

- task #7：建立 `ai-docs/schema.md` + 更新 `flyway-convention.md`（索引命名規範）+ 更新 `architecture.md`（中庸級別模組邊界）+ 更新 CLAUDE.md + 寫 auto memory
- 批 2：Bookmark + Highlight & Note + Reading Progress（另一份 spec/plan）
