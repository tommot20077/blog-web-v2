package dowob.xyz.blog.infrastructure.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 文章內容變更事件（跨模組共用契約；輕量 marker，給 version 模組訂閱觸發快照）。
 *
 * <p>故意設計輕量 payload — consumer 自己用 articleRepository 拉完整 article。
 * 對齊 ArticleViewedEvent 慣例。</p>
 *
 * <p><b>過渡欄位（只增不減）</b>：{@code articleId}、{@code authorId} 兩個 Long 欄位保留，
 * version 模組的 consumer 仍讀 {@code articleId}；發送端同時填入 {@code authorUuid}（articleUuid 本來就有）。
 * Long 欄位將於 M3（version 階段）移除，屆時 consumer 改讀 {@code articleUuid}。</p>
 *
 * <p>Backward compatibility：升級前的 in-flight 舊 message 沒有 {@code eventId}／{@code authorUuid}，
 * Jackson 反序列化為 null，consumer 須自行防禦。</p>
 *
 * @param eventId     事件 dedup key（producer 每次 publish 時 random gen；舊訊息為 null）
 * @param articleId   文章資料庫主鍵（過渡欄位，將於 M3 移除）
 * @param articleUuid 文章公開 UUID
 * @param authorId    作者資料庫主鍵（過渡欄位，將於 M3 移除）
 * @param authorUuid  作者公開 UUID（nullable — 舊 message 或 uuid 查詢失敗時為 null）
 * @param action      觸發動作（SAVED / PUBLISHED / RESTORED）
 * @param occurredAt  事件發生時間
 *
 * @author Yuan
 * @version 2.0（由 article 模組搬到 blog-infrastructure；新增 eventId、authorUuid）
 */
public record ArticleContentChangedEvent(
    UUID eventId,
    Long articleId,
    UUID articleUuid,
    Long authorId,
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
