package dowob.xyz.blog.module.user.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 忘記密碼請求 DTO
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Schema(description = "忘記密碼請求")
public class ForgotPasswordRequest {

    /**
     * 用戶電子信箱
     */
    @Schema(description = "電子信箱", example = "user@example.com")
    @NotBlank(message = "信箱不能為空")
    @Email(message = "信箱格式不正確")
    private String email;
}
