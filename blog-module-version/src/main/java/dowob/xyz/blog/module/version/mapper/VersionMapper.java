package dowob.xyz.blog.module.version.mapper;

import dowob.xyz.blog.module.version.model.LatestSnapshotMeta;
import dowob.xyz.blog.module.version.model.dto.response.VersionSummaryResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;
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
     * 取某文章最新一筆指定類型快照的「建立時間＋內容長度」（不撈內容本身，PERF-21）。
     *
     * <p>供 {@code AutoSnapshotPolicy} 判斷時間間隔與字元差距。長度須與 Java {@code String.length()} 一致：
     * PostgreSQL {@code length()} 以字元（code point）計，補充平面字元（emoji 等，U+10000 以上）算 1，
     * Java 的 UTF-16 code unit 算 2，故額外加上補充平面字元的個數（以正規表示式刪去其他字元後計長），
     * 使兩邊對任何內容結果相同，快照觸發的邊界行為與改寫前完全一致。</p>
     *
     * <p>SELECT 欄位順序（created_at, content_length）必須與 {@link LatestSnapshotMeta} 的元件順序一致，不可調整。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @param type        快照類型（AUTO／MANUAL／PUBLISHED）
     * @return 最新一筆的投影；沒有快照時為 empty
     */
    @Select({
        "SELECT created_at,",
        "       coalesce(length(content), 0)",
        "       + coalesce(length(regexp_replace(content, '[^\\U00010000-\\U0010FFFF]', '', 'g')), 0) AS content_length",
        "  FROM article_versions",
        " WHERE article_uuid = #{articleUuid}::uuid AND type = #{type}",
        " ORDER BY created_at DESC",
        " LIMIT 1"
    })
    Optional<LatestSnapshotMeta> findLatestMeta(@Param("articleUuid") UUID articleUuid, @Param("type") String type);

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
