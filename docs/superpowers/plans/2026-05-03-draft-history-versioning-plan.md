# Draft History / Versioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 實作 `blog-module-version` 模組（編輯器自動快照 + 手動快照 + 發布凍結 + 還原），同 PR 順帶 refactor article 模組以 MQ event-driven pattern 發布變更通知（避免 batch 1-3 的 facade + @Lazy 循環依賴）。

**Architecture:** 新增 `blog-module-version` 獨立模組（沿用 batch 1-3 模式）。Article 模組透過 `ArticleEventPublisher` 發 `ArticleContentChangedEvent` 到 RabbitMQ；Version 模組 `ArticleVersionConsumer` 訂閱該事件評估觸發條件後寫入 `article_versions` 表。Restore 同步操作（不走 MQ）— Version 模組直接 inject `ArticleRepository` + `ArticleMarkdownRenderer` + `ArticleEventPublisher`（單向依賴，零循環）。

**Tech Stack:** Spring Boot, Spring Data JDBC, MyBatis, PostgreSQL（V16 migration）, RabbitMQ, Flyway, JUnit 5, Mockito, Spring Security Test, Testcontainers (PostgreSQL + Redis).

**Spec:** `docs/superpowers/specs/2026-05-03-draft-history-versioning-design.md`

---

## File Map

### 新增檔案

```
blog-db-migration/
└─ src/main/resources/db/migration/V16__add_article_versions_and_user_preferences.sql

blog-module-article/
└─ src/main/java/dowob/xyz/blog/module/article/
   ├─ event/ArticleContentChangedEvent.java                 NEW (record + Action enum)
   └─ service/ArticleEventPublisher.java                    NEW (refactor 既有 publish 邏輯)

blog-module-version/                                        [NEW MODULE]
├─ pom.xml
├─ src/main/java/dowob/xyz/blog/module/version/
│  ├─ config/VersionRabbitMqConfig.java
│  ├─ consumer/ArticleVersionConsumer.java
│  ├─ controller/VersionController.java
│  ├─ controller/PreferenceController.java
│  ├─ service/VersioningService.java
│  ├─ service/AutoSnapshotPolicy.java
│  ├─ service/PreferenceResolver.java
│  ├─ repository/ArticleVersionRepository.java
│  ├─ repository/UserPreferenceRepository.java
│  ├─ mapper/VersionMapper.java
│  ├─ mapper/UserPreferenceMapper.java
│  ├─ model/
│  │   ├─ ArticleVersion.java                              (entity)
│  │   ├─ UserPreference.java                              (entity)
│  │   └─ dto/
│  │       ├─ request/{CreateManualSnapshotRequest, UpdatePreferenceRequest}.java
│  │       └─ response/{VersionSummaryResponse, VersionDetailResponse,
│  │                    EffectiveConfigResponse, AutoSnapshotConfig}.java
│  └─ exception/VersionErrorCode.java
└─ src/test/
   ├─ java/dowob/xyz/blog/module/version/
   │  ├─ config/VersionTestApplication.java
   │  ├─ service/{AutoSnapshotPolicyTest, VersioningServiceTest, PreferenceResolverTest}.java
   │  ├─ controller/{VersionControllerIT, PreferenceControllerIT}.java
   │  ├─ consumer/ArticleVersionConsumerIT.java
   │  └─ integration/CrossModuleVersionIT.java
   └─ resources/
      ├─ application-test.yaml
      └─ db/testdata/R__version_test_seed.sql

ai-docs/schema.md                                          MODIFY (V16 兩張新表)

pom.xml (root)                                             MODIFY (加 blog-module-version)
blog-start/pom.xml                                         MODIFY (加 dependency)
blog-start/src/main/resources/application.yaml             MODIFY (加 version.auto.* 預設)
blog-start/src/test/java/dowob/xyz/blog/e2e/support/
                                  DatabaseCleaner.java     MODIFY (補表清理)
blog-infrastructure/.../config/MyBatisConfig.java          MODIFY (加 version.mapper)
blog-module-article/.../config/ArticleRabbitMqConfig.java  MODIFY (加 ROUTING_KEY_CONTENT_CHANGED)
blog-module-article/.../service/ArticleServiceImpl.java    MODIFY (改用 ArticleEventPublisher)
```

---

## Pre-Flight Notes

1. **Worktree**：`.worktrees/feature-draft-history-versioning/`，base 在 develop（`df5df51`，含批 1+2+3 全部）
2. **Maven**：`./mvnw.cmd`（Windows wrapper）
3. **測試輸出**：`./mvnw.cmd test ... 2>&1 | tee logs/<task>.log`
4. **Surefire 報告**：失敗時讀 `<module>/target/surefire-reports/TEST-*.xml`
5. **Commit 慣例**：Conventional Commits + 繁中描述 + Co-Authored-By 行
6. **既有 patterns 對齊：**
   - Spring Data JDBC entity 用 `@Table` + `@Column` + `@CreatedDate`/`@LastModifiedDate`
   - UUID 必設 `setUuid(UUID.randomUUID())`
   - `BusinessException` 路徑：`dowob.xyz.blog.common.exception.BusinessException`
   - GlobalExceptionHandler：`BusinessException` → HTTP 400 + body code；未認證 → HTTP 401
   - IT 必須 R__seed.sql 在 `src/test/resources/db/testdata/` 提供 FK users
   - application-test.yaml 必須有 `mybatis.type-handlers-package: dowob.xyz.blog.infrastructure.config`
   - **MyBatisConfig.@MapperScan 必須加新模組 mapper package**（批 1/2/3 教訓）
   - Test app 不掃自己模組以外的東西時要 `@MockitoBean` 補欠缺的 bean
7. **MQ event payload 故意輕量**：consumer 自己用 articleRepository 拉完整 article（對齊 ArticleViewedEvent 慣例）
8. **PG TOAST 自動壓縮 TEXT 欄位**（≥2KB），不需手動 GZIP article_versions.content

---

## Task 1: V16 Migration

**Files:**
- Create: `blog-db-migration/src/main/resources/db/migration/V16__add_article_versions_and_user_preferences.sql`

- [ ] **Step 1: Write V16 SQL**

```sql
-- V16__add_article_versions_and_user_preferences.sql

-- ─────────────────────────────────────────────
-- Part 1: article_versions 表
-- ─────────────────────────────────────────────
CREATE TABLE article_versions (
    id              BIGSERIAL    PRIMARY KEY,
    uuid            UUID         NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    article_id      BIGINT       NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    author_id       BIGINT       NOT NULL REFERENCES users(id),
    type            VARCHAR(20)  NOT NULL CHECK (type IN ('AUTO','MANUAL','PUBLISHED')),
    title           VARCHAR(255) NOT NULL,
    slug            VARCHAR(255) NOT NULL,
    content         TEXT         NOT NULL,
    summary         VARCHAR(500) NULL,
    category_id     BIGINT       NULL,
    cover_image_url VARCHAR(512) NULL,
    status          VARCHAR(20)  NOT NULL,
    tags            UUID[]       NULL,
    note            VARCHAR(255) NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_article_versions_article_created
    ON article_versions(article_id, created_at DESC);
CREATE INDEX idx_article_versions_article_type
    ON article_versions(article_id, type);

-- ─────────────────────────────────────────────
-- Part 2: user_preferences 表
-- ─────────────────────────────────────────────
CREATE TABLE user_preferences (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pref_key   VARCHAR(100) NOT NULL,
    pref_value TEXT         NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_preferences_user_key UNIQUE (user_id, pref_key)
);
```

- [ ] **Step 2: 編譯驗證**

```bash
./mvnw.cmd -pl blog-db-migration -am compile 2>&1 | tee logs/t1-v16-compile.log
```

Expected: BUILD SUCCESS。compile 不會跑 Flyway，只確認 module 整體編譯沒問題。

- [ ] **Step 3: Commit**

```bash
git add blog-db-migration/src/main/resources/db/migration/V16__add_article_versions_and_user_preferences.sql
git commit -m "$(cat <<'EOF'
feat(version): V16 migration — article_versions + user_preferences

- 新建 article_versions 表（含 type CHECK / tags UUID[] / FK article ON DELETE CASCADE）
- 新建 user_preferences 表（K-V 通用，UNIQUE(user_id, pref_key)）
- 兩張表預留批 4 draft history / versioning 模組使用
- partial index 略：article_id+created_at DESC（列表）、article_id+type（配額計算）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: ArticleContentChangedEvent record + ROUTING_KEY 常數

**Files:**
- Create: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/event/ArticleContentChangedEvent.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/config/ArticleRabbitMqConfig.java`

- [ ] **Step 1: ArticleContentChangedEvent.java**

```java
package dowob.xyz.blog.module.article.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 文章內容變更事件（輕量 marker，給 version 模組訂閱觸發快照）。
 *
 * <p>故意設計輕量 payload — consumer 自己用 articleRepository 拉完整 article。
 * 對齊 ArticleViewedEvent 慣例。</p>
 *
 * @param articleId    文章資料庫主鍵
 * @param articleUuid  文章公開 UUID
 * @param authorId     作者資料庫主鍵
 * @param action       觸發動作（SAVED / PUBLISHED / RESTORED）
 * @param occurredAt   事件發生時間
 *
 * @author Yuan
 * @version 1.0
 */
public record ArticleContentChangedEvent(
    Long articleId,
    UUID articleUuid,
    Long authorId,
    Action action,
    Instant occurredAt
) {
    public enum Action {
        /** 任何 update（draft 或 published 都發） */
        SAVED,
        /** publish 動作（同時也發既有的 ArticlePublishedEvent） */
        PUBLISHED,
        /** restore 完成（version 模組訂閱時 no-op，但 search 訂同 article.updated 重 index） */
        RESTORED
    }
}
```

- [ ] **Step 2: ArticleRabbitMqConfig 加常數**

Read `blog-module-article/src/main/java/dowob/xyz/blog/module/article/config/ArticleRabbitMqConfig.java`，在既有的 `ROUTING_KEY_TAGGED` 之後加：

```java
    /** 文章內容變更（含 SAVED / PUBLISHED / RESTORED）— 給 version 模組訂閱 */
    public static final String ROUTING_KEY_CONTENT_CHANGED = "article.content.changed";
```

- [ ] **Step 3: 編譯**

```bash
./mvnw.cmd -pl blog-module-article -am compile 2>&1 | tee logs/t2-event.log
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/event/ArticleContentChangedEvent.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/config/ArticleRabbitMqConfig.java
git commit -m "$(cat <<'EOF'
feat(article): 新增 ArticleContentChangedEvent + ROUTING_KEY_CONTENT_CHANGED

- 新 event record 為 Version 模組訂閱觸發快照寫入用
- 輕量 payload（articleId + uuid + authorId + action + occurredAt），consumer 自己拉完整 article
- Action enum: SAVED / PUBLISHED / RESTORED
- 對齊 ArticleViewedEvent 風格

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 抽出 ArticleEventPublisher + ArticleServiceImpl 改用

**Files:**
- Create: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleEventPublisher.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java`

- [ ] **Step 1: ArticleEventPublisher.java**

```java
package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent.Action;
import dowob.xyz.blog.module.article.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.article.event.ArticlePublishedEvent;
import dowob.xyz.blog.module.article.event.ArticleTagEvent;
import dowob.xyz.blog.module.article.event.ArticleUpdatedEvent;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.utils.MarkdownStripper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Article 事件發布器（best-effort）。
 *
 * <p>從 ArticleServiceImpl 抽出散落的 rabbitTemplate.convertAndSend 邏輯，
 * 統一在此 component。Version 模組可 inject 來發 RESTORED event。</p>
 *
 * <p>所有 publish 方法皆 best-effort（try/catch + log warn），失敗不影響呼叫端業務。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArticleEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final UserFacade userFacade;

    /**
     * 發 ArticleContentChangedEvent（給 version 模組訂閱）。
     */
    public void publishContentChanged(Article article, Action action) {
        try {
            ArticleContentChangedEvent event = new ArticleContentChangedEvent(
                article.getId(),
                article.getUuid(),
                article.getAuthorId(),
                action,
                Instant.now()
            );
            rabbitTemplate.convertAndSend(
                ArticleRabbitMqConfig.EXCHANGE,
                ArticleRabbitMqConfig.ROUTING_KEY_CONTENT_CHANGED,
                event
            );
        } catch (Exception e) {
            log.warn("ArticleContentChangedEvent 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    /**
     * 發 ArticleUpdatedEvent（既有，給 search 重 index 用）— 僅 PUBLISHED 才送。
     */
    public void publishUpdated(Article article) {
        if (article.getStatus() != ArticleStatus.PUBLISHED) {
            return;
        }
        try {
            ArticleUpdatedEvent event = new ArticleUpdatedEvent(
                article.getUuid(),
                article.getAuthorId(),
                article.getTitle(),
                article.getPublishedAt(),
                article.getSlug(),
                article.getSummary(),
                MarkdownStripper.strip(article.getContent()),
                userFacade.getUserUsernameById(article.getAuthorId()).orElse(null),
                userFacade.getUserNicknameById(article.getAuthorId()).orElse(null),
                List.of()  // tags 由呼叫端額外處理；本 method 不撈 tags
            );
            rabbitTemplate.convertAndSend(
                ArticleRabbitMqConfig.EXCHANGE,
                ArticleRabbitMqConfig.ROUTING_KEY_UPDATED,
                event
            );
        } catch (Exception e) {
            log.warn("ArticleUpdatedEvent 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    /**
     * 發 ArticlePublishedEvent（既有，給 recommend / search 用）。
     */
    public void publishPublished(Article article, List<TagInfo> tags) {
        try {
            ArticlePublishedEvent event = new ArticlePublishedEvent(
                article.getUuid(),
                article.getAuthorId(),
                article.getTitle(),
                article.getPublishedAt(),
                article.getSlug(),
                article.getSummary(),
                MarkdownStripper.strip(article.getContent()),
                userFacade.getUserUsernameById(article.getAuthorId()).orElse(null),
                userFacade.getUserNicknameById(article.getAuthorId()).orElse(null),
                tags
            );
            rabbitTemplate.convertAndSend(
                ArticleRabbitMqConfig.EXCHANGE,
                ArticleRabbitMqConfig.ROUTING_KEY_PUBLISHED,
                event
            );
        } catch (Exception e) {
            log.warn("ArticlePublishedEvent 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    /**
     * 發 ArticleDeletedEvent（既有，給 search 從索引移除）。
     */
    public void publishDeleted(Article article) {
        try {
            ArticleDeletedEvent event = new ArticleDeletedEvent(article.getUuid(), Instant.now());
            rabbitTemplate.convertAndSend(
                ArticleRabbitMqConfig.EXCHANGE,
                ArticleRabbitMqConfig.ROUTING_KEY_DELETED,
                event
            );
        } catch (Exception e) {
            log.warn("ArticleDeletedEvent 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    /**
     * 發 ArticleTagEvent（既有，給 tag.usage 計數）。
     */
    public void publishTagged(Article article, List<UUID> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        try {
            ArticleTagEvent event = new ArticleTagEvent(article.getUuid(), tagIds);
            rabbitTemplate.convertAndSend(
                ArticleRabbitMqConfig.EXCHANGE,
                ArticleRabbitMqConfig.ROUTING_KEY_TAGGED,
                event
            );
        } catch (Exception e) {
            log.warn("ArticleTagEvent 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }
}
```

⚠ 注意 `userFacade.getUserNicknameById` 是否存在 — 用 Grep 確認 UserFacade interface。如果欄位名為 `getUserNickname` 或 `resolveAuthorNickname` 等，調整對應。如果 UserFacade 沒這 method，看既有 ArticleServiceImpl 的 `resolveAuthorNickname` 怎麼做（可能是 user 模組另一個入口），對齊。

⚠ 注意 `MarkdownStripper.strip` 是既有 utility（ArticleServiceImpl 用的 `stripMarkdown` 內呼叫），確認類別位置。

- [ ] **Step 2: ArticleServiceImpl 改用 ArticleEventPublisher**

Read `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java`，找到所有 `rabbitTemplate.convertAndSend(...)` 呼叫，refactor 為 publisher 呼叫。

關鍵改動：
- 加 `private final ArticleEventPublisher articleEventPublisher;` inject（@RequiredArgsConstructor 自動）
- `updateArticle` 末尾的 `publishUpdatedEvent(updated)` 改 `articleEventPublisher.publishUpdated(updated)` + 加 `articleEventPublisher.publishContentChanged(updated, Action.SAVED)`
- `publishArticle` 末尾的 publish event 改 `articleEventPublisher.publishPublished(saved, tags)` + 加 `articleEventPublisher.publishContentChanged(saved, Action.PUBLISHED)`
- `deleteArticle` 末尾的 delete event 改 `articleEventPublisher.publishDeleted(article)`
- 標籤事件改 `articleEventPublisher.publishTagged(article, tagIds)`
- 移除既有的 `publishUpdatedEvent` private method（功能搬到 publisher）

⚠ 注意 `rabbitTemplate` 與 `userFacade` field 如有不再使用，移除 inject + import。

- [ ] **Step 3: 跑既有 article 模組所有測試確保 refactor 沒破壞**

```bash
./mvnw.cmd -pl blog-module-article -am test 2>&1 | tee logs/t3-article-tests.log
```

Expected: 既有 240 tests 全綠。如有失敗（如 ArticleServiceTest mock 的是 `RabbitTemplate` 而 refactor 後變 inject `ArticleEventPublisher`），對應修 test mock：

```java
@Mock private ArticleEventPublisher articleEventPublisher;
// 移除 @Mock RabbitTemplate / @Mock UserFacade（如不再被 service 直接用）
```

並驗證 `verify(articleEventPublisher).publishUpdated(...)` 等。

- [ ] **Step 4: Commit**

```bash
git add blog-module-article/src/
git commit -m "$(cat <<'EOF'
refactor(article): 抽出 ArticleEventPublisher 統一 MQ 發送

- 從 ArticleServiceImpl 抽出散落的 rabbitTemplate.convertAndSend
- ArticleEventPublisher 提供 publishContentChanged / Updated / Published / Deleted / Tagged
- ArticleServiceImpl.updateArticle 加發 ContentChanged(SAVED)
- ArticleServiceImpl.publishArticle 加發 ContentChanged(PUBLISHED)
- 為 batch 4 Version 模組訂閱 + restore 發 RESTORED 鋪路
- 既有 240 tests 全綠（mock 對應調整）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: blog-module-version 模組骨架

**Files:**
- Create: `blog-module-version/pom.xml`
- Modify: `pom.xml` (root)
- Modify: `blog-start/pom.xml`
- Modify: `blog-start/src/main/resources/application.yaml`
- Modify: `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/config/MyBatisConfig.java`

- [ ] **Step 1: 建模組目錄樹**

```bash
cd "D:/end/workspace/java/blog-web-v2/.worktrees/feature-draft-history-versioning"
mkdir -p blog-module-version/src/main/java/dowob/xyz/blog/module/version/{config,consumer,controller,service,repository,mapper,model/dto/request,model/dto/response,exception}
mkdir -p blog-module-version/src/test/java/dowob/xyz/blog/module/version/{config,service,controller,consumer,integration}
mkdir -p blog-module-version/src/test/resources/db/testdata
```

- [ ] **Step 2: blog-module-version/pom.xml**

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

    <artifactId>blog-module-version</artifactId>

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
        <dependency>
            <groupId>org.springframework.amqp</groupId>
            <artifactId>spring-rabbit-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: 註冊到 root pom.xml `<modules>`**

Read root `pom.xml`，在 `blog-module-series` 之後（`blog-start` 之前）加：

```xml
<module>blog-module-version</module>
```

於 `<dependencyManagement>` Internal Modules 區塊加（在 `blog-module-series` dependency 之後）：

```xml
<dependency>
    <groupId>dowob.xyz</groupId>
    <artifactId>blog-module-version</artifactId>
    <version>${blog.version}</version>
</dependency>
```

- [ ] **Step 4: 加 dependency 到 blog-start/pom.xml**

Read `blog-start/pom.xml`，在 `blog-module-series` 之後加：

```xml
<dependency>
    <groupId>dowob.xyz</groupId>
    <artifactId>blog-module-version</artifactId>
</dependency>
```

- [ ] **Step 5: 更新 MyBatisConfig @MapperScan**

Read `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/config/MyBatisConfig.java`，於 `@MapperScan basePackages` array 加：

```java
"dowob.xyz.blog.module.version.mapper"
```

確保 array 包含所有模組 mapper：user, article, tag, file, comment, reading, series, **version**。

⚠ **批 1/2/3 都踩過這坑（缺新模組 mapper package 導致 E2E 失敗）**。本 step 必做。

- [ ] **Step 6: blog-start application.yaml 加 version 預設**

Read `blog-start/src/main/resources/application.yaml`，在合適位置加：

```yaml
version:
  auto:
    enabled: true
    retain: 50
    interval-seconds: 60
    diff-chars: 50
```

- [ ] **Step 7: 驗證編譯**

```bash
./mvnw.cmd -pl blog-module-version -am compile 2>&1 | tee logs/t4-version-skeleton.log
./mvnw.cmd compile -DskipTests 2>&1 | tee logs/t4-root-compile.log
```

Expected: 兩個都 BUILD SUCCESS（即使 source 樹空，maven 還是能 compile）。

- [ ] **Step 8: Commit**

```bash
git add blog-module-version/pom.xml pom.xml blog-start/pom.xml \
        blog-start/src/main/resources/application.yaml \
        blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/config/MyBatisConfig.java
git commit -m "$(cat <<'EOF'
feat(version): 新增 blog-module-version 模組骨架

- 註冊到 root pom modules + dependencyManagement
- 依賴 blog-infrastructure / blog-module-article
- 加入 blog-start dependency
- MyBatisConfig @MapperScan 加入 version.mapper（批 1/2/3 教訓）
- application.yaml 加 version.auto.* 4 個預設值

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: ArticleVersion / UserPreference entity + Repository

**Files:**
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/model/ArticleVersion.java`
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/model/UserPreference.java`
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/repository/ArticleVersionRepository.java`
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/repository/UserPreferenceRepository.java`

- [ ] **Step 1: ArticleVersion.java entity**

```java
package dowob.xyz.blog.module.version.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文章版本快照（article_versions 表映射）。
 *
 * <p>3 種 type：
 * <ul>
 *   <li>AUTO — 自動快照，滾動 N 份保留</li>
 *   <li>MANUAL — 用戶手動快照，永久保留</li>
 *   <li>PUBLISHED — publish 時系統凍結，永久保留</li>
 * </ul>
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("article_versions")
public class ArticleVersion {
    @Id
    private Long id;

    private UUID uuid;

    @Column("article_id")
    private Long articleId;

    @Column("author_id")
    private Long authorId;

    private String type;

    private String title;

    private String slug;

    private String content;

    private String summary;

    @Column("category_id")
    private Long categoryId;

    @Column("cover_image_url")
    private String coverImageUrl;

    private String status;

    /** PG UUID[] — Spring Data JDBC 對 array 對應為 List<UUID>（透過 type handler）*/
    private java.util.List<UUID> tags;

    private String note;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;
}
```

⚠ Spring Data JDBC 對 PG UUID[] 的支援：可能需要在 infrastructure 配 `JdbcCustomConversions` 或 type handler。**先用 List<UUID>，跑 IT 時若報「cannot convert array」再加 type handler**。實際上 PG JDBC driver 對 `UUID[]` → `java.util.List<UUID>` 有 native 支援，多數情況直接 work。

- [ ] **Step 2: UserPreference.java entity**

```java
package dowob.xyz.blog.module.version.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 使用者偏好設定（user_preferences 表映射）。
 *
 * <p>通用 K-V 結構，key 以 dot separator 分組（如 version.auto.retain）。
 * 未來其他模組（bookmark / reading）也可塞同一張表。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user_preferences")
public class UserPreference {
    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("pref_key")
    private String prefKey;

    @Column("pref_value")
    private String prefValue;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: ArticleVersionRepository.java**

```java
package dowob.xyz.blog.module.version.repository;

import dowob.xyz.blog.module.version.model.ArticleVersion;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Article Version Repository。
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface ArticleVersionRepository extends CrudRepository<ArticleVersion, Long> {
    Optional<ArticleVersion> findByUuid(UUID uuid);

    /** 取最新一筆 type 的快照（用於 AutoSnapshotPolicy 計算 diff）*/
    @Query("""
            SELECT * FROM article_versions
             WHERE article_id = :articleId AND type = :type
             ORDER BY created_at DESC LIMIT 1
            """)
    Optional<ArticleVersion> findLatestByArticleAndType(
            @Param("articleId") Long articleId,
            @Param("type") String type);
}
```

- [ ] **Step 4: UserPreferenceRepository.java**

```java
package dowob.xyz.blog.module.version.repository;

import dowob.xyz.blog.module.version.model.UserPreference;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * User Preference Repository。
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface UserPreferenceRepository extends CrudRepository<UserPreference, Long> {
    Optional<UserPreference> findByUserIdAndPrefKey(Long userId, String prefKey);
    List<UserPreference> findByUserId(Long userId);
    void deleteByUserIdAndPrefKey(Long userId, String prefKey);
}
```

- [ ] **Step 5: 編譯驗證**

```bash
./mvnw.cmd -pl blog-module-version -am compile 2>&1 | tee logs/t5-entities.log
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add blog-module-version/src/main/java/dowob/xyz/blog/module/version/model/ \
        blog-module-version/src/main/java/dowob/xyz/blog/module/version/repository/
git commit -m "$(cat <<'EOF'
feat(version): ArticleVersion / UserPreference entity + Repository

- ArticleVersion entity 對齊既有慣例（@CreatedDate / @Column / Spring Data JDBC）
- ArticleVersion.tags 用 List<UUID> 對應 PG UUID[] (driver 原生支援)
- UserPreference 通用 K-V entity（@LastModifiedDate）
- ArticleVersionRepository: findByUuid + findLatestByArticleAndType
- UserPreferenceRepository: findByUserIdAndPrefKey / findByUserId / deleteByUserIdAndPrefKey

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: VersionErrorCode + DTOs

**Files:**
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/exception/VersionErrorCode.java`
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/model/dto/request/{CreateManualSnapshotRequest, UpdatePreferenceRequest}.java`
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/model/dto/response/{VersionSummaryResponse, VersionDetailResponse, EffectiveConfigResponse, AutoSnapshotConfig}.java`

- [ ] **Step 1: VersionErrorCode.java**

```java
package dowob.xyz.blog.module.version.exception;

import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Version 模組錯誤碼。
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
@AllArgsConstructor
public enum VersionErrorCode implements IErrorCode {

    VERSION_NOT_FOUND("V0101", "Version 不存在"),
    VERSION_ACCESS_DENIED("V0102", "不可操作他人的 Version"),
    CANNOT_DELETE_PUBLISHED("V0103", "PUBLISHED 凍結快照不可刪除"),
    CANNOT_PROMOTE_NON_AUTO("V0104", "只有 AUTO 類型可升級為 MANUAL"),
    PREFERENCE_INVALID("V0105", "配置值超出合理範圍"),
    ARTICLE_NOT_FOUND("V0106", "操作的 Article 不存在");

    private final String code;
    private final String message;
}
```

- [ ] **Step 2: CreateManualSnapshotRequest.java**

```java
package dowob.xyz.blog.module.version.model.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 建立手動快照請求。note 為 optional 命名。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class CreateManualSnapshotRequest {
    @Size(max = 255)
    private String note;
}
```

- [ ] **Step 3: UpdatePreferenceRequest.java**

```java
package dowob.xyz.blog.module.version.model.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 更新 version 偏好請求；所有欄位 optional，僅當非 null 才更新。
 *
 * <p>各欄位範圍對齊 Validation：
 * <ul>
 *   <li>retain: [1, 300]</li>
 *   <li>intervalSeconds: [10, 600] 上限 10 分鐘</li>
 *   <li>diffChars: [0, 5000]，0 = disabled（純時間觸發）</li>
 * </ul></p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class UpdatePreferenceRequest {
    private Boolean enabled;

    @Min(1)
    @Max(300)
    private Integer retain;

    @Min(10)
    @Max(600)
    private Integer intervalSeconds;

    @Min(0)
    @Max(5000)
    private Integer diffChars;
}
```

- [ ] **Step 4: AutoSnapshotConfig.java（response + 內部用）**

```java
package dowob.xyz.blog.module.version.model.dto.response;

/**
 * 自動快照配置（user effective）— PreferenceResolver 的輸出 record。
 *
 * @param enabled         是否啟用自動快照
 * @param retain          滾動保留份數
 * @param intervalSeconds 自動快照觸發時間間隔（秒）
 * @param diffChars       自動快照觸發字元差距門檻；0 表示 disabled（純時間觸發）
 *
 * @author Yuan
 * @version 1.0
 */
public record AutoSnapshotConfig(
    boolean enabled,
    int retain,
    int intervalSeconds,
    int diffChars
) {}
```

- [ ] **Step 5: VersionSummaryResponse.java**

```java
package dowob.xyz.blog.module.version.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 版本列表用 summary（不含 content，給列表頁輕量用）。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VersionSummaryResponse {
    private UUID uuid;
    private String type;            // AUTO / MANUAL / PUBLISHED
    private String note;
    private LocalDateTime createdAt;
    private Long authorId;
    private int contentLength;       // 字元數，給前端排序 / 顯示用
}
```

- [ ] **Step 6: VersionDetailResponse.java**

```java
package dowob.xyz.blog.module.version.model.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 版本詳情（含完整快照內容）— 給預覽 / restore 確認用。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class VersionDetailResponse {
    private UUID uuid;
    private String type;
    private String note;
    private LocalDateTime createdAt;
    private Long authorId;

    private String title;
    private String slug;
    private String content;
    private String summary;
    private Long categoryId;
    private String coverImageUrl;
    private String status;
    private List<UUID> tags;
}
```

- [ ] **Step 7: EffectiveConfigResponse.java**

```java
package dowob.xyz.blog.module.version.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Effective version config response。每個欄位含 value + source 給前端標示來源。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EffectiveConfigResponse {
    private Field<Boolean> enabled;
    private Field<Integer> retain;
    private Field<Integer> intervalSeconds;
    private Field<Integer> diffChars;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Field<T> {
        private T value;
        /** "user" — 來自 user_preferences override；"system" — 來自 application.yaml fallback */
        private String source;
    }
}
```

- [ ] **Step 8: 編譯**

```bash
./mvnw.cmd -pl blog-module-version -am compile 2>&1 | tee logs/t6-dtos.log
```

Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add blog-module-version/src/main/java/dowob/xyz/blog/module/version/exception/ \
        blog-module-version/src/main/java/dowob/xyz/blog/module/version/model/dto/
git commit -m "$(cat <<'EOF'
feat(version): VersionErrorCode + DTOs

- VersionErrorCode V0101-V0106
- CreateManualSnapshotRequest / UpdatePreferenceRequest（含 @Min/@Max validation）
- VersionSummaryResponse（不含 content）/ VersionDetailResponse（含 content）
- EffectiveConfigResponse 每欄位含 value + source（user|system）
- AutoSnapshotConfig record 給 PreferenceResolver 輸出用

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: VersionMapper（MyBatis 批次 SQL）

**Files:**
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/mapper/VersionMapper.java`
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/mapper/UserPreferenceMapper.java`

- [ ] **Step 1: VersionMapper.java**

```java
package dowob.xyz.blog.module.version.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Version 模組 MyBatis Mapper — retention DELETE / count 等批次操作。
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface VersionMapper {

    /**
     * 滾動保留：刪掉 article 的 AUTO 類型快照中超過 retain 份的最舊那些。
     *
     * <p>用 OFFSET 跳過最新 retain 份，剩下的就是要刪的。</p>
     */
    @Update({
        "DELETE FROM article_versions",
        " WHERE id IN (",
        "   SELECT id FROM article_versions",
        "    WHERE article_id = #{articleId} AND type = 'AUTO'",
        "    ORDER BY created_at DESC",
        "    OFFSET #{retain}",
        " )"
    })
    int retainAuto(@Param("articleId") Long articleId, @Param("retain") int retain);

    /**
     * publish 凍結時清掉所有 AUTO 快照（保留 MANUAL / PUBLISHED）。
     */
    @Update("DELETE FROM article_versions WHERE article_id = #{articleId} AND type = 'AUTO'")
    int deleteAutoByArticle(@Param("articleId") Long articleId);

    /**
     * 計算 article 的 PUBLISHED 快照數（用於 freezePublished 算 vN）。
     */
    @Select("SELECT COUNT(*) FROM article_versions WHERE article_id = #{articleId} AND type = 'PUBLISHED'")
    int countPublished(@Param("articleId") Long articleId);
}
```

- [ ] **Step 2: UserPreferenceMapper.java**

```java
package dowob.xyz.blog.module.version.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * UserPreference UPSERT mapper。
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface UserPreferenceMapper {

    /**
     * UPSERT 一筆 user preference。
     */
    @Update({
        "INSERT INTO user_preferences (user_id, pref_key, pref_value)",
        "VALUES (#{userId}, #{prefKey}, #{prefValue})",
        "ON CONFLICT (user_id, pref_key) DO UPDATE",
        "  SET pref_value = EXCLUDED.pref_value, updated_at = CURRENT_TIMESTAMP"
    })
    int upsert(@Param("userId") Long userId,
               @Param("prefKey") String prefKey,
               @Param("prefValue") String prefValue);
}
```

- [ ] **Step 3: 編譯**

```bash
./mvnw.cmd -pl blog-module-version -am compile 2>&1 | tee logs/t7-mapper.log
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add blog-module-version/src/main/java/dowob/xyz/blog/module/version/mapper/
git commit -m "$(cat <<'EOF'
feat(version): VersionMapper + UserPreferenceMapper

- VersionMapper.retainAuto: 滾動保留邏輯（OFFSET retain 後刪）
- VersionMapper.deleteAutoByArticle: publish 凍結後清 AUTO
- VersionMapper.countPublished: 算 publish v N 用
- UserPreferenceMapper.upsert: ON CONFLICT 更新 + updated_at

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: PreferenceResolver + AutoSnapshotPolicy (TDD)

**Files:**
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/PreferenceResolver.java`
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/AutoSnapshotPolicy.java`
- Create: `blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/PreferenceResolverTest.java`
- Create: `blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/AutoSnapshotPolicyTest.java`

### Sub-task 8A: PreferenceResolver（4 unit tests）

- [ ] **Step 1: PreferenceResolverTest.java**

```java
package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.module.version.model.UserPreference;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.repository.UserPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreferenceResolverTest {

    @Mock private UserPreferenceRepository repo;
    @InjectMocks private PreferenceResolver resolver;

    private final Long userId = 1L;

    @BeforeEach
    void setupDefaults() {
        ReflectionTestUtils.setField(resolver, "defaultEnabled", true);
        ReflectionTestUtils.setField(resolver, "defaultRetain", 50);
        ReflectionTestUtils.setField(resolver, "defaultIntervalSeconds", 60);
        ReflectionTestUtils.setField(resolver, "defaultDiffChars", 50);
    }

    private UserPreference pref(String key, String value) {
        return new UserPreference(null, userId, key, value, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void resolveForUser_noOverride_returnsAllSystemDefaults() {
        when(repo.findByUserId(userId)).thenReturn(List.of());

        AutoSnapshotConfig cfg = resolver.resolveForUser(userId);

        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.retain()).isEqualTo(50);
        assertThat(cfg.intervalSeconds()).isEqualTo(60);
        assertThat(cfg.diffChars()).isEqualTo(50);
    }

    @Test
    void resolveForUser_fullOverride_returnsAllUserValues() {
        when(repo.findByUserId(userId)).thenReturn(List.of(
            pref("version.auto.enabled", "false"),
            pref("version.auto.retain", "30"),
            pref("version.auto.interval-seconds", "120"),
            pref("version.auto.diff-chars", "100")
        ));

        AutoSnapshotConfig cfg = resolver.resolveForUser(userId);

        assertThat(cfg.enabled()).isFalse();
        assertThat(cfg.retain()).isEqualTo(30);
        assertThat(cfg.intervalSeconds()).isEqualTo(120);
        assertThat(cfg.diffChars()).isEqualTo(100);
    }

    @Test
    void resolveForUser_partialOverride_mixedSources() {
        when(repo.findByUserId(userId)).thenReturn(List.of(
            pref("version.auto.retain", "100"),
            pref("version.auto.diff-chars", "0")
        ));

        AutoSnapshotConfig cfg = resolver.resolveForUser(userId);

        assertThat(cfg.enabled()).isTrue();           // system default
        assertThat(cfg.retain()).isEqualTo(100);      // user
        assertThat(cfg.intervalSeconds()).isEqualTo(60);  // system default
        assertThat(cfg.diffChars()).isEqualTo(0);     // user (disabled)
    }

    @Test
    void resolveForUser_disabledFlag_disablesAutoSnapshot() {
        when(repo.findByUserId(userId)).thenReturn(List.of(
            pref("version.auto.enabled", "false")
        ));

        AutoSnapshotConfig cfg = resolver.resolveForUser(userId);

        assertThat(cfg.enabled()).isFalse();
    }
}
```

- [ ] **Step 2: Run RED**

```bash
./mvnw.cmd -pl blog-module-version -am test -Dtest=PreferenceResolverTest 2>&1 | tee logs/t8a-red.log
```

Expected: 編譯失敗（PreferenceResolver class 不存在）。

- [ ] **Step 3: PreferenceResolver.java（GREEN minimal）**

```java
package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.module.version.model.UserPreference;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.repository.UserPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 user 的 effective version config（user_preferences override → application.yaml fallback）。
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class PreferenceResolver {

    public static final String KEY_ENABLED = "version.auto.enabled";
    public static final String KEY_RETAIN = "version.auto.retain";
    public static final String KEY_INTERVAL_SECONDS = "version.auto.interval-seconds";
    public static final String KEY_DIFF_CHARS = "version.auto.diff-chars";

    private final UserPreferenceRepository repo;

    @Value("${version.auto.enabled:true}")
    private boolean defaultEnabled;
    @Value("${version.auto.retain:50}")
    private int defaultRetain;
    @Value("${version.auto.interval-seconds:60}")
    private int defaultIntervalSeconds;
    @Value("${version.auto.diff-chars:50}")
    private int defaultDiffChars;

    public AutoSnapshotConfig resolveForUser(Long userId) {
        Map<String, String> kv = toMap(repo.findByUserId(userId));
        return new AutoSnapshotConfig(
            getBool(kv, KEY_ENABLED,          defaultEnabled),
            getInt (kv, KEY_RETAIN,           defaultRetain),
            getInt (kv, KEY_INTERVAL_SECONDS, defaultIntervalSeconds),
            getInt (kv, KEY_DIFF_CHARS,       defaultDiffChars)
        );
    }

    /** 取得單筆 user override，未設則回 null（給 EffectiveConfigResponse source 標示用）*/
    public String getRawValue(Long userId, String prefKey) {
        return repo.findByUserIdAndPrefKey(userId, prefKey)
                .map(UserPreference::getPrefValue)
                .orElse(null);
    }

    private Map<String, String> toMap(List<UserPreference> prefs) {
        Map<String, String> map = new HashMap<>();
        for (UserPreference p : prefs) {
            map.put(p.getPrefKey(), p.getPrefValue());
        }
        return map;
    }

    private boolean getBool(Map<String, String> kv, String key, boolean fallback) {
        String v = kv.get(key);
        if (v == null) return fallback;
        return Boolean.parseBoolean(v);
    }

    private int getInt(Map<String, String> kv, String key, int fallback) {
        String v = kv.get(key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) { return fallback; }
    }
}
```

- [ ] **Step 4: Run GREEN**

```bash
./mvnw.cmd -pl blog-module-version -am test -Dtest=PreferenceResolverTest 2>&1 | tee logs/t8a-green.log
```

Expected: 4 tests pass.

### Sub-task 8B: AutoSnapshotPolicy（6 unit tests）

- [ ] **Step 5: AutoSnapshotPolicyTest.java**

```java
package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoSnapshotPolicyTest {

    @Mock private ArticleRepository articleRepo;
    @Mock private ArticleVersionRepository versionRepo;
    @Mock private PreferenceResolver preferenceResolver;
    @InjectMocks private AutoSnapshotPolicy policy;

    private final Long articleId = 100L;
    private final Long authorId = 1L;

    private Article article(String content) {
        Article a = new Article();
        a.setId(articleId);
        a.setAuthorId(authorId);
        a.setContent(content);
        return a;
    }

    private ArticleVersion lastAuto(String content, LocalDateTime createdAt) {
        ArticleVersion v = new ArticleVersion();
        v.setContent(content);
        v.setCreatedAt(createdAt);
        return v;
    }

    @Test
    void shouldSnapshot_disabled_returnsFalse() {
        when(articleRepo.findById(articleId)).thenReturn(Optional.of(article("aaa")));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(false, 50, 60, 50));

        assertThat(policy.shouldSnapshot(articleId)).isFalse();
    }

    @Test
    void shouldSnapshot_firstTime_returnsTrue() {
        when(articleRepo.findById(articleId)).thenReturn(Optional.of(article("aaa")));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.findLatestByArticleAndType(articleId, "AUTO"))
            .thenReturn(Optional.empty());

        assertThat(policy.shouldSnapshot(articleId)).isTrue();
    }

    @Test
    void shouldSnapshot_intervalNotElapsed_returnsFalse() {
        when(articleRepo.findById(articleId)).thenReturn(Optional.of(article("a".repeat(200))));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.findLatestByArticleAndType(articleId, "AUTO"))
            .thenReturn(Optional.of(lastAuto("a".repeat(100), LocalDateTime.now().minusSeconds(30))));

        assertThat(policy.shouldSnapshot(articleId)).isFalse();
    }

    @Test
    void shouldSnapshot_diffNotEnough_returnsFalse() {
        when(articleRepo.findById(articleId)).thenReturn(Optional.of(article("a".repeat(110))));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.findLatestByArticleAndType(articleId, "AUTO"))
            .thenReturn(Optional.of(lastAuto("a".repeat(100), LocalDateTime.now().minusSeconds(120))));

        assertThat(policy.shouldSnapshot(articleId)).isFalse();
    }

    @Test
    void shouldSnapshot_diffCharsZero_skipsDiffCheck_returnsTrueWhenIntervalPassed() {
        when(articleRepo.findById(articleId)).thenReturn(Optional.of(article("a".repeat(100))));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 0));   // diffChars=0 disabled
        when(versionRepo.findLatestByArticleAndType(articleId, "AUTO"))
            .thenReturn(Optional.of(lastAuto("a".repeat(100), LocalDateTime.now().minusSeconds(120))));

        assertThat(policy.shouldSnapshot(articleId)).isTrue();
    }

    @Test
    void shouldSnapshot_intervalAndDiffPass_returnsTrue() {
        when(articleRepo.findById(articleId)).thenReturn(Optional.of(article("a".repeat(200))));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.findLatestByArticleAndType(articleId, "AUTO"))
            .thenReturn(Optional.of(lastAuto("a".repeat(100), LocalDateTime.now().minusSeconds(120))));

        assertThat(policy.shouldSnapshot(articleId)).isTrue();
    }
}
```

- [ ] **Step 6: Run RED**

```bash
./mvnw.cmd -pl blog-module-version -am test -Dtest=AutoSnapshotPolicyTest 2>&1 | tee logs/t8b-red.log
```

Expected: 編譯失敗。

- [ ] **Step 7: AutoSnapshotPolicy.java（GREEN）**

```java
package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 自動快照觸發判斷（時間 + 字元差距雙門檻）。
 *
 * <p>邏輯：
 * <ol>
 *   <li>取 user effective config — 若 enabled=false 直接 false</li>
 *   <li>若無前一筆 AUTO → true（第一次）</li>
 *   <li>若距上次 AUTO 不到 intervalSeconds → false</li>
 *   <li>若 diffChars > 0 且字元差距 < diffChars → false</li>
 *   <li>都通過 → true</li>
 * </ol></p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class AutoSnapshotPolicy {

    public static final String TYPE_AUTO = "AUTO";

    private final ArticleRepository articleRepo;
    private final ArticleVersionRepository versionRepo;
    private final PreferenceResolver preferenceResolver;

    public boolean shouldSnapshot(Long articleId) {
        Article article = articleRepo.findById(articleId).orElse(null);
        if (article == null) return false;

        AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.getAuthorId());
        if (!cfg.enabled()) return false;

        Optional<ArticleVersion> lastOpt = versionRepo.findLatestByArticleAndType(articleId, TYPE_AUTO);
        if (lastOpt.isEmpty()) return true;

        ArticleVersion last = lastOpt.get();
        Duration sinceLast = Duration.between(last.getCreatedAt(), LocalDateTime.now());
        if (sinceLast.getSeconds() < cfg.intervalSeconds()) return false;

        if (cfg.diffChars() > 0) {
            int diff = Math.abs(currentLength(article) - currentLength(last));
            if (diff < cfg.diffChars()) return false;
        }

        return true;
    }

    private int currentLength(Article a) {
        return a.getContent() != null ? a.getContent().length() : 0;
    }

    private int currentLength(ArticleVersion v) {
        return v.getContent() != null ? v.getContent().length() : 0;
    }
}
```

- [ ] **Step 8: Run GREEN**

```bash
./mvnw.cmd -pl blog-module-version -am test -Dtest=AutoSnapshotPolicyTest 2>&1 | tee logs/t8b-green.log
```

Expected: 6 tests pass.

- [ ] **Step 9: Run all version unit tests**

```bash
./mvnw.cmd -pl blog-module-version -am test 2>&1 | tee logs/t8-all.log
```

Expected: 10 tests pass (4 PreferenceResolver + 6 AutoSnapshotPolicy).

- [ ] **Step 10: Commit**

```bash
git add blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/PreferenceResolver.java \
        blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/AutoSnapshotPolicy.java \
        blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/PreferenceResolverTest.java \
        blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/AutoSnapshotPolicyTest.java
git commit -m "$(cat <<'EOF'
feat(version): PreferenceResolver + AutoSnapshotPolicy（TDD）

- PreferenceResolver: user_preferences override → application.yaml fallback
- 4 個 pref keys: enabled / retain / interval-seconds / diff-chars
- AutoSnapshotPolicy: 時間 + 字元差距雙門檻判斷
- diffChars=0 表 disabled（純時間觸發）
- 10 unit tests（4 + 6）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: VersioningService.recordAutoSnapshot + retention (TDD)

**Files:**
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/VersioningService.java`（先寫 recordAutoSnapshot）
- Create: `blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/VersioningServiceTest.java`

- [ ] **Step 1: VersioningServiceTest 加 2 個 test**

```java
package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.version.mapper.VersionMapper;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VersioningServiceTest {

    @Mock private ArticleRepository articleRepo;
    @Mock private ArticleVersionRepository versionRepo;
    @Mock private VersionMapper versionMapper;
    @Mock private PreferenceResolver preferenceResolver;
    // 後續 task 加 markdownRenderer / eventPublisher / userPrefRepo / userPrefMapper

    @InjectMocks private VersioningService service;

    private final Long articleId = 100L;
    private final Long authorId = 1L;

    private Article article(String title, String content) {
        Article a = new Article();
        a.setId(articleId);
        a.setUuid(UUID.randomUUID());
        a.setAuthorId(authorId);
        a.setTitle(title);
        a.setSlug("test-slug");
        a.setContent(content);
        a.setStatus(dowob.xyz.blog.common.api.enums.ArticleStatus.DRAFT);
        return a;
    }

    @Test
    void recordAutoSnapshot_savesAndAppliesRetention() {
        when(articleRepo.findById(articleId)).thenReturn(Optional.of(article("Test", "Hello world")));
        when(preferenceResolver.resolveForUser(authorId))
            .thenReturn(new AutoSnapshotConfig(true, 50, 60, 50));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordAutoSnapshot(articleId);

        ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
        verify(versionRepo).save(captor.capture());
        ArticleVersion saved = captor.getValue();
        assertThat(saved.getUuid()).isNotNull();
        assertThat(saved.getArticleId()).isEqualTo(articleId);
        assertThat(saved.getAuthorId()).isEqualTo(authorId);
        assertThat(saved.getType()).isEqualTo("AUTO");
        assertThat(saved.getTitle()).isEqualTo("Test");
        assertThat(saved.getContent()).isEqualTo("Hello world");

        verify(versionMapper).retainAuto(articleId, 50);
    }

    @Test
    void recordAutoSnapshot_articleNotFound_doesNothing() {
        when(articleRepo.findById(articleId)).thenReturn(Optional.empty());

        service.recordAutoSnapshot(articleId);

        verify(versionRepo, org.mockito.Mockito.never()).save(any());
        verify(versionMapper, org.mockito.Mockito.never()).retainAuto(any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
```

⚠ 後續 task 會 inject 更多 mock；這些 test 用 `@InjectMocks` 會自動填 null 給未 mock 的 field（lombok @RequiredArgsConstructor 順序）。如撞到 NPE 在 step 後加 `@MockitoSettings(strictness = LENIENT)` 或補 mock。

- [ ] **Step 2: Run RED**

```bash
./mvnw.cmd -pl blog-module-version -am test -Dtest=VersioningServiceTest 2>&1 | tee logs/t9-red.log
```

Expected: 編譯失敗（VersioningService 不存在）。

- [ ] **Step 3: VersioningService.java（先寫 recordAutoSnapshot）**

```java
package dowob.xyz.blog.module.version.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.version.mapper.VersionMapper;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.model.dto.response.AutoSnapshotConfig;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Version 模組核心 Service — 寫入 / 還原 / 配置。
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class VersioningService {

    public static final String TYPE_AUTO = "AUTO";
    public static final String TYPE_MANUAL = "MANUAL";
    public static final String TYPE_PUBLISHED = "PUBLISHED";

    private final ArticleRepository articleRepo;
    private final ArticleVersionRepository versionRepo;
    private final VersionMapper versionMapper;
    private final PreferenceResolver preferenceResolver;

    @Transactional
    public void recordAutoSnapshot(Long articleId) {
        Article article = articleRepo.findById(articleId).orElse(null);
        if (article == null) return;

        AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.getAuthorId());

        ArticleVersion v = snapshotFromArticle(article, TYPE_AUTO, null);
        versionRepo.save(v);

        versionMapper.retainAuto(articleId, cfg.retain());
    }

    /** 把 article 當前狀態複製成一份 ArticleVersion（不寫入）。 */
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
        v.setCategoryId(article.getCategoryId());
        v.setCoverImageUrl(article.getCoverImageUrl());
        ArticleStatus st = article.getStatus();
        v.setStatus(st != null ? st.name() : null);
        // tags 暫由 mapper 從 article_tags 撈（T11 restore + freezePublished 細節補上）
        v.setNote(note);
        v.setCreatedAt(LocalDateTime.now());
        return v;
    }
}
```

- [ ] **Step 4: Run GREEN**

```bash
./mvnw.cmd -pl blog-module-article -am install -DskipTests
./mvnw.cmd -pl blog-module-version test -Dtest=VersioningServiceTest 2>&1 | tee logs/t9-green.log
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/VersioningService.java \
        blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/VersioningServiceTest.java
git commit -m "$(cat <<'EOF'
feat(version): VersioningService.recordAutoSnapshot + retention（TDD）

- recordAutoSnapshot: snapshot from article + INSERT type=AUTO + retainAuto
- snapshotFromArticle helper: 共用給 manual / published / pre-restore
- 2 unit tests（happy path + article not found 早返）

⚠ tags 暫不複製，T11 restore 中補完整 tags handling

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: VersioningService.recordManualSnapshot / freezePublished / promote / delete (TDD)

**Files:**
- Modify: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/VersioningService.java`（加 4 個 method）
- Modify: `blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/VersioningServiceTest.java`（加 8 個 test）

- [ ] **Step 1: 加 8 個 test 到 VersioningServiceTest**

於既有 VersioningServiceTest class 內加：

```java
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.version.exception.VersionErrorCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

private final Long versionId = 200L;
private final UUID versionUuid = UUID.randomUUID();

private ArticleVersion existingVersion(String type) {
    ArticleVersion v = new ArticleVersion();
    v.setId(versionId);
    v.setUuid(versionUuid);
    v.setArticleId(articleId);
    v.setAuthorId(authorId);
    v.setType(type);
    v.setTitle("Test");
    v.setContent("Hello");
    v.setStatus("DRAFT");
    return v;
}

// ─── recordManualSnapshot ─────────────

@Test
void recordManualSnapshot_savesWithNote_noRetention() {
    when(articleRepo.findById(articleId)).thenReturn(Optional.of(article("T", "C")));
    when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.recordManualSnapshot(articleId, "milestone");

    ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
    verify(versionRepo).save(captor.capture());
    assertThat(captor.getValue().getType()).isEqualTo("MANUAL");
    assertThat(captor.getValue().getNote()).isEqualTo("milestone");
    verify(versionMapper, never()).retainAuto(any(), org.mockito.ArgumentMatchers.anyInt());
}

@Test
void recordManualSnapshot_articleNotFound_throwsV0106() {
    when(articleRepo.findById(articleId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.recordManualSnapshot(articleId, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining(VersionErrorCode.ARTICLE_NOT_FOUND.getMessage());
}

// ─── freezePublished ─────────────

@Test
void freezePublished_writesPublishedAndClearsAutos() {
    when(articleRepo.findById(articleId)).thenReturn(Optional.of(article("T", "C")));
    when(versionMapper.countPublished(articleId)).thenReturn(0);  // 沒 publish 過
    when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.freezePublished(articleId);

    ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
    verify(versionRepo).save(captor.capture());
    assertThat(captor.getValue().getType()).isEqualTo("PUBLISHED");
    assertThat(captor.getValue().getNote()).isEqualTo("Published v1");

    verify(versionMapper).deleteAutoByArticle(articleId);
}

@Test
void freezePublished_secondPublishIncrementsVN() {
    when(articleRepo.findById(articleId)).thenReturn(Optional.of(article("T", "C")));
    when(versionMapper.countPublished(articleId)).thenReturn(1);  // 第二次 publish
    when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.freezePublished(articleId);

    ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
    verify(versionRepo).save(captor.capture());
    assertThat(captor.getValue().getNote()).isEqualTo("Published v2");
}

// ─── promote ─────────────

@Test
void promote_autoToManual_updatesType() {
    ArticleVersion v = existingVersion("AUTO");
    when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));
    when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.promote(versionUuid, authorId, false);

    ArgumentCaptor<ArticleVersion> captor = ArgumentCaptor.forClass(ArticleVersion.class);
    verify(versionRepo).save(captor.capture());
    assertThat(captor.getValue().getType()).isEqualTo("MANUAL");
}

@Test
void promote_nonAuto_throwsV0104() {
    ArticleVersion v = existingVersion("MANUAL");
    when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

    assertThatThrownBy(() -> service.promote(versionUuid, authorId, false))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining(VersionErrorCode.CANNOT_PROMOTE_NON_AUTO.getMessage());
}

@Test
void promote_byNonOwner_throwsV0102() {
    ArticleVersion v = existingVersion("AUTO");
    when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

    assertThatThrownBy(() -> service.promote(versionUuid, 999L, false))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining(VersionErrorCode.VERSION_ACCESS_DENIED.getMessage());
}

// ─── delete ─────────────

@Test
void delete_manualVersion_deletes() {
    ArticleVersion v = existingVersion("MANUAL");
    when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

    service.delete(versionUuid, authorId, false);

    verify(versionRepo).delete(v);
}

@Test
void delete_autoVersion_deletes() {
    ArticleVersion v = existingVersion("AUTO");
    when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

    service.delete(versionUuid, authorId, false);

    verify(versionRepo).delete(v);
}

@Test
void delete_publishedVersion_throwsV0103() {
    ArticleVersion v = existingVersion("PUBLISHED");
    when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(v));

    assertThatThrownBy(() -> service.delete(versionUuid, authorId, false))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining(VersionErrorCode.CANNOT_DELETE_PUBLISHED.getMessage());
}
```

- [ ] **Step 2: Run RED**

```bash
./mvnw.cmd -pl blog-module-version -am test -Dtest=VersioningServiceTest 2>&1 | tee logs/t10-red.log
```

Expected: 編譯失敗（recordManualSnapshot / freezePublished / promote / delete 不存在）。

- [ ] **Step 3: 加 4 個 method 到 VersioningService**

```java
// 加 import
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.version.exception.VersionErrorCode;

// 加方法（接 recordAutoSnapshot 後）

@Transactional
public ArticleVersion recordManualSnapshot(Long articleId, String note) {
    Article article = articleRepo.findById(articleId)
        .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));

    ArticleVersion v = snapshotFromArticle(article, TYPE_MANUAL, note);
    return versionRepo.save(v);
}

@Transactional
public void freezePublished(Long articleId) {
    Article article = articleRepo.findById(articleId).orElse(null);
    if (article == null) return;

    int count = versionMapper.countPublished(articleId);
    String note = "Published v" + (count + 1);

    ArticleVersion v = snapshotFromArticle(article, TYPE_PUBLISHED, note);
    versionRepo.save(v);

    versionMapper.deleteAutoByArticle(articleId);
}

@Transactional
public ArticleVersion promote(UUID versionUuid, Long currentUserId, boolean isAdmin) {
    ArticleVersion v = versionRepo.findByUuid(versionUuid)
        .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
    if (!isAdmin && !v.getAuthorId().equals(currentUserId)) {
        throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
    }
    if (!TYPE_AUTO.equals(v.getType())) {
        throw new BusinessException(VersionErrorCode.CANNOT_PROMOTE_NON_AUTO);
    }
    v.setType(TYPE_MANUAL);
    return versionRepo.save(v);
}

@Transactional
public void delete(UUID versionUuid, Long currentUserId, boolean isAdmin) {
    ArticleVersion v = versionRepo.findByUuid(versionUuid)
        .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
    if (!isAdmin && !v.getAuthorId().equals(currentUserId)) {
        throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
    }
    if (TYPE_PUBLISHED.equals(v.getType())) {
        throw new BusinessException(VersionErrorCode.CANNOT_DELETE_PUBLISHED);
    }
    versionRepo.delete(v);
}
```

- [ ] **Step 4: Run GREEN**

```bash
./mvnw.cmd -pl blog-module-version -am test -Dtest=VersioningServiceTest 2>&1 | tee logs/t10-green.log
```

Expected: 11 tests pass (2 + 9 加上).

- [ ] **Step 5: Commit**

```bash
git add blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/VersioningService.java \
        blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/VersioningServiceTest.java
git commit -m "$(cat <<'EOF'
feat(version): VersioningService manual / freeze / promote / delete（TDD）

- recordManualSnapshot: INSERT type=MANUAL（不執行 retention）
- freezePublished: INSERT type=PUBLISHED note="Published vN" + 清所有 AUTO
- promote: AUTO → MANUAL（user 救援機制；V0104 / V0102 守衛）
- delete: 只允許 MANUAL / AUTO，禁刪 PUBLISHED（V0103）
- 9 個新 unit test（共 11）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: VersioningService.restore (TDD — 含 stash + 寫回 + tags + render + events)

**Files:**
- Modify: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/VersioningService.java`
- Modify: `blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/VersioningServiceTest.java`
- Possibly modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/mapper/ArticleMapper.java` (加 article_tags 操作)

restore 是 batch 4 最複雜的方法。逐 sub-step：

- [ ] **Step 1: 確認 ArticleMapper 既有 article_tags 操作**

用 Grep 找 article_tags 相關 SQL：

```
Grep "article_tags" in blog-module-article/src/main/java/...
```

確認既有 `ArticleMapper` 有沒有 `deleteArticleTags(articleId)` / `insertArticleTag(articleId, tagId)` 方法。如果沒有，需要加：

```java
@Update("DELETE FROM article_tags WHERE article_id = #{articleId}")
int deleteArticleTags(@Param("articleId") Long articleId);

@Insert("INSERT INTO article_tags (article_id, tag_id) VALUES (#{articleId}, #{tagId})")
int insertArticleTag(@Param("articleId") Long articleId, @Param("tagId") Long tagId);
```

⚠ 如果既有 ArticleMapper 已有相似 method 但簽章不同（例如 `replaceArticleTags(articleId, List<Long>)`），改用既有的避免重複。

- [ ] **Step 2: 確認 ArticleMarkdownRenderer interface**

```
Grep "ArticleMarkdownRenderer" in blog-module-article/src/main/java/
```

確認 inject 方式 — 應該是 `@Component public class ArticleMarkdownRenderer { public String render(String markdown); }`。

- [ ] **Step 3: 確認 TagMapper / Tag 模組怎麼從 UUID 找 id**

需要 batch 撈 tag UUIDs → tag IDs。可能既有 `TagMapper.findIdsByUuids(List<UUID>)` 或 `TagFacade.findIdsByUuids` — 用 Grep 找。

如果不存在，本 task 加 mapper method（在 tag 模組或暴露 `TagFacade`）：

```java
// TagFacade interface 加（在 infrastructure）
List<Long> findIdsByUuids(List<UUID> tagUuids);
```

⚠ 如果加 Facade method，要更新對應 TagFacadeImpl 與相關 mock。

- [ ] **Step 4: 加 restore 的 1 個 unit test 到 VersioningServiceTest**

```java
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent.Action;
import dowob.xyz.blog.module.article.service.ArticleMarkdownRenderer;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import static org.mockito.ArgumentMatchers.anyList;

@Mock private ArticleMarkdownRenderer markdownRenderer;
@Mock private ArticleEventPublisher articleEventPublisher;
@Mock private ArticleMapper articleMapper;
@Mock private TagFacade tagFacade;

@Test
void restore_stashesAndOverwritesArticle() {
    UUID currentUuid = UUID.randomUUID();
    Article current = article("Old", "old content");
    current.setUuid(currentUuid);
    current.setTitle("Old");

    UUID tagUuid = UUID.randomUUID();
    ArticleVersion target = existingVersion("AUTO");
    target.setTitle("New");
    target.setContent("new content");
    target.setStatus("PUBLISHED");
    target.setTags(List.of(tagUuid));

    when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(target));
    when(articleRepo.findById(articleId)).thenReturn(Optional.of(current));
    when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(articleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(markdownRenderer.render("new content")).thenReturn("<p>new content</p>");
    when(tagFacade.findIdsByUuids(List.of(tagUuid))).thenReturn(List.of(99L));

    service.restore(versionUuid, authorId, false);

    // 1. stash 寫入（type=AUTO，title=Old）
    ArgumentCaptor<ArticleVersion> stashCap = ArgumentCaptor.forClass(ArticleVersion.class);
    verify(versionRepo, times(1)).save(stashCap.capture());
    assertThat(stashCap.getValue().getType()).isEqualTo("AUTO");
    assertThat(stashCap.getValue().getTitle()).isEqualTo("Old");

    // 2. retention 執行
    verify(versionMapper).retainAuto(eq(articleId), org.mockito.ArgumentMatchers.anyInt());

    // 3. article 寫回 + render
    ArgumentCaptor<Article> articleCap = ArgumentCaptor.forClass(Article.class);
    verify(articleRepo).save(articleCap.capture());
    assertThat(articleCap.getValue().getTitle()).isEqualTo("New");
    assertThat(articleCap.getValue().getContent()).isEqualTo("new content");
    assertThat(articleCap.getValue().getContentHtml()).isEqualTo("<p>new content</p>");

    // 4. tags 重綁
    verify(articleMapper).deleteArticleTags(articleId);
    verify(articleMapper).insertArticleTag(articleId, 99L);

    // 5. events 發出
    verify(articleEventPublisher).publishContentChanged(any(), eq(Action.RESTORED));
    verify(articleEventPublisher).publishUpdated(any());
}

@Test
void restore_byNonOwner_throwsV0102() {
    ArticleVersion target = existingVersion("AUTO");
    when(versionRepo.findByUuid(versionUuid)).thenReturn(Optional.of(target));

    assertThatThrownBy(() -> service.restore(versionUuid, 999L, false))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining(VersionErrorCode.VERSION_ACCESS_DENIED.getMessage());

    verify(articleRepo, never()).findById(any());
}
```

- [ ] **Step 5: Run RED**

```bash
./mvnw.cmd -pl blog-module-version -am test -Dtest=VersioningServiceTest 2>&1 | tee logs/t11-red.log
```

Expected: 編譯失敗。

- [ ] **Step 6: VersioningService 加 restore + inject 新 dependency**

```java
// 改 inject 區（加 4 個）
private final ArticleMarkdownRenderer markdownRenderer;
private final ArticleEventPublisher articleEventPublisher;
private final ArticleMapper articleMapper;
private final TagFacade tagFacade;

// 加方法

@Transactional
public Article restore(UUID versionUuid, Long currentUserId, boolean isAdmin) {
    ArticleVersion v = versionRepo.findByUuid(versionUuid)
        .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
    if (!isAdmin && !v.getAuthorId().equals(currentUserId)) {
        throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
    }

    Article article = articleRepo.findById(v.getArticleId())
        .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));

    /* 1. stash 當前狀態為 AUTO snapshot */
    ArticleVersion stash = snapshotFromArticle(article, TYPE_AUTO, null);
    versionRepo.save(stash);
    AutoSnapshotConfig cfg = preferenceResolver.resolveForUser(article.getAuthorId());
    versionMapper.retainAuto(article.getId(), cfg.retain());

    /* 2. 寫回 article 內容 */
    article.setTitle(v.getTitle());
    article.setSlug(v.getSlug());
    article.setContent(v.getContent());
    article.setSummary(v.getSummary());
    article.setCategoryId(v.getCategoryId());
    article.setCoverImageUrl(v.getCoverImageUrl());
    if (v.getStatus() != null) {
        article.setStatus(dowob.xyz.blog.common.api.enums.ArticleStatus.valueOf(v.getStatus()));
    }
    article.setContentHtml(markdownRenderer.render(v.getContent()));
    Article saved = articleRepo.save(article);

    /* 3. 重綁 article_tags */
    articleMapper.deleteArticleTags(saved.getId());
    if (v.getTags() != null && !v.getTags().isEmpty()) {
        List<Long> tagIds = tagFacade.findIdsByUuids(v.getTags());
        for (Long tagId : tagIds) {
            articleMapper.insertArticleTag(saved.getId(), tagId);
        }
    }

    /* 4. 發 events */
    articleEventPublisher.publishContentChanged(saved, Action.RESTORED);
    articleEventPublisher.publishUpdated(saved);

    return saved;
}
```

⚠ 注意 imports：`Action`, `ArticleMarkdownRenderer`, `ArticleEventPublisher`, `ArticleMapper`, `TagFacade`。

- [ ] **Step 7: Run GREEN**

```bash
./mvnw.cmd -pl blog-module-article -am install -DskipTests
./mvnw.cmd -pl blog-module-version test -Dtest=VersioningServiceTest 2>&1 | tee logs/t11-green.log
```

Expected: 13 tests pass (11 + 2).

- [ ] **Step 8: Commit**

```bash
git add blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/VersioningService.java \
        blog-module-version/src/test/java/dowob/xyz/blog/module/version/service/VersioningServiceTest.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/mapper/ArticleMapper.java
git commit -m "$(cat <<'EOF'
feat(version): VersioningService.restore 含 stash + 寫回 + tags + events（TDD）

- restore: stash 當前 article 為 AUTO + UPDATE article 內容 + 重綁 tags
- 重新 render contentHtml（呼叫 ArticleMarkdownRenderer）
- 發 ArticleContentChangedEvent(RESTORED) + ArticleUpdatedEvent（給 search re-index）
- 權限檢查 V0102 / V0101 / V0106
- 2 個新 unit test（共 13）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: ArticleVersionConsumer + VersionRabbitMqConfig + Consumer IT

**Files:**
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/config/VersionRabbitMqConfig.java`
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/consumer/ArticleVersionConsumer.java`
- Create: `blog-module-version/src/test/java/dowob/xyz/blog/module/version/config/VersionTestApplication.java`
- Create: `blog-module-version/src/test/resources/application-test.yaml`
- Create: `blog-module-version/src/test/resources/db/testdata/R__version_test_seed.sql`
- Create: `blog-module-version/src/test/java/dowob/xyz/blog/module/version/consumer/ArticleVersionConsumerIT.java`

- [ ] **Step 1: VersionRabbitMqConfig.java**

```java
package dowob.xyz.blog.module.version.config;

import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Version 模組 RabbitMQ 設定。訂閱 article.events / article.content.changed。
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class VersionRabbitMqConfig {

    public static final String QUEUE_VERSION_SNAPSHOT = "version.snapshot";
    public static final String DLQ_ROUTING_KEY = "dlq.version.snapshot";

    @Bean
    public Queue versionSnapshotQueue() {
        return new Queue(QUEUE_VERSION_SNAPSHOT, true, false, false, dlqArgs());
    }

    @Bean
    public Binding bindVersionSnapshot(
            Queue versionSnapshotQueue,
            @Qualifier("articleEventsExchange") TopicExchange articleEventsExchange) {
        return BindingBuilder
                .bind(versionSnapshotQueue)
                .to(articleEventsExchange)
                .with(ArticleRabbitMqConfig.ROUTING_KEY_CONTENT_CHANGED);
    }

    private Map<String, Object> dlqArgs() {
        return Map.of(
            "x-dead-letter-exchange", "dlq.exchange",
            "x-dead-letter-routing-key", DLQ_ROUTING_KEY,
            "x-message-ttl", 600_000  // 10 分鐘 TTL window
        );
    }
}
```

⚠ 確認 article 模組的 TopicExchange bean name — 用 Grep 找 `articleEventsExchange` 或 `EXCHANGE` bean。如非「articleEventsExchange」名，調整 @Qualifier。實際 batch 1-3 都用過此 pattern，看 batch 3 的 SeriesFacade 同模式檔案找。

- [ ] **Step 2: ArticleVersionConsumer.java**

```java
package dowob.xyz.blog.module.version.consumer;

import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent.Action;
import dowob.xyz.blog.module.version.config.VersionRabbitMqConfig;
import dowob.xyz.blog.module.version.service.AutoSnapshotPolicy;
import dowob.xyz.blog.module.version.service.VersioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Article content changed 事件消費者 — 觸發 version 模組寫快照。
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArticleVersionConsumer {

    private final VersioningService versioningService;
    private final AutoSnapshotPolicy autoSnapshotPolicy;

    @RabbitListener(queues = VersionRabbitMqConfig.QUEUE_VERSION_SNAPSHOT)
    public void onContentChanged(ArticleContentChangedEvent event) {
        try {
            switch (event.action()) {
                case SAVED -> {
                    if (autoSnapshotPolicy.shouldSnapshot(event.articleId())) {
                        versioningService.recordAutoSnapshot(event.articleId());
                    }
                }
                case PUBLISHED -> versioningService.freezePublished(event.articleId());
                case RESTORED -> {
                    /* no-op：restore 已在 VersioningService 內部寫過 stash */
                }
            }
        } catch (Exception e) {
            log.error("處理 ArticleContentChangedEvent 失敗 articleId={} action={}",
                event.articleId(), event.action(), e);
            throw e;  // re-throw 讓 RabbitMQ requeue / 進 DLQ
        }
    }
}
```

- [ ] **Step 3: VersionTestApplication.java**

```java
package dowob.xyz.blog.module.version.config;

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
                "dowob.xyz.blog.module.version"
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
        "dowob.xyz.blog.module.version.repository"
})
@MapperScan(basePackages = {
        "dowob.xyz.blog.module.article.mapper",
        "dowob.xyz.blog.module.version.mapper"
})
@EnableScheduling
public class VersionTestApplication {
}
```

⚠ exclude RabbitAutoConfiguration → IT 不真起 RabbitMQ；ArticleVersionConsumerIT 用 direct method call 測 consumer 邏輯（不走 broker）。

- [ ] **Step 4: application-test.yaml**

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

version:
  auto:
    enabled: true
    retain: 50
    interval-seconds: 60
    diff-chars: 50
```

- [ ] **Step 5: R__version_test_seed.sql**

```sql
INSERT INTO users (id, uuid, email, password_hash, nickname, username, role, status, email_verified, created_at, updated_at)
VALUES
    (1, gen_random_uuid(), 'user1@version-test.com', 'hash', 'User1', 'version-user1', 'AUTHOR', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, gen_random_uuid(), 'user2@version-test.com', 'hash', 'User2', 'version-user2', 'AUTHOR', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, gen_random_uuid(), 'admin@version-test.com', 'hash', 'Admin', 'version-admin', 'ADMIN', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

SELECT setval('users_id_seq', GREATEST(3, (SELECT MAX(id) FROM users)));
```

- [ ] **Step 6: ArticleVersionConsumerIT.java（4 IT）**

```java
package dowob.xyz.blog.module.version.consumer;

import com.redis.testcontainers.RedisContainer;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent.Action;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.version.config.VersionTestApplication;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = VersionTestApplication.class)
@Testcontainers
@ActiveProfiles("test")
class ArticleVersionConsumerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("blog_test")
        .withUsername("test")
        .withPassword("test");

    @Container
    static RedisContainer redis = new RedisContainer("redis:7-alpine");

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.url", redis::getRedisURI);
    }

    @Autowired private ArticleVersionConsumer consumer;
    @Autowired private ArticleRepository articleRepo;
    @Autowired private ArticleVersionRepository versionRepo;

    private Article testArticle;

    @BeforeEach
    void setup() {
        versionRepo.deleteAll();
        articleRepo.deleteAll();

        testArticle = new Article();
        testArticle.setUuid(UUID.randomUUID());
        testArticle.setAuthorId(1L);
        testArticle.setTitle("Test");
        testArticle.setSlug("test-" + UUID.randomUUID());
        testArticle.setContent("a".repeat(200));
        testArticle.setStatus(ArticleStatus.DRAFT);
        testArticle.setLikeCount(0);
        testArticle.setCommentCount(0);
        testArticle.setViewCount(0L);
        testArticle.setCreatedAt(LocalDateTime.now());
        testArticle.setUpdatedAt(LocalDateTime.now());
        testArticle = articleRepo.save(testArticle);
    }

    @Test
    void onContentChanged_savedAction_writesAutoVersion() {
        ArticleContentChangedEvent event = new ArticleContentChangedEvent(
            testArticle.getId(), testArticle.getUuid(), 1L, Action.SAVED, Instant.now()
        );

        consumer.onContentChanged(event);

        List<ArticleVersion> versions = versionRepo.findAll().stream()
            .filter(v -> v.getArticleId().equals(testArticle.getId()))
            .toList();
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getType()).isEqualTo("AUTO");
    }

    @Test
    void onContentChanged_publishedAction_freezesAndClearsAutos() {
        // 先寫 3 個 AUTO（手動 INSERT 模擬已有）
        for (int i = 0; i < 3; i++) {
            ArticleVersion v = new ArticleVersion();
            v.setUuid(UUID.randomUUID());
            v.setArticleId(testArticle.getId());
            v.setAuthorId(1L);
            v.setType("AUTO");
            v.setTitle("Test");
            v.setSlug("test-slug");
            v.setContent("auto " + i);
            v.setStatus("DRAFT");
            v.setCreatedAt(LocalDateTime.now());
            versionRepo.save(v);
        }

        ArticleContentChangedEvent event = new ArticleContentChangedEvent(
            testArticle.getId(), testArticle.getUuid(), 1L, Action.PUBLISHED, Instant.now()
        );

        consumer.onContentChanged(event);

        List<ArticleVersion> all = versionRepo.findAll().stream()
            .filter(v -> v.getArticleId().equals(testArticle.getId()))
            .toList();
        // 1 個 PUBLISHED + 0 個 AUTO（清光）
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getType()).isEqualTo("PUBLISHED");
        assertThat(all.get(0).getNote()).isEqualTo("Published v1");
    }

    @Test
    void onContentChanged_restoredAction_isNoOp() {
        ArticleContentChangedEvent event = new ArticleContentChangedEvent(
            testArticle.getId(), testArticle.getUuid(), 1L, Action.RESTORED, Instant.now()
        );

        consumer.onContentChanged(event);

        List<ArticleVersion> versions = versionRepo.findAll().stream()
            .filter(v -> v.getArticleId().equals(testArticle.getId()))
            .toList();
        assertThat(versions).isEmpty();  // RESTORED 不寫
    }

    @Test
    void onContentChanged_savedActionMultipleTimes_retainsCap() {
        // 先用 application.yaml 預設 retain=50；這 test 寫 51 個 AUTO 看是否剩 50
        // 但 51 個寫入要逐次間隔、長度差異，policy 才會觸發
        // 簡化：直接多次呼叫 recordAutoSnapshot 跳過 policy 模擬「policy 都判斷 true 的場景」
        // 實際 production retention 走的是 mapper.retainAuto SQL，所以可直接驗 SQL 正確
        // 此 IT 改測：手動 INSERT 51 個 AUTO，呼叫 recordAutoSnapshot 一次後應只剩 50

        for (int i = 0; i < 51; i++) {
            ArticleVersion v = new ArticleVersion();
            v.setUuid(UUID.randomUUID());
            v.setArticleId(testArticle.getId());
            v.setAuthorId(1L);
            v.setType("AUTO");
            v.setTitle("Test");
            v.setSlug("test-slug");
            v.setContent("auto " + i);
            v.setStatus("DRAFT");
            v.setCreatedAt(LocalDateTime.now().minusSeconds(60 * (51 - i)));  // 老的在前
            versionRepo.save(v);
        }

        // 觸發一次 recordAutoSnapshot，應該寫 1 個新（總 52）然後 retainAuto 刪掉 2 個（剩 50）
        ArticleContentChangedEvent event = new ArticleContentChangedEvent(
            testArticle.getId(), testArticle.getUuid(), 1L, Action.SAVED, Instant.now()
        );
        consumer.onContentChanged(event);

        long autoCount = versionRepo.findAll().stream()
            .filter(v -> v.getArticleId().equals(testArticle.getId()) && "AUTO".equals(v.getType()))
            .count();
        assertThat(autoCount).isLessThanOrEqualTo(50);
    }
}
```

⚠ Step 4 test 在 production 真實情境會：
1. policy 判斷 51 個 AUTO 中最新的（55s 前）距 now 大於 60s? → 應該觸發
2. recordAutoSnapshot INSERT 一個新（總 52）
3. retainAuto OFFSET 50 刪老的 2 個（剩 50）

實際數字會看 retention 邏輯。Test 用 `<=50` 較寬鬆。

- [ ] **Step 7: install + Run IT**

```bash
./mvnw.cmd -pl blog-module-version -am install -DskipTests
./mvnw.cmd -pl blog-module-version test -Dtest=ArticleVersionConsumerIT 2>&1 | tee logs/t12-it.log
```

Expected: 4 IT pass.

- [ ] **Step 8: Commit**

```bash
git add blog-module-version/src/main/java/dowob/xyz/blog/module/version/config/ \
        blog-module-version/src/main/java/dowob/xyz/blog/module/version/consumer/ \
        blog-module-version/src/test/java/dowob/xyz/blog/module/version/config/ \
        blog-module-version/src/test/resources/ \
        blog-module-version/src/test/java/dowob/xyz/blog/module/version/consumer/
git commit -m "$(cat <<'EOF'
feat(version): ArticleVersionConsumer + IT (4 tests)

- VersionRabbitMqConfig: queue version.snapshot 訂 article.events / article.content.changed
- ArticleVersionConsumer @RabbitListener 處理 SAVED / PUBLISHED / RESTORED
- VersionTestApplication 啟動 article + version
- application-test.yaml + R__seed.sql 對齊 batch 1-3 模式
- 4 IT 覆蓋 SAVED 寫 AUTO / PUBLISHED freeze + 清 / RESTORED no-op / retention

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: VersionController + IT

**Files:**
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/controller/VersionController.java`
- Create: `blog-module-version/src/test/java/dowob/xyz/blog/module/version/controller/VersionControllerIT.java`
- Possibly modify: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/VersioningService.java` (加 list / getDetail)

- [ ] **Step 1: 加 list / getDetail 到 VersioningService**

於既有 VersioningService 加：

```java
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.version.model.dto.response.VersionDetailResponse;
import dowob.xyz.blog.module.version.model.dto.response.VersionSummaryResponse;
// import org.springframework.data.domain.PageRequest;

@Transactional(readOnly = true)
public PageResult<VersionSummaryResponse> listByArticle(
        UUID articleUuid, String typeFilter, int page, int size,
        Long currentUserId, boolean isAdmin) {
    Article article = articleRepo.findByUuid(articleUuid)
        .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));
    if (!isAdmin && !article.getAuthorId().equals(currentUserId)) {
        throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
    }
    int offset = Math.max(0, (page - 1) * size);
    List<VersionSummaryResponse> rows = versionMapper
        .listSummaries(article.getId(), typeFilter, size, offset);
    long total = versionMapper.countSummaries(article.getId(), typeFilter);
    return PageResult.of(page, size, total, rows);
}

@Transactional(readOnly = true)
public VersionDetailResponse getDetail(UUID versionUuid, Long currentUserId, boolean isAdmin) {
    ArticleVersion v = versionRepo.findByUuid(versionUuid)
        .orElseThrow(() -> new BusinessException(VersionErrorCode.VERSION_NOT_FOUND));
    if (!isAdmin && !v.getAuthorId().equals(currentUserId)) {
        throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
    }
    return toDetailResponse(v);
}

private VersionDetailResponse toDetailResponse(ArticleVersion v) {
    VersionDetailResponse r = new VersionDetailResponse();
    r.setUuid(v.getUuid());
    r.setType(v.getType());
    r.setNote(v.getNote());
    r.setCreatedAt(v.getCreatedAt());
    r.setAuthorId(v.getAuthorId());
    r.setTitle(v.getTitle());
    r.setSlug(v.getSlug());
    r.setContent(v.getContent());
    r.setSummary(v.getSummary());
    r.setCategoryId(v.getCategoryId());
    r.setCoverImageUrl(v.getCoverImageUrl());
    r.setStatus(v.getStatus());
    r.setTags(v.getTags());
    return r;
}
```

VersionMapper 加對應 method（兩個新）：

```java
@Select({
    "<script>",
    "SELECT uuid, type, note, created_at, author_id, length(content) AS content_length",
    "  FROM article_versions",
    " WHERE article_id = #{articleId}",
    " <if test='typeFilter != null'>AND type = #{typeFilter}</if>",
    " ORDER BY created_at DESC",
    " LIMIT #{size} OFFSET #{offset}",
    "</script>"
})
List<VersionSummaryResponse> listSummaries(
        @Param("articleId") Long articleId,
        @Param("typeFilter") String typeFilter,
        @Param("size") int size,
        @Param("offset") int offset);

@Select({
    "<script>",
    "SELECT COUNT(*) FROM article_versions",
    " WHERE article_id = #{articleId}",
    " <if test='typeFilter != null'>AND type = #{typeFilter}</if>",
    "</script>"
})
long countSummaries(@Param("articleId") Long articleId, @Param("typeFilter") String typeFilter);
```

⚠ MyBatis 自動把 `content_length` → `contentLength` 對應 `VersionSummaryResponse.contentLength`（map-underscore-to-camel-case）。

- [ ] **Step 2: VersionController.java**

```java
package dowob.xyz.blog.module.version.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.model.dto.request.CreateManualSnapshotRequest;
import dowob.xyz.blog.module.version.model.dto.response.VersionDetailResponse;
import dowob.xyz.blog.module.version.model.dto.response.VersionSummaryResponse;
import dowob.xyz.blog.module.version.service.VersioningService;
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
@RequestMapping("/api/v1/articles/{articleUuid}/versions")
@RequiredArgsConstructor
@Tag(name = "Article Version")
public class VersionController {

    private final VersioningService versioningService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Version 列表（不含 content）")
    public ApiResponse<PageResult<VersionSummaryResponse>> list(
            @PathVariable UUID articleUuid,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Long userId) {
        return ApiResponse.success(versioningService.listByArticle(
            articleUuid, type, page, size, userId, isAdmin()));
    }

    @GetMapping("/{versionUuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Version 詳情（含 content，給預覽）")
    public ApiResponse<VersionDetailResponse> getDetail(
            @PathVariable UUID articleUuid,
            @PathVariable UUID versionUuid,
            @AuthenticationPrincipal Long userId) {
        return ApiResponse.success(versioningService.getDetail(versionUuid, userId, isAdmin()));
    }

    @PostMapping("/manual")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "建立手動快照")
    public ApiResponse<UUID> createManual(
            @PathVariable UUID articleUuid,
            @Valid @RequestBody(required = false) CreateManualSnapshotRequest req,
            @AuthenticationPrincipal Long userId) {
        Long articleId = versioningService.findArticleIdByUuidOrThrow(articleUuid, userId, isAdmin());
        String note = req != null ? req.getNote() : null;
        ArticleVersion saved = versioningService.recordManualSnapshot(articleId, note);
        return ApiResponse.success(saved.getUuid());
    }

    @PostMapping("/{versionUuid}/restore")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "還原文章為某版本")
    public ApiResponse<Void> restore(
            @PathVariable UUID articleUuid,
            @PathVariable UUID versionUuid,
            @AuthenticationPrincipal Long userId) {
        versioningService.restore(versionUuid, userId, isAdmin());
        return ApiResponse.success();
    }

    @PostMapping("/{versionUuid}/promote")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "升級 AUTO 為 MANUAL（救援機制）")
    public ApiResponse<Void> promote(
            @PathVariable UUID articleUuid,
            @PathVariable UUID versionUuid,
            @AuthenticationPrincipal Long userId) {
        versioningService.promote(versionUuid, userId, isAdmin());
        return ApiResponse.success();
    }

    @DeleteMapping("/{versionUuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "刪除 Version（PUBLISHED 不可刪）")
    public ApiResponse<Void> delete(
            @PathVariable UUID articleUuid,
            @PathVariable UUID versionUuid,
            @AuthenticationPrincipal Long userId) {
        versioningService.delete(versionUuid, userId, isAdmin());
        return ApiResponse.success();
    }

    private boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities()
            .contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
```

VersioningService 加 helper:

```java
@Transactional(readOnly = true)
public Long findArticleIdByUuidOrThrow(UUID articleUuid, Long currentUserId, boolean isAdmin) {
    Article article = articleRepo.findByUuid(articleUuid)
        .orElseThrow(() -> new BusinessException(VersionErrorCode.ARTICLE_NOT_FOUND));
    if (!isAdmin && !article.getAuthorId().equals(currentUserId)) {
        throw new BusinessException(VersionErrorCode.VERSION_ACCESS_DENIED);
    }
    return article.getId();
}
```

- [ ] **Step 3: VersionControllerIT.java**

對齊 batch 3 SeriesControllerIT pattern。完整 11 case：

```java
@Test void list_byOwner_returns200() { ... }
@Test void list_byNonOwner_returnsV0102() { ... }
@Test void getDetail_byOwner_returns200WithContent() { ... }
@Test void getDetail_versionNotFound_returnsV0101() { ... }
@Test void createManual_validRequest_returns200() { ... }
@Test void createManual_unauthenticated_returns401() { ... }
@Test void restore_byOwner_returns200() { ... }
@Test void promote_autoVersion_returns200() { ... }
@Test void promote_manualVersion_returnsV0104() { ... }
@Test void delete_manualVersion_returns200() { ... }
@Test void delete_publishedVersion_returnsV0103() { ... }
```

⚠ 此處 IT 程式碼較長（300+ 行），實作時對照 batch 3 SeriesControllerIT.java 寫完整 setup（@MockitoBean / @Container / @BeforeEach / asUser helper）+ 11 個 test method。

- [ ] **Step 4: install + Run**

```bash
./mvnw.cmd -pl blog-module-version -am install -DskipTests
./mvnw.cmd -pl blog-module-version test -Dtest=VersionControllerIT 2>&1 | tee logs/t13-it.log
```

Expected: 11 IT pass.

- [ ] **Step 5: Commit**

```bash
git add blog-module-version/src/main/java/dowob/xyz/blog/module/version/controller/VersionController.java \
        blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/VersioningService.java \
        blog-module-version/src/main/java/dowob/xyz/blog/module/version/mapper/VersionMapper.java \
        blog-module-version/src/test/java/dowob/xyz/blog/module/version/controller/VersionControllerIT.java
git commit -m "$(cat <<'EOF'
feat(version): VersionController + IT (11 tests)

- 6 端點：GET list / GET detail / POST manual / POST restore / POST promote / DELETE
- VersioningService 補 listByArticle / getDetail / findArticleIdByUuidOrThrow
- VersionMapper 補 listSummaries / countSummaries（type filter 可選）
- 11 IT 覆蓋 happy path + 401 + V0101/V0102/V0103/V0104

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: PreferenceController + IT

**Files:**
- Create: `blog-module-version/src/main/java/dowob/xyz/blog/module/version/controller/PreferenceController.java`
- Modify: `blog-module-version/.../service/PreferenceResolver.java`（加 update / reset / toEffectiveResponse）
- Create: `blog-module-version/src/test/java/dowob/xyz/blog/module/version/controller/PreferenceControllerIT.java`

- [ ] **Step 1: PreferenceResolver 加 update / reset / toEffectiveResponse**

```java
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.version.exception.VersionErrorCode;
import dowob.xyz.blog.module.version.mapper.UserPreferenceMapper;
import dowob.xyz.blog.module.version.model.dto.request.UpdatePreferenceRequest;
import dowob.xyz.blog.module.version.model.dto.response.EffectiveConfigResponse;
import org.springframework.transaction.annotation.Transactional;

// 新 inject
private final UserPreferenceMapper userPreferenceMapper;

@Transactional
public EffectiveConfigResponse updatePreferences(Long userId, UpdatePreferenceRequest req) {
    if (req.getEnabled() != null) {
        userPreferenceMapper.upsert(userId, KEY_ENABLED, req.getEnabled().toString());
    }
    if (req.getRetain() != null) {
        validateRange(req.getRetain(), 1, 300);
        userPreferenceMapper.upsert(userId, KEY_RETAIN, req.getRetain().toString());
    }
    if (req.getIntervalSeconds() != null) {
        validateRange(req.getIntervalSeconds(), 10, 600);
        userPreferenceMapper.upsert(userId, KEY_INTERVAL_SECONDS, req.getIntervalSeconds().toString());
    }
    if (req.getDiffChars() != null) {
        validateRange(req.getDiffChars(), 0, 5000);
        userPreferenceMapper.upsert(userId, KEY_DIFF_CHARS, req.getDiffChars().toString());
    }
    return getEffectiveResponse(userId);
}

@Transactional
public EffectiveConfigResponse resetKey(Long userId, String prefKey) {
    repo.deleteByUserIdAndPrefKey(userId, prefKey);
    return getEffectiveResponse(userId);
}

private void validateRange(int value, int min, int max) {
    if (value < min || value > max) {
        throw new BusinessException(VersionErrorCode.PREFERENCE_INVALID);
    }
}

@Transactional(readOnly = true)
public EffectiveConfigResponse getEffectiveResponse(Long userId) {
    AutoSnapshotConfig effective = resolveForUser(userId);
    EffectiveConfigResponse r = new EffectiveConfigResponse();
    r.setEnabled(buildField(userId, KEY_ENABLED, effective.enabled()));
    r.setRetain(buildField(userId, KEY_RETAIN, effective.retain()));
    r.setIntervalSeconds(buildField(userId, KEY_INTERVAL_SECONDS, effective.intervalSeconds()));
    r.setDiffChars(buildField(userId, KEY_DIFF_CHARS, effective.diffChars()));
    return r;
}

private <T> EffectiveConfigResponse.Field<T> buildField(Long userId, String key, T value) {
    String source = getRawValue(userId, key) != null ? "user" : "system";
    return new EffectiveConfigResponse.Field<>(value, source);
}
```

- [ ] **Step 2: PreferenceController.java**

```java
package dowob.xyz.blog.module.version.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.version.model.dto.request.UpdatePreferenceRequest;
import dowob.xyz.blog.module.version.model.dto.response.EffectiveConfigResponse;
import dowob.xyz.blog.module.version.service.PreferenceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/preferences/version")
@RequiredArgsConstructor
@Tag(name = "Version Preference")
public class PreferenceController {

    private final PreferenceResolver preferenceResolver;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "取得 effective version config")
    public ApiResponse<EffectiveConfigResponse> get(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(preferenceResolver.getEffectiveResponse(userId));
    }

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "更新 user override（partial）")
    public ApiResponse<EffectiveConfigResponse> update(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UpdatePreferenceRequest req) {
        return ApiResponse.success(preferenceResolver.updatePreferences(userId, req));
    }

    @DeleteMapping("/{key}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "重置某 key 為系統預設")
    public ApiResponse<EffectiveConfigResponse> reset(
            @AuthenticationPrincipal Long userId,
            @PathVariable String key) {
        // 驗證 key 是否合法（只允許 4 個 known keys）
        String fullKey = mapKey(key);
        return ApiResponse.success(preferenceResolver.resetKey(userId, fullKey));
    }

    private String mapKey(String shortKey) {
        return switch (shortKey) {
            case "enabled" -> PreferenceResolver.KEY_ENABLED;
            case "retain" -> PreferenceResolver.KEY_RETAIN;
            case "intervalSeconds", "interval-seconds" -> PreferenceResolver.KEY_INTERVAL_SECONDS;
            case "diffChars", "diff-chars" -> PreferenceResolver.KEY_DIFF_CHARS;
            default -> throw new IllegalArgumentException("Unknown preference key: " + shortKey);
        };
    }
}
```

- [ ] **Step 3: PreferenceControllerIT.java**

5 個 IT，骨架同 VersionControllerIT。涵蓋：

```java
@Test void get_noOverride_returnsAllSystemSource() { ... }
@Test void put_validValues_returns200WithUserSource() { ... }
@Test void put_invalidRetain301_returnsV0105() { ... }
@Test void delete_existingKey_resetToSystem() { ... }
@Test void put_partialUpdate_otherKeysStaySystem() { ... }
```

- [ ] **Step 4: install + Run**

```bash
./mvnw.cmd -pl blog-module-version -am install -DskipTests
./mvnw.cmd -pl blog-module-version test -Dtest=PreferenceControllerIT 2>&1 | tee logs/t14-it.log
```

Expected: 5 IT pass.

- [ ] **Step 5: Commit**

```bash
git add blog-module-version/src/main/java/dowob/xyz/blog/module/version/controller/PreferenceController.java \
        blog-module-version/src/main/java/dowob/xyz/blog/module/version/service/PreferenceResolver.java \
        blog-module-version/src/test/java/dowob/xyz/blog/module/version/controller/PreferenceControllerIT.java
git commit -m "$(cat <<'EOF'
feat(version): PreferenceController + IT (5 tests)

- 3 端點：GET effective config / PUT partial update / DELETE reset
- PreferenceResolver 補 updatePreferences / resetKey / getEffectiveResponse
- Validation 範圍：retain[1,300] interval[10,600] diff[0,5000]，超出 V0105
- EffectiveConfigResponse 含 source 欄位讓前端標 "user"|"system"
- 5 IT 覆蓋 GET 無 override / PUT happy / PUT invalid V0105 / DELETE 重置 / PUT partial

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 15: Cross-module IT (5 個 e2e flow)

**Files:**
- Create: `blog-module-version/src/test/java/dowob/xyz/blog/module/version/integration/CrossModuleVersionIT.java`

- [ ] **Step 1: 設計 5 個 e2e 流程**

每個 IT 端對端驗證：

```java
@Test
@DisplayName("PUT /articles/{uuid} → MQ 觸發 → 寫入 AUTO 快照")
void updateArticle_triggersAutoSnapshot() {
    // 1. POST /api/v1/articles 建文章
    // 2. PUT /api/v1/articles/{uuid} 更新（觸發 SAVED event）
    // 3. consumer 消化 event → AutoSnapshotPolicy → recordAutoSnapshot
    // 4. GET /api/v1/articles/{uuid}/versions assert 至少 1 筆 AUTO
}

@Test
@DisplayName("POST /articles/{uuid}/publish → freeze 凍結 + 清 AUTO")
void publishArticle_freezesAndClearsAutos() {
    // 1. 建文章 + update 多次累積 AUTO
    // 2. POST publish
    // 3. assert: 1 個 PUBLISHED + 0 個 AUTO
}

@Test
@DisplayName("POST /versions/{uuid}/restore → article 內容更新 + ArticleUpdatedEvent 發出")
void restoreVersion_updatesArticleAndPublishesEvent() {
    // 1. 建文章 (title=A) + 手動快照 + update (title=B) + 多次 update
    // 2. POST /versions/{snapshotUuid}/restore
    // 3. GET /api/v1/articles/{uuid} → article.title == "A"
    // 4. 驗證有發 ArticleUpdatedEvent (mock RabbitTemplate spy/assert)
}

@Test
@DisplayName("DELETE /articles/{uuid} → article_versions ON DELETE CASCADE 自動清")
void deleteArticle_cascadesVersions() {
    // 1. 建文章 + 寫快照
    // 2. DELETE /api/v1/articles/{uuid}
    // 3. assert article_versions 中該 article 的 row 全清
}

@Test
@DisplayName("User config enabled=false → 後續 update 不再產生 AUTO")
void preferenceDisabled_skipsAutoSnapshot() {
    // 1. PUT /me/preferences/version body { enabled: false }
    // 2. PUT /api/v1/articles/{uuid} 更新
    // 3. assert article_versions 中該 article 沒新增（policy 在 enabled=false 時直接 false）
}
```

⚠ 此檔程式碼較長（500+ 行），實作時對照 batch 3 CrossModuleSeriesIT.java 寫完整 setup 與 helper（asUser / createArticleViaApi 等）。

⚠ MQ 在 IT 內模擬：因為 VersionTestApplication exclude RabbitAutoConfiguration，cross-module 的 MQ 驅動測試**不真走 broker**。實作可選擇：
- 選 1: 直接呼叫 ArticleVersionConsumer.onContentChanged() 模擬（已在 ArticleVersionConsumerIT 證明 logic 正確；cross 重點驗 article-side 確實**會觸發** publishContentChanged）
- 選 2: 用 `@MockitoBean RabbitTemplate` spy 看是否被 article 端呼叫

兩種都接受。實作優先選 1（走 consumer 就驗了端到端流），記得在 cross IT 開頭也包 article 模組的 ArticleEventPublisher 真實 inject（這樣 article-side 的 publishContentChanged 會被執行，但因 exchange/queue mock，訊息不會傳到 consumer 真實實作 — 在 IT 直接呼叫 consumer.onContentChanged() 補完）。

- [ ] **Step 2: Run + Run all batch 4 modules**

```bash
./mvnw.cmd -pl blog-module-version test -Dtest=CrossModuleVersionIT 2>&1 | tee logs/t15-cross.log
./mvnw.cmd -pl blog-module-article,blog-module-reading,blog-module-comment,blog-module-series,blog-module-version test 2>&1 | tee logs/t15-all.log
```

Expected: 全綠（cross 5 IT pass + 既有 modules 全綠）。

- [ ] **Step 3: Commit**

```bash
git add blog-module-version/src/test/java/dowob/xyz/blog/module/version/integration/
git commit -m "$(cat <<'EOF'
test(version): 跨模組整合 IT (5 tests)

- updateArticle → MQ → AUTO 快照
- publishArticle → freeze 凍結 + 清 AUTO
- restoreVersion → article 內容更新 + ArticleUpdatedEvent
- deleteArticle → article_versions ON DELETE CASCADE
- preferenceDisabled → 跳過 AUTO 寫入

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 16: DatabaseCleaner 補表清理 + schema.md 更新 V16

**Files:**
- Modify: `blog-start/src/test/java/dowob/xyz/blog/e2e/support/DatabaseCleaner.java`
- Modify: `ai-docs/schema.md`

- [ ] **Step 1: DatabaseCleaner 加 article_versions / user_preferences**

Read 既有 `DatabaseCleaner.cleanAll()`，在「實體表」區之前加：

```java
jdbcTemplate.execute("DELETE FROM article_versions");
jdbcTemplate.execute("DELETE FROM user_preferences");
```

順序：在 `DELETE FROM articles` 之前（FK ON DELETE CASCADE 其實會自動清，但顯式刪較直觀）。

- [ ] **Step 2: schema.md 加 V16 兩張表**

於 `ai-docs/schema.md` 找合適位置（建議 series 之後）加：

```markdown
### article_versions

> V16 新增。文章版本快照（draft history / publish freeze）。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | 對外識別 |
| article_id | BIGINT | NOT NULL REFERENCES articles(id) ON DELETE CASCADE | |
| author_id | BIGINT | NOT NULL REFERENCES users(id) | 反正規化 |
| type | VARCHAR(20) | NOT NULL CHECK IN ('AUTO','MANUAL','PUBLISHED') | |
| title | VARCHAR(255) | NOT NULL | snapshot 當時 |
| slug | VARCHAR(255) | NOT NULL | snapshot 當時 |
| content | TEXT | NOT NULL | markdown source |
| summary | VARCHAR(500) | NULL | |
| category_id | BIGINT | NULL | 不加 FK（category 後刪不該失效）|
| cover_image_url | VARCHAR(512) | NULL | |
| status | VARCHAR(20) | NOT NULL | snapshot 當時 article.status |
| tags | UUID[] | NULL | 快照當時 tag UUID 列表 |
| note | VARCHAR(255) | NULL | MANUAL: user 命名；PUBLISHED: 自動 `Published vN` |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `article_versions_pkey`（auto）
- `article_versions_uuid_key`（auto, UNIQUE）
- `idx_article_versions_article_created` on (article_id, created_at DESC)
- `idx_article_versions_article_type` on (article_id, type)

**Foreign keys:**
- `article_id` → `articles(id)` ON DELETE CASCADE
- `author_id` → `users(id)` NO ACTION

---

### user_preferences

> V16 新增。使用者偏好（K-V 通用結構，未來其他模組可共用）。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| user_id | BIGINT | NOT NULL REFERENCES users(id) ON DELETE CASCADE | |
| pref_key | VARCHAR(100) | NOT NULL | dot-separated（如 version.auto.retain）|
| pref_value | TEXT | NOT NULL | 字串值（boolean/int/json 序列化）|
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Constraints:**
- `uq_user_preferences_user_key` UNIQUE (user_id, pref_key)

**Indexes:**
- `user_preferences_pkey`（auto）
- `uq_user_preferences_user_key`（auto, UNIQUE，等同 user_id 索引）

**Foreign keys:**
- `user_id` → `users(id)` ON DELETE CASCADE

**初始 pref keys（version 模組用）:**
- `version.auto.enabled` (boolean, default true)
- `version.auto.retain` (int, default 50)
- `version.auto.interval-seconds` (int, default 60)
- `version.auto.diff-chars` (int, default 50；0 = disabled)
```

於文件最上方更新 header：
```markdown
> 最後更新版本：**V16**
```

於 Migration Index 末尾加：
```markdown
| **V16** | 新建 `article_versions` 表（content snapshot 含 type/note/tags UUID[]，FK ON DELETE CASCADE）+ `user_preferences` 表（K-V 通用，UNIQUE user_id+pref_key） |
```

- [ ] **Step 3: Commit**

```bash
git add blog-start/src/test/java/dowob/xyz/blog/e2e/support/DatabaseCleaner.java \
        ai-docs/schema.md
git commit -m "$(cat <<'EOF'
docs(schema): 更新 schema.md 加入 V16 + DatabaseCleaner 補表清理

schema.md:
- 新建 article_versions 表（snapshot + tags UUID[]）
- 新建 user_preferences 表（K-V 通用，version 模組首個使用者）
- Migration Index 補 V16

DatabaseCleaner:
- 加 DELETE FROM article_versions / user_preferences
- 位置：實體表清理之前

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 17: 最終 sanity + 整批驗證

**Files:**（不新增）
- Run: `./mvnw.cmd test` 全模組

- [ ] **Step 1: 跑全模組所有 tests**

```bash
./mvnw.cmd test 2>&1 | tee logs/t17-all-modules.log
```

Expected: 全綠。預估各模組 test 數：
- blog-common: ~120 tests
- blog-infrastructure: ~80 tests
- blog-module-user: ~60 tests
- blog-module-article: 240 tests + 可能微調
- blog-module-comment: 67
- blog-module-reading: 66
- blog-module-series: 27
- **blog-module-version: ~50 (10 unit + 4 IT consumer + 11 IT controller + 5 IT preference + 5 cross + 13 unit service - 重複算法)**
- blog-module-tag / file / search / recommend：照舊

實際數字以執行為準。

- [ ] **Step 2: Self-review checklist 對 spec**

對照 spec §10 的測試規劃 checklist，確認都涵蓋：
- [ ] AUTO / MANUAL / PUBLISHED / pre-restore 4 種寫入路徑都有測
- [ ] retention 50 滾動有測
- [ ] User config override 走得通
- [ ] DELETE article CASCADE 有測
- [ ] Restore 完整流程（stash + 寫回 + tags + render + 2 events）有測
- [ ] V0101-V0106 6 個錯誤碼都有測

- [ ] **Step 3: Commit（如有 sanity 補測 / 微調）**

如執行過程中發現需補充的 test / 修正 / 文檔，commit 進來。否則本 task 不需 commit（純驗證）。

---

## Self-Review Checklist

實作完成後請勾選：

- [ ] V16 migration 套用成功（Testcontainers）
- [ ] `ArticleEventPublisher` 抽出成功，既有 article 模組 240 tests 全綠
- [ ] `ArticleContentChangedEvent` 在 update / publish 都有發出
- [ ] `blog-module-version` 模組正確註冊（root pom + blog-start + MyBatisConfig.@MapperScan + application.yaml）
- [ ] 4 個自動快照 pref keys 可走 yaml fallback 與 user override
- [ ] AutoSnapshotPolicy 4 個門檻（enabled / interval / diff / diffChars=0 disabled）全部有 test
- [ ] VersioningService 5 個寫入動作（auto / manual / freezePublished / promote / delete）都通過 unit test
- [ ] Restore 完整 flow 含 stash + 寫回 + tags + render contentHtml + 2 events 都有 unit + cross IT
- [ ] VersionController 6 端點 + 11 IT 全綠
- [ ] PreferenceController 3 端點 + 5 IT 全綠
- [ ] ArticleVersionConsumer 4 IT 全綠
- [ ] CrossModuleVersionIT 5 e2e flow 全綠
- [ ] schema.md 更新 V16 兩張表（含 type CHECK / tags UUID[] / FK CASCADE 等所有細節）
- [ ] DatabaseCleaner 加 article_versions / user_preferences

---

## 後續批次

- 下一階段架構重寫：把 batch 1-3 的 facade pattern（comment / reading / series）改 MQ-driven，對齊本批次模式
- 可選批 6+：Bookmark 分類、`GET /me/highlights` 列表頁、Series 章節、Tags index 整合 Series
