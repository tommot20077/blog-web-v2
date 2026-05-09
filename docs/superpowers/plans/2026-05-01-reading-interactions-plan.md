# Reading Interactions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 實作三個「使用者-文章 私人狀態」功能：Bookmark（收藏）/ Highlight & Note（劃線+註記）/ Reading Progress（閱讀進度），並把 `bookmarked` / `lastReadProgress` 欄位整合進 ArticleResponse。

**Architecture:** 新增 `blog-module-reading` 模組，內含 3 個獨立 service。Reading Progress 採 Redis 主 + 5 分鐘 flush 到 DB 的 write-buffer pattern（Redis TTL 3 天，progress >= 0.95 視為完成）。沿用批 1 的 `ArticleQueryService` Read 層注入機制擴充新欄位，無循環依賴。

**Tech Stack:** Spring Boot, Spring Data JDBC, MyBatis, PostgreSQL, Spring Data Redis, Flyway, Spring `@Scheduled`, JUnit 5, Mockito, Spring Security Test, Testcontainers (PostgreSQL + Redis).

**Spec:** `docs/superpowers/specs/2026-05-01-reading-interactions-design.md`

---

## File Map

### 新增檔案

```
blog-db-migration/
└─ src/main/resources/db/migration/V14__add_reading_interactions.sql

blog-module-reading/                                     [NEW MODULE]
├─ pom.xml
├─ src/main/java/dowob/xyz/blog/module/reading/
│  ├─ controller/
│  │   ├─ BookmarkController.java
│  │   ├─ HighlightController.java
│  │   └─ ReadingProgressController.java
│  ├─ service/
│  │   ├─ BookmarkService.java
│  │   ├─ HighlightService.java
│  │   └─ ReadingProgressService.java
│  ├─ repository/
│  │   ├─ UserBookmarkRepository.java
│  │   ├─ UserHighlightRepository.java
│  │   └─ UserReadingProgressRepository.java
│  ├─ mapper/
│  │   ├─ BookmarkMapper.java
│  │   └─ ReadingProgressMapper.java
│  ├─ model/
│  │   ├─ UserBookmark.java
│  │   ├─ UserHighlight.java
│  │   ├─ UserReadingProgress.java
│  │   └─ dto/
│  │       ├─ request/
│  │       │   ├─ CreateHighlightRequest.java
│  │       │   ├─ UpdateHighlightRequest.java
│  │       │   └─ UpdateProgressRequest.java
│  │       └─ response/
│  │           ├─ HighlightResponse.java
│  │           └─ ProgressResponse.java
│  ├─ exception/
│  │   └─ ReadingErrorCode.java
│  ├─ job/
│  │   └─ ReadingProgressFlushJob.java
│  └─ config/
│      └─ (placeholder)
└─ src/test/
   ├─ java/dowob/xyz/blog/module/reading/
   │  ├─ config/ReadingTestApplication.java
   │  ├─ service/BookmarkServiceTest.java
   │  ├─ service/HighlightServiceTest.java
   │  ├─ service/ReadingProgressServiceTest.java
   │  ├─ controller/BookmarkControllerIT.java
   │  ├─ controller/HighlightControllerIT.java
   │  ├─ controller/ReadingProgressControllerIT.java
   │  ├─ job/ReadingProgressFlushJobTest.java
   │  └─ integration/CrossModuleReadingIT.java
   └─ resources/application-test.yaml

blog-common/
└─ src/main/java/dowob/xyz/blog/common/constant/RedisKeyConstant.java   [MODIFY]

blog-module-article/                                     [MODIFICATIONS]
├─ src/main/java/dowob/xyz/blog/module/article/
│  ├─ mapper/
│  │   └─ ArticleMapper.java                            (MODIFY — add findUuidsByIds)
│  ├─ service/
│  │   └─ ArticleQueryService.java                      (MODIFY — inject 2 new services + enrich)
│  └─ model/dto/response/
│      ├─ ArticleSummaryResponse.java                   (MODIFY — add bookmarked + lastReadProgress)
│      └─ ArticleResponse.java                          (MODIFY — same fields)

ai-docs/schema.md                                        [MODIFY — add V14 tables]

pom.xml (root)                                           [MODIFY — add blog-module-reading]
blog-start/pom.xml                                       [MODIFY — add dependency]
```

---

## Pre-Flight Notes

1. **Worktree**：plan 假設在 `.worktrees/feature-reading-interactions/` 內執行
2. **依賴批 1**：base 在 `feature/reading-interactions` 分支，繼承批 1 全部 commits（包含 ArticleQueryService、CommentService 等）。批 1 PR (#27) merge 後須 rebase 到 develop
3. **Maven**：`./mvnw.cmd`（Windows）
4. **測試輸出**：`./mvnw.cmd test ... 2>&1 | tee logs/<task>.log`
5. **Surefire 報告**：失敗時優先讀 `<module>/target/surefire-reports/TEST-*.xml`
6. **Commit 慣例**：Conventional Commits + 繁中描述 + Co-Authored-By 行
7. **既有 patterns**（從批 1 學到）：
   - Spring Data JDBC entity 用 `@Table` + `@Column` + `@CreatedDate`/`@LastModifiedDate`（不是 JPA）
   - UUID 必設 `setUuid(UUID.randomUUID())`，不要依賴 DB DEFAULT（記憶 Bug #2）
   - `BusinessException` 路徑：`dowob.xyz.blog.common.exception.BusinessException`
   - GlobalExceptionHandler 行為：BusinessException → HTTP 400 + body code；AccessDeniedException → HTTP 403 + body code "A0006"
   - IT 需要 `R__seed.sql` 在 `src/test/resources/db/testdata/` 提供 FK 用 users
   - application-test.yaml 必須有 `mybatis.type-handlers-package: dowob.xyz.blog.infrastructure.config`
   - mapper UUID 查詢用 `WHERE col = #{val}::uuid` cast

---

## Task 1: V14 Migration

**Files:**
- Create: `blog-db-migration/src/main/resources/db/migration/V14__add_reading_interactions.sql`

- [ ] **Step 1: Write V14 SQL**

```sql
-- V14__add_reading_interactions.sql

-- ─────────────────────────────────────────────
-- 1. user_bookmarks（純 flag）
-- ─────────────────────────────────────────────
CREATE TABLE user_bookmarks (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT    NOT NULL REFERENCES users(id),
    article_id BIGINT    NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_bookmarks_user_article UNIQUE (user_id, article_id)
);

-- ─────────────────────────────────────────────
-- 2. user_highlights
-- ─────────────────────────────────────────────
CREATE TABLE user_highlights (
    id         BIGSERIAL    PRIMARY KEY,
    uuid       UUID         NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    user_id    BIGINT       NOT NULL REFERENCES users(id),
    article_id BIGINT       NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    snippet    TEXT         NOT NULL,
    prefix     VARCHAR(64)  NOT NULL DEFAULT '',
    suffix     VARCHAR(64)  NOT NULL DEFAULT '',
    color      VARCHAR(7)   NOT NULL DEFAULT '#FFEB3B',
    note       TEXT         NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_user_highlights_color CHECK (color ~ '^#[0-9A-Fa-f]{6}$')
);

CREATE INDEX idx_user_highlights_article_for_user
    ON user_highlights(user_id, article_id);

CREATE INDEX idx_user_highlights_user_recent
    ON user_highlights(user_id, created_at DESC);

-- ─────────────────────────────────────────────
-- 3. user_reading_progress
-- ─────────────────────────────────────────────
CREATE TABLE user_reading_progress (
    id                  BIGSERIAL    PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users(id),
    article_id          BIGINT       NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    progress            NUMERIC(4,3) NOT NULL,
    last_heading_anchor VARCHAR(255) NULL,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_reading_progress_user_article UNIQUE (user_id, article_id),
    CONSTRAINT chk_user_reading_progress_range CHECK (progress >= 0 AND progress <= 1)
);

CREATE INDEX idx_user_reading_progress_user_recent
    ON user_reading_progress(user_id, updated_at DESC);
```

- [ ] **Step 2: 跑既有 article IT 驗證 migration**

```bash
cd D:/end/workspace/java/blog-web-v2/.worktrees/feature-reading-interactions
mkdir -p logs
./mvnw.cmd -pl blog-module-article test -Dtest=ArticleControllerIT 2>&1 | tee logs/v14-validate.log
```

Expected: BUILD SUCCESS + log 顯示 `Migrating schema "public" to version "14 - add reading interactions"` + 既有 article 測試全綠。

- [ ] **Step 3: Commit**

```bash
git add blog-db-migration/src/main/resources/db/migration/V14__add_reading_interactions.sql
git commit -m "$(cat <<'EOF'
feat(reading): 新增 V14 migration 建立 reading interactions 三張表

- user_bookmarks: 收藏（user × article 1:1）
- user_highlights: 劃線 + 私人 note，含 hex 色號 CHECK constraint
- user_reading_progress: 閱讀進度（NUMERIC(4,3) 0-1 範圍）
- 全部 user_id 走 NO ACTION（user 軟刪除，FK 不觸發）
- article_id 走 ON DELETE CASCADE

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: blog-module-reading 模組骨架

**Files:**
- Create: `blog-module-reading/pom.xml`
- Modify: `pom.xml` (root)
- Modify: `blog-start/pom.xml`

- [ ] **Step 1: 建模組目錄與 pom.xml**

```bash
cd D:/end/workspace/java/blog-web-v2/.worktrees/feature-reading-interactions
mkdir -p blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/{controller,service,repository,mapper,model/dto/request,model/dto/response,exception,job,config}
mkdir -p blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/{config,service,controller,job,integration}
mkdir -p blog-module-reading/src/test/resources
```

`blog-module-reading/pom.xml`：

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

    <artifactId>blog-module-reading</artifactId>

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

於 `pom.xml` 找 `<modules>` 區塊，在 `blog-module-comment` 之後 / `blog-start` 之前加入：

```xml
<module>blog-module-reading</module>
```

於 `<dependencyManagement>` 的 Internal Modules 區塊（`blog-module-comment` 之後）加入：

```xml
<dependency>
    <groupId>dowob.xyz</groupId>
    <artifactId>blog-module-reading</artifactId>
    <version>${blog.version}</version>
</dependency>
```

- [ ] **Step 3: 加 dependency 到 blog-start/pom.xml**

```xml
<dependency>
    <groupId>dowob.xyz</groupId>
    <artifactId>blog-module-reading</artifactId>
</dependency>
```

放在 `blog-module-comment` 之後。

- [ ] **Step 4: 驗證 mvn 編譯**

```bash
./mvnw.cmd -pl blog-module-reading -am compile 2>&1 | tee logs/reading-skeleton.log
```

Expected: BUILD SUCCESS（空模組無 .java，pom 解析成功即可）。

- [ ] **Step 5: Commit**

```bash
git add blog-module-reading/pom.xml pom.xml blog-start/pom.xml
git commit -m "$(cat <<'EOF'
feat(reading): 新增 blog-module-reading 模組骨架

- 註冊到 root pom modules + dependencyManagement
- 依賴 blog-infrastructure / blog-module-article
- 加入 blog-start dependency

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: RedisKeyConstant 擴充

**Files:**
- Modify: `blog-common/src/main/java/dowob/xyz/blog/common/constant/RedisKeyConstant.java`

- [ ] **Step 1: 加 Reading Progress 相關常數**

於 `RedisKeyConstant.java` 既有常數區塊末尾加：

```java
// ─────────────────────────────────────────────
// Reading Progress（批 2 - V14）
// ─────────────────────────────────────────────

/** 閱讀進度 Hash key prefix；完整 key = "reading:progress:{userId}:{articleUuid}" */
public static final String READING_PROGRESS_PREFIX = "reading:progress:";

/** 待 flush dirty Set；members = "{userId}:{articleUuid}" */
public static final String READING_DIRTY_KEY = "reading:dirty";

/** Reading progress Redis key TTL（天）*/
public static final long READING_PROGRESS_TTL_DAYS = 3L;

/** 視為已讀完的進度門檻 */
public static final java.math.BigDecimal READING_PROGRESS_COMPLETED_THRESHOLD = new java.math.BigDecimal("0.95");
```

- [ ] **Step 2: 編譯驗證**

```bash
./mvnw.cmd -pl blog-common compile 2>&1 | tee logs/redis-constant.log
```

Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add blog-common/src/main/java/dowob/xyz/blog/common/constant/RedisKeyConstant.java
git commit -m "$(cat <<'EOF'
feat(common): RedisKeyConstant 加入 Reading Progress 常數

- READING_PROGRESS_PREFIX / READING_DIRTY_KEY
- READING_PROGRESS_TTL_DAYS = 3
- READING_PROGRESS_COMPLETED_THRESHOLD = 0.95

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Entities + Repositories

**Files:**
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/model/UserBookmark.java`
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/model/UserHighlight.java`
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/model/UserReadingProgress.java`
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/repository/UserBookmarkRepository.java`
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/repository/UserHighlightRepository.java`
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/repository/UserReadingProgressRepository.java`

- [ ] **Step 1: UserBookmark.java**

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

/**
 * 文章收藏紀錄（user_bookmarks 表映射）。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user_bookmarks")
public class UserBookmark {
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

- [ ] **Step 2: UserHighlight.java**

```java
package dowob.xyz.blog.module.reading.model;

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
 * 文字劃線 + 註記（user_highlights 表映射）。
 *
 * <p>定位策略：snippet + prefix/suffix anchor — 文章編輯後 self-healing。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user_highlights")
public class UserHighlight {
    @Id
    private Long id;

    private UUID uuid;        // setUuid(UUID.randomUUID()) before save

    @Column("user_id")
    private Long userId;

    @Column("article_id")
    private Long articleId;

    private String snippet;
    private String prefix;
    private String suffix;
    private String color;     // hex
    private String note;      // nullable

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: UserReadingProgress.java**

```java
package dowob.xyz.blog.module.reading.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 閱讀進度（user_reading_progress 表映射）。
 *
 * <p>Redis 主要儲存，DB 為 5 分鐘 flush 後的備份。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user_reading_progress")
public class UserReadingProgress {
    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("article_id")
    private Long articleId;

    private BigDecimal progress;     // NUMERIC(4,3) 0-1

    @Column("last_heading_anchor")
    private String lastHeadingAnchor;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 4: UserBookmarkRepository.java**

```java
package dowob.xyz.blog.module.reading.repository;

import dowob.xyz.blog.module.reading.model.UserBookmark;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserBookmarkRepository extends CrudRepository<UserBookmark, Long> {
    Optional<UserBookmark> findByUserIdAndArticleId(Long userId, Long articleId);
    void deleteByUserIdAndArticleId(Long userId, Long articleId);
}
```

- [ ] **Step 5: UserHighlightRepository.java**

```java
package dowob.xyz.blog.module.reading.repository;

import dowob.xyz.blog.module.reading.model.UserHighlight;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserHighlightRepository extends CrudRepository<UserHighlight, Long> {
    Optional<UserHighlight> findByUuid(UUID uuid);
    List<UserHighlight> findByUserIdAndArticleIdOrderByCreatedAtAsc(Long userId, Long articleId);
}
```

- [ ] **Step 6: UserReadingProgressRepository.java**

```java
package dowob.xyz.blog.module.reading.repository;

import dowob.xyz.blog.module.reading.model.UserReadingProgress;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserReadingProgressRepository extends CrudRepository<UserReadingProgress, Long> {
    Optional<UserReadingProgress> findByUserIdAndArticleId(Long userId, Long articleId);
    List<UserReadingProgress> findByUserIdAndArticleIdIn(Long userId, List<Long> articleIds);
}
```

- [ ] **Step 7: 編譯驗證**

```bash
./mvnw.cmd -pl blog-module-reading -am compile 2>&1 | tee logs/reading-entities.log
```

Expected: BUILD SUCCESS。

- [ ] **Step 8: Commit**

```bash
git add blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/model/ \
        blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/repository/
git commit -m "$(cat <<'EOF'
feat(reading): 新增 3 個 entity 與對應 CrudRepository

- UserBookmark / UserHighlight / UserReadingProgress
- 全部用 @CreatedDate / @LastModifiedDate / @Column 對齊既有慣例
- UserHighlight 含 uuid（setUuid before save）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: ReadingErrorCode + DTOs

**Files:**
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/exception/ReadingErrorCode.java`
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/model/dto/request/CreateHighlightRequest.java`
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/model/dto/request/UpdateHighlightRequest.java`
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/model/dto/request/UpdateProgressRequest.java`
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/model/dto/response/HighlightResponse.java`
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/model/dto/response/ProgressResponse.java`

- [ ] **Step 1: ReadingErrorCode.java**

```java
package dowob.xyz.blog.module.reading.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Reading 模組錯誤碼。範圍：R02 / R03
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum ReadingErrorCode implements IErrorCode {

    /** Highlight 不存在 */
    HIGHLIGHT_NOT_FOUND("R0201", "Highlight 不存在"),

    /** 不可變更他人的 Highlight */
    HIGHLIGHT_ACCESS_DENIED("R0202", "不可變更他人的 Highlight"),

    /** 進度值超出範圍 */
    PROGRESS_OUT_OF_RANGE("R0301", "進度值超出範圍");

    private final String code;
    private final String message;
}
```

- [ ] **Step 2: CreateHighlightRequest.java**

```java
package dowob.xyz.blog.module.reading.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateHighlightRequest {

    @NotBlank
    @Size(max = 500)
    private String snippet;

    @Size(max = 64)
    private String prefix = "";

    @Size(max = 64)
    private String suffix = "";

    @NotBlank
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
    private String color;

    @Size(max = 2000)
    private String note;
}
```

- [ ] **Step 3: UpdateHighlightRequest.java**

```java
package dowob.xyz.blog.module.reading.model.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新 Highlight — color / note 皆 optional，僅當非 null 才更新。
 */
@Data
public class UpdateHighlightRequest {

    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
    private String color;

    @Size(max = 2000)
    private String note;
}
```

- [ ] **Step 4: UpdateProgressRequest.java**

```java
package dowob.xyz.blog.module.reading.model.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateProgressRequest {

    @NotNull
    @DecimalMin("0.000")
    @DecimalMax("1.000")
    private BigDecimal progress;

    @Size(max = 255)
    private String lastHeading;
}
```

- [ ] **Step 5: HighlightResponse.java**

```java
package dowob.xyz.blog.module.reading.model.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class HighlightResponse {
    private UUID uuid;
    private String snippet;
    private String prefix;
    private String suffix;
    private String color;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 6: ProgressResponse.java**

```java
package dowob.xyz.blog.module.reading.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProgressResponse {
    private BigDecimal progress;
    private String lastHeading;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 7: 編譯**

```bash
./mvnw.cmd -pl blog-module-reading -am compile 2>&1 | tee logs/reading-dto.log
```

Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/exception/ \
        blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/model/dto/
git commit -m "$(cat <<'EOF'
feat(reading): 新增 ReadingErrorCode + request/response DTOs

- ReadingErrorCode: R0201 / R0202 / R0301
- CreateHighlightRequest（snippet / prefix / suffix / color / note 校驗）
- UpdateHighlightRequest（color / note 皆 optional）
- UpdateProgressRequest（@DecimalMin/Max 守備 0-1）
- HighlightResponse / ProgressResponse

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: BookmarkMapper + BookmarkService (TDD)

**Files:**
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/mapper/BookmarkMapper.java`
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/BookmarkService.java`
- Create: `blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/service/BookmarkServiceTest.java`

- [ ] **Step 1: BookmarkMapper.java**

```java
package dowob.xyz.blog.module.reading.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Bookmark MyBatis Mapper：批次查詢給 ArticleQueryService 用。
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface BookmarkMapper {

    /**
     * 批次查詢「使用者收藏過哪些文章」。
     *
     * @return 已收藏的 article_id 集合
     */
    @Select({
        "<script>",
        "SELECT article_id FROM user_bookmarks",
        " WHERE user_id = #{userId}",
        "   AND article_id IN",
        "<foreach collection='articleIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "</script>"
    })
    List<Long> findBookmarkedArticleIdsByUser(@Param("userId") Long userId,
                                                @Param("articleIds") List<Long> articleIds);

    /**
     * 我的收藏文章 id 列表（分頁，最新優先）—— 給 my-bookmarks 端點用。
     */
    @Select("""
            SELECT article_id FROM user_bookmarks
             WHERE user_id = #{userId}
             ORDER BY created_at DESC
             LIMIT #{size} OFFSET #{offset}
            """)
    List<Long> findMyBookmarkedArticleIds(@Param("userId") Long userId,
                                            @Param("size") int size,
                                            @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM user_bookmarks WHERE user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);
}
```

- [ ] **Step 2: 寫失敗測試 BookmarkServiceTest.java**

```java
package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.module.reading.mapper.BookmarkMapper;
import dowob.xyz.blog.module.reading.model.UserBookmark;
import dowob.xyz.blog.module.reading.repository.UserBookmarkRepository;
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
class BookmarkServiceTest {

    @Mock private UserBookmarkRepository repo;
    @Mock private BookmarkMapper mapper;
    @InjectMocks private BookmarkService service;

    private final Long userId = 1L;
    private final Long articleId = 100L;

    @Test
    void bookmark_firstTime_createsRow() {
        when(repo.findByUserIdAndArticleId(userId, articleId)).thenReturn(Optional.empty());

        service.bookmark(userId, articleId);

        verify(repo, times(1)).save(any(UserBookmark.class));
    }

    @Test
    void bookmark_alreadyBookmarked_isIdempotent() {
        when(repo.findByUserIdAndArticleId(userId, articleId))
                .thenReturn(Optional.of(new UserBookmark()));

        service.bookmark(userId, articleId);

        verify(repo, never()).save(any());
    }

    @Test
    void unbookmark_existing_deletesRow() {
        when(repo.findByUserIdAndArticleId(userId, articleId))
                .thenReturn(Optional.of(new UserBookmark()));

        service.unbookmark(userId, articleId);

        verify(repo, times(1)).deleteByUserIdAndArticleId(userId, articleId);
    }

    @Test
    void unbookmark_notBookmarked_isIdempotent() {
        when(repo.findByUserIdAndArticleId(userId, articleId)).thenReturn(Optional.empty());

        service.unbookmark(userId, articleId);

        verify(repo, never()).deleteByUserIdAndArticleId(any(), any());
    }

    @Test
    void isBookmarked_returnsTrueWhenBookmarked() {
        when(repo.findByUserIdAndArticleId(userId, articleId))
                .thenReturn(Optional.of(new UserBookmark()));

        assertThat(service.isBookmarked(userId, articleId)).isTrue();
    }

    @Test
    void batchIsBookmarked_returnsCorrectIdSet() {
        List<Long> articleIds = List.of(1L, 2L, 3L);
        when(mapper.findBookmarkedArticleIdsByUser(eq(userId), eq(articleIds)))
                .thenReturn(List.of(1L, 3L));

        Set<Long> result = service.batchIsBookmarked(userId, articleIds);

        assertThat(result).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void batchIsBookmarked_unauthenticated_returnsEmpty() {
        Set<Long> result = service.batchIsBookmarked(null, List.of(1L, 2L));

        assertThat(result).isEmpty();
        verify(mapper, never()).findBookmarkedArticleIdsByUser(any(), any());
    }
}
```

⚠ 注意：以上有 7 個測試（plan 寫 6 個 + batch unauth）。執行時補完即可。

- [ ] **Step 3: Run red**

```bash
./mvnw.cmd -pl blog-module-reading test -Dtest=BookmarkServiceTest 2>&1 | tee logs/bookmark-red.log
```

Expected: 編譯失敗（BookmarkService 還沒寫）。

- [ ] **Step 4: 實作 BookmarkService**

```java
package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.module.reading.mapper.BookmarkMapper;
import dowob.xyz.blog.module.reading.model.UserBookmark;
import dowob.xyz.blog.module.reading.repository.UserBookmarkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文章收藏 Service。Idempotent bookmark / unbookmark + batch 查詢。
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class BookmarkService {

    private final UserBookmarkRepository repo;
    private final BookmarkMapper mapper;

    @Transactional
    public void bookmark(Long userId, Long articleId) {
        if (repo.findByUserIdAndArticleId(userId, articleId).isPresent()) {
            return;
        }
        UserBookmark bm = new UserBookmark();
        bm.setUserId(userId);
        bm.setArticleId(articleId);
        bm.setCreatedAt(LocalDateTime.now());
        repo.save(bm);
    }

    @Transactional
    public void unbookmark(Long userId, Long articleId) {
        if (repo.findByUserIdAndArticleId(userId, articleId).isEmpty()) {
            return;
        }
        repo.deleteByUserIdAndArticleId(userId, articleId);
    }

    public boolean isBookmarked(Long userId, Long articleId) {
        return repo.findByUserIdAndArticleId(userId, articleId).isPresent();
    }

    public Set<Long> batchIsBookmarked(Long userId, List<Long> articleIds) {
        if (userId == null || articleIds == null || articleIds.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(mapper.findBookmarkedArticleIdsByUser(userId, articleIds));
    }

    public List<Long> findMyBookmarkedArticleIds(Long userId, int size, int offset) {
        return mapper.findMyBookmarkedArticleIds(userId, size, offset);
    }

    public long countByUser(Long userId) {
        return mapper.countByUser(userId);
    }
}
```

- [ ] **Step 5: Run green**

```bash
./mvnw.cmd -pl blog-module-reading test -Dtest=BookmarkServiceTest 2>&1 | tee logs/bookmark-green.log
```

Expected: 7 tests pass.

- [ ] **Step 6: Commit**

```bash
git add blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/mapper/BookmarkMapper.java \
        blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/BookmarkService.java \
        blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/service/BookmarkServiceTest.java
git commit -m "$(cat <<'EOF'
feat(reading): BookmarkService + Mapper（idempotent + batch query）

- bookmark / unbookmark idempotent
- batchIsBookmarked 給 ArticleQueryService 用
- findMyBookmarkedArticleIds 分頁給 my-bookmarks 端點
- 7 個 unit test 覆蓋

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: BookmarkController + IT

**Files:**
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/controller/BookmarkController.java`
- Create: `blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/config/ReadingTestApplication.java`
- Create: `blog-module-reading/src/test/resources/application-test.yaml`
- Create: `blog-module-reading/src/test/resources/db/testdata/R__reading_test_seed.sql`
- Create: `blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/controller/BookmarkControllerIT.java`

- [ ] **Step 1: ReadingTestApplication.java**

```java
package dowob.xyz.blog.module.reading.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Reading 模組 IT 專用 Spring Boot 應用程式。
 *
 * @author Yuan
 */
@SpringBootApplication(
        scanBasePackages = {
                "dowob.xyz.blog.common",
                "dowob.xyz.blog.infrastructure",
                "dowob.xyz.blog.module.article",
                "dowob.xyz.blog.module.reading"
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
        "dowob.xyz.blog.module.reading.repository"
})
@MapperScan(basePackages = {
        "dowob.xyz.blog.module.article.mapper",
        "dowob.xyz.blog.module.reading.mapper"
})
@EnableScheduling
public class ReadingTestApplication {
}
```

- [ ] **Step 2: application-test.yaml**

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
```

- [ ] **Step 3: R__reading_test_seed.sql**

```sql
-- 給 IT 用的 user 種子（FK constraint 用）
-- Repeatable migration：每次測試執行都重新跑
INSERT INTO users (id, uuid, email, password_hash, nickname, username, role, status, email_verified, created_at, updated_at)
VALUES
    (1, gen_random_uuid(), 'user1@test.com', 'hash', 'User1', 'user1', 'USER', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, gen_random_uuid(), 'user2@test.com', 'hash', 'User2', 'user2', 'USER', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, gen_random_uuid(), 'admin@test.com', 'hash', 'Admin', 'admin', 'ADMIN', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- 重置 sequence（避免後續 INSERT 衝突）
SELECT setval('users_id_seq', GREATEST(3, (SELECT MAX(id) FROM users)));
```

- [ ] **Step 4: BookmarkController.java**

```java
package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.service.ArticleQueryService;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.reading.service.BookmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 收藏 Controller。
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Bookmark")
public class BookmarkController {

    private final BookmarkService bookmarkService;
    private final ArticleService articleService;
    private final ArticleQueryService articleQueryService;

    @PostMapping("/articles/{articleUuid}/bookmark")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "收藏文章（idempotent）")
    public ApiResponse<Void> bookmark(@AuthenticationPrincipal Long userId,
                                        @PathVariable UUID articleUuid) {
        Long articleId = articleService.findIdByUuid(articleUuid);
        bookmarkService.bookmark(userId, articleId);
        return ApiResponse.success();
    }

    @DeleteMapping("/articles/{articleUuid}/bookmark")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "取消收藏（idempotent）")
    public ApiResponse<Void> unbookmark(@AuthenticationPrincipal Long userId,
                                          @PathVariable UUID articleUuid) {
        Long articleId = articleService.findIdByUuid(articleUuid);
        bookmarkService.unbookmark(userId, articleId);
        return ApiResponse.success();
    }

    @GetMapping("/users/me/bookmarks")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "我的收藏列表")
    public ApiResponse<PageResult<ArticleSummaryResponse>> myBookmarks(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        int offset = Math.max(0, (page - 1) * size);
        List<Long> articleIds = bookmarkService.findMyBookmarkedArticleIds(userId, size, offset);
        long total = bookmarkService.countByUser(userId);

        List<ArticleSummaryResponse> records = articleQueryService.getArticleSummariesByIds(articleIds);
        PageResult<ArticleSummaryResponse> result = PageResult.of(page, size, total, records);
        return ApiResponse.success(result);
    }
}
```

⚠ 注意：上面用了 `articleQueryService.getArticleSummariesByIds(List<Long>)` —— 這是 ArticleQueryService 上**新需要**的方法。在 Task 12（ArticleQueryService 擴充）裡會加上。本 task 暫時保留 controller 寫法，編譯時應該會錯（ArticleQueryService 還沒此方法）。**所以本 task 的 IT 測試先 skip / @Disabled，等 Task 12 完成後 enable**。

或者：先在這 task 加一個 stub 方法到 ArticleQueryService（回傳 emptyList），Task 12 才補真正實作。建議走這條路。

加到 `ArticleQueryService.java`（已存在）：

```java
public List<ArticleSummaryResponse> getArticleSummariesByIds(List<Long> articleIds) {
    if (articleIds == null || articleIds.isEmpty()) return List.of();
    // TODO Task 12: 補完整實作（含 batch enrich liked / bookmarked / progress）
    return articleService.getArticleSummariesByIds(articleIds);    // delegate to ArticleService
}
```

⚠ 同樣需要在 `ArticleService` 加 `getArticleSummariesByIds(List<Long>)` 方法。**詳細邏輯交給 Task 12**，本 task 只需要簽名能編譯。

實際操作：在 ArticleService interface + ArticleServiceImpl 都加 stub method（回傳 emptyList），Task 12 改成正常實作。

- [ ] **Step 5: BookmarkControllerIT.java**

對齊 `CommentControllerIT` pattern：

```java
package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.reading.config.ReadingTestApplication;
import dowob.xyz.blog.module.reading.repository.UserBookmarkRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ReadingTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("BookmarkController 整合測試")
class BookmarkControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("blog_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:7-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.url", redis::getRedisURI);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ArticleRepository articleRepo;
    @Autowired private UserBookmarkRepository bookmarkRepo;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;

    private static final Long USER_ID = 1L;
    private UUID articleUuid;
    private Long articleId;

    @BeforeEach
    void setup() {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(USER_ID);
        article.setTitle("Bookmark IT");
        article.setSlug("bookmark-it-" + UUID.randomUUID());
        article.setContent("test");
        article.setContentHtml("<p>test</p>");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        Article saved = articleRepo.save(article);
        articleUuid = saved.getUuid();
        articleId = saved.getId();
    }

    @AfterEach
    void cleanUp() {
        bookmarkRepo.deleteAll();
        articleRepo.deleteAll();
    }

    private RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    @Test
    @DisplayName("POST /bookmark - 200 + row created")
    void bookmark_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(bookmarkRepo.findByUserIdAndArticleId(USER_ID, articleId)).isPresent();
    }

    @Test
    @DisplayName("POST /bookmark - idempotent")
    void bookmark_idempotent() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER))).andExpect(status().isOk());

        assertThat(bookmarkRepo.findByUserIdAndArticleId(USER_ID, articleId)).isPresent();
    }

    @Test
    @DisplayName("DELETE /bookmark - 200 + row removed")
    void unbookmark_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER))).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(bookmarkRepo.findByUserIdAndArticleId(USER_ID, articleId)).isEmpty();
    }

    @Test
    @DisplayName("DELETE /bookmark - idempotent")
    void unbookmark_idempotent() throws Exception {
        mockMvc.perform(delete("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /me/bookmarks - 分頁列表")
    void myBookmarks_returnsPaginated() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER))).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me/bookmarks")
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("POST /bookmark - 401 unauthenticated")
    void bookmark_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 6: 在 ArticleService 加 stub method**

於 `ArticleService.java` interface 加：

```java
java.util.List<dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse>
    getArticleSummariesByIds(java.util.List<Long> articleIds);
```

於 `ArticleServiceImpl.java` 加 stub 實作：

```java
@Override
public List<ArticleSummaryResponse> getArticleSummariesByIds(List<Long> articleIds) {
    if (articleIds == null || articleIds.isEmpty()) return List.of();
    // TODO Task 12 補完整實作；目前直接 findById + map
    List<ArticleSummaryResponse> results = new ArrayList<>();
    for (Long id : articleIds) {
        articleRepository.findById(id).ifPresent(a -> {
            // 借用既有 toSummary 方法（若無，私下加；或用 toResponse 轉換）
            results.add(toSummary(a));
        });
    }
    return results;
}
```

⚠ `toSummary(Article)` 應該已存在（既有列表方法都用），若無則私下加（簡單映射欄位）。

於 `ArticleQueryService.java` 加：

```java
public List<ArticleSummaryResponse> getArticleSummariesByIds(List<Long> articleIds) {
    List<ArticleSummaryResponse> records = articleService.getArticleSummariesByIds(articleIds);
    enrichLikedList(records);   // batch 1 既有
    return records;
}
```

- [ ] **Step 7: 跑 IT**

```bash
./mvnw.cmd -pl blog-module-reading -am install -DskipTests
./mvnw.cmd -pl blog-module-reading test -Dtest=BookmarkControllerIT 2>&1 | tee logs/bookmark-it.log
```

Expected: 6 IT pass.

- [ ] **Step 8: Commit**

```bash
git add blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/controller/BookmarkController.java \
        blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/config/ReadingTestApplication.java \
        blog-module-reading/src/test/resources/application-test.yaml \
        blog-module-reading/src/test/resources/db/testdata/R__reading_test_seed.sql \
        blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/controller/BookmarkControllerIT.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/
git commit -m "$(cat <<'EOF'
feat(reading): BookmarkController + IT (6 tests)

- POST/DELETE /bookmark idempotent
- GET /me/bookmarks 分頁
- ReadingTestApplication 啟動 article + reading 模組
- ArticleService / ArticleQueryService 新增 getArticleSummariesByIds（stub for Task 12）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: HighlightService (TDD)

**Files:**
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/HighlightService.java`
- Create: `blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/service/HighlightServiceTest.java`

- [ ] **Step 1: HighlightServiceTest（red）**

```java
package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.reading.exception.ReadingErrorCode;
import dowob.xyz.blog.module.reading.model.UserHighlight;
import dowob.xyz.blog.module.reading.model.dto.request.CreateHighlightRequest;
import dowob.xyz.blog.module.reading.model.dto.request.UpdateHighlightRequest;
import dowob.xyz.blog.module.reading.model.dto.response.HighlightResponse;
import dowob.xyz.blog.module.reading.repository.UserHighlightRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HighlightServiceTest {

    @Mock private UserHighlightRepository repo;
    @Mock private ArticleService articleService;
    @InjectMocks private HighlightService service;

    private final Long userId = 1L;
    private final Long articleId = 100L;
    private final UUID articleUuid = UUID.randomUUID();
    private final UUID highlightUuid = UUID.randomUUID();

    @Test
    void createHighlight_savesWithUuidAndAllFields() {
        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(repo.save(any(UserHighlight.class))).thenAnswer(inv -> {
            UserHighlight h = inv.getArgument(0);
            h.setId(1L);
            return h;
        });

        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("hello world");
        req.setPrefix("intro: ");
        req.setSuffix(" end");
        req.setColor("#FFEB3B");
        req.setNote("important");

        service.create(articleUuid, userId, req);

        ArgumentCaptor<UserHighlight> captor = ArgumentCaptor.forClass(UserHighlight.class);
        verify(repo).save(captor.capture());
        UserHighlight saved = captor.getValue();
        assertThat(saved.getUuid()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getArticleId()).isEqualTo(articleId);
        assertThat(saved.getSnippet()).isEqualTo("hello world");
        assertThat(saved.getColor()).isEqualTo("#FFEB3B");
        assertThat(saved.getNote()).isEqualTo("important");
    }

    @Test
    void createHighlight_emptyNote_savesAsNull() {
        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("test");
        req.setColor("#FFEB3B");
        req.setNote(null);

        service.create(articleUuid, userId, req);

        ArgumentCaptor<UserHighlight> captor = ArgumentCaptor.forClass(UserHighlight.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getNote()).isNull();
    }

    @Test
    void getByArticle_returnsUserOwnedOnly() {
        UserHighlight h1 = new UserHighlight();
        h1.setId(1L); h1.setUuid(UUID.randomUUID());
        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(repo.findByUserIdAndArticleIdOrderByCreatedAtAsc(userId, articleId))
                .thenReturn(List.of(h1));

        List<HighlightResponse> results = service.getByArticle(articleUuid, userId);

        assertThat(results).hasSize(1);
        verify(repo).findByUserIdAndArticleIdOrderByCreatedAtAsc(userId, articleId);
    }

    @Test
    void updateHighlight_byOwner_updatesColorAndNote() {
        UserHighlight h = new UserHighlight();
        h.setId(1L); h.setUuid(highlightUuid); h.setUserId(userId);
        h.setColor("#FFEB3B"); h.setNote("old");
        when(repo.findByUuid(highlightUuid)).thenReturn(Optional.of(h));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateHighlightRequest req = new UpdateHighlightRequest();
        req.setColor("#00FF00");
        req.setNote("new");

        service.update(highlightUuid, userId, req);

        ArgumentCaptor<UserHighlight> captor = ArgumentCaptor.forClass(UserHighlight.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getColor()).isEqualTo("#00FF00");
        assertThat(captor.getValue().getNote()).isEqualTo("new");
    }

    @Test
    void updateHighlight_partialUpdate_keepsUntouched() {
        UserHighlight h = new UserHighlight();
        h.setId(1L); h.setUuid(highlightUuid); h.setUserId(userId);
        h.setColor("#FFEB3B"); h.setNote("kept");
        when(repo.findByUuid(highlightUuid)).thenReturn(Optional.of(h));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateHighlightRequest req = new UpdateHighlightRequest();
        req.setColor("#00FF00");     // 只給 color，note 留 null

        service.update(highlightUuid, userId, req);

        ArgumentCaptor<UserHighlight> captor = ArgumentCaptor.forClass(UserHighlight.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getColor()).isEqualTo("#00FF00");
        assertThat(captor.getValue().getNote()).isEqualTo("kept");      // 沒被改
    }

    @Test
    void updateHighlight_byNonOwner_throwsAccessDenied() {
        UserHighlight h = new UserHighlight();
        h.setId(1L); h.setUuid(highlightUuid); h.setUserId(999L);
        when(repo.findByUuid(highlightUuid)).thenReturn(Optional.of(h));

        UpdateHighlightRequest req = new UpdateHighlightRequest();
        req.setColor("#00FF00");

        assertThatThrownBy(() -> service.update(highlightUuid, userId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ReadingErrorCode.HIGHLIGHT_ACCESS_DENIED.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void deleteHighlight_byOwner_hardDeletes() {
        UserHighlight h = new UserHighlight();
        h.setId(1L); h.setUuid(highlightUuid); h.setUserId(userId);
        when(repo.findByUuid(highlightUuid)).thenReturn(Optional.of(h));

        service.delete(highlightUuid, userId);

        verify(repo).deleteById(1L);
    }

    @Test
    void deleteHighlight_byNonOwner_throwsAccessDenied() {
        UserHighlight h = new UserHighlight();
        h.setId(1L); h.setUuid(highlightUuid); h.setUserId(999L);
        when(repo.findByUuid(highlightUuid)).thenReturn(Optional.of(h));

        assertThatThrownBy(() -> service.delete(highlightUuid, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ReadingErrorCode.HIGHLIGHT_ACCESS_DENIED.getMessage());
        verify(repo, never()).deleteById(any());
    }

    @Test
    void deleteHighlight_nonExistent_throwsR0201() {
        when(repo.findByUuid(highlightUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(highlightUuid, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ReadingErrorCode.HIGHLIGHT_NOT_FOUND.getMessage());
    }
}
```

(9 tests instead of 10; the spec mentioned `getByArticle_paginationOrSorting` but Repository derived 已 ORDER BY，無額外排序需求。9 個夠用。)

- [ ] **Step 2: Run red**

```bash
./mvnw.cmd -pl blog-module-reading test -Dtest=HighlightServiceTest 2>&1 | tee logs/highlight-red.log
```

Expected: compile error.

- [ ] **Step 3: 實作 HighlightService.java**

```java
package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.reading.exception.ReadingErrorCode;
import dowob.xyz.blog.module.reading.model.UserHighlight;
import dowob.xyz.blog.module.reading.model.dto.request.CreateHighlightRequest;
import dowob.xyz.blog.module.reading.model.dto.request.UpdateHighlightRequest;
import dowob.xyz.blog.module.reading.model.dto.response.HighlightResponse;
import dowob.xyz.blog.module.reading.repository.UserHighlightRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 文字劃線 Service。
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class HighlightService {

    private final UserHighlightRepository repo;
    private final ArticleService articleService;

    @Transactional
    public HighlightResponse create(UUID articleUuid, Long userId, CreateHighlightRequest req) {
        Long articleId = articleService.findIdByUuid(articleUuid);

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

    public List<HighlightResponse> getByArticle(UUID articleUuid, Long userId) {
        Long articleId = articleService.findIdByUuid(articleUuid);
        return repo.findByUserIdAndArticleIdOrderByCreatedAtAsc(userId, articleId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public HighlightResponse update(UUID highlightUuid, Long userId, UpdateHighlightRequest req) {
        UserHighlight h = repo.findByUuid(highlightUuid)
                .orElseThrow(() -> new BusinessException(ReadingErrorCode.HIGHLIGHT_NOT_FOUND));
        if (!h.getUserId().equals(userId)) {
            throw new BusinessException(ReadingErrorCode.HIGHLIGHT_ACCESS_DENIED);
        }
        if (req.getColor() != null) h.setColor(req.getColor());
        if (req.getNote() != null) h.setNote(req.getNote());

        return toResponse(repo.save(h));
    }

    @Transactional
    public void delete(UUID highlightUuid, Long userId) {
        UserHighlight h = repo.findByUuid(highlightUuid)
                .orElseThrow(() -> new BusinessException(ReadingErrorCode.HIGHLIGHT_NOT_FOUND));
        if (!h.getUserId().equals(userId)) {
            throw new BusinessException(ReadingErrorCode.HIGHLIGHT_ACCESS_DENIED);
        }
        repo.deleteById(h.getId());
    }

    private HighlightResponse toResponse(UserHighlight h) {
        HighlightResponse r = new HighlightResponse();
        r.setUuid(h.getUuid());
        r.setSnippet(h.getSnippet());
        r.setPrefix(h.getPrefix());
        r.setSuffix(h.getSuffix());
        r.setColor(h.getColor());
        r.setNote(h.getNote());
        r.setCreatedAt(h.getCreatedAt());
        r.setUpdatedAt(h.getUpdatedAt());
        return r;
    }
}
```

- [ ] **Step 4: Run green**

```bash
./mvnw.cmd -pl blog-module-reading test -Dtest=HighlightServiceTest 2>&1 | tee logs/highlight-green.log
```

Expected: 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/HighlightService.java \
        blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/service/HighlightServiceTest.java
git commit -m "$(cat <<'EOF'
feat(reading): HighlightService 實作 + TDD

- create / getByArticle / update / delete
- 擁有者隔離：updateAccessDenied / deleteAccessDenied
- partial update：只給 color 不影響 note
- 9 個 unit test 覆蓋

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: HighlightController + IT

**Files:**
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/controller/HighlightController.java`
- Create: `blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/controller/HighlightControllerIT.java`

- [ ] **Step 1: HighlightController.java**

```java
package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.reading.model.dto.request.CreateHighlightRequest;
import dowob.xyz.blog.module.reading.model.dto.request.UpdateHighlightRequest;
import dowob.xyz.blog.module.reading.model.dto.response.HighlightResponse;
import dowob.xyz.blog.module.reading.service.HighlightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 劃線 Controller。
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Highlight")
public class HighlightController {

    private final HighlightService highlightService;

    @PostMapping("/articles/{articleUuid}/highlights")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "建立 highlight + 可選 note")
    public ApiResponse<HighlightResponse> create(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateHighlightRequest req) {
        return ApiResponse.success(highlightService.create(articleUuid, userId, req));
    }

    @GetMapping("/articles/{articleUuid}/highlights")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "撈我在這篇文章的所有 highlight")
    public ApiResponse<List<HighlightResponse>> list(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long userId) {
        return ApiResponse.success(highlightService.getByArticle(articleUuid, userId));
    }

    @PutMapping("/highlights/{uuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "更新 highlight (color/note)")
    public ApiResponse<HighlightResponse> update(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UpdateHighlightRequest req) {
        return ApiResponse.success(highlightService.update(uuid, userId, req));
    }

    @DeleteMapping("/highlights/{uuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "硬刪除 highlight")
    public ApiResponse<Void> delete(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal Long userId) {
        highlightService.delete(uuid, userId);
        return ApiResponse.success();
    }
}
```

- [ ] **Step 2: HighlightControllerIT.java**

對齊 BookmarkControllerIT。覆蓋 8 cases：

```java
package dowob.xyz.blog.module.reading.controller;

// imports... 對齊 BookmarkControllerIT

@SpringBootTest(classes = ReadingTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("HighlightController 整合測試")
class HighlightControllerIT {

    // ...container setup 同 BookmarkControllerIT...

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ArticleRepository articleRepo;
    @Autowired private UserHighlightRepository highlightRepo;

    // ... MockitoBean 同 BookmarkControllerIT ...

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    private UUID articleUuid;
    private Long articleId;

    @BeforeEach
    void setup() {
        // 同 BookmarkControllerIT.setup()
    }

    @AfterEach
    void cleanUp() {
        highlightRepo.deleteAll();
        articleRepo.deleteAll();
    }

    private RequestPostProcessor asUser(Long userId, Role role) { /* same */ }

    @Test
    @DisplayName("POST /highlights - 200 with valid request")
    void post_validRequest_returns200() throws Exception {
        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("hello world");
        req.setColor("#FFEB3B");
        req.setNote("note");

        mockMvc.perform(post("/api/v1/articles/{uuid}/highlights", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.uuid").exists())
                .andExpect(jsonPath("$.data.snippet").value("hello world"));
    }

    @Test
    @DisplayName("POST - 400 if snippet > 500 chars")
    void post_snippetTooLong_returns400() throws Exception {
        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("a".repeat(501));
        req.setColor("#FFEB3B");

        mockMvc.perform(post("/api/v1/articles/{uuid}/highlights", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST - 400 if color invalid hex")
    void post_invalidColor_returns400() throws Exception {
        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("test");
        req.setColor("yellow");      // not hex

        mockMvc.perform(post("/api/v1/articles/{uuid}/highlights", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /highlights - 只回我的")
    void list_returnsOnlyMine() throws Exception {
        // 用 USER_ID 建一個 highlight；用 OTHER_USER_ID 建另一個
        UserHighlight mine = new UserHighlight();
        mine.setUuid(UUID.randomUUID());
        mine.setUserId(USER_ID); mine.setArticleId(articleId);
        mine.setSnippet("mine"); mine.setPrefix(""); mine.setSuffix("");
        mine.setColor("#FFEB3B");
        highlightRepo.save(mine);

        UserHighlight other = new UserHighlight();
        other.setUuid(UUID.randomUUID());
        other.setUserId(OTHER_USER_ID); other.setArticleId(articleId);
        other.setSnippet("other"); other.setPrefix(""); other.setSuffix("");
        other.setColor("#FFEB3B");
        highlightRepo.save(other);

        mockMvc.perform(get("/api/v1/articles/{uuid}/highlights", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].snippet").value("mine"));
    }

    @Test
    @DisplayName("PUT /highlights/{uuid} - 200 by owner")
    void put_byOwner_returns200() throws Exception {
        UserHighlight h = createHighlight(USER_ID);

        UpdateHighlightRequest req = new UpdateHighlightRequest();
        req.setColor("#00FF00");

        mockMvc.perform(put("/api/v1/highlights/{uuid}", h.getUuid())
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.color").value("#00FF00"));
    }

    @Test
    @DisplayName("PUT - 400 (R0202) by non-owner")
    void put_byNonOwner_returns400_R0202() throws Exception {
        UserHighlight h = createHighlight(USER_ID);

        UpdateHighlightRequest req = new UpdateHighlightRequest();
        req.setColor("#00FF00");

        mockMvc.perform(put("/api/v1/highlights/{uuid}", h.getUuid())
                .with(asUser(OTHER_USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("R0202"));
    }

    @Test
    @DisplayName("DELETE /highlights/{uuid} - 200 by owner")
    void delete_byOwner_returns200() throws Exception {
        UserHighlight h = createHighlight(USER_ID);

        mockMvc.perform(delete("/api/v1/highlights/{uuid}", h.getUuid())
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());

        assertThat(highlightRepo.findByUuid(h.getUuid())).isEmpty();
    }

    @Test
    @DisplayName("POST - 401 unauthenticated")
    void post_unauthenticated_returns401() throws Exception {
        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("test"); req.setColor("#FFEB3B");

        mockMvc.perform(post("/api/v1/articles/{uuid}/highlights", articleUuid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    private UserHighlight createHighlight(Long ownerId) {
        UserHighlight h = new UserHighlight();
        h.setUuid(UUID.randomUUID());
        h.setUserId(ownerId); h.setArticleId(articleId);
        h.setSnippet("seed"); h.setPrefix(""); h.setSuffix("");
        h.setColor("#FFEB3B");
        return highlightRepo.save(h);
    }
}
```

- [ ] **Step 3: Run**

```bash
./mvnw.cmd -pl blog-module-reading test -Dtest=HighlightControllerIT 2>&1 | tee logs/highlight-it.log
```

Expected: 8 tests pass.

- [ ] **Step 4: Commit**

```bash
git add blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/controller/HighlightController.java \
        blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/controller/HighlightControllerIT.java
git commit -m "$(cat <<'EOF'
feat(reading): HighlightController + IT (8 tests)

- POST/GET /articles/{uuid}/highlights
- PUT/DELETE /highlights/{uuid}（擁有者隔離）
- 8 個 IT 覆蓋 happy path / 驗證 / R0202 / 401

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: ReadingProgressMapper (UPSERT)

**Files:**
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/mapper/ReadingProgressMapper.java`

- [ ] **Step 1: ReadingProgressMapper.java**

```java
package dowob.xyz.blog.module.reading.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * Reading Progress MyBatis Mapper：UPSERT 用。
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface ReadingProgressMapper {

    /**
     * UPSERT user_reading_progress；衝突時更新進度與 last_heading_anchor。
     */
    @Update("""
            INSERT INTO user_reading_progress (user_id, article_id, progress, last_heading_anchor, updated_at)
            VALUES (#{userId}, #{articleId}, #{progress}, #{lastHeading}, CURRENT_TIMESTAMP)
            ON CONFLICT (user_id, article_id) DO UPDATE SET
              progress = EXCLUDED.progress,
              last_heading_anchor = EXCLUDED.last_heading_anchor,
              updated_at = CURRENT_TIMESTAMP
            """)
    int upsert(@Param("userId") Long userId,
               @Param("articleId") Long articleId,
               @Param("progress") BigDecimal progress,
               @Param("lastHeading") String lastHeading);
}
```

- [ ] **Step 2: 編譯**

```bash
./mvnw.cmd -pl blog-module-reading -am compile 2>&1 | tee logs/progress-mapper.log
```

- [ ] **Step 3: Commit**

```bash
git add blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/mapper/ReadingProgressMapper.java
git commit -m "$(cat <<'EOF'
feat(reading): 新增 ReadingProgressMapper UPSERT

- PostgreSQL ON CONFLICT (user_id, article_id) DO UPDATE
- 給 ReadingProgressService.update() / FlushJob 使用

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: ReadingProgressService (TDD with mock Redis)

**Files:**
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/ReadingProgressService.java`
- Create: `blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/service/ReadingProgressServiceTest.java`

⚠ **這個 Service 在 ReadingProgressFlushJob (Task 13) 也會用到。先做完 service 再寫 job。**

- [ ] **Step 1: ReadingProgressServiceTest（red）**

由於用了 Redis pipeline 跟 RedisTemplate 各種 ops，testing 較複雜。簡化策略：mock `StringRedisTemplate` + `HashOperations` + `SetOperations` + `ValueOperations`。

```java
package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import dowob.xyz.blog.module.reading.model.UserReadingProgress;
import dowob.xyz.blog.module.reading.repository.UserReadingProgressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingProgressServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private ArticleService articleService;
    @Mock private ReadingProgressMapper progressMapper;
    @Mock private UserReadingProgressRepository progressRepo;
    @InjectMocks private ReadingProgressService service;

    private final Long userId = 1L;
    private final UUID articleUuid = UUID.randomUUID();
    private final Long articleId = 100L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    void update_progressBelowThreshold_writesRedisAndAddsDirty() {
        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);

        service.update(userId, articleUuid, new BigDecimal("0.50"), "intro");

        verify(hashOps).putAll(eq(RedisKeyConstant.READING_PROGRESS_PREFIX + userId + ":" + articleUuid), any(Map.class));
        verify(setOps).add(RedisKeyConstant.READING_DIRTY_KEY, userId + ":" + articleUuid);
    }

    @Test
    void update_progressBelowThreshold_setsTTL() {
        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);

        service.update(userId, articleUuid, new BigDecimal("0.50"), null);

        verify(redisTemplate).expire(any(String.class), eq(RedisKeyConstant.READING_PROGRESS_TTL_DAYS), eq(TimeUnit.DAYS));
    }

    @Test
    void update_progressAboveThreshold_deletesRedisAndUpsertsDb() {
        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);

        service.update(userId, articleUuid, new BigDecimal("0.98"), "end");

        verify(redisTemplate).delete(RedisKeyConstant.READING_PROGRESS_PREFIX + userId + ":" + articleUuid);
        verify(progressMapper).upsert(eq(userId), eq(articleId), eq(new BigDecimal("0.98")), eq("end"));
        verify(hashOps, never()).putAll(any(), any());
    }

    @Test
    void get_redisHit_returnsFromRedis() {
        Map<Object, Object> hash = new HashMap<>();
        hash.put("progress", "0.612");
        hash.put("lastHeading", "intro");
        hash.put("updatedAt", "1730438400000");
        when(hashOps.entries(any(String.class))).thenReturn(hash);

        Optional<?> result = service.get(userId, articleUuid);

        assertThat(result).isPresent();
        verify(progressRepo, never()).findByUserIdAndArticleId(any(), any());
    }

    @Test
    void get_redisMiss_fallsBackToDbAndCachesBack() {
        when(hashOps.entries(any(String.class))).thenReturn(Map.of());
        UserReadingProgress dbVal = new UserReadingProgress();
        dbVal.setProgress(new BigDecimal("0.50"));
        dbVal.setLastHeadingAnchor("intro");
        dbVal.setUpdatedAt(LocalDateTime.now());
        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(progressRepo.findByUserIdAndArticleId(userId, articleId))
                .thenReturn(Optional.of(dbVal));

        Optional<?> result = service.get(userId, articleUuid);

        assertThat(result).isPresent();
        // cache 回 Redis
        verify(hashOps).putAll(any(String.class), any(Map.class));
    }

    @Test
    void batchGetProgress_unauthenticated_returnsEmpty() {
        var result = service.batchGetProgress(null, java.util.List.of(1L, 2L));
        assertThat(result).isEmpty();
    }

    @Test
    void batchGetProgress_emptyArticleIds_returnsEmpty() {
        var result = service.batchGetProgress(userId, java.util.List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void update_lastHeadingNull_storesEmptyString() {
        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);

        service.update(userId, articleUuid, new BigDecimal("0.50"), null);

        verify(hashOps).putAll(any(String.class), argThat(m -> "".equals(m.get("lastHeading"))));
    }

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.Mockito.argThat(matcher);
    }
}
```

(8 tests; `batchGetProgress` 的詳細測試（pipeline + DB miss 補完）放到 IT 跑，unit 只測守備路徑)

- [ ] **Step 2: Run red**

```bash
./mvnw.cmd -pl blog-module-reading test -Dtest=ReadingProgressServiceTest 2>&1 | tee logs/rp-red.log
```

Expected: compile error.

- [ ] **Step 3: 實作 ReadingProgressService.java**

```java
package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import dowob.xyz.blog.module.reading.model.UserReadingProgress;
import dowob.xyz.blog.module.reading.model.dto.response.ProgressResponse;
import dowob.xyz.blog.module.reading.repository.UserReadingProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 閱讀進度 Service：Redis 主、DB 備份。progress >= 0.95 視為完成（DEL Redis + UPSERT DB）。
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadingProgressService {

    private final StringRedisTemplate redisTemplate;
    private final ArticleService articleService;
    private final ArticleMapper articleMapper;
    private final ReadingProgressMapper progressMapper;
    private final UserReadingProgressRepository progressRepo;

    @Transactional   // 用於 DB UPSERT；Redis 操作不在 tx 內
    public void update(Long userId, UUID articleUuid, BigDecimal progress, String lastHeading) {
        Long articleId = articleService.findIdByUuid(articleUuid);
        if (articleId == null) return;     // article 不存在，靜默忽略

        String key = RedisKeyConstant.READING_PROGRESS_PREFIX + userId + ":" + articleUuid;

        if (progress.compareTo(RedisKeyConstant.READING_PROGRESS_COMPLETED_THRESHOLD) >= 0) {
            // 已讀完：刪 Redis + UPSERT DB
            redisTemplate.delete(key);
            progressMapper.upsert(userId, articleId, progress, lastHeading);
        } else {
            // 進行中：寫 Redis + 加 dirty
            Map<String, String> hash = new HashMap<>();
            hash.put("progress", progress.toPlainString());
            hash.put("lastHeading", lastHeading != null ? lastHeading : "");
            hash.put("updatedAt", String.valueOf(System.currentTimeMillis()));
            redisTemplate.opsForHash().putAll(key, hash);
            redisTemplate.expire(key, RedisKeyConstant.READING_PROGRESS_TTL_DAYS, TimeUnit.DAYS);
            redisTemplate.opsForSet().add(RedisKeyConstant.READING_DIRTY_KEY, userId + ":" + articleUuid);
        }
    }

    public Optional<ProgressResponse> get(Long userId, UUID articleUuid) {
        String key = RedisKeyConstant.READING_PROGRESS_PREFIX + userId + ":" + articleUuid;
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
        if (!hash.isEmpty()) {
            return Optional.of(fromHash(hash));
        }
        // miss → DB
        Long articleId = articleService.findIdByUuid(articleUuid);
        if (articleId == null) return Optional.empty();

        Optional<UserReadingProgress> dbVal = progressRepo.findByUserIdAndArticleId(userId, articleId);
        dbVal.ifPresent(p -> cacheToRedis(key, p));
        return dbVal.map(this::toResponse);
    }

    public Map<Long, BigDecimal> batchGetProgress(Long userId, List<Long> articleIds) {
        if (userId == null || articleIds == null || articleIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // 1. id → uuid 反向映射
        Map<Long, UUID> idToUuid = articleMapper.findUuidsByIds(articleIds);

        // 2. Redis pipeline HGET 一次撈
        Map<Long, BigDecimal> result = new HashMap<>();
        List<Long> missingIds = new ArrayList<>();
        for (Map.Entry<Long, UUID> entry : idToUuid.entrySet()) {
            String key = RedisKeyConstant.READING_PROGRESS_PREFIX + userId + ":" + entry.getValue();
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
            if (!hash.isEmpty()) {
                Object pVal = hash.get("progress");
                if (pVal != null) {
                    result.put(entry.getKey(), new BigDecimal(pVal.toString()));
                }
            } else {
                missingIds.add(entry.getKey());
            }
        }

        // 3. DB 補 Redis miss
        if (!missingIds.isEmpty()) {
            List<UserReadingProgress> dbRows = progressRepo.findByUserIdAndArticleIdIn(userId, missingIds);
            dbRows.forEach(p -> result.put(p.getArticleId(), p.getProgress()));
        }
        return result;
    }

    private void cacheToRedis(String key, UserReadingProgress p) {
        Map<String, String> hash = new HashMap<>();
        hash.put("progress", p.getProgress().toPlainString());
        hash.put("lastHeading", p.getLastHeadingAnchor() != null ? p.getLastHeadingAnchor() : "");
        hash.put("updatedAt", String.valueOf(p.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        redisTemplate.opsForHash().putAll(key, hash);
        redisTemplate.expire(key, RedisKeyConstant.READING_PROGRESS_TTL_DAYS, TimeUnit.DAYS);
    }

    private ProgressResponse fromHash(Map<Object, Object> hash) {
        ProgressResponse r = new ProgressResponse();
        Object pVal = hash.get("progress");
        if (pVal != null) r.setProgress(new BigDecimal(pVal.toString()));
        Object hVal = hash.get("lastHeading");
        r.setLastHeading(hVal != null && !hVal.toString().isEmpty() ? hVal.toString() : null);
        Object uVal = hash.get("updatedAt");
        if (uVal != null) {
            r.setUpdatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(uVal.toString())), ZoneId.systemDefault()));
        }
        return r;
    }

    private ProgressResponse toResponse(UserReadingProgress p) {
        ProgressResponse r = new ProgressResponse();
        r.setProgress(p.getProgress());
        r.setLastHeading(p.getLastHeadingAnchor());
        r.setUpdatedAt(p.getUpdatedAt());
        return r;
    }
}
```

⚠ 用到 `articleMapper.findUuidsByIds(List<Long>)` —— 在 Task 12 ArticleMapper 改動補上。本 task 編譯會錯。所以實作順序：先在 Task 12 完成 `findUuidsByIds`，再回頭做這 task。**或者本 task 先加 stub method 到 ArticleMapper（@Select 回 emptyMap），Task 12 修正成正確 SQL**。

簡化做法：**本 task 先在 ArticleMapper 加 stub method**：

```java
@Select({
    "<script>",
    "SELECT id, uuid FROM articles WHERE id IN",
    "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
    "</script>"
})
@MapKey("id")     // optional MyBatis annotation：用 id 當 key
java.util.Map<Long, java.util.UUID> findUuidsByIds(@Param("ids") java.util.List<Long> ids);
```

⚠ MyBatis 的 `@MapKey` 不適用於這種「row → entry」形式。實際做法：定義一個 row class（IdAndUuid）或回傳 `List<Article>` 然後在 service 內轉。為簡化，改 service 內處理：

```java
// 改 service 不直接呼叫 articleMapper.findUuidsByIds，改透過 ArticleService.findUuidsByIds
```

**最簡作法**：在 `ArticleService` interface 加：

```java
List<Article> findByIds(List<Long> ids);     // 既有 articleRepo.findAllById 即可
```

然後 ReadingProgressService 用：

```java
List<Article> articles = articleService.findByIds(articleIds);
Map<Long, UUID> idToUuid = articles.stream()
    .collect(Collectors.toMap(Article::getId, Article::getUuid));
```

更新 ReadingProgressService 的 batchGetProgress 用此 pattern。本 task 同時在 ArticleService 加 `findByIds(List<Long>)` 方法（stub for Task 12 if needed）。

實際操作：
1. 在 `ArticleService.java` 加 `List<Article> findByIds(List<Long> ids);`
2. 在 `ArticleServiceImpl.java` 加：`return (List<Article>) articleRepository.findAllById(ids);`
3. 在 ReadingProgressService 內用此

- [ ] **Step 4: 實作上述「ArticleService.findByIds」+ ReadingProgressService**

實作 ArticleService 的 findByIds、補完 ReadingProgressService。

- [ ] **Step 5: Run green**

```bash
./mvnw.cmd -pl blog-module-reading -am install -DskipTests
./mvnw.cmd -pl blog-module-reading test -Dtest=ReadingProgressServiceTest 2>&1 | tee logs/rp-green.log
```

Expected: 8 tests pass.

- [ ] **Step 6: Commit**

```bash
git add blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/service/ReadingProgressService.java \
        blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/service/ReadingProgressServiceTest.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/
git commit -m "$(cat <<'EOF'
feat(reading): ReadingProgressService 實作 + TDD

- update：< 0.95 寫 Redis + add dirty + TTL 3d；>= 0.95 DEL Redis + UPSERT DB
- get：Redis 優先 → fallback DB → 回填 cache
- batchGetProgress：Redis 個別 HGET + DB miss 補完
- ArticleService 新增 findByIds 給 batchGetProgress 取得 uuid
- 8 個 unit test 覆蓋

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: ArticleQueryService 擴充 + ArticleResponse 欄位

**Files:**
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/dto/response/ArticleSummaryResponse.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/dto/response/ArticleResponse.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleQueryService.java`
- Test: `blog-module-article/src/test/java/.../service/ArticleQueryServiceTest.java`（既有，加新測試）

- [ ] **Step 1: 加欄位到 DTO**

於 `ArticleSummaryResponse.java`、`ArticleResponse.java`：

```java
private Boolean bookmarked;
private java.math.BigDecimal lastReadProgress;
```

- [ ] **Step 2: 修改 ArticleQueryService**

於 `ArticleQueryService.java` 加 inject + enrich：

```java
private final BookmarkService bookmarkService;
private final ReadingProgressService readingProgressService;

// 既有 enrichLikedList 改成統一 enrich
private void enrich(List<ArticleSummaryResponse> records) {
    if (records == null || records.isEmpty()) return;
    Long userId = currentUserIdOrNull();
    List<Long> articleIds = records.stream().map(ArticleSummaryResponse::getId).toList();

    Set<Long> likedIds = articleLikeService.batchIsLiked(userId, articleIds);
    Set<Long> bookmarkedIds = bookmarkService.batchIsBookmarked(userId, articleIds);
    Map<Long, BigDecimal> progressMap = readingProgressService.batchGetProgress(userId, articleIds);

    records.forEach(r -> {
        r.setLiked(likedIds.contains(r.getId()));
        r.setBookmarked(bookmarkedIds.contains(r.getId()));
        r.setLastReadProgress(progressMap.get(r.getId()));
    });
}

// 把所有 enrichLikedList 呼叫改成 enrich
```

於單篇 ArticleResponse 取得方法（如 `getArticleByUuid`）加：

```java
private void enrichSingle(ArticleResponse resp) {
    if (resp == null) return;
    Long userId = currentUserIdOrNull();
    resp.setLiked(userId != null && articleLikeService.isLiked(userId, resp.getId()));
    resp.setBookmarked(userId != null && bookmarkService.isBookmarked(userId, resp.getId()));
    if (userId != null) {
        readingProgressService.get(userId, resp.getUuid())
            .ifPresent(p -> resp.setLastReadProgress(p.getProgress()));
    }
}
```

⚠ 上面假設 `ArticleResponse.getId()` 存在（既有列表也用了 id）；若沒有，用 uuid + 額外 query 補。

`getArticleSummariesByIds` 也加 enrich：

```java
public List<ArticleSummaryResponse> getArticleSummariesByIds(List<Long> articleIds) {
    List<ArticleSummaryResponse> records = articleService.getArticleSummariesByIds(articleIds);
    enrich(records);
    return records;
}
```

- [ ] **Step 3: 既有 ArticleQueryServiceTest 補測試**

加 4 個 test：

```java
@Test
void enrich_includesBookmarkedFlag() { /* mock bookmarkService.batchIsBookmarked → setBookmarked 生效 */ }

@Test
void enrich_includesLastReadProgress() { /* mock readingProgressService.batchGetProgress → setLastReadProgress 生效 */ }

@Test
void enrich_unauthenticated_allFlagsFalseOrNull() { /* userId null → liked/bookmarked false, progress null */ }

@Test
void enrichSingle_setsAllNewFields() { /* getArticleByUuid 流程也填 */ }
```

- [ ] **Step 4: Run**

```bash
./mvnw.cmd -pl blog-module-article test 2>&1 | tee logs/article-after-batch2-enrich.log
```

Expected: 既有 230 + 新 4 = 234 tests pass。

- [ ] **Step 5: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/model/dto/response/ \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleQueryService.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleQueryServiceTest.java
git commit -m "$(cat <<'EOF'
feat(article): ArticleQueryService 擴充 bookmarked + lastReadProgress 欄位

- inject BookmarkService + ReadingProgressService（read-only，沿用 CQRS Read 層）
- enrich list / single 都填 liked + bookmarked + lastReadProgress
- 4 個新 unit test 覆蓋
- ArticleSummaryResponse / ArticleResponse 加 2 欄位

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: ReadingProgressController + IT (with Redis testcontainer)

**Files:**
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/controller/ReadingProgressController.java`
- Create: `blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/controller/ReadingProgressControllerIT.java`

- [ ] **Step 1: ReadingProgressController.java**

```java
package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.reading.model.dto.request.UpdateProgressRequest;
import dowob.xyz.blog.module.reading.model.dto.response.ProgressResponse;
import dowob.xyz.blog.module.reading.service.ReadingProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 閱讀進度 Controller。
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/articles/{articleUuid}/progress")
@RequiredArgsConstructor
@Tag(name = "Reading Progress")
public class ReadingProgressController {

    private final ReadingProgressService progressService;

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "更新閱讀進度（HSET Redis）")
    public ApiResponse<Void> update(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UpdateProgressRequest req) {
        progressService.update(userId, articleUuid, req.getProgress(), req.getLastHeading());
        return ApiResponse.success();
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "查詢進度（Redis 優先）")
    public ApiResponse<ProgressResponse> get(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long userId) {
        return ApiResponse.success(progressService.get(userId, articleUuid).orElse(null));
    }
}
```

- [ ] **Step 2: ReadingProgressControllerIT.java**

對齊 BookmarkControllerIT，但需要驗證 Redis state。`StringRedisTemplate` 應該由 Spring Boot Auto-config 自動 wire（Redis testcontainer 已起）。

```java
package dowob.xyz.blog.module.reading.controller;

// imports 對齊 BookmarkControllerIT + 額外：
//   import org.springframework.data.redis.core.StringRedisTemplate;
//   import dowob.xyz.blog.common.constant.RedisKeyConstant;

@SpringBootTest(...)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ReadingProgressController 整合測試")
class ReadingProgressControllerIT {

    // ... container + DynamicPropertySource ...

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ArticleRepository articleRepo;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private UserReadingProgressRepository progressRepo;

    // ... MockitoBean ...

    private static final Long USER_ID = 1L;
    private UUID articleUuid;
    private Long articleId;

    @BeforeEach
    void setup() { /* setup article 同 BookmarkControllerIT */ }

    @AfterEach
    void cleanUp() {
        // 清 Redis
        redisTemplate.delete(redisTemplate.keys(RedisKeyConstant.READING_PROGRESS_PREFIX + "*"));
        redisTemplate.delete(RedisKeyConstant.READING_DIRTY_KEY);
        progressRepo.deleteAll();
        articleRepo.deleteAll();
    }

    private RequestPostProcessor asUser(Long userId, Role role) { /* same */ }

    @Test
    @DisplayName("PUT /progress 0.6 - 200 + Redis 有資料")
    void put_progress_0_6_writesToRedis() throws Exception {
        UpdateProgressRequest req = new UpdateProgressRequest();
        req.setProgress(new BigDecimal("0.6"));
        req.setLastHeading("intro");

        mockMvc.perform(put("/api/v1/articles/{uuid}/progress", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        String key = RedisKeyConstant.READING_PROGRESS_PREFIX + USER_ID + ":" + articleUuid;
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
        assertThat(hash).containsKey("progress");
        assertThat(hash.get("progress").toString()).isEqualTo("0.6");
    }

    @Test
    @DisplayName("PUT /progress 1.0 - 200 + Redis 無 + DB 有")
    void put_progress_1_0_deletesRedisAndUpsertsDb() throws Exception {
        UpdateProgressRequest req = new UpdateProgressRequest();
        req.setProgress(new BigDecimal("1.0"));
        req.setLastHeading("end");

        mockMvc.perform(put("/api/v1/articles/{uuid}/progress", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        String key = RedisKeyConstant.READING_PROGRESS_PREFIX + USER_ID + ":" + articleUuid;
        assertThat(redisTemplate.opsForHash().entries(key)).isEmpty();
        assertThat(progressRepo.findByUserIdAndArticleId(USER_ID, articleId)).isPresent();
    }

    @Test
    @DisplayName("GET /progress - 從 Redis 撈得到")
    void get_redisHit_returnsValue() throws Exception {
        UpdateProgressRequest req = new UpdateProgressRequest();
        req.setProgress(new BigDecimal("0.5"));
        mockMvc.perform(put("/api/v1/articles/{uuid}/progress", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/articles/{uuid}/progress", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progress").value(0.5));
    }

    @Test
    @DisplayName("PUT - 400 if progress > 1")
    void put_progressOutOfRange_returns400() throws Exception {
        UpdateProgressRequest req = new UpdateProgressRequest();
        req.setProgress(new BigDecimal("1.5"));
        mockMvc.perform(put("/api/v1/articles/{uuid}/progress", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT - 401 unauthenticated")
    void put_unauthenticated_returns401() throws Exception {
        UpdateProgressRequest req = new UpdateProgressRequest();
        req.setProgress(new BigDecimal("0.5"));
        mockMvc.perform(put("/api/v1/articles/{uuid}/progress", articleUuid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }
}
```

(5 tests; 「Redis miss → DB 撈 + 回填」case 屬於進階 path，可 skip 或在 batch get 那邊驗)

- [ ] **Step 3: Run**

```bash
./mvnw.cmd -pl blog-module-reading test -Dtest=ReadingProgressControllerIT 2>&1 | tee logs/rp-it.log
```

Expected: 5 tests pass.

- [ ] **Step 4: Commit**

```bash
git add blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/controller/ReadingProgressController.java \
        blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/controller/ReadingProgressControllerIT.java
git commit -m "$(cat <<'EOF'
feat(reading): ReadingProgressController + IT (5 tests)

- PUT /progress: Redis HSET + dirty SADD（< 0.95）or Redis DEL + DB UPSERT（>= 0.95）
- GET /progress: Redis 優先
- 5 個 IT with Redis testcontainer：完整路徑驗證

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: ReadingProgressFlushJob (TDD)

**Files:**
- Create: `blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/job/ReadingProgressFlushJob.java`
- Create: `blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/job/ReadingProgressFlushJobTest.java`

- [ ] **Step 1: Test（red）**

```java
package dowob.xyz.blog.module.reading.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingProgressFlushJobTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @Mock private SetOperations<String, String> setOps;
    @Mock private ArticleService articleService;
    @Mock private ReadingProgressMapper progressMapper;
    @InjectMocks private ReadingProgressFlushJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    @Test
    void flush_dirtyEntries_upsertedAndRemovedFromDirty() {
        UUID articleUuid = UUID.randomUUID();
        String entry = "1:" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));

        Map<Object, Object> hash = new HashMap<>();
        hash.put("progress", "0.6");
        hash.put("lastHeading", "intro");
        when(hashOps.entries(any(String.class))).thenReturn(hash);
        when(articleService.findIdByUuid(articleUuid)).thenReturn(100L);

        job.flush();

        verify(progressMapper).upsert(eq(1L), eq(100L), eq(new BigDecimal("0.6")), eq("intro"));
        verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
    }

    @Test
    void flush_redisKeyExpiredButDirtyExists_removesDirtyEntry() {
        String entry = "1:" + UUID.randomUUID();
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        when(hashOps.entries(any(String.class))).thenReturn(Map.of());      // empty = expired

        job.flush();

        verify(progressMapper, never()).upsert(any(), any(), any(), any());
        verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
    }

    @Test
    void flush_dbErrorOnSingleEntry_keepsItInDirtyForRetry() {
        UUID articleUuid = UUID.randomUUID();
        String entry = "1:" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        Map<Object, Object> hash = new HashMap<>();
        hash.put("progress", "0.6");
        when(hashOps.entries(any(String.class))).thenReturn(hash);
        when(articleService.findIdByUuid(articleUuid)).thenReturn(100L);
        when(progressMapper.upsert(any(), any(), any(), any())).thenThrow(new RuntimeException("DB error"));

        job.flush();

        // 不從 dirty 移除（保留下次 retry）
        verify(setOps, never()).remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
    }

    @Test
    void flush_articleAlreadyDeleted_removesDirtyAndKey() {
        UUID articleUuid = UUID.randomUUID();
        String entry = "1:" + articleUuid;
        when(setOps.members(RedisKeyConstant.READING_DIRTY_KEY)).thenReturn(Set.of(entry));
        Map<Object, Object> hash = new HashMap<>();
        hash.put("progress", "0.6");
        when(hashOps.entries(any(String.class))).thenReturn(hash);
        when(articleService.findIdByUuid(articleUuid)).thenReturn(null);  // article 已被刪除

        job.flush();

        verify(redisTemplate).delete(RedisKeyConstant.READING_PROGRESS_PREFIX + entry);
        verify(setOps).remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
        verify(progressMapper, never()).upsert(any(), any(), any(), any());
    }
}
```

- [ ] **Step 2: Run red**

```bash
./mvnw.cmd -pl blog-module-reading test -Dtest=ReadingProgressFlushJobTest 2>&1 | tee logs/flush-red.log
```

Expected: compile error.

- [ ] **Step 3: 實作 ReadingProgressFlushJob.java**

```java
package dowob.xyz.blog.module.reading.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.reading.mapper.ReadingProgressMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reading Progress Redis → DB 定期 flush 任務。
 *
 * <p>每 5 分鐘掃 reading:dirty Set，把待持久化的 progress 寫到 DB。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingProgressFlushJob {

    private final StringRedisTemplate redisTemplate;
    private final ArticleService articleService;
    private final ReadingProgressMapper progressMapper;

    @Scheduled(fixedDelayString = "${reading.progress.flush-interval-ms:300000}")  // 5 min
    public void flush() {
        Set<String> dirtyEntries = redisTemplate.opsForSet().members(RedisKeyConstant.READING_DIRTY_KEY);
        if (dirtyEntries == null || dirtyEntries.isEmpty()) return;

        for (String entry : dirtyEntries) {
            try {
                String[] parts = entry.split(":", 2);
                Long userId = Long.parseLong(parts[0]);
                UUID articleUuid = UUID.fromString(parts[1]);
                String key = RedisKeyConstant.READING_PROGRESS_PREFIX + entry;

                Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
                if (hash.isEmpty()) {
                    // expired before flush
                    redisTemplate.opsForSet().remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
                    continue;
                }

                Long articleId = articleService.findIdByUuid(articleUuid);
                if (articleId == null) {
                    // article deleted
                    redisTemplate.delete(key);
                    redisTemplate.opsForSet().remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
                    continue;
                }

                BigDecimal progress = new BigDecimal(hash.get("progress").toString());
                Object hVal = hash.get("lastHeading");
                String lastHeading = (hVal != null && !hVal.toString().isEmpty()) ? hVal.toString() : null;

                progressMapper.upsert(userId, articleId, progress, lastHeading);
                redisTemplate.opsForSet().remove(RedisKeyConstant.READING_DIRTY_KEY, entry);
            } catch (Exception e) {
                log.warn("flush 跳過無效 entry，下次重試 - {}", entry, e);
                // 不從 dirty 移除
            }
        }
    }
}
```

- [ ] **Step 4: Run green**

```bash
./mvnw.cmd -pl blog-module-reading test -Dtest=ReadingProgressFlushJobTest 2>&1 | tee logs/flush-green.log
```

Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add blog-module-reading/src/main/java/dowob/xyz/blog/module/reading/job/ReadingProgressFlushJob.java \
        blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/job/ReadingProgressFlushJobTest.java
git commit -m "$(cat <<'EOF'
feat(reading): ReadingProgressFlushJob (5min 定期 flush)

- @Scheduled fixedDelay 5 分鐘
- SMEMBERS reading:dirty → for each: HGETALL → UPSERT → SREM
- 過期 key / 文章已刪除 / DB 錯誤都各自處理（不中斷整批）
- 4 個 unit test 覆蓋

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: 跨模組整合 IT

**Files:**
- Create: `blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/integration/CrossModuleReadingIT.java`

- [ ] **Step 1: 實作 IT**

```java
package dowob.xyz.blog.module.reading.integration;

// imports 對齊 BookmarkControllerIT

@SpringBootTest(classes = ReadingTestApplication.class, ...)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Reading 跨模組整合 IT")
class CrossModuleReadingIT {

    // ... container + DynamicPropertySource ...

    // ... Autowired + MockitoBean ...

    @Test
    @DisplayName("ArticleResponse.bookmarked - bookmark 後變 true")
    void articleResponse_includesBookmarkedFlag() throws Exception {
        // bookmark
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER))).andExpect(status().isOk());

        // GET /articles → bookmarked: true
        mockMvc.perform(get("/api/v1/articles/{uuid}", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(jsonPath("$.data.bookmarked").value(true));
    }

    @Test
    @DisplayName("ArticleSummary.lastReadProgress - PUT progress 後反映")
    void articleSummary_includesLastReadProgress() throws Exception {
        UpdateProgressRequest req = new UpdateProgressRequest();
        req.setProgress(new BigDecimal("0.5"));
        mockMvc.perform(put("/api/v1/articles/{uuid}/progress", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/articles")
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(jsonPath("$.data.records[0].lastReadProgress").value(0.5));
    }

    @Test
    @DisplayName("Anonymous - 進度為 null")
    void articleSummary_unauth_progressIsNull() throws Exception {
        mockMvc.perform(get("/api/v1/articles"))
                .andExpect(jsonPath("$.data.records[0].lastReadProgress").doesNotExist());
        // 或 .value(IsNull.nullValue())
    }

    @Test
    @DisplayName("Article 物理刪除 cascade - bookmarks/highlights/progress 清空")
    void deleteArticle_cascadeRemovesAllReadingState() throws Exception {
        // 建 bookmark / highlight / progress
        bookmarkRepo.save(/* ... */);
        highlightRepo.save(/* ... */);
        progressRepo.save(/* ... */);

        // 物理刪除 article
        articleRepo.deleteById(articleId);

        // 驗證
        assertThat(bookmarkRepo.findByUserIdAndArticleId(USER_ID, articleId)).isEmpty();
        assertThat(highlightRepo.findByUserIdAndArticleIdOrderByCreatedAtAsc(USER_ID, articleId)).isEmpty();
        assertThat(progressRepo.findByUserIdAndArticleId(USER_ID, articleId)).isEmpty();
    }
}
```

- [ ] **Step 2: Run**

```bash
./mvnw.cmd -pl blog-module-reading test -Dtest=CrossModuleReadingIT 2>&1 | tee logs/cross-module-it.log
```

Expected: 4 tests pass.

- [ ] **Step 3: Run all reading module tests**

```bash
./mvnw.cmd -pl blog-module-reading test 2>&1 | tee logs/reading-all.log
```

Expected: 49 tests pass（28 unit + 21 IT — 用戶端 IT 共 5+8+6+4 = 23 actually. Counts may vary; check logs for actual)

- [ ] **Step 4: Commit**

```bash
git add blog-module-reading/src/test/java/dowob/xyz/blog/module/reading/integration/CrossModuleReadingIT.java
git commit -m "$(cat <<'EOF'
test(reading): 加跨模組整合 IT (4 tests)

- ArticleResponse.bookmarked - bookmark 後反映
- ArticleSummary.lastReadProgress - PUT progress 後反映
- 匿名查詢進度為 null
- Article 物理刪除 cascade 清光所有 reading state

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 16: 更新 ai-docs/schema.md

**Files:**
- Modify: `ai-docs/schema.md`

依照 CLAUDE.md Schema Maintenance 規則。

- [ ] **Step 1: 加 V14 三張表到 schema.md**

於 `ai-docs/schema.md` 找適當位置（建議在 comments / comment_likes 之後），加：

```markdown
### user_bookmarks

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| user_id | BIGINT | NOT NULL REFERENCES users(id) | NO ACTION（user 軟刪除）|
| article_id | BIGINT | NOT NULL REFERENCES articles(id) ON DELETE CASCADE | |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `user_bookmarks_pkey` (auto) on id
- `uq_user_bookmarks_user_article` UNIQUE on (user_id, article_id) | 覆蓋 is-bookmarked + my-bookmarks 列表

**V14 新增**

---

### user_highlights

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | |
| user_id | BIGINT | NOT NULL REFERENCES users(id) | NO ACTION |
| article_id | BIGINT | NOT NULL REFERENCES articles(id) ON DELETE CASCADE | |
| snippet | TEXT | NOT NULL | max 500 chars (app-level) |
| prefix | VARCHAR(64) | NOT NULL DEFAULT '' | 前 32 字元 anchor + buffer |
| suffix | VARCHAR(64) | NOT NULL DEFAULT '' | 後 32 字元 anchor + buffer |
| color | VARCHAR(7) | NOT NULL DEFAULT '#FFEB3B' | hex；CHECK ~ '^#[0-9A-Fa-f]{6}$' |
| note | TEXT | NULL | max 2000 chars (app-level)，純文字 |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `user_highlights_pkey` (auto)
- `user_highlights_uuid_key` (auto, UNIQUE) on uuid
- `idx_user_highlights_article_for_user` on (user_id, article_id)
- `idx_user_highlights_user_recent` on (user_id, created_at DESC)

**Constraints:**
- `chk_user_highlights_color` CHECK (color ~ '^#[0-9A-Fa-f]{6}$')

**V14 新增**

---

### user_reading_progress

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| user_id | BIGINT | NOT NULL REFERENCES users(id) | NO ACTION |
| article_id | BIGINT | NOT NULL REFERENCES articles(id) ON DELETE CASCADE | |
| progress | NUMERIC(4,3) | NOT NULL CHECK (>= 0 AND <= 1) | 0.000 - 1.000 |
| last_heading_anchor | VARCHAR(255) | NULL | |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `user_reading_progress_pkey` (auto)
- `uq_user_reading_progress_user_article` UNIQUE on (user_id, article_id) | 覆蓋 lookup + UPSERT
- `idx_user_reading_progress_user_recent` on (user_id, updated_at DESC) | 將來閱讀清單預留

**Constraints:**
- `chk_user_reading_progress_range` CHECK (progress >= 0 AND progress <= 1)

**Storage 策略：**
- Redis 主：`reading:progress:{userId}:{articleUuid}` Hash, TTL 3 天
- DB 為 5 分鐘 flush 後備份
- progress >= 0.95 視為已讀完，DEL Redis + UPSERT DB（保留 row）

**V14 新增**
```

於 Migration Index 末尾加：

```markdown
- **V14**: 新增 reading interactions — user_bookmarks / user_highlights / user_reading_progress
```

- [ ] **Step 2: Commit**

```bash
git add ai-docs/schema.md
git commit -m "$(cat <<'EOF'
docs(schema): 更新 schema.md 加入 V14 三張表

- user_bookmarks
- user_highlights（含 color CHECK constraint）
- user_reading_progress（含 progress range CHECK + Redis 策略說明）
- Migration Index 補上 V14 描述

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review Checklist

實作完成後請勾選：

- [ ] V14 migration 在 Testcontainers 跑通，既有 article + comment 模組測試無回退
- [ ] `blog-module-reading` 模組正確註冊
- [ ] `RedisKeyConstant` 4 個新常數
- [ ] 3 個 entity + 3 個 Repository
- [ ] DTO 6 個 + ReadingErrorCode
- [ ] BookmarkService TDD 7 tests + IT 6 tests
- [ ] HighlightService TDD 9 tests + IT 8 tests
- [ ] ReadingProgressService TDD 8 tests（mock Redis）+ IT 5 tests（真 Redis）
- [ ] ReadingProgressFlushJob TDD 4 tests
- [ ] ArticleQueryService 擴充 + 4 新 unit tests
- [ ] 跨模組整合 IT 4 tests
- [ ] schema.md V14 新增三表寫入
- [ ] **總計 ~49 tests** 覆蓋

---

## 後續 task

- 批 3 — Article Series（系列文）
- 批 4 — Draft History / Versioning
- （可選）批 5 — `GET /me/highlights`、Bookmark 分類

---

## 附錄：相關文件更新清單

實作完成時須同步更新：

- [ ] `ai-docs/schema.md` 補 V14 三張表（已在 Task 16）
- [ ] auto memory：批 2 完成記錄（controller 收尾時加）
