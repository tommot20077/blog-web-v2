package dowob.xyz.blog.module.search.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 搜尋結果回應 DTO
 *
 * <p>
 * 包含單篇文章的搜尋摘要資訊，供 API 列表回傳使用。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@Builder
public class SearchResultResponse {

    /**
     * 文章公開 UUID
     */
    private UUID articleUuid;

    /**
     * 文章標題
     */
    private String title;

    /**
     * 文章摘要（可能含高亮標記）
     */
    private String summary;

    /**
     * URL slug
     */
    private String slug;

    /**
     * 作者暱稱
     */
    private String authorNickname;

    /**
     * 文章標籤名稱列表
     */
    private List<String> tagNames;

    /**
     * 發布時間
     */
    private LocalDateTime publishedAt;

    /**
     * 瀏覽次數
     */
    private long viewCount;

    /**
     * 按讚次數
     */
    private long likeCount;
}
