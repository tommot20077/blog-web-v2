package dowob.xyz.blog.module.user.model.dto.response;

import dowob.xyz.blog.common.api.enums.Role;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 使用者個人資料回應 DTO
 *
 * <p>用於 GET /api/v1/users/me 端點，回傳當前登入使用者的公開個人資料。</p>
 *
 * @param uuid          使用者對外公開的 UUID
 * @param email         電子信箱
 * @param nickname      暱稱
 * @param avatarUrl     頭貼 URL（可為 null）
 * @param role          使用者角色
 * @param emailVerified 信箱是否已驗證
 * @param createdAt     帳號建立時間
 * @author Yuan
 * @version 1.0
 */
public record UserProfileResponse(
        UUID uuid,
        String email,
        String nickname,
        String avatarUrl,
        Role role,
        boolean emailVerified,
        LocalDateTime createdAt
) {
}
