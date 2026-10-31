package dowob.xyz.blog.module.user.model.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import dowob.xyz.blog.infrastructure.config.RabbitMqConfig;
import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import dowob.xyz.blog.module.user.consumer.EmailVerificationConsumer;
import dowob.xyz.blog.module.user.consumer.PasswordResetConsumer;
import dowob.xyz.blog.module.user.service.UserMailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * M8：user 模組內部事件（註冊、重設密碼）的訊息契約測試。
 *
 * <p>以正式的 {@link RabbitMqConfig#messageConverter()} 驗證兩件事：
 * ① 新格式只帶 {@code userUuid}／{@code eventId}，序列化後的 JSON 沒有 {@code userId}；
 * ② 升級當下佇列裡仍可能有舊格式的 in-flight 訊息（含 Long 的 {@code userId}、沒有 {@code eventId}），
 * 必須仍可反序列化，且 consumer 照常寄信——否則使用者的驗證信／重設密碼信會被視為毒訊息而遺失。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("user 內部事件：新格式內容與舊格式 in-flight 訊息相容性")
class UserEventMessageContractTest {

    /** 與正式環境相同的訊息轉換器 */
    private static final MessageConverter CONVERTER = new RabbitMqConfig().messageConverter();

    /** 解析序列化結果用 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 舊格式的註冊事件 JSON（M8 之前：Long userId、無 eventId／userUuid） */
    private static final String LEGACY_REGISTERED = "{\"userId\":7,\"email\":\"old@example.com\","
            + "\"nickname\":\"Old\",\"verificationToken\":\"tok-old\",\"verificationCode\":\"654321\"}";

    /** 舊格式的密碼重設申請事件 JSON */
    private static final String LEGACY_RESET = "{\"userId\":7,\"email\":\"old@example.com\","
            + "\"resetToken\":\"reset-old\"}";

    /**
     * 組出帶推斷型別的 AMQP 訊息，模擬 listener 容器反序列化時的行為。
     *
     * @param json 訊息內容
     * @param type 推斷的目標型別
     * @return AMQP 訊息
     */
    private static Message messageOf(String json, Class<?> type) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setInferredArgumentType(type);
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
    }

    @Test
    @DisplayName("UserRegisteredEvent 新格式：序列化含 eventId／userUuid，不含 userId，並能無損還原")
    void registeredEvent_newFormat_hasUuidFieldsAndNoUserId() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userUuid = UUID.randomUUID();
        UserRegisteredEvent event =
                new UserRegisteredEvent(eventId, userUuid, "a@example.com", "Nick", "tok", "123456");

        Message message = CONVERTER.toMessage(event, new MessageProperties());
        JsonNode json = MAPPER.readTree(message.getBody());

        assertThat(json.has("userId")).as("不得再帶 Long 主鍵").isFalse();
        assertThat(json.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(json.get("userUuid").asText()).isEqualTo(userUuid.toString());
        message.getMessageProperties().setInferredArgumentType(UserRegisteredEvent.class);
        assertThat(CONVERTER.fromMessage(message)).isEqualTo(event);
    }

    @Test
    @DisplayName("UserPasswordResetRequestedEvent 新格式：序列化含 eventId／userUuid，不含 userId，並能無損還原")
    void resetEvent_newFormat_hasUuidFieldsAndNoUserId() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userUuid = UUID.randomUUID();
        UserPasswordResetRequestedEvent event =
                new UserPasswordResetRequestedEvent(eventId, userUuid, "a@example.com", "reset-tok");

        Message message = CONVERTER.toMessage(event, new MessageProperties());
        JsonNode json = MAPPER.readTree(message.getBody());

        assertThat(json.has("userId")).as("不得再帶 Long 主鍵").isFalse();
        assertThat(json.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(json.get("userUuid").asText()).isEqualTo(userUuid.toString());
        message.getMessageProperties().setInferredArgumentType(UserPasswordResetRequestedEvent.class);
        assertThat(CONVERTER.fromMessage(message)).isEqualTo(event);
    }

    @Test
    @DisplayName("舊格式註冊訊息（含 userId、無 eventId）仍可反序列化，信件欄位正確、eventId／userUuid 為 null")
    void legacyRegisteredMessage_stillDeserializes() {
        Object restored = CONVERTER.fromMessage(messageOf(LEGACY_REGISTERED, UserRegisteredEvent.class));

        assertThat(restored).isInstanceOfSatisfying(UserRegisteredEvent.class, e -> {
            assertThat(e.eventId()).isNull();
            assertThat(e.userUuid()).isNull();
            assertThat(e.email()).isEqualTo("old@example.com");
            assertThat(e.nickname()).isEqualTo("Old");
            assertThat(e.verificationToken()).isEqualTo("tok-old");
            assertThat(e.verificationCode()).isEqualTo("654321");
        });
    }

    @Test
    @DisplayName("舊格式密碼重設訊息（含 userId、無 eventId）仍可反序列化，信件欄位正確、eventId／userUuid 為 null")
    void legacyResetMessage_stillDeserializes() {
        Object restored = CONVERTER.fromMessage(messageOf(LEGACY_RESET, UserPasswordResetRequestedEvent.class));

        assertThat(restored).isInstanceOfSatisfying(UserPasswordResetRequestedEvent.class, e -> {
            assertThat(e.eventId()).isNull();
            assertThat(e.userUuid()).isNull();
            assertThat(e.email()).isEqualTo("old@example.com");
            assertThat(e.resetToken()).isEqualTo("reset-old");
        });
    }

    @Test
    @DisplayName("舊格式註冊訊息經 EmailVerificationConsumer：照常寄驗證信、不查冪等表、ack")
    void legacyRegisteredMessage_isStillMailedByConsumer() throws Exception {
        UserMailService mailService = mock(UserMailService.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        Channel channel = mock(Channel.class);
        EmailVerificationConsumer consumer =
                new EmailVerificationConsumer(mailService, new ConsumerExecutor(
                        new TransactionTemplate(mock(PlatformTransactionManager.class)), idempotency));
        UserRegisteredEvent legacy = (UserRegisteredEvent)
                CONVERTER.fromMessage(messageOf(LEGACY_REGISTERED, UserRegisteredEvent.class));

        consumer.handleUserRegistered(legacy, channel, 1L);

        verify(mailService).sendVerificationEmail(legacy);
        verifyNoInteractions(idempotency);
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("舊格式密碼重設訊息經 PasswordResetConsumer：照常寄重設信、不查冪等表、ack")
    void legacyResetMessage_isStillMailedByConsumer() throws Exception {
        UserMailService mailService = mock(UserMailService.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        Channel channel = mock(Channel.class);
        PasswordResetConsumer consumer =
                new PasswordResetConsumer(mailService, new ConsumerExecutor(
                        new TransactionTemplate(mock(PlatformTransactionManager.class)), idempotency));
        UserPasswordResetRequestedEvent legacy = (UserPasswordResetRequestedEvent)
                CONVERTER.fromMessage(messageOf(LEGACY_RESET, UserPasswordResetRequestedEvent.class));

        consumer.handlePasswordResetRequested(legacy, channel, 2L);

        verify(mailService).sendPasswordResetEmail(legacy);
        verifyNoInteractions(idempotency);
        verify(channel).basicAck(2L, false);
    }
}
