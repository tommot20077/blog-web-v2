package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleTrendingData;
import dowob.xyz.blog.module.article.model.ArticleSummaryRow;
import dowob.xyz.blog.module.article.model.ArticleTagRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 推薦功能專用 MyBatis Mapper
 *
 * <p>
 * 包含推薦演算法所需的複雜查詢，與 ArticleMapper 分離以保持職責單一。
 * 涉及 IN 子句的動態 SQL 使用 XML 映射定義。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface ArticleRecommendMapper {

    /**
     * 確認文章是否已發布（用於 getPublishedArticleBasicInfo 前置檢查）
     *
     * @param uuid 文章公開 UUID
     * @return 文章 DB 主鍵，若不存在或未發布則回傳 null
     */
    @Select("SELECT id FROM articles WHERE uuid = #{uuid}::uuid AND status = 'PUBLISHED'")
    Long findPublishedIdByUuid(@Param("uuid") UUID uuid);

    /**
     * 查詢已發布文章的所有標籤 ID
     *
     * @param articleId 文章 DB 主鍵
     * @return 標籤 ID 列表
     */
    @Select("SELECT tag_id FROM article_tags WHERE article_id = #{articleId} ORDER BY tag_id")
    List<Long> findTagIdsByArticleId(@Param("articleId") Long articleId);

    /**
     * 查詢包含指定標籤的已發布文章（依瀏覽次數降冪）
     *
     * <p>使用 XML mapper 處理 IN 子句動態 SQL。</p>
     *
     * @param tagIds      標籤 ID 列表
     * @param excludeUuid 排除的文章 UUID
     * @param limit       最多回傳筆數
     * @return 文章摘要原始資料列表
     */
    List<ArticleSummaryRow> findByTagIds(
            @Param("tagIds") List<Long> tagIds,
            @Param("excludeUuid") UUID excludeUuid,
            @Param("limit") int limit);

    /**
     * 查詢最新已發布文章（依發布時間降冪）
     *
     * @param excludeUuid 排除的文章 UUID
     * @param limit       最多回傳筆數
     * @return 文章摘要原始資料列表
     */
    @Select("""
            SELECT a.id, a.uuid, a.title, a.slug, a.summary,
                   u.nickname AS authorNickname,
                   a.view_count AS viewCount, a.like_count AS likeCount,
                   a.published_at AS publishedAt
            FROM articles a
            JOIN users u ON a.author_id = u.id
            WHERE a.uuid != #{excludeUuid}::uuid
              AND a.status = 'PUBLISHED'
            ORDER BY a.published_at DESC
            LIMIT #{limit}
            """)
    List<ArticleSummaryRow> findRecentPublished(
            @Param("excludeUuid") UUID excludeUuid,
            @Param("limit") int limit);

    /**
     * 批次查詢指定 UUID 的已發布文章
     *
     * <p>使用 XML mapper 處理 IN 子句動態 SQL。</p>
     *
     * @param uuids 文章 UUID 列表
     * @return 文章摘要原始資料列表
     */
    List<ArticleSummaryRow> findByUuids(@Param("uuids") List<UUID> uuids);

    /**
     * 查詢指定時間之後發布的所有文章統計資料
     *
     * @param since 起始時間（含）
     * @return 文章熱門計算資料列表
     */
    @Select("""
            SELECT a.uuid, a.view_count AS viewCount,
                   a.like_count AS likeCount, a.published_at AS publishedAt
            FROM articles a
            WHERE a.published_at >= #{since}
              AND a.status = 'PUBLISHED'
            """)
    List<ArticleTrendingData> findPublishedAfter(@Param("since") LocalDateTime since);

    /**
     * 批次查詢文章的標籤名稱（用於組裝 ArticleSummaryInfo.tagNames）
     *
     * <p>使用 XML mapper 處理 IN 子句動態 SQL。</p>
     *
     * @param articleIds 文章 DB 主鍵列表
     * @return 文章標籤對應資料列表
     */
    List<ArticleTagRow> findTagsByArticleIds(@Param("articleIds") List<Long> articleIds);
}
