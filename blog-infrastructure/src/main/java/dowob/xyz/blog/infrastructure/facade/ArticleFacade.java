package dowob.xyz.blog.infrastructure.facade;

import java.util.List;

/**
 * 文章模組跨模組查詢 Facade 介面
 *
 * <p>
 * 定義搜尋等其他模組存取文章資料的合約。
 * 實作由 blog-module-article 提供，透過 Spring DI 注入。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface ArticleFacade {

    /**
     * 查詢所有已發布文章的索引資料
     *
     * <p>
     * 用於搜尋模組全量重建 Elasticsearch 索引（Reindex）。
     * 僅回傳狀態為 PUBLISHED 的文章，包含完整索引所需欄位。
     * </p>
     *
     * @return 所有已發布文章的索引資料列表
     */
    List<ArticleIndexData> findAllPublishedForIndex();
}
