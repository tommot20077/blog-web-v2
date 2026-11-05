package dowob.xyz.blog.module.series.repository;

import dowob.xyz.blog.module.series.model.Series;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    /**
     * 查出某作者名下的所有 series（作者刪帳連動用）。
     *
     * @param authorUuid 作者公開 UUID
     * @return 該作者的 series；無則為空清單
     */
    List<Series> findByAuthorUuid(UUID authorUuid);
}
