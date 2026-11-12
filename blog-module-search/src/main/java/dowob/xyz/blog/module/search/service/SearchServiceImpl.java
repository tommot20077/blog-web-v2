package dowob.xyz.blog.module.search.service;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleEngagementCounts;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.index.ArticleIndexAdmin;
import dowob.xyz.blog.module.search.reindex.ReindexJobStore;
import dowob.xyz.blog.module.search.reindex.ReindexJournal;
import dowob.xyz.blog.module.search.model.dto.response.SearchIndexStatusResponse;
import dowob.xyz.blog.module.search.model.dto.response.SearchReindexJobResponse;
import dowob.xyz.blog.module.search.model.dto.response.SearchResultResponse;
import dowob.xyz.blog.module.search.repository.ArticleSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilterBuilder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
     * 文章 Facade（搜尋結果的互動計數）
     */
    private final ArticleFacade articleFacade;

    /**
     * 文章索引管理（作者暱稱改寫經 alias）
     */
    private final ArticleIndexAdmin indexAdmin;

    /**
     * 全量重建期間的增量變動紀錄（D1：重建進行中，listener 的寫入只會進到舊索引，先記下來供切換前後補齊）
     */
    private final ReindexJournal reindexJournal;

    /**
     * 全量重建作業狀態（狀態查詢附帶最近一次作業）
     */
    private final ReindexJobStore reindexJobStore;

    /**
     * 個人搜尋歷史最大保留筆數
     */
    private static final int HISTORY_MAX_SIZE = 20;

    /**
     * 搜尋建議最大回傳筆數
     */
    private static final int SUGGEST_MAX_SIZE = 10;

    /**
     * 重建變動紀錄（Redis）寫入失敗、仍照常更新 ES 的次數（tag {@code kind}：{@code article}／{@code author}，決策 D1-1）
     */
    public static final String JOURNAL_FAILED_METRIC = "search.reindex.journal.failed";

    /** 可計入全站熱門搜尋詞的最大長度（正規化後；超過者搜尋照常但不進榜，SEC-05） */
    static final int HOT_TERM_MAX_LENGTH = 50;

    /**
     * ES 預設的 {@code index.max_result_window}；{@code page × size} 超過時搜尋請求回 400
     */
    private static final int MAX_RESULT_WINDOW = 10_000;

    /**
     * {@inheritDoc}
     */
    @Override
    public PageResult<SearchResultResponse> search(
            String q, String tag, String sort, int page, int size, UUID userUuid) {

        /* from + size 超過 ES index.max_result_window 會被 ES 拒絕並變成 500，這裡提前以 400 拒絕 */
        if ((long) page * size > MAX_RESULT_WINDOW) {
            throw new BusinessException(CommonErrorCode.PARAM_VALID_ERROR,
                    "搜尋結果深度超出上限：page × size 不可超過 " + MAX_RESULT_WINDOW);
        }

        /*
         * sort 白名單：只接受 relevance 與 latest（不分大小寫），其餘一律 400。
         * 放在 service 而非 controller，是因為排序策略（含哪些值有效）本來就由 service 的查詢組裝決定，
         * 白名單與 ES 排序分支放在同一處才不會漂移。
         * 不靜默退回相關度排序：呼叫端傳 hot（B4 已移除，索引內沒有可靠的計數可排）、popularity、date 等
         * 都代表它預期另一種排序；靜默退回會讓它以為拿到了熱門榜／日期序，錯誤要在第一次呼叫就浮現。
         * null 只可能來自內部呼叫（HTTP 端有預設值 relevance），視同 relevance。
         */
        if (sort != null && !"relevance".equalsIgnoreCase(sort) && !"latest".equalsIgnoreCase(sort)) {
            throw new BusinessException(CommonErrorCode.PARAM_VALID_ERROR,
                    "sort 僅支援 relevance 或 latest");
        }

        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        /*
         * 全文搜尋（D4／PERF-35）：標題與摘要保留 fuzziness（短欄位，容錯成本低、打錯字很常見），
         * 內文改為精確比對——對整篇正文每個 term 展開編輯距離內的變體既昂貴，又會讓近似字
         * 在長文裡大量誤中。兩個 should 至少須命中一個；權重沿用原本的 title^3、summary^2、content^1。
         */
        if (StringUtils.hasText(q)) {
            boolQuery.must(Query.of(qb -> qb.bool(b -> b
                    .should(Query.of(sq -> sq.multiMatch(mm -> mm
                            .query(q)
                            .fields("title^3", "summary^2")
                            .type(TextQueryType.BestFields)
                            .fuzziness("AUTO"))))
                    .should(Query.of(sq -> sq.match(m -> m
                            .field("content")
                            .query(q)
                            .boost(1.0f))))
                    .minimumShouldMatch("1"))));
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
                .withPageable(PageRequest.of(page - 1, size))
                /* 結果 DTO 不使用 content，排除以省下大欄位的傳輸與反序列化 */
                .withSourceFilter(new FetchSourceFilterBuilder().withExcludes("content").build());

        /** 排序策略 */
        if ("latest".equalsIgnoreCase(sort)) {
            queryBuilder.withSort(sb -> sb.field(f -> f.field("publishedAt").order(SortOrder.Desc)));
        }

        SearchHits<ArticleDocument> hits =
                elasticsearchOperations.search(queryBuilder.build(), ArticleDocument.class);

        List<ArticleDocument> documents = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());
        /* 計數與詳情頁同源（B4）：整頁一次批次查詢，不使用 ES 文件內的值 */
        Map<UUID, ArticleEngagementCounts> counts = loadEngagementCounts(documents);
        List<SearchResultResponse> results = documents.stream()
                .map(doc -> toSearchResult(doc, counts))
                .collect(Collectors.toList());

        long total = hits.getTotalHits();

        /** 記錄搜尋關鍵字至 Redis（熱門詞僅在有命中已發布文章時計入，見 recordSearch） */
        if (StringUtils.hasText(q)) {
            recordSearch(q, userUuid, total > 0);
        }

        return PageResult.of(page, size, total, results);
    }

    /**
     * 整頁搜尋結果的互動計數，一次批次向 article 模組取得（DB 一條批次 SQL + Redis 一次 MGET）。
     *
     * @param documents 本頁命中的 ES 文件
     * @return 文章 UUID 對應互動計數；沒有命中時不查詢，回傳空 Map
     */
    private Map<UUID, ArticleEngagementCounts> loadEngagementCounts(List<ArticleDocument> documents) {
        List<UUID> uuids = documents.stream()
                .map(ArticleDocument::getId)
                .map(SearchServiceImpl::parseUuidOrNull)
                .filter(Objects::nonNull)
                .toList();
        return uuids.isEmpty() ? Map.of() : articleFacade.getEngagementCounts(uuids);
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
    public List<String> getHistory(UUID userUuid) {
        List<String> history = redisTemplate.opsForList()
                .range(RedisKeyConstant.getSearchHistoryKey(userUuid), 0, HISTORY_MAX_SIZE - 1);
        return history == null ? List.of() : history;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearHistory(UUID userUuid) {
        redisTemplate.delete(RedisKeyConstant.getSearchHistoryKey(userUuid));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void indexArticle(ArticleDocument document, UUID eventId) {
        /* 先記錄、再寫入（D1）：重建進行中時這次寫入只會落在舊索引，記錄讓重建在切換前後補進新索引 */
        recordJournalTolerantly(() -> reindexJournal.recordArticle(document.getId()), "article", document.getId(), eventId);
        articleSearchRepository.save(document);
        log.info("文章已索引至 Elasticsearch：id={}", document.getId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteIndex(String articleUuid, UUID eventId) {
        /* 先記錄、再刪除（D1）：重建補齊時回查 DB，已非 PUBLISHED 者從新索引移除 */
        recordJournalTolerantly(() -> reindexJournal.recordArticle(articleUuid), "article", articleUuid, eventId);
        articleSearchRepository.deleteById(articleUuid);
        log.info("文章已從 Elasticsearch 移除：uuid={}", articleUuid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long updateAuthorNickname(UUID authorUuid, String nickname, UUID eventId) {
        /* 先記錄、再改寫（D1）：重建進行中時新索引的該作者文件可能帶著舊暱稱，切換前後以當下暱稱再改寫一次 */
        recordJournalTolerantly(() -> reindexJournal.recordAuthor(authorUuid), "author", authorUuid.toString(), eventId);
        long updated = indexAdmin.updateAuthorNickname(authorUuid, nickname, ArticleIndexAdmin.ALIAS);
        log.info("已改寫索引內作者暱稱：authorUuid={}, updated={}", authorUuid, updated);
        return updated;
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
                .reindex(readReindexJob())
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
     * 寫入重建變動紀錄；Redis 故障（資料存取例外）時記 WARN 並計數，<b>照常繼續更新 ES</b>，不往外拋（決策 D1-1，Yuan 2026-11-07）。
     *
     * <p><b>取捨</b>：若把 Redis 故障往外拋，listener 會重試、耗盡進 DLQ——平常沒有重建時，Redis 抖動就會讓文章發布／下架
     * 進不了搜尋索引，代價遠大於收益。照常寫 ES 的代價只在「剛好有重建進行中」時出現：這一筆變動寫進了舊索引、
     * 沒被記下，切換後可能沒進新索引；再跑一次重建即可補回（{@value #JOURNAL_FAILED_METRIC} 計數與 WARN 用來發現這種情況）。
     * Redis 長時間中斷時，重建本身會因無法續鎖、無法續期變動紀錄而自行中止且不切換 alias，因此不會出現「長時間漏記卻照樣切換」。</p>
     *
     * <p>只接住 {@link DataAccessException}（Spring Data Redis 把連線失敗、逾時、指令錯誤都轉成它的子類別）；
     * 其他例外代表程式錯誤，原樣往外拋。計數本身絕不外拋。</p>
     *
     * @param record  寫入變動紀錄的動作
     * @param kind    {@code article} 或 {@code author}（指標 tag）
     * @param subject 文章或作者 UUID 字串（寫進日誌）
     * @param eventId 觸發本次寫入的事件 id（寫進日誌；可為 {@code null}）
     */
    private void recordJournalTolerantly(Runnable record, String kind, String subject, UUID eventId) {
        try {
            record.run();
        } catch (DataAccessException e) {
            log.warn("搜尋索引重建變動紀錄寫入失敗（Redis），照常更新 ES：{}Uuid={}, eventId={}；若此刻有全量重建進行中，"
                    + "這筆變動可能不會進入新索引，再執行一次重建即可補回。原因：{}", kind, subject, eventId, e.getMessage());
            incrementJournalFailed(kind);
        }
    }

    /**
     * 累加 {@value #JOURNAL_FAILED_METRIC}；旁路觀測，絕不外拋。全域 registry 未掛任何子 registry 時跳過。
     *
     * @param kind {@code article} 或 {@code author}
     */
    private static void incrementJournalFailed(String kind) {
        try {
            if (Metrics.globalRegistry.getRegistries().isEmpty()) {
                return;
            }
            Counter.builder(JOURNAL_FAILED_METRIC).tag("kind", kind).register(Metrics.globalRegistry).increment();
        } catch (RuntimeException e) {
            log.warn("指標 {}（kind={}）計數失敗，已忽略", JOURNAL_FAILED_METRIC, kind, e);
        }
    }

    /**
     * 讀取最近一次全量重建作業（D1）。
     *
     * @return 作業；從未重建或 Redis 不可達時為 {@code null}
     */
    private SearchReindexJobResponse readReindexJob() {
        try {
            return reindexJobStore.current().orElse(null);
        } catch (Exception e) {
            log.warn("讀取搜尋索引重建作業狀態失敗，視同沒有作業：{}", e.getMessage());
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
     * <p>SEC-05（Yuan 2026-11-04 決定，Q15）：全站熱門詞 ZSet 只計入「有命中已發布文章」且正規化後
     * 長度不超過 {@link #HOT_TERM_MAX_LENGTH} 的詞，使匿名者灌不進文章裡沒有的字，也灌不進超長字串；
     * 匿名搜尋有結果時仍計入。個人搜尋歷史不受此規則影響。</p>
     *
     * @param keyword    搜尋關鍵字
     * @param userUuid   用戶 UUID（匿名為 null）
     * @param hasResults 本次搜尋是否有命中（total &gt; 0）
     */
    private void recordSearch(String keyword, UUID userUuid, boolean hasResults) {
        String lower = keyword.toLowerCase();
        String hotTerm = lower.trim().replaceAll("\\s+", " ");
        if (hasResults && hotTerm.length() <= HOT_TERM_MAX_LENGTH) {
            /** 更新熱門搜尋 ZSet（分數遞增），限制最多 500 個成員避免無限增長 */
            redisTemplate.opsForZSet().incrementScore(RedisKeyConstant.SEARCH_HOT_KEY, hotTerm, 1.0);
            Long hotSize = redisTemplate.opsForZSet().zCard(RedisKeyConstant.SEARCH_HOT_KEY);
            if (hotSize != null && hotSize > 500) {
                redisTemplate.opsForZSet().removeRange(RedisKeyConstant.SEARCH_HOT_KEY, 0, hotSize - 501);
            }
        }

        /** 記錄個人搜尋歷史 */
        if (userUuid != null) {
            String historyKey = RedisKeyConstant.getSearchHistoryKey(userUuid);
            redisTemplate.opsForList().leftPush(historyKey, lower);
            redisTemplate.opsForList().trim(historyKey, 0, HISTORY_MAX_SIZE - 1);
            redisTemplate.expire(historyKey, RedisKeyConstant.SEARCH_HISTORY_TTL_DAYS, java.util.concurrent.TimeUnit.DAYS);
        }
    }

    /**
     * 將 ArticleDocument 轉換為 SearchResultResponse
     *
     * @param doc    ES document
     * @param counts 文章互動計數（與詳情頁同源）；查無該篇時計數為 0
     * @return 搜尋結果 DTO
     */
    private SearchResultResponse toSearchResult(ArticleDocument doc, Map<UUID, ArticleEngagementCounts> counts) {
        ArticleEngagementCounts engagement =
                counts.getOrDefault(parseUuidOrNull(doc.getId()), ArticleEngagementCounts.ZERO);
        List<String> tagNames = doc.getTags() == null ? List.of() :
                doc.getTags().stream().map(ArticleDocument.TagInfo::getName).collect(Collectors.toList());
        return SearchResultResponse.builder()
                .articleUuid(UUID.fromString(doc.getId()))
                .title(doc.getTitle())
                .summary(doc.getSummary())
                .slug(doc.getSlug())
                .authorNickname(doc.getAuthor() != null ? doc.getAuthor().getNickname() : null)
                .authorUuid(doc.getAuthor() != null ? parseUuidOrNull(doc.getAuthor().getUuid()) : null)
                .tagNames(tagNames)
                .publishedAt(doc.getPublishedAt())
                .viewCount(engagement.viewCount())
                .likeCount(engagement.likeCount())
                .build();
    }

    /**
     * 寬鬆解析 UUID 字串：null、空白或格式不符一律回傳 null，不拋例外
     *
     * <p>ES 文件的 {@code author.uuid} 是 Keyword 字串，舊文件或髒資料可能不是合法 UUID；
     * 搜尋結果不應因單筆文件的作者欄位異常而整頁失敗。</p>
     *
     * @param value UUID 字串
     * @return 解析成功的 UUID；否則 null
     */
    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
