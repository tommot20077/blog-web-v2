package dowob.xyz.blog.module.user.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 信箱驗證請求 DTO
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Schema(description = "信箱驗證請求")
public class VerifyEmailRequest {

    /**
     * 信箱驗證 Token（由驗證信連結中取得）
     */
    @Schema(description = "信箱驗證 Token")
    @NotBlank(message = "Token 不能為空")
    private String token;
}
