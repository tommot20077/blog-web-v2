package dowob.xyz.blog.module.article.service;

import java.util.Map;
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
     * 批次版的「加上 Redis 暫存增量」：把一組文章的 DB 瀏覽數各自加上 Redis 暫存增量。
     *
     * <p>與 {@link #getViewCount(UUID)} 共用同一段合併規則（非數字視為 0），差別只在 Redis 讀取：
     * 這裡用<b>一次 MGET</b> 取回所有 {@code article:views:{uuid}}，而不是逐篇 GET。
     * 呼叫端先自行批次查出 DB 值（可與按讚數同一條 SQL），再交給本方法合併。</p>
     *
     * @param dbViewCounts 文章 UUID → DB 的 {@code view_count}；null 或空 Map 回傳空 Map（不連 Redis）
     * @return 文章 UUID → 總瀏覽數（DB 值 + Redis 增量），key 集合與輸入相同
     */
    Map<UUID, Long> addRedisDeltas(Map<UUID, Long> dbViewCounts);

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
