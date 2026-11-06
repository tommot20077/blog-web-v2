package dowob.xyz.blog.module.search.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob.Result;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.repository.ArticleSearchRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.11.0")
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
        IndexOperations indexOps = template.indexOps(ArticleDocument.class);
        if (indexOps.exists()) {
            indexOps.delete();
        }
        /* 與正式環境一致：以 annotation 建 mapping（SearchIndexInitializer 的做法） */
        indexOps.createWithMapping();
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
        IndexOperations indexOps = template.indexOps(ArticleDocument.class);
        indexOps.delete();
        indexOps.create();
        indexOps.putMapping(org.springframework.data.elasticsearch.core.document.Document.parse(
                "{\"properties\":{\"status\":{\"type\":\"keyword\"},\"tags\":{\"type\":\"nested\"},"
                        + "\"author\":{\"properties\":{\"uuid\":{\"type\":\"keyword\"}}}}}"));
        new dowob.xyz.blog.module.search.init.SearchIndexInitializer(template).ensureCorrectMapping();
        UUID a = indexArticle();
        UUID b = indexArticle();
        dbPublishedOnly(Set.of(a));

        Result result = newJob(500).run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(indexedIds()).containsExactly(a.toString());
        assertThat(b).isNotNull();
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
