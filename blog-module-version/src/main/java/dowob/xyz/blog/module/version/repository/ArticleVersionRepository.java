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
 * @version 1.0
 */
@Repository
public interface ArticleVersionRepository extends CrudRepository<ArticleVersion, Long> {
    Optional<ArticleVersion> findByUuid(UUID uuid);

    /** 取最新一筆 type 的快照（用於 AutoSnapshotPolicy 計算 diff）*/
    @Query("""
            SELECT * FROM article_versions
             WHERE article_id = :articleId AND type = :type
             ORDER BY created_at DESC LIMIT 1
            """)
    Optional<ArticleVersion> findLatestByArticleAndType(
            @Param("articleId") Long articleId,
            @Param("type") String type);
}
