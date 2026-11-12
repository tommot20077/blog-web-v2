package dowob.xyz.blog.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleArchivedEvent} 序列化測試：搬移到共用契約後 JSON 格式必須維持不變。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleArchivedEvent 序列化")
class ArticleArchivedEventSerializationTest {

    @Test
    @DisplayName("JSON 欄位維持 eventId／articleUuid／occurredAt，型別不變")
    void serialize_shouldKeepOriginalFieldsAndTypes() {
        UUID eventId = UUID.randomUUID();
        UUID articleUuid = UUID.randomUUID();

        JsonNode json = EventJsonSupport.toJson(
                new ArticleArchivedEvent(eventId, articleUuid, Instant.parse("2026-10-24T00:00:00Z")));

        assertThat(EventJsonSupport.fieldNames(json))
                .containsExactlyInAnyOrderElementsOf(Set.of("eventId", "articleUuid", "occurredAt"));
        assertThat(json.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(json.get("articleUuid").asText()).isEqualTo(articleUuid.toString());
        assertThat(json.get("occurredAt").isNumber()).isTrue();
    }

    @Test
    @DisplayName("反序列化可還原事件")
    void roundTrip_shouldRestoreEvent() {
        ArticleArchivedEvent original = new ArticleArchivedEvent(
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-10-24T00:00:00Z"));

        Object restored = EventJsonSupport.receive(
                EventJsonSupport.toBody(original), null, ArticleArchivedEvent.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("以舊類別全名的 __TypeId__ header 發送的訊息，仍依 listener 參數型別反序列化為新類別")
    void receive_withLegacyTypeIdHeader_shouldDeserializeByInferredType() {
        ArticleArchivedEvent original = new ArticleArchivedEvent(
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-10-24T00:00:00Z"));

        Object restored = EventJsonSupport.receive(
                EventJsonSupport.toBody(original),
                "dowob.xyz.blog.infrastructure.event.ArticleArchivedEvent",
                ArticleArchivedEvent.class);

        assertThat(restored).isEqualTo(original);
    }
}
