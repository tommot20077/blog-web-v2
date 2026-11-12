package dowob.xyz.blog.module.article.model;

import lombok.Data;

import java.util.UUID;

/**
 * 批次查詢標籤結果（含所屬文章 UUID）
 *
 * <p>
 * 供批次查詢 {@code findTagsByArticleUuids} 使用，
 * 攜帶 articleUuid 以便在 Service 層依文章分組。
 * 不是 Spring Data 實體，無 @Table 標註。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class TagWithArticleUuid {

    /**
     * 標籤公開 UUID
     */
    private UUID id;

    /**
     * 標籤名稱
     */
    private String name;

    /**
     * 標籤 URL slug
     */
    private String slug;

    /**
     * 所屬文章公開 UUID
     */
    private UUID articleUuid;
}
