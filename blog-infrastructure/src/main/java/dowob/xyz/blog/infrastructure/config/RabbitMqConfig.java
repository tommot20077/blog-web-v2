package dowob.xyz.blog.infrastructure.config;

import dowob.xyz.blog.infrastructure.mq.BoundedRetryContextCache;
import dowob.xyz.blog.infrastructure.mq.DeadLetterCountingErrorHandler;
import dowob.xyz.blog.infrastructure.mq.EventIdJackson2JsonMessageConverter;
import dowob.xyz.blog.infrastructure.mq.MessageIdAwareRetryInterceptor;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.interceptor.StatefulRetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.messaging.handler.invocation.MethodArgumentResolutionException;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 配置類
 *
 * <p>
 * 配置消息轉換器、RabbitTemplate、死信佇列（DLQ）以及帶有
 * 指數退避重試機制的監聽容器工廠。以下描述與實際行為一致，並由
 * {@code RetryPipelineIntegrationTest}（真 RabbitMQ＋Postgres）逐條驗證。
 * </p>
 *
 * <ul>
 * <li>消息序列化：JSON（跨語言相容）；事件的 {@code eventId} 同時作為 AMQP messageId
 *     （{@link EventIdJackson2JsonMessageConverter}），無 eventId 者為隨機 messageId</li>
 * <li>Publisher Confirm：CORRELATED 模式，記錄 nack 與 return</li>
 * <li>監聽容器：{@code spring.rabbitmq.listener.simple.*}（concurrency、prefetch、ack 模式）經
 *     {@code SimpleRabbitListenerContainerFactoryConfigurer} 套用；ack 模式為 {@code AUTO}——
 *     listener 正常返回即 ack，拋例外即 nack(requeue)，交給重試攔截器</li>
 * <li>重試：有狀態重試（以 messageId＋queue 追蹤），最多 3 次（含首次），指數退避 1s→5s（乘數 5，上限 25s；
 *     3 次嘗試實際只會等待 1s 與 5s）。退避睡眠發生在 consumer 執行緒上，故必須搭配 concurrency ≥ 2 與
 *     prefetch = 1（見 application.yaml），單一毒訊息才不會阻塞同 queue 的其他訊息</li>
 * <li>不可重試的例外（訊息轉換失敗等「重試也不會好」者）不重試，直接進 DLQ；維運把 DLQ 訊息重投回原 queue 後
 *     若仍致命 → 回到 DLQ，不丟棄（不採用 Spring 預設的「帶 x-death 的致命訊息直接 ack 丟棄」）</li>
 * <li>沒有 messageId 的訊息（部署前殘留）無法計次：只嘗試一次，失敗即進 DLQ</li>
 * <li>DLQ：重試耗盡的訊息以 requeue=false 拒絕，經 DLX 路由至 {@code queue.dead-letter}，
 *     並計 {@code mq.consumer.dead_lettered}</li>
 * </ul>
 *
 * @author Yuan
 * @version 2.0
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

    /** 重試 context 快取容量（超過淘汰最舊的，見 {@link BoundedRetryContextCache}） */
    private static final int RETRY_CONTEXT_CACHE_CAPACITY = 10_000;

    /**
     * 消息轉換器
     *
     * <p>
     * 使用 Jackson 將 Java 物件轉換為 JSON 格式，並把事件的 {@code eventId} 設為 AMQP messageId
     * （有狀態重試以 messageId 追蹤嘗試次數）。
     * </p>
     *
     * @return EventIdJackson2JsonMessageConverter 實例
     */
    @Bean
    public MessageConverter messageConverter() {
        return new EventIdJackson2JsonMessageConverter();
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
     * <b>先</b>交給 Spring Boot 的 {@link SimpleRabbitListenerContainerFactoryConfigurer} 套用
     * {@code spring.rabbitmq.listener.simple.*}（concurrency、max-concurrency、prefetch、acknowledge-mode 等），
     * <b>再</b>疊加本專案的設定：訊息轉換器、有狀態重試攔截器，以及計數 {@code mq.consumer.dead_lettered}
     * 的 {@link DeadLetterCountingErrorHandler}。過去此工廠從零手建、未經 configurer，
     * yaml 的 listener 設定一律靜默失效（PERF-15）。
     * </p>
     *
     * <p>
     * ack 模式由 yaml 決定，正式設定為 {@code auto}：listener 拋例外時容器才會 nack，重試攔截器才有機會介入
     * （{@code MANUAL} 模式下容器不會對一般例外 nack，訊息會停在 unacked，重試形同死碼——
     * 見 {@code AckModeRetryProofIntegrationTest}）。{@code defaultRequeueRejected} 維持容器預設的 true，
     * 重試耗盡才由 recoverer 明確以 requeue=false 拒絕。
     * </p>
     *
     * @param configurer        Spring Boot 提供的容器工廠設定器（讀取 {@code spring.rabbitmq.listener.simple.*}）
     * @param connectionFactory RabbitMQ 連接工廠
     * @param converter         消息轉換器
     * @return 已配置的 SimpleRabbitListenerContainerFactory
     */
    @Bean(name = "rabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter converter) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(converter);
        factory.setAdviceChain(buildRetryInterceptor());
        factory.setErrorHandler(new DeadLetterCountingErrorHandler());
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
     * 建立重試政策：最多 {@value #MAX_ATTEMPTS} 次（含首次），並把「重試也不會好」的例外標為不可重試。
     *
     * <p>不可重試的例外與 Spring 容器預設的致命例外清單
     * （{@code ConditionalRejectingErrorHandler.DefaultExceptionStrategy}）完全一致，不另外擴大：</p>
     * <ul>
     *   <li>{@link MessageConversionException}（amqp）與
     *       {@link org.springframework.messaging.converter.MessageConversionException}（messaging）：
     *       訊息內容無法轉成 listener 參數型別</li>
     *   <li>{@link MethodArgumentResolutionException}：listener 參數無法解析</li>
     *   <li>{@link NoSuchMethodException}、{@link ClassCastException}：listener 簽章與訊息不相容</li>
     * </ul>
     * <p>例外分類沿 cause 鏈遍歷（容器拋出的是包著原始例外的 {@code ListenerExecutionFailedException}）；
     * 其餘例外一律可重試。</p>
     *
     * @return 已配置的 SimpleRetryPolicy
     */
    static SimpleRetryPolicy buildRetryPolicy() {
        Map<Class<? extends Throwable>, Boolean> classified = new HashMap<>();
        classified.put(MessageConversionException.class, false);
        classified.put(org.springframework.messaging.converter.MessageConversionException.class, false);
        classified.put(MethodArgumentResolutionException.class, false);
        classified.put(NoSuchMethodException.class, false);
        classified.put(ClassCastException.class, false);
        return new SimpleRetryPolicy(MAX_ATTEMPTS, classified, true, true);
    }

    /**
     * 建立有狀態重試攔截器
     *
     * <p>
     * 重試策略：見 {@link #buildRetryPolicy()}；
     * 退避策略：初始 1s，乘數 5，上限 25s（3 次嘗試實際等待 1s、5s）。
     * 重試耗盡後使用 {@link RejectAndDontRequeueRecoverer} 拒絕訊息（requeue=false），
     * 由 Broker 路由至 DLQ。
     * </p>
     *
     * <p>
     * 重試以「來源 queue ＋ messageId」為 key：同一事件（同一 messageId）會扇出到多條 queue
     * （例如文章刪除事件送往 comment／reading／series／tag／version），各 queue 的 consumer 各自計次，
     * 不會互相消耗彼此的重試次數。外層的 {@link MessageIdAwareRetryInterceptor} 讓沒有 messageId 的訊息
     * 不進入此攔截器。
     * </p>
     *
     * @return 已配置的重試攔截器（含無 messageId 的守門）
     */
    private MethodInterceptor buildRetryInterceptor() {
        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(INITIAL_INTERVAL_MS);
        backOff.setMultiplier(BACKOFF_MULTIPLIER);
        backOff.setMaxInterval(MAX_INTERVAL_MS);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(buildRetryPolicy());
        retryTemplate.setBackOffPolicy(backOff);
        retryTemplate.setRetryContextCache(new BoundedRetryContextCache(RETRY_CONTEXT_CACHE_CAPACITY));

        StatefulRetryOperationsInterceptor stateful = RetryInterceptorBuilder
                .stateful()
                .retryOperations(retryTemplate)
                .messageKeyGenerator(message -> message.getMessageProperties().getConsumerQueue()
                        + "|" + message.getMessageProperties().getMessageId())
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
        return new MessageIdAwareRetryInterceptor(stateful);
    }
}
