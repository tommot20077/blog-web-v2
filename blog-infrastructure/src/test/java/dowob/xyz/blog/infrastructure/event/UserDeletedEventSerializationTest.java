package dowob.xyz.blog.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserDeletedEvent} 序列化測試：欄位只有 eventId、userUuid、occurredAt，且皆非 Long。
 *
 * <p>事件刻意<b>不帶</b> email、暱稱等任何個資：刪除當下這些欄位已被匿名化，
 * 訂閱端需要顯示資訊時一律走 {@code UserFacade}，MQ 訊息與其日誌因此不會成為個資殘留點。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("UserDeletedEvent 序列化")
class UserDeletedEventSerializationTest {

    private static UserDeletedEvent sample() {
        return new UserDeletedEvent(UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test
    @DisplayName("JSON 只含 eventId、userUuid、occurredAt，UUID 為字串")
    void serialize_shouldContainExactlyTheContractFields() {
        UserDeletedEvent event = sample();

        JsonNode json = EventJsonSupport.toJson(event);

        assertThat(EventJsonSupport.fieldNames(json))
                .containsExactlyInAnyOrderElementsOf(Set.of("eventId", "userUuid", "occurredAt"));
        assertThat(json.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(json.get("userUuid").asText()).isEqualTo(event.userUuid().toString());
        assertThat(json.get("occurredAt").isNumber()).isTrue();
    }

    @Test
    @DisplayName("序列化後再反序列化（依 listener 參數型別）得到相同事件")
    void roundTrip_shouldRestoreSameEvent() {
        UserDeletedEvent original = sample();

        Object restored = EventJsonSupport.receive(
                EventJsonSupport.toBody(original),
                "dowob.xyz.blog.infrastructure.event.UserDeletedEvent",
                UserDeletedEvent.class);

        assertThat(restored).isEqualTo(original);
    }
}
