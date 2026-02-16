package dowob.xyz.blog.module.recommend.model.dto.response;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 推薦文章回應 DTO
 *
 * <p>
 * 推薦 API 回傳的文章摘要格式，包含展示所需的所有欄位。
 * 由 {@link ArticleSummaryInfo} 轉換而來。
 * </p>
 *
 * @param uuid           文章公開 UUID
 * @param title          文章標題
 * @param slug           文章 URL slug
 * @param summary        文章摘要
 * @param authorNickname 作者暱稱
 * @param tagNames       標籤名稱列表
 * @param viewCount      瀏覽次數
 * @param likeCount      按讚次數
 * @param publishedAt    發布時間
 * @author Yuan
 * @version 1.0
 */
public record RecommendArticleResponse(
        UUID uuid,
        String title,
        String slug,
        String summary,
        String authorNickname,
        List<String> tagNames,
        long viewCount,
        long likeCount,
        LocalDateTime publishedAt
) {

    /**
     * 從 ArticleSummaryInfo 建立 RecommendArticleResponse
     *
     * @param info 文章摘要資訊
     * @return 推薦文章回應 DTO
     */
    public static RecommendArticleResponse from(ArticleSummaryInfo info) {
        return new RecommendArticleResponse(
                info.uuid(),
                info.title(),
                info.slug(),
                info.summary(),
                info.authorNickname(),
                info.tagNames(),
                info.viewCount(),
                info.likeCount(),
                info.publishedAt()
        );
    }
}
