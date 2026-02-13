package dowob.xyz.blog.module.user.repository;

import dowob.xyz.blog.module.user.model.VerificationToken;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 驗證 Token Repository
 *
 * <p>提供驗證 Token 的資料存取操作，支援依 Token 字串與類型查詢，
 * 以及依用戶 ID 批量刪除。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface VerificationTokenRepository extends CrudRepository<VerificationToken, Long> {

    /**
     * 依 Token 字串與類型查詢驗證 Token
     *
     * @param token Token 字串
     * @param type  Token 類型
     * @return 驗證 Token Optional
     */
    Optional<VerificationToken> findByTokenAndType(String token, String type);

    /**
     * 依用戶 ID 刪除所有對應的驗證 Token
     *
     * @param userId 用戶 ID
     */
    void deleteByUserId(Long userId);
}
