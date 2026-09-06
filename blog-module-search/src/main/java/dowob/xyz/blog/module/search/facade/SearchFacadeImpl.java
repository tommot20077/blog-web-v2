package dowob.xyz.blog.module.search.facade;

import dowob.xyz.blog.infrastructure.facade.SearchFacade;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * SearchFacade 實作
 *
 * <p>
 * 使用 Elasticsearch {@code more_like_this} 查詢找出與目標文章語意相近的文章。
 * 依據文章的標題、摘要與內文計算相似度。
 * 若 ES 不可用或查無結果，安全地回傳空列表而非拋出例外。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchFacadeImpl implements SearchFacade {

    /**
     * Elasticsearch 操作介面
     */
    private final ElasticsearchOperations elasticsearchOperations;

    /**
     * 文章索引名稱，與 {@link dowob.xyz.blog.module.search.document.ArticleDocument} 宣告一致
     */
    private static final String ARTICLE_INDEX = "blog_articles";

    /**
     * {@inheritDoc}
     *
     * <p>
     * 使用 {@code more_like_this} 查詢，以指定文章 UUID 為基準，
     * 在 title、summary、content 欄位上計算相似度。
     * 若 ES 發生錯誤，記錄警告並回傳空列表（降級容錯）。
     * </p>
     */
    @Override
    public List<UUID> findSimilarArticles(UUID articleUuid, int limit) {
        try {
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.moreLikeThis(mlt -> mlt
                            .like(l -> l.document(d -> d
                                    .index(ARTICLE_INDEX)
                                    .id(articleUuid.toString())
                            ))
                            .fields("title", "summary", "content")
                            .minTermFreq(1)
                            .minDocFreq(1)
                            .maxQueryTerms(25)
                    ))
                    .withMaxResults(limit)
                    .build();

            SearchHits<ArticleDocument> hits =
                    elasticsearchOperations.search(query, ArticleDocument.class);

            return hits.getSearchHits().stream()
                    .map(SearchHit::getId)
                    .map(UUID::fromString)
                    .toList();

        } catch (Exception e) {
            log.warn("Elasticsearch more_like_this 查詢失敗，articleUuid={}: {}", articleUuid, e.getMessage());
            return Collections.emptyList();
        }
    }
}
