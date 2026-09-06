package dowob.xyz.blog.module.user.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import dowob.xyz.blog.common.constant.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重設密碼請求 DTO
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Schema(description = "重設密碼請求")
public class ResetPasswordRequest {

    /**
     * 密碼重設 Token（由忘記密碼信中取得）
     */
    @Schema(description = "密碼重設 Token")
    @NotBlank(message = "Token 不能為空")
    private String token;

    /**
     * 新密碼（{@value PasswordPolicy#MIN_LENGTH}~{@value PasswordPolicy#MAX_LENGTH} 字元）
     */
    @Schema(description = "新密碼（" + PasswordPolicy.MIN_LENGTH + "~" + PasswordPolicy.MAX_LENGTH + " 字元）")
    @NotBlank(message = "新密碼不能為空")
    @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.PATTERN_MESSAGE)
    private String newPassword;
}
