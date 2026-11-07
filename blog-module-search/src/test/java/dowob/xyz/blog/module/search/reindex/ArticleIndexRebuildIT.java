package dowob.xyz.blog.module.search.reindex;

import com.redis.testcontainers.RedisContainer;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.facade.SearchFacadeImpl;
import dowob.xyz.blog.module.search.index.ArticleIndexAdmin;
import dowob.xyz.blog.module.search.index.IndexState;
import dowob.xyz.blog.module.search.init.SearchIndexInitializer;
import dowob.xyz.blog.module.search.model.dto.response.SearchReindexJobResponse;
import dowob.xyz.blog.module.search.model.dto.response.SearchResultResponse;
import dowob.xyz.blog.module.search.repository.ArticleSearchRepository;
import dowob.xyz.blog.module.search.service.SearchServiceImpl;
import dowob.xyz.blog.module.search.support.ArticleIndexTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.repository.support.ElasticsearchRepositoryFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 背景全量重建的整合測試（真實 ES ＋ 真實 Redis，D1／PERF-01）。
 *
 * <p>DB 以 {@link ArticleFacade} 的記憶體替身表示（{@link #db}），其餘元件皆為正式實作：
 * {@link SearchReindexService}（背景執行緒、分散式鎖、作業狀態）、{@link ArticleIndexRebuilder}、
 * {@link ReindexJournal}（Lua）、{@link ArticleIndexAdmin}，增量寫入走 {@link SearchServiceImpl}（listener 呼叫的同一條路）。</p>
 *
 * <p>最關鍵的場景是「重建進行中發布一篇、下架一篇」：以 latch 讓重建停在讀取第一批之後
 * （該批是變動<b>之前</b>的快照），此時 listener 的寫入只會進到舊索引（alias 仍指向它）；
 * 切換後兩個變動都必須正確反映在 alias 上。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("背景全量重建 IT（真實 ES ＋ Redis）")
class ArticleIndexRebuildIT {

    private static final ElasticsearchContainer ELASTICSEARCH = ArticleIndexTestSupport.newContainer();
    private static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");
    private static final Duration WAIT = Duration.ofSeconds(60);

    private static ElasticsearchTemplate template;
    private static ArticleSearchRepository repository;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    /** DB 中目前為 PUBLISHED 的文章（記憶體替身） */
    private final Map<UUID, ArticleIndexData> db = new ConcurrentHashMap<>();

    private ArticleFacade articleFacade;
    private UserFacade userFacade;
    private ArticleIndexAdmin admin;
    private SearchServiceImpl searchService;
    private SearchReindexService reindexService;

    @BeforeAll
    static void startContainers() {
        ELASTICSEARCH.start();
        REDIS.start();
        template = ArticleIndexTestSupport.newTemplate(ELASTICSEARCH);
        repository = new ElasticsearchRepositoryFactory(template).getRepository(ArticleSearchRepository.class);
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopContainers() {
        connectionFactory.destroy();
        REDIS.stop();
        ELASTICSEARCH.stop();
    }

    @BeforeEach
    void setUp() {
        ArticleIndexTestSupport.deleteAllArticleIndices(template);
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        articleFacade = mock(ArticleFacade.class);
        userFacade = mock(UserFacade.class);
        when(userFacade.getDisplayInfoByUuids(anyCollection())).thenReturn(Map.of());
        when(articleFacade.getEngagementCounts(any())).thenReturn(Map.of());
        when(articleFacade.countPublished()).thenAnswer(inv -> (long) db.size());
        when(articleFacade.findPublishedForIndexAfter(any(), anyInt()))
                .thenAnswer(inv -> page(snapshot(), inv.getArgument(0), inv.getArgument(1)));
        when(articleFacade.findPublishedForIndexByUuids(any())).thenAnswer(inv -> {
            Collection<UUID> uuids = inv.getArgument(0);
            return uuids.stream().map(db::get).filter(java.util.Objects::nonNull).toList();
        });

        admin = ArticleIndexTestSupport.newAdmin(template);
        new SearchIndexInitializer(admin).ensureCorrectMapping();
        ReindexJournal journal = new ReindexJournal(redis);
        ReindexJobStore jobStore = new ReindexJobStore(redis);
        ArticleIndexRebuilder rebuilder = new ArticleIndexRebuilder(admin, journal, articleFacade, userFacade);
        searchService = new SearchServiceImpl(template, repository, redis, articleFacade, admin, journal, jobStore);
        reindexService = new SearchReindexService(jobStore, rebuilder, articleFacade, redis);
    }

    @AfterEach
    void tearDown() {
        reindexService.destroy();
    }

    /** DB 目前狀態的快照，依 uuid 字串排序（keyset 替身只需自洽） */
    private List<ArticleIndexData> snapshot() {
        return db.values().stream().sorted(Comparator.comparing(d -> d.articleUuid().toString())).toList();
    }

    private static List<ArticleIndexData> page(List<ArticleIndexData> sorted, UUID after, int limit) {
        int from = 0;
        if (after != null) {
            for (int i = 0; i < sorted.size(); i++) {
                if (sorted.get(i).articleUuid().equals(after)) {
                    from = i + 1;
                }
            }
        }
        return new ArrayList<>(sorted.subList(Math.min(from, sorted.size()), Math.min(from + limit, sorted.size())));
    }

    private static ArticleIndexData article(UUID uuid, String title, UUID authorUuid) {
        return new ArticleIndexData(uuid, title, "slug-" + uuid, "summary of " + title,
                title + " shared corpus about container orchestration and cluster scheduling", "快照暱稱",
                LocalDateTime.of(2026, 11, 1, 9, 0), 0L, 0L, List.of(), authorUuid);
    }

    private static ArticleDocument document(ArticleIndexData data, String nickname) {
        return ArticleDocument.builder().id(data.articleUuid().toString()).uuid(data.articleUuid().toString())
                .title(data.title()).summary(data.summary()).content(data.contentText()).slug(data.slug())
                .status("PUBLISHED").publishedAt(data.publishedAt())
                .author(new ArticleDocument.AuthorInfo(
                        data.authorUuid() == null ? null : data.authorUuid().toString(), nickname))
                .tags(List.of()).build();
    }

    /** 以 DB 現況建立一篇並同步寫進目前的線上索引（模擬重建前已存在的狀態） */
    private ArticleIndexData publishedAndIndexed(String title) {
        ArticleIndexData data = article(UUID.randomUUID(), title, null);
        db.put(data.articleUuid(), data);
        repository.save(document(data, "快照暱稱"));
        return data;
    }

    private void refreshLive() {
        template.indexOps(ArticleDocument.class).refresh();
    }

    private List<UUID> searchUuids(String q) {
        refreshLive();
        PageResult<SearchResultResponse> result = searchService.search(q, null, "relevance", 1, 50, null);
        return result.getRecords().stream().map(SearchResultResponse::getArticleUuid).toList();
    }

    private SearchReindexJobResponse awaitFinished(UUID jobId) throws InterruptedException {
        return awaitFinishedOn(reindexService, jobId);
    }

    private static SearchReindexJobResponse awaitFinishedOn(SearchReindexService service, UUID jobId)
            throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            SearchReindexJobResponse job = service.currentJob().orElseThrow();
            if (job.getJobId().equals(jobId) && job.getState() != ReindexJobState.RUNNING) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("重建在 " + WAIT + " 內沒有結束");
    }

    /**
     * 等到作業自己寫下結束時間（收尾完成後才寫；與「鎖已不在而被判定中斷」區分）。
     *
     * @param jobId 作業 id
     * @return 作業狀態
     */
    private SearchReindexJobResponse awaitRecordedFinish(UUID jobId) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            SearchReindexJobResponse job = reindexService.currentJob().orElseThrow();
            if (job.getJobId().equals(jobId) && job.getFinishedAt() != null) {
                return job;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("作業在 " + WAIT + " 內沒有寫下結束時間");
    }

    /**
     * 讓重建在讀第一批時停住：先取下呼叫當下（變動前）的快照，通知測試執行緒，等放行後才回傳舊快照。
     *
     * @param reading 重建已讀完第一批（尚未寫入）時倒數
     * @param proceed 測試執行緒放行時倒數
     */
    private void pauseFirstBatch(CountDownLatch reading, CountDownLatch proceed) {
        when(articleFacade.findPublishedForIndexAfter(any(), anyInt())).thenAnswer(inv -> {
            List<ArticleIndexData> stale = page(snapshot(), inv.getArgument(0), inv.getArgument(1));
            if (inv.getArgument(0) == null) {
                reading.countDown();
                assertThat(proceed.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            }
            return stale;
        });
    }

    private static <T> T await(Supplier<T> action, CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        return action.get();
    }

    @Test
    @DisplayName("重建前後經 alias 都搜得到；完成後 alias 指向新實體索引、舊索引已刪除；新索引只有 PUBLISHED（舊索引的幽靈文件消失）")
    void rebuild_switchesAliasToFreshIndexWithOnlyPublished() throws Exception {
        ArticleIndexData alpha = publishedAndIndexed("Kubernetes alpha");
        UUID ghost = UUID.randomUUID();
        repository.save(document(article(ghost, "Kubernetes ghost", null), "x"));
        ArticleIndexData beta = article(UUID.randomUUID(), "Kubernetes beta", null);
        db.put(beta.articleUuid(), beta);
        assertThat(searchUuids("Kubernetes")).containsExactlyInAnyOrder(alpha.articleUuid(), ghost);

        ReindexStartResult started = reindexService.start();
        SearchReindexJobResponse job = awaitFinished(started.job().getJobId());

        assertThat(job.getState()).isEqualTo(ReindexJobState.COMPLETED);
        assertThat(job.getProcessed()).isEqualTo(2);
        assertThat(job.getTotal()).isEqualTo(2L);
        assertThat(job.getFinishedAt()).isNotNull();
        IndexState state = admin.inspect();
        assertThat(state.kind()).isEqualTo(IndexState.Kind.ALIAS);
        String physical = state.aliasTargets().get(0);
        assertThat(physical).startsWith(ArticleIndexAdmin.VERSIONED_PREFIX).isNotEqualTo(ArticleIndexAdmin.INITIAL_INDEX);
        assertThat(ArticleIndexTestSupport.articleIndexNames(template)).containsExactly(physical);
        assertThat(template.count(template.matchAllQuery(), ArticleDocument.class, IndexCoordinates.of(physical))).isEqualTo(2);
        assertThat(searchUuids("Kubernetes")).containsExactlyInAnyOrder(alpha.articleUuid(), beta.articleUuid());
        assertThat(redis.hasKey(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY)).isFalse();
        assertThat(redis.hasKey(RedisKeyConstant.LOCK_SEARCH_REINDEX)).isFalse();
        assertThat(redis.opsForValue().get(RedisKeyConstant.SEARCH_REINDEX_AT_KEY)).isNotBlank();
        /* more_like_this 的 like 文件經 alias 解析，切換後照常運作（不寫死實體索引名稱） */
        assertThat(new SearchFacadeImpl(template).findSimilarArticles(alpha.articleUuid(), 5)).contains(beta.articleUuid());
    }

    @Test
    @DisplayName("重建進行中發布一篇新文章、下架一篇舊文章：重建期間 alias 仍是舊索引；切換後兩者都正確反映（不遺失增量）")
    void rebuild_concurrentPublishAndArchive_bothReflectedAfterSwitch() throws Exception {
        ArticleIndexData kept = publishedAndIndexed("Concurrency kept");
        ArticleIndexData archived = publishedAndIndexed("Concurrency archived");
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        pauseFirstBatch(reading, proceed);

        UUID jobId = reindexService.start().job().getJobId();
        await(() -> null, reading);
        /* 重建已讀走「變動前」的快照（含 archived、不含 fresh），此時 listener 處理兩個事件 */
        ArticleIndexData fresh = article(UUID.randomUUID(), "Concurrency fresh", null);
        db.put(fresh.articleUuid(), fresh);
        searchService.indexArticle(document(fresh, "快照暱稱"), UUID.randomUUID());
        db.remove(archived.articleUuid());
        searchService.deleteIndex(archived.articleUuid().toString(), UUID.randomUUID());
        assertThat(searchUuids("Concurrency")).as("重建期間 alias 仍指向舊索引，listener 的寫入進到舊索引")
                .containsExactlyInAnyOrder(kept.articleUuid(), fresh.articleUuid());
        proceed.countDown();

        assertThat(awaitFinished(jobId).getState()).isEqualTo(ReindexJobState.COMPLETED);
        assertThat(admin.inspect().aliasTargets()).doesNotContain(ArticleIndexAdmin.INITIAL_INDEX);
        assertThat(searchUuids("Concurrency"))
                .as("新發布的在、已下架的不在")
                .containsExactlyInAnyOrder(kept.articleUuid(), fresh.articleUuid());
    }

    @Test
    @DisplayName("重建進行中作者刪帳（暱稱匿名化）：即使該批讀到的是舊暱稱，切換後新索引的作者暱稱也是匿名的（PII 不回流）")
    void rebuild_concurrentAuthorAnonymization_reflectedAfterSwitch() throws Exception {
        UUID author = UUID.randomUUID();
        ArticleIndexData data = article(UUID.randomUUID(), "Anonymize target", author);
        db.put(data.articleUuid(), data);
        repository.save(document(data, "愛麗絲"));
        when(userFacade.getDisplayInfoByUuids(anyCollection())).thenReturn(
                Map.of(author, new UserDisplayInfo(author, "愛麗絲", null)),
                Map.of(author, new UserDisplayInfo(author, "已刪除使用者#ab12", null)));
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        pauseFirstBatch(reading, proceed);

        UUID jobId = reindexService.start().job().getJobId();
        await(() -> null, reading);
        searchService.updateAuthorNickname(author, "已刪除使用者#ab12", UUID.randomUUID());
        proceed.countDown();

        assertThat(awaitFinished(jobId).getState()).isEqualTo(ReindexJobState.COMPLETED);
        refreshLive();
        ArticleDocument indexed = repository.findById(data.articleUuid().toString()).orElseThrow();
        assertThat(indexed.getAuthor().getNickname()).isEqualTo("已刪除使用者#ab12");
    }

    /**
     * 審查 M3：切換請求回報失敗（socket 逾時）但 ES 已套用。若照舊刪新索引，alias 會一起被刪，
     * 下一次寫入讓 ES 自動建出 dynamic mapping 的同名實體索引，搜尋全壞。
     */
    @Test
    @DisplayName("M3：切換回報失敗但 ES 已套用 → 視為成功（COMPLETED），新索引保留且 alias 指向它，仍可搜尋")
    void rebuild_switchAppliedButReportedFailure_completes() throws Exception {
        ArticleIndexData alpha = publishedAndIndexed("Timeout switch alpha");
        ArticleIndexAdmin flaky = org.mockito.Mockito.spy(admin);
        org.mockito.Mockito.doAnswer(inv -> {
            inv.callRealMethod();
            throw new java.io.UncheckedIOException(new java.net.SocketTimeoutException("read timed out"));
        }).when(flaky).switchAlias(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        ReindexJournal journal = new ReindexJournal(redis);
        ReindexJobStore jobStore = new ReindexJobStore(redis);
        SearchReindexService flakyService = new SearchReindexService(jobStore,
                new ArticleIndexRebuilder(flaky, journal, articleFacade, userFacade), articleFacade, redis);
        try {
            SearchReindexJobResponse job = awaitFinishedOn(flakyService, flakyService.start().job().getJobId());

            assertThat(job.getState()).isEqualTo(ReindexJobState.COMPLETED);
            IndexState state = admin.inspect();
            assertThat(state.kind()).isEqualTo(IndexState.Kind.ALIAS);
            assertThat(state.aliasTargets()).singleElement().asString().isNotEqualTo(ArticleIndexAdmin.INITIAL_INDEX);
            assertThat(searchUuids("Timeout")).containsExactly(alpha.articleUuid());
        } finally {
            flakyService.destroy();
        }
    }

    @Test
    @DisplayName("重建失敗：作業 FAILED 並帶錯誤摘要；alias 仍指向舊索引、新索引已刪除、舊資料照常可搜；鎖與進行中標記已釋放")
    void rebuild_failure_keepsOldIndex() throws Exception {
        ArticleIndexData alpha = publishedAndIndexed("Failure alpha");
        when(articleFacade.findPublishedForIndexAfter(any(), anyInt())).thenThrow(new IllegalStateException("db down"));

        SearchReindexJobResponse job = awaitFinished(reindexService.start().job().getJobId());

        assertThat(job.getState()).isEqualTo(ReindexJobState.FAILED);
        assertThat(job.getError()).contains("db down");
        assertThat(admin.inspect()).isEqualTo(IndexState.alias(List.of(ArticleIndexAdmin.INITIAL_INDEX)));
        assertThat(ArticleIndexTestSupport.articleIndexNames(template)).containsExactly(ArticleIndexAdmin.INITIAL_INDEX);
        assertThat(searchUuids("Failure")).containsExactly(alpha.articleUuid());
        assertThat(redis.hasKey(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY)).isFalse();
        assertThat(redis.hasKey(RedisKeyConstant.LOCK_SEARCH_REINDEX)).isFalse();
    }

    /**
     * 審查 M2：作業 A 的鎖遺失 → 作業 B 開始（接手鎖與變動紀錄）→ A 的收尾若無條件刪掉變動紀錄，
     * listener 之後不再記錄，B 照常切換、這段期間的增量遺失。
     */
    @Test
    @DisplayName("M2：A 鎖遺失後 B 已開始，A 中止且不切換；A 的收尾不會刪掉 B 的進行中標記與已記下的變動")
    void rebuild_staleJobAbort_keepsNewJobsJournal() throws Exception {
        publishedAndIndexed("Stale job alpha");
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        pauseFirstBatch(reading, proceed);

        UUID jobA = reindexService.start().job().getJobId();
        await(() -> null, reading);
        /* A 的鎖到期、B 取得鎖並開始記錄變動 */
        UUID jobB = UUID.randomUUID();
        redis.opsForValue().set(RedisKeyConstant.LOCK_SEARCH_REINDEX, jobB.toString());
        new ReindexJournal(redis).begin(jobB);
        ArticleIndexData during = article(UUID.randomUUID(), "Stale job during", null);
        db.put(during.articleUuid(), during);
        searchService.indexArticle(document(during, "快照暱稱"), UUID.randomUUID());
        proceed.countDown();

        /* 鎖已屬於 B 時狀態端點會立刻把 A 報成中斷；這裡等 A 自己寫下結束時間（收尾完成後才寫） */
        SearchReindexJobResponse jobAResult = awaitRecordedFinish(jobA);

        assertThat(jobAResult.getState()).isEqualTo(ReindexJobState.FAILED);
        assertThat(jobAResult.getError()).containsAnyOf("變動紀錄", "鎖");
        assertThat(admin.inspect()).as("A 不切換").isEqualTo(IndexState.alias(List.of(ArticleIndexAdmin.INITIAL_INDEX)));
        assertThat(ArticleIndexTestSupport.articleIndexNames(template)).containsExactly(ArticleIndexAdmin.INITIAL_INDEX);
        assertThat(redis.opsForValue().get(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY)).isEqualTo(jobB.toString());
        assertThat(redis.opsForSet().members(RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY))
                .containsExactly(during.articleUuid().toString());
        assertThat(redis.opsForValue().get(RedisKeyConstant.LOCK_SEARCH_REINDEX)).as("A 也不會刪掉 B 的鎖").isEqualTo(jobB.toString());
    }

    /**
     * 審查 m1：應用程式關閉時 executor.shutdownNow() 中斷背景執行緒；中斷旗標仍在時，Lettuce 與 RestClient 的呼叫會直接丟例外，
     * 收尾（刪新索引、結束變動紀錄、寫 FAILED、解鎖）全部失敗：留下孤兒索引、鎖卡 10 分鐘、狀態一直顯示 RUNNING。
     */
    @Test
    @DisplayName("m1：關閉應用（中斷背景執行緒）時收尾仍完成：新索引刪除、鎖與進行中標記釋放、作業記為 FAILED")
    void shutdown_interruptedRebuild_stillCleansUp() throws Exception {
        publishedAndIndexed("Shutdown alpha");
        CountDownLatch reading = new CountDownLatch(1);
        when(articleFacade.findPublishedForIndexAfter(any(), anyInt())).thenAnswer(inv -> {
            reading.countDown();
            try {
                new CountDownLatch(1).await(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                /* 與 I/O 函式庫被中斷時的行為一致：還原中斷旗標後丟出例外 */
                Thread.currentThread().interrupt();
                throw new IllegalStateException("讀取被中斷", e);
            }
            return List.of();
        });
        UUID jobId = reindexService.start().job().getJobId();
        await(() -> null, reading);

        reindexService.destroy();

        SearchReindexJobResponse job = reindexService.currentJob().orElseThrow();
        assertThat(job.getJobId()).isEqualTo(jobId);
        assertThat(job.getState()).isEqualTo(ReindexJobState.FAILED);
        assertThat(job.getFinishedAt()).as("由作業自己寫下，不是被判定中斷").isNotNull();
        assertThat(ArticleIndexTestSupport.articleIndexNames(template)).containsExactly(ArticleIndexAdmin.INITIAL_INDEX);
        assertThat(redis.hasKey(RedisKeyConstant.LOCK_SEARCH_REINDEX)).isFalse();
        assertThat(redis.hasKey(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY)).isFalse();
    }

    /**
     * 審查 m4：補齊時同一批剛 bulk 進新索引的文件尚未 refresh，緊接著的作者暱稱 update_by_query 看不到它們。
     * 重建中發布的文章 X 經補齊寫入時（UserFacade 當時仍回舊暱稱），作者隨後刪帳匿名化；
     * 若不先 refresh，作者改寫漏掉 X，PII 隨切換回到索引。
     */
    @Test
    @DisplayName("m4：補齊時剛寫入的文章也會被作者匿名化改寫到（改寫前先 refresh 新索引）")
    void rebuild_authorRewriteSeesDocumentsJustWrittenByCatchUp() throws Exception {
        UUID author = UUID.randomUUID();
        ArticleIndexData existing = article(UUID.randomUUID(), "Refresh existing", author);
        db.put(existing.articleUuid(), existing);
        repository.save(document(existing, "愛麗絲"));
        Map<UUID, UserDisplayInfo> before = Map.of(author, new UserDisplayInfo(author, "愛麗絲", null));
        Map<UUID, UserDisplayInfo> after = Map.of(author, new UserDisplayInfo(author, "已刪除使用者#cd34", null));
        /* 第一批與補齊文章時仍回舊暱稱，補齊作者時才回匿名暱稱 */
        when(userFacade.getDisplayInfoByUuids(anyCollection())).thenReturn(before, before, after);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        pauseFirstBatch(reading, proceed);

        UUID jobId = reindexService.start().job().getJobId();
        await(() -> null, reading);
        ArticleIndexData fresh = article(UUID.randomUUID(), "Refresh fresh", author);
        db.put(fresh.articleUuid(), fresh);
        searchService.indexArticle(document(fresh, "愛麗絲"), UUID.randomUUID());
        searchService.updateAuthorNickname(author, "已刪除使用者#cd34", UUID.randomUUID());
        proceed.countDown();

        assertThat(awaitFinished(jobId).getState()).isEqualTo(ReindexJobState.COMPLETED);
        refreshLive();
        assertThat(repository.findById(fresh.articleUuid().toString()).orElseThrow().getAuthor().getNickname())
                .isEqualTo("已刪除使用者#cd34");
        assertThat(repository.findById(existing.articleUuid().toString()).orElseThrow().getAuthor().getNickname())
                .isEqualTo("已刪除使用者#cd34");
    }

    @Test
    @DisplayName("重建進行中再觸發：第二次不建立新作業，回傳進行中的同一個 jobId（呼叫端回 409）")
    void rebuild_secondTriggerWhileRunning_isRejected() throws Exception {
        publishedAndIndexed("Second trigger");
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        pauseFirstBatch(reading, proceed);

        ReindexStartResult first = reindexService.start();
        await(() -> null, reading);
        ReindexStartResult second = reindexService.start();
        proceed.countDown();

        assertThat(first.started()).isTrue();
        assertThat(second.started()).isFalse();
        assertThat(second.job().getJobId()).isEqualTo(first.job().getJobId());
        assertThat(second.job().getState()).isEqualTo(ReindexJobState.RUNNING);
        assertThat(awaitFinished(first.job().getJobId()).getState()).isEqualTo(ReindexJobState.COMPLETED);
        /* 狀態先寫 COMPLETED、解鎖在 finally，兩者之間有極短的空窗；等鎖釋放後再觸發 */
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (Boolean.TRUE.equals(redis.hasKey(RedisKeyConstant.LOCK_SEARCH_REINDEX)) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(reindexService.start().started()).as("前一個結束後可以再觸發").isTrue();
    }

    @Test
    @DisplayName("既有環境：名稱仍是改版前的實體索引時，重建以同一個 _aliases 請求刪掉它並建立 alias（遷移路徑）")
    void rebuild_fromConcreteIndex_migratesToAlias() throws Exception {
        ArticleIndexTestSupport.deleteAllArticleIndices(template);
        template.indexOps(ArticleDocument.class).createWithMapping();
        assertThat(admin.inspect()).isEqualTo(IndexState.concrete());
        ArticleIndexData alpha = publishedAndIndexed("Concrete alpha");

        SearchReindexJobResponse job = awaitFinished(reindexService.start().job().getJobId());

        assertThat(job.getState()).isEqualTo(ReindexJobState.COMPLETED);
        IndexState state = admin.inspect();
        assertThat(state.kind()).isEqualTo(IndexState.Kind.ALIAS);
        assertThat(ArticleIndexTestSupport.articleIndexNames(template)).containsExactly(state.aliasTargets().get(0));
        assertThat(searchUuids("Concrete")).containsExactly(alpha.articleUuid());
    }
}
