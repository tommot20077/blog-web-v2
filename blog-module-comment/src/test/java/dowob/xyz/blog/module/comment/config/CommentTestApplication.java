package dowob.xyz.blog.module.comment.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

/**
 * Comment 模組整合測試專用 Spring Boot 應用程式。
 *
 * <p>排除 RabbitMQ / Elasticsearch 等非本模組需求的 auto-config，
 * 並掃描 article 模組（comment 透過 ArticleFacade 操作）。</p>
 *
 * @author Yuan
 */
@SpringBootApplication(
        scanBasePackages = {
                "dowob.xyz.blog.common",
                "dowob.xyz.blog.infrastructure",
                "dowob.xyz.blog.module.article",
                "dowob.xyz.blog.module.series",
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
        "dowob.xyz.blog.module.series.repository",
        "dowob.xyz.blog.module.comment.repository",
        "dowob.xyz.blog.module.reading.repository"
})
@MapperScan(basePackages = {
        "dowob.xyz.blog.module.article.mapper",
        "dowob.xyz.blog.module.series.mapper",
        "dowob.xyz.blog.module.comment.mapper",
        "dowob.xyz.blog.module.reading.mapper"
})
public class CommentTestApplication {

    /**
     * 測試用指標註冊表。
     *
     * <p>comment 模組的測試 classpath 沒有 actuator，因此沒有自動配置的 {@link MeterRegistry} bean；
     * 孤兒校正排程（{@code OrphanReconciliationJob}）需要它，故在此補一個記憶體內實作。
     * 正式環境由 blog-start 的 actuator 提供。</p>
     *
     * @return 記憶體內的 MeterRegistry
     */
    @Bean
    public MeterRegistry testMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}
