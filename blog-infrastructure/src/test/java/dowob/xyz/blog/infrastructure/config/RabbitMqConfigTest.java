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
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.test.util.ReflectionTestUtils;
import dowob.xyz.blog.infrastructure.mq.DeadLetterCountingErrorHandler;
import dowob.xyz.blog.infrastructure.mq.EventIdJackson2JsonMessageConverter;
import dowob.xyz.blog.infrastructure.mq.MessageIdAwareRetryInterceptor;

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
     * 驗證容器工廠的 acknowledge mode 來自 {@code spring.rabbitmq.listener.simple.acknowledge-mode}（經 configurer），
     * 而不是程式碼寫死
     *
     * <p>
     * 透過建立 container 並檢查其 acknowledge mode 來驗證，
     * 因為工廠本身不暴露 getAcknowledgeMode()。
     * </p>
     */
    @Test
    @DisplayName("rabbitListenerContainerFactory 的 acknowledge mode 取自 RabbitProperties（經 configurer）")
    void rabbitListenerContainerFactory_acknowledgeModeComesFromProperties() {
        RabbitProperties properties = new RabbitProperties();
        properties.getListener().getSimple().setAcknowledgeMode(AcknowledgeMode.MANUAL);
        MessageConverter converter = config.messageConverter();
        SimpleRabbitListenerContainerFactory factory = config.rabbitListenerContainerFactory(
                new SimpleRabbitListenerContainerFactoryConfigurer(properties), connectionFactory, converter);

        assertThat(factory).isNotNull();

        SimpleMessageListenerContainer container =
                (SimpleMessageListenerContainer) factory.createListenerContainer();
        assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
    }

    /**
     * 驗證容器工廠真的掛上重試攔截器與計數用的 error handler（ARCH-02：過去的重試攔截器是死碼）
     */
    @Test
    @DisplayName("rabbitListenerContainerFactory 的容器掛有 MessageIdAwareRetryInterceptor 與 DeadLetterCountingErrorHandler")
    void rabbitListenerContainerFactory_hasRetryInterceptorAndDeadLetterErrorHandler() {
        SimpleRabbitListenerContainerFactory factory = config.rabbitListenerContainerFactory(
                new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties()),
                connectionFactory, config.messageConverter());

        SimpleMessageListenerContainer container = (SimpleMessageListenerContainer) factory.createListenerContainer();

        Object[] adviceChain = (Object[]) ReflectionTestUtils.getField(container, "adviceChain");
        assertThat(adviceChain).hasSize(1);
        assertThat(adviceChain[0]).isInstanceOf(MessageIdAwareRetryInterceptor.class);
        assertThat(ReflectionTestUtils.getField(container, "errorHandler"))
                .isInstanceOf(DeadLetterCountingErrorHandler.class);
    }

    /**
     * 驗證轉換器會以事件的 eventId 作為 AMQP messageId（stateful retry 的追蹤依據）
     */
    @Test
    @DisplayName("messageConverter 應為 EventIdJackson2JsonMessageConverter（messageId = eventId）")
    void messageConverter_assignsEventIdAsMessageId() {
        assertThat(config.messageConverter()).isInstanceOf(EventIdJackson2JsonMessageConverter.class);
    }

    /**
     * 驗證重試政策：最多 3 次；訊息轉換等「重試也不會好」的例外不重試，其餘例外照常重試
     */
    @Test
    @DisplayName("重試政策：最多 3 次；轉換失敗等不可重試，一般例外可重試（cause 鏈遍歷）")
    void retryPolicy_classifiesNonRetryableExceptions() {
        SimpleRetryPolicy policy = RabbitMqConfig.buildRetryPolicy();

        assertThat(policy.getMaxAttempts()).isEqualTo(3);
        assertThat(retryableAfter(policy, wrapped(new IllegalStateException("boom")))).isTrue();
        assertThat(retryableAfter(policy, wrapped(new MessageConversionException("bad json")))).isFalse();
        assertThat(retryableAfter(policy,
                wrapped(new org.springframework.messaging.converter.MessageConversionException("bad payload")))).isFalse();
        assertThat(retryableAfter(policy, wrapped(new ClassCastException("cce")))).isFalse();
        assertThat(retryableAfter(policy, wrapped(new NoSuchMethodException("nsme")))).isFalse();
    }

    /**
     * 模擬容器拋出的外層例外：ListenerExecutionFailedException 包原始例外
     */
    private static Throwable wrapped(Throwable cause) {
        return new ListenerExecutionFailedException("Listener threw exception", cause, new Message(new byte[0]));
    }

    /**
     * 登記一次失敗後，政策是否仍允許重試
     */
    private static boolean retryableAfter(SimpleRetryPolicy policy, Throwable failure) {
        RetryContext context = policy.open(null);
        policy.registerThrowable(context, failure);
        return policy.canRetry(context);
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
