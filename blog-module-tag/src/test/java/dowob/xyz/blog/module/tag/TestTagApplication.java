package dowob.xyz.blog.module.tag;

import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

/**
 * 標籤模組整合測試用 Spring Boot 啟動類
 *
 * <p>
 * 僅掃描 common、infrastructure 與 tag 模組所需的 Bean，
 * 排除 RabbitMQ、Elasticsearch 自動配置以簡化測試環境。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootApplication(
        scanBasePackages = {
                "dowob.xyz.blog.common",
                "dowob.xyz.blog.infrastructure",
                "dowob.xyz.blog.module.tag"
        },
        exclude = {
                RabbitAutoConfiguration.class,
                ElasticsearchDataAutoConfiguration.class,
                ElasticsearchClientAutoConfiguration.class,
                ElasticsearchRestClientAutoConfiguration.class,
                ReactiveElasticsearchRepositoriesAutoConfiguration.class
        }
)
@EnableJdbcRepositories(basePackages = {
        "dowob.xyz.blog.module.tag.repository"
})
public class TestTagApplication {

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
