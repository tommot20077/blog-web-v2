package dowob.xyz.blog.module.search.job;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.repository.ArticleSearchRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilterBuilder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ES 幽靈文件校正排程（DATA-04）：每日掃描 {@code blog_articles} 索引，刪除「ES 有、DB 已非 PUBLISHED」的文件。
 *
 * <p>下架與刪除靠 best-effort 的 MQ 事件（{@code ArticleSearchListener}）即時移除文件，producer 端事件遺失時，
 * 幽靈文件會一直留著，搜尋結果可以點進已下架或已刪除的文章。原本只有手動 {@code reindexAll} 會清；
 * 本排程是第二道保險。掃描、分批、中止門檻、分散式鎖、游標與指標由 {@link OrphanReconciliationJob} 框架負責，
 * 這裡只提供識別與三個鉤子：</p>
 * <ul>
 *   <li>被參照的 UUID 取自文件的頂層 {@code uuid} keyword 欄位，以 {@code search_after} keyset 分頁、
 *       依該欄位升冪，不用 from/size 深分頁。查詢帶 {@code exists: uuid} 過濾：欄位上線前寫入的舊文件
 *       <b>不會被掃描，也就不會被刪</b>，等一次 {@code reindexAll} 補上欄位後才納入</li>
 *   <li><b>與框架預設語意刻意不同</b>：框架 JavaDoc 要求 {@code filterExisting} 回傳「存在（不分狀態）」，
 *       但索引只應存在 PUBLISHED 文章，草稿／待審／已下架的文件在這裡就是幽靈。因此委派
 *       {@link ArticleFacade#filterPublishedUuids}，語意是「仍為 PUBLISHED」；與
 *       {@code SearchServiceImpl#reindexAll} 的幽靈判準一致</li>
 *   <li>孤兒處理是從 ES 刪除該文件；沒有其他表需要修正</li>
 *   <li>fail-safe 完全沿用框架：facade 拋例外 → 整次 failed、零刪除；facade 異常回空集合 →
 *       「全部都是幽靈」或孤兒過多 → aborted、零刪除。本類別不覆寫中止門檻</li>
 * </ul>
 *
 * <p><b>不在範圍</b>：反方向（DB 是 PUBLISHED 但 ES 缺文件）由 {@code reindexAll} 補，本排程不處理。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
public class ArticleIndexOrphanReconciliationJob extends OrphanReconciliationJob {

    /**
     * 排程時間：每日 04:29（伺服器時區），秒 分 時 日 月 週。
     *
     * <p>與既有排程錯開：comment 03:17、reading 03:23／29／35／41、version 03:47、article 03:53、
     * tag 03:59／04:11、file 04:23、idempotency 清理 04:37，以及每小時 :07 的 user 事件補送；
     * 排程執行緒池為單執行緒（PERF-16），同時觸發只會互相拖延。</p>
     */
    public static final String CRON = "0 29 4 * * *";

    /**
     * keyset 排序欄位：文件頂層 {@code uuid} keyword（與 {@code _id} 同值）。
     *
     * <p>不能用 {@code _id} 排序：ES 預設停用 {@code _id} 的 fielddata（實測回 400
     * {@code Fielddata access on the _id field is disallowed}）。{@code uuid} 唯一，適合作為 search_after 的排序鍵；
     * 排序是字串順序，只需自洽（游標與排序同源），不需要與 PostgreSQL 的 uuid 順序一致。</p>
     *
     * <p><b>已知限制</b>：本欄位上線前寫入的舊文件沒有此欄位，要等一次 {@code reindexAll}（以 id 覆寫文件）才會補上；
     * 查詢以 {@code exists} 過濾掉它們，補上前不會被本排程掃到（只是尚未校正，不會誤刪，也不會因
     * 缺值排序而讓 search_after 卡在同一批）。</p>
     *
     * <p><b>型別風險</b>：若既有索引在 mapping 增補前就被動態寫入而建成 text，排序會回 400；
     * 復原方式見 {@link #RECOVERY_HINT}。</p>
     */
    public static final String SORT_FIELD = "uuid";

    /** 排序欄位型別錯誤時的復原方式（initializer 與本 job 的 ERROR 訊息共用同一句） */
    static final String RECOVERY_HINT = "復原方式：刪除索引（blog_articles）後由 SearchIndexInitializer 以 annotation mapping 重建"
            + "（重啟應用程式），再執行 POST /api/v1/admin/search/reindex";

    private final ElasticsearchOperations operations;
    private final ArticleSearchRepository repository;
    private final ArticleFacade articleFacade;

    /**
     * 建立排程。指標走全域 registry（正式環境由 actuator 綁定）。
     *
     * @param redis         Redis 模板（分散式鎖與游標）
     * @param operations    ES 操作（keyset 掃描）
     * @param repository    文章索引 repository（刪除）
     * @param articleFacade 文章 facade（PUBLISHED 查證）
     */
    @Autowired
    public ArticleIndexOrphanReconciliationJob(StringRedisTemplate redis, ElasticsearchOperations operations,
                                               ArticleSearchRepository repository, ArticleFacade articleFacade) {
        super(redis);
        this.operations = operations;
        this.repository = repository;
        this.articleFacade = articleFacade;
    }

    /**
     * 測試用建構子：指定 registry、頁大小與單次頁數上限。
     *
     * @param redis         Redis 模板
     * @param registry      指標註冊表
     * @param operations    ES 操作
     * @param repository    文章索引 repository
     * @param articleFacade 文章 facade
     * @param pageSize      單頁筆數
     * @param maxPages      單次執行頁數上限
     */
    ArticleIndexOrphanReconciliationJob(StringRedisTemplate redis, MeterRegistry registry,
                                        ElasticsearchOperations operations, ArticleSearchRepository repository,
                                        ArticleFacade articleFacade, int pageSize, int maxPages) {
        super(redis, registry, pageSize, maxPages);
        this.operations = operations;
        this.repository = repository;
        this.articleFacade = articleFacade;
    }

    /** 每日排程入口：委派框架的 {@link #run()}（結果與日誌由框架記錄，run 不拋例外）。 */
    @Scheduled(cron = CRON)
    void scheduledRun() {
        run();
    }

    @Override
    protected String module() {
        return "search";
    }

    @Override
    protected String table() {
        return "blog_articles";
    }

    /**
     * 以 {@code search_after} keyset 分頁取得索引內的文章 UUID（依 {@code uuid} 欄位升冪）。
     *
     * <p>永遠取第 0 頁，游標以 search_after 帶入，避免 from/size 深分頁；只取 slug 欄位，不拉整份內文；
     * 只掃有 {@code uuid} 欄位的文件（{@code exists} 過濾）。</p>
     *
     * <p>id 不是 UUID 的垃圾文件略過（不讓整次掃描失敗），留給全量重建清除。<b>略過後若不足 limit，
     * 會以 ES 回傳的原始 sort 值當游標繼續往下取</b>，直到湊滿 limit 或 ES 沒有更多資料為止：
     * 框架把「少於 limit 筆」視為最後一頁，若這裡因略過而回短頁，會提早把游標清掉、漏掃其後的文件。</p>
     *
     * <p>ES 回 400（例如 {@code uuid} 被動態建成 text 而無法排序）時記一行可行動的 ERROR 並原樣往外拋，
     * 框架會整次 failed、零刪除；連線類錯誤不屬此類，直接往外拋。</p>
     *
     * @param afterExclusive 游標（不含）；null 表示從頭
     * @param limit          本頁筆數上限
     * @return 升冪的文章 UUID；少於 limit 筆代表 ES 已沒有更多資料
     */
    @Override
    protected List<UUID> fetchReferencedUuids(UUID afterExclusive, int limit) {
        List<UUID> uuids = new ArrayList<>();
        Object cursor = afterExclusive == null ? null : afterExclusive.toString();
        try {
            while (uuids.size() < limit) {
                int wanted = limit - uuids.size();
                NativeQueryBuilder builder = NativeQuery.builder()
                        .withQuery(q -> q.exists(e -> e.field(SORT_FIELD)))
                        .withSort(Sort.by(Sort.Order.asc(SORT_FIELD)))
                        .withPageable(PageRequest.of(0, wanted))
                        .withSourceFilter(new FetchSourceFilterBuilder().withIncludes("slug").build());
                if (cursor != null) {
                    builder.withSearchAfter(List.of(cursor));
                }
                List<SearchHit<ArticleDocument>> hits =
                        operations.search(builder.build(), ArticleDocument.class).getSearchHits();
                hits.forEach(hit -> addIfUuid(uuids, hit.getId()));
                if (hits.size() < wanted) {
                    break;
                }
                List<Object> lastSortValues = hits.get(hits.size() - 1).getSortValues();
                if (lastSortValues == null || lastSortValues.isEmpty()) {
                    break;
                }
                cursor = lastSortValues.get(0);
            }
        } catch (RuntimeException e) {
            if (isElasticsearchBadRequest(e)) {
                log.error("ES 幽靈校正掃描被拒絕（HTTP 400）：排序欄位 {} 可能被動態建成 text 而無法排序。{}；原因：{}",
                        SORT_FIELD, RECOVERY_HINT, e.getMessage());
            }
            throw e;
        }
        return uuids;
    }

    private void addIfUuid(List<UUID> uuids, String id) {
        try {
            uuids.add(UUID.fromString(id));
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("ES 索引含非 UUID 的文件 id，校正略過（由全量重建清除）：id={}", id);
        }
    }

    private static boolean isElasticsearchBadRequest(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ElasticsearchException ee && ee.status() == 400) {
                return true;
            }
        }
        return false;
    }

    /**
     * 委派 {@link ArticleFacade#filterPublishedUuids}：語意是「DB 現在仍為 PUBLISHED」，不是「存在」。
     *
     * <p>不在回傳集合內者（已下架、退回草稿、已刪除）即為幽靈。facade 拋例外時原樣往外拋，框架會整次中止。</p>
     *
     * @param uuids 待確認的文章 UUID
     * @return 仍為 PUBLISHED 的文章 UUID
     */
    @Override
    protected Set<UUID> filterExisting(Collection<UUID> uuids) {
        return articleFacade.filterPublishedUuids(uuids);
    }

    /**
     * 從 ES 刪除幽靈文件（以文件 id，冪等：文件已不在不會出錯）。
     *
     * @param missing 已確認不再是 PUBLISHED 的文章 UUID
     * @return 嘗試刪除的文件數
     */
    @Override
    protected int removeOrphans(Collection<UUID> missing) {
        if (missing.isEmpty()) {
            return 0;
        }
        repository.deleteAllById(missing.stream().map(UUID::toString).toList());
        return missing.size();
    }
}
