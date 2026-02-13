package dowob.xyz.blog.module.user.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 認證回應 DTO
 *
 * <p>登入成功後回傳給前端的存取憑證，僅包含短效 Access Token；
 * Refresh Token 透過 HttpOnly Cookie 傳遞，不出現於此 DTO。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Data
@AllArgsConstructor
@Schema(description = "認證回應")
public class AuthResponse {

    /**
     * 短效 JWT Access Token（有效期 1 小時）
     */
    @Schema(description = "JWT Access Token")
    private String accessToken;
}
