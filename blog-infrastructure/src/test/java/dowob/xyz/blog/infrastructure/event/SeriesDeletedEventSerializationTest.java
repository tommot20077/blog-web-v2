package dowob.xyz.blog.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SeriesDeletedEvent} 序列化測試：欄位只有 eventId、seriesUuid、occurredAt，且皆非 Long。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("SeriesDeletedEvent 序列化")
class SeriesDeletedEventSerializationTest {

    private static SeriesDeletedEvent sample() {
        return new SeriesDeletedEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-10-26T00:00:00Z"));
    }

    @Test
    @DisplayName("JSON 只含 eventId、seriesUuid、occurredAt，UUID 為字串")
    void serialize_shouldContainExactlyTheContractFields() {
        SeriesDeletedEvent event = sample();

        JsonNode json = EventJsonSupport.toJson(event);

        assertThat(EventJsonSupport.fieldNames(json))
                .containsExactlyInAnyOrderElementsOf(Set.of("eventId", "seriesUuid", "occurredAt"));
        assertThat(json.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(json.get("seriesUuid").asText()).isEqualTo(event.seriesUuid().toString());
        assertThat(json.get("occurredAt").isNumber()).isTrue();
    }

    @Test
    @DisplayName("序列化後再反序列化（依 listener 參數型別）得到相同事件")
    void roundTrip_shouldRestoreSameEvent() {
        SeriesDeletedEvent original = sample();

        Object restored = EventJsonSupport.receive(
                EventJsonSupport.toBody(original),
                "dowob.xyz.blog.infrastructure.event.SeriesDeletedEvent",
                SeriesDeletedEvent.class);

        assertThat(restored).isEqualTo(original);
    }
}
