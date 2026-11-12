package dowob.xyz.blog.infrastructure.facade.dto;

/**
 * 作者的已發布文章統計（供公開作者頁使用）
 *
 * <p>只統計 {@code PUBLISHED} 文章。作者沒有已發布文章時三個欄位皆為 0，不會有 null。
 * {@code views} 來自 Redis 每 5 分鐘回寫的 {@code articles.view_count}，最多落後約 5 分鐘。</p>
 *
 * @param count 已發布文章篇數
 * @param views 已發布文章的瀏覽數總和
 * @param likes 已發布文章的按讚數總和
 * @author Yuan
 * @version 1.0
 */
public record AuthorArticleStats(long count, long views, long likes) {

    /** 零統計（作者沒有任何已發布文章時使用） */
    public static final AuthorArticleStats EMPTY = new AuthorArticleStats(0L, 0L, 0L);
}
