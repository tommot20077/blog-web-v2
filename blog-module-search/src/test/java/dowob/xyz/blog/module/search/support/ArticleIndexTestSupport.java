package dowob.xyz.blog.module.search.support;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import dowob.xyz.blog.module.search.index.ArticleIndexAdmin;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchClients;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.IndexInformation;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchCustomConversions;
import org.springframework.data.elasticsearch.core.convert.MappingElasticsearchConverter;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.mapping.SimpleElasticsearchMappingContext;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import java.util.List;

/**
 * 搜尋模組真 ES 整合測試的共用工具。
 *
 * <p>讀寫改走 alias 之後，{@code indexOps(ArticleDocument.class).delete()} 對著 alias 名稱會被 ES 拒絕
 * （{@code matches an alias, specify the corresponding concrete indices instead}），ES 8 也預設不允許以萬用字元刪除。
 * 因此重設環境改為：列出所有 {@code blog_articles*} 實體索引後逐一以實體名稱刪除（alias 隨之消失）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class ArticleIndexTestSupport {

    /**
     * 靜態工具類別，不提供實例化。
     */
    private ArticleIndexTestSupport() {
    }

    /**
     * 與正式環境一致的 ES 測試容器設定（版本見 E2E 基底）。
     *
     * @return 尚未啟動的容器
     */
    @SuppressWarnings("resource")
    public static ElasticsearchContainer newContainer() {
        return new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.11.0")
                .withEnv("xpack.security.enabled", "false")
                .withEnv("discovery.type", "single-node")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
    }

    /**
     * 建立與 Spring Boot 自動配置一致的 {@link ElasticsearchTemplate}（UUID 視為簡單型別）。
     *
     * @param container 已啟動的容器
     * @return template
     */
    public static ElasticsearchTemplate newTemplate(ElasticsearchContainer container) {
        var restClient = ElasticsearchClients.getRestClient(
                ClientConfiguration.builder().connectedTo(container.getHttpHostAddress()).build());
        ElasticsearchClient client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
        ElasticsearchCustomConversions conversions = new ElasticsearchCustomConversions(List.of());
        SimpleElasticsearchMappingContext mappingContext = new SimpleElasticsearchMappingContext();
        mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        MappingElasticsearchConverter converter = new MappingElasticsearchConverter(mappingContext);
        converter.setConversions(conversions);
        converter.afterPropertiesSet();
        return new ElasticsearchTemplate(client, converter);
    }

    /**
     * 刪除所有名稱以 {@code blog_articles} 開頭的實體索引（含改版前的實體 {@code blog_articles} 與所有版本化索引）。
     *
     * @param template ES template
     */
    public static void deleteAllArticleIndices(ElasticsearchTemplate template) {
        List<IndexInformation> indices = template.indexOps(IndexCoordinates.of(ArticleIndexAdmin.ALIAS))
                .getInformation(IndexCoordinates.of(ArticleIndexAdmin.ALIAS + "*"));
        for (IndexInformation info : indices) {
            template.indexOps(IndexCoordinates.of(info.getName())).delete();
        }
    }

    /**
     * 列出目前所有 {@code blog_articles*} 實體索引名稱。
     *
     * @param template ES template
     * @return 實體索引名稱
     */
    public static List<String> articleIndexNames(ElasticsearchTemplate template) {
        return template.indexOps(IndexCoordinates.of(ArticleIndexAdmin.ALIAS))
                .getInformation(IndexCoordinates.of(ArticleIndexAdmin.ALIAS + "*")).stream()
                .map(IndexInformation::getName)
                .sorted()
                .toList();
    }
}
