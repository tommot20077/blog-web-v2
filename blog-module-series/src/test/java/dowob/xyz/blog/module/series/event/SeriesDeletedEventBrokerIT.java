package dowob.xyz.blog.module.series.event;

import dowob.xyz.blog.infrastructure.config.RabbitMqConfig;
import dowob.xyz.blog.infrastructure.event.SeriesDeletedEvent;
import dowob.xyz.blog.infrastructure.event.SeriesEventContract;
import dowob.xyz.blog.module.series.config.SeriesRabbitMqConfig;
import dowob.xyz.blog.module.series.service.SeriesEventPublisher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code SeriesDeletedEvent} 對真實 RabbitMQ 的整合測試：本階段<b>沒有 consumer</b>（article 於 M6 訂閱），
 * 必須確認「exchange 已宣告、沒有任何 queue 綁定時發送不出錯」，且日後訂閱端（以相同契約綁定）收得到
 * 正確的 JSON。使用與正式環境相同的 {@link RabbitMqConfig#messageConverter()}。
 *
 * @author Yuan
 * @version 1.0
 */
@Testcontainers
@DisplayName("SeriesDeletedEvent 對真實 RabbitMQ")
class SeriesDeletedEventBrokerIT {

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management-alpine");

    private static CachingConnectionFactory connectionFactory;
    private static RabbitAdmin admin;
    private static RabbitTemplate template;
    private static SeriesEventPublisher publisher;

    @BeforeAll
    static void connect() {
        connectionFactory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        connectionFactory.setUsername(rabbit.getAdminUsername());
        connectionFactory.setPassword(rabbit.getAdminPassword());
        admin = new RabbitAdmin(connectionFactory);
        template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new RabbitMqConfig().messageConverter());
        publisher = new SeriesEventPublisher(template);
        admin.declareExchange(new SeriesRabbitMqConfig().seriesEventsExchange());
    }

    @AfterAll
    static void close() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("沒有任何 queue 綁定（本階段沒有 consumer）→ exchange 存在，發送不出錯")
    void publishWithoutAnyConsumer_isSilentlyDroppedWithoutError() {
        assertThatCode(() -> template.execute(channel ->
                channel.exchangeDeclarePassive(SeriesEventContract.EXCHANGE)))
                .as("series.events 已由 SeriesRabbitMqConfig 宣告").doesNotThrowAnyException();

        assertThatCode(() -> publisher.publishDeleted(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("日後的訂閱端以契約的 routing key 綁定 queue → 收到 JSON：eventId、seriesUuid、occurredAt")
    void futureSubscriber_receivesEventAsContractJson() {
        Queue queue = new Queue("series.deleted.future-subscriber", false, true, true);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue)
                .to(new SeriesRabbitMqConfig().seriesEventsExchange())
                .with(SeriesEventContract.ROUTING_KEY_DELETED));
        UUID seriesUuid = UUID.randomUUID();

        publisher.publishDeleted(seriesUuid);

        Message received = template.receive(queue.getName(), 5_000);
        assertThat(received).as("訂閱端應收到訊息").isNotNull();
        Object restored = new RabbitMqConfig().messageConverter().fromMessage(withInferredType(received));
        assertThat(restored).isInstanceOfSatisfying(SeriesDeletedEvent.class, event -> {
            assertThat(event.seriesUuid()).isEqualTo(seriesUuid);
            assertThat(event.eventId()).isNotNull();
            assertThat(event.occurredAt()).isBeforeOrEqualTo(Instant.now());
        });
    }

    /** 模擬 listener 以參數型別推斷目標類別（與其他事件的 consumer 相同做法）。 */
    private static Message withInferredType(Message message) {
        message.getMessageProperties().setInferredArgumentType(SeriesDeletedEvent.class);
        return message;
    }
}
