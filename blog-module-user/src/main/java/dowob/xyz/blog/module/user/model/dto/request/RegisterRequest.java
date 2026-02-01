package dowob.xyz.blog.module.user.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
    @Schema(description = "電子信箱", example = "user@example.com")
    @NotBlank(message = "信箱不能為空")
    @Email(message = "信箱格式不正確")
    private String email;

    @Schema(description = "密碼", example = "password123")
    @NotBlank(message = "密碼不能為空")
    @Size(min = 6, max = 50, message = "密碼長度須為 6-50 字元")
    private String password;

    @Schema(description = "暱稱", example = "Yuan")
    @NotBlank(message = "暱稱不能為空")
    private String nickname;
}
