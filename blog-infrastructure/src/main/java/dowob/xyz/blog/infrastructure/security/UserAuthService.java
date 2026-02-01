package dowob.xyz.blog.infrastructure.security;

import dowob.xyz.blog.common.exception.BusinessException;

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
     * 獲取用戶當前的 Token 版本號
     *
     * @param userId 用戶 ID
     * @return Token 版本號
     */
    String getUserTokenVersion(Long userId) throws Exception;

    /**
     * 載入用戶詳細資訊 (用於構建 Authentication)
     *
     * @param userId 用戶 ID
     * @return 用戶詳細資訊 (包含角色等)
     *
     */
    SimpleUserDetail getUserDetail(Long userId) throws Exception;

    /**
     * 簡易用戶詳情 DTO
     */
    record SimpleUserDetail(Long id, String email, String role, boolean enabled) {
    }
}
