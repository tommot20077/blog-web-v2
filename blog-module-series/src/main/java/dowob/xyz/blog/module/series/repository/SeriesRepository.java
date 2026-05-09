package dowob.xyz.blog.module.series.repository;

import dowob.xyz.blog.module.series.model.Series;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Series Repository。
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface SeriesRepository extends CrudRepository<Series, Long> {
    Optional<Series> findByUuid(UUID uuid);
    Optional<Series> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
