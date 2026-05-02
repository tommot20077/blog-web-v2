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
