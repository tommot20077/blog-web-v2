package dowob.xyz.blog.module.tag.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * 文章標籤關聯實體
 *
 * <p>
 * 對應資料庫 {@code article_tags} 中間表，
 * 記錄文章與標籤的多對多關聯關係。
 * 以 article_id 作為主鍵供 Spring Data JDBC 識別，
 * 實際查詢皆透過 {@link dowob.xyz.blog.module.tag.repository.ArticleTagRepository} 的 @Query 方法執行。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Table("article_tags")
public class ArticleTag {

    /**
     * 文章 ID（作為 Spring Data JDBC 識別用主鍵）
     */
    @Id
    @Column("article_id")
    private UUID articleId;

    /**
     * 標籤 ID
     */
    @Column("tag_id")
    private UUID tagId;
}
