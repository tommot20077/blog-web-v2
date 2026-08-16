package dowob.xyz.blog.module.search.service;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.model.dto.response.SearchIndexStatusResponse;
import dowob.xyz.blog.module.search.model.dto.response.SearchResultResponse;
import dowob.xyz.blog.module.search.repository.ArticleSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 搜尋服務實作
 *
 * <p>
 * 實作全文檢索、搜尋建議（Redis ZSet）、搜尋歷史（Redis List）
 * 與索引管理（Elasticsearch）等核心功能。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    /**
     * Elasticsearch 操作工具（用於複雜查詢）
     */
    private final ElasticsearchOperations elasticsearchOperations;

    /**
     * 文章 Elasticsearch Repository（用於 CRUD）
     */
    private final ArticleSearchRepository articleSearchRepository;

    /**
     * Redis 操作工具
     */
    private final StringRedisTemplate redisTemplate;

    /**
     * 文章 Facade（用於全量重建索引）
     */
    private final ArticleFacade articleFacade;

    /**
     * 個人搜尋歷史最大保留筆數
     */
    private static final int HISTORY_MAX_SIZE = 20;

    /**
     * 搜尋建議最大回傳筆數
     */
    private static final int SUGGEST_MAX_SIZE = 10;

    /**
     * {@inheritDoc}
     */
    @Override
    public PageResult<SearchResultResponse> search(
            String q, String tag, String sort, int page, int size, Long userId) {

        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        /** 全文搜尋：MultiMatch，title^3, summary^2, content^1 */
        if (StringUtils.hasText(q)) {
            boolQuery.must(Query.of(qb -> qb.multiMatch(mm -> mm
                    .query(q)
                    .fields("title^3", "summary^2", "content^1")
                    .type(TextQueryType.BestFields)
                    .fuzziness("AUTO"))));
        }

        /** 僅搜尋已發布文章（status — ES index uses explicit keyword mapping via createWithMapping()） */
        boolQuery.filter(Query.of(fb -> fb.term(t -> t
                .field("status")
                .value("PUBLISHED"))));

        /** 標籤過濾（Nested Query） */
        if (StringUtils.hasText(tag)) {
            boolQuery.filter(Query.of(fb -> fb.nested(n -> n
                    .path("tags")
                    .query(nq -> nq.term(t -> t
                            .field("tags.slug")
                            .value(tag))))));
        }

        var queryBuilder = NativeQuery.builder()
                .withQuery(Query.of(q2 -> q2.bool(boolQuery.build())))
                .withPageable(PageRequest.of(page - 1, size));

        /** 排序策略 */
        if ("latest".equalsIgnoreCase(sort)) {
            queryBuilder.withSort(sb -> sb.field(f -> f.field("publishedAt").order(SortOrder.Desc)));
        } else if ("hot".equalsIgnoreCase(sort)) {
            queryBuilder.withSort(sb -> sb.field(f -> f.field("viewCount").order(SortOrder.Desc)));
            queryBuilder.withSort(sb -> sb.field(f -> f.field("likeCount").order(SortOrder.Desc)));
        }

        SearchHits<ArticleDocument> hits =
                elasticsearchOperations.search(queryBuilder.build(), ArticleDocument.class);

        List<SearchResultResponse> results = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(this::toSearchResult)
                .collect(Collectors.toList());

        long total = hits.getTotalHits();

        /** 記錄搜尋關鍵字至 Redis */
        if (StringUtils.hasText(q)) {
            recordSearch(q, userId);
        }

        return PageResult.of(page, size, total, results);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> suggest(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return List.of();
        }
        String lower = prefix.toLowerCase();
        /**
         * 取 search:hot 中分數最高的前 100 筆，再於 Java 端過濾前綴。
         * 因 ZINCRBY 使各元素分數不同，ZRANGEBYLEX 僅適用於同分 ZSet，
         * 故此處改以 ZREVRANGE 取熱門詞後進行前綴比對。
         */
        Set<String> hot = redisTemplate.opsForZSet().reverseRange(RedisKeyConstant.SEARCH_HOT_KEY, 0, 99);
        if (hot == null) return List.of();
        return hot.stream()
                .filter(s -> s.startsWith(lower))
                .limit(SUGGEST_MAX_SIZE)
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getHistory(Long userId) {
        List<String> history = redisTemplate.opsForList()
                .range(RedisKeyConstant.getSearchHistoryKey(userId), 0, HISTORY_MAX_SIZE - 1);
        return history == null ? List.of() : history;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearHistory(Long userId) {
        redisTemplate.delete(RedisKeyConstant.getSearchHistoryKey(userId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void indexArticle(ArticleDocument document) {
        articleSearchRepository.save(document);
        log.info("文章已索引至 Elasticsearch：id={}", document.getId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteIndex(String articleUuid) {
        articleSearchRepository.deleteById(articleUuid);
        log.info("文章已從 Elasticsearch 移除：uuid={}", articleUuid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reindexAll() {
        log.info("開始全量重建 Elasticsearch 索引...");
        List<ArticleIndexData> articles = articleFacade.findAllPublishedForIndex();
        List<ArticleDocument> documents = articles.stream()
                .map(this::toDocument)
                .collect(Collectors.toList());
        articleSearchRepository.saveAll(documents);
        /*
         * 時間戳只是給後台顯示用的附帶資訊，寫入失敗不可讓「索引其實已重建成功」的動作
         * 對外回報失敗（否則管理員會重複點擊，每次都真的重建一遍）。與專案內 MQ 發送
         * 一致採 best-effort。
         */
        try {
            redisTemplate.opsForValue().set(RedisKeyConstant.SEARCH_REINDEX_AT_KEY, LocalDateTime.now().toString());
        } catch (Exception e) {
            log.warn("寫入重建時間戳失敗（best-effort，索引已重建完成）：{}", e.getMessage());
        }
        log.info("全量重建完成，共索引 {} 篇文章", documents.size());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SearchIndexStatusResponse getIndexStatus() {
        /*
         * 兩個資料來源各自獨立 try：Redis 掛掉不該讓 ES 的數字消失，反之亦然。
         * 原實作把 Redis 讀取放在 try 之外，Redis 一掛整個方法就拋出，違反本方法
         * 「不拋例外、避免拖垮儀表板整格顯示」的介面契約。
         */
        Long documentCount = readDocumentCount();
        return SearchIndexStatusResponse.builder()
                .documentCount(documentCount)
                .lastReindexAt(readLastReindexAt())
                /* healthy 描述的是 Elasticsearch 可達性，不受 Redis 故障影響 */
                .healthy(documentCount != null)
                .build();
    }

    /**
     * 讀取 Elasticsearch 索引文件數。
     *
     * @return 文件數；ES 不可達時為 {@code null}（呼叫端據此判定 healthy=false）
     */
    private Long readDocumentCount() {
        try {
            return articleSearchRepository.count();
        } catch (Exception e) {
            log.error("查詢 Elasticsearch 索引文件數失敗：{}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 讀取最後一次全量重建的時間戳。
     *
     * @return ISO-8601 時間字串；從未重建過或 Redis 不可達時為 {@code null}
     */
    private String readLastReindexAt() {
        try {
            return redisTemplate.opsForValue().get(RedisKeyConstant.SEARCH_REINDEX_AT_KEY);
        } catch (Exception e) {
            log.warn("讀取重建時間戳失敗，視同從未重建：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 記錄搜尋關鍵字至 Redis 熱門榜與個人歷史
     *
     * @param keyword 搜尋關鍵字
     * @param userId  用戶 ID（匿名為 null）
     */
    private void recordSearch(String keyword, Long userId) {
        String lower = keyword.toLowerCase();
        /** 更新熱門搜尋 ZSet（分數遞增），限制最多 500 個成員避免無限增長 */
        redisTemplate.opsForZSet().incrementScore(RedisKeyConstant.SEARCH_HOT_KEY, lower, 1.0);
        Long hotSize = redisTemplate.opsForZSet().zCard(RedisKeyConstant.SEARCH_HOT_KEY);
        if (hotSize != null && hotSize > 500) {
            redisTemplate.opsForZSet().removeRange(RedisKeyConstant.SEARCH_HOT_KEY, 0, hotSize - 501);
        }

        /** 記錄個人搜尋歷史 */
        if (userId != null) {
            String historyKey = RedisKeyConstant.getSearchHistoryKey(userId);
            redisTemplate.opsForList().leftPush(historyKey, lower);
            redisTemplate.opsForList().trim(historyKey, 0, HISTORY_MAX_SIZE - 1);
            redisTemplate.expire(historyKey, RedisKeyConstant.SEARCH_HISTORY_TTL_DAYS, java.util.concurrent.TimeUnit.DAYS);
        }
    }

    /**
     * 將 ArticleDocument 轉換為 SearchResultResponse
     *
     * @param doc ES document
     * @return 搜尋結果 DTO
     */
    private SearchResultResponse toSearchResult(ArticleDocument doc) {
        List<String> tagNames = doc.getTags() == null ? List.of() :
                doc.getTags().stream().map(ArticleDocument.TagInfo::getName).collect(Collectors.toList());
        return SearchResultResponse.builder()
                .articleUuid(UUID.fromString(doc.getId()))
                .title(doc.getTitle())
                .summary(doc.getSummary())
                .slug(doc.getSlug())
                .authorNickname(doc.getAuthor() != null ? doc.getAuthor().getNickname() : null)
                .tagNames(tagNames)
                .publishedAt(doc.getPublishedAt())
                .viewCount(doc.getViewCount())
                .likeCount(doc.getLikeCount())
                .build();
    }

    /**
     * 將 ArticleIndexData 轉換為 ArticleDocument
     *
     * @param data 文章索引資料
     * @return ES document
     */
    private ArticleDocument toDocument(ArticleIndexData data) {
        List<ArticleDocument.TagInfo> tags = data.tags() == null ? List.of() :
                data.tags().stream()
                        .map(t -> new ArticleDocument.TagInfo(t.id(), t.name(), t.slug()))
                        .collect(Collectors.toList());
        return ArticleDocument.builder()
                .id(data.articleUuid().toString())
                .title(data.title())
                .summary(data.summary())
                .content(data.contentText())
                .slug(data.slug())
                .author(new ArticleDocument.AuthorInfo(
                        data.authorId(), data.authorUsername(), data.authorNickname()))
                .tags(tags)
                .publishedAt(data.publishedAt())
                .viewCount(data.viewCount())
                .likeCount(data.likeCount())
                .status("PUBLISHED")
                .build();
    }
}
