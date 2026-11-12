package dowob.xyz.blog.module.user.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新通知偏好請求 DTO
 *
 * <p>對應設定頁面 Notifications 區塊的 5 個開關。所有欄位皆為必填布林值，
 * 前端每次提交完整偏好狀態（非 patch 單一欄位）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Schema(description = "更新通知偏好請求")
public class NotificationPreferencesRequest {

    /** 留言通知 */
    @Schema(description = "留言通知", example = "true")
    @NotNull(message = "留言通知偏好不能為空")
    private Boolean comment;

    /** 按讚通知 */
    @Schema(description = "按讚通知", example = "true")
    @NotNull(message = "按讚通知偏好不能為空")
    private Boolean like;

    /** 審核結果通知 */
    @Schema(description = "審核結果通知", example = "true")
    @NotNull(message = "審核通知偏好不能為空")
    private Boolean review;

    /** 追蹤通知 */
    @Schema(description = "追蹤通知", example = "true")
    @NotNull(message = "追蹤通知偏好不能為空")
    private Boolean follow;

    /** 電子報訂閱 */
    @Schema(description = "電子報訂閱", example = "true")
    @NotNull(message = "電子報偏好不能為空")
    private Boolean newsletter;
}
