package dowob.xyz.blog.infrastructure.security;

import dowob.xyz.blog.common.api.enums.UserStatus;

import java.util.Optional;
import java.util.UUID;

/**
 * 用戶認證服務接口
 * <p>
 * 定義 Infrastructure 層需要的用戶相關操作，由 User Module 實作，
 * 避免 Infrastructure 直接依賴 User Module。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface UserAuthService {
    /**
     * 以 uuid 查詢使用者的認證狀態（供 {@code AuthStateLoader} 在 Redis miss 時回填）。
     *
     * <p>只回報事實，不套用任何狀態政策；查無使用者時回傳 empty 而非拋例外。</p>
     *
     * @param uuid 使用者 UUID
     * @return 認證狀態；使用者不存在時為 {@link Optional#empty()}
     */
    Optional<AuthState> findAuthStateByUuid(UUID uuid);

    /**
     * 簡易用戶詳情 DTO
     */
    record SimpleUserDetail(Long id, String email, String role, UserStatus status) {
    }
}
