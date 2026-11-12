package dowob.xyz.blog.module.user.model.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 作者下拉選單的一筆結果（{@code GET /api/v1/authors}，匿名可讀）
 *
 * @param uuid                  作者公開 UUID
 * @param nickname              暱稱
 * @param avatarUrl             頭貼 URL（可為 null）
 * @param publishedArticleCount 已發布文章篇數（至少 1）
 * @author Yuan
 * @version 1.0
 */
@Schema(description = "作者下拉選單項目")
public record AuthorSummaryResponse(
        @Schema(description = "作者公開 UUID") UUID uuid,
        @Schema(description = "暱稱") String nickname,
        @Schema(description = "頭貼 URL（可為 null）") String avatarUrl,
        @Schema(description = "已發布文章篇數") long publishedArticleCount
) {
}
