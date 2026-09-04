package dowob.xyz.blog.module.article.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 文章下架事件（PUBLISHED → ARCHIVED）。
 *
 * <p>語意為「這篇文章不再公開」，供 search 模組移除 Elasticsearch 索引。
 * 與 {@link ArticleDeletedEvent} 的差別在於資料列仍在，consumer 需要更多欄位時可回查，
 * 故 payload 維持精簡（不採 rich payload）。</p>
 *
 * <p><b>刻意不共用 {@code article.deleted} routing key</b>：該 key 另有 series 模組訂閱並遞減
 * {@code series.article_count}，下架若走同一條路會讓該計數多扣一次（見
 * {@code ArticleRabbitMqConfig#ROUTING_KEY_ARCHIVED} 的說明）。</p>
 *
 * @param eventId     事件 dedup key（producer 每次 publish 時 random gen，供 consumer 冪等使用）
 * @param articleUuid 文章公開 UUID（即 Elasticsearch document id）
 * @param occurredAt  事件發生時間
 *
 * @author Yuan
 * @version 1.0
 */
public record ArticleArchivedEvent(
    UUID eventId,
    UUID articleUuid,
    Instant occurredAt
) {}
