package dowob.xyz.blog.module.user.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 公開作者頁的使用者資料回應（{@code GET /api/v1/users/{uuid}/profile}，匿名可讀）
 *
 * <p><b>只放可公開的欄位</b>：不含 email、username（登入識別符）、role、emailVerified、通知偏好、status。
 * 日後新增欄位前先確認該欄位可對匿名訪客公開；欄位集合由 {@code PublicAuthorControllerTest} 以白名單斷言守住。</p>
 *
 * @param uuid        使用者公開 UUID
 * @param nickname    暱稱
 * @param avatarUrl   頭貼 URL；不合格的舊資料為 null
 * @param bio         個人簡介
 * @param location    所在地（前端作為職稱行）
 * @param website     個人網站，僅 http(s)；不合格的舊資料為 null
 * @param socialLinks 社群帳號名稱（handle）：github／twitter／linkedin 三個 key 恆存在（未填為 null），
 *                    另含其他符合 {@code [a-z0-9_]{1,32}} 的 key
 * @param joinedAt    帳號建立時間（{@code users.created_at}）
 * @param stats       已發布文章統計
 * @author Yuan
 * @version 1.0
 */
@Schema(description = "公開作者頁的使用者資料")
public record PublicProfileResponse(
        @Schema(description = "使用者公開 UUID") UUID uuid,
        @Schema(description = "暱稱") String nickname,
        @Schema(description = "頭貼 URL（可為 null）") String avatarUrl,
        @Schema(description = "個人簡介（可為 null）") String bio,
        @Schema(description = "所在地（可為 null）") String location,
        @Schema(description = "個人網站，僅 http(s)（可為 null）") String website,
        @Schema(description = "社群帳號名稱；github／twitter／linkedin 恆存在，未填為 null") Map<String, String> socialLinks,
        @Schema(description = "帳號建立時間") LocalDateTime joinedAt,
        @Schema(description = "已發布文章統計") Stats stats
) {

    /**
     * 已發布文章統計（只計 PUBLISHED；瀏覽數最多落後約 5 分鐘）
     *
     * @param publishedArticleCount 已發布文章篇數
     * @param totalViewCount        已發布文章瀏覽數總和
     * @param totalLikeCount        已發布文章按讚數總和
     */
    @Schema(description = "已發布文章統計")
    public record Stats(
            @Schema(description = "已發布文章篇數") long publishedArticleCount,
            @Schema(description = "已發布文章瀏覽數總和（最多落後約 5 分鐘）") long totalViewCount,
            @Schema(description = "已發布文章按讚數總和") long totalLikeCount
    ) {
    }
}
