package dowob.xyz.blog.module.user.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密碼請求 DTO
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Schema(description = "修改密碼請求")
public class ChangePasswordRequest {

    /**
     * 當前舊密碼
     */
    @Schema(description = "舊密碼")
    @NotBlank(message = "舊密碼不能為空")
    private String oldPassword;

    /**
     * 新密碼（最少 6 字元）
     */
    @Schema(description = "新密碼（最少 6 字元）")
    @NotBlank(message = "新密碼不能為空")
    @Size(min = 6, message = "新密碼至少需要 6 字元")
    private String newPassword;
}
