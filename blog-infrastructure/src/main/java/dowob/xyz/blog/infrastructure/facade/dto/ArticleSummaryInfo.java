package dowob.xyz.blog.infrastructure.facade.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文章摘要資訊 DTO（用於推薦模組回傳列表）
 *
 * <p>
 * 包含文章展示所需的摘要欄位，供推薦 API 回傳使用，
 * 不含完整內文以降低傳輸量。
 * </p>
 *
 * @param uuid             文章公開 UUID
 * @param title            文章標題
 * @param slug             文章 URL slug
 * @param summary          文章摘要
 * @param authorNickname   作者暱稱
 * @param tagNames         標籤名稱列表
 * @param viewCount        瀏覽次數
 * @param likeCount        按讚次數
 * @param publishedAt      發布時間
 * @author Yuan
 * @version 1.0
 */
public record ArticleSummaryInfo(
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
}
