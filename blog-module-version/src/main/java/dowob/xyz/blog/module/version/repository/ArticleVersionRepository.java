package dowob.xyz.blog.module.version.repository;

import dowob.xyz.blog.module.version.model.ArticleVersion;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Article Version Repository。
 *
 * @author Yuan
 * @version 1.1
 */
@Repository
public interface ArticleVersionRepository extends CrudRepository<ArticleVersion, Long> {
    Optional<ArticleVersion> findByUuid(UUID uuid);

    /**
     * 取某篇文章最新一筆指定 type 的快照（用於 AutoSnapshotPolicy 計算 diff）。
     *
     * @param articleUuid 文章公開 UUID
     * @param type        快照類型（AUTO／MANUAL／PUBLISHED）
     * @return 最新一筆；沒有時 empty
     */
    @Query("""
            SELECT * FROM article_versions
             WHERE article_uuid = :articleUuid AND type = :type
             ORDER BY created_at DESC LIMIT 1
            """)
    Optional<ArticleVersion> findLatestByArticleUuidAndType(
            @Param("articleUuid") UUID articleUuid,
            @Param("type") String type);
}
