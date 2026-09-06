package dowob.xyz.blog.module.user.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import dowob.xyz.blog.common.constant.PasswordPolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 註冊請求 DTO
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Schema(description = "註冊請求")
public class RegisterRequest {

    /**
     * 用戶電子信箱
     */
    @Schema(description = "電子信箱", example = "user@example.com")
    @NotBlank(message = "信箱不能為空")
    @Email(message = "信箱格式不正確")
    private String email;

    /**
     * 密碼（{@value PasswordPolicy#MIN_LENGTH}~{@value PasswordPolicy#MAX_LENGTH} 字元）
     */
    @Schema(description = "密碼", example = "password123")
    @NotBlank(message = "密碼不能為空")
    @Pattern(regexp = PasswordPolicy.PATTERN, message = PasswordPolicy.PATTERN_MESSAGE)
    private String password;

    /**
     * 用戶名（唯一登入識別符，3~50 字元）
     */
    @Schema(description = "用戶名", example = "yuan_dev")
    @NotBlank(message = "用戶名不能為空")
    @Size(min = 3, max = 50, message = "用戶名長度須為 3-50 字元")
    private String username;

    /**
     * 暱稱
     */
    @Schema(description = "暱稱", example = "Yuan")
    @NotBlank(message = "暱稱不能為空")
    private String nickname;
}
