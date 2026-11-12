package dowob.xyz.blog.module.search.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob.Result;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.repository.ArticleSearchRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchClients;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchCustomConversions;
import org.springframework.data.elasticsearch.core.convert.MappingElasticsearchConverter;
import org.springframework.data.elasticsearch.core.mapping.SimpleElasticsearchMappingContext;
import org.springframework.data.elasticsearch.repository.support.ElasticsearchRepositoryFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleIndexOrphanReconciliationJob} 整合測試（真實 Elasticsearch，Testcontainers）。
 *
 * <p>ES 與 keyset 分頁走真實實作；owner 端 {@link ArticleFacade} 以替身模擬 DB 狀態（DB 屬 article 模組，
 * 本模組測試不起 Postgres）；Redis 以記憶體替身模擬鎖與游標。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleIndexOrphanReconciliationJob 整合測試（真實 ES）")
class ArticleIndexOrphanReconciliationJobIT {

    private static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.2.4")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("discovery.type", "single-node")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static ElasticsearchTemplate template;
    private static ArticleSearchRepository repository;

    private final ArticleFacade articleFacade = mock(ArticleFacade.class);
    private StringRedisTemplate redis;
    private SimpleMeterRegistry registry;

    @BeforeAll
    static void startElasticsearch() {
        ELASTICSEARCH.start();
        var restClient = ElasticsearchClients.getRestClient(
                ClientConfiguration.builder().connectedTo(ELASTICSEARCH.getHttpHostAddress()).build());
        ElasticsearchClient client = new ElasticsearchClient(
                new RestClientTransport(restClient, new JacksonJsonpMapper()));
        ElasticsearchCustomConversions conversions = new ElasticsearchCustomConversions(List.of());
        SimpleElasticsearchMappingContext mappingContext = new SimpleElasticsearchMappingContext();
        mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        MappingElasticsearchConverter converter = new MappingElasticsearchConverter(mappingContext);
        converter.setConversions(conversions);
        converter.afterPropertiesSet();
        template = new ElasticsearchTemplate(client, converter);
        repository = new ElasticsearchRepositoryFactory(template).getRepository(ArticleSearchRepository.class);
    }

    @AfterAll
    static void stopElasticsearch() {
        ELASTICSEARCH.stop();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void resetIndexAndRedis() {
        /* 讀寫改走 alias 後，對 alias 名稱 delete 會被 ES 拒絕：改為逐一刪除所有 blog_articles* 實體索引 */
        dowob.xyz.blog.module.search.support.ArticleIndexTestSupport.deleteAllArticleIndices(template);
        /* 與正式環境一致：由 SearchIndexInitializer 建立 alias 與 annotation mapping 的實體索引 */
        newInitializer().ensureCorrectMapping();
        registry = new SimpleMeterRegistry();
        /* Redis 記憶體替身：鎖永遠取得成功；游標存在 AtomicReference，讓多頁續跑可以真的走 */
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        AtomicReference<String> cursor = new AtomicReference<>();
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        when(valueOps.get(anyString())).thenAnswer(inv -> cursor.get());
        org.mockito.Mockito.doAnswer(inv -> {
            cursor.set(inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(inv -> {
            cursor.set(null);
            return true;
        });
    }

    /** 以真實 ES 建立 initializer（索引管理經 {@link dowob.xyz.blog.module.search.index.ArticleIndexAdmin}） */
    private dowob.xyz.blog.module.search.init.SearchIndexInitializer newInitializer() {
        return new dowob.xyz.blog.module.search.init.SearchIndexInitializer(
                dowob.xyz.blog.module.search.support.ArticleIndexTestSupport.newAdmin(template));
    }

    /**
     * 模擬「頂層 uuid 欄位上線前」建立的實體索引：mapping 沒有 uuid，且以 alias {@code blog_articles} 指向它。
     *
     * <p>改走 alias 後，舊實體索引一定在 alias 背後（改版前的實體 {@code blog_articles} 會被 initializer
     * 以正確 mapping 的新索引取代），所以「缺 uuid mapping」的情境要建在 alias 背後的實體索引上，
     * initializer 才會走 putMapping 增補這條路。</p>
     */
    private void useLegacyIndexWithoutUuidMappingBehindAlias() {
        dowob.xyz.blog.module.search.support.ArticleIndexTestSupport.deleteAllArticleIndices(template);
        String legacy = "blog_articles_v20260101000000000";
        template.indexOps(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.of(legacy)).create(
                java.util.Map.of(), org.springframework.data.elasticsearch.core.document.Document.parse(
                        "{\"properties\":{\"status\":{\"type\":\"keyword\"},\"tags\":{\"type\":\"nested\"},"
                                + "\"author\":{\"properties\":{\"uuid\":{\"type\":\"keyword\"}}}}}"));
        dowob.xyz.blog.module.search.support.ArticleIndexTestSupport.newAdmin(template)
                .switchAlias(legacy, dowob.xyz.blog.module.search.index.IndexState.absent());
    }

    /** 捕捉日誌事件的測試用 appender */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("job-it-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    private static List<String> runCapturing(Class<?> loggerClass, Runnable action, Level level) {
        Logger logger = (Logger) LogManager.getLogger(loggerClass);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            action.run();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
        return appender.events.stream().filter(e -> e.getLevel() == level)
                .map(e -> e.getMessage().getFormattedMessage()).toList();
    }

    private void saveRaw(String id, String uuidOrNull) {
        repository.save(ArticleDocument.builder().id(id).uuid(uuidOrNull).title("t-" + id).slug("s-" + id)
                .status("PUBLISHED").build());
        template.indexOps(ArticleDocument.class).refresh();
    }

    private ArticleIndexOrphanReconciliationJob newJob(int pageSize) {
        return new ArticleIndexOrphanReconciliationJob(redis, registry, template, repository, articleFacade,
                pageSize, 200);
    }

    private UUID indexArticle() {
        UUID uuid = UUID.randomUUID();
        repository.save(ArticleDocument.builder().id(uuid.toString()).uuid(uuid.toString()).title("t-" + uuid).slug("s-" + uuid)
                .status("PUBLISHED").build());
        template.indexOps(ArticleDocument.class).refresh();
        return uuid;
    }

    private Set<String> indexedIds() {
        template.indexOps(ArticleDocument.class).refresh();
        Set<String> ids = new HashSet<>();
        repository.findAll().forEach(d -> ids.add(d.getId()));
        return ids;
    }

    /** 模擬 DB：只有 {@code stillPublished} 內的 uuid 回報為 PUBLISHED。 */
    private void dbPublishedOnly(Collection<UUID> stillPublished) {
        when(articleFacade.filterPublishedUuids(any())).thenAnswer(inv -> {
            Collection<UUID> asked = inv.getArgument(0);
            Set<UUID> result = new HashSet<>(asked);
            result.retainAll(stillPublished);
            return result;
        });
    }

    @Test
    @DisplayName("ES 有 3 篇，1 篇 DB 已下架、1 篇已刪除：執行後只剩仍 PUBLISHED 的那篇")
    void run_removesUnpublishedAndDeletedDocuments() {
        UUID published = indexArticle();
        UUID unpublished = indexArticle();
        UUID deleted = indexArticle();
        /* 下架與刪除在 facade 看來都是「不在 PUBLISHED 集合內」 */
        dbPublishedOnly(Set.of(published));

        Result result = newJob(500).run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(indexedIds()).containsExactly(published.toString());
        assertThat(indexedIds()).doesNotContain(unpublished.toString(), deleted.toString());
    }

    @Test
    @DisplayName("上線前建立的舊索引（無頂層 uuid mapping）：initializer 增補 mapping 後，新文件可被 keyset 掃到")
    void run_legacyIndexWithoutUuidMapping_worksAfterInitializerPutsMapping() {
        useLegacyIndexWithoutUuidMappingBehindAlias();
        newInitializer().ensureCorrectMapping();
        UUID a = indexArticle();
        UUID b = indexArticle();
        dbPublishedOnly(Set.of(a));

        Result result = newJob(500).run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(indexedIds()).containsExactly(a.toString());
        assertThat(b).isNotNull();
    }

    @Test
    @DisplayName("同一頁混入非 UUID 的垃圾 id：掃描仍走完全部文件，幽靈全數清除，垃圾文件不被刪（留給全量重建）")
    void run_garbageIdsInPages_stillScansEverything() {
        List<UUID> keep = new ArrayList<>();
        List<UUID> ghosts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            (i % 4 == 3 ? ghosts : keep).add(indexArticle());
        }
        saveRaw("0-garbage", "0-garbage");
        saveRaw("g-garbage", "g-garbage");
        dbPublishedOnly(keep);

        Result result = newJob(2).run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        Set<String> expected = new HashSet<>(Set.of("0-garbage", "g-garbage"));
        keep.forEach(u -> expected.add(u.toString()));
        assertThat(indexedIds()).isEqualTo(expected);
    }

    @Test
    @DisplayName("混入沒有 uuid 欄位的舊文件：掃描不會卡住，這些文件不被掃描也不被刪除，其餘幽靈照常清除")
    void run_documentsWithoutUuidField_areSkippedAndNeverDeleted() {
        UUID keep = indexArticle();
        UUID ghost = indexArticle();
        UUID legacyA = UUID.randomUUID();
        UUID legacyB = UUID.randomUUID();
        saveRaw(legacyA.toString(), null);
        saveRaw(legacyB.toString(), null);
        dbPublishedOnly(Set.of(keep));

        Result result = newJob(2).run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(indexedIds()).containsExactlyInAnyOrder(keep.toString(), legacyA.toString(), legacyB.toString());
        assertThat(indexedIds()).doesNotContain(ghost.toString());
    }

    @Test
    @DisplayName("舊索引的 uuid 已被動態建成 text：initializer 的 putMapping 衝突記 ERROR（不拋例外）；校正 FAILED 並記可行動 ERROR，ES 零刪除")
    void run_uuidDynamicallyMappedAsText_logsActionableErrorsAndDeletesNothing() {
        useLegacyIndexWithoutUuidMappingBehindAlias();
        /* 新版程式在 initializer 補 mapping 之前就寫入帶 uuid 的文件 → ES 動態建成 text */
        UUID a = indexArticle();
        UUID b = indexArticle();
        dbPublishedOnly(Set.of());

        List<String> initializerErrors = runCapturing(dowob.xyz.blog.module.search.init.SearchIndexInitializer.class,
                () -> newInitializer().ensureCorrectMapping(),
                Level.ERROR);
        Result[] result = new Result[1];
        List<String> jobErrors = runCapturing(ArticleIndexOrphanReconciliationJob.class,
                () -> result[0] = newJob(500).run(), Level.ERROR);

        assertThat(initializerErrors).anySatisfy(m -> assertThat(m).contains("刪除索引").contains("reindex"));
        assertThat(result[0]).isEqualTo(Result.FAILED);
        assertThat(jobErrors).anySatisfy(m -> assertThat(m).contains("刪除索引").contains("reindex"));
        assertThat(indexedIds()).containsExactlyInAnyOrder(a.toString(), b.toString());
    }

    @Test
    @DisplayName("沒有幽靈：COMPLETED 且 ES 文件不變")
    void run_noGhost_keepsEverything() {
        UUID a = indexArticle();
        UUID b = indexArticle();
        dbPublishedOnly(Set.of(a, b));

        assertThat(newJob(500).run()).isEqualTo(Result.COMPLETED);
        assertThat(indexedIds()).containsExactlyInAnyOrder(a.toString(), b.toString());
    }

    @Test
    @DisplayName("多頁 keyset（pageSize=2，search_after）：每一篇都被掃到，所有幽靈都被清掉")
    void run_multiplePages_scansEveryDocument() {
        List<UUID> keep = new ArrayList<>();
        List<UUID> ghosts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            (i % 6 == 5 ? ghosts : keep).add(indexArticle());
        }
        dbPublishedOnly(keep);

        Result result = newJob(2).run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        Set<String> expected = new HashSet<>();
        keep.forEach(u -> expected.add(u.toString()));
        assertThat(indexedIds()).isEqualTo(expected);
    }

    @Test
    @DisplayName("owner facade 拋例外：FAILED，ES 不刪任何文件（框架 fail-safe 未被子類別破壞）")
    void run_facadeThrows_deletesNothing() {
        UUID a = indexArticle();
        UUID b = indexArticle();
        UUID c = indexArticle();
        when(articleFacade.filterPublishedUuids(any())).thenThrow(new IllegalStateException("article down"));

        Result result = newJob(500).run();

        assertThat(result).isEqualTo(Result.FAILED);
        assertThat(indexedIds()).containsExactlyInAnyOrder(a.toString(), b.toString(), c.toString());
    }

    @Test
    @DisplayName("facade 異常回空集合（全部看似幽靈）：ABORTED，ES 不刪任何文件")
    void run_facadeReturnsEmpty_abortsWithoutDeleting() {
        UUID a = indexArticle();
        UUID b = indexArticle();
        dbPublishedOnly(Set.of());

        Result result = newJob(500).run();

        assertThat(result).isEqualTo(Result.ABORTED);
        assertThat(indexedIds()).containsExactlyInAnyOrder(a.toString(), b.toString());
    }
}
