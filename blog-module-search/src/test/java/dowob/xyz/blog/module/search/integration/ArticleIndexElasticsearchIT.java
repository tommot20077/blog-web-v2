package dowob.xyz.blog.module.search.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.init.SearchIndexInitializer;
import dowob.xyz.blog.module.search.model.dto.response.SearchResultResponse;
import dowob.xyz.blog.module.search.repository.ArticleSearchRepository;
import dowob.xyz.blog.module.search.service.SearchServiceImpl;
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
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.repository.support.ElasticsearchRepositoryFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文章索引的 Elasticsearch 整合測試（真實 ES，Testcontainers）
 *
 * <p>驗證 M7-search 的 ES 文件改版：{@code author} 只存 {@code uuid}（Keyword）與 {@code nickname}，
 * 不含 Long {@code id} 與 {@code username}；舊 mapping 的索引能被 {@link SearchIndexInitializer}
 * 偵測並重建；重建索引（{@code reindexAll}）後搜得到、{@code author.uuid} 正確、搜尋結果 DTO 不變。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("文章索引 ES 整合測試（author.uuid）")
class ArticleIndexElasticsearchIT {

    /** 與正式環境一致的 ES 版本（見 E2E 基底） */
    private static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.11.0")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("discovery.type", "single-node")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static ElasticsearchClient client;
    private static ElasticsearchTemplate template;
    private static ArticleSearchRepository repository;

    private final ArticleFacade articleFacade = mock(ArticleFacade.class);
    private SearchIndexInitializer initializer;
    private SearchServiceImpl searchService;

    @BeforeAll
    static void startElasticsearch() {
        ELASTICSEARCH.start();
        var restClient = ElasticsearchClients.getRestClient(
                ClientConfiguration.builder().connectedTo(ELASTICSEARCH.getHttpHostAddress()).build());
        client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
        /* 與 Spring Boot 自動配置一致：用 ElasticsearchCustomConversions 的 SimpleTypeHolder（UUID 視為簡單型別） */
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
    void resetIndex() {
        IndexOperations indexOps = template.indexOps(ArticleDocument.class);
        if (indexOps.exists()) {
            indexOps.delete();
        }
        initializer = new SearchIndexInitializer(template);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForZSet()).thenReturn(mock(ZSetOperations.class));
        searchService = new SearchServiceImpl(template, repository, redis, articleFacade);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> authorMappingProperties() {
        Map<String, Object> mapping = template.indexOps(ArticleDocument.class).getMapping();
        Map<String, Object> properties = (Map<String, Object>) mapping.get("properties");
        Map<String, Object> author = (Map<String, Object>) properties.get("author");
        return (Map<String, Object>) author.get("properties");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rawAuthorSource(UUID articleUuid) throws Exception {
        Map<String, Object> source = client.get(g -> g.index("blog_articles").id(articleUuid.toString()), Map.class)
                .source();
        return (Map<String, Object>) source.get("author");
    }

    @Test
    @DisplayName("索引不存在時，initializer 建出的 mapping：author.uuid 為 keyword，且沒有 author.id／author.username")
    void initializer_freshIndex_shouldMapAuthorUuidAsKeyword() {
        initializer.ensureCorrectMapping();

        Map<String, Object> authorProps = authorMappingProperties();
        assertThat(authorProps).containsKeys("uuid", "nickname").doesNotContainKeys("id", "username");
        assertThat(((Map<?, ?>) authorProps.get("uuid")).get("type")).isEqualTo("keyword");
        assertThat(((Map<?, ?>) authorProps.get("nickname")).get("type")).isEqualTo("text");
    }

    @Test
    @DisplayName("mapping 正確的索引：initializer 不得刪除索引，既有文件仍在（防多台機器啟動互刪、重啟清空）")
    void initializer_correctIndexWithData_shouldKeepIndexAndDocuments() {
        initializer.ensureCorrectMapping();
        UUID articleUuid = UUID.randomUUID();
        repository.save(ArticleDocument.builder().id(articleUuid.toString()).title("既有文件").status("PUBLISHED")
                .author(new ArticleDocument.AuthorInfo(UUID.randomUUID().toString(), "愛麗絲")).build());
        template.indexOps(ArticleDocument.class).refresh();

        initializer.ensureCorrectMapping();

        assertThat(template.indexOps(ArticleDocument.class).exists()).isTrue();
        assertThat(repository.existsById(articleUuid.toString())).isTrue();
    }

    @Test
    @DisplayName("舊 author mapping（含 id／username、無 uuid）：initializer 不刪索引、文件仍在；admin reindex 後 _source 不含 id／username")
    void initializer_legacyAuthorMapping_shouldKeepIndexAndReindexReplacesLegacyFields() throws Exception {
        IndexOperations indexOps = template.indexOps(ArticleDocument.class);
        indexOps.create(Map.of(), Document.parse("""
                {"properties":{
                  "status":{"type":"keyword"},
                  "tags":{"type":"nested"},
                  "author":{"properties":{
                    "id":{"type":"keyword"},
                    "username":{"type":"keyword"},
                    "nickname":{"type":"text"}}}}}
                """));
        UUID articleUuid = UUID.randomUUID();
        UUID authorUuid = UUID.randomUUID();
        client.index(i -> i.index("blog_articles").id(articleUuid.toString()).refresh(Refresh.True).document(Map.of(
                "title", "Elasticsearch guide", "status", "PUBLISHED",
                "author", Map.of("id", "424242", "username", "login-name-sentinel", "nickname", "愛麗絲"))));

        initializer.ensureCorrectMapping();

        /* 偵測到舊 author mapping 只告警，不刪除也不重建 */
        assertThat(indexOps.exists()).isTrue();
        assertThat(repository.existsById(articleUuid.toString())).isTrue();
        assertThat(rawAuthorSource(articleUuid)).containsKeys("id", "username");

        when(articleFacade.findAllPublishedForIndex()).thenReturn(List.of(new ArticleIndexData(
                articleUuid, "Elasticsearch guide", "es-guide", "about search", "full text content", "愛麗絲",
                LocalDateTime.of(2026, 10, 24, 12, 0), 3L, 2L, List.of(), authorUuid)));
        when(articleFacade.filterPublishedUuids(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Set.of(articleUuid));
        searchService.reindexAll();
        indexOps.refresh();

        Map<String, Object> author = rawAuthorSource(articleUuid);
        assertThat(author).containsKeys("uuid", "nickname").doesNotContainKeys("id", "username");
        assertThat(author.get("uuid")).isEqualTo(authorUuid.toString());
    }

    @Test
    @DisplayName("reindexAll：文章搜得到、_source 的 author.uuid 正確、不含 username／id，搜尋結果仍回 authorNickname")
    void reindexAll_shouldIndexAuthorUuidWithoutUsernameOrId() throws Exception {
        initializer.ensureCorrectMapping();
        UUID articleUuid = UUID.randomUUID();
        UUID authorUuid = UUID.randomUUID();
        when(articleFacade.findAllPublishedForIndex()).thenReturn(List.of(new ArticleIndexData(
                articleUuid, "Elasticsearch guide", "es-guide", "about search", "full text content", "愛麗絲",
                LocalDateTime.of(2026, 10, 24, 12, 0), 3L, 2L, List.of(), authorUuid)));
        when(articleFacade.filterPublishedUuids(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Set.of(articleUuid));

        searchService.reindexAll();
        template.indexOps(ArticleDocument.class).refresh();

        Map<String, Object> author = rawAuthorSource(articleUuid);
        /* Spring Data 會自動加 _class 型別提示，與作者身分無關，故用「不含 id／username」而非「只含」 */
        assertThat(author).containsKeys("uuid", "nickname").doesNotContainKeys("id", "username");
        assertThat(author.get("uuid")).isEqualTo(authorUuid.toString());
        assertThat(author.get("nickname")).isEqualTo("愛麗絲");
        assertThat(author.toString()).doesNotContain("login-name-sentinel").doesNotContain("424242");

        PageResult<SearchResultResponse> result = searchService.search("Elasticsearch", null, "relevance", 1, 10, null);
        assertThat(result.getRecords()).singleElement().satisfies(r -> {
            assertThat(r.getArticleUuid()).isEqualTo(articleUuid);
            assertThat(r.getAuthorNickname()).isEqualTo("愛麗絲");
        });
    }
}
