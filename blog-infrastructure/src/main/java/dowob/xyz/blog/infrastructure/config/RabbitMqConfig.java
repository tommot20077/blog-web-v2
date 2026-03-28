package dowob.xyz.blog.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.interceptor.StatefulRetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * RabbitMQ 配置類
 *
 * <p>
 * 配置消息轉換器、RabbitTemplate、死信佇列（DLQ）以及帶有
 * 指數退避重試機制的監聽容器工廠。
 * </p>
 *
 * <ul>
 * <li>消息序列化：JSON（跨語言相容）</li>
 * <li>Publisher Confirm：CORRELATED 模式，記錄 nack 與 return</li>
 * <li>DLQ：消費失敗訊息路由至 {@code queue.dead-letter}</li>
 * <li>重試：最多 3 次，指數退避 1s→5s（乘數 5，上限 25s）</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class RabbitMqConfig {

    /** 日誌紀錄器 */
    private static final Logger log = LoggerFactory.getLogger(RabbitMqConfig.class);

    /** DLQ Exchange 名稱 */
    private static final String DLQ_EXCHANGE = "blog.dlq";

    /** DLQ Queue 名稱 */
    private static final String DLQ_QUEUE = "queue.dead-letter";

    /** DLQ Routing Key */
    private static final String DLQ_ROUTING_KEY = "dead-letter";

    /** 最大重試次數（含首次嘗試） */
    private static final int MAX_ATTEMPTS = 3;

    /** 初始退避等待時間（毫秒） */
    private static final long INITIAL_INTERVAL_MS = 1_000L;

    /** 退避乘數 */
    private static final double BACKOFF_MULTIPLIER = 5.0;

    /** 退避最大等待時間（毫秒） */
    private static final long MAX_INTERVAL_MS = 25_000L;

    /**
     * 消息轉換器
     *
     * <p>
     * 使用 Jackson 將 Java 物件轉換為 JSON 格式。
     * </p>
     *
     * @return Jackson2JsonMessageConverter 實例
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 死信佇列 Exchange
     *
     * <p>
     * 消費重試耗盡後，訊息將被路由至此 DirectExchange。
     * </p>
     *
     * @return 名稱為 {@value #DLQ_EXCHANGE} 的持久性 DirectExchange
     */
    @Bean
    public DirectExchange dlqExchange() {
        return new DirectExchange(DLQ_EXCHANGE, true, false);
    }

    /**
     * 死信佇列
     *
     * <p>
     * 儲存所有消費失敗並超過重試上限的訊息，供人工介入處理。
     * </p>
     *
     * @return 名稱為 {@value #DLQ_QUEUE} 的持久性佇列
     */
    @Bean
    public Queue dlqQueue() {
        return new Queue(DLQ_QUEUE, true);
    }

    /**
     * 死信佇列綁定
     *
     * <p>
     * 將 {@value #DLQ_QUEUE} 佇列綁定至 {@value #DLQ_EXCHANGE} Exchange，
     * 使用路由鍵 {@value #DLQ_ROUTING_KEY}。
     * </p>
     *
     * @param queue    死信佇列
     * @param exchange 死信 Exchange
     * @return Binding 實例
     */
    @Bean
    public Binding dlqBinding(@Qualifier("dlqQueue") Queue queue, DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(DLQ_ROUTING_KEY);
    }

    /**
     * RabbitMQ 監聽容器工廠
     *
     * <p>
     * 配置手動確認模式（MANUAL ACK）與有狀態重試攔截器（指數退避），
     * 重試耗盡後透過 {@link RejectAndDontRequeueRecoverer} nack 訊息以觸發 DLQ 路由。
     * </p>
     *
     * @param connectionFactory RabbitMQ 連接工廠
     * @param converter         消息轉換器
     * @return 已配置的 SimpleRabbitListenerContainerFactory
     */
    @Bean(name = "rabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter converter) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setAdviceChain(buildRetryInterceptor());
        return factory;
    }

    /**
     * 自動確認模式的監聽容器工廠
     *
     * <p>
     * 適用於非關鍵業務或無法處理重試的消費者（如日誌記錄、非同步統計）。
     * 設置為 AUTO 模式，消費者無需手動調用 basicAck。
     * </p>
     *
     * @param connectionFactory RabbitMQ 連接工廠
     * @param converter         消息轉換器
     * @return 已配置為 AUTO ACK 的 SimpleRabbitListenerContainerFactory
     */
    @Bean(name = "autoAckContainerFactory")
    public SimpleRabbitListenerContainerFactory autoAckContainerFactory(ConnectionFactory connectionFactory,MessageConverter converter) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        /**
         * 對於 Auto Ack 模式，通常不建議配置複雜的 RetryInterceptor，
         * 因為一旦拋出異常，Spring AMQP 預設行為是無限 Requeue（除非配置了 error handler）。
         * 這裡保持預設行為，若有需要可額外配置 ErrorHandler。
         */
        return factory;
    }

    /**
     * RabbitMQ 操作模板
     *
     * <p>
     * 啟用 Publisher Confirm（CORRELATED）與 Publisher Returns（mandatory=true），
     * 並記錄 nack 與 return 事件以便排查訊息遺失問題。
     * </p>
     *
     * @param connectionFactory RabbitMQ 連接工廠 (由 Spring Boot 自動配置)
     * @return 配置好的 RabbitTemplate
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        if (connectionFactory instanceof CachingConnectionFactory cachingCf) {
            cachingCf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            cachingCf.setPublisherReturns(true);
        }

        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        template.setMandatory(true);

        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.warn("Publisher Confirm: 訊息未被 Broker 確認。correlationData={}, cause={}",
                        correlationData, cause);
            }
        });

        template.setReturnsCallback(returned -> log.error(
                "Publisher Returns: 訊息無法路由。exchange={}, routingKey={}, replyCode={}, replyText={}",
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyCode(),
                returned.getReplyText()
        ));

        return template;
    }

    /**
     * 建立有狀態重試攔截器
     *
     * <p>
     * 重試策略：最多 {@value #MAX_ATTEMPTS} 次；
     * 退避策略：初始 1s，乘數 5，上限 25s。
     * 重試耗盡後使用 {@link RejectAndDontRequeueRecoverer} 拒絕訊息（nack），
     * 由 Broker 路由至 DLQ。
     * </p>
     *
     * @return 已配置的 StatefulRetryOperationsInterceptor
     */
    private StatefulRetryOperationsInterceptor buildRetryInterceptor() {
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(INITIAL_INTERVAL_MS);
        backOff.setMultiplier(BACKOFF_MULTIPLIER);
        backOff.setMaxInterval(MAX_INTERVAL_MS);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(MAX_ATTEMPTS));
        retryTemplate.setBackOffPolicy(backOff);

        return org.springframework.amqp.rabbit.config.RetryInterceptorBuilder
                .stateful()
                .retryOperations(retryTemplate)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }
}
