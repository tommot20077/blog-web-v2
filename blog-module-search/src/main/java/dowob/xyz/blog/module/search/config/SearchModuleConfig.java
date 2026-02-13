package dowob.xyz.blog.module.search.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

/**
 * 搜尋模組 Elasticsearch Repository 配置
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
@EnableElasticsearchRepositories(basePackages = "dowob.xyz.blog.module.search.repository")
public class SearchModuleConfig {
}
