package dowob.xyz.blog.module.user.repository;

import dowob.xyz.blog.module.user.model.User;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
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
     * 透過暱稱查詢用戶
     *
     * @param nickname 暱稱
     * @return 用戶 Optional
     */
    Optional<User> findByNickname(String nickname);

    /**
     * 檢查暱稱是否存在
     *
     * @param nickname 暱稱
     * @return 是否存在
     */
    boolean existsByNickname(String nickname);

    /**
     * 透過用戶名查詢用戶
     *
     * @param username 用戶名
     * @return 用戶 Optional
     */
    Optional<User> findByUsername(String username);

    /**
     * 檢查用戶名是否存在
     *
     * @param username 用戶名
     * @return 是否存在
     */
    boolean existsByUsername(String username);

    /**
     * 查詢「近期被刪除」的使用者 UUID（{@code status='DELETED' AND updated_at > since}），
     * 供 {@code UserDeletedEventResendJob} 補發遺失的刪帳事件。
     *
     * <p>users 表規模小且條件只命中極少數列，不另建索引（見該排程的 JavaDoc）。</p>
     *
     * @param since 下界（不含）
     * @return 符合條件的使用者公開 UUID
     */
    @Query("SELECT uuid FROM users WHERE status = 'DELETED' AND updated_at > :since")
    List<UUID> findRecentlyDeletedUuids(@Param("since") LocalDateTime since);
}
