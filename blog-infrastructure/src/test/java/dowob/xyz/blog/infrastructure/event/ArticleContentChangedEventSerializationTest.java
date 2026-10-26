package dowob.xyz.blog.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleContentChangedEvent} 序列化測試：M3-b 起 Long 過渡欄位（articleId、authorId）已移除，
 * 事件只帶 eventId、articleUuid、authorUuid、action、occurredAt；舊訊息（仍含 Long 欄位）必須仍可反序列化。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleContentChangedEvent 序列化")
class ArticleContentChangedEventSerializationTest {

    private static ArticleContentChangedEvent sample() {
        return new ArticleContentChangedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ArticleContentChangedEvent.Action.SAVED, Instant.parse("2026-10-24T00:00:00Z"));
    }

    @Test
    @DisplayName("JSON 只含 eventId、articleUuid、authorUuid、action、occurredAt，沒有任何 Long 識別欄位")
    void serialize_shouldContainOnlyUuidIdentityFields() {
        ArticleContentChangedEvent event = sample();

        JsonNode json = EventJsonSupport.toJson(event);

        assertThat(EventJsonSupport.fieldNames(json)).containsExactlyInAnyOrderElementsOf(Set.of(
                "eventId", "articleUuid", "authorUuid", "action", "occurredAt"));
        assertThat(json.get("articleUuid").asText()).isEqualTo(event.articleUuid().toString());
        assertThat(json.get("action").asText()).isEqualTo("SAVED");
        assertThat(json.get("occurredAt").isNumber()).isTrue();
        assertThat(json.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(json.get("authorUuid").asText()).isEqualTo(event.authorUuid().toString());
    }

    @Test
    @DisplayName("record 元件不含 articleId／authorId（version 是最後一個讀取者，M3-b 移除過渡欄位）")
    void recordComponents_shouldNotContainLongIdentityFields() {
        assertThat(java.util.Arrays.stream(ArticleContentChangedEvent.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("eventId", "articleUuid", "authorUuid", "action", "occurredAt");
    }

    @Test
    @DisplayName("升級前（不含 eventId／authorUuid）的舊 JSON 仍可反序列化，新欄位為 null")
    void deserialize_legacyJson_shouldSucceed() {
        UUID articleUuid = UUID.randomUUID();
        String legacy = "{\"articleId\":5,\"articleUuid\":\"" + articleUuid
                + "\",\"authorId\":2,\"action\":\"PUBLISHED\",\"occurredAt\":1792800000.000000000}";

        Object restored = EventJsonSupport.receive(
                legacy.getBytes(StandardCharsets.UTF_8), null, ArticleContentChangedEvent.class);

        assertThat(restored).isInstanceOfSatisfying(ArticleContentChangedEvent.class, e -> {
            assertThat(e.articleUuid()).isEqualTo(articleUuid);
            assertThat(e.action()).isEqualTo(ArticleContentChangedEvent.Action.PUBLISHED);
            assertThat(e.eventId()).isNull();
            assertThat(e.authorUuid()).isNull();
        });
    }

    @Test
    @DisplayName("移除 Long 欄位前發出的 in-flight 舊訊息（仍含 articleId／authorId）可反序列化，多餘欄位被忽略")
    void deserialize_inFlightJsonWithRemovedLongFields_shouldSucceed() {
        UUID articleUuid = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID authorUuid = UUID.randomUUID();
        String inFlight = "{\"eventId\":\"" + eventId + "\",\"articleId\":5,\"articleUuid\":\"" + articleUuid
                + "\",\"authorId\":2,\"authorUuid\":\"" + authorUuid
                + "\",\"action\":\"SAVED\",\"occurredAt\":1792800000.000000000}";

        Object restored = EventJsonSupport.receive(
                inFlight.getBytes(StandardCharsets.UTF_8), null, ArticleContentChangedEvent.class);

        assertThat(restored).isInstanceOfSatisfying(ArticleContentChangedEvent.class, e -> {
            assertThat(e.eventId()).isEqualTo(eventId);
            assertThat(e.articleUuid()).isEqualTo(articleUuid);
            assertThat(e.authorUuid()).isEqualTo(authorUuid);
            assertThat(e.action()).isEqualTo(ArticleContentChangedEvent.Action.SAVED);
        });
    }

    @Test
    @DisplayName("以舊類別全名的 __TypeId__ header 發送的訊息，仍依 listener 參數型別反序列化為新類別")
    void receive_withLegacyTypeIdHeader_shouldDeserializeByInferredType() {
        ArticleContentChangedEvent original = sample();

        Object restored = EventJsonSupport.receive(
                EventJsonSupport.toBody(original),
                "dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent",
                ArticleContentChangedEvent.class);

        assertThat(restored).isEqualTo(original);
    }
}
