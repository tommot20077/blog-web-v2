package dowob.xyz.blog.module.search.listener.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 文章下架訊息（搜尋模組消費者視角）
 *
 * <p>
 * 對應 {@code blog-module-article} 的 {@code ArticleArchivedEvent} JSON 結構，
 * 搜尋模組自定義反序列化 DTO，不直接依賴 article 模組的類別
 * （與 {@link ArticleDeletedMessage} 同一慣例）。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
public class ArticleArchivedMessage {

    /**
     * 事件 dedup key（producer 端產生）
     */
    private UUID eventId;

    /**
     * 文章公開 UUID（即 Elasticsearch document id）
     */
    private UUID articleUuid;

    /**
     * 下架時間戳
     */
    private Instant occurredAt;
}
