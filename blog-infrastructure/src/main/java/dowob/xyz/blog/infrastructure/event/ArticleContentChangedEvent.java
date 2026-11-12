package dowob.xyz.blog.infrastructure.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 文章內容變更事件（跨模組共用契約；輕量 marker，給 version 模組訂閱觸發快照）。
 *
 * <p>故意設計輕量 payload — consumer 自己用 articleRepository 拉完整 article。
 * 對齊 ArticleViewedEvent 慣例。</p>
 *
 * <p><b>M3-b：舊 Long 欄位已移除</b>。原本保留的 {@code articleId}、{@code authorId} 兩個 Long 欄位，
 * 唯一的讀取者是 version 模組的 consumer；該 consumer 於 M3-b 改讀 {@code articleUuid} 後即無人讀取，依實作計畫 §4.2
 * 步驟 7（最後一個讀取者改造時移除）一併自事件刪除，發送端（article）不再填入。</p>
 *
 * <p>Backward compatibility：升級前的 in-flight 舊 message 沒有 {@code eventId}／{@code authorUuid}，
 * Jackson 反序列化為 null，consumer 須自行防禦；仍含 {@code articleId}／{@code authorId} 的舊 message
 * 反序列化時多餘欄位被忽略（Spring AMQP 的 Jackson converter 不因未知欄位失敗）。</p>
 *
 * @param eventId     事件 dedup key（producer 每次 publish 時 random gen；舊訊息為 null）
 * @param articleUuid 文章公開 UUID
 * @param authorUuid  作者公開 UUID（nullable — 舊 message 或 uuid 查詢失敗時為 null）
 * @param action      觸發動作（SAVED / PUBLISHED / RESTORED）
 * @param occurredAt  事件發生時間
 *
 * @author Yuan
 * @version 3.0（M3-b：移除 articleId、authorId 舊 Long 欄位；2.0 由 article 模組搬到 blog-infrastructure，新增 eventId、authorUuid）
 */
public record ArticleContentChangedEvent(
    UUID eventId,
    UUID articleUuid,
    UUID authorUuid,
    Action action,
    Instant occurredAt
) {
    /** 內容變更的觸發動作 */
    public enum Action {
        /** 任何 update（draft 或 published 都發） */
        SAVED,
        /** publish 動作（同時也發既有的 ArticlePublishedEvent） */
        PUBLISHED,
        /** restore 完成（version 模組訂閱時 no-op，但 search 訂同 article.updated 重 index） */
        RESTORED
    }
}
