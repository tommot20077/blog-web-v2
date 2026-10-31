package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.config.UUIDTypeHandler;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleNavRef;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.PublishedArticleCriteria;
import dowob.xyz.blog.module.article.model.TagWithArticleUuid;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
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
     * 公開列表的篩選條件（WHERE 子句），由列表查詢與 count 查詢共用。
     *
     * <p>抽成常數是為了讓兩條查詢<b>不可能</b>篩選條件不一致——不一致時 total 與實際筆數對不上，
     * 前端分頁器會多出空頁或少掉最後幾頁。annotation 值須為編譯期常數，
     * 介面常數的字串串接符合此要求。</p>
     *
     * <ul>
     *   <li>分類（OR）：{@code EXISTS} 子查詢而非 JOIN，文章同時屬於多個被選分類時不會重複出現。</li>
     *   <li>標籤（AND）：{@code HAVING COUNT(DISTINCT t.id) = tagCount}，文章須帶有全部被選標籤；
     *       任一 slug 不存在即無結果，符合 AND 語意。屬跨批聚合，故參數數量不切批，
     *       而由 {@code PublishedArticleCriteria#of} 以 {@code ArticleListQuery.MAX_VALUES_PER_FILTER} 為上限、超過即拒絕。</li>
     *   <li>作者（OR）：直接比對 {@code articles.author_uuid}（M6-a 起作者以 UUID 儲存，不再反查 users）。</li>
     *   <li>日期：{@code published_at >= publishedAfter}，時間點由呼叫端以 JVM 時區換算
     *       （見 {@link PublishedArticleCriteria}）。</li>
     * </ul>
     */
    String PUBLISHED_CRITERIA_WHERE =
            "WHERE a.status = 'PUBLISHED' " +
            "<if test='criteria.categorySlugs.size() > 0'>" +
            "AND EXISTS (SELECT 1 FROM article_categories ac " +
            "INNER JOIN categories c ON c.id = ac.category_id " +
            "WHERE ac.article_id = a.id AND c.slug IN " +
            "<foreach collection='criteria.categorySlugs' item='slug' open='(' separator=',' close=')'>#{slug}</foreach>" +
            ") " +
            "</if>" +
            "<if test='criteria.tagCount > 0'>" +
            "AND a.uuid IN (SELECT art.article_id FROM article_tags art " +
            "INNER JOIN tags t ON t.id = art.tag_id " +
            "WHERE t.slug IN " +
            "<foreach collection='criteria.tagSlugs' item='slug' open='(' separator=',' close=')'>#{slug}</foreach> " +
            "GROUP BY art.article_id HAVING COUNT(DISTINCT t.id) = #{criteria.tagCount}) " +
            "</if>" +
            "<if test='criteria.authorUuids.size() > 0'>" +
            "AND a.author_uuid IN " +
            "<foreach collection='criteria.authorUuids' item='authorUuid' open='(' separator=',' close=')'>#{authorUuid}::uuid</foreach>" +
            " " +
            "</if>" +
            "<if test='criteria.publishedAfter != null'>" +
            "AND a.published_at >= #{criteria.publishedAfter} " +
            "</if>";

    /**
     * 公開列表的排序子句。
     *
     * <p>每種排序都以 {@code a.id DESC} 收尾作為 tie-breaker：排序鍵相同時若無 tie-breaker，
     * PostgreSQL 不保證 {@code LIMIT/OFFSET} 跨頁穩定，同一篇可能在兩頁重複出現或整篇消失。
     * tie-breaker 保證的是「同一份資料快照下順序完全確定」；資料若在翻頁之間變動
     * （新文章發布、{@code ViewCountFlushJob} 回寫瀏覽數、留言增減），OFFSET 分頁仍可能重複或遺漏，
     * 要消除需改為 keyset 分頁。</p>
     *
     * <p>以 {@code <choose>} 選擇固定字面值而非 {@code ${}} 字串替換，排序鍵無從注入 SQL。
     * {@code latest} 用 {@code NULLS LAST}，與 V22 的 partial index 定義一致。</p>
     */
    String PUBLISHED_CRITERIA_ORDER_BY =
            "ORDER BY " +
            "<choose>" +
            "<when test=\"criteria.sortKey == 'popular'\">a.view_count DESC, a.id DESC</when>" +
            "<when test=\"criteria.sortKey == 'commented'\">a.comment_count DESC, a.id DESC</when>" +
            "<otherwise>a.published_at DESC NULLS LAST, a.id DESC</otherwise>" +
            "</choose> ";

    /**
     * 依篩選條件分頁查詢已發布文章（公開列表）
     *
     * @param criteria 篩選與排序條件
     * @param offset   偏移量
     * @param size     每頁筆數
     * @return 文章列表，依條件指定的排序
     */
    @Select("<script>SELECT a.* FROM articles a " + PUBLISHED_CRITERIA_WHERE + PUBLISHED_CRITERIA_ORDER_BY +
            "LIMIT #{size} OFFSET #{offset}</script>")
    List<Article> findPublishedPageByCriteria(@Param("criteria") PublishedArticleCriteria criteria,
                                              @Param("offset") long offset,
                                              @Param("size") int size);

    /**
     * 依篩選條件計算已發布文章總筆數（與 {@link #findPublishedPageByCriteria} 共用同一 WHERE 子句）
     *
     * @param criteria 篩選條件（排序鍵不影響本查詢）
     * @return 符合條件的總筆數
     */
    @Select("<script>SELECT COUNT(*) FROM articles a " + PUBLISHED_CRITERIA_WHERE + "</script>")
    long countPublishedByCriteria(@Param("criteria") PublishedArticleCriteria criteria);

    /**
     * 根據作者公開 UUID 與狀態篩選文章
     *
     * @param authorUuid 作者公開 UUID
     * @param status     文章狀態
     * @param offset     偏移量
     * @param size       每頁筆數
     * @return 文章列表
     */
    @Select("SELECT * FROM articles WHERE author_uuid = #{authorUuid}::uuid AND status = #{status} ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<Article> findByAuthorUuidAndStatus(
            @Param("authorUuid") UUID authorUuid,
            @Param("status") ArticleStatus status,
            @Param("offset") long offset,
            @Param("size") int size);

    /**
     * 根據作者公開 UUID 分頁查詢（不篩選狀態）
     *
     * @param authorUuid 作者公開 UUID
     * @param offset     偏移量
     * @param size       每頁筆數
     * @return 文章列表
     */
    @Select("SELECT * FROM articles WHERE author_uuid = #{authorUuid}::uuid ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<Article> findByAuthorUuidPaged(
            @Param("authorUuid") UUID authorUuid,
            @Param("offset") long offset,
            @Param("size") int size);

    /**
     * 計算作者的文章總筆數
     *
     * @param authorUuid 作者公開 UUID
     * @return 總筆數
     */
    @Select("SELECT COUNT(*) FROM articles WHERE author_uuid = #{authorUuid}::uuid")
    long countByAuthorUuid(@Param("authorUuid") UUID authorUuid);

    /**
     * 計算作者在特定狀態下的文章總筆數
     *
     * @param authorUuid 作者公開 UUID
     * @param status     文章狀態
     * @return 總筆數
     */
    @Select("SELECT COUNT(*) FROM articles WHERE author_uuid = #{authorUuid}::uuid AND status = #{status}")
    long countByAuthorUuidAndStatus(@Param("authorUuid") UUID authorUuid, @Param("status") ArticleStatus status);

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
     * 查詢指定 Series 內的所有文章（不限狀態），按 series_position 升冪排序。
     *
     * <p>以 {@code articles.series_uuid} 直接查詢（M6-b；V32 起舊的 Long 系列欄位已移除）。
     * 走 partial index {@code idx_articles_series_position (series_uuid, series_position)}。</p>
     *
     * @param seriesUuid Series 公開 UUID
     * @return 按 series_position 排序的文章列表；系列沒有成員（含系列不存在）時為空
     */
    @Select("SELECT * FROM articles WHERE series_uuid = #{seriesUuid}::uuid ORDER BY series_position")
    List<Article> findBySeriesUuidOrderByPosition(@Param("seriesUuid") UUID seriesUuid);

    /**
     * 條件式加入 series：僅當文章目前不屬於任何 series（{@code series_uuid IS NULL}）時才寫入。
     *
     * <p>把「是否為新成員」的判斷放進寫入本身（RACE-14）：同一時間只有一個交易能把 NULL 改為非 NULL，
     * 另一個交易（READ COMMITTED 下等鎖、重評 WHERE）得到 affected = 0。
     * 比照實體 save 的效果遞增樂觀鎖 {@code version} 並更新 {@code updated_at}；
     * 時間由 Java 傳入（與 {@code @LastModifiedDate} 同一個時鐘來源，不用資料庫的 CURRENT_TIMESTAMP）。
     * 不檢查系列是否存在（V32 起沒有 FK，系列存在性由呼叫端 series 模組負責）。</p>
     *
     * @param uuid       文章公開 UUID
     * @param seriesUuid 要加入的 series 公開 UUID
     * @param position   在 series 內的位置
     * @param now        寫入的 updated_at（JVM 時間）
     * @return 受影響列數：1 表示指派成功，0 表示文章不存在或已屬於某個 series
     */
    @Update("UPDATE articles SET series_uuid = #{seriesUuid}::uuid, series_position = #{position}, "
            + "version = version + 1, updated_at = #{now} "
            + "WHERE uuid = #{uuid}::uuid AND series_uuid IS NULL")
    int assignToSeriesIfUnassigned(@Param("uuid") UUID uuid,
                                   @Param("seriesUuid") UUID seriesUuid,
                                   @Param("position") Integer position,
                                   @Param("now") LocalDateTime now);

    /**
     * 條件式移出 series：僅當文章目前屬於指定的 series（{@code series_uuid = #{seriesUuid}}）時才清空。
     *
     * <p>與 {@link #assignToSeriesIfUnassigned} 對稱；affected = 1 才代表這次呼叫真的移出了文章。</p>
     *
     * @param uuid       文章公開 UUID
     * @param seriesUuid 文章應屬於的 series 公開 UUID
     * @param now        寫入的 updated_at（JVM 時間）
     * @return 受影響列數：1 表示移出成功，0 表示文章不存在或不屬於該 series
     */
    @Update("UPDATE articles SET series_uuid = NULL, series_position = NULL, "
            + "version = version + 1, updated_at = #{now} "
            + "WHERE uuid = #{uuid}::uuid AND series_uuid = #{seriesUuid}::uuid")
    int removeFromSeriesIfAssigned(@Param("uuid") UUID uuid, @Param("seriesUuid") UUID seriesUuid,
                                   @Param("now") LocalDateTime now);

    /**
     * 條件式更新位置：僅當文章目前屬於指定的 series 時才改 {@code series_position}。
     *
     * <p>取代「讀文章、改欄位、整列 save」的讀改寫：那條路徑在兩個請求同時更新位置時會因樂觀鎖衝突回 500，
     * 且與併發移出交錯時可能把文章重新寫回 series 卻沒有 +1 計數（RACE-14）。本語句只動
     * {@code series_position}，WHERE 條件保證「文章仍屬於該 series」與寫入在同一個原子步驟內判定；
     * 同樣遞增 {@code version} 並更新 {@code updated_at}。</p>
     *
     * @param uuid       文章公開 UUID
     * @param seriesUuid 文章應屬於的 series 公開 UUID
     * @param position   新的位置
     * @param now        寫入的 updated_at（JVM 時間）
     * @return 受影響列數：1 表示位置已更新，0 表示文章不存在或目前不屬於該 series（已被移出或移到別處）
     */
    @Update("UPDATE articles SET series_position = #{position}, version = version + 1, updated_at = #{now} "
            + "WHERE uuid = #{uuid}::uuid AND series_uuid = #{seriesUuid}::uuid")
    int updateSeriesPositionIfAssigned(@Param("uuid") UUID uuid, @Param("seriesUuid") UUID seriesUuid,
                                       @Param("position") Integer position, @Param("now") LocalDateTime now);

    /**
     * 集合述詞：一次算出多個 series 各自的 PUBLISHED 文章數。
     *
     * <p>取代 series 模組原本直讀 articles 的 EXISTS ＋ COUNT 兩個子查詢
     * （ARCH-13 / PERF-34）。GROUP BY 天然只回傳有資料的 series，
     * 故 count = 0 者不出現在結果中，caller 可直接以 key 集合當作過濾條件。</p>
     *
     * <p><b>注意</b>：MyBatis 對 record 建構子採位置對應（見 Ruling F1），
     * SELECT 欄位順序（seriesUuid, publishedCount）必須與 {@link SeriesPublishedCountRow} 的元件順序一致，不可調整。</p>
     *
     * @param seriesUuids series 公開 UUID 集合（不得為空，由 caller 保證）
     * @return 每個有公開文章的 series 及其計數
     */
    @Select({
        "<script>",
        "SELECT series_uuid AS seriesUuid, COUNT(*) AS publishedCount",
        "  FROM articles",
        " WHERE status = 'PUBLISHED'",
        "   AND series_uuid IN",
        "<foreach collection='seriesUuids' item='uuid' open='(' separator=',' close=')'>",
        "  #{uuid}::uuid",
        "</foreach>",
        " GROUP BY series_uuid",
        "</script>"
    })
    List<SeriesPublishedCountRow> countPublishedBySeriesUuids(@Param("seriesUuids") Collection<UUID> seriesUuids);

    /**
     * series 內 PUBLISHED 且 position 小於 current 的最後一筆（prev 導覽）。
     *
     * <p><b>注意</b>：MyBatis 對 record 建構子採位置對應（見 Ruling F1），
     * SELECT 欄位順序（uuid, title, slug）必須與 {@link ArticleNavRef} 的元件順序一致，不可調整。</p>
     *
     * @param seriesUuid      series 公開 UUID
     * @param currentPosition 當前文章在 series 內的位置
     * @return 前一篇，若當前為第一篇則回傳 null
     */
    @Select("""
            SELECT uuid, title, slug
              FROM articles
             WHERE series_uuid = #{seriesUuid}::uuid
               AND status = 'PUBLISHED'
               AND series_position < #{currentPosition}
             ORDER BY series_position DESC
             LIMIT 1
            """)
    ArticleNavRef findPrevPublishedInSeries(@Param("seriesUuid") UUID seriesUuid,
                                            @Param("currentPosition") Integer currentPosition);

    /**
     * series 內 PUBLISHED 且 position 大於 current 的第一筆（next 導覽）。
     *
     * <p><b>注意</b>：MyBatis 對 record 建構子採位置對應（見 Ruling F1），
     * SELECT 欄位順序（uuid, title, slug）必須與 {@link ArticleNavRef} 的元件順序一致，不可調整。</p>
     *
     * @param seriesUuid      series 公開 UUID
     * @param currentPosition 當前文章在 series 內的位置
     * @return 後一篇，若當前為最後一篇則回傳 null
     */
    @Select("""
            SELECT uuid, title, slug
              FROM articles
             WHERE series_uuid = #{seriesUuid}::uuid
               AND status = 'PUBLISHED'
               AND series_position > #{currentPosition}
             ORDER BY series_position ASC
             LIMIT 1
            """)
    ArticleNavRef findNextPublishedInSeries(@Param("seriesUuid") UUID seriesUuid,
                                            @Param("currentPosition") Integer currentPosition);

    /**
     * {@link #countPublishedBySeriesUuids} 的投影列。
     *
     * <p><b>注意</b>：MyBatis 對 record 建構子採位置對應（見 Ruling F1），
     * SELECT 欄位順序（seriesUuid, publishedCount）必須與此 record 的元件順序一致，不可調整。</p>
     *
     * @param seriesUuid     series 公開 UUID
     * @param publishedCount 該 series 的 PUBLISHED 文章數
     */
    record SeriesPublishedCountRow(UUID seriesUuid, Integer publishedCount) {}

    /**
     * 集合述詞（UUID 版）：取回一批文章的可見性判斷所需欄位。
     *
     * <p>只投影 uuid / status / 作者 uuid 三欄（不拉 TEXT 內容欄位）。作者 uuid 直接取自
     * {@code articles.author_uuid}（M6-a 起不再關聯 users 表）。
     * 政策判斷在 {@code ArticleFacadeImpl} 委派 {@code ArticleVisibility}，<b>不在 SQL 內重寫</b>。</p>
     *
     * <p><b>注意</b>：MyBatis 對 record 建構子採位置對應（見 Ruling F1），
     * SELECT 欄位順序（uuid, status, authorUuid）必須與 {@link ArticleUuidVisibilityRow} 的元件順序一致，不可調整。</p>
     *
     * @param articleUuids 文章公開 UUID 集合（不得為空，由 caller 保證）
     * @return 可見性判斷所需的欄位列
     */
    @Select({
        "<script>",
        "SELECT a.uuid AS uuid, a.status AS status, a.author_uuid AS authorUuid",
        "  FROM articles a",
        " WHERE a.uuid IN",
        "<foreach collection='articleUuids' item='articleUuid' open='(' separator=',' close=')'>",
        "  #{articleUuid}::uuid",
        "</foreach>",
        "</script>"
    })
    List<ArticleUuidVisibilityRow> findVisibilityRowsByUuids(@Param("articleUuids") Collection<UUID> articleUuids);

    /**
     * 以公開 UUID 原子性遞增文章按讚計數。
     *
     * @param uuid 文章公開 UUID
     * @return 受影響列數（文章不存在為 0）
     */
    @Update("UPDATE articles SET like_count = like_count + 1 WHERE uuid = #{uuid}::uuid")
    int incrementLikeCountByUuid(@Param("uuid") UUID uuid);

    /**
     * 以公開 UUID 原子性遞減文章按讚計數，守衛 {@code > 0} 防 underflow。
     *
     * @param uuid 文章公開 UUID
     * @return 受影響列數（文章不存在或計數已為 0 時為 0）
     */
    @Update("UPDATE articles SET like_count = like_count - 1 WHERE uuid = #{uuid}::uuid AND like_count > 0")
    int decrementLikeCountByUuid(@Param("uuid") UUID uuid);

    /**
     * {@link #findVisibilityRowsByUuids} 的投影列。
     *
     * @param uuid       文章公開 UUID
     * @param status     文章狀態名稱
     * @param authorUuid 作者公開 UUID
     */
    record ArticleUuidVisibilityRow(UUID uuid, String status, UUID authorUuid) {}

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
     * @return uuid → id/seriesUuid 對應結果
     */
    @Results(id = "uuidToIdMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "uuid", column = "uuid", javaType = UUID.class, typeHandler = UUIDTypeHandler.class),
            @Result(property = "seriesUuid", column = "series_uuid", javaType = UUID.class, typeHandler = UUIDTypeHandler.class)
    })
    @Select("<script>" +
            "SELECT id, uuid, series_uuid FROM articles " +
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

    /**
     * 在給定的 UUID 中篩出「目前仍存在」的文章，<b>不分狀態</b>。
     *
     * <p>與 {@link #findPublishedUuidsIn(List)} 的差別：本查詢不篩 {@code status}，
     * DRAFT／PENDING／REJECTED／PUBLISHED 皆回傳。供其他模組的孤兒校正排程判斷
     * 「文章是否已被刪除」。回傳型別採 {@code String}（{@code uuid::text}），由 caller 轉回 {@link UUID}。</p>
     *
     * @param uuids 待查證的文章公開 UUID（caller 須保證非空，避免 {@code IN ()} 語法錯誤）
     * @return 其中仍存在的文章 UUID 字串列表
     */
    @Select("<script>" +
            "SELECT uuid::text AS uuid FROM articles " +
            "WHERE uuid IN " +
            "<foreach collection='list' item='uuid' open='(' separator=',' close=')'>#{uuid}::uuid</foreach>" +
            "</script>")
    List<String> findExistingUuidsIn(@Param("list") List<UUID> uuids);

    /**
     * 文章是否存在，<b>不分狀態</b>（供 comment 模組建立留言前的存在性檢查）。
     *
     * @param uuid 文章公開 UUID
     * @return 存在為 true
     */
    @Select("SELECT EXISTS (SELECT 1 FROM articles WHERE uuid = #{uuid}::uuid)")
    boolean existsByUuid(@Param("uuid") UUID uuid);

    /**
     * 以公開 UUID 原子性遞增文章留言計數。
     *
     * @param uuid 文章公開 UUID
     * @return 受影響列數（文章不存在為 0）
     */
    @Update("UPDATE articles SET comment_count = comment_count + 1 WHERE uuid = #{uuid}::uuid")
    int incrementCommentCountByUuid(@Param("uuid") UUID uuid);

    /**
     * 以公開 UUID 原子性遞減文章留言計數，守衛 {@code > 0} 防 underflow。
     *
     * @param uuid 文章公開 UUID
     * @return 受影響列數（文章不存在或計數已為 0 時為 0）
     */
    @Update("UPDATE articles SET comment_count = comment_count - 1 WHERE uuid = #{uuid}::uuid AND comment_count > 0")
    int decrementCommentCountByUuid(@Param("uuid") UUID uuid);
}
