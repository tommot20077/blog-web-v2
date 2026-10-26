package dowob.xyz.blog.module.version.mapper;

import dowob.xyz.blog.module.version.model.dto.response.VersionSummaryResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/**
 * Version 模組 MyBatis Mapper — retention DELETE / count 等批次操作（V28 起以 {@code article_uuid} 識別文章）。
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface VersionMapper {

    /**
     * 滾動保留：刪掉 article 的 AUTO 類型快照中超過 retain 份的最舊那些。
     *
     * <p>用 OFFSET 跳過最新 retain 份，剩下的就是要刪的。</p>
     */
    @Update({
        "DELETE FROM article_versions",
        " WHERE id IN (",
        "   SELECT id FROM article_versions",
        "    WHERE article_uuid = #{articleUuid}::uuid AND type = 'AUTO'",
        "    ORDER BY created_at DESC",
        "    OFFSET #{retain}",
        " )"
    })
    int retainAuto(@Param("articleUuid") UUID articleUuid, @Param("retain") int retain);

    /**
     * publish 凍結時清掉所有 AUTO 快照（保留 MANUAL / PUBLISHED）。
     */
    @Update("DELETE FROM article_versions WHERE article_uuid = #{articleUuid}::uuid AND type = 'AUTO'")
    int deleteAutoByArticle(@Param("articleUuid") UUID articleUuid);

    /**
     * 計算 article 的 PUBLISHED 快照數（用於 freezePublished 算 vN）。
     */
    @Select("SELECT COUNT(*) FROM article_versions WHERE article_uuid = #{articleUuid}::uuid AND type = 'PUBLISHED'")
    int countPublished(@Param("articleUuid") UUID articleUuid);

    /**
     * 分頁查詢版本 summary（不含 content，給列表頁輕量用）。
     * typeFilter 為 null 時不做 type 篩選。
     */
    @Select({
        "<script>",
        "SELECT uuid, type, note, created_at, length(content) AS content_length",
        "  FROM article_versions",
        " WHERE article_uuid = #{articleUuid}::uuid",
        " <if test='typeFilter != null'>AND type = #{typeFilter}</if>",
        " ORDER BY created_at DESC",
        " LIMIT #{size} OFFSET #{offset}",
        "</script>"
    })
    List<VersionSummaryResponse> listSummaries(
            @Param("articleUuid") UUID articleUuid,
            @Param("typeFilter") String typeFilter,
            @Param("size") int size,
            @Param("offset") int offset);

    /**
     * 計算版本總數（配合 listSummaries 分頁用）。
     * typeFilter 為 null 時不做 type 篩選。
     */
    @Select({
        "<script>",
        "SELECT COUNT(*) FROM article_versions",
        " WHERE article_uuid = #{articleUuid}::uuid",
        " <if test='typeFilter != null'>AND type = #{typeFilter}</if>",
        "</script>"
    })
    long countSummaries(@Param("articleUuid") UUID articleUuid, @Param("typeFilter") String typeFilter);
}
