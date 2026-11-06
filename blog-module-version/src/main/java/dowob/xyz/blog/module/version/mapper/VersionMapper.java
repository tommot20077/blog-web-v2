package dowob.xyz.blog.module.version.mapper;

import dowob.xyz.blog.module.version.model.LatestSnapshotRef;
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
     * 取某文章最新一筆指定類型快照的主鍵與建立時間（不碰內容，PERF-21 第一步）。
     *
     * <p>先查本投影檢查時間間隔：連續存檔時大多數事件在這一步就被擋下，不必計算任何內容長度。
     * 選擇「分兩個查詢」而非單一 SQL 的 {@code CASE}：Java 端的間隔判斷用 JVM 時鐘
     * （共通規則：時間來源維持 {@code LocalDateTime.now()}），若要在 SQL 內跳過計算就得把間隔門檻與時鐘傳進 SQL，
     * 耦合較深；兩段式第二段以主鍵點查，成本固定，邏輯也留在 policy 一處。</p>
     *
     * <p>SELECT 欄位順序（id, created_at）必須與 {@link LatestSnapshotRef} 的元件順序一致，不可調整。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @param type        快照類型（AUTO／MANUAL／PUBLISHED）
     * @return 最新一筆的參照；沒有快照時為 empty
     */
    @Select({
        "SELECT id, created_at",
        "  FROM article_versions",
        " WHERE article_uuid = #{articleUuid}::uuid AND type = #{type}",
        " ORDER BY created_at DESC, id DESC",
        " LIMIT 1"
    })
    Optional<LatestSnapshotRef> findLatestRef(@Param("articleUuid") UUID articleUuid, @Param("type") String type);

    /**
     * 以主鍵取一筆快照的內容長度（PERF-21 第二步；只在時間間隔通過後才呼叫）。
     *
     * <p>長度須與 Java {@code String.length()} 一致：PostgreSQL {@code length()} 以字元（code point）計，
     * 補充平面字元（emoji 等，U+10000 以上）算 1，Java 的 UTF-16 code unit 算 2，故額外加上補充平面字元的個數
     * （以正規表示式刪去其他字元後計長），使兩邊對任何內容結果相同，快照觸發的邊界行為與改寫前完全一致。</p>
     *
     * @param id 快照主鍵
     * @return UTF-16 code unit 數；該列已不存在（例如剛被滾動保留刪掉）時為 empty
     */
    @Select({
        "SELECT coalesce(length(content), 0)",
        "       + coalesce(length(regexp_replace(content, '[^\\U00010000-\\U0010FFFF]', '', 'g')), 0)",
        "  FROM article_versions",
        " WHERE id = #{id}"
    })
    Optional<Integer> findContentLength(@Param("id") Long id);

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
