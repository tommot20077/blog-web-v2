package dowob.xyz.blog.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.infrastructure.config.RabbitMqConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.util.TreeSet;

/**
 * 事件序列化測試的共用工具：以正式的 {@link RabbitMqConfig#messageConverter()} 序列化／反序列化，
 * 確保測到的是實際上線的 JSON 格式。
 *
 * @author Yuan
 * @version 1.0
 */
final class EventJsonSupport {

    /** 與正式環境相同的訊息轉換器 */
    private static final MessageConverter CONVERTER = new RabbitMqConfig().messageConverter();

    /** 解析 JSON 用 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EventJsonSupport() {
    }

    /**
     * 以正式 MessageConverter 序列化事件並解析為 JSON 樹。
     *
     * @param event 事件物件
     * @return 訊息 body 的 JSON 樹
     */
    static JsonNode toJson(Object event) {
        Message message = CONVERTER.toMessage(event, new MessageProperties());
        try {
            return MAPPER.readTree(message.getBody());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 取得 JSON 物件的欄位名稱集合（排序後，方便比對與顯示）。
     *
     * @param json JSON 物件
     * @return 排序後的欄位名稱
     */
    static TreeSet<String> fieldNames(JsonNode json) {
        TreeSet<String> names = new TreeSet<>();
        json.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * 模擬 consumer 端收到訊息：把 body 以指定的 {@code __TypeId__} header 與 listener 參數型別
     * （inferredArgumentType）交給正式 MessageConverter 反序列化。
     *
     * @param body         訊息 body（JSON）
     * @param typeIdHeader 發送端寫入的 {@code __TypeId__} header 值；null 表示不帶
     * @param listenerType listener 方法參數型別（Spring AMQP 會以此推斷目標型別）
     * @return 反序列化後的物件
     */
    static Object receive(byte[] body, String typeIdHeader, Type listenerType) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        if (typeIdHeader != null) {
            props.setHeader("__TypeId__", typeIdHeader);
        }
        props.setInferredArgumentType(listenerType);
        return CONVERTER.fromMessage(new Message(body, props));
    }

    /**
     * 取得事件序列化後的 body bytes。
     *
     * @param event 事件物件
     * @return JSON bytes
     */
    static byte[] toBody(Object event) {
        return CONVERTER.toMessage(event, new MessageProperties()).getBody();
    }
}
