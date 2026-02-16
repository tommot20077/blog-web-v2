package dowob.xyz.blog.module.search.listener.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文章發布訊息（搜尋模組消費者視角）
 *
 * <p>
 * 對應 {@code blog-module-article} 的 {@code ArticlePublishedEvent} JSON 結構，
 * 搜尋模組自定義反序列化 DTO，不直接依賴 article 模組的類別，
 * 以保持模組間低耦合。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
public class ArticlePublishedMessage {

    /**
     * 文章公開 UUID
     */
    private UUID articleUuid;

    /**
     * 作者資料庫主鍵
     */
    private Long authorId;

    /**
     * 文章標題
     */
    private String title;

    /**
     * 發布時間
     */
    private LocalDateTime publishedAt;

    /**
     * URL slug
     */
    private String slug;

    /**
     * 文章摘要
     */
    private String summary;

    /**
     * Markdown 去格式後的純文字
     */
    private String contentText;

    /**
     * 作者帳號名稱
     */
    private String authorUsername;

    /**
     * 作者暱稱
     */
    private String authorNickname;

    /**
     * 文章標籤列表
     */
    private List<TagInfoMessage> tags;

    /**
     * 標籤資訊（對應 article 模組的 TagInfo）
     */
    @Data
    @NoArgsConstructor
    public static class TagInfoMessage {

        /**
         * 標籤資料庫主鍵
         */
        private Long id;

        /**
         * 標籤名稱
         */
        private String name;

        /**
         * 標籤 URL slug
         */
        private String slug;
    }
}
