package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.config.UUIDTypeHandler;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleNavRef;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.TagWithArticleUuid;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 文章 MyBatis Mapper
 *
 * <p>
 * 負責複雜查詢，例如分頁列表、篩選與原子性更新。
 * 簡單 CRUD 仍使用 ArticleRepository。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface ArticleMapper {

    /**
     * 分頁查詢已發布文章（公開列表）
     *
     * @param offset 偏移量
     * @param size   每頁筆數
     * @return 文章列表
     */
    @Select("SELECT * FROM articles WHERE status = 'PUBLISHED' ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<Article> findPublishedPage(@Param("offset") long offset, @Param("size") int size);

    /**
     * 計算已發布文章總筆數
     *
     * @return 總筆數
     */
    @Select("SELECT COUNT(*) FROM articles WHERE status = 'PUBLISHED'")
    long countPublished();

    /**
     * 根據作者 ID 與狀態篩選文章
     *
     * @param authorId 作者資料庫主鍵
     * @param status   文章狀態
     * @param offset   偏移量
     * @param size     每頁筆數
     * @return 文章列表
     */
    @Select("SELECT * FROM articles WHERE author_id = #{authorId} AND status = #{status} ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<Article> findByAuthorIdAndStatus(
            @Param("authorId") Long authorId,
            @Param("status") ArticleStatus status,
            @Param("offset") long offset,
            @Param("size") int size);

    /**
     * 根據作者 ID 分頁查詢（不篩選狀態）
     *
     * @param authorId 作者資料庫主鍵
     * @param offset   偏移量
     * @param size     每頁筆數
     * @return 文章列表
     */
    @Select("SELECT * FROM articles WHERE author_id = #{authorId} ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<Article> findByAuthorIdPaged(
            @Param("authorId") Long authorId,
            @Param("offset") long offset,
            @Param("size") int size);

    /**
     * 計算作者的文章總筆數
     *
     * @param authorId 作者資料庫主鍵
     * @return 總筆數
     */
    @Select("SELECT COUNT(*) FROM articles WHERE author_id = #{authorId}")
    long countByAuthorId(@Param("authorId") Long authorId);

    /**
     * 計算作者在特定狀態下的文章總筆數
     *
     * @param authorId 作者資料庫主鍵
     * @param status   文章狀態
     * @return 總筆數
     */
    @Select("SELECT COUNT(*) FROM articles WHERE author_id = #{authorId} AND status = #{status}")
    long countByAuthorIdAndStatus(@Param("authorId") Long authorId, @Param("status") ArticleStatus status);

    /**
     * 分頁查詢待審文章（按提交時間升冪，供管理員審核）
     *
     * @param offset 偏移量
     * @param size   每頁筆數
     * @return 文章列表
     */
    @Select("SELECT * FROM articles WHERE status = 'PENDING_REVIEW' ORDER BY created_at ASC LIMIT #{size} OFFSET #{offset}")
    List<Article> findPendingReviewPage(@Param("offset") long offset, @Param("size") int size);

    /**
     * 計算待審文章總筆數
     *
     * @return 總筆數
     */
    @Select("SELECT COUNT(*) FROM articles WHERE status = 'PENDING_REVIEW'")
    long countPendingReview();

    /**
     * 查詢文章的所有標籤
     *
     * <p>
     * 透過 article_tags 關聯表查詢對應的 tags 資料，
     * 供建立 {@link dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent} 使用。
     * 使用 {@link ConstructorArgs} 明確指定 Record 建構子參數對應，
     * 因 Java Record 無無參建構子，MyBatis 需此提示才能正確映射。
     * V9 migration 後 article_tags.article_id 改為 UUID 型別，
     * 故改以文章公開 UUID 查詢並加上 ::uuid 強制轉型。
     * </p>
     *
     * @param articleUuid 文章公開 UUID
     * @return 標籤資訊列表
     */
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, typeHandler = UUIDTypeHandler.class, name = "id"),
            @Arg(column = "name", javaType = String.class, name = "name"),
            @Arg(column = "slug", javaType = String.class, name = "slug")
    })
    @Select("SELECT t.id, t.name, t.slug FROM tags t " +
            "INNER JOIN article_tags art ON t.id = art.tag_id " +
            "WHERE art.article_id = #{articleUuid}::uuid")
    List<TagInfo> findTagsByArticleUuid(@Param("articleUuid") UUID articleUuid);

    /**
     * 查詢所有已發布文章（供全量重建 Elasticsearch 索引使用）
     *
     * @return 所有已發布文章列表
     */
    @Select("SELECT * FROM articles WHERE status = 'PUBLISHED' ORDER BY published_at DESC")
    List<Article> findAllPublished();

    /**
     * 根據文章公開 UUID 查詢 DB 中儲存的瀏覽計數
     *
     * @param uuid 文章公開 UUID
     * @return 瀏覽計數，若文章不存在則回傳 null
     */
    @Select("SELECT view_count FROM articles WHERE uuid = #{uuid}::uuid")
    Long findViewCountByUuid(@Param("uuid") UUID uuid);

    /**
     * 以批次增量更新文章瀏覽計數（原子性加法）
     *
     * @param uuid  文章公開 UUID
     * @param delta 要增加的數量
     */
    @Update("UPDATE articles SET view_count = view_count + #{delta} WHERE uuid = #{uuid}::uuid")
    void incrementViewCountBatch(@Param("uuid") UUID uuid, @Param("delta") long delta);

    /**
     * 根據分類 slug 分頁查詢已發布文章
     *
     * @param categorySlug 分類 slug
     * @param offset       偏移量
     * @param size         每頁筆數
     * @return 文章列表
     */
    @Select("SELECT a.* FROM articles a " +
            "INNER JOIN article_categories ac ON a.id = ac.article_id " +
            "INNER JOIN categories c ON ac.category_id = c.id " +
            "WHERE a.status = 'PUBLISHED' AND c.slug = #{categorySlug} " +
            "ORDER BY a.created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<Article> findPublishedPageByCategorySlug(
            @Param("categorySlug") String categorySlug,
            @Param("offset") long offset,
            @Param("size") int size);

    /**
     * 根據分類 slug 計算已發布文章總筆數
     *
     * @param categorySlug 分類 slug
     * @return 總筆數
     */
    @Select("SELECT COUNT(DISTINCT a.id) FROM articles a " +
            "INNER JOIN article_categories ac ON a.id = ac.article_id " +
            "INNER JOIN categories c ON ac.category_id = c.id " +
            "WHERE a.status = 'PUBLISHED' AND c.slug = #{categorySlug}")
    long countPublishedByCategorySlug(@Param("categorySlug") String categorySlug);

    /**
     * 批次查詢多篇文章的標籤（含所屬文章 UUID）
     *
     * <p>
     * 供列表場景使用，一次查詢所有文章的標籤，避免 N+1 查詢問題。
     * 使用 PostgreSQL ::uuid 強制轉型以確保 UUID 比對正確。
     * </p>
     *
     * @param articleUuids 文章公開 UUID 列表
     * @return 標籤與文章 UUID 關聯列表
     */
    @Results(id = "tagWithArticleUuidMap", value = {
            @Result(property = "id", column = "id", javaType = UUID.class, typeHandler = UUIDTypeHandler.class),
            @Result(property = "name", column = "name"),
            @Result(property = "slug", column = "slug"),
            @Result(property = "articleUuid", column = "article_uuid", javaType = UUID.class, typeHandler = UUIDTypeHandler.class)
    })
    @Select("<script>" +
            "SELECT t.id, t.name, t.slug, art.article_id AS article_uuid " +
            "FROM tags t " +
            "INNER JOIN article_tags art ON t.id = art.tag_id " +
            "WHERE art.article_id IN " +
            "<foreach collection='list' item='uuid' open='(' separator=',' close=')'>#{uuid}::uuid</foreach>" +
            "</script>")
    List<TagWithArticleUuid> findTagsByArticleUuids(@Param("list") List<UUID> articleUuids);

    /**
     * 原子性遞增文章留言計數
     *
     * @param id 文章資料庫主鍵
     * @return 受影響列數
     */
    @Update("UPDATE articles SET comment_count = comment_count + 1 WHERE id = #{id}")
    int incrementCommentCount(@Param("id") Long id);

    /**
     * 原子性遞減文章留言計數（守衛 > 0，防 underflow）
     *
     * @param id 文章資料庫主鍵
     * @return 受影響列數
     */
    @Update("UPDATE articles SET comment_count = comment_count - 1 WHERE id = #{id} AND comment_count > 0")
    int decrementCommentCount(@Param("id") Long id);

    /**
     * 原子性遞增文章按讚計數
     *
     * @param id 文章資料庫主鍵
     * @return 受影響列數
     */
    @Update("UPDATE articles SET like_count = like_count + 1 WHERE id = #{id}")
    int incrementLikeCount(@Param("id") Long id);

    /**
     * 原子性遞減文章按讚計數（守衛 > 0，防 underflow）
     *
     * @param id 文章資料庫主鍵
     * @return 受影響列數
     */
    @Update("UPDATE articles SET like_count = like_count - 1 WHERE id = #{id} AND like_count > 0")
    int decrementLikeCount(@Param("id") Long id);

    /**
     * 根據文章公開 UUID 查詢資料庫主鍵
     *
     * <p>
     * 供跨模組 Service（如 CommentService、ArticleLikeService）透過 UUID 取得
     * article PK，避免直接 JOIN articles 表造成模組耦合。
     * </p>
     *
     * @param uuid 文章公開 UUID
     * @return 文章資料庫主鍵，若不存在則回傳 null
     */
    @Select("SELECT id FROM articles WHERE uuid = #{uuid}::uuid")
    Long findIdByUuid(@Param("uuid") UUID uuid);

    /**
     * 查詢指定 Series 內的所有文章，按 series_position 升冪排序。
     *
     * @param seriesId Series 資料庫主鍵
     * @return 按 series_position 排序的文章列表
     */
    @Select("SELECT * FROM articles WHERE series_id = #{seriesId} ORDER BY series_position")
    List<Article> findBySeriesIdOrderByPosition(@Param("seriesId") Long seriesId);

    /**
     * 集合述詞：一次算出多個 series 各自的 PUBLISHED 文章數。
     *
     * <p>取代 series 模組原本直讀 articles 的 EXISTS ＋ COUNT 兩個子查詢
     * （ARCH-13 / PERF-34）。GROUP BY 天然只回傳有資料的 series，
     * 故 count = 0 者不出現在結果中，caller 可直接以 key 集合當作過濾條件。</p>
     *
     * <p><b>注意</b>：MyBatis 對 record 建構子採位置對應（見 Ruling F1），
     * SELECT 欄位順序（seriesId, publishedCount）必須與 {@link SeriesPublishedCountRow} 的元件順序一致，不可調整。</p>
     *
     * @param seriesIds series 主鍵集合（不得為空，由 caller 保證）
     * @return 每個有公開文章的 series 及其計數
     */
    @Select({
        "<script>",
        "SELECT series_id AS seriesId, COUNT(*) AS publishedCount",
        "  FROM articles",
        " WHERE status = 'PUBLISHED'",
        "   AND series_id IN",
        "<foreach collection='seriesIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        " GROUP BY series_id",
        "</script>"
    })
    List<SeriesPublishedCountRow> countPublishedBySeriesIds(@Param("seriesIds") Collection<Long> seriesIds);

    /**
     * series 內 PUBLISHED 且 position 小於 current 的最後一筆（prev 導覽）。
     *
     * <p><b>注意</b>：MyBatis 對 record 建構子採位置對應（見 Ruling F1），
     * SELECT 欄位順序（uuid, title, slug）必須與 {@link ArticleNavRef} 的元件順序一致，不可調整。</p>
     *
     * @param seriesId        series 主鍵
     * @param currentPosition 當前文章在 series 內的位置
     * @return 前一篇，若當前為第一篇則回傳 null
     */
    @Select("""
            SELECT uuid, title, slug
              FROM articles
             WHERE series_id = #{seriesId}
               AND status = 'PUBLISHED'
               AND series_position < #{currentPosition}
             ORDER BY series_position DESC
             LIMIT 1
            """)
    ArticleNavRef findPrevPublishedInSeries(@Param("seriesId") Long seriesId,
                                            @Param("currentPosition") Integer currentPosition);

    /**
     * series 內 PUBLISHED 且 position 大於 current 的第一筆（next 導覽）。
     *
     * <p><b>注意</b>：MyBatis 對 record 建構子採位置對應（見 Ruling F1），
     * SELECT 欄位順序（uuid, title, slug）必須與 {@link ArticleNavRef} 的元件順序一致，不可調整。</p>
     *
     * @param seriesId        series 主鍵
     * @param currentPosition 當前文章在 series 內的位置
     * @return 後一篇，若當前為最後一篇則回傳 null
     */
    @Select("""
            SELECT uuid, title, slug
              FROM articles
             WHERE series_id = #{seriesId}
               AND status = 'PUBLISHED'
               AND series_position > #{currentPosition}
             ORDER BY series_position ASC
             LIMIT 1
            """)
    ArticleNavRef findNextPublishedInSeries(@Param("seriesId") Long seriesId,
                                            @Param("currentPosition") Integer currentPosition);

    /**
     * 集合述詞：取回一批文章的可見性判斷所需欄位。
     *
     * <p>只投影 id / status / author_id 三欄，刻意不用 {@code SELECT *}
     * （避免把 content / content_html 等 TEXT 欄位拉進記憶體）。
     * 政策判斷本身在 {@code ArticleFacadeImpl} 委派 {@code ArticleVisibility}，
     * <b>不在 SQL 內重寫</b>——可見性政策必須維持單一真相。</p>
     *
     * <p><b>注意</b>：MyBatis 對 record 建構子採位置對應（見 Ruling F1），
     * SELECT 欄位順序（id, status, authorId）必須與 {@link ArticleVisibilityRow} 的元件順序一致，不可調整。</p>
     *
     * @param articleIds 文章主鍵集合（不得為空，由 caller 保證）
     * @return 可見性判斷所需的欄位列
     */
    @Select({
        "<script>",
        "SELECT id, status, author_id AS authorId",
        "  FROM articles",
        " WHERE id IN",
        "<foreach collection='articleIds' item='id' open='(' separator=',' close=')'>",
        "  #{id}",
        "</foreach>",
        "</script>"
    })
    List<ArticleVisibilityRow> findVisibilityRowsByIds(@Param("articleIds") Collection<Long> articleIds);

    /**
     * {@link #countPublishedBySeriesIds} 的投影列。
     *
     * <p><b>注意</b>：MyBatis 對 record 建構子採位置對應（見 Ruling F1），
     * SELECT 欄位順序（seriesId, publishedCount）必須與此 record 的元件順序一致，不可調整。</p>
     *
     * @param seriesId       series 主鍵
     * @param publishedCount 該 series 的 PUBLISHED 文章數
     */
    record SeriesPublishedCountRow(Long seriesId, Integer publishedCount) {}

    /**
     * {@link #findVisibilityRowsByIds} 的投影列。
     *
     * <p><b>注意</b>：MyBatis 對 record 建構子採位置對應（見 Ruling F1），
     * SELECT 欄位順序（id, status, authorId）必須與此 record 的元件順序一致，不可調整。</p>
     *
     * @param id       文章主鍵
     * @param status   文章狀態名稱
     * @param authorId 作者主鍵
     */
    record ArticleVisibilityRow(Long id, String status, Long authorId) {}

    /**
     * 撈文章對應的 tag UUID 列表（給 ArticleDeletedEvent rich payload 用）。
     *
     * <p>
     * article_tags.article_id 為 UUID（FK → articles.uuid，V9 migration），
     * 故先以 articles.id（BIGINT）查出 articles.uuid，再 JOIN article_tags。
     * 文章被刪前呼叫，因刪除後 ON DELETE CASCADE 會清 article_tags 撈不到。
     * </p>
     *
     * @param articleId 文章資料庫主鍵（BIGINT）
     * @return tag UUID 列表（無 tag 回 emptyList）
     */
    @Select("SELECT t.id FROM tags t " +
            "INNER JOIN article_tags at ON t.id = at.tag_id " +
            "WHERE at.article_id = (SELECT uuid FROM articles WHERE id = #{articleId})::uuid")
    List<UUID> findTagUuidsByArticleId(@Param("articleId") Long articleId);

    /**
     * 撈文章對應的 category UUID 列表（給 ArticleDeletedEvent rich payload 用）。
     *
     * <p>
     * article_categories.article_id 為 BIGINT（FK → articles.id），可直接用主鍵 JOIN。
     * 個人部落格通常一篇文章對應一個 category，但回傳 List 保持 forward-compat。
     * 文章被刪前呼叫，因刪除後 ON DELETE CASCADE 會清 article_categories 撈不到。
     * </p>
     *
     * @param articleId 文章資料庫主鍵（BIGINT）
     * @return category UUID 列表（無 category 回 emptyList）
     */
    @Select("SELECT c.uuid FROM categories c " +
            "INNER JOIN article_categories ac ON c.id = ac.category_id " +
            "WHERE ac.article_id = #{articleId}")
    List<UUID> findCategoryUuidsByArticleId(@Param("articleId") Long articleId);

    /**
     * 批次查詢文章 UUID → DB 主鍵對應關係。
     *
     * <p>
     * 供 ArticleQueryService 在列表頁 enrichLiked 時，一次查詢所有文章 id，
     * 避免 N+1 問題。
     * </p>
     *
     * @param uuids 文章公開 UUID 列表
     * @return uuid → id/seriesId 對應結果
     */
    @Results(id = "uuidToIdMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "uuid", column = "uuid", javaType = UUID.class, typeHandler = UUIDTypeHandler.class),
            @Result(property = "seriesId", column = "series_id")
    })
    @Select("<script>" +
            "SELECT id, uuid, series_id FROM articles " +
            "WHERE uuid IN " +
            "<foreach collection='list' item='uuid' open='(' separator=',' close=')'>#{uuid}::uuid</foreach>" +
            "</script>")
    List<Article> findIdsByUuids(@Param("list") List<UUID> uuids);

    /**
     * 在給定的 UUID 中篩出「目前確實是 PUBLISHED」的文章
     *
     * <p>
     * 供搜尋模組清除幽靈 document 用：判準必須是**查詢當下的 DB 狀態**，
     * 不能是全量重建開始時的快照，否則快照之後才發布的文章會被誤判為幽靈而刪除。
     * 回傳型別採 {@code String}（{@code uuid::text}），與
     * {@code ArticleRecommendMapper#findTagIdsByArticleUuid} 一致，
     * 由 caller 轉回 {@link UUID}。
     * </p>
     *
     * @param uuids 待查證的文章公開 UUID（caller 須保證非空，避免 {@code IN ()} 語法錯誤）
     * @return 其中狀態為 PUBLISHED 的文章 UUID 字串列表
     */
    @Select("<script>" +
            "SELECT uuid::text AS uuid FROM articles " +
            "WHERE status = 'PUBLISHED' AND uuid IN " +
            "<foreach collection='list' item='uuid' open='(' separator=',' close=')'>#{uuid}::uuid</foreach>" +
            "</script>")
    List<String> findPublishedUuidsIn(@Param("list") List<UUID> uuids);
}
