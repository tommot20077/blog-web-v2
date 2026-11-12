package dowob.xyz.blog.architecture.fixture.eventcontract.modules.alpha;

import dowob.xyz.blog.architecture.fixture.eventcontract.modules.alpha.dto.ListenerMessage;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;

/**
 * EventContractTest 事件集合的反向驗證 fixture：listener 方法的訊息參數要納入守衛，
 * 但 {@code Channel} 與 {@code @Header} 參數不是訊息本體，不得納入。
 *
 * @author Yuan
 * @version 1.0
 */
public class AlphaListener {

    /**
     * 模擬 consumer 方法。
     *
     * @param message     訊息本體
     * @param channel     AMQP channel（非訊息）
     * @param deliveryTag 投遞標籤（非訊息）
     */
    @RabbitListener(queues = "fixture.queue")
    public void on(ListenerMessage message, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
    }
}
