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
