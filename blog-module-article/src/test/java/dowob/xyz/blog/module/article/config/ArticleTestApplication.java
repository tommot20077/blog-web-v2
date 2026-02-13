package dowob.xyz.blog.module.article.config;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

/**
 * 文章模組整合測試用 Spring Boot 應用程式
 *
 * <p>
 * 排除與本模組測試無關的自動配置（RabbitMQ、Elasticsearch），
 * 使測試上下文可以獨立啟動。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootApplication(
        scanBasePackages = {
                "dowob.xyz.blog.common",
                "dowob.xyz.blog.infrastructure",
                "dowob.xyz.blog.module.article"
        },
        exclude = {
                RabbitAutoConfiguration.class,
                ElasticsearchDataAutoConfiguration.class,
                ElasticsearchClientAutoConfiguration.class,
                ElasticsearchRestClientAutoConfiguration.class,
                ReactiveElasticsearchRepositoriesAutoConfiguration.class
        })
@EnableJdbcRepositories(basePackages = "dowob.xyz.blog.module.article.repository")
public class ArticleTestApplication {
}
