package dowob.xyz.blog.config;

import com.zaxxer.hikari.HikariDataSource;
import dowob.xyz.blog.infrastructure.config.RabbitMqConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 正式 {@code application.yaml} 的 MQ 監聽容器與連線池設定守衛（PERF-15／PERF-30）。
 *
 * <p>以真實 yaml 載入 {@code RabbitAutoConfiguration}＋{@link RabbitMqConfig}，對實際產生的容器斷言
 * concurrency／prefetch／ack 模式——yaml 寫了卻沒套用（PERF-15）或被誤改（例如 prefetch 改回大值使
 * 重試退避期間的 prefetch 緩衝被卡住）都會在這裡失敗。連線池大小同檔斷言，因為它與 MQ 並行度綁定
 * （見 application.yaml 的預算註解）。不需要 Docker（連線皆 lazy）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("application.yaml：MQ 監聽容器與連線池設定")
class RabbitListenerYamlBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class, DataSourceAutoConfiguration.class))
            .withUserConfiguration(RabbitMqConfig.class);

    @Test
    @DisplayName("yaml 的 concurrency=2、max-concurrency=2、prefetch=1 套用到 rabbitListenerContainerFactory 產出的容器")
    void yamlListenerSettings_areAppliedToContainer() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            SimpleRabbitListenerContainerFactory factory =
                    context.getBean("rabbitListenerContainerFactory", SimpleRabbitListenerContainerFactory.class);

            SimpleMessageListenerContainer container = (SimpleMessageListenerContainer) factory.createListenerContainer();

            assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(2);
            assertThat(ReflectionTestUtils.getField(container, "maxConcurrentConsumers")).isEqualTo(2);
            assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(1);
            assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        });
    }

    @Test
    @DisplayName("yaml 的 Hikari maximum-pool-size=30（MQ consumer 吃滿時仍留連線給 HTTP）")
    void yamlHikariPoolSize_isThirty() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            HikariDataSource dataSource = context.getBean(HikariDataSource.class);

            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(30);
        });
    }
}
