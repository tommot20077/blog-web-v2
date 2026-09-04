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
     * 個人搜尋歷史最大保留筆數
     */
    private static final int HISTORY_MAX_SIZE = 20;

    /**
     * 搜尋建議最大回傳筆數
     */
    private static final int SUGGEST_MAX_SIZE = 10;

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
