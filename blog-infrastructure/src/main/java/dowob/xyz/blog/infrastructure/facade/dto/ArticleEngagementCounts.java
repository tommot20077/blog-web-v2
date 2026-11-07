package dowob.xyz.blog.infrastructure.facade.dto;

/**
 * 單篇文章對外顯示的互動計數（供搜尋結果等列表與詳情頁同源）
 *
 * <p>數字的計算口徑與文章詳情頁完全一致：瀏覽數 = DB 的 {@code view_count} + Redis 暫存增量，
 * 按讚數 = DB 的 {@code like_count}。</p>
 *
 * @param viewCount 瀏覽數（DB 值 + Redis 暫存增量）
 * @param likeCount 按讚數（DB 值）
 * @author Yuan
 * @version 1.0
 */
public record ArticleEngagementCounts(long viewCount, long likeCount) {

    /** 零計數（查無該文章時的呼叫端預設值） */
    public static final ArticleEngagementCounts ZERO = new ArticleEngagementCounts(0L, 0L);
}
