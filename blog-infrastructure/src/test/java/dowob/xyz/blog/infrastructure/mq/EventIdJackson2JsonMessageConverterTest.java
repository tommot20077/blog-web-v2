package dowob.xyz.blog.infrastructure.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EventIdJackson2JsonMessageConverter} 的單元測試：發送端 AMQP messageId 的來源。
 *
 * <p>stateful retry 以 messageId 追蹤嘗試次數；事件帶 {@code eventId} 者 messageId 必須等於 eventId，
 * 沒有 eventId 者由轉換器產生隨機 messageId。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("EventIdJackson2JsonMessageConverter：messageId 來源")
class EventIdJackson2JsonMessageConverterTest {

    /** 帶 eventId 的事件（record，與正式事件同型） */
    record WithEventId(UUID eventId, String name) { }

    /** 不帶 eventId 的訊息 */
    record WithoutEventId(String name) { }

    private final EventIdJackson2JsonMessageConverter converter = new EventIdJackson2JsonMessageConverter();

    @Test
    @DisplayName("payload 帶 eventId → messageId 等於 eventId 字串")
    void payloadWithEventId_messageIdEqualsEventId() {
        UUID eventId = UUID.randomUUID();

        Message message = converter.toMessage(new WithEventId(eventId, "x"), new MessageProperties());

        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
    }

    @Test
    @DisplayName("payload 沒有 eventId → messageId 為隨機 UUID（每次不同）")
    void payloadWithoutEventId_messageIdIsRandom() {
        Message first = converter.toMessage(new WithoutEventId("x"), new MessageProperties());
        Message second = converter.toMessage(new WithoutEventId("x"), new MessageProperties());

        assertThat(first.getMessageProperties().getMessageId()).isNotBlank();
        assertThat(UUID.fromString(first.getMessageProperties().getMessageId())).isNotNull();
        assertThat(first.getMessageProperties().getMessageId()).isNotEqualTo(second.getMessageProperties().getMessageId());
    }

    @Test
    @DisplayName("payload 的 eventId 為 null → 退回隨機 messageId")
    void payloadWithNullEventId_messageIdIsRandom() {
        Message message = converter.toMessage(new WithEventId(null, "x"), new MessageProperties());

        assertThat(message.getMessageProperties().getMessageId()).isNotBlank();
    }

    @Test
    @DisplayName("呼叫端已明確設定 messageId → 不覆寫")
    void explicitMessageId_isPreserved() {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("custom-id");

        Message message = converter.toMessage(new WithEventId(UUID.randomUUID(), "x"), properties);

        assertThat(message.getMessageProperties().getMessageId()).isEqualTo("custom-id");
    }

    @Test
    @DisplayName("訊息內容仍是原本的 JSON，可還原為事件（本轉換器不改 payload）")
    void payloadStillRoundTrips() {
        UUID eventId = UUID.randomUUID();
        Message message = converter.toMessage(new WithEventId(eventId, "hello"), new MessageProperties());
        message.getMessageProperties().setInferredArgumentType(WithEventId.class);

        Object restored = converter.fromMessage(message);

        assertThat(restored).isEqualTo(new WithEventId(eventId, "hello"));
    }
}
