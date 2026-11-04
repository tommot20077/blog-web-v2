package dowob.xyz.blog.module.user.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import dowob.xyz.blog.module.user.validation.ProfileFieldLimits;
import dowob.xyz.blog.module.user.validation.SafeHttpUrl;
import dowob.xyz.blog.module.user.validation.SafeSocialLinks;
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
     *
     * <p>SEC-10：只允許 http／https 絕對網址，拒絕 javascript:／data: 等協定與相對路徑；
     * 空字串視為清除。</p>
     */
    @Schema(description = "個人網站 URL（只允許 http/https 絕對網址；空字串代表清除）", example = "https://myblog.com")
    @Size(max = ProfileFieldLimits.WEBSITE_MAX_LENGTH, message = "網站 URL 不能超過 255 字元")
    @SafeHttpUrl(maxLength = ProfileFieldLimits.WEBSITE_MAX_LENGTH)
    private String website;

    /**
     * 社群連結（JSON 字串，可為空）
     *
     * <p>SEC-10：必須是 JSON object，<b>value 為帳號名稱（handle），不含網址</b>；
     * 前端若要顯示連結，須自行加上平台固定前綴（例如 {@code https://github.com/}）。規則：</p>
     * <ul>
     *   <li>最多 10 個項目；key 為 {@code [a-z0-9_]{1,32}}</li>
     *   <li>value 為字串、長度 ≤ 100，不得含空白、控制字元、{@code / : \ < > "}；空字串代表未填</li>
     *   <li>允許單一前導 {@code @}（如 {@code @user}，相容 Twitter 寫法，後端原樣儲存不剝除）</li>
     *   <li>JSON null、數字、物件、陣列等非字串 value 一律拒絕</li>
     * </ul>
     */
    @Schema(description = "社群連結（JSON object）。value 為帳號名稱，不含網址（含 : 或 / 會被拒絕）；"
            + "前端若要顯示連結須自行加平台固定前綴。允許單一前導 @。最多 10 項，key 限 [a-z0-9_]{1,32}，value 長度 ≤ 100，空字串代表未填",
            example = "{\"github\":\"octocat\",\"twitter\":\"@user\",\"linkedin\":\"\"}")
    @SafeSocialLinks
    private String socialLinks;

    /**
     * 頭貼 URL（最多 512 字元，可為空）
     *
     * <p>SEC-10：只允許 http／https 絕對網址，或檔案上傳 API 回傳的本站路徑
     * {@code /api/v1/files/{uuid}/content}；其餘相對路徑與危險協定一律拒絕。</p>
     */
    @Schema(description = "頭貼 URL（http/https 絕對網址，或檔案上傳 API 回傳的 /api/v1/files/{uuid}/content；空字串代表清除）",
            example = "https://cdn.example.com/avatar.png")
    @Size(max = ProfileFieldLimits.AVATAR_URL_MAX_LENGTH, message = "頭貼 URL 不能超過 512 字元")
    @SafeHttpUrl(maxLength = ProfileFieldLimits.AVATAR_URL_MAX_LENGTH, allowFileContentPath = true)
    private String avatarUrl;

    /**
     * 所在地（最多 100 字元，可為空）
     */
    @Schema(description = "所在地", example = "Taipei")
    @Size(max = 100, message = "所在地不能超過 100 字元")
    private String location;
}
