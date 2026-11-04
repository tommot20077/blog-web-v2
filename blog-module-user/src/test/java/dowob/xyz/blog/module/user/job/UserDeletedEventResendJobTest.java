package dowob.xyz.blog.module.user.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.service.UserEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link UserDeletedEventResendJob} 單元測試（M2：UserDeletedEvent 遺失的輕量補償）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("UserDeletedEventResendJob")
class UserDeletedEventResendJobTest {

    private UserRepository userRepository;
    private UserEventPublisher publisher;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private UserDeletedEventResendJob job;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        publisher = mock(UserEventPublisher.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        job = new UserDeletedEventResendJob(userRepository, publisher, redis);
    }

    private void lockAcquired(boolean acquired) {
        when(ops.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(acquired);
    }

    @Test
    @DisplayName("取得鎖：以 now-48h 為下界查詢，對每位近期刪除的使用者補發事件")
    void run_lockAcquired_resendsForRecentlyDeletedUsers() {
        lockAcquired(true);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(userRepository.findRecentlyDeletedUuids(any(LocalDateTime.class))).thenReturn(List.of(a, b));

        UserDeletedEventResendJob.Result result = job.run();

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).findRecentlyDeletedUuids(since.capture());
        assertThat(Duration.between(since.getValue(), LocalDateTime.now()))
                .isBetween(Duration.ofHours(48).minusSeconds(30), Duration.ofHours(48).plusSeconds(30));
        verify(publisher).publishDeleted(a);
        verify(publisher).publishDeleted(b);
        assertThat(result.executed()).isTrue();
        assertThat(result.resent()).isEqualTo(2);
        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), any(List.class), any());
    }

    @Test
    @DisplayName("沒有近期刪除的使用者：不發任何事件")
    void run_noRecentlyDeleted_publishesNothing() {
        lockAcquired(true);
        when(userRepository.findRecentlyDeletedUuids(any())).thenReturn(List.of());

        assertThat(job.run().resent()).isZero();

        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("未取得分散式鎖：跳過，不查詢也不發送")
    void run_lockNotAcquired_skips() {
        lockAcquired(false);

        UserDeletedEventResendJob.Result result = job.run();

        assertThat(result.executed()).isFalse();
        verify(userRepository, never()).findRecentlyDeletedUuids(any());
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("鎖 key 沿用 lock:reconciliation:{module}:{table} 慣例")
    void run_usesReconciliationLockKeyConvention() {
        lockAcquired(false);

        job.run();

        verify(ops).setIfAbsent(eq(RedisKeyConstant.getReconciliationLockKey("user", "users.deleted_event_resend")),
                anyString(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("單一使用者補發拋例外：其餘仍繼續，且仍會解鎖")
    void run_onePublishFails_continuesAndUnlocks() {
        lockAcquired(true);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(userRepository.findRecentlyDeletedUuids(any())).thenReturn(List.of(a, b));
        doThrow(new IllegalStateException("mq")).when(publisher).publishDeleted(a);

        UserDeletedEventResendJob.Result result = job.run();

        verify(publisher).publishDeleted(b);
        assertThat(result.resent()).isEqualTo(1);
        verify(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), any(List.class), any());
    }

    @Test
    @DisplayName("排程：恰有一個 @Scheduled 方法，cron 為每小時 :07（不與既有 03:17~04:11 每日排程衝突）")
    void scheduled_exactlyOneHourlyCron() {
        List<Method> scheduled = Arrays.stream(UserDeletedEventResendJob.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class)).toList();
        assertThat(scheduled).hasSize(1);
        assertThat(scheduled.get(0).getAnnotation(Scheduled.class).cron())
                .isEqualTo(UserDeletedEventResendJob.CRON).isEqualTo("0 7 * * * *");
    }
}
