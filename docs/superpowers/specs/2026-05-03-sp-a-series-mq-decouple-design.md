# SP-A：Series MQ Decouple — 設計文件

> **Spec date:** 2026-05-03
> **Branch:** `refactor/sp-a-series-mq-decouple`
> **Migration:** V17
> **Roadmap parent:** `2026-05-03-architecture-decoupling-roadmap.md`
> **Status:** Approved by Yuan, ready for implementation plan

---

## 1. Goal & 範圍

對齊 roadmap §5.1。SP-A 是 4 個 sub-projects 第一個，負責：

1. 解掉 `SeriesFacade.notifyArticleDeletedFromSeries(Long)` 同步通知 — 改用 ArticleDeletedEvent 訂閱模式
2. 擴充 `ArticleDeletedEvent` payload 為 rich event（含 seriesId / authorId / categoryIds / tagIds，給未來 SP-D / SP-X 重用）
3. 加 V17 `processed_events` 表 + `IdempotencyService` 通用 utility，所有 MQ consumer 用同一套冪等策略
4. Series 模組新增 `SeriesArticleDeletedConsumer` 訂閱 `article.deleted` 事件，內部冪等處理 `decrementArticleCount`

**範圍邊界（不在 SP-A 做）:**
- ❌ SeriesFacadeImpl 的 @Lazy ArticleService inject — `getSeriesNavigation` 仍在用，留給 SP-B 統一在 ArticleFacade 補 method 後解
- ❌ TagFacade.deleteArticleTags 改 event — 這是 SP-D 範圍（但 ArticleDeletedEvent payload 提早含 tagIds 給 SP-D 用）
- ❌ Reconcile endpoint — 加 IdempotencyService 後重送已不會出錯，不需要 reconcile

**Done definition:**
- `SeriesFacade` interface 不再含 notify method（grep 0）
- `ArticleServiceImpl.deleteArticle` 不再呼叫 SeriesFacade
- `ArticleDeletedEvent` 新 payload，既有 search consumer 仍正常（backward compat 驗證）
- 重送同一 ArticleDeletedEvent → series.article_count 不會被多次扣
- IT 全綠：cross-module IT「DELETE article → series.article_count -1」 + 新增重送冪等驗證

---

## 2. 設計決策摘要

| 決策點 | 選擇 | 理由 |
|------|------|------|
| Series consumer 冪等性 | dedup table（C） | 重送會讓 article_count 漂移；個人部落格規模也接受不了 user-visible 不一致 |
| Dedup table 位置 | blog-infrastructure 通用 | DRY，未來 SP-D / 其他 SP 同套 |
| Event payload 風格 | Rich payload（articleId/uuid/authorId/seriesId/categoryIds/tagIds） | 對齊 roadmap §4.4 — 文章已刪 consumer 撈不到，必須 event 帶 |
| Event 加 dedup key | UUID `eventId`（producer random gen） | 既有 `articleUuid + occurredAt` 同毫秒 burst delete 會撞；UUID 永遠唯一 |
| Backward compat | Jackson 對缺欄位 deserialize 為 null | 升級時 in-flight 舊 message 仍可處理；新 consumer 對 null 做防禦 |
| @Lazy 處理時機 | SP-A 不解（A 選項） | SeriesFacadeImpl 還用 ArticleService.findById；留給 SP-B 在 ArticleFacade 補 method 後一次解 |
| 既有 ArticleEventPublisher.publishDeleted 簽名 | 加 4 個 params（seriesId / categoryIds / tagIds） | 由 caller 傳入避免 publisher 內 inject Mapper（違反原則 3） |

---

## 3. 模組變動範圍

**會動的模組（5 個）:**
1. `blog-db-migration` — V17 + schema.md
2. `blog-infrastructure` — IdempotencyService / ProcessedEvent entity / Repository
3. `blog-module-article` — ArticleDeletedEvent / ArticleEventPublisher / ArticleServiceImpl / ArticleMapper / ArticleServiceTest
4. `blog-module-series` — 新 consumer / 新 RabbitMqConfig / SeriesFacade / SeriesFacadeImpl
5. `blog-start` — DatabaseCleaner 補 processed_events 清理

**不會動的模組（保 backward compat）:**
- `blog-module-search` — ArticleSearchListener 訂閱 `article.deleted` 仍正常運作（payload 多欄位不影響其只用 articleUuid 的邏輯）
- 其他模組

---

## 4. Schema (V17)

### 4.1 processed_events 表

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| event_id | UUID | NOT NULL | event 的 dedup key（producer 在 publish 時 random gen）|
| consumer_name | VARCHAR(100) | NOT NULL | 哪個 consumer 處理過（避免不同 consumer 互相 skip）|
| processed_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Constraints:**
- `uq_processed_events_event_consumer` UNIQUE (event_id, consumer_name) — 一個 event 多 consumer 訂閱時各自獨立冪等

**Indexes:**
- `processed_events_pkey` (auto)
- `uq_processed_events_event_consumer` (auto, UNIQUE)
- `idx_processed_events_processed_at` on (processed_at) — 給未來 cleanup（保留 N 天紀錄）用

### 4.2 V17 SQL

```sql
-- V17__add_processed_events.sql
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

### 4.3 Cleanup 策略（未來）

`processed_events` 會持續增長。Cleanup cron（未來 SP-X 加）：

```sql
DELETE FROM processed_events WHERE processed_at < NOW() - INTERVAL '30 days';
```

SP-A 不做 cleanup — 個人部落格量不大，跑半年才幾千筆。Cleanup 工作 spec 留給後續 batch。

---

## 5. ArticleDeletedEvent 新 payload

### 5.1 Record 定義

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

### 5.2 既有 search consumer 影響

`blog-module-search/listener/ArticleSearchListener` 接 `ArticleDeletedEvent` argument，只用 `articleUuid` 來 ES delete。新欄位多餘但**不會 break**。

驗證方式：SP-A IT 跑既有 search IT（如有）+ verify ES delete 仍走得通。

---

## 6. IdempotencyService（blog-infrastructure 通用 utility）

### 6.1 ProcessedEvent entity

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

### 6.2 ProcessedEventRepository

```java
package dowob.xyz.blog.infrastructure.idempotency.repository;

import dowob.xyz.blog.infrastructure.idempotency.model.ProcessedEvent;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEventRepository extends CrudRepository<ProcessedEvent, Long> {
}
```

### 6.3 IdempotencyService

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
     * <p>用 {@code REQUIRES_NEW} 避免 caller transaction rollback 時也 rollback 此記錄
     * — 我們希望「業務失敗 / 業務成功」兩種都 mark processed（避免無限重送）。</p>
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

### 6.4 Transaction propagation 注意

`REQUIRES_NEW`：開新 transaction（即使 caller 在 transaction 內）。理由：
- Caller transaction 失敗 rollback → 不該連 processed_events 記錄一起 rollback（否則會無限重送）
- 換言之：**markProcessed 是 commit-once 的標記**，業務 logic 失敗也 keep mark
- 業務 logic 失敗則由 RabbitMQ 重送 → 但下次因 mark 已存在會 skip → 漏處理
- **這是接受的 trade-off：寧可漏一次也不重複扣**

⚠ 對應 SP-A 場景：series.article_count 寧可少扣一次（admin 後台手動補）也不要多扣（user 看到計數異常）。

---

## 7. Producer 改動

### 7.1 ArticleMapper 加 2 個 query

```java
@Select("""
        SELECT t.uuid FROM article_tags at JOIN tags t ON at.tag_id = t.id
         WHERE at.article_id = #{articleId}
        """)
List<UUID> findTagUuidsByArticleId(@Param("articleId") Long articleId);

@Select("""
        SELECT c.uuid FROM article_categories ac JOIN categories c ON ac.category_id = c.id
         WHERE ac.article_id = #{articleId}
        """)
List<UUID> findCategoryUuidsByArticleId(@Param("articleId") Long articleId);
```

⚠ 確認 `article_tags` / `article_categories` 結構（SP-A T 階段先 Read schema.md / V1 migration 確認 column 名）。如果結構不同（例如 article_categories 不存在）就調整。

### 7.2 ArticleEventPublisher.publishDeleted 改簽名

```java
/**
 * 發 ArticleDeletedEvent — rich payload，因文章已刪 consumer 撈不到。
 *
 * @param article      文章 entity（提供 id / uuid / authorId）
 * @param seriesId     文章所屬 series id（nullable）
 * @param categoryIds  文章 categories（caller 在 delete 前讀取）
 * @param tagIds       文章 tags（caller 在 delete 前讀取）
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

### 7.3 ArticleServiceImpl.deleteArticle 改寫

```java
@Override
public void deleteArticle(Long operatorId, Role operatorRole, UUID articleUuid) {
    Article article = findByUuidOrThrow(articleUuid);
    checkWritePermission(operatorId, operatorRole, article);

    /* 在 delete 前讀取 categories / tags / seriesId */
    Long seriesId = article.getSeriesId();
    List<UUID> categoryIds = articleMapper.findCategoryUuidsByArticleId(article.getId());
    List<UUID> tagIds = articleMapper.findTagUuidsByArticleId(article.getId());

    /* DB 刪除（含 FK CASCADE 自動清 article_versions / article_tags / article_categories）*/
    transactionTemplate.executeWithoutResult(status -> {
        articleRepository.delete(article);
        /*
         * ⚠ 移除：seriesFacade.notifyArticleDeletedFromSeries(seriesId)
         * 改由 SeriesArticleDeletedConsumer 訂閱 ArticleDeletedEvent 處理（冪等）。
         */
    });

    /* DB 已 commit，best-effort 發送刪除事件 MQ */
    articleEventPublisher.publishDeleted(article, seriesId, categoryIds, tagIds);
}
```

### 7.4 既有 SeriesFacade inject 移除

ArticleServiceImpl `private final SeriesFacade seriesFacade;` inject 是否還需要？

- `SeriesFacade.getSeriesNavigation()` — ArticleQueryService 用，不在 ArticleServiceImpl
- `SeriesFacade.batchGetSeriesBasicInfo()` — ArticleQueryService 用
- `SeriesFacade.notifyArticleDeletedFromSeries()` — SP-A 移除

→ ArticleServiceImpl 完全不需要 SeriesFacade，**移除 inject**。

---

## 8. Series 模組改動

### 8.1 SeriesRabbitMqConfig 加 queue + binding

新檔（或擴充既有）`blog-module-series/.../config/SeriesRabbitMqConfig.java`：

```java
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

### 8.2 SeriesArticleDeletedConsumer

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
 * <p>冪等性：用 IdempotencyService 對 event_id + consumer_name 做 dedup，
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

### 8.3 SeriesFacade interface 移除 notify

```java
public interface SeriesFacade {
    Optional<SeriesNavigation> getSeriesNavigation(Long articleId);
    Map<Long, SeriesBasicInfo> batchGetSeriesBasicInfo(List<Long> articleIds);
    /* notifyArticleDeletedFromSeries(Long) 移除 — 改由 SeriesArticleDeletedConsumer 處理 */
}
```

`SeriesFacadeImpl` 對應移除 `notifyArticleDeletedFromSeries` method 實作。

⚠ `@Lazy ArticleService` setter injection **保留**（getSeriesNavigation 還在用）— 留給 SP-B 統一在 ArticleFacade 補 findById 後解。

---

## 9. 測試策略（~12 tests）

### 9.1 Unit tests（10）

| 測試類別 | 數量 | 涵蓋 |
|---|---|---|
| `IdempotencyServiceTest` | 3 | 第一次 markProcessed 回 true / 重送 conflict 回 false / 不同 consumer 互不影響 |
| `ArticleEventPublisherTest`（既有，加 1） | 1 | publishDeleted 新簽名構造正確 event（rich payload） |
| `ArticleServiceTest`（既有，加 2） | 2 | deleteArticle 收集 categoryIds/tagIds 並 call publisher / 移除 seriesFacade 呼叫 |
| `SeriesArticleDeletedConsumerTest` | 4 | seriesId null no-op / 第一次處理 decrement / 重送 skip / consumer crash re-throw 進 DLQ（mock IdempotencyService）|

### 9.2 Cross-module IT（3）

```java
@Test
@DisplayName("DELETE article → series.article_count 連動 -1（既有 IT 升級）")
void deleteArticle_decrementsSeriesCount() {
    // 既有 batch 3 cross IT，要驗證走 MQ event 路徑仍正確
    // 1. 建 series + article (in series)
    // 2. POST /articles/{uuid}/publish (article_count = 1)
    // 3. DELETE /articles/{uuid}
    // 4. 等待 consumer 處理（in-process @RabbitListener 同步）
    // 5. assert series.article_count == 0
}

@Test
@DisplayName("DELETE article 同事件重送 → article_count 不再扣（冪等驗證）")
void deleteArticle_replayedEvent_skipsDecrement() {
    // 1. 建 series + article in series
    // 2. POST /articles/{uuid}/publish (article_count = 1)
    // 3. DELETE article → consumer 處理 article_count = 0
    // 4. 模擬重送：直接 call consumer.onArticleDeleted(同一 event 物件)
    // 5. assert article_count 仍是 0（沒被扣到 -1）
    // 6. assert processed_events 仍只有 1 筆對應 (event_id, "series.article-deleted")
}

@Test
@DisplayName("Article 不在 series 被刪 → no-op")
void deleteArticle_notInSeries_noDecrement() {
    // 1. 建 article（不在任何 series）
    // 2. DELETE
    // 3. assert 無 series article_count 變動
    // 4. assert processed_events 無新增記錄（早返不寫 dedup）
    //    ⚠ 此處 verify 細節：seriesId == null 早返不需要 dedup
}
```

### 9.3 既有 batch 3 cross IT 不破

執行 `mvnw test -pl blog-module-series` 確認既有 SeriesControllerIT (11) + 既有 CrossModuleSeriesIT (3) + SeriesServiceTest (13) 全綠 = 27 既有 + 新加 4 + 3 = ~34 tests。

---

## 10. 模組依賴變化

```
[Before]
ArticleServiceImpl → SeriesFacade.notifyArticleDeletedFromSeries
SeriesFacadeImpl    → @Lazy ArticleService（為了 getSeriesNavigation + ...）

[After]
ArticleServiceImpl → ArticleEventPublisher → MQ → SeriesArticleDeletedConsumer
                                                  ↓
                                                  IdempotencyService（infrastructure）
                                                  SeriesMapper
SeriesFacadeImpl    → @Lazy ArticleService（保留，留給 SP-B）
```

**ArticleServiceImpl 已不依賴 SeriesFacade**（移除 inject）— 是 SP-A 的核心架構成果。

---

## 11. Limitations / Future Work

1. **@Lazy ArticleService 仍存在於 SeriesFacadeImpl** — 因 `getSeriesNavigation` 還用 `articleService.findById`。SP-B 補 ArticleFacade.findById 後完全移除。

2. **processed_events 無自動 cleanup** — 表會持續增長。個人部落格規模（每月幾十事件）半年才幾千筆 PG hold 得住，但長期需要 cleanup cron（保留最近 30 天）。SP-A 不做，留給後續 batch。

3. **markProcessed 在業務 logic 失敗時也會 keep mark（REQUIRES_NEW）** — 接受「寧可漏一次也不重複扣」的 trade-off。對應 series.article_count 寧可少扣不多扣的方向。極端情境下需要 admin reconcile（未來可加 endpoint）。

4. **舊 in-flight ArticleDeletedEvent message** — 升級時還在 queue 的舊 message（2 欄位 payload）會 deserialize 為新 record（多欄位 null）。SeriesArticleDeletedConsumer 對 seriesId null 早返，安全。

5. **Backward compat for search consumer** — 既有 ArticleSearchListener 接 ArticleDeletedEvent 只用 articleUuid，新欄位多餘但不破壞。SP-A 跑既有 IT 確認。

---

## 12. Implementation Plan 預估（~7 tasks）

```
T1  V17 migration（processed_events 表）+ schema.md
T2  IdempotencyService + ProcessedEvent entity / Repository（在 blog-infrastructure）
T3  ArticleDeletedEvent payload 擴充（rich record）
T4  ArticleMapper 加 findTagUuidsByArticleId / findCategoryUuidsByArticleId
T5  ArticleEventPublisher.publishDeleted 改簽名 + ArticleServiceImpl.deleteArticle 改寫
    + 移除 SeriesFacade inject
T6  SeriesFacade interface 移除 notify method + SeriesFacadeImpl 實作移除
    + SeriesRabbitMqConfig + SeriesArticleDeletedConsumer + 對應 unit tests
T7  Cross-module IT（含重送冪等驗證）+ DatabaseCleaner 補 processed_events 清理
```

---

## 13. 後續批次預告

完成 SP-A 後，**SP-B（article-facade-routing）** 接續：4 模組（comment / reading / series / version）改用 ArticleFacade 取代直接 inject ArticleService；徹底解掉 SeriesFacadeImpl 的 @Lazy ArticleService。
