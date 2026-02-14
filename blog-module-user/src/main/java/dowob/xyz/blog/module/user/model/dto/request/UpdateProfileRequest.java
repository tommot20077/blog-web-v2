package dowob.xyz.blog.module.user.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新個人資料請求 DTO
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Schema(description = "更新個人資料請求")
public class UpdateProfileRequest {

    /**
     * 新的暱稱（1~50 字元）
     */
    @Schema(description = "暱稱", example = "newNickname")
    @NotBlank(message = "暱稱不能為空")
    @Size(max = 50, message = "暱稱不能超過 50 字元")
    private String nickname;

    /**
     * 個人簡介（最多 500 字元，可為空）
     */
    @Schema(description = "個人簡介", example = "我是一位部落客")
    @Size(max = 500, message = "個人簡介不能超過 500 字元")
    private String bio;

    /**
     * 個人網站 URL（最多 255 字元，可為空）
     */
    @Schema(description = "個人網站 URL", example = "https://myblog.com")
    @Size(max = 255, message = "網站 URL 不能超過 255 字元")
    private String website;

    /**
     * 社群連結（JSON 字串，可為空）
     */
    @Schema(description = "社群連結（JSON 格式）", example = "{\"twitter\":\"@user\"}")
    private String socialLinks;
}
