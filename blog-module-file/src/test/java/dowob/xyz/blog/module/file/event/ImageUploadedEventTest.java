package dowob.xyz.blog.module.file.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImageUploadedEvent} 訊息反序列化相容性測試
 *
 * <p>使用與正式環境相同的 {@link Jackson2JsonMessageConverter}（見 {@code RabbitMqConfig.messageConverter()}），
 * 並以 {@code inferredArgumentType} 模擬 {@code @RabbitListener} 依方法參數型別推斷目標型別的路徑。
 * 補上 {@code eventId} 的瞬間，佇列裡可能還有舊格式（無 eventId）的 in-flight 訊息，必須仍能反序列化
 * （eventId 為 null），consumer 才能照常處理。</p>
 *
 * @author Yuan
 * @version 1.1
 */
@DisplayName("ImageUploadedEvent 訊息反序列化相容性")
class ImageUploadedEventTest {

    private final Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();

    /** 建立模擬 listener 收到的訊息（JSON 位元組＋推斷的參數型別） */
    private Message listenerMessage(byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setInferredArgumentType(ImageUploadedEvent.class);
        return new Message(body, properties);
    }

    /** 驗證舊格式 JSON（無 eventId）可反序列化，eventId 為 null、其餘欄位完整 */
    @Test
    @DisplayName("fromMessage_legacyJsonWithoutEventId_yieldsNullEventId")
    void fromMessage_legacyJsonWithoutEventId_yieldsNullEventId() {
        UUID fileId = UUID.randomUUID();
        String legacyJson = "{\"fileId\":\"" + fileId + "\",\"storagePath\":\"a/b.jpg\",\"contentType\":\"image/jpeg\"}";

        Object converted = converter.fromMessage(listenerMessage(legacyJson.getBytes(StandardCharsets.UTF_8)));

        assertThat(converted).isInstanceOf(ImageUploadedEvent.class);
        ImageUploadedEvent event = (ImageUploadedEvent) converted;
        assertThat(event.eventId()).isNull();
        assertThat(event.fileId()).isEqualTo(fileId);
        assertThat(event.storagePath()).isEqualTo("a/b.jpg");
        assertThat(event.contentType()).isEqualTo("image/jpeg");
    }

    /** 驗證新格式經 toMessage／fromMessage 往返後 eventId 保持不變 */
    @Test
    @DisplayName("roundTrip_withEventId_preservesEventId")
    void roundTrip_withEventId_preservesEventId() {
        ImageUploadedEvent original = new ImageUploadedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "a/b.jpg", "image/jpeg");

        Message sent = converter.toMessage(original, new MessageProperties());
        Object converted = converter.fromMessage(listenerMessage(sent.getBody()));

        assertThat(converted).isEqualTo(original);
    }
}
