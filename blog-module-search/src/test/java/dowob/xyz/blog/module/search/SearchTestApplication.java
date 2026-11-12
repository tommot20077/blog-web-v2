package dowob.xyz.blog.module.search;

import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;

/**
 * 搜尋模組整合測試用 Spring Boot 應用程式
 *
 * <p>
 * 排除 RabbitMQ、Elasticsearch、資料庫等不需要的自動配置，
 * 使 {@link org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest} 可正確啟動。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootApplication(
        scanBasePackages = {
                "dowob.xyz.blog.common",
                "dowob.xyz.blog.infrastructure",
                "dowob.xyz.blog.module.search"
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
public class SearchTestApplication {

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
