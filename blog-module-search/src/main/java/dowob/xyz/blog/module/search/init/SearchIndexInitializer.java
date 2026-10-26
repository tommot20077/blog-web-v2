package dowob.xyz.blog.module.search.init;

import dowob.xyz.blog.module.search.document.ArticleDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Search index 啟動初始化器
 *
 * <p>
 * 因 {@link ArticleDocument} 標註 {@code @Document(createIndex = false)}，Spring Data ES
 * 不會在 context 啟動時自動建立索引；若 index 不存在，第一次寫入會由 ES dynamic mapping
 * 建立 — 結果與 Java {@code @Field} annotation 不一致 (例如 {@code status} 變成 text 而非
 * keyword)，導致 {@code term} query 失效、全文搜尋整體 0 result。
 *
 * <p>本初始化器在啟動時：
 * <ol>
 *   <li>若 {@code blog_articles} 不存在 → {@code createWithMapping()} 從 Java annotation 建</li>
 *   <li>若已存在但 mapping 不對齊（如 {@code status} 非 keyword）→ delete + 以正確 mapping 重建</li>
 * </ol>
 * 重建後資料會清空，admin 需主動觸發 {@code POST /api/v1/admin/search/reindex} 灌回。
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexInitializer implements ApplicationRunner {

    private final ElasticsearchOperations elasticsearchOperations;

    @Override
    public void run(ApplicationArguments args) {
        ensureCorrectMapping();
    }

    /**
     * 確認 ES index 存在且 mapping 對齊 Java annotation；
     * status／tags 不對齊則砍掉重建（資料需 admin reindex 灌回）。
     *
     * <p>{@code author.uuid} 不屬於「砍掉重建」的判準：沒有任何查詢、排序、aggregation 用到
     * {@code author.*}，舊 author mapping 不影響搜尋；刪索引會讓搜尋在 admin reindex 前整個回空，
     * 多台機器同時啟動時還會互相刪掉對方剛建好的索引。遷移由 {@code reindexAll} 以 id 覆寫文件完成
     * （舊文件的 author 欄位整份被取代），這裡偵測到舊 author mapping 只記 warn。</p>
     */
    public void ensureCorrectMapping() {
        IndexOperations indexOps = elasticsearchOperations.indexOps(ArticleDocument.class);
        if (!indexOps.exists()) {
            indexOps.createWithMapping();
            log.info("Elasticsearch index 'blog_articles' 已建立並套用 Java annotation mapping");
            return;
        }
        warnIfLegacyAuthorMapping(indexOps);
        if (isMappingValid(indexOps)) {
            log.debug("Elasticsearch index 'blog_articles' mapping 已對齊");
            return;
        }
        log.warn("Elasticsearch index 'blog_articles' mapping 與 Java annotation 不一致 (status 非 keyword 或 tags 非 nested), "
                + "將砍掉重建; 請執行 POST /api/v1/admin/search/reindex 灌回資料");
        indexOps.delete();
        indexOps.createWithMapping();
        log.info("Elasticsearch index 'blog_articles' 已重建; 等待 admin 觸發 reindex");
    }

    private boolean isMappingValid(IndexOperations indexOps) {
        Map<String, Object> mapping = indexOps.getMapping();
        if (mapping == null) {
            return false;
        }
        Object propertiesObj = mapping.get("properties");
        if (!(propertiesObj instanceof Map<?, ?> properties)) {
            return false;
        }
        // 驗證 status 為 keyword（term query 必要條件）
        Object statusObj = properties.get("status");
        if (!(statusObj instanceof Map<?, ?> statusField)) {
            return false;
        }
        if (!"keyword".equals(statusField.get("type"))) {
            return false;
        }
        // 驗證 tags 為 nested（Nested Query 必要條件）
        Object tagsObj = properties.get("tags");
        if (!(tagsObj instanceof Map<?, ?> tagsField)) {
            return false;
        }
        return "nested".equals(tagsField.get("type"));
    }

    /**
     * 偵測舊 author mapping（沒有 {@code author.uuid} keyword），只記 warn，不刪除也不重建
     *
     * @param indexOps 索引操作
     */
    private void warnIfLegacyAuthorMapping(IndexOperations indexOps) {
        Map<String, Object> mapping = indexOps.getMapping();
        if (mapping == null || !(mapping.get("properties") instanceof Map<?, ?> properties)) {
            return;
        }
        if (!isAuthorUuidKeyword(properties)) {
            log.warn("Elasticsearch index 'blog_articles' 的 author mapping 為舊版（缺少 author.uuid keyword），"
                    + "不影響搜尋，索引不會被刪除；需執行 POST /api/v1/admin/search/reindex 以移除舊作者欄位");
        }
    }

    /**
     * 檢查 {@code author.uuid} 是否為 keyword 型別
     *
     * @param properties 索引 mapping 的頂層 properties
     * @return {@code author.properties.uuid.type} 為 keyword 時 true
     */
    private boolean isAuthorUuidKeyword(Map<?, ?> properties) {
        if (!(properties.get("author") instanceof Map<?, ?> authorField)) {
            return false;
        }
        if (!(authorField.get("properties") instanceof Map<?, ?> authorProps)) {
            return false;
        }
        return authorProps.get("uuid") instanceof Map<?, ?> uuidField
                && "keyword".equals(uuidField.get("type"));
    }
}
