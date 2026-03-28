package dowob.xyz.blog.infrastructure.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * RabbitMqConfig 單元測試
 *
 * <p>
 * 驗證 RabbitMQ 死信佇列（DLQ）、重試機制與 Publisher Confirm 等 Bean 的配置正確性。
 * 採用純單元測試，不啟動 Spring Context。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
class RabbitMqConfigTest {

    /** 受測配置類別 */
    private RabbitMqConfig config;

    /** 模擬的 RabbitMQ 連接工廠 */
    @Mock
    private ConnectionFactory connectionFactory;

    /**
     * 每個測試前初始化受測物件
     */
    @BeforeEach
    void setUp() {
        config = new RabbitMqConfig();
    }

    /**
     * 驗證 DLQ Exchange 為 DirectExchange 且名稱正確
     */
    @Test
    @DisplayName("dlqExchange 應回傳名稱為 blog.dlq 的 DirectExchange")
    void dlqExchange_isDirectExchange_withCorrectName() {
        DirectExchange exchange = config.dlqExchange();

        assertThat(exchange).isNotNull();
        assertThat(exchange.getName()).isEqualTo("blog.dlq");
        assertThat(exchange.isDurable()).isTrue();
    }

    /**
     * 驗證 DLQ Queue 為持久佇列且名稱正確
     */
    @Test
    @DisplayName("dlqQueue 應回傳名稱為 queue.dead-letter 的持久佇列")
    void dlqQueue_isDurable_withCorrectName() {
        Queue queue = config.dlqQueue();

        assertThat(queue).isNotNull();
        assertThat(queue.getName()).isEqualTo("queue.dead-letter");
        assertThat(queue.isDurable()).isTrue();
    }

    /**
     * 驗證 DLQ Binding 正確連結佇列、Exchange 與 Routing Key
     */
    @Test
    @DisplayName("dlqBinding 應將 queue.dead-letter 綁定到 blog.dlq 並使用 dead-letter 路由鍵")
    void dlqBinding_connectsQueueToExchange() {
        Queue queue = config.dlqQueue();
        DirectExchange exchange = config.dlqExchange();

        Binding binding = config.dlqBinding(queue, exchange);

        assertThat(binding).isNotNull();
        assertThat(binding.getDestination()).isEqualTo("queue.dead-letter");
        assertThat(binding.getExchange()).isEqualTo("blog.dlq");
        assertThat(binding.getRoutingKey()).isEqualTo("dead-letter");
    }

    /**
     * 驗證 SimpleRabbitListenerContainerFactory 配置為手動確認模式
     *
     * <p>
     * 透過建立 container 並檢查其 acknowledge mode 來驗證，
     * 因為工廠本身不暴露 getAcknowledgeMode()。
     * </p>
     */
    @Test
    @DisplayName("rabbitListenerContainerFactory 應配置為 MANUAL acknowledge mode")
    void rabbitListenerContainerFactory_hasManualAck() {
        MessageConverter converter = config.messageConverter();
        SimpleRabbitListenerContainerFactory factory =
                config.rabbitListenerContainerFactory(connectionFactory, converter);

        assertThat(factory).isNotNull();

        SimpleMessageListenerContainer container =
                (SimpleMessageListenerContainer) factory.createListenerContainer();
        assertThat(container.getAcknowledgeMode())
                .isEqualTo(org.springframework.amqp.core.AcknowledgeMode.MANUAL);
    }

    /**
     * 驗證 RabbitTemplate 設定了 mandatory 旗標
     *
     * <p>
     * Spring AMQP 3.x 不提供 {@code isMandatory()} getter，
     * 改用 {@code isMandatoryFor(Message)} 驗證：當 mandatory=true 時，
     * 任意訊息均應回傳 true。
     * </p>
     */
    @Test
    @DisplayName("rabbitTemplate 應設定 mandatory=true 以啟用 Publisher Returns")
    void rabbitTemplate_hasMandatoryFlag() {
        RabbitTemplate template = config.rabbitTemplate(connectionFactory);

        assertThat(template).isNotNull();
        assertThat(template.isMandatoryFor(new Message(new byte[0]))).isTrue();
    }

    /**
     * 驗證 messageConverter 回傳 Jackson2JsonMessageConverter
     */
    @Test
    @DisplayName("messageConverter 應回傳 Jackson2JsonMessageConverter 實例")
    void messageConverter_returnsJacksonConverter() {
        MessageConverter converter = config.messageConverter();

        assertThat(converter).isNotNull();
        assertThat(converter).isInstanceOf(Jackson2JsonMessageConverter.class);
    }

    /**
     * 驗證 autoAckContainerFactory 配置為 AUTO acknowledge mode
     */
    @Test
    @DisplayName("autoAckContainerFactory 應配置為 AUTO acknowledge mode")
    void autoAckContainerFactory_hasAutoAck() {
        MessageConverter converter = config.messageConverter();
        SimpleRabbitListenerContainerFactory factory =
                config.autoAckContainerFactory(connectionFactory, converter);

        assertThat(factory).isNotNull();

        SimpleMessageListenerContainer container =
                (SimpleMessageListenerContainer) factory.createListenerContainer();
        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.AUTO);
    }

    /**
     * 驗證 rabbitTemplate 使用 CachingConnectionFactory 時，配置 Publisher Confirm 與 Returns
     */
    @Test
    @DisplayName("rabbitTemplate 使用 CachingConnectionFactory 時應配置 CORRELATED confirm 與 publisherReturns")
    void rabbitTemplate_withCachingConnectionFactory_configuresPublisherConfirm() {
        CachingConnectionFactory cachingCf = mock(CachingConnectionFactory.class);

        RabbitTemplate template = config.rabbitTemplate(cachingCf);

        assertThat(template).isNotNull();
        verify(cachingCf).setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        verify(cachingCf).setPublisherReturns(true);
    }

    /**
     * 驗證 rabbitTemplate 的 ConfirmCallback 處理 nack（ack=false）不拋出異常
     */
    @Test
    @DisplayName("rabbitTemplate 的 ConfirmCallback 收到 nack 時不應拋出異常")
    void rabbitTemplate_confirmCallback_handlesNackWithoutException() {
        RabbitTemplate template = config.rabbitTemplate(connectionFactory);

        RabbitTemplate.ConfirmCallback confirmCallback =
                (RabbitTemplate.ConfirmCallback) ReflectionTestUtils.getField(template, "confirmCallback");
        assertThat(confirmCallback).isNotNull();
        confirmCallback.confirm(null, false, "broker rejected");
    }

    /**
     * 驗證 rabbitTemplate 的 ConfirmCallback 處理 ack（ack=true）不拋出異常
     */
    @Test
    @DisplayName("rabbitTemplate 的 ConfirmCallback 收到 ack 時不應拋出異常")
    void rabbitTemplate_confirmCallback_handlesAckWithoutException() {
        RabbitTemplate template = config.rabbitTemplate(connectionFactory);

        RabbitTemplate.ConfirmCallback confirmCallback =
                (RabbitTemplate.ConfirmCallback) ReflectionTestUtils.getField(template, "confirmCallback");
        assertThat(confirmCallback).isNotNull();
        confirmCallback.confirm(null, true, null);
    }

    /**
     * 驗證 rabbitTemplate 的 ReturnsCallback 處理 returned message 不拋出異常
     */
    @Test
    @DisplayName("rabbitTemplate 的 ReturnsCallback 處理 returned message 不應拋出異常")
    void rabbitTemplate_returnsCallback_handlesReturnedMessageWithoutException() {
        RabbitTemplate template = config.rabbitTemplate(connectionFactory);

        RabbitTemplate.ReturnsCallback returnsCallback =
                (RabbitTemplate.ReturnsCallback) ReflectionTestUtils.getField(template, "returnsCallback");
        assertThat(returnsCallback).isNotNull();
        ReturnedMessage returned = new ReturnedMessage(
                new Message(new byte[0]), 312, "NO_ROUTE", "test.exchange", "test.key");
        returnsCallback.returnedMessage(returned);
    }
}
