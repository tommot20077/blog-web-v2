package dowob.xyz.blog.module.user.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登入請求 DTO
 *
 * <p>支援以電子信箱或暱稱作為登入識別符。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Data
@Schema(description = "登入請求")
public class LoginRequest {

    /**
     * 登入識別符，可為電子信箱或暱稱
     */
    @Schema(description = "登入識別符（信箱或暱稱）", example = "user@example.com")
    @NotBlank(message = "識別符不能為空")
    private String identifier;

    /**
     * 密碼
     */
    @Schema(description = "密碼", example = "password123")
    @NotBlank(message = "密碼不能為空")
    private String password;
}
