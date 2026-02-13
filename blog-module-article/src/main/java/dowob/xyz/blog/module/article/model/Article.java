package dowob.xyz.blog.module.article.model;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文章實體
 *
 * <p>
 * 對應資料庫 articles 表，封裝部落格文章的完整資料。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Table("articles")
public class Article {

    /**
     * 資料庫主鍵（內部使用）
     */
    @Id
    private Long id;

    /**
     * 對外公開的 UUID 識別碼
     */
    private UUID uuid;

    /**
     * 作者的資料庫 ID（FK → users.id）
     */
    @Column("author_id")
    private Long authorId;

    /**
     * 文章標題
     */
    private String title;

    /**
     * URL slug（用於 SEO 友善網址，由 Service 層自動生成）
     */
    private String slug;

    /**
     * 文章摘要（可選）
     */
    private String summary;

    /**
     * 文章 Markdown 原文
     */
    @Column("content_md")
    private String content;

    /**
     * 封面圖片 URL（預留欄位，Phase 3 實作）
     */
    @Column("cover_image_url")
    private String coverImageUrl;

    /**
     * 文章狀態
     */
    private ArticleStatus status;

    /**
     * 瀏覽次數
     */
    @Column("view_count")
    private long viewCount;

    /**
     * 按讚次數
     */
    @Column("like_count")
    private long likeCount;

    /**
     * 留言數量
     */
    @Column("comment_count")
    private int commentCount;

    /**
     * 發布時間（發布後由 Service 設定）
     */
    @Column("published_at")
    private LocalDateTime publishedAt;

    /**
     * 建立時間
     */
    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    /**
     * 最後更新時間
     */
    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;
}
