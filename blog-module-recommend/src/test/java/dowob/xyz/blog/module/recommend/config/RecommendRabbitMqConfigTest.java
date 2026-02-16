package dowob.xyz.blog.module.recommend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RecommendRabbitMqConfig 單元測試
 *
 * <p>
 * 驗證 Queue 宣告的正確性，以及 {@code manualAckContainerFactory}
 * 確實設定了 MessageConverter 與手動 ACK 模式。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecommendRabbitMqConfig 單元測試")
class RecommendRabbitMqConfigTest {

    /**
     * RabbitMQ 連線工廠 Mock
     */
    @Mock
    private ConnectionFactory connectionFactory;

    /**
     * 訊息轉換器 Mock
     */
    @Mock
    private MessageConverter messageConverter;

    /**
     * 受測物件
     */
    private final RecommendRabbitMqConfig config = new RecommendRabbitMqConfig();

    /**
     * 驗證文章發布 Queue 持久化且帶有 DLQ 參數
     */
    @Test
    @DisplayName("recommendArticlePublishedQueue 應為持久化且包含 DLQ args")
    void recommendArticlePublishedQueue_isDurable_withDlqArgs() {
        Queue queue = config.recommendArticlePublishedQueue();

        assertThat(queue.getName()).isEqualTo(RecommendRabbitMqConfig.QUEUE_RECOMMEND_ARTICLE_PUBLISHED);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq");
    }

    /**
     * 驗證 manualAckContainerFactory 設定 MessageConverter
     *
     * <p>
     * {@link SimpleRabbitListenerContainerFactory} 未暴露公開 getter，
     * 使用反射讀取父類別 {@code messageConverter} 保護欄位來驗證。
     * 若未設定，Consumer 收到 JSON 訊息時會拋出反序列化例外。
     * </p>
     */
    @Test
    @DisplayName("manualAckContainerFactory 應設定 MessageConverter")
    void manualAckContainerFactory_setsMessageConverter() throws Exception {
        SimpleRabbitListenerContainerFactory factory =
                config.manualAckContainerFactory(connectionFactory, messageConverter);

        java.lang.reflect.Field field =
                org.springframework.amqp.rabbit.config.AbstractRabbitListenerContainerFactory.class
                        .getDeclaredField("messageConverter");
        field.setAccessible(true);
        Object actual = field.get(factory);
        assertThat(actual).isEqualTo(messageConverter);
    }

    /**
     * 驗證 manualAckContainerFactory 設定手動 ACK 模式
     *
     * <p>使用反射讀取父類別 {@code acknowledgeMode} 欄位。</p>
     */
    @Test
    @DisplayName("manualAckContainerFactory 應設定 MANUAL AcknowledgeMode")
    void manualAckContainerFactory_setsManualAcknowledgeMode() throws Exception {
        SimpleRabbitListenerContainerFactory factory =
                config.manualAckContainerFactory(connectionFactory, messageConverter);

        java.lang.reflect.Field field =
                org.springframework.amqp.rabbit.config.AbstractRabbitListenerContainerFactory.class
                        .getDeclaredField("acknowledgeMode");
        field.setAccessible(true);
        Object actual = field.get(factory);
        assertThat(actual).isEqualTo(AcknowledgeMode.MANUAL);
    }
}
