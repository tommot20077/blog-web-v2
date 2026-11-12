package dowob.xyz.blog.module.reading.config;

import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Reading 模組 IT 專用 Spring Boot 應用程式。
 *
 * @author Yuan
 */
@SpringBootApplication(
        scanBasePackages = {
                "dowob.xyz.blog.common",
                "dowob.xyz.blog.infrastructure",
                "dowob.xyz.blog.module.article",
                "dowob.xyz.blog.module.series",
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
        "dowob.xyz.blog.module.reading.repository"
})
@MapperScan(basePackages = {
        "dowob.xyz.blog.module.article.mapper",
        "dowob.xyz.blog.module.series.mapper",
        "dowob.xyz.blog.module.reading.mapper"
})
@EnableScheduling
public class ReadingTestApplication {

    /**
     * 補上被排除的 {@code RabbitAutoConfiguration} 原本提供的容器工廠設定器。
     *
     * <p>{@code RabbitMqConfig.rabbitListenerContainerFactory} 依賴它（PERF-15：listener 設定必須經 configurer 套用）。
     * 本測試應用程式不連線 broker，使用預設的 {@link RabbitProperties} 即可。</p>
     *
     * @return 以預設 RabbitProperties 建立的設定器
     */
    @Bean
    public SimpleRabbitListenerContainerFactoryConfigurer simpleRabbitListenerContainerFactoryConfigurer() {
        return new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties());
    }
}
