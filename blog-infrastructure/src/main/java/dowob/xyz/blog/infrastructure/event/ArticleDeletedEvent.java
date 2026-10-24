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
 * <p><b>過渡欄位（只增不減）</b>：{@code articleId}、{@code authorId}、{@code seriesId} 三個 Long 欄位保留，
 * 現有 consumer（series、search）仍讀取它們；發送端同時填入對應的 uuid 欄位。
 * Long 欄位將於各 owner 模組階段（series 於 M4、article 於 M6）移除，屆時 consumer 改讀 uuid。</p>
 *
 * @param eventId      事件 dedup key（producer 每次 publish 時 random gen）
 * @param articleId    文章資料庫主鍵（過渡欄位，將於 M4 移除）
 * @param articleUuid  文章公開 UUID
 * @param authorId     作者資料庫主鍵（過渡欄位，將於 M6 移除）
 * @param seriesId     文章所屬 series 主鍵（nullable — 文章不在 series 時為 null；過渡欄位，將於 M4 移除）
 * @param categoryIds  文章被刪前的 category UUIDs（nullable — 舊 message 為 null）
 * @param tagIds       文章被刪前的 tag UUIDs（nullable — 舊 message 為 null）
 * @param occurredAt   事件發生時間
 * @param authorUuid   作者公開 UUID（nullable — 舊 message 或 uuid 查詢失敗時為 null）
 * @param seriesUuid   文章所屬 series 公開 UUID（nullable — 文章不在 series、舊 message 或查詢失敗時為 null）
 *
 * @author Yuan
 * @version 3.0（由 article 模組搬到 blog-infrastructure；新增 authorUuid、seriesUuid）
 */
public record ArticleDeletedEvent(
    UUID eventId,
    Long articleId,
    UUID articleUuid,
    Long authorId,
    Long seriesId,
    List<UUID> categoryIds,
    List<UUID> tagIds,
    Instant occurredAt,
    UUID authorUuid,
    UUID seriesUuid
) {}
