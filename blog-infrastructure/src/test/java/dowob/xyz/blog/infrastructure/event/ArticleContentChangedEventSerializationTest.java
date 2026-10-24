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
 * {@link ArticleContentChangedEvent} 序列化測試：只增不減——原有欄位名稱與型別不變，另含 eventId 與 authorUuid。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleContentChangedEvent 序列化")
class ArticleContentChangedEventSerializationTest {

    private static ArticleContentChangedEvent sample() {
        return new ArticleContentChangedEvent(
                UUID.randomUUID(), 100L, UUID.randomUUID(), 1L, UUID.randomUUID(),
                ArticleContentChangedEvent.Action.SAVED, Instant.parse("2026-10-24T00:00:00Z"));
    }

    @Test
    @DisplayName("JSON 含原有全部欄位（名稱型別不變）＋ eventId ＋ authorUuid")
    void serialize_shouldKeepOriginalFieldsAndAddNewOnes() {
        ArticleContentChangedEvent event = sample();

        JsonNode json = EventJsonSupport.toJson(event);

        assertThat(EventJsonSupport.fieldNames(json)).containsExactlyInAnyOrderElementsOf(Set.of(
                "eventId", "articleId", "articleUuid", "authorId", "authorUuid", "action", "occurredAt"));
        assertThat(json.get("articleId").isIntegralNumber()).isTrue();
        assertThat(json.get("articleId").asLong()).isEqualTo(100L);
        assertThat(json.get("articleUuid").asText()).isEqualTo(event.articleUuid().toString());
        assertThat(json.get("authorId").isIntegralNumber()).isTrue();
        assertThat(json.get("action").asText()).isEqualTo("SAVED");
        assertThat(json.get("occurredAt").isNumber()).isTrue();
        assertThat(json.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(json.get("authorUuid").asText()).isEqualTo(event.authorUuid().toString());
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
            assertThat(e.articleId()).isEqualTo(5L);
            assertThat(e.action()).isEqualTo(ArticleContentChangedEvent.Action.PUBLISHED);
            assertThat(e.eventId()).isNull();
            assertThat(e.authorUuid()).isNull();
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
