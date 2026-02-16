package dowob.xyz.blog.module.search.repository;

import dowob.xyz.blog.module.search.document.ArticleDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * 文章 Elasticsearch Repository
 *
 * <p>
 * 提供 Elasticsearch CRUD 操作，繼承自 {@link ElasticsearchRepository}。
 * 複雜的全文檢索查詢由 {@link org.springframework.data.elasticsearch.core.ElasticsearchOperations} 處理。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface ArticleSearchRepository extends ElasticsearchRepository<ArticleDocument, String> {
}
