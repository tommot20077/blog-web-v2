package dowob.xyz.blog.module.user.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 信箱驗證碼請求 DTO
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Schema(description = "信箱驗證碼請求")
public class VerifyEmailCodeRequest {

    /** 用戶電子信箱 */
    @Schema(description = "電子信箱", example = "user@example.com")
    @NotBlank(message = "信箱不能為空")
    @Email(message = "信箱格式不正確")
    private String email;

    /** 6 位數驗證碼 */
    @Schema(description = "6 位數驗證碼", example = "123456")
    @NotBlank(message = "驗證碼不能為空")
    @Pattern(regexp = "\\d{6}", message = "驗證碼須為 6 位數字")
    private String code;
}
