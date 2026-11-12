package dowob.xyz.blog.module.user.model.dto.response;

import dowob.xyz.blog.common.api.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 使用者個人資料回應 DTO
 *
 * <p>用於 GET /api/v1/users/me 端點，回傳當前登入使用者的公開個人資料與偏好設定。</p>
 *
 * @param uuid                    使用者對外公開的 UUID
 * @param email                   電子信箱
 * @param nickname                暱稱
 * @param bio                     個人簡介
 * @param avatarUrl               頭貼 URL（可為 null）
 * @param website                 個人網站 URL（可為 null）
 * @param socialLinks             社群連結 JSON 字串（可為 null）
 * @param location                所在地（可為 null）
 * @param role                    使用者角色
 * @param emailVerified           信箱是否已驗證
 * @param createdAt               帳號建立時間
 * @param notificationComment     留言通知偏好
 * @param notificationLike        按讚通知偏好
 * @param notificationReview      審核結果通知偏好
 * @param notificationFollow      追蹤通知偏好
 * @param notificationNewsletter  電子報訂閱偏好
 * @author Yuan
 * @version 2.0
 */
@Schema(description = "使用者個人資料回應")
public record UserProfileResponse(
        @Schema(description = "使用者對外公開的 UUID") UUID uuid,
        @Schema(description = "電子信箱") String email,
        @Schema(description = "暱稱") String nickname,
        @Schema(description = "個人簡介") String bio,
        @Schema(description = "頭貼 URL（可為 null）") String avatarUrl,
        @Schema(description = "個人網站 URL（可為 null）") String website,
        @Schema(description = "社群連結（JSON 格式，可為 null）") String socialLinks,
        @Schema(description = "所在地（可為 null）") String location,
        @Schema(description = "使用者角色") Role role,
        @Schema(description = "信箱是否已驗證") boolean emailVerified,
        @Schema(description = "帳號建立時間") LocalDateTime createdAt,
        @Schema(description = "留言通知偏好") boolean notificationComment,
        @Schema(description = "按讚通知偏好") boolean notificationLike,
        @Schema(description = "審核結果通知偏好") boolean notificationReview,
        @Schema(description = "追蹤通知偏好") boolean notificationFollow,
        @Schema(description = "電子報訂閱偏好") boolean notificationNewsletter
) {
}
