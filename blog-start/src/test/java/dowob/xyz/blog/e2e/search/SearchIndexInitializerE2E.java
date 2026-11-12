package dowob.xyz.blog.e2e.search;

import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.SearchIndexCleaner;
import dowob.xyz.blog.module.search.index.ArticleIndexAdmin;
import dowob.xyz.blog.module.search.index.IndexState;
import dowob.xyz.blog.module.search.init.SearchIndexInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexInformation;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SearchIndexInitializer E2E 測試
 *
 * <p>
 * 驗證啟動時對 ES 索引的自我修復行為（D1／PERF-01 起讀寫改走 alias）：
 * 名稱不存在則建立 {@code blog_articles_v1} 與 alias；{@code blog_articles} 是 mapping 不對齊的實體索引
 * （模擬開發機 dynamic mapping 的結果）時，遷移為 alias ＋ 正確 mapping 的版本化索引。
 * </p>
 */
@DisplayName("SearchIndexInitializer E2E 測試")
class SearchIndexInitializerE2E extends AbstractE2ETest {

    @Autowired
    private ElasticsearchOperations esOps;

    @Autowired
    private SearchIndexInitializer initializer;

    @Autowired
    private ArticleIndexAdmin indexAdmin;

    @Autowired
    private SearchIndexCleaner searchIndexCleaner;

    @AfterEach
    void resetToCleanMapping() {
        /* 確保下個測試開始時是正常的 alias 狀態（避免污染其他測試） */
        searchIndexCleaner.resetToFreshAlias();
    }

    /** 刪除所有 blog_articles* 實體索引（不經 initializer 重建），模擬全新或改版前的環境 */
    private void deleteAllArticleIndices() {
        for (IndexInformation info : esOps.indexOps(IndexCoordinates.of(ArticleIndexAdmin.ALIAS))
                .getInformation(IndexCoordinates.of(ArticleIndexAdmin.ALIAS + "*"))) {
            esOps.indexOps(IndexCoordinates.of(info.getName())).delete();
        }
    }

    @Test
    @DisplayName("名稱不存在時，initializer 建立 blog_articles_v1 與 alias，並套用 Java annotation mapping (status=keyword)")
    void initializer_indexNotExists_createsAliasWithCorrectMapping() {
        deleteAllArticleIndices();
        assertThat(indexAdmin.inspect()).isEqualTo(IndexState.absent());

        initializer.ensureCorrectMapping();

        assertThat(indexAdmin.inspect()).isEqualTo(IndexState.alias(java.util.List.of(ArticleIndexAdmin.INITIAL_INDEX)));
        assertThat(fieldType(ArticleIndexAdmin.INITIAL_INDEX, "status")).isEqualTo("keyword");
    }

    @Test
    @DisplayName("blog_articles 是 mapping 不對 (status=text) 的實體索引時，initializer 遷移為 alias ＋ status=keyword 的版本化索引")
    void initializer_wrongMapping_migratesToKeyword() {
        deleteAllArticleIndices();
        /* 故意建一個 status=text 的實體索引 (模擬 dev dynamic mapping 結果) */
        IndexOperations concrete = esOps.indexOps(IndexCoordinates.of(ArticleIndexAdmin.ALIAS));
        concrete.create(Map.of(), Document.parse("{\"properties\":{\"status\":{\"type\":\"text\"}}}"));
        assertThat(indexAdmin.inspect()).isEqualTo(IndexState.concrete());

        initializer.ensureCorrectMapping();

        IndexState state = indexAdmin.inspect();
        assertThat(state.kind()).isEqualTo(IndexState.Kind.ALIAS);
        assertThat(state.aliasTargets()).singleElement().asString().startsWith(ArticleIndexAdmin.VERSIONED_PREFIX);
        assertThat(fieldType(state.aliasTargets().get(0), "status")).isEqualTo("keyword");
    }

    @Test
    @DisplayName("blog_articles 是 status=keyword 但 tags 非 nested 的實體索引時，initializer 遷移為 tags=nested 的版本化索引")
    void initializer_tagsNotNested_migratesIndex() {
        deleteAllArticleIndices();
        /* 故意建一個 status=keyword 但 tags=object（非 nested）的實體索引，模擬 dynamic mapping 未正確套用 nested type 的情況 */
        IndexOperations concrete = esOps.indexOps(IndexCoordinates.of(ArticleIndexAdmin.ALIAS));
        concrete.create(Map.of(), Document.parse(
                "{\"properties\":{\"status\":{\"type\":\"keyword\"},\"tags\":{\"type\":\"object\"}}}"));

        initializer.ensureCorrectMapping();

        String physical = indexAdmin.inspect().aliasTargets().get(0);
        assertThat(fieldType(physical, "status")).isEqualTo("keyword");
        assertThat(fieldType(physical, "tags")).isEqualTo("nested");
    }

    @SuppressWarnings("unchecked")
    private String fieldType(String physicalIndex, String field) {
        Map<String, Object> properties = (Map<String, Object>) indexAdmin.getMapping(physicalIndex).get("properties");
        Map<String, Object> fieldMapping = (Map<String, Object>) properties.get(field);
        return fieldMapping != null ? (String) fieldMapping.get("type") : null;
    }
}
