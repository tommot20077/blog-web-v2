package dowob.xyz.blog.module.search.service;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleEngagementCounts;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
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
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.SearchHits;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import org.springframework.data.elasticsearch.core.query.ByQueryResponse;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilterBuilder;
import org.springframework.data.elasticsearch.core.query.ScriptType;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * 文章 Facade（用於全量重建索引）
     */
    private final ArticleFacade articleFacade;

    /**
     * 使用者 Facade（取得作者當下的顯示資訊）
     */
    private final UserFacade userFacade;

    /**
     * 個人搜尋歷史最大保留筆數
     */
    private static final int HISTORY_MAX_SIZE = 20;

    /**
     * 搜尋建議最大回傳筆數
     */
    private static final int SUGGEST_MAX_SIZE = 10;

    /** 可計入全站熱門搜尋詞的最大長度（正規化後；超過者搜尋照常但不進榜，SEC-05） */
    static final int HOT_TERM_MAX_LENGTH = 50;

    /**
     * 幽靈 document 掃描的每頁筆數
     */
    private static final int GHOST_SCAN_PAGE_SIZE = 1000;

    /**
     * 幽靈 document 掃描的最大頁數
     *
     * <p>from + size 分頁受 ES {@code index.max_result_window}（預設 10000）限制，
     * 故 1000 × 10 為上限；超過時記 ERROR，本輪只清掃描範圍內的殘留。</p>
     */
    private static final int GHOST_SCAN_MAX_PAGES = 10;

    /**
     * ES 預設的 {@code index.max_result_window}；{@code page × size} 超過時搜尋請求回 400
     */
    private static final int MAX_RESULT_WINDOW = 10_000;

    /**
     * 幽靈清除日誌中列出的 id 樣本數上限
     *
     * <p>只記數量與前幾筆即可定位；把整份 id 塞進單行日誌，索引長期漂移後會是
     * 數千個 UUID 擠成一行，反而讓日誌難讀（也可能被 log pipeline 截斷）。</p>
     */
    private static final int GHOST_LOG_SAMPLE_SIZE = 10;

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
         * B4（DATA-05）：sort=hot 已移除。它依賴索引內的 viewCount／likeCount，而那兩個值在增量索引時
         * 硬編 0、之後也沒有任何路徑更新，排序等於沒排。要讓它有意義得靠高頻更新 ES（每次瀏覽／按讚都要寫索引），
         * 代價遠大於效益，且熱門排序在前端已無使用者。靜默退回相關度排序會讓呼叫端以為拿到了熱門榜，
         * 故明確回 400，讓仍傳 hot 的舊客戶端能被看見並修正。
         */
        if ("hot".equalsIgnoreCase(sort)) {
            throw new BusinessException(CommonErrorCode.PARAM_VALID_ERROR,
                    "sort=hot 已移除，請改用 relevance 或 latest");
        }

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
    public long updateAuthorNickname(UUID authorUuid, String nickname) {
        org.springframework.data.elasticsearch.core.query.Query matchAuthor = NativeQuery.builder()
                .withQuery(Query.of(q -> q.term(t -> t.field("author.uuid").value(authorUuid.toString()))))
                .build();
        UpdateQuery updateQuery = UpdateQuery.builder(matchAuthor)
                .withScriptType(ScriptType.INLINE)
                .withLang("painless")
                .withScript("ctx._source.author.nickname = params.nickname")
                .withParams(Map.of("nickname", nickname))
                .withAbortOnVersionConflict(false)
                .withRefreshPolicy(RefreshPolicy.IMMEDIATE)
                .build();
        ByQueryResponse response = elasticsearchOperations.updateByQuery(
                updateQuery, elasticsearchOperations.getIndexCoordinatesFor(ArticleDocument.class));
        log.info("已改寫索引內作者暱稱：authorUuid={}, updated={}", authorUuid, response.getUpdated());
        return response.getUpdated();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reindexAll() {
        log.info("開始全量重建 Elasticsearch 索引...");
        List<ArticleIndexData> articles = articleFacade.findAllPublishedForIndex();
        /* 作者暱稱一律取當下值（一次批次查詢），不用 DB 快照內冗餘的舊暱稱（M3） */
        Map<UUID, UserDisplayInfo> authors = loadAuthors(articles);
        List<ArticleDocument> documents = articles.stream()
                .map(a -> toDocument(a, authors))
                .collect(Collectors.toList());
        articleSearchRepository.saveAll(documents);
        /*
         * 先寫入再清除：任何時點索引裡都不會少掉一篇仍公開的文章（若先 delete 整個索引，
         * 重建期間搜尋會回空）。清除的對象是「ES 有但 DB 已非 PUBLISHED」的幽靈 document。
         */
        removeGhostDocuments(documents.size());
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
     * 清除幽靈 document —— 索引裡有、但 DB 已經不是 PUBLISHED 的文件。
     *
     * <p><b>為什麼需要</b>：文件寫入 ES 時 status 是硬寫的常數 {@code "PUBLISHED"}
     * （見 {@code ArticleSearchListener#toDocument}），DB 改狀態不會反映到索引，
     * 搜尋的 status filter 對殘留文件恆為 true。刪除／下架都靠 MQ 事件即時刪 document，
     * 一旦那條路徑失手（MQ 不可用、consumer NACK 進 DLQ），文件就永久殘留。
     * 原本的 {@code reindexAll} 只做 {@code saveAll}、從不刪任何文件，
     * 所以「重建索引」修不好這件事——這正是
     * {@code ai-docs/backlog/2026-07-29-index-cache-rebuild-completeness.md} 記載的缺口。</p>
     *
     * <p><b>為什麼不 drop 整個索引再重建</b>：重建期間搜尋會回空，對線上讀取是可見的退化。
     * 改為掃描索引現況、只刪對不上 DB 的文件，重建全程搜尋結果都是完整的。
     * （完整的 alias 切換方案仍在上述 backlog，本方法只解「幽靈文件」這一半。）</p>
     *
     * <p><b>判準必須即時，不能用快照</b>：重建開始時撈到的 PUBLISHED 清單只是那一瞬間的樣子。
     * 若拿它當「誰是幽靈」的判準，在 {@code saveAll} 與掃描之間被發布、由 MQ consumer 寫進索引的
     * 文章就不在清單裡，會被當成幽靈刪掉——文章是 PUBLISHED 卻搜尋不到，而日誌只會說
     * 「已清除 N 筆幽靈」，看不出是誤刪。故改為反向判定：拿掃到的 id 回 DB 問「現在還是 PUBLISHED 嗎」
     * （{@code ArticleFacade#filterPublishedUuids}），只有回查說不是的才刪。</p>
     *
     * <p><b>殘留窗口</b>：回查是逐頁做的，刪除則在整輪掃描結束後一次執行，
     * 因此「回查之後、刪除之前」被發布的文章理論上仍可能被刪。這個窗口是掃描本身的長度
     * （幾次 ES 查詢），已遠小於原本的「整輪重建長度」；要完全消除需要帶版本條件的
     * conditional delete 或 alias 切換，屬上述 backlog 的範圍。</p>
     *
     * <p>清除是重建的附加保險：掃描或刪除失敗只記 log，不讓已成功的重建對外報錯
     * （與時間戳寫入同一套 best-effort 判準）。</p>
     *
     * @param publishedCount 本次重建寫入的文件數（即 DB 快照裡的 PUBLISHED 篇數），僅供健全性檢查
     */
    private void removeGhostDocuments(int publishedCount) {
        try {
            List<String> ghostIds = new ArrayList<>();
            boolean scanCompleted = false;
            for (int page = 0; page < GHOST_SCAN_MAX_PAGES; page++) {
                NativeQuery query = NativeQuery.builder()
                        .withQuery(Query.of(q -> q.matchAll(m -> m)))
                        .withPageable(PageRequest.of(page, GHOST_SCAN_PAGE_SIZE))
                        .build();
                List<SearchHit<ArticleDocument>> pageHits =
                        elasticsearchOperations.search(query, ArticleDocument.class).getSearchHits();
                List<String> scannedIds = pageHits.stream()
                        .map(hit -> hit.getContent().getId())
                        .filter(Objects::nonNull)
                        .toList();
                /*
                 * 健全性檢查：DB 回報 0 篇 PUBLISHED、索引卻還有 document。
                 * 門檻取「DB 為 0 且 ES > 0」這個最保守的形狀——正常運作下這兩件事不會同時成立
                 * （有 document 就代表曾經有文章發布過，而全部下架/刪除又同時發生的機率極低），
                 * 遠比「查詢失敗或交易異常回了空 list」來得不可能。若照常清除，一次讀取失敗就會
                 * 被放大成「整個索引被清空」這種破壞性動作。寧可留下幽靈（下架事件與下次重建都還有
                 * 機會清掉），也不要清空索引，並記 ERROR 讓維運知道這次重建沒有做清除。
                 */
                if (publishedCount == 0 && !scannedIds.isEmpty()) {
                    log.error("中止幽靈清除：資料庫回報 0 篇 PUBLISHED，但索引仍有 document（本頁 {} 筆）。"
                            + "這比較可能是查詢或交易異常而非全站真的沒有公開文章，照常清除會清空整個索引，"
                            + "故保留索引並中止清除", scannedIds.size());
                    return;
                }
                ghostIds.addAll(collectGhostIds(scannedIds));
                if (pageHits.size() < GHOST_SCAN_PAGE_SIZE) {
                    scanCompleted = true;
                    break;
                }
            }
            /*
             * 這是「幽靈在重建之後仍存活」的唯一分支，需要人介入（擴大掃描範圍或改走
             * alias 重建），與下架事件發送失敗同一種需告警狀態，故用 ERROR 而非 warn。
             */
            if (!scanCompleted) {
                log.error("索引文件數超過幽靈掃描上限 {} 筆，本次僅清除掃描範圍內的殘留，"
                                + "掃描範圍外的已下架／已刪除文章仍可能被搜尋到",
                        GHOST_SCAN_MAX_PAGES * GHOST_SCAN_PAGE_SIZE);
            }
            if (ghostIds.isEmpty()) {
                log.info("索引與資料庫一致，無幽靈 document 需清除");
                return;
            }
            articleSearchRepository.deleteAllById(ghostIds);
            int sampleSize = Math.min(GHOST_LOG_SAMPLE_SIZE, ghostIds.size());
            log.warn("已清除 {} 筆幽靈 document（ES 有、DB 已非 PUBLISHED），前 {} 筆樣本：{}",
                    ghostIds.size(), sampleSize, ghostIds.subList(0, sampleSize));
        } catch (Exception e) {
            log.error("清除幽靈 document 失敗，索引可能仍殘留已刪除／已下架文章：{}", e.getMessage(), e);
        }
    }

    /**
     * 以「即時回查 DB」判定一頁掃描結果裡哪些是幽靈 document。
     *
     * <p>回查走 {@code ArticleFacade}——{@code articles} 是業務 Data，
     * 跨模組不得直接查表（{@code ai-docs/architecture.md} 邊界速查表）。
     * 一次只送一頁的 id，記憶體與單次 {@code IN (...)} 大小都受 {@link #GHOST_SCAN_PAGE_SIZE} 限制，
     * facade 端會再切批。</p>
     *
     * @param scannedIds 本頁掃描到的 document id
     * @return 其中「DB 已非 PUBLISHED」的 id
     */
    private List<String> collectGhostIds(List<String> scannedIds) {
        if (scannedIds.isEmpty()) {
            return List.of();
        }
        List<String> ghostIds = new ArrayList<>();
        Map<UUID, String> parsedIds = new LinkedHashMap<>();
        for (String id : scannedIds) {
            try {
                parsedIds.put(UUID.fromString(id), id);
            } catch (IllegalArgumentException e) {
                /* document id 一律是文章 uuid 字串（見 ArticleSearchListener#toDocument），
                   解析不出 UUID 的必然不對應任何文章，是純粹的垃圾資料 */
                ghostIds.add(id);
            }
        }
        if (!parsedIds.isEmpty()) {
            Set<UUID> stillPublished = articleFacade.filterPublishedUuids(parsedIds.keySet());
            parsedIds.forEach((uuid, id) -> {
                if (!stillPublished.contains(uuid)) {
                    ghostIds.add(id);
                }
            });
        }
        return ghostIds;
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

    /**
     * 將 ArticleIndexData 轉換為 ArticleDocument
     *
     * @param data    文章索引資料
     * @param authors 作者當下顯示資訊（key 為作者 UUID）
     * @return ES document
     */
    private ArticleDocument toDocument(ArticleIndexData data, Map<UUID, UserDisplayInfo> authors) {
        List<ArticleDocument.TagInfo> tags = data.tags() == null ? List.of() :
                data.tags().stream()
                        .map(t -> new ArticleDocument.TagInfo(t.id(), t.name(), t.slug()))
                        .collect(Collectors.toList());
        return ArticleDocument.builder()
                .id(data.articleUuid().toString())
                .uuid(data.articleUuid().toString())
                .title(data.title())
                .summary(data.summary())
                .content(data.contentText())
                .slug(data.slug())
                .author(new ArticleDocument.AuthorInfo(
                        data.authorUuid() == null ? null : data.authorUuid().toString(),
                        currentNickname(data, authors)))
                .tags(tags)
                .publishedAt(data.publishedAt())
                .status("PUBLISHED")
                .build();
    }

    /**
     * 以單次批次查詢取得所有作者的當下顯示資訊。
     *
     * @param articles 待重建的文章
     * @return 作者 UUID 對應顯示資訊；沒有任何帶 authorUuid 的文章時不查詢
     */
    private Map<UUID, UserDisplayInfo> loadAuthors(List<ArticleIndexData> articles) {
        Set<UUID> authorUuids = articles.stream()
                .map(ArticleIndexData::authorUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return authorUuids.isEmpty() ? Map.of() : userFacade.getDisplayInfoByUuids(authorUuids);
    }

    /**
     * 決定文件的作者暱稱：有 authorUuid 時以當下值為準，查無此人用通用匿名暱稱（不回退到快照的舊暱稱）；
     * 沒有 authorUuid 才沿用快照值。
     *
     * @param data    文章索引資料
     * @param authors 作者當下顯示資訊
     * @return 暱稱
     */
    private String currentNickname(ArticleIndexData data, Map<UUID, UserDisplayInfo> authors) {
        if (data.authorUuid() == null) {
            return data.authorNickname();
        }
        UserDisplayInfo info = authors.get(data.authorUuid());
        return info != null && info.nickname() != null ? info.nickname() : ArticleDocument.ANONYMOUS_NICKNAME;
    }
}
