package dowob.xyz.blog.e2e.search;

import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import dowob.xyz.blog.module.search.init.SearchIndexInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SearchIndexInitializer E2E 測試
 *
 * <p>
 * 驗證啟動時對 ES index mapping 的自我修復行為：
 * 不存在則建、mapping 不對齊 Java annotation 則砍掉重建。
 * </p>
 */
@DisplayName("SearchIndexInitializer E2E 測試")
class SearchIndexInitializerE2E extends AbstractE2ETest {

    @Autowired
    private ElasticsearchOperations esOps;

    @Autowired
    private SearchIndexInitializer initializer;

    @AfterEach
    void resetToCleanMapping() {
        // 確保下個測試開始時 index 處於正確 mapping 狀態（避免污染其他測試）
        IndexOperations idxOps = esOps.indexOps(ArticleDocument.class);
        if (idxOps.exists()) {
            idxOps.delete();
        }
        idxOps.createWithMapping();
    }

    @Test
    @DisplayName("Index 不存在時，啟動 initializer 應建立並套用 Java annotation mapping (status=keyword)")
    void initializer_indexNotExists_createsWithCorrectMapping() {
        IndexOperations idxOps = esOps.indexOps(ArticleDocument.class);
        if (idxOps.exists()) {
            idxOps.delete();
        }

        initializer.ensureCorrectMapping();

        assertThat(idxOps.exists()).isTrue();
        assertThat(statusFieldType(idxOps)).isEqualTo("keyword");
    }

    @Test
    @DisplayName("Index 存在但 mapping 不對 (status=text) 時，initializer 應砍掉重建為 keyword")
    void initializer_wrongMapping_recreatesWithKeyword() {
        IndexOperations idxOps = esOps.indexOps(ArticleDocument.class);
        if (idxOps.exists()) {
            idxOps.delete();
        }
        // 故意建一個 status=text mapping (模擬 dev dynamic mapping 結果)
        Document wrongMapping = Document.parse(
                "{\"properties\":{\"status\":{\"type\":\"text\"}}}"
        );
        idxOps.create(Map.of(), wrongMapping);
        assertThat(statusFieldType(idxOps)).isEqualTo("text");

        initializer.ensureCorrectMapping();

        assertThat(idxOps.exists()).isTrue();
        assertThat(statusFieldType(idxOps)).isEqualTo("keyword");
    }

    @Test
    @DisplayName("Index 存在、status=keyword 但 tags 非 nested 時，initializer 應砍掉重建為 nested")
    void initializer_tagsNotNested_recreatesIndex() {
        IndexOperations idxOps = esOps.indexOps(ArticleDocument.class);
        if (idxOps.exists()) {
            idxOps.delete();
        }
        // 故意建一個 status=keyword 但 tags=object（非 nested）的 mapping，
        // 模擬 dynamic mapping 未正確套用 nested type 的情況
        Document wrongMapping = Document.parse(
                "{\"properties\":{\"status\":{\"type\":\"keyword\"},\"tags\":{\"type\":\"object\"}}}"
        );
        idxOps.create(Map.of(), wrongMapping);
        assertThat(statusFieldType(idxOps)).isEqualTo("keyword");
        assertThat(tagsFieldType(idxOps)).isEqualTo("object");

        initializer.ensureCorrectMapping();

        assertThat(idxOps.exists()).isTrue();
        assertThat(statusFieldType(idxOps)).isEqualTo("keyword");
        assertThat(tagsFieldType(idxOps)).isEqualTo("nested");
    }

    private String statusFieldType(IndexOperations idxOps) {
        Map<String, Object> mapping = idxOps.getMapping();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) mapping.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> statusField = (Map<String, Object>) properties.get("status");
        return (String) statusField.get("type");
    }

    private String tagsFieldType(IndexOperations idxOps) {
        Map<String, Object> mapping = idxOps.getMapping();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) mapping.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> tagsField = (Map<String, Object>) properties.get("tags");
        return tagsField != null ? (String) tagsField.get("type") : null;
    }
}
