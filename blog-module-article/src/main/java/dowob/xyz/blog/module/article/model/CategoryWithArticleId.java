package dowob.xyz.blog.module.article.model;

import lombok.Data;

import java.util.UUID;

/**
 * 帶有 article_id 的分類批次查詢結果
 *
 * <p>
 * 用於批次查詢多篇文章的分類，避免 N+1 問題。
 * MyBatis map-underscore-to-camel-case 自動映射：
 * sort_order → sortOrder, article_id → articleId
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class CategoryWithArticleId {

    /** 分類資料庫主鍵 */
    private Long id;

    /** 分類對外公開 UUID */
    private UUID uuid;

    /** 分類名稱 */
    private String name;

    /** URL slug */
    private String slug;

    /** 分類描述 */
    private String description;

    /** 排序權重 */
    private int sortOrder;

    /** 對應的文章 ID（JOIN article_categories 取得） */
    private Long articleId;
}
