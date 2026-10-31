package dowob.xyz.blog.infrastructure.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;

/**
 * 發送端的 JSON 轉換器：把事件的 {@code eventId} 設為 AMQP {@code messageId}。
 *
 * <p><b>為什麼</b>：有狀態重試（stateful retry）以 messageId 追蹤「同一則訊息已嘗試幾次」。事件帶
 * {@code eventId} 者 messageId = eventId，使 messageId、冪等 key（{@code processed_events.event_id}）與日誌中的
 * eventId 三者對齊；沒有 {@code eventId} 的訊息（或其值為 null）則由父類產生隨機 messageId。
 * 呼叫端已明確設定 messageId 者不覆寫。</p>
 *
 * <p><b>為什麼放在轉換器而非各 publisher</b>：所有經由 {@code RabbitTemplate}（本專案唯一一個，使用此轉換器）
 * 發出的事件都自動套用，新的 publisher 不可能忘記設定；JSON 以 {@code eventId} 欄位為準，
 * 與各事件 record／類別的型別無關（article、series、file、user 皆同）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class EventIdJackson2JsonMessageConverter extends Jackson2JsonMessageConverter {

    /** JSON 事件中 dedup key 的欄位名稱 */
    private static final String EVENT_ID_FIELD = "eventId";

    /** 僅用於讀取已序列化 JSON 的 eventId 欄位 */
    private static final ObjectMapper READER = new ObjectMapper();

    /**
     * 建立轉換器：沒有 eventId 的訊息由父類產生隨機 messageId。
     */
    public EventIdJackson2JsonMessageConverter() {
        super();
        setCreateMessageIds(true);
    }

    @Override
    protected Message createMessage(Object objectToConvert, MessageProperties messageProperties,
                                    @Nullable Type genericType) throws MessageConversionException {
        Message message = super.createMessage(objectToConvert, messageProperties, genericType);
        MessageProperties properties = message.getMessageProperties();
        if (properties.getMessageId() == null) {
            String eventId = readEventId(message.getBody());
            if (eventId != null) {
                properties.setMessageId(eventId);
            }
        }
        return message;
    }

    /**
     * 從已序列化的 JSON 取出 {@code eventId} 欄位。
     *
     * @param body JSON 位元組
     * @return eventId 字串；沒有此欄位、值為 null 或空白、或內容不是 JSON 物件時為 {@code null}
     */
    @Nullable
    private static String readEventId(byte[] body) {
        try {
            JsonNode node = READER.readTree(body);
            JsonNode eventId = node == null ? null : node.get(EVENT_ID_FIELD);
            if (eventId == null || !eventId.isTextual() || eventId.asText().isBlank()) {
                return null;
            }
            return eventId.asText();
        } catch (IOException e) {
            return null;
        }
    }
}
