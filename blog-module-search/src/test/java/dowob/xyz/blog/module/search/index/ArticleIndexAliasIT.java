package dowob.xyz.blog.module.search.index;

import co.elastic.clients.elasticsearch._types.Refresh;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.init.SearchIndexInitializer;
import dowob.xyz.blog.module.search.repository.ArticleSearchRepository;
import dowob.xyz.blog.module.search.support.ArticleIndexTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.repository.support.ElasticsearchRepositoryFactory;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * alias 與版本化實體索引的整合測試（真實 ES，D1／PERF-01）。
 *
 * <p>驗證 {@link ArticleIndexAdmin} 的每個 ES 動作，以及 {@link SearchIndexInitializer} 對三種起點
 * （全新環境、改版前的實體索引、alias）的處置：最後一律是「alias {@code blog_articles} 指向一個 mapping 正確的實體索引」，
 * 既有文件不遺失，Repository 與查詢經 alias 照常讀寫。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("文章索引 alias 整合測試（真實 ES）")
class ArticleIndexAliasIT {

    private static final ElasticsearchContainer ELASTICSEARCH = ArticleIndexTestSupport.newContainer();

    private static ElasticsearchTemplate template;
    private static ArticleSearchRepository repository;

    private ArticleIndexAdmin admin;
    private SearchIndexInitializer initializer;

    @BeforeAll
    static void startElasticsearch() {
        ELASTICSEARCH.start();
        template = ArticleIndexTestSupport.newTemplate(ELASTICSEARCH);
        repository = new ElasticsearchRepositoryFactory(template).getRepository(ArticleSearchRepository.class);
    }

    @AfterAll
    static void stopElasticsearch() {
        ELASTICSEARCH.stop();
    }

    @BeforeEach
    void reset() {
        ArticleIndexTestSupport.deleteAllArticleIndices(template);
        admin = new ArticleIndexAdmin(template);
        initializer = new SearchIndexInitializer(admin);
    }

    private static ArticleDocument doc(UUID uuid, String title) {
        return ArticleDocument.builder().id(uuid.toString()).uuid(uuid.toString()).title(title)
                .slug("slug-" + uuid).status("PUBLISHED")
                .author(new ArticleDocument.AuthorInfo(UUID.randomUUID().toString(), "愛麗絲")).build();
    }

    @SuppressWarnings("unchecked")
    private String fieldType(String index, String field) {
        Map<String, Object> properties = (Map<String, Object>) admin.getMapping(index).get("properties");
        Map<String, Object> fieldMapping = (Map<String, Object>) properties.get(field);
        return fieldMapping == null ? null : (String) fieldMapping.get("type");
    }

    private long searchPublishedTitle(String word) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                        .must(m -> m.match(mt -> mt.field("title").query(word)))
                        .filter(f -> f.term(t -> t.field("status").value("PUBLISHED")))))
                .build();
        return template.search(query, ArticleDocument.class).getTotalHits();
    }

    private void createLegacyConcreteIndex(String mappingJson) {
        template.indexOps(IndexCoordinates.of(ArticleIndexAdmin.ALIAS)).create(Map.of(), Document.parse(mappingJson));
    }

    private void indexRaw(String index, UUID uuid, Map<String, Object> source) throws Exception {
        template.execute(client -> client.index(i -> i.index(index).id(uuid.toString()).refresh(Refresh.True).document(source)));
    }

    @Test
    @DisplayName("全新環境：initializer 建立 blog_articles_v1 與 alias；mapping 正確；經 alias 寫入後搜得到")
    void freshEnvironment_createsInitialIndexAndAlias() {
        initializer.ensureCorrectMapping();

        assertThat(admin.inspect()).isEqualTo(IndexState.alias(List.of(ArticleIndexAdmin.INITIAL_INDEX)));
        assertThat(fieldType(ArticleIndexAdmin.INITIAL_INDEX, "status")).isEqualTo("keyword");
        assertThat(fieldType(ArticleIndexAdmin.INITIAL_INDEX, "tags")).isEqualTo("nested");
        assertThat(fieldType(ArticleIndexAdmin.INITIAL_INDEX, "uuid")).isEqualTo("keyword");
        UUID uuid = UUID.randomUUID();
        repository.save(doc(uuid, "Kubernetes guide"));
        admin.refresh(ArticleIndexAdmin.INITIAL_INDEX);
        assertThat(searchPublishedTitle("Kubernetes")).isEqualTo(1);
        assertThat(admin.countLive()).isEqualTo(1);
    }

    @Test
    @DisplayName("重啟（再跑一次 initializer）：alias 與實體索引不變，既有文件仍在")
    void restart_isIdempotent() {
        initializer.ensureCorrectMapping();
        UUID uuid = UUID.randomUUID();
        repository.save(doc(uuid, "Kept"));

        initializer.ensureCorrectMapping();

        assertThat(admin.inspect()).isEqualTo(IndexState.alias(List.of(ArticleIndexAdmin.INITIAL_INDEX)));
        assertThat(repository.existsById(uuid.toString())).isTrue();
        assertThat(ArticleIndexTestSupport.articleIndexNames(template)).containsExactly(ArticleIndexAdmin.INITIAL_INDEX);
    }

    @Test
    @DisplayName("既有環境：blog_articles 是實體索引（缺頂層 uuid）→ 遷移為 alias + 版本化索引，文件全數保留、mapping 補齊")
    void concreteLegacyIndex_migratesToAliasKeepingDocuments() throws Exception {
        createLegacyConcreteIndex("""
                {"properties":{"status":{"type":"keyword"},"tags":{"type":"nested"},"title":{"type":"text"}}}""");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        indexRaw(ArticleIndexAdmin.ALIAS, a, Map.of("title", "Legacy alpha", "status", "PUBLISHED"));
        indexRaw(ArticleIndexAdmin.ALIAS, b, Map.of("title", "Legacy beta", "status", "PUBLISHED"));
        assertThat(admin.inspect()).isEqualTo(IndexState.concrete());

        initializer.ensureCorrectMapping();

        IndexState state = admin.inspect();
        assertThat(state.kind()).isEqualTo(IndexState.Kind.ALIAS);
        String physical = state.aliasTargets().get(0);
        assertThat(physical).startsWith(ArticleIndexAdmin.VERSIONED_PREFIX);
        assertThat(ArticleIndexTestSupport.articleIndexNames(template)).containsExactly(physical);
        assertThat(fieldType(physical, "uuid")).isEqualTo("keyword");
        assertThat(repository.existsById(a.toString())).isTrue();
        assertThat(repository.existsById(b.toString())).isTrue();
        assertThat(searchPublishedTitle("Legacy")).isEqualTo(2);
    }

    @Test
    @DisplayName("既有環境：實體索引的 status 被動態建成 text（term 查不到）→ 遷移後 status 為 keyword，原文件可被 status 過濾搜到")
    void concreteIndexWithWrongStatus_migrationFixesMapping() throws Exception {
        createLegacyConcreteIndex("""
                {"properties":{"status":{"type":"text"}}}""");
        UUID a = UUID.randomUUID();
        indexRaw(ArticleIndexAdmin.ALIAS, a, Map.of("title", "Broken status doc", "status", "PUBLISHED"));
        assertThat(searchPublishedTitle("Broken")).as("遷移前：status 為 text，term 查不到").isZero();

        initializer.ensureCorrectMapping();

        String physical = admin.inspect().aliasTargets().get(0);
        assertThat(fieldType(physical, "status")).isEqualTo("keyword");
        assertThat(searchPublishedTitle("Broken")).isEqualTo(1);
    }

    @Test
    @DisplayName("alias 背後的實體索引缺頂層 uuid：putMapping 作用在實體索引上（不重建、文件不動）")
    void aliasTargetMissingUuid_putsMappingOnPhysicalIndex() throws Exception {
        String physical = "blog_articles_v20260101000000000";
        template.indexOps(IndexCoordinates.of(physical)).create(Map.of(), Document.parse("""
                {"properties":{"status":{"type":"keyword"},"tags":{"type":"nested"},
                 "author":{"properties":{"uuid":{"type":"keyword"}}}}}"""));
        admin.switchAlias(physical, IndexState.absent());
        UUID a = UUID.randomUUID();
        indexRaw(physical, a, Map.of("title", "Before put mapping", "status", "PUBLISHED"));

        initializer.ensureCorrectMapping();

        assertThat(admin.inspect()).isEqualTo(IndexState.alias(List.of(physical)));
        assertThat(fieldType(physical, "uuid")).isEqualTo("keyword");
        assertThat(repository.existsById(a.toString())).isTrue();
    }

    @Test
    @DisplayName("switchAlias：同一個 _aliases 請求內把 alias 從舊索引移到新索引；舊索引在刪除前仍存在、刪除後消失")
    void switchAlias_movesAliasAtomically() {
        initializer.ensureCorrectMapping();
        UUID oldDoc = UUID.randomUUID();
        repository.save(doc(oldDoc, "Old index doc"));
        String next = admin.newVersionedIndexName();
        admin.createIndex(next);
        UUID newDoc = UUID.randomUUID();
        admin.bulkIndex(List.of(doc(newDoc, "New index doc")), next);
        admin.refresh(next);

        admin.switchAlias(next, admin.inspect());

        assertThat(admin.inspect()).isEqualTo(IndexState.alias(List.of(next)));
        assertThat(admin.indexExists(ArticleIndexAdmin.INITIAL_INDEX)).isTrue();
        assertThat(repository.existsById(newDoc.toString())).isTrue();
        assertThat(repository.existsById(oldDoc.toString())).isFalse();
        assertThat(admin.deleteIndexQuietly(ArticleIndexAdmin.INITIAL_INDEX)).isTrue();
        assertThat(admin.indexExists(ArticleIndexAdmin.INITIAL_INDEX)).isFalse();
    }

    @Test
    @DisplayName("copyDocuments 以 _reindex 複製全部文件；deleteDocument 只刪指定實體索引內的文件")
    void copyAndDeleteDocument() {
        initializer.ensureCorrectMapping();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        repository.saveAll(List.of(doc(a, "Copy a"), doc(b, "Copy b")));
        admin.refresh(ArticleIndexAdmin.INITIAL_INDEX);
        String next = admin.newVersionedIndexName();
        admin.createIndex(next);

        long copied = admin.copyDocuments(ArticleIndexAdmin.INITIAL_INDEX, next);
        admin.deleteDocument(a.toString(), next);
        admin.refresh(next);

        assertThat(copied).isEqualTo(2);
        assertThat(template.count(template.matchAllQuery(), ArticleDocument.class, IndexCoordinates.of(next))).isEqualTo(1);
        assertThat(repository.existsById(a.toString())).as("alias（舊索引）不受影響").isTrue();
    }

    @Test
    @DisplayName("刪除不存在的實體索引：deleteIndexQuietly 回 false、不拋例外")
    void deleteIndexQuietly_missingIndex_returnsFalse() {
        assertThat(admin.deleteIndexQuietly("blog_articles_v_missing")).isFalse();
    }

    @Test
    @DisplayName("版本化索引名稱：前綴 blog_articles_v ＋ 時間戳（毫秒），依時鐘遞增")
    void newVersionedIndexName_usesClockTimestamp() {
        ArticleIndexAdmin fixed = new ArticleIndexAdmin(template,
                Clock.fixed(Instant.parse("2026-11-07T04:05:06.789Z"), ZoneId.of("UTC")));

        assertThat(fixed.newVersionedIndexName()).isEqualTo("blog_articles_v20261107040506789");
    }
}
