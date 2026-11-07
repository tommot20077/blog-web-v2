package dowob.xyz.blog.module.article.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 作者刪除帳號（DATA-02）連動用的 MyBatis Mapper：供 {@code ArticleUserDeletedConsumer} 找出
 * 該作者名下「應隨帳號移除」的文章。
 *
 * <p>只負責<b>查詢</b>；刪除一律走既有的 {@code ArticleService#deleteArticle}（含其發出的 {@code ArticleDeletedEvent}），
 * 讓 comment／reading／tag／version／series／search 的既有連動自然生效，不在此另寫刪除 SQL。
 * 單一 SQL 敘述，走 {@code idx_articles_author_id}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface ArticleAuthorCleanupMapper {

    /**
     * 查出某作者在指定狀態下的所有文章公開 UUID。
     *
     * <p>狀態集合由呼叫端依 {@code ArticleStatus#isRemovedWithAuthorAccount} 決定（去留政策的唯一真相），
     * 本方法不寫死任何狀態。回傳 {@code uuid::text}，由呼叫端轉回 {@link UUID}。
     * 呼叫端須保證 {@code statuses} 非空，避免產生 {@code IN ()} 語法錯誤。</p>
     *
     * @param authorUuid 作者公開 UUID
     * @param statuses   文章狀態名稱，至少一筆
     * @return 符合條件的文章公開 UUID 字串；無則為空清單
     */
    @Select({
        "<script>",
        "SELECT uuid::text FROM articles WHERE author_uuid = #{authorUuid}::uuid AND status IN",
        "<foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach>",
        "</script>"
    })
    List<String> findUuidsByAuthorAndStatuses(@Param("authorUuid") UUID authorUuid,
                                              @Param("statuses") List<String> statuses);

    /**
     * 擷取某作者在指定狀態下所有文章（內文＋封面網址）中引用的檔案 UUID 字串（去重）。
     *
     * <p>在資料庫端以正規表示式擷取 {@code /api/v1/files/{uuid}/content}，只回傳 UUID，不把內文搬到應用層。
     * 狀態集合由呼叫端決定，須非空。</p>
     *
     * @param authorUuid 作者公開 UUID
     * @param statuses   文章狀態名稱，至少一筆
     * @return 檔案 UUID 字串（小寫）；無則為空清單
     */
    @Select({
        "<script>",
        "SELECT DISTINCT lower(m[1]) FROM articles a,",
        "LATERAL regexp_matches(coalesce(a.content_md, '') || ' ' || coalesce(a.cover_image_url, ''),",
        "'/api/v1/files/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/content', 'g') AS m",
        "WHERE a.author_uuid = #{authorUuid}::uuid AND a.status IN",
        "<foreach collection='statuses' item='s' open='(' separator=',' close=')'>#{s}</foreach>",
        "</script>"
    })
    List<String> findReferencedFileUuidsByAuthorAndStatuses(@Param("authorUuid") UUID authorUuid,
                                                            @Param("statuses") List<String> statuses);

    /**
     * 查詢給定文章 UUID 中仍存在於 articles 表者（不分狀態）。
     *
     * <p>供 {@code ArticleLookupFacadeImpl#filterExistingUuids} 使用；呼叫端須先切批（見 {@code BatchedQuery}）。</p>
     *
     * @param uuids 文章 UUID（非空，大小需在 bind parameter 安全範圍內）
     * @return 仍存在的 UUID 字串
     */
    @Select("<script>"
            + "SELECT uuid::text FROM articles WHERE uuid IN "
            + "<foreach collection='list' item='u' open='(' separator=',' close=')'>#{u}::uuid</foreach>"
            + "</script>")
    List<String> findExistingUuidsIn(@Param("list") List<UUID> uuids);

    /**
     * 以 keyset 分頁查詢 {@code updated_at >= since} 的文章公開 UUID（不分狀態，依 uuid 升冪）。
     *
     * <p>供 {@code ArticleLookupFacadeImpl#findUuidsUpdatedSinceAnyStatus} 使用；排序與游標比較都在資料庫端
     * （uuid 位元組序），{@code after} 為 null 時從頭開始。</p>
     *
     * @param since 更新時間下界（含）
     * @param after 游標（不含）；null 表示從頭
     * @param limit 本頁筆數上限（正數）
     * @return 文章 UUID 字串（升冪）
     */
    @Select({
        "<script>",
        "SELECT uuid::text FROM articles WHERE updated_at &gt;= #{since}",
        "<if test='after != null'>AND uuid &gt; #{after}::uuid</if>",
        "ORDER BY uuid LIMIT #{limit}",
        "</script>"
    })
    List<String> findUuidsUpdatedSince(@Param("since") LocalDateTime since,
                                       @Param("after") UUID after,
                                       @Param("limit") int limit);
}
