package dowob.xyz.blog.module.user.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刪除帳號請求 DTO
 *
 * <p>將密碼從 URL Query String 移至 Request Body，避免密碼暴露在 access log 中。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Schema(description = "刪除帳號請求")
public class DeleteAccountRequest {

    /** 當前密碼（用於二次身份確認） */
    @Schema(description = "當前密碼", example = "password123")
    @NotBlank(message = "密碼不能為空")
    private String password;
}
