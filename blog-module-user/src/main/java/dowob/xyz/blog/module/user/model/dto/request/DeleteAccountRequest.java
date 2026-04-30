package dowob.xyz.blog.module.user.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 刪除帳號請求 DTO
 *
 * <p>將密碼放在 request body 中，避免透過 URL query string 暴露。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Schema(description = "刪除帳號請求")
public class DeleteAccountRequest {

    /**
     * 當前密碼（用於二次身份確認）
     */
    @NotBlank(message = "密碼不能為空")
    @Size(min = 6, max = 50, message = "密碼長度須為 6-50 字元")
    @Schema(description = "當前密碼", example = "password123")
    private String password;
}
