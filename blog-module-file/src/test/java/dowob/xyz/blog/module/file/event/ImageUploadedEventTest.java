package dowob.xyz.blog.module.file.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImageUploadedEvent} JSON 相容性測試
 *
 * <p>RabbitMQ 以 Jackson 序列化事件。補上 {@code eventId} 的瞬間，佇列裡可能還有舊格式（無 eventId）的
 * in-flight 訊息，必須仍能反序列化（eventId 為 null），consumer 才能照常處理。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ImageUploadedEvent JSON 相容性")
class ImageUploadedEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 驗證舊格式 JSON（無 eventId）可反序列化，eventId 為 null、其餘欄位完整 */
    @Test
    @DisplayName("deserialize_legacyJsonWithoutEventId_yieldsNullEventId")
    void deserialize_legacyJsonWithoutEventId_yieldsNullEventId() throws Exception {
        UUID fileId = UUID.randomUUID();
        String legacyJson = "{\"fileId\":\"" + fileId + "\",\"storagePath\":\"a/b.jpg\",\"contentType\":\"image/jpeg\"}";

        ImageUploadedEvent event = objectMapper.readValue(legacyJson, ImageUploadedEvent.class);

        assertThat(event.eventId()).isNull();
        assertThat(event.fileId()).isEqualTo(fileId);
        assertThat(event.storagePath()).isEqualTo("a/b.jpg");
        assertThat(event.contentType()).isEqualTo("image/jpeg");
    }

    /** 驗證新格式往返序列化後 eventId 保持不變 */
    @Test
    @DisplayName("roundTrip_withEventId_preservesEventId")
    void roundTrip_withEventId_preservesEventId() throws Exception {
        ImageUploadedEvent original = new ImageUploadedEvent(
                UUID.randomUUID(), UUID.randomUUID(), "a/b.jpg", "image/jpeg");

        ImageUploadedEvent copy = objectMapper.readValue(
                objectMapper.writeValueAsString(original), ImageUploadedEvent.class);

        assertThat(copy).isEqualTo(original);
    }
}
