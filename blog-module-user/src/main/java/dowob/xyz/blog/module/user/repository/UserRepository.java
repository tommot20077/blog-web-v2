package dowob.xyz.blog.module.user.repository;

import dowob.xyz.blog.module.user.model.User;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 用戶 Repository
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface UserRepository extends CrudRepository<User, Long> {
    /**
     * 透過信箱查詢用戶
     *
     * @param email 信箱
     * @return 用戶 Optional
     */
    Optional<User> findByEmail(String email);

    /**
     * 透過 UUID 查詢用戶
     *
     * @param uuid UUID
     * @return 用戶 Optional
     */
    Optional<User> findByUuid(UUID uuid);

    /**
     * 檢查信箱是否存在
     *
     * @param email 信箱
     * @return 是否存在
     */
    boolean existsByEmail(String email);

    /**
     * 檢查暱稱是否存在
     *
     * @param nickname 暱稱
     * @return 是否存在
     */
    boolean existsByNickname(String nickname);
}
