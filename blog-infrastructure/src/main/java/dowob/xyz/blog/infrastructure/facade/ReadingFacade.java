package dowob.xyz.blog.infrastructure.facade;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 閱讀互動模組跨模組查詢 Facade 介面
 *
 * <p>
 * 供 article 模組（ArticleQueryService）查詢收藏狀態與閱讀進度，
 * 避免 blog-module-article ↔ blog-module-reading 循環依賴。
 * 實作由 blog-module-reading 提供，透過 Spring DI 注入。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface ReadingFacade {

    /**
     * 批次查詢指定使用者已收藏的文章 ID 集合。
     *
     * <p>userId 為 null 時回傳空集合（未登入）。</p>
     *
     * @param userId     使用者資料庫主鍵（可為 null）
     * @param articleIds 文章資料庫主鍵列表
     * @return 已收藏的 article_id 集合
     */
    Set<Long> batchIsBookmarked(Long userId, List<Long> articleIds);

    /**
     * 查詢單篇文章是否已被指定使用者收藏。
     *
     * @param userId    使用者資料庫主鍵
     * @param articleId 文章資料庫主鍵
     * @return 已收藏為 true，否則 false
     */
    boolean isBookmarked(Long userId, Long articleId);

    /**
     * 批次查詢指定使用者的閱讀進度。
     *
     * <p>userId 為 null 時回傳空 Map（未登入）。進度值為 0.00 ~ 1.00。</p>
     *
     * @param userId     使用者資料庫主鍵（可為 null）
     * @param articleIds 文章資料庫主鍵列表
     * @return articleId → progress 對應 Map（無紀錄則不含該 key）
     */
    Map<Long, BigDecimal> batchGetProgress(Long userId, List<Long> articleIds);

    /**
     * 查詢單篇文章的閱讀進度。
     *
     * @param userId      使用者資料庫主鍵
     * @param articleUuid 文章公開 UUID
     * @return 閱讀進度（0.00 ~ 1.00），無紀錄時回傳 null
     */
    BigDecimal getProgress(Long userId, UUID articleUuid);
}
