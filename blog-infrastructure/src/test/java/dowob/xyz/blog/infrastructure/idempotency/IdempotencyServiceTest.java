package dowob.xyz.blog.infrastructure.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IdempotencyService} 的單元測試。
 *
 * @author Yuan
 * @version 2.0
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @InjectMocks private IdempotencyService service;

    @Test
    void tryMark_firstCall_returnsTrue() {
        UUID eventId = UUID.randomUUID();
        // INSERT ... ON CONFLICT DO NOTHING 成功 → affected rows = 1
        when(jdbcTemplate.update(any(String.class), eq(eventId), any(), any())).thenReturn(1);

        boolean result = service.tryMark(eventId, "test.consumer");

        assertThat(result).isTrue();
    }

    @Test
    void tryMark_uniqueConflict_returnsFalse() {
        UUID eventId = UUID.randomUUID();
        // ON CONFLICT DO NOTHING → affected rows = 0
        when(jdbcTemplate.update(any(String.class), eq(eventId), any(), any())).thenReturn(0);

        boolean result = service.tryMark(eventId, "test.consumer");

        assertThat(result).isFalse();
    }

    @Test
    void tryMark_differentConsumers_independent() {
        UUID eventId = UUID.randomUUID();
        // 兩個不同 consumer 各自 INSERT 成功 → 各自 affected rows = 1
        when(jdbcTemplate.update(any(String.class), eq(eventId), any(), any())).thenReturn(1);

        boolean a = service.tryMark(eventId, "consumer.a");
        boolean b = service.tryMark(eventId, "consumer.b");

        assertThat(a).isTrue();
        assertThat(b).isTrue();
    }

    @Test
    @DisplayName("tryMark 必須在既有交易內執行（MANDATORY），不得再有獨立提交的路徑")
    void tryMark_isTransactionalMandatory() throws NoSuchMethodException {
        Transactional tx = IdempotencyService.class
                .getMethod("tryMark", UUID.class, String.class).getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    @DisplayName("processed_at 維持 Java LocalDateTime.now() 傳入，SQL 不使用資料庫 CURRENT_TIMESTAMP")
    void tryMark_processedAtComesFromJavaClock() {
        UUID eventId = UUID.randomUUID();
        when(jdbcTemplate.update(any(String.class), eq(eventId), any(), any())).thenReturn(1);
        LocalDateTime before = LocalDateTime.now();

        service.tryMark(eventId, "test.consumer");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> processedAt = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(sql.capture(), eq(eventId), eq("test.consumer"), processedAt.capture());
        assertThat(sql.getValue()).doesNotContain("CURRENT_TIMESTAMP").contains("ON CONFLICT");
        assertThat(processedAt.getValue()).isInstanceOf(LocalDateTime.class);
        assertThat((LocalDateTime) processedAt.getValue()).isBetween(before, LocalDateTime.now());
    }
}
