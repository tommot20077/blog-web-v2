package dowob.xyz.blog.module.version.repository;

import dowob.xyz.blog.module.version.model.UserPreference;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * User Preference Repository。
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface UserPreferenceRepository extends CrudRepository<UserPreference, Long> {
    Optional<UserPreference> findByUserIdAndPrefKey(Long userId, String prefKey);
    List<UserPreference> findByUserId(Long userId);
    void deleteByUserIdAndPrefKey(Long userId, String prefKey);
}
