package dowob.xyz.blog.module.user.consumer;

import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.user.config.UserRabbitMqConfig;
import dowob.xyz.blog.module.user.model.event.RegistrationAttemptOnExistingEmailEvent;
import dowob.xyz.blog.module.user.service.UserMailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RegistrationAttemptNoticeConsumer} 單元測試（SEC-14／Q8）
 *
 * <p>與 {@link EmailVerificationConsumerTest} 同一套冪等語意：以 {@code (eventId, CONSUMER_NAME)} 去重，
 * 寄信成功後標記才 commit（Z3-a「寄後標記」）；寄信失敗往外拋、標記一起 rollback。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RegistrationAttemptNoticeConsumer 單元測試")
class RegistrationAttemptNoticeConsumerTest {

    /** 測試用事件 dedup key */
    private static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000011");

    /** 測試用原信箱主人 uuid（與任何 Long 主鍵無數值關聯） */
    private static final UUID OWNER_UUID = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000012");

    /** 交易管理員替身：用來觀察 commit／rollback */
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    /** Mock 郵件服務 */
    @Mock
    private UserMailService userMailService;

    /** Mock 冪等服務（預設視為第一次處理） */
    @Mock
    private IdempotencyService idempotencyService;

    /** 受測物件 */
    private RegistrationAttemptNoticeConsumer consumer;

    /**
     * 建立被測物件：ConsumerExecutor 使用真實實例，冪等服務為 Mock、交易由 Mock 交易管理員模擬。
     */
    @BeforeEach
    void setUp() {
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(idempotencyService.tryMark(any(), anyString())).thenReturn(true);
        consumer = new RegistrationAttemptNoticeConsumer(userMailService,
                new ConsumerExecutor(new TransactionTemplate(txManager), idempotencyService));
    }

    private static RegistrationAttemptOnExistingEmailEvent event() {
        return new RegistrationAttemptOnExistingEmailEvent(EVENT_ID, OWNER_UUID, "owner@example.com", "Owner");
    }

    @Test
    @DisplayName("監聽專屬 Queue，且與驗證信／重設密碼 consumer 使用不同的 CONSUMER_NAME（冪等標記不互相干擾）")
    void listensOnDedicatedQueue_withDistinctConsumerName() throws Exception {
        RabbitListener listener = RegistrationAttemptNoticeConsumer.class
                .getMethod("handleRegistrationAttempt", RegistrationAttemptOnExistingEmailEvent.class)
                .getAnnotation(RabbitListener.class);

        assertThat(listener).as("handleRegistrationAttempt 必須是 @RabbitListener").isNotNull();
        assertThat(listener.queues()).containsExactly(UserRabbitMqConfig.QUEUE_REGISTRATION_ATTEMPT);
        assertThat(RegistrationAttemptNoticeConsumer.CONSUMER_NAME)
                .isNotBlank()
                .isNotEqualTo(EmailVerificationConsumer.CONSUMER_NAME)
                .isNotEqualTo(PasswordResetConsumer.CONSUMER_NAME);
    }

    @Test
    @DisplayName("第一次處理 → 以 (eventId, CONSUMER_NAME) 標記並寄通知信")
    void handle_firstDelivery_marksEventIdAndSendsNotice() {
        consumer.handleRegistrationAttempt(event());

        verify(idempotencyService).tryMark(EVENT_ID, RegistrationAttemptNoticeConsumer.CONSUMER_NAME);
        verify(userMailService).sendRegistrationAttemptNotice(event());
    }

    @Test
    @DisplayName("寄信成功後標記才 commit（寄後標記）")
    void handle_onSuccess_commitsMarkOnlyAfterMailSent() {
        consumer.handleRegistrationAttempt(event());

        InOrder order = inOrder(userMailService, txManager);
        order.verify(userMailService).sendRegistrationAttemptNotice(event());
        order.verify(txManager).commit(any());
    }

    @Test
    @DisplayName("同一事件重送（tryMark 回 false）→ 不重寄，仍正常返回")
    void handle_duplicateEventId_skipsMail() {
        when(idempotencyService.tryMark(EVENT_ID, RegistrationAttemptNoticeConsumer.CONSUMER_NAME)).thenReturn(false);

        consumer.handleRegistrationAttempt(event());

        verify(userMailService, never()).sendRegistrationAttemptNotice(any());
    }

    @Test
    @DisplayName("寄信失敗 → 例外往外拋（由容器重試／進 DLQ），標記所在交易 rollback、不 commit")
    void handle_onMailFailure_rethrowsAndRollsBackMark() {
        doThrow(new IllegalStateException("SMTP failed")).when(userMailService)
                .sendRegistrationAttemptNotice(event());

        assertThatThrownBy(() -> consumer.handleRegistrationAttempt(event()))
                .isInstanceOf(RuntimeException.class);

        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
    }

    @Test
    @DisplayName("eventId 為 null 的訊息 → 不查冪等表，照常寄信")
    void handle_withoutEventId_sendsWithoutDedup() {
        RegistrationAttemptOnExistingEmailEvent noId =
                new RegistrationAttemptOnExistingEmailEvent(null, OWNER_UUID, "owner@example.com", "Owner");

        consumer.handleRegistrationAttempt(noId);

        verifyNoInteractions(idempotencyService);
        verify(userMailService).sendRegistrationAttemptNotice(noId);
    }
}
