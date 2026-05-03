package dowob.xyz.blog.infrastructure.idempotency;

import dowob.xyz.blog.infrastructure.idempotency.model.ProcessedEvent;
import dowob.xyz.blog.infrastructure.idempotency.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock private ProcessedEventRepository repo;
    @InjectMocks private IdempotencyService service;

    @Test
    void markProcessed_firstCall_returnsTrue() {
        UUID eventId = UUID.randomUUID();
        when(repo.save(any(ProcessedEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean result = service.markProcessed(eventId, "test.consumer");

        assertThat(result).isTrue();
    }

    @Test
    void markProcessed_uniqueConflict_returnsFalse() {
        UUID eventId = UUID.randomUUID();
        when(repo.save(any(ProcessedEvent.class)))
            .thenThrow(new DataIntegrityViolationException("uq_processed_events_event_consumer"));

        boolean result = service.markProcessed(eventId, "test.consumer");

        assertThat(result).isFalse();
    }

    @Test
    void markProcessed_differentConsumers_independent() {
        UUID eventId = UUID.randomUUID();
        when(repo.save(any(ProcessedEvent.class)))
            .thenAnswer(inv -> inv.getArgument(0))
            .thenAnswer(inv -> inv.getArgument(0));

        boolean a = service.markProcessed(eventId, "consumer.a");
        boolean b = service.markProcessed(eventId, "consumer.b");

        assertThat(a).isTrue();
        assertThat(b).isTrue();
    }
}
