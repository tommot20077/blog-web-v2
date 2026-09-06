package dowob.xyz.blog.architecture.fixture;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 守衛 #6 的反向驗證 fixture（類層級標註）——<b>故意違規</b>，不得被當成範例。
 *
 * <p>類層級 {@code @Transactional} 會套用到所有 public 方法，是 BUG-2026-001
 * 當時的實際寫法之一。守衛若只看方法層標註就會對這種形式失明——這正是
 * 本 fixture 要證明守衛沒有漏掉的那一格。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Transactional
public class ClassLevelTransactionalMqFixture {

    /** 模擬的 MQ 樣板 */
    private final RabbitTemplate rabbitTemplate;

    /**
     * 建立 fixture。
     *
     * @param rabbitTemplate MQ 樣板
     */
    public ClassLevelTransactionalMqFixture(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 違規形式二：類層級 {@code @Transactional}，方法本身無標註但仍在交易內發 MQ。
     */
    public void inheritsClassLevelTransactionalAndSendsMq() {
        rabbitTemplate.convertAndSend("ex", "rk", "payload");
    }
}
