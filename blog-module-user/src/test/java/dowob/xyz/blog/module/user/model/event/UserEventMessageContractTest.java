package dowob.xyz.blog.module.user.model.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.infrastructure.config.RabbitMqConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user 模組內部事件（註冊、重設密碼、重複信箱註冊通知）的訊息契約測試。
 *
 * <p>以正式的 {@link RabbitMqConfig#messageConverter()} 驗證：事件只帶 {@code eventId}／{@code userUuid}，
 * 序列化後的 JSON 沒有 {@code userId}，也<b>沒有 email、暱稱、token、驗證碼</b>（ARCH-24：事件不含個資與 token，
 * consumer 寄信時查詢）。上線前沒有舊格式 in-flight 訊息，故移除原本的舊格式相容測試（Yuan 2026-11-05 決定可直接改格式）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("user 內部事件：訊息內容不含個資與 token")
class UserEventMessageContractTest {

    /** 與正式環境相同的訊息轉換器 */
    private static final MessageConverter CONVERTER = new RabbitMqConfig().messageConverter();

    /** 解析序列化結果用 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("UserRegisteredEvent 新格式：序列化含 eventId／userUuid，不含 userId，並能無損還原")
    void registeredEvent_newFormat_hasUuidFieldsAndNoUserId() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userUuid = UUID.randomUUID();
        UserRegisteredEvent event =
                new UserRegisteredEvent(eventId, userUuid);

        Message message = CONVERTER.toMessage(event, new MessageProperties());
        JsonNode json = MAPPER.readTree(message.getBody());

        assertThat(json.has("userId")).as("不得再帶 Long 主鍵").isFalse();
        assertThat(json.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(json.get("userUuid").asText()).isEqualTo(userUuid.toString());
        assertThat(json.fieldNames()).toIterable()
                .as("ARCH-24：事件不含個資與 token，只帶 eventId／userUuid；consumer 寄信時查詢")
                .containsExactlyInAnyOrder("eventId", "userUuid");
        message.getMessageProperties().setInferredArgumentType(UserRegisteredEvent.class);
        assertThat(CONVERTER.fromMessage(message)).isEqualTo(event);
    }

    @Test
    @DisplayName("UserPasswordResetRequestedEvent 新格式：序列化含 eventId／userUuid，不含 userId，並能無損還原")
    void resetEvent_newFormat_hasUuidFieldsAndNoUserId() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userUuid = UUID.randomUUID();
        UserPasswordResetRequestedEvent event =
                new UserPasswordResetRequestedEvent(eventId, userUuid);

        Message message = CONVERTER.toMessage(event, new MessageProperties());
        JsonNode json = MAPPER.readTree(message.getBody());

        assertThat(json.has("userId")).as("不得再帶 Long 主鍵").isFalse();
        assertThat(json.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(json.get("userUuid").asText()).isEqualTo(userUuid.toString());
        assertThat(json.fieldNames()).toIterable()
                .as("ARCH-24：事件不含個資與 token，只帶 eventId／userUuid；consumer 寄信時查詢")
                .containsExactlyInAnyOrder("eventId", "userUuid");
        message.getMessageProperties().setInferredArgumentType(UserPasswordResetRequestedEvent.class);
        assertThat(CONVERTER.fromMessage(message)).isEqualTo(event);
    }

    @Test
    @DisplayName("RegistrationAttemptOnExistingEmailEvent：序列化含 eventId／userUuid，不含 userId 與任何請求端欄位，並能無損還原")
    void registrationAttemptEvent_hasUuidFieldsOnly_andRoundTrips() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userUuid = UUID.randomUUID();
        RegistrationAttemptOnExistingEmailEvent event =
                new RegistrationAttemptOnExistingEmailEvent(eventId, userUuid);

        Message message = CONVERTER.toMessage(event, new MessageProperties());
        JsonNode json = MAPPER.readTree(message.getBody());

        assertThat(json.has("userId")).as("不得再帶 Long 主鍵").isFalse();
        assertThat(json.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(json.get("userUuid").asText()).isEqualTo(userUuid.toString());
        assertThat(json.fieldNames()).toIterable()
                .as("事件只帶原信箱主人的資料，不帶嘗試者提交的 username／nickname／password／ip")
                .containsExactlyInAnyOrder("eventId", "userUuid");
        message.getMessageProperties().setInferredArgumentType(RegistrationAttemptOnExistingEmailEvent.class);
        assertThat(CONVERTER.fromMessage(message)).isEqualTo(event);
    }
}
