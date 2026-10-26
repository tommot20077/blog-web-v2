package dowob.xyz.blog.infrastructure.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文章刪除事件（跨模組共用契約；rich payload — 文章已刪，consumer 撈不到 entity，必須 event 帶足夠資訊）。
 *
 * <p>Backward compatibility: Spring AMQP + Jackson 對缺欄位 deserialize 為 null，
 * 升級時 in-flight 舊 message（缺少新增的 {@code authorUuid}／{@code seriesUuid}）仍可處理；
 * 新 consumer 對 null 做防禦判斷。</p>
 *
 * <p><b>Long 過渡欄位已全數移除</b>：{@code articleId}、{@code seriesId} 於 M4-b 移除，{@code authorId} 於 M6-a 移除——
 * 發送端只帶 {@code authorUuid}。升級當下佇列中仍含這些舊欄位的 in-flight 訊息可正常反序列化（多出的欄位被忽略），
 * 見 {@code ArticleDeletedEventSerializationTest}。</p>
 *
 * @param eventId      事件 dedup key（producer 每次 publish 時 random gen）
 * @param articleUuid  文章公開 UUID
 * @param categoryIds  文章被刪前的 category UUIDs（nullable — 舊 message 為 null）
 * @param tagIds       文章被刪前的 tag UUIDs（nullable — 舊 message 為 null）
 * @param occurredAt   事件發生時間
 * @param authorUuid   作者公開 UUID（nullable — 舊 message 或 uuid 查詢失敗時為 null）
 * @param seriesUuid   文章所屬 series 公開 UUID（nullable — 文章不在 series、舊 message 或查詢失敗時為 null）
 *
 * @author Yuan
 * @version 5.0（由 article 模組搬到 blog-infrastructure；新增 authorUuid、seriesUuid；M4-b 移除 articleId、seriesId；M6-a 移除 authorId）
 */
public record ArticleDeletedEvent(
    UUID eventId,
    UUID articleUuid,
    List<UUID> categoryIds,
    List<UUID> tagIds,
    Instant occurredAt,
    UUID authorUuid,
    UUID seriesUuid
) {}
