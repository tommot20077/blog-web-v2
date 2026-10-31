package dowob.xyz.blog.mq;

import dowob.xyz.blog.infrastructure.config.RabbitMqConfig;
import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.event.SeriesDeletedEvent;
import dowob.xyz.blog.infrastructure.event.SeriesEventContract;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.article.consumer.ArticleSeriesDeletedConsumer;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import dowob.xyz.blog.module.comment.config.CommentRabbitMqConfig;
import dowob.xyz.blog.module.comment.consumer.CommentArticleDeletedConsumer;
import dowob.xyz.blog.module.file.event.ImageUploadedEvent;
import dowob.xyz.blog.module.reading.config.ReadingRabbitMqConfig;
import dowob.xyz.blog.module.reading.consumer.ReadingArticleDeletedConsumer;
import dowob.xyz.blog.module.search.config.SearchRabbitMqConfig;
import dowob.xyz.blog.module.search.listener.ArticleSearchListener;
import dowob.xyz.blog.module.series.config.SeriesRabbitMqConfig;
import dowob.xyz.blog.module.series.consumer.SeriesArticleDeletedConsumer;
import dowob.xyz.blog.module.series.service.SeriesEventPublisher;
import dowob.xyz.blog.module.tag.config.TagRabbitMqConfig;
import dowob.xyz.blog.module.tag.consumer.TagArticleDeletedConsumer;
import dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent;
import dowob.xyz.blog.module.user.model.event.UserRegisteredEvent;
import dowob.xyz.blog.module.version.config.VersionRabbitMqConfig;
import dowob.xyz.blog.module.version.consumer.VersionArticleDeletedConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.RabbitMQContainer;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「刪除連動」queue 的真 RabbitMQ 綁定測試（backlog：Z3 打開重試前須補）。
 *
 * <p>以<b>正式的各模組 {@code *RabbitMqConfig}</b>（經 Spring context 與 {@link RabbitAdmin} 宣告，
 * 與正式啟動時宣告 exchange／queue／binding 的方式相同）連到真 RabbitMQ，再由<b>正式的 publisher</b>
 * ({@link ArticleEventPublisher#publishDeleted}、{@link SeriesEventPublisher#publishDeleted}) 發布事件到正式的
 * exchange＋routing key，逐一斷言：</p>
 * <ul>
 *   <li>article 刪除 → {@code comment.article-deleted}、{@code reading.article-deleted}、{@code series.article-deleted}、
 *       {@code tag.article-deleted}、{@code version.article-deleted}、{@code search.index.delete} 各收到一則</li>
 *   <li>series 刪除 → {@code article.series-deleted} 收到一則</li>
 *   <li>該 queue 在正式程式碼裡<b>真的有 {@code @RabbitListener} consumer 訂閱</b>（以反射找出對應方法），
 *       且收到的訊息能被轉成該 consumer 的參數型別、欄位正確</li>
 *   <li>AMQP messageId 等於事件的 eventId（有狀態重試的追蹤依據）</li>
 * </ul>
 *
 * <p>為什麼不啟動 consumer：本測試要證明的是「管線的前半段」——exchange／binding／routing key／序列化相容，
 * 用 {@code receive} 讀 queue 才能確定訊息「到達」而不被 consumer 搶走。各 consumer 的業務（對真 Postgres）由各模組的
 * {@code *ConsumerIT} 覆蓋，重試／冪等／DLQ 路徑由 blog-infrastructure 的 {@code RetryPipelineIntegrationTest} 覆蓋。
 * 需要 Docker；命名為 {@code *IT} 故隨 blog-start 的預設 {@code mvn test} 執行。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("刪除連動 queue 的真 RabbitMQ 綁定")
class DeleteCascadeQueueBindingIT {

    /** 等待訊息到達的上限（毫秒） */
    private static final long RECEIVE_TIMEOUT_MS = 10_000L;

    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3-management-alpine");

    /** 每個「文章刪除」queue 與其 consumer 類別 */
    private static final Map<String, Class<?>> ARTICLE_DELETED_QUEUES = Map.of(
            CommentRabbitMqConfig.QUEUE_COMMENT_ARTICLE_DELETED, CommentArticleDeletedConsumer.class,
            ReadingRabbitMqConfig.QUEUE_READING_ARTICLE_DELETED, ReadingArticleDeletedConsumer.class,
            SeriesRabbitMqConfig.QUEUE_SERIES_ARTICLE_DELETED, SeriesArticleDeletedConsumer.class,
            TagRabbitMqConfig.QUEUE_TAG_ARTICLE_DELETED, TagArticleDeletedConsumer.class,
            VersionRabbitMqConfig.QUEUE_VERSION_ARTICLE_DELETED, VersionArticleDeletedConsumer.class,
            SearchRabbitMqConfig.QUEUE_SEARCH_INDEX_DELETE, ArticleSearchListener.class);

    private static AnnotationConfigApplicationContext context;
    private static RabbitTemplate rabbitTemplate;
    private static MessageConverter converter;

    /** 只載入各模組的 RabbitMQ 拓撲設定（exchange／queue／binding），不含任何 consumer */
    @Configuration
    @Import({RabbitMqConfig.class, ArticleRabbitMqConfig.class, CommentRabbitMqConfig.class,
            ReadingRabbitMqConfig.class, SeriesRabbitMqConfig.class, TagRabbitMqConfig.class,
            VersionRabbitMqConfig.class, SearchRabbitMqConfig.class})
    static class Topology {

        @Bean
        ConnectionFactory connectionFactory() {
            CachingConnectionFactory factory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
            factory.setUsername(RABBIT.getAdminUsername());
            factory.setPassword(RABBIT.getAdminPassword());
            return factory;
        }

        @Bean
        RabbitAdmin amqpAdmin(ConnectionFactory connectionFactory) {
            return new RabbitAdmin(connectionFactory);
        }

        @Bean
        SimpleRabbitListenerContainerFactoryConfigurer simpleRabbitListenerContainerFactoryConfigurer() {
            return new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties());
        }
    }

    @BeforeAll
    static void startBroker() {
        RABBIT.start();
        context = new AnnotationConfigApplicationContext(Topology.class);
        context.getBean(RabbitAdmin.class).initialize();
        rabbitTemplate = context.getBean(RabbitTemplate.class);
        converter = context.getBean(MessageConverter.class);
    }

    @AfterAll
    static void stopBroker() {
        if (context != null) {
            context.close();
        }
        RABBIT.stop();
    }

    /**
     * 找出正式程式碼中訂閱指定 queue 的 {@code @RabbitListener} 方法。
     *
     * @param consumerClass consumer 類別
     * @param queue         queue 名稱
     * @return 方法；找不到則 {@code null}
     */
    private static Method listenerMethodFor(Class<?> consumerClass, String queue) {
        return Arrays.stream(consumerClass.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(RabbitListener.class))
                .filter(m -> Arrays.asList(m.getAnnotation(RabbitListener.class).queues()).contains(queue))
                .findFirst().orElse(null);
    }

    /**
     * 從 queue 取一則訊息並以 consumer 的參數型別還原（模擬容器以 listener 參數型別推斷目標類別）。
     */
    private static Object receiveAs(String queue, Class<?> parameterType, Message[] raw) {
        Message message = rabbitTemplate.receive(queue, RECEIVE_TIMEOUT_MS);
        assertThat(message).as("%s 應收到訊息", queue).isNotNull();
        raw[0] = message;
        message.getMessageProperties().setInferredArgumentType(parameterType);
        return converter.fromMessage(message);
    }

    @Test
    @DisplayName("article 刪除事件：comment／reading／series／tag／version／search 的刪除 queue 都收到，且對應 consumer 存在、能還原事件")
    void articleDeleted_reachesEveryCascadeQueue() throws Exception {
        UUID articleUuid = UUID.randomUUID();
        UUID authorUuid = UUID.randomUUID();
        UUID seriesUuid = UUID.randomUUID();
        Article article = new Article();
        article.setUuid(articleUuid);
        article.setAuthorUuid(authorUuid);
        ArticleEventPublisher publisher = new ArticleEventPublisher(
                rabbitTemplate, Mockito.mock(UserFacade.class), Mockito.mock(ArticleMapper.class));
        ARTICLE_DELETED_QUEUES.keySet().forEach(q -> context.getBean(RabbitAdmin.class).purgeQueue(q, false));

        publisher.publishDeleted(article, seriesUuid, List.of(), List.of());

        Map<String, String> messageIds = new HashMap<>();
        for (Map.Entry<String, Class<?>> entry : ARTICLE_DELETED_QUEUES.entrySet()) {
            String queue = entry.getKey();
            Method listener = listenerMethodFor(entry.getValue(), queue);
            assertThat(listener).as("%s 在 %s 應有 @RabbitListener 訂閱", queue, entry.getValue().getSimpleName()).isNotNull();
            Message[] raw = new Message[1];

            Object restored = receiveAs(queue, listener.getParameterTypes()[0], raw);

            assertThat(restored).as("%s 的 consumer 參數型別 %s", queue, listener.getParameterTypes()[0].getSimpleName())
                    .isNotNull();
            assertThat(String.valueOf(restored)).as("%s 還原後的內容含 articleUuid", queue).contains(articleUuid.toString());
            messageIds.put(queue, raw[0].getMessageProperties().getMessageId());
        }
        assertThat(messageIds.values()).as("同一事件扇出到各 queue，messageId 都等於 eventId").hasSize(ARTICLE_DELETED_QUEUES.size())
                .allSatisfy(id -> assertThat(UUID.fromString(id)).isNotNull())
                .containsOnly(messageIds.values().iterator().next());
    }

    @Test
    @DisplayName("article 刪除事件以正式 ArticleDeletedEvent 型別還原：欄位完整，messageId 等於 eventId")
    void articleDeleted_payloadAndMessageId() {
        UUID articleUuid = UUID.randomUUID();
        UUID seriesUuid = UUID.randomUUID();
        Article article = new Article();
        article.setUuid(articleUuid);
        article.setAuthorUuid(UUID.randomUUID());
        ArticleEventPublisher publisher = new ArticleEventPublisher(
                rabbitTemplate, Mockito.mock(UserFacade.class), Mockito.mock(ArticleMapper.class));
        context.getBean(RabbitAdmin.class).purgeQueue(CommentRabbitMqConfig.QUEUE_COMMENT_ARTICLE_DELETED, false);

        publisher.publishDeleted(article, seriesUuid, List.of(), List.of());

        Message[] raw = new Message[1];
        ArticleDeletedEvent event = (ArticleDeletedEvent) receiveAs(
                CommentRabbitMqConfig.QUEUE_COMMENT_ARTICLE_DELETED, ArticleDeletedEvent.class, raw);
        assertThat(event.articleUuid()).isEqualTo(articleUuid);
        assertThat(event.seriesUuid()).isEqualTo(seriesUuid);
        assertThat(raw[0].getMessageProperties().getMessageId()).isEqualTo(event.eventId().toString());
        assertThat(raw[0].getMessageProperties().getReceivedExchange()).isEqualTo(ArticleEventContract.EXCHANGE);
        assertThat(raw[0].getMessageProperties().getReceivedRoutingKey()).isEqualTo(ArticleEventContract.ROUTING_KEY_DELETED);
    }

    @Test
    @DisplayName("series 刪除事件：article.series-deleted 收到，ArticleSeriesDeletedConsumer 存在、能還原事件，messageId 等於 eventId")
    void seriesDeleted_reachesArticleQueue() {
        UUID seriesUuid = UUID.randomUUID();
        SeriesEventPublisher publisher = new SeriesEventPublisher(rabbitTemplate);
        Method listener = listenerMethodFor(ArticleSeriesDeletedConsumer.class, ArticleRabbitMqConfig.QUEUE_SERIES_DELETED);
        assertThat(listener).as("article.series-deleted 應有 @RabbitListener 訂閱").isNotNull();
        context.getBean(RabbitAdmin.class).purgeQueue(ArticleRabbitMqConfig.QUEUE_SERIES_DELETED, false);

        publisher.publishDeleted(seriesUuid);

        Message[] raw = new Message[1];
        SeriesDeletedEvent event = (SeriesDeletedEvent) receiveAs(
                ArticleRabbitMqConfig.QUEUE_SERIES_DELETED, listener.getParameterTypes()[0], raw);
        assertThat(event.seriesUuid()).isEqualTo(seriesUuid);
        assertThat(raw[0].getMessageProperties().getMessageId()).isEqualTo(event.eventId().toString());
        assertThat(raw[0].getMessageProperties().getReceivedExchange()).isEqualTo(SeriesEventContract.EXCHANGE);
    }

    @Test
    @DisplayName("file／user 的事件（FileServiceImpl、AuthService 所發）經正式 RabbitTemplate 轉換後 messageId 等於 eventId")
    void fileAndUserEvents_carryEventIdAsMessageId() {
        UUID imageEventId = UUID.randomUUID();
        UUID registeredEventId = UUID.randomUUID();
        UUID resetEventId = UUID.randomUUID();

        Message image = converter.toMessage(
                new ImageUploadedEvent(imageEventId, UUID.randomUUID(), "files/a.jpg", "image/jpeg"), new MessageProperties());
        Message registered = converter.toMessage(
                new UserRegisteredEvent(registeredEventId, UUID.randomUUID(), "a@b.c", "nick", "token", "123456"),
                new MessageProperties());
        Message reset = converter.toMessage(
                new UserPasswordResetRequestedEvent(resetEventId, UUID.randomUUID(), "a@b.c", "token"), new MessageProperties());

        assertThat(image.getMessageProperties().getMessageId()).isEqualTo(imageEventId.toString());
        assertThat(registered.getMessageProperties().getMessageId()).isEqualTo(registeredEventId.toString());
        assertThat(reset.getMessageProperties().getMessageId()).isEqualTo(resetEventId.toString());
        assertThat(new String(image.getBody(), StandardCharsets.UTF_8)).contains(imageEventId.toString());
    }
}
