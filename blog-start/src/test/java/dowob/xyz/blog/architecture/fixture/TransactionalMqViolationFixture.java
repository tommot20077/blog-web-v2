package dowob.xyz.blog.architecture.fixture;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 守衛 #6 的反向驗證 fixture——<b>故意違規</b>，不得被當成範例。
 *
 * <p>存在理由：守衛「掃不到任何違規」有兩種可能——真的沒有違規，或守衛壞了。
 * 只斷言 violations 為空無法區分兩者（見 {@code 29a1000} 堵住守衛 #5 兩處靜默假陰性
 * 的經過）。本 fixture 提供已知違規，讓反向測試證明守衛確實抓得到。</p>
 *
 * <p>本類位於測試 source 且不被 Spring 掃描，正向守衛只匯入
 * {@code dowob.xyz.blog.module}，不會掃到這裡。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class TransactionalMqViolationFixture {

    /** 模擬的 MQ 樣板 */
    private final RabbitTemplate rabbitTemplate;

    /**
     * 建立 fixture。
     *
     * @param rabbitTemplate MQ 樣板
     */
    public TransactionalMqViolationFixture(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 違規形式一：方法層 {@code @Transactional} 內直接發 MQ。
     */
    @Transactional
    public void methodLevelTransactionalSendsMq() {
        rabbitTemplate.convertAndSend("ex", "rk", "payload");
    }

    /**
     * 合規對照：無交易標註時直接發 MQ 不算違規。
     */
    public void nonTransactionalSendsMq() {
        rabbitTemplate.convertAndSend("ex", "rk", "payload");
    }

    /**
     * 合規對照：有交易標註但不發 MQ。
     */
    @Transactional
    public void transactionalWithoutMq() {
        String ignored = rabbitTemplate.toString();
    }
}
