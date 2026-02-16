package dowob.xyz.blog.infrastructure.facade;

import java.util.List;
import java.util.UUID;

/**
 * 搜尋模組跨模組查詢 Facade 介面
 *
 * <p>
 * 定義推薦模組等跨模組存取 Elasticsearch 搜尋功能的合約。
 * 實作由 blog-module-search 提供，透過 Spring DI 注入。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface SearchFacade {

    /**
     * 使用 Elasticsearch more_like_this 查詢與指定文章相似的文章 UUID 列表
     *
     * <p>
     * 依據文章的標題、內文與摘要找出語意相近的文章。
     * 若 ES 不可用或查無結果，回傳空列表（不拋例外）。
     * </p>
     *
     * @param articleUuid 目標文章公開 UUID
     * @param limit       最多回傳筆數
     * @return 相似文章的 UUID 列表
     */
    List<UUID> findSimilarArticles(UUID articleUuid, int limit);
}
