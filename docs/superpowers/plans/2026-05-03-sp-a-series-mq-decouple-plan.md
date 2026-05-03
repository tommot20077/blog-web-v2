# SP-A: Series MQ Decouple Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 解掉 SeriesFacade.notifyArticleDeletedFromSeries 同步通知（改 ArticleDeletedEvent 訂閱模式），擴充 event 為 rich payload，加 V17 processed_events 表 + IdempotencyService 提供 MQ consumer 通用冪等基礎。

**Architecture:** Article 模組對 Series 完全不知情（只發 ArticleDeletedEvent），Series 模組透過 `SeriesArticleDeletedConsumer @RabbitListener` 訂閱事件並用 IdempotencyService 對 (eventId, consumerName) 做 dedup 確保冪等。重送會被 skip 不會多次扣 series.article_count。

**Tech Stack:** Spring Boot, Spring Data JDBC, MyBatis, PostgreSQL（V17 migration）, RabbitMQ, Flyway, JUnit 5, Mockito, Spring Security Test, Testcontainers (PostgreSQL + Redis).

**Spec:** `docs/superpowers/specs/2026-05-03-sp-a-series-mq-decouple-design.md`

---

## File Map

### 新增檔案

```
blog-db-migration/
└─ src/main/resources/db/migration/V17__add_processed_events.sql        NEW

blog-infrastructure/
└─ src/main/java/dowob/xyz/blog/infrastructure/idempotency/
   ├─ IdempotencyService.java                                            NEW
   ├─ model/ProcessedEvent.java                                          NEW (entity)
   └─ repository/ProcessedEventRepository.java                           NEW
└─ src/test/java/dowob/xyz/blog/infrastructure/idempotency/
   └─ IdempotencyServiceTest.java                                        NEW (3 tests)

blog-module-series/
├─ src/main/java/dowob/xyz/blog/module/series/
│  ├─ config/SeriesRabbitMqConfig.java                                   NEW
│  └─ consumer/SeriesArticleDeletedConsumer.java                         NEW
└─ src/test/java/dowob/xyz/blog/module/series/
   └─ consumer/SeriesArticleDeletedConsumerTest.java                     NEW (4 unit tests)

blog-module-version/   (cross-module IT 在 SP-A 範圍 — 在 series 模組寫)
└─ (不會動)
```

### 修改檔案

```
blog-module-article/
├─ src/main/java/dowob/xyz/blog/module/article/
│  ├─ event/ArticleDeletedEvent.java                                     MODIFY (擴充 record)
│  ├─ mapper/ArticleMapper.java                                          MODIFY (加 2 query)
│  ├─ service/ArticleEventPublisher.java                                 MODIFY (publishDeleted 改簽名)
│  └─ service/ArticleServiceImpl.java                                    MODIFY (deleteArticle 改寫 + 移除 SeriesFacade inject)
└─ src/test/java/dowob/xyz/blog/module/article/service/
   ├─ ArticleEventPublisherTest.java                                     MODIFY (publishDeleted test 加 1)
   └─ ArticleServiceTest.java                                            MODIFY (deleteArticle test 改)

blog-module-series/
├─ src/main/java/dowob/xyz/blog/module/series/facade/SeriesFacadeImpl.java  MODIFY (移除 notifyArticleDeletedFromSeries)
└─ src/test/java/dowob/xyz/blog/module/series/integration/
   └─ CrossModuleSeriesIT.java                                           MODIFY (升級 IT + 加重送冪等驗證)

blog-infrastructure/
└─ src/main/java/dowob/xyz/blog/infrastructure/facade/SeriesFacade.java  MODIFY (interface 移除 notify method)

blog-start/
└─ src/test/java/dowob/xyz/blog/e2e/support/DatabaseCleaner.java         MODIFY (補 processed_events 清理)

ai-docs/schema.md                                                        MODIFY (V17 + processed_events 表)
```

---

## Pre-Flight Notes

1. **Worktree**：`.worktrees/refactor-sp-a-series-mq/`，base 在 batch 4 HEAD（含 roadmap commit `bb4d418`）
2. **Maven**：`./mvnw.cmd`（Windows wrapper）
3. **測試輸出**：`./mvnw.cmd test ... 2>&1 | tee logs/<task>.log`
4. **Surefire 報告**：失敗時讀 `<module>/target/surefire-reports/TEST-*.xml`
5. **Commit 慣例**：Conventional Commits + 繁中描述 + Co-Authored-By 行
6. **既有 patterns 對齊：**
   - Spring Data JDBC entity 用 `@Table` + `@Column` + `@CreatedDate`/`@LastModifiedDate`
   - UUID 必設 `setUuid(UUID.randomUUID())`（但 ProcessedEvent 不需要 uuid，event_id 由 caller 傳入）
   - Backward compat：Spring AMQP Jackson 對 record 缺欄位 deserialize 為 null
   - **MyBatisConfig.@MapperScan 已含 reading / series / version 等 mapper package — 本 SP 不新增 mapper package，跳過此檢查**
7. **既有 ArticleDeletedEvent 影響範圍：** 只 `ArticleSearchListener` 訂閱，且只用 `articleUuid` 欄位 — payload 擴充不會 break
8. **重要：MyBatisConfig / SpringBootApplication scan 不需改** — `blog-infrastructure` 已被所有模組透過 `@SpringBootApplication(scanBasePackages = {..., "dowob.xyz.blog.infrastructure"})` 掃過。新增 `idempotency` 子 package 自動被掃。
9. **IdempotencyService 寫在 infrastructure 後，要確認 `EnableJdbcRepositories` scan 含 `dowob.xyz.blog.infrastructure.idempotency.repository`** — 沒有的話要加。看各 TestApplication.java 對應加（這是潛在踩坑）。

---

## Task 1: V17 Migration + schema.md

**Files:**
- Create: `blog-db-migration/src/main/resources/db/migration/V17__add_processed_events.sql`
- Modify: `ai-docs/schema.md`

- [ ] **Step 1: Write V17 SQL**

```sql
-- V17__add_processed_events.sql
-- 目的：MQ event 冪等記錄表，所有 consumer 共用 IdempotencyService 對此表做 dedup。

CREATE TABLE processed_events (
    id            BIGSERIAL    PRIMARY KEY,
    event_id      UUID         NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    processed_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_processed_events_event_consumer UNIQUE (event_id, consumer_name)
);

CREATE INDEX idx_processed_events_processed_at
    ON processed_events(processed_at);
```

⚠ 編碼 UTF-8 LF。用 Write tool 直接寫，不要用 echo / cat。

- [ ] **Step 2: 更新 schema.md**

Read `ai-docs/schema.md`，於 Migration Index 之前 / `user_preferences` 之後加：

```markdown
### processed_events

> V17 新增。MQ event 冪等記錄表。所有 consumer 透過 `IdempotencyService` 對 (event_id, consumer_name) UNIQUE 做 dedup，避免重送導致重複扣分。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| event_id | UUID | NOT NULL | event 的 dedup key（producer 每次 publish random gen）|
| consumer_name | VARCHAR(100) | NOT NULL | 哪個 consumer 處理過 |
| processed_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Constraints:**
- `uq_processed_events_event_consumer` UNIQUE (event_id, consumer_name) — 一個 event 多 consumer 訂閱時各自獨立冪等

**Indexes:**
- `processed_events_pkey`（auto）
- `uq_processed_events_event_consumer`（auto, UNIQUE）
- `idx_processed_events_processed_at` on (processed_at) — 給未來 cleanup 用

**Foreign keys:** 無
```

於 Migration Index 末尾加：
```markdown
| **V17** | 新建 `processed_events` 表（MQ event 冪等記錄）|
```

於 header header 更新：`> 最後更新版本：**V17**`

- [ ] **Step 3: 編譯驗證**

```bash
./mvnw.cmd -pl blog-db-migration -am compile 2>&1 | tee logs/t1-v17-compile.log
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add blog-db-migration/src/main/resources/db/migration/V17__add_processed_events.sql \
        ai-docs/schema.md
git commit -m "$(cat <<'EOF'
feat(infra): V17 migration — processed_events 表

- 新建 processed_events 表（MQ event 冪等記錄）
- UNIQUE (event_id, consumer_name) — 一個 event 多 consumer 各自獨立冪等
- 所有 MQ consumer 將透過 IdempotencyService 對此表做 dedup
- schema.md 同步更新 V17

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: IdempotencyService + ProcessedEvent entity / Repository (TDD)

**Files:**
- Create: `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/idempotency/model/ProcessedEvent.java`
- Create: `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/idempotency/repository/ProcessedEventRepository.java`
- Create: `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/idempotency/IdempotencyService.java`
- Create: `blog-infrastructure/src/test/java/dowob/xyz/blog/infrastructure/idempotency/IdempotencyServiceTest.java`

- [ ] **Step 1: ProcessedEvent.java entity**

```java
package dowob.xyz.blog.infrastructure.idempotency.model;

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
 * MQ event 冪等記錄（processed_events 表映射）。
 *
 * <p>UNIQUE (event_id, consumer_name) — 一個 event 多 consumer 訂閱時各自獨立冪等。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("processed_events")
public class ProcessedEvent {
    @Id
    private Long id;

    @Column("event_id")
    private UUID eventId;

    @Column("consumer_name")
    private String consumerName;

    @CreatedDate
    @Column("processed_at")
    private LocalDateTime processedAt;
}
```

- [ ] **Step 2: ProcessedEventRepository.java**

```java
package dowob.xyz.blog.infrastructure.idempotency.repository;

import dowob.xyz.blog.infrastructure.idempotency.model.ProcessedEvent;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Processed Event Repository — 提供 save 跟 UNIQUE 衝突 detect。
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface ProcessedEventRepository extends CrudRepository<ProcessedEvent, Long> {
}
```

- [ ] **Step 3: IdempotencyServiceTest.java（TDD Red 先寫 3 tests）**

```java
package dowob.xyz.blog.infrastructure.idempotency;

import dowob.xyz.blog.infrastructure.idempotency.model.ProcessedEvent;
import dowob.xyz.blog.infrastructure.idempotency.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock private ProcessedEventRepository repo;
    @InjectMocks private IdempotencyService service;

    @Test
    void markProcessed_firstCall_returnsTrue() {
        UUID eventId = UUID.randomUUID();
        when(repo.save(any(ProcessedEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean result = service.markProcessed(eventId, "test.consumer");

        assertThat(result).isTrue();
    }

    @Test
    void markProcessed_uniqueConflict_returnsFalse() {
        UUID eventId = UUID.randomUUID();
        when(repo.save(any(ProcessedEvent.class)))
            .thenThrow(new DataIntegrityViolationException("uq_processed_events_event_consumer"));

        boolean result = service.markProcessed(eventId, "test.consumer");

        assertThat(result).isFalse();
    }

    @Test
    void markProcessed_differentConsumers_independent() {
        UUID eventId = UUID.randomUUID();
        // consumer A 先處理（save 成功）
        when(repo.save(any(ProcessedEvent.class)))
            .thenAnswer(inv -> inv.getArgument(0))
            .thenAnswer(inv -> inv.getArgument(0));  // 第二次 consumer B 也成功

        boolean a = service.markProcessed(eventId, "consumer.a");
        boolean b = service.markProcessed(eventId, "consumer.b");

        assertThat(a).isTrue();
        assertThat(b).isTrue();
    }
}
```

- [ ] **Step 4: Run RED**

```bash
./mvnw.cmd -pl blog-infrastructure -am test -Dtest=IdempotencyServiceTest 2>&1 | tee logs/t2-red.log
```

Expected: 編譯失敗（IdempotencyService 不存在）。

- [ ] **Step 5: IdempotencyService.java（GREEN 最小實作）**

```java
package dowob.xyz.blog.infrastructure.idempotency;

import dowob.xyz.blog.infrastructure.idempotency.model.ProcessedEvent;
import dowob.xyz.blog.infrastructure.idempotency.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MQ event 冪等處理服務。
 *
 * <p>用法（在 consumer 中）:
 * <pre>{@code
 * @RabbitListener(queues = "...")
 * public void onEvent(SomeEvent event) {
 *     if (!idempotencyService.markProcessed(event.eventId(), CONSUMER_NAME)) {
 *         return; // 已處理過，skip
 *     }
 *     // 真正的業務邏輯
 * }
 * }</pre></p>
 *
 * <p>Trade-off：用 {@code REQUIRES_NEW} 確保 caller transaction rollback 不會連帶
 * rollback 此記錄。寧可漏處理一次也不重複扣（如 series.article_count 寧可少扣不多扣）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final ProcessedEventRepository repo;

    /**
     * 標記 event 已被某 consumer 處理。
     *
     * <p>用 UNIQUE constraint (event_id, consumer_name) 兜底：</p>
     * <ul>
     *   <li>第一次處理 → INSERT 成功 → return true（caller 繼續業務）</li>
     *   <li>重送處理 → INSERT 衝突 → return false（caller skip）</li>
     * </ul>
     *
     * @param eventId      event 的 dedup key
     * @param consumerName 哪個 consumer 處理（用於同 event 多 consumer 各自冪等）
     * @return true = 第一次處理；false = 已處理過要 skip
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markProcessed(UUID eventId, String consumerName) {
        try {
            ProcessedEvent pe = new ProcessedEvent(null, eventId, consumerName, LocalDateTime.now());
            repo.save(pe);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.debug("Event {} 已被 consumer {} 處理過，skip", eventId, consumerName);
            return false;
        }
    }
}
```

- [ ] **Step 6: Run GREEN**

```bash
./mvnw.cmd -pl blog-infrastructure -am test -Dtest=IdempotencyServiceTest 2>&1 | tee logs/t2-green.log
```

Expected: 3 tests pass。

- [ ] **Step 7: 確認 SpringBoot scan 與 EnableJdbcRepositories**

確認 `blog-infrastructure` 既有 SpringBootApplication 配置。檢查所有 TestApplication 是否會掃 `dowob.xyz.blog.infrastructure.idempotency` package：

```bash
grep -rE "scanBasePackages|EnableJdbcRepositories" blog-module-*/src/test/java/ 2>&1 | head -20
```

Read 既有 TestApplication（如 SeriesTestApplication, VersionTestApplication）— 應該已經掃 `"dowob.xyz.blog.infrastructure"`，子 package `idempotency` 自動被掃。但 `EnableJdbcRepositories` 可能僅指定特定 module package，**需要加 `dowob.xyz.blog.infrastructure.idempotency.repository`**。

對應修改各 TestApplication（SeriesTestApplication / VersionTestApplication / 其他）：

```java
@EnableJdbcRepositories(basePackages = {
    "dowob.xyz.blog.module.article.repository",
    "dowob.xyz.blog.module.series.repository",
    "dowob.xyz.blog.infrastructure.idempotency.repository"   // ← 新加
})
```

⚠ 跑 IT 時若報 `ProcessedEventRepository` not found → 補對應 TestApplication。

⚠ 對齊 main blog-start application — 確認 main `BlogApplication` 的 `@EnableJdbcRepositories` 也含。

- [ ] **Step 8: Commit**

```bash
git add blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/idempotency/ \
        blog-infrastructure/src/test/java/dowob/xyz/blog/infrastructure/idempotency/
git commit -m "$(cat <<'EOF'
feat(infra): IdempotencyService + ProcessedEvent entity / Repository (TDD)

- ProcessedEvent entity 對齊 Spring Data JDBC（@CreatedDate / @Column）
- IdempotencyService.markProcessed: REQUIRES_NEW transaction propagation
  確保 caller transaction rollback 不會連帶 rollback dedup 記錄
- 所有 MQ consumer 透過此 service 做冪等處理
- 3 unit tests（first call true / conflict false / different consumer 互不影響）

⚠ 各 TestApplication 的 @EnableJdbcRepositories 可能要加
   dowob.xyz.blog.infrastructure.idempotency.repository（若 IT 報 bean not found）

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: ArticleMapper 加 findTagUuidsByArticleId / findCategoryUuidsByArticleId

**Files:**
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/mapper/ArticleMapper.java`

- [ ] **Step 1: 確認既有 schema 對齊**

讀 `ai-docs/schema.md` 找 `article_tags` 表跟 `article_categories` 表結構：
- `article_tags`：有 `article_id`、`tag_id` 兩欄（V9 改 UUID 還是 BIGINT 看 schema）
- `article_categories`：是否存在？看 `blog-db-migration/src/main/resources/db/migration/V6__add_categories.sql` 或對應 migration

⚠ 如果 `article_categories` 不存在（categories 用 article.category_id 單值），改成 `findCategoryUuidByArticleId(articleId): Optional<UUID>`。
⚠ 如果 `article_tags.article_id` / `tag_id` 是 UUID（V9 後）而非 BIGINT，SQL 對應調整。

- [ ] **Step 2: 加 2 個 query 到 ArticleMapper**

於既有 `ArticleMapper.java` 適當位置加：

```java
import java.util.List;
import java.util.UUID;

/**
 * 撈文章對應的 tag UUID 列表（給 ArticleDeletedEvent rich payload 用）。
 *
 * @param articleId 文章主鍵
 * @return tag UUID 列表（無 tag 回 emptyList）
 */
@Select("""
        SELECT t.uuid FROM article_tags at
          JOIN tags t ON at.tag_id = t.id
         WHERE at.article_id = #{articleId}
        """)
List<UUID> findTagUuidsByArticleId(@Param("articleId") Long articleId);

/**
 * 撈文章對應的 category UUID 列表（給 ArticleDeletedEvent rich payload 用）。
 *
 * @param articleId 文章主鍵
 * @return category UUID 列表（無 category 回 emptyList）
 */
@Select("""
        SELECT c.uuid FROM article_categories ac
          JOIN categories c ON ac.category_id = c.id
         WHERE ac.article_id = #{articleId}
        """)
List<UUID> findCategoryUuidsByArticleId(@Param("articleId") Long articleId);
```

⚠ 如果 `article_tags.article_id` 是 UUID（依 V9 schema）而非 BIGINT，改成 `WHERE at.article_id = #{articleUuid}` 並讓參數改 UUID 型別。實作時先 Read schema 再決定。

⚠ 如果 article_categories 不存在（categories 用 article.category_id 單值），改成：
```java
@Select("SELECT c.uuid FROM categories c WHERE c.id = (SELECT category_id FROM articles WHERE id = #{articleId})")
Optional<UUID> findCategoryUuidByArticleId(@Param("articleId") Long articleId);
```
並後續 task ArticleEventPublisher.publishDeleted 接受 `List<UUID> categoryIds` 仍是 list（單個 category 包成 list of size 0 or 1）。

- [ ] **Step 3: 編譯驗證**

```bash
./mvnw.cmd -pl blog-module-article -am compile 2>&1 | tee logs/t3-article-compile.log
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/mapper/ArticleMapper.java
git commit -m "$(cat <<'EOF'
feat(article): ArticleMapper 加 findTagUuidsByArticleId / findCategoryUuidsByArticleId

- 給 ArticleDeletedEvent rich payload 用（後續 task 擴 event）
- JOIN tags / categories 表轉 UUID 列表
- 文章被刪前讀取，因刪除後 FK CASCADE 會清 article_tags / article_categories 撈不到

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: ArticleDeletedEvent payload 擴充 + ArticleEventPublisher.publishDeleted 改簽名

**Files:**
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/event/ArticleDeletedEvent.java`
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleEventPublisher.java`
- Modify: `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleEventPublisherTest.java`（如已有）or `ArticleServiceTest.java`（看既有測試在哪）

- [ ] **Step 1: 改 ArticleDeletedEvent record**

完全覆蓋舊 record:

```java
package dowob.xyz.blog.module.article.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文章刪除事件（rich payload — 文章已刪，consumer 撈不到 entity，必須 event 帶足夠資訊）。
 *
 * <p>Backward compatibility: Spring AMQP + Jackson 對缺欄位 deserialize 為 null，
 * 升級時 in-flight 舊 message（只 2 欄位）仍可處理；新 consumer 對 null 做防禦判斷。</p>
 *
 * @param eventId      事件 dedup key（producer 每次 publish 時 random gen）
 * @param articleId    文章資料庫主鍵
 * @param articleUuid  文章公開 UUID
 * @param authorId     作者資料庫主鍵
 * @param seriesId     文章所屬 series 主鍵（nullable — 文章不在 series 時為 null）
 * @param categoryIds  文章被刪前的 category UUIDs（nullable — 舊 message 為 null）
 * @param tagIds       文章被刪前的 tag UUIDs（nullable — 舊 message 為 null）
 * @param occurredAt   事件發生時間
 *
 * @author Yuan
 * @version 2.0
 */
public record ArticleDeletedEvent(
    UUID eventId,
    Long articleId,
    UUID articleUuid,
    Long authorId,
    Long seriesId,
    List<UUID> categoryIds,
    List<UUID> tagIds,
    Instant occurredAt
) {}
```

- [ ] **Step 2: 改 ArticleEventPublisher.publishDeleted 簽名**

Read `ArticleEventPublisher.java` 找 `publishDeleted` method，改簽名：

```java
import java.util.List;
import java.util.UUID;

/**
 * 發 ArticleDeletedEvent — rich payload，因文章已刪 consumer 撈不到 entity。
 *
 * @param article      文章 entity（提供 id / uuid / authorId）
 * @param seriesId     文章所屬 series id（nullable）
 * @param categoryIds  文章 categories（caller 在 delete 前讀取；空 list 不可 null）
 * @param tagIds       文章 tags（caller 在 delete 前讀取；空 list 不可 null）
 */
public void publishDeleted(Article article, Long seriesId,
                          List<UUID> categoryIds, List<UUID> tagIds) {
    try {
        ArticleDeletedEvent event = new ArticleDeletedEvent(
            UUID.randomUUID(),
            article.getId(),
            article.getUuid(),
            article.getAuthorId(),
            seriesId,
            categoryIds != null ? categoryIds : List.of(),
            tagIds != null ? tagIds : List.of(),
            Instant.now()
        );
        rabbitTemplate.convertAndSend(
            ArticleRabbitMqConfig.EXCHANGE,
            ArticleRabbitMqConfig.ROUTING_KEY_DELETED,
            event
        );
    } catch (Exception e) {
        log.warn("ArticleDeletedEvent 發送失敗（best-effort）: {}", e.getMessage(), e);
    }
}
```

⚠ 移除既有 `publishDeleted(Article article)` 簽名 — 改新簽名取代。

- [ ] **Step 3: 修對應 test（既有 ArticleEventPublisherTest 或 ArticleServiceTest）**

Grep 找哪些 test 用 `publishDeleted`：

```bash
grep -rn "publishDeleted" blog-module-article/src/test/java/
```

對每個 caller test 改成新簽名 — 例如：

```java
verify(articleEventPublisher).publishDeleted(any(), eq(seriesId), eq(categoryIds), eq(tagIds));
```

或用 `any()` 寬鬆驗：

```java
verify(articleEventPublisher).publishDeleted(any(), any(), any(), any());
```

加 1 個新 unit test 驗 publishDeleted 構造 event 正確：

```java
@Test
void publishDeleted_richPayload_constructsEventCorrectly() {
    Article article = new Article();
    article.setId(100L);
    article.setUuid(UUID.randomUUID());
    article.setAuthorId(1L);

    UUID tagUuid = UUID.randomUUID();
    UUID categoryUuid = UUID.randomUUID();

    publisher.publishDeleted(article, 200L, List.of(categoryUuid), List.of(tagUuid));

    ArgumentCaptor<ArticleDeletedEvent> captor = ArgumentCaptor.forClass(ArticleDeletedEvent.class);
    verify(rabbitTemplate).convertAndSend(
        eq(ArticleRabbitMqConfig.EXCHANGE),
        eq(ArticleRabbitMqConfig.ROUTING_KEY_DELETED),
        captor.capture()
    );
    ArticleDeletedEvent event = captor.getValue();
    assertThat(event.eventId()).isNotNull();
    assertThat(event.articleId()).isEqualTo(100L);
    assertThat(event.authorId()).isEqualTo(1L);
    assertThat(event.seriesId()).isEqualTo(200L);
    assertThat(event.categoryIds()).containsExactly(categoryUuid);
    assertThat(event.tagIds()).containsExactly(tagUuid);
}
```

- [ ] **Step 4: 編譯（會發現 ArticleServiceImpl.deleteArticle 編譯失敗）**

```bash
./mvnw.cmd -pl blog-module-article -am compile 2>&1 | tee logs/t4-compile.log
```

Expected: 編譯失敗 — `ArticleServiceImpl.deleteArticle` 內呼叫 `articleEventPublisher.publishDeleted(article)` 找不到 method。**這是預期的中間態**，下個 task T5 會修。

如果想暫時 unblock 編譯，可在 ArticleServiceImpl.deleteArticle 暫時改：
```java
articleEventPublisher.publishDeleted(article, null, List.of(), List.of());  // T5 會改寫
```

但更乾淨：直接進 T5 一起改。本 task commit 前不要求編譯成功 — 可以接受 ArticleServiceImpl 編譯失敗，T5 commit 一起編譯成功。

- [ ] **Step 5: Run unit test（如可）**

```bash
./mvnw.cmd -pl blog-module-article -am test -Dtest=ArticleEventPublisherTest 2>&1 | tee logs/t4-publisher-test.log
```

Expected: 1 個新 test pass + 既有 tests pass。但其他 test class（ArticleServiceTest）可能因 ArticleServiceImpl 編譯失敗而 fail — 接受。

- [ ] **Step 6: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/event/ArticleDeletedEvent.java \
        blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleEventPublisher.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleEventPublisherTest.java
git commit -m "$(cat <<'EOF'
feat(article): ArticleDeletedEvent rich payload + ArticleEventPublisher.publishDeleted 改簽名

- ArticleDeletedEvent 從 (UUID, Instant) 擴 8 欄位 record
  含 eventId / articleId / authorId / seriesId / categoryIds / tagIds
- Backward compat: Jackson 對缺欄位 deserialize 為 null，舊 in-flight message 仍可處理
- ArticleEventPublisher.publishDeleted 加 4 個 params（caller 在 delete 前讀取傳入）
- 加 1 個 unit test 驗 rich payload 構造正確

⚠ ArticleServiceImpl.deleteArticle 編譯失敗 — T5 改寫一起修

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: ArticleServiceImpl.deleteArticle 改寫 + 移除 SeriesFacade inject

**Files:**
- Modify: `blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java`
- Modify: `blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleServiceTest.java`

- [ ] **Step 1: ArticleServiceImpl 改 deleteArticle method**

Read `ArticleServiceImpl.java` 找 `deleteArticle` method（既有 batch 4 修改後的版本，含 transactionTemplate + seriesFacade.notify）。

改寫為：

```java
@Override
public void deleteArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
    Article article = findByUuidOrThrow(articleUuid);
    checkWritePermission(operatorId, operatorRole, article);

    /* delete 前讀取：article 刪除後 FK CASCADE 會清 article_tags / article_categories，
     * series consumer 也撈不到。所以要在 delete 前撈 + 包進 ArticleDeletedEvent payload。*/
    Long seriesId = article.getSeriesId();
    List<UUID> categoryIds = articleMapper.findCategoryUuidsByArticleId(article.getId());
    List<UUID> tagIds = articleMapper.findTagUuidsByArticleId(article.getId());

    /* DB 刪除（含 FK CASCADE 自動清 article_versions / article_tags / article_categories）*/
    transactionTemplate.executeWithoutResult(status -> {
        articleRepository.delete(article);
        /*
         * SP-A: 移除 seriesFacade.notifyArticleDeletedFromSeries(seriesId)
         * 改由 SeriesArticleDeletedConsumer 訂閱 ArticleDeletedEvent 處理（冪等）。
         * 同時 ArticleServiceImpl 不再依賴 SeriesFacade interface。
         */
    });

    /* DB 已 commit，best-effort 發送 ArticleDeletedEvent（rich payload）*/
    articleEventPublisher.publishDeleted(article, seriesId, categoryIds, tagIds);
}
```

⚠ Imports 加：
```java
import java.util.List;
import java.util.UUID;
```

- [ ] **Step 2: 移除 SeriesFacade inject**

於 `ArticleServiceImpl` class field 區，移除：
```java
private final SeriesFacade seriesFacade;
```

⚠ 確認 SeriesFacade 在 ArticleServiceImpl 裡只用於 `notifyArticleDeletedFromSeries` — 用 grep 確認。如果有其他用法，留 inject 但移除 notify 呼叫。

```bash
grep -n "seriesFacade" blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java
```

- [ ] **Step 3: 修 ArticleServiceTest 對應 deleteArticle test**

Grep 既有 `deleteArticle` test:

```bash
grep -n "deleteArticle\|seriesFacade" blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleServiceTest.java
```

關鍵改動：
1. **既有 verify 對 seriesFacade.notifyArticleDeletedFromSeries** — 整段移除
2. **既有 verify 對 articleEventPublisher.publishDeleted(any())** — 改成新簽名 verify
3. **既有 test 用 @Mock SeriesFacade** — 如果 ArticleServiceImpl 完全移除 inject 就移除 mock

加 mock for ArticleMapper.findCategoryUuidsByArticleId / findTagUuidsByArticleId：

```java
when(articleMapper.findCategoryUuidsByArticleId(article.getId())).thenReturn(List.of(categoryUuid));
when(articleMapper.findTagUuidsByArticleId(article.getId())).thenReturn(List.of(tagUuid));
```

verify publisher：

```java
verify(articleEventPublisher).publishDeleted(
    eq(article),
    eq(article.getSeriesId()),
    eq(List.of(categoryUuid)),
    eq(List.of(tagUuid))
);
```

加 2 個新 test：

```java
@Test
void deleteArticle_collectsCategoriesAndTags_thenPublish() {
    // 既有 happy path test 加強驗證 categoryIds/tagIds 被傳給 publisher
}

@Test
void deleteArticle_doesNotCallSeriesFacade() {
    // 驗證 SeriesFacade 完全沒被 inject 也不呼叫
    // (如果 SeriesFacade 不再被 inject，此 test 不必 — 改驗證 publisher.publishDeleted 被呼叫即可)
}
```

⚠ 既有 batch 4 T15 寫的「ArticleServiceTest 加 SeriesFacade mock 與 2 個 deleteArticle 測試」需要對應更新。Grep:
```bash
grep -n "SeriesFacade\|seriesFacade" blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleServiceTest.java
```

- [ ] **Step 4: 編譯 + Run article 模組所有 tests**

```bash
./mvnw.cmd -pl blog-module-article -am install -DskipTests
./mvnw.cmd -pl blog-module-article test 2>&1 | tee logs/t5-article-tests.log
```

Expected: 既有 240 tests + 1 new (publishDeleted) = 241 全綠。

- [ ] **Step 5: Commit**

```bash
git add blog-module-article/src/main/java/dowob/xyz/blog/module/article/service/ArticleServiceImpl.java \
        blog-module-article/src/test/java/dowob/xyz/blog/module/article/service/ArticleServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(article): ArticleServiceImpl.deleteArticle 改用 ArticleDeletedEvent rich payload

- delete 前讀 categoryIds / tagIds / seriesId 包進 event
- 移除 seriesFacade.notifyArticleDeletedFromSeries 同步呼叫
- 移除 SeriesFacade inject（ArticleServiceImpl 不再依賴 series 模組）
- ArticleServiceTest mock 對應調整：移除 seriesFacade verify，補 articleMapper 撈 ids 的 mock
- 既有 240 tests + 1 publishDeleted test = 241 全綠

⚠ Series 模組仍未訂閱 ArticleDeletedEvent — T6 加 consumer 後 cross-module 連動才回復

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: SeriesFacade interface 移除 notify + SeriesArticleDeletedConsumer + 對應 unit tests

**Files:**
- Modify: `blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/SeriesFacade.java`
- Modify: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/facade/SeriesFacadeImpl.java`
- Create: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/config/SeriesRabbitMqConfig.java`
- Create: `blog-module-series/src/main/java/dowob/xyz/blog/module/series/consumer/SeriesArticleDeletedConsumer.java`
- Create: `blog-module-series/src/test/java/dowob/xyz/blog/module/series/consumer/SeriesArticleDeletedConsumerTest.java`

- [ ] **Step 1: SeriesFacade interface 移除 notify method**

Read `blog-infrastructure/.../facade/SeriesFacade.java`，移除：

```java
/* 移除整個 method 宣告 */
void notifyArticleDeletedFromSeries(Long seriesId);
```

剩下：
```java
public interface SeriesFacade {
    Optional<SeriesNavigation> getSeriesNavigation(Long articleId);
    Map<Long, SeriesBasicInfo> batchGetSeriesBasicInfo(List<Long> articleIds);
    /* notifyArticleDeletedFromSeries(Long) — SP-A 移除，改由 SeriesArticleDeletedConsumer 訂閱 ArticleDeletedEvent 處理 */
}
```

- [ ] **Step 2: SeriesFacadeImpl 移除對應 method**

Read `SeriesFacadeImpl.java`，移除整個 `notifyArticleDeletedFromSeries(Long)` method 實作。

⚠ `@Lazy ArticleService` setter injection **保留**（getSeriesNavigation 仍用，留給 SP-B 處理）。

- [ ] **Step 3: SeriesRabbitMqConfig 新檔**

新檔 `blog-module-series/src/main/java/dowob/xyz/blog/module/series/config/SeriesRabbitMqConfig.java`:

```java
package dowob.xyz.blog.module.series.config;

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
 * Series 模組 RabbitMQ 設定。訂閱 article.events / article.deleted。
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class SeriesRabbitMqConfig {

    public static final String QUEUE_SERIES_ARTICLE_DELETED = "series.article-deleted";
    public static final String DLQ_ROUTING_KEY = "dlq.series.article-deleted";

    @Bean
    public Queue seriesArticleDeletedQueue() {
        return new Queue(QUEUE_SERIES_ARTICLE_DELETED, true, false, false, dlqArgs());
    }

    @Bean
    public Binding bindSeriesArticleDeleted(
            Queue seriesArticleDeletedQueue,
            @Qualifier("articleEventsExchange") TopicExchange articleEventsExchange) {
        return BindingBuilder
                .bind(seriesArticleDeletedQueue)
                .to(articleEventsExchange)
                .with(ArticleRabbitMqConfig.ROUTING_KEY_DELETED);
    }

    private Map<String, Object> dlqArgs() {
        return Map.of(
            "x-dead-letter-exchange", "dlq.exchange",
            "x-dead-letter-routing-key", DLQ_ROUTING_KEY,
            "x-message-ttl", 600_000
        );
    }
}
```

⚠ 確認 `articleEventsExchange` bean name — 若不同，調 @Qualifier。

- [ ] **Step 4: SeriesArticleDeletedConsumerTest（先寫 4 RED tests）**

新檔 `blog-module-series/src/test/java/dowob/xyz/blog/module/series/consumer/SeriesArticleDeletedConsumerTest.java`:

```java
package dowob.xyz.blog.module.series.consumer;

import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.module.article.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeriesArticleDeletedConsumerTest {

    @Mock private IdempotencyService idempotencyService;
    @Mock private SeriesMapper seriesMapper;
    @InjectMocks private SeriesArticleDeletedConsumer consumer;

    private ArticleDeletedEvent event(Long seriesId) {
        return new ArticleDeletedEvent(
            UUID.randomUUID(), 100L, UUID.randomUUID(), 1L,
            seriesId, List.of(), List.of(), Instant.now()
        );
    }

    @Test
    void onArticleDeleted_seriesIdNull_noOp() {
        ArticleDeletedEvent ev = event(null);

        consumer.onArticleDeleted(ev);

        verify(idempotencyService, never()).markProcessed(any(), any());
        verify(seriesMapper, never()).decrementArticleCount(any());
    }

    @Test
    void onArticleDeleted_firstTime_decrementsCount() {
        ArticleDeletedEvent ev = event(200L);
        when(idempotencyService.markProcessed(eq(ev.eventId()),
                eq(SeriesArticleDeletedConsumer.CONSUMER_NAME))).thenReturn(true);

        consumer.onArticleDeleted(ev);

        verify(seriesMapper, times(1)).decrementArticleCount(200L);
    }

    @Test
    void onArticleDeleted_replayed_skipsDecrement() {
        ArticleDeletedEvent ev = event(200L);
        when(idempotencyService.markProcessed(eq(ev.eventId()),
                eq(SeriesArticleDeletedConsumer.CONSUMER_NAME))).thenReturn(false);

        consumer.onArticleDeleted(ev);

        verify(seriesMapper, never()).decrementArticleCount(any());
    }

    @Test
    void onArticleDeleted_decrementThrows_reThrowToDLQ() {
        ArticleDeletedEvent ev = event(200L);
        when(idempotencyService.markProcessed(any(), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("DB down"))
            .when(seriesMapper).decrementArticleCount(200L);

        assertThatThrownBy(() -> consumer.onArticleDeleted(ev))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("DB down");
    }
}
```

- [ ] **Step 5: Run RED**

```bash
./mvnw.cmd -pl blog-module-series -am test -Dtest=SeriesArticleDeletedConsumerTest 2>&1 | tee logs/t6-red.log
```

Expected: 編譯失敗（SeriesArticleDeletedConsumer 不存在）。

- [ ] **Step 6: SeriesArticleDeletedConsumer.java（GREEN）**

新檔 `blog-module-series/src/main/java/dowob/xyz/blog/module/series/consumer/SeriesArticleDeletedConsumer.java`:

```java
package dowob.xyz.blog.module.series.consumer;

import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.module.article.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.series.config.SeriesRabbitMqConfig;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Series 模組訂閱 ArticleDeletedEvent — 連動 series.article_count -1。
 *
 * <p>冪等性：用 IdempotencyService 對 (event_id, consumer_name) 做 dedup，
 * 重送會被 skip 不會多次扣 count。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeriesArticleDeletedConsumer {

    public static final String CONSUMER_NAME = "series.article-deleted";

    private final IdempotencyService idempotencyService;
    private final SeriesMapper seriesMapper;

    @RabbitListener(queues = SeriesRabbitMqConfig.QUEUE_SERIES_ARTICLE_DELETED)
    public void onArticleDeleted(ArticleDeletedEvent event) {
        try {
            /* seriesId null 表示文章不在 series（或舊 payload backward compat）*/
            if (event.seriesId() == null) {
                return;
            }
            /* 冪等 check：第一次 INSERT 成功才繼續處理；重送會 skip */
            if (!idempotencyService.markProcessed(event.eventId(), CONSUMER_NAME)) {
                return;
            }
            seriesMapper.decrementArticleCount(event.seriesId());
        } catch (Exception e) {
            log.error("處理 ArticleDeletedEvent 失敗 articleId={} seriesId={}",
                event.articleId(), event.seriesId(), e);
            throw e;  // re-throw 讓 RabbitMQ requeue / 進 DLQ
        }
    }
}
```

- [ ] **Step 7: Run GREEN**

```bash
./mvnw.cmd -pl blog-module-series -am test -Dtest=SeriesArticleDeletedConsumerTest 2>&1 | tee logs/t6-green.log
```

Expected: 4 tests pass。

- [ ] **Step 8: Run series 全模組（既有 13 unit + 11 IT + 3 cross + 4 new = 31）**

```bash
./mvnw.cmd -pl blog-module-series -am test 2>&1 | tee logs/t6-series-all.log
```

⚠ 既有 cross-module IT「DELETE article → series.article_count -1」會 fail（因為 ArticleServiceImpl 不再 sync notify，改 MQ event；但測試裡 RabbitMQ 是 mocked / excluded，consumer 不會自動跑）。**這是預期問題**，T7 修。

如果 IT fail 是因為 SeriesFacade.notifyArticleDeletedFromSeries 找不到（既有 SeriesServiceTest 引用），改 SeriesServiceTest 移除對應 mock / verify。

```bash
grep -n "notifyArticleDeletedFromSeries" blog-module-series/src/test/
```

- [ ] **Step 9: Commit**

```bash
git add blog-infrastructure/src/main/java/dowob/xyz/blog/infrastructure/facade/SeriesFacade.java \
        blog-module-series/src/main/java/dowob/xyz/blog/module/series/facade/SeriesFacadeImpl.java \
        blog-module-series/src/main/java/dowob/xyz/blog/module/series/config/SeriesRabbitMqConfig.java \
        blog-module-series/src/main/java/dowob/xyz/blog/module/series/consumer/SeriesArticleDeletedConsumer.java \
        blog-module-series/src/test/java/dowob/xyz/blog/module/series/consumer/SeriesArticleDeletedConsumerTest.java \
        blog-module-series/src/test/java/dowob/xyz/blog/module/series/service/SeriesServiceTest.java
git commit -m "$(cat <<'EOF'
feat(series): SeriesArticleDeletedConsumer + RabbitMqConfig + 移除 SeriesFacade.notify

- SeriesFacade interface 移除 notifyArticleDeletedFromSeries
- SeriesFacadeImpl 對應移除 method 實作
- SeriesRabbitMqConfig: queue series.article-deleted 訂 article.events / article.deleted
- SeriesArticleDeletedConsumer @RabbitListener 處理 ArticleDeletedEvent
  - seriesId null no-op
  - IdempotencyService 對 (event_id, consumer_name) 做 dedup
  - 處理失敗 re-throw 進 DLQ
- 4 unit tests 涵蓋 no-op / first-time / replayed / DLQ
- SeriesServiceTest 移除既有 notify 相關 mock

⚠ @Lazy ArticleService 保留 — getSeriesNavigation 仍用，留給 SP-B 解
⚠ Cross-module IT 升級在 T7

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Cross-module IT 升級（含重送冪等驗證）+ DatabaseCleaner

**Files:**
- Modify: `blog-module-series/src/test/java/dowob/xyz/blog/module/series/integration/CrossModuleSeriesIT.java`
- Modify: `blog-start/src/test/java/dowob/xyz/blog/e2e/support/DatabaseCleaner.java`

- [ ] **Step 1: 升級既有 cross-module IT**

Read `CrossModuleSeriesIT.java`，找 既有 test「DELETE article → series.article_count 連動 -1」，改為走 MQ event 流：

```java
@Test
@DisplayName("DELETE article → series.article_count 連動 -1（透過 MQ event）")
void deleteArticle_decrementsSeriesCount() {
    // 1. 建 series + article in series（既有 logic）
    Series series = createSeries(...);
    Article article = createPublishedArticle(...);
    article.setSeriesId(series.getId());
    articleRepo.save(article);
    seriesMapper.incrementArticleCount(series.getId());  // count = 1

    // 2. DELETE /api/v1/articles/{uuid}（會 publish ArticleDeletedEvent）
    mockMvc.perform(delete("/api/v1/articles/{uuid}", article.getUuid())
            .with(asUser(USER1_ID, Role.AUTHOR)))
        .andExpect(status().isOk());

    // 3. 直接呼叫 SeriesArticleDeletedConsumer 模擬 MQ 投遞
    //    （IT 跑時 RabbitMQ 通常不真起，所以手動觸發 consumer）
    ArticleDeletedEvent event = new ArticleDeletedEvent(
        UUID.randomUUID(), article.getId(), article.getUuid(), USER1_ID,
        series.getId(), List.of(), List.of(), Instant.now()
    );
    seriesArticleDeletedConsumer.onArticleDeleted(event);

    // 4. assert series.article_count = 0
    Series afterDelete = seriesRepo.findByUuid(series.getUuid()).orElseThrow();
    assertThat(afterDelete.getArticleCount()).isEqualTo(0);
}
```

⚠ 既有 batch 3 cross IT 是同步呼叫 facade。改 MQ event 後 IT 要：
- 直接呼叫 consumer（最簡，但 IT 不真覆蓋 RabbitMQ wiring）
- 或起 RabbitMQ Testcontainer + 等 consumer 跑（複雜）

選簡單做法：直接呼叫 consumer，附 comment 說明 wiring 有 SeriesArticleDeletedConsumerTest 涵蓋。

- [ ] **Step 2: 加重送冪等 IT**

```java
@Test
@DisplayName("DELETE article 同事件重送 → article_count 不再扣（冪等驗證）")
void deleteArticle_replayedEvent_skipsDecrement() {
    // 1. 建 series + article in series
    Series series = createSeries(...);
    Article article = createPublishedArticle(...);
    article.setSeriesId(series.getId());
    articleRepo.save(article);
    seriesMapper.incrementArticleCount(series.getId());  // count = 1

    // 2. 構造一個 event
    UUID eventId = UUID.randomUUID();
    ArticleDeletedEvent event = new ArticleDeletedEvent(
        eventId, article.getId(), article.getUuid(), USER1_ID,
        series.getId(), List.of(), List.of(), Instant.now()
    );

    // 3. 第一次處理 → article_count = 0
    seriesArticleDeletedConsumer.onArticleDeleted(event);
    Series afterFirst = seriesRepo.findByUuid(series.getUuid()).orElseThrow();
    assertThat(afterFirst.getArticleCount()).isEqualTo(0);

    // 4. 第二次處理（同一 event，模擬重送）
    seriesArticleDeletedConsumer.onArticleDeleted(event);

    // 5. assert article_count 仍是 0（沒被扣到 -1）
    Series afterReplay = seriesRepo.findByUuid(series.getUuid()).orElseThrow();
    assertThat(afterReplay.getArticleCount()).isEqualTo(0);

    // 6. assert processed_events 只有 1 筆對應 (eventId, "series.article-deleted")
    long count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM processed_events WHERE event_id = ?::uuid AND consumer_name = ?",
        Long.class, eventId.toString(), SeriesArticleDeletedConsumer.CONSUMER_NAME
    );
    assertThat(count).isEqualTo(1);
}
```

- [ ] **Step 3: 加 article 不在 series IT**

```java
@Test
@DisplayName("Article 不在 series 被刪 → no-op")
void deleteArticle_notInSeries_noDecrement() {
    Article article = createPublishedArticle(USER1_ID);  // seriesId 為 null

    ArticleDeletedEvent event = new ArticleDeletedEvent(
        UUID.randomUUID(), article.getId(), article.getUuid(), USER1_ID,
        null, List.of(), List.of(), Instant.now()  // seriesId = null
    );
    seriesArticleDeletedConsumer.onArticleDeleted(event);

    // assert: 無 processed_events 記錄（早返不寫 dedup）
    long count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM processed_events", Long.class);
    assertThat(count).isEqualTo(0);
}
```

⚠ 補 `@Autowired SeriesArticleDeletedConsumer seriesArticleDeletedConsumer` + `@Autowired JdbcTemplate jdbcTemplate` 到 CrossModuleSeriesIT 既有 setup。

- [ ] **Step 4: 修 DatabaseCleaner 補 processed_events 清理**

Read `blog-start/src/test/java/dowob/xyz/blog/e2e/support/DatabaseCleaner.java`，於 cleanAll() 加：

```java
jdbcTemplate.execute("DELETE FROM processed_events");
```

順序：放在實體表清理區（articles / series / users 等）**之前**。`processed_events` 沒 FK 到其他表，順序不嚴格 — 放最前面方便 dedup state 清零。

- [ ] **Step 5: 修各 TestApplication EnableJdbcRepositories（如需）**

T2 已標記：各 TestApplication 的 `@EnableJdbcRepositories(basePackages = {...})` 可能要加 `dowob.xyz.blog.infrastructure.idempotency.repository`。

如果 T6 / T7 跑 IT 報「ProcessedEventRepository bean not found」就加。檔案：
- `blog-module-series/src/test/java/dowob/xyz/blog/module/series/config/SeriesTestApplication.java`
- 其他 TestApplication（version / reading / comment）視 IT 範圍決定

- [ ] **Step 6: install + Run cross IT**

```bash
./mvnw.cmd -pl blog-module-series -am install -DskipTests
./mvnw.cmd -pl blog-module-series test -Dtest=CrossModuleSeriesIT 2>&1 | tee logs/t7-cross.log
```

Expected: 既有 3 cross IT + 2 新（重送冪等 + not-in-series）= 5 cross IT 全綠。

- [ ] **Step 7: Run all SP-A affected modules**

```bash
./mvnw.cmd -pl blog-module-article,blog-module-series,blog-module-version,blog-module-reading,blog-module-comment,blog-infrastructure test 2>&1 | tee logs/t7-all.log
```

Expected: 全綠。

- [ ] **Step 8: Commit**

```bash
git add blog-module-series/src/test/java/dowob/xyz/blog/module/series/integration/CrossModuleSeriesIT.java \
        blog-start/src/test/java/dowob/xyz/blog/e2e/support/DatabaseCleaner.java \
        # 如有改 TestApplication 也加
git commit -m "$(cat <<'EOF'
test(series): CrossModuleSeriesIT 升級 + 重送冪等驗證 + DatabaseCleaner 補 processed_events

- 既有 deleteArticle_decrementsSeriesCount 改走 MQ event 流（直接呼叫 consumer）
- 加 deleteArticle_replayedEvent_skipsDecrement：重送 1 次仍只扣 1 次（dedup 驗證）
- 加 deleteArticle_notInSeries_noDecrement：seriesId null 早返不寫 processed_events
- DatabaseCleaner 補 DELETE FROM processed_events
- TestApplication EnableJdbcRepositories 補 idempotency.repository（如需）

SP-A 全綠：article 241 + series 31 (含 5 cross IT) + version 48 + reading 66 + comment 67

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review Checklist

實作完成後請勾選：

- [ ] V17 migration 套用成功（Testcontainers）
- [ ] processed_events 表 + UNIQUE constraint + index 都對
- [ ] IdempotencyService 3 unit tests 全綠（first / conflict / different consumer）
- [ ] ArticleDeletedEvent 從 2 欄位擴 8 欄位 record
- [ ] ArticleEventPublisher.publishDeleted 簽名改為 (Article, Long, List<UUID>, List<UUID>)
- [ ] ArticleServiceImpl.deleteArticle 不再呼叫 SeriesFacade
- [ ] ArticleServiceImpl 完全移除 SeriesFacade inject
- [ ] SeriesFacade interface 不再含 notifyArticleDeletedFromSeries
- [ ] SeriesFacadeImpl 對應移除 method 實作
- [ ] SeriesArticleDeletedConsumer 4 unit tests 全綠
- [ ] CrossModuleSeriesIT：既有 deleteArticle 升級 + 重送冪等 + not-in-series 三個 cross IT 全綠
- [ ] DatabaseCleaner 補 processed_events 清理
- [ ] schema.md V17 + processed_events 表寫入
- [ ] @Lazy ArticleService 仍存在於 SeriesFacadeImpl（SP-B 處理）

---

## 後續批次

- **SP-B（article-facade-routing）**：4 模組改用 ArticleFacade 取代直接 inject ArticleService；徹底解掉 SeriesFacadeImpl 的 @Lazy ArticleService。
- **SP-D（events-and-helpers-cleanup）**：TagFacade.deleteArticleTags 改 event（用 SP-A 留的 categoryIds / tagIds payload）；AutoSnapshotPolicy / VersioningService 改 facade；SecurityUtils.isAdmin 提取。
- **SP-C（article-service-split）**：ArticleServiceImpl 1018 行拆 god class。
