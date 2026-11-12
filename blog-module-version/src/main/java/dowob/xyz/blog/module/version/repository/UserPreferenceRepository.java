package dowob.xyz.blog.module.version.repository;

import dowob.xyz.blog.module.version.model.UserPreference;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * User Preference Repository。
 *
 * @author Yuan
 * @version 1.1
 */
@Repository
public interface UserPreferenceRepository extends CrudRepository<UserPreference, Long> {
    Optional<UserPreference> findByUserUuidAndPrefKey(UUID userUuid, String prefKey);
    List<UserPreference> findByUserUuid(UUID userUuid);
    void deleteByUserUuidAndPrefKey(UUID userUuid, String prefKey);
}
