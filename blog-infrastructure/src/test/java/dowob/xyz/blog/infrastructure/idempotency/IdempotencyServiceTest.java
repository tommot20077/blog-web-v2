package dowob.xyz.blog.infrastructure.idempotency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @InjectMocks private IdempotencyService service;

    @Test
    void markProcessed_firstCall_returnsTrue() {
        UUID eventId = UUID.randomUUID();
        // INSERT ... ON CONFLICT DO NOTHING 成功 → affected rows = 1
        when(jdbcTemplate.update(any(String.class), eq(eventId), any(), any())).thenReturn(1);

        boolean result = service.markProcessed(eventId, "test.consumer");

        assertThat(result).isTrue();
    }

    @Test
    void markProcessed_uniqueConflict_returnsFalse() {
        UUID eventId = UUID.randomUUID();
        // ON CONFLICT DO NOTHING → affected rows = 0
        when(jdbcTemplate.update(any(String.class), eq(eventId), any(), any())).thenReturn(0);

        boolean result = service.markProcessed(eventId, "test.consumer");

        assertThat(result).isFalse();
    }

    @Test
    void markProcessed_differentConsumers_independent() {
        UUID eventId = UUID.randomUUID();
        // 兩個不同 consumer 各自 INSERT 成功 → 各自 affected rows = 1
        when(jdbcTemplate.update(any(String.class), eq(eventId), any(), any())).thenReturn(1);

        boolean a = service.markProcessed(eventId, "consumer.a");
        boolean b = service.markProcessed(eventId, "consumer.b");

        assertThat(a).isTrue();
        assertThat(b).isTrue();
    }
}
