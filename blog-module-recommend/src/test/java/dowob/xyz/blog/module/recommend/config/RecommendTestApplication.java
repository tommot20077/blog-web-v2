package dowob.xyz.blog.module.recommend.config;

import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import dowob.xyz.blog.infrastructure.config.AuditingConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 推薦模組整合測試用 Spring Boot 應用程式
 *
 * <p>
 * 排除 RabbitMQ、Elasticsearch 與 DB 相關自動配置，
 * 使測試上下文可在僅有 Redis Testcontainer 的環境下獨立啟動。
 * AuditingConfig 依賴 JDBC 環境，此處以 Filter 排除。
 * ArticleFacade 與 SearchFacade 由測試以 MockBean 注入。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootApplication(
        scanBasePackages = {
                "dowob.xyz.blog.common",
                "dowob.xyz.blog.infrastructure",
                "dowob.xyz.blog.module.recommend"
        },
        exclude = {
                RabbitAutoConfiguration.class,
                ElasticsearchDataAutoConfiguration.class,
                ElasticsearchClientAutoConfiguration.class,
                ElasticsearchRestClientAutoConfiguration.class,
                ReactiveElasticsearchRepositoriesAutoConfiguration.class,
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class
        })
@ComponentScan(
        basePackages = {
                "dowob.xyz.blog.common",
                "dowob.xyz.blog.infrastructure",
                "dowob.xyz.blog.module.recommend"
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = AuditingConfig.class
        )
)
@EnableScheduling
public class RecommendTestApplication {

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
