package dowob.xyz.blog.module.article.model;

import lombok.Data;

/**
 * 文章標籤對應查詢結果
 *
 * <p>
 * 供 ArticleRecommendMapper.findTagsByArticleUuids() 使用，
 * 用於批次取得文章的標籤名稱。
 * articleUuid 以 String 映射避免 MyBatis UUID TypeHandler 問題。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class ArticleTagRow {

    /**
     * 文章公開 UUID（以 text 映射，對應 article_tags.article_id::text）
     */
    private String articleUuid;

    /**
     * 標籤名稱
     */
    private String tagName;
}
