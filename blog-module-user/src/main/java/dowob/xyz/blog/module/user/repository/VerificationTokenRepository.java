package dowob.xyz.blog.module.user.repository;

import dowob.xyz.blog.module.user.model.VerificationToken;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 驗證 Token Repository
 *
 * <p>提供驗證 Token 的資料存取操作（資料庫只存 Token 的 SHA-256），支援依 Token 雜湊與類型查詢，
 * 以及依用戶 ID 批量刪除。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface VerificationTokenRepository extends CrudRepository<VerificationToken, Long> {

    /**
     * 依 Token 雜湊與類型查詢驗證 Token
     *
     * @param tokenHash 使用者提交明文 token 的 SHA-256 hex（呼叫端以 {@code TokenHasher} 計算）
     * @param type      Token 類型
     * @return 驗證 Token Optional
     */
    Optional<VerificationToken> findByTokenHashAndType(String tokenHash, String type);

    /**
     * 依用戶 ID 刪除所有對應的驗證 Token
     *
     * @param userId 用戶 ID
     */
    void deleteByUserId(Long userId);

    /**
     * 依用戶 ID 與類型刪除對應的驗證 Token
     *
     * @param userId 用戶 ID
     * @param type   Token 類型
     */
    void deleteByUserIdAndType(Long userId, String type);
}
