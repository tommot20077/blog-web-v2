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
     * 作者公開 UUID（取自 ES 文件的 {@code author.uuid}，供前端連到公開作者頁；
     * 文件沒有作者或 uuid 無法解析時為 null）
     */
    private UUID authorUuid;

    /**
     * 文章標籤名稱列表
     */
    private List<String> tagNames;

    /**
     * 發布時間
     */
    private LocalDateTime publishedAt;

    /**
     * 瀏覽次數（與文章詳情頁同源：DB 值 + Redis 暫存增量；不取自 ES 文件）
     */
    private long viewCount;

    /**
     * 按讚次數（與文章詳情頁同源：DB 值；不取自 ES 文件）
     */
    private long likeCount;
}
