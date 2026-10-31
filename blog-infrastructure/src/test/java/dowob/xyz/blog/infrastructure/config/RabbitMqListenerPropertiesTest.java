package dowob.xyz.blog.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PERF-15 回歸守衛：{@code spring.rabbitmq.listener.simple.*} 必須真的套用到監聽容器。
 *
 * <p>過去 {@code rabbitListenerContainerFactory} 從零手建、未經
 * {@code SimpleRabbitListenerContainerFactoryConfigurer}，yaml 的 concurrency／prefetch 設了沒反應且無任何錯誤。
 * 本測試以真實的 {@link RabbitAutoConfiguration}＋{@link RabbitMqConfig} 組裝 context
 * （連線為 lazy，不需要 broker），對容器斷言屬性值。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("RabbitMqConfig：spring.rabbitmq.listener.simple.* 套用到監聽容器（PERF-15）")
class RabbitMqListenerPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withUserConfiguration(RabbitMqConfig.class);

    @Test
    @DisplayName("設定 concurrency／max-concurrency／prefetch／acknowledge-mode → 容器套用相同的值")
    void listenerSimpleProperties_areAppliedToContainer() {
        runner.withPropertyValues(
                "spring.rabbitmq.listener.simple.concurrency=3",
                "spring.rabbitmq.listener.simple.max-concurrency=5",
                "spring.rabbitmq.listener.simple.prefetch=7",
                "spring.rabbitmq.listener.simple.acknowledge-mode=manual"
        ).run(context -> {
            SimpleRabbitListenerContainerFactory factory =
                    context.getBean("rabbitListenerContainerFactory", SimpleRabbitListenerContainerFactory.class);

            SimpleMessageListenerContainer container = (SimpleMessageListenerContainer) factory.createListenerContainer();

            assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(3);
            assertThat(ReflectionTestUtils.getField(container, "maxConcurrentConsumers")).isEqualTo(5);
            assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(7);
            assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        });
    }

    @Test
    @DisplayName("沒有任何 listener 設定 → 不指定 concurrency／prefetch 時仍可建出容器（容器自身預設）")
    void withoutListenerProperties_containerStillCreated() {
        runner.run(context -> {
            SimpleRabbitListenerContainerFactory factory =
                    context.getBean("rabbitListenerContainerFactory", SimpleRabbitListenerContainerFactory.class);

            SimpleMessageListenerContainer container = (SimpleMessageListenerContainer) factory.createListenerContainer();

            assertThat(container).isNotNull();
        });
    }
}
