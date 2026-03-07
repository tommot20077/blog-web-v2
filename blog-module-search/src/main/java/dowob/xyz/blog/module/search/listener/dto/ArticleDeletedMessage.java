package dowob.xyz.blog.module.search.listener.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 文章刪除訊息（搜尋模組消費者視角）
 *
 * <p>
 * 對應 {@code blog-module-article} 的 {@code ArticleDeletedEvent} JSON 結構，
 * 搜尋模組自定義反序列化 DTO，不直接依賴 article 模組的類別。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
public class ArticleDeletedMessage {

    /**
     * 文章公開 UUID
     */
    private UUID articleUuid;

    /**
     * 刪除時間戳
     */
    private Instant deletedAt;
}
