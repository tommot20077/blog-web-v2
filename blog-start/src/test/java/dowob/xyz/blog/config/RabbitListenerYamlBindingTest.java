package dowob.xyz.blog.config;

import com.zaxxer.hikari.HikariDataSource;
import dowob.xyz.blog.infrastructure.config.RabbitMqConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.file.consumer.FileArticleContentChangedConsumer;
import dowob.xyz.blog.module.file.consumer.FileArticleDeletedConsumer;
import dowob.xyz.blog.module.file.service.ArticleFileBindingService;
import dowob.xyz.blog.module.file.service.FileArticleCleanupService;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 正式 {@code application.yaml} 的 MQ 監聽容器、連線池與排程池設定守衛（PERF-15／PERF-30／PERF-16）。
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
    @DisplayName("yaml 的 concurrency=2、max-concurrency=2、prefetch=1、acknowledge-mode=auto 套用到 rabbitListenerContainerFactory 產出的容器")
    void yamlListenerSettings_areAppliedToContainer() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            SimpleRabbitListenerContainerFactory factory =
                    context.getBean("rabbitListenerContainerFactory", SimpleRabbitListenerContainerFactory.class);

            SimpleMessageListenerContainer container = (SimpleMessageListenerContainer) factory.createListenerContainer();

            assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(2);
            assertThat(ReflectionTestUtils.getField(container, "maxConcurrentConsumers")).isEqualTo(2);
            assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(1);
            assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.AUTO);
        });
    }

    @Test
    @DisplayName("yaml 的 Hikari maximum-pool-size=32（13 個 DB consumer 最壞佔 24 條：11×2＋2 條固定 1-1 的 file consumer，已由實際容器斷言 max=1，仍留 8 條給 HTTP／排程；W7-d1 移除 series.article-deleted 後的算式，池大小維持 Yuan 2026-11-05 的決定）、minimum-idle=10")
    void yamlHikariPoolSize_isThirtyTwo() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            HikariDataSource dataSource = context.getBean(HikariDataSource.class);

            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(32);
            assertThat(dataSource.getMinimumIdle()).isEqualTo(10);
        });
    }

    @Test
    @DisplayName("yaml 的 spring.task.scheduling.pool.size=4 套用到實際的 ThreadPoolTaskScheduler（PERF-16：預設 1 條會讓 fixedDelay job 互相等待）")
    void yamlSchedulingPoolSize_isFour() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(SchedulingEnabledConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);

                    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
                });
    }

    @Test
    @DisplayName("固定單執行緒的 consumer（file.article-binding、file.article-deleted）：實際容器的 concurrent 與 max 都是 1（\"1\" 不含 '-' 時 max 會沿用 factory 的 2）")
    void singleThreadedListeners_haveMaxConcurrencyOne() {
        runner.withUserConfiguration(EnableRabbitConfig.class)
                .withPropertyValues("spring.rabbitmq.listener.simple.auto-startup=false")
                .withBean(ArticleFileBindingService.class, () -> Mockito.mock(ArticleFileBindingService.class))
                .withBean(FileArticleCleanupService.class, () -> Mockito.mock(FileArticleCleanupService.class))
                .withBean(ConsumerExecutor.class, () -> Mockito.mock(ConsumerExecutor.class))
                .withBean(FileArticleContentChangedConsumer.class)
                .withBean(FileArticleDeletedConsumer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RabbitListenerEndpointRegistry registry = context.getBean(RabbitListenerEndpointRegistry.class);
                    for (String queue : new String[]{"file.article-binding", "file.article-deleted"}) {
                        SimpleMessageListenerContainer container = registry.getListenerContainers().stream()
                                .map(SimpleMessageListenerContainer.class::cast)
                                .filter(c -> java.util.Arrays.asList(c.getQueueNames()).contains(queue))
                                .findFirst().orElseThrow();
                        assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).as(queue).isEqualTo(1);
                        assertThat(ReflectionTestUtils.getField(container, "maxConcurrentConsumers")).as(queue).isEqualTo(1);
                    }
                });
    }

    /** 啟用 {@code @RabbitListener} 處理，讓 registry 依 yaml 的 factory 建立真實容器（不啟動、不連線） */
    @Configuration(proxyBeanMethods = false)
    @EnableRabbit
    static class EnableRabbitConfig {
    }

    /** 提供 {@code @EnableScheduling}，讓 {@code TaskSchedulingAutoConfiguration} 建立 ThreadPoolTaskScheduler */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingEnabledConfig {
    }
}
