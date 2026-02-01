package dowob.xyz.blog.module.user.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 認證回應 DTO
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@AllArgsConstructor
@Schema(description = "認證回應")
public class AuthResponse {
    @Schema(description = "JWT Token")
    private String token;
}
