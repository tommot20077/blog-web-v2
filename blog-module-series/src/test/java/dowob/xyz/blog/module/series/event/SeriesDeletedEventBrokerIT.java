package dowob.xyz.blog.module.series.event;

import dowob.xyz.blog.infrastructure.config.RabbitMqConfig;
import dowob.xyz.blog.infrastructure.event.SeriesDeletedEvent;
import dowob.xyz.blog.infrastructure.event.SeriesEventContract;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.series.config.SeriesRabbitMqConfig;
import dowob.xyz.blog.module.series.service.SeriesEventPublisher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
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
 * {@code SeriesDeletedEvent} 對真實 RabbitMQ 的整合測試：確認「exchange 已宣告、沒有任何 queue 綁定時發送不出錯」，
 * 且 article 模組（M6-b 起的訂閱端）以自己的 {@link ArticleRabbitMqConfig} 宣告的 queue／binding 收得到正確的 JSON；
 * article 與 series 各自宣告同名的 {@code series.events} exchange（參數相同）時，重複宣告不衝突。
 * 使用與正式環境相同的 {@link RabbitMqConfig#messageConverter()}。
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
    @DisplayName("沒有任何 queue 綁定 → exchange 存在，發送不出錯（topic exchange 直接丟棄）")
    void publishWithoutAnyConsumer_isSilentlyDroppedWithoutError() {
        assertThatCode(() -> template.execute(channel ->
                channel.exchangeDeclarePassive(SeriesEventContract.EXCHANGE)))
                .as("series.events 已由 SeriesRabbitMqConfig 宣告").doesNotThrowAnyException();

        assertThatCode(() -> publisher.publishDeleted(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("article 與 series 各自宣告同名的 series.events exchange（參數相同）→ 重複宣告冪等，不被 broker 拒絕")
    void duplicateExchangeDeclarationByArticleAndSeries_isIdempotent() {
        assertThatCode(() -> {
            admin.declareExchange(new SeriesRabbitMqConfig().seriesEventsExchange());
            admin.declareExchange(new ArticleRabbitMqConfig().articleSeriesEventsExchange());
            admin.declareExchange(new SeriesRabbitMqConfig().seriesEventsExchange());
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("article 的 queue／binding（ArticleRabbitMqConfig）→ series 發出的事件送進 article.series-deleted，JSON 為 eventId、seriesUuid、occurredAt")
    void articleQueue_receivesEventPublishedBySeries() {
        ArticleRabbitMqConfig articleConfig = new ArticleRabbitMqConfig();
        Queue queue = articleConfig.articleSeriesDeletedQueue();
        admin.declareExchange(articleConfig.articleSeriesEventsExchange());
        admin.declareQueue(queue);
        admin.declareBinding(articleConfig.articleSeriesDeletedBinding(queue, articleConfig.articleSeriesEventsExchange()));
        UUID seriesUuid = UUID.randomUUID();

        publisher.publishDeleted(seriesUuid);

        Message received = template.receive(queue.getName(), 5_000);
        assertThat(received).as("article.series-deleted 應收到訊息").isNotNull();
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
