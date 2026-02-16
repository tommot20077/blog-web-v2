package dowob.xyz.blog.module.article.service;

import java.util.UUID;

/**
 * 文章瀏覽計數服務介面
 *
 * <p>提供瀏覽計數的讀取、Redis 增量以及批次刷入 DB 的操作。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface ViewCountService {

    /**
     * 取得文章總瀏覽數（DB 基礎值 + Redis 暫存增量）
     *
     * @param articleUuid 文章公開 UUID
     * @return 總瀏覽數
     */
    long getViewCount(UUID articleUuid);

    /**
     * 消費 ArticleViewedEvent，在 Redis 中增加暫存瀏覽數
     *
     * @param articleUuid 文章公開 UUID
     */
    void incrementRedisViewCount(UUID articleUuid);

    /**
     * 將 Redis 中所有文章的暫存瀏覽增量批次寫入 DB，並清除 Redis key
     */
    void flushViewCounts();
}
