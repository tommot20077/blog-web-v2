package dowob.xyz.blog.module.version.repository;

import dowob.xyz.blog.module.version.model.ArticleVersion;
import org.springframework.data.repository.CrudRepository;
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
}
