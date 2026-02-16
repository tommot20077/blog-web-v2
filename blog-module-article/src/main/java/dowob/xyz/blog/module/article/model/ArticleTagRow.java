package dowob.xyz.blog.module.article.model;

import lombok.Data;

/**
 * 文章標籤對應查詢結果
 *
 * <p>
 * 供 ArticleRecommendMapper.findTagsByArticleIds() 使用，
 * 用於批次取得文章的標籤名稱。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class ArticleTagRow {

    /**
     * 文章資料庫主鍵
     */
    private Long articleId;

    /**
     * 標籤名稱
     */
    private String tagName;
}
