package dowob.xyz.blog.module.comment.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

/**
 * Comment 模組整合測試專用 Spring Boot 應用程式。
 *
 * <p>排除 RabbitMQ / Elasticsearch 等非本模組需求的 auto-config，
 * 並掃描 article 模組（comment 透過 ArticleService 操作）。</p>
 *
 * @author Yuan
 */
@SpringBootApplication(
        scanBasePackages = {
                "dowob.xyz.blog.common",
                "dowob.xyz.blog.infrastructure",
                "dowob.xyz.blog.module.article",
                "dowob.xyz.blog.module.comment",
                "dowob.xyz.blog.module.reading"
        },
        exclude = {
                RabbitAutoConfiguration.class,
                ElasticsearchDataAutoConfiguration.class,
                ElasticsearchClientAutoConfiguration.class,
                ElasticsearchRestClientAutoConfiguration.class,
                ReactiveElasticsearchRepositoriesAutoConfiguration.class
        })
@EnableJdbcRepositories(basePackages = {
        "dowob.xyz.blog.module.article.repository",
        "dowob.xyz.blog.module.comment.repository",
        "dowob.xyz.blog.module.reading.repository"
})
@MapperScan(basePackages = {
        "dowob.xyz.blog.module.article.mapper",
        "dowob.xyz.blog.module.comment.mapper",
        "dowob.xyz.blog.module.reading.mapper"
})
public class CommentTestApplication {
}
