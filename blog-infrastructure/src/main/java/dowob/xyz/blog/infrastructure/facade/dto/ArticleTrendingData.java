package dowob.xyz.blog.infrastructure.facade.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文章熱門排行計算資料 DTO
 *
 * <p>
 * 提供時間衰減演算法所需的文章統計資料，
 * 由 TrendingRefreshJob 用於計算各週期熱門分數。
 * </p>
 *
 * @param uuid        文章公開 UUID
 * @param viewCount   瀏覽次數
 * @param likeCount   按讚次數
 * @param publishedAt 發布時間（用於計算文章年齡）
 * @author Yuan
 * @version 1.0
 */
public record ArticleTrendingData(
        UUID uuid,
        long viewCount,
        long likeCount,
        LocalDateTime publishedAt
) {
}
