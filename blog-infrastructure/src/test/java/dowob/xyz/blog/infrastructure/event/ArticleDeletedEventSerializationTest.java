package dowob.xyz.blog.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleDeletedEvent} 序列化測試：只增不減——原有欄位名稱與型別不變，另含 uuid 過渡欄位。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleDeletedEvent 序列化")
class ArticleDeletedEventSerializationTest {

    private static ArticleDeletedEvent sample(UUID authorUuid, UUID seriesUuid) {
        return new ArticleDeletedEvent(
                UUID.randomUUID(), 100L, UUID.randomUUID(), 1L, 200L,
                List.of(UUID.randomUUID()), List.of(UUID.randomUUID()),
                Instant.parse("2026-10-24T00:00:00Z"), authorUuid, seriesUuid);
    }

    @Test
    @DisplayName("JSON 含原有全部欄位（名稱型別不變）＋ authorUuid／seriesUuid")
    void serialize_shouldKeepOriginalFieldsAndAddUuidFields() {
        UUID authorUuid = UUID.randomUUID();
        UUID seriesUuid = UUID.randomUUID();
        ArticleDeletedEvent event = sample(authorUuid, seriesUuid);

        JsonNode json = EventJsonSupport.toJson(event);

        assertThat(EventJsonSupport.fieldNames(json)).containsExactlyInAnyOrderElementsOf(Set.of(
                "eventId", "articleId", "articleUuid", "authorId", "seriesId",
                "categoryIds", "tagIds", "occurredAt", "authorUuid", "seriesUuid"));
        assertThat(json.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(json.get("articleId").isIntegralNumber()).isTrue();
        assertThat(json.get("articleId").asLong()).isEqualTo(100L);
        assertThat(json.get("articleUuid").asText()).isEqualTo(event.articleUuid().toString());
        assertThat(json.get("authorId").isIntegralNumber()).isTrue();
        assertThat(json.get("seriesId").asLong()).isEqualTo(200L);
        assertThat(json.get("categoryIds").isArray()).isTrue();
        assertThat(json.get("tagIds").isArray()).isTrue();
        assertThat(json.get("occurredAt").isNumber()).isTrue();
        assertThat(json.get("authorUuid").asText()).isEqualTo(authorUuid.toString());
        assertThat(json.get("seriesUuid").asText()).isEqualTo(seriesUuid.toString());
    }

    @Test
    @DisplayName("文章不在 series 時 seriesId 與 seriesUuid 皆序列化為 null")
    void serialize_withoutSeries_shouldWriteNulls() {
        ArticleDeletedEvent event = new ArticleDeletedEvent(
                UUID.randomUUID(), 1L, UUID.randomUUID(), 2L, null,
                List.of(), List.of(), Instant.parse("2026-10-24T00:00:00Z"), UUID.randomUUID(), null);

        JsonNode json = EventJsonSupport.toJson(event);

        assertThat(json.get("seriesId").isNull()).isTrue();
        assertThat(json.get("seriesUuid").isNull()).isTrue();
    }

    @Test
    @DisplayName("升級前（不含 authorUuid／seriesUuid）的舊 JSON 仍可反序列化，新欄位為 null")
    void deserialize_legacyJsonWithoutUuidFields_shouldSucceed() {
        UUID eventId = UUID.randomUUID();
        UUID articleUuid = UUID.randomUUID();
        String legacy = "{\"eventId\":\"" + eventId + "\",\"articleId\":7,\"articleUuid\":\"" + articleUuid
                + "\",\"authorId\":3,\"seriesId\":9,\"categoryIds\":[],\"tagIds\":[],\"occurredAt\":1792800000.000000000}";

        Object restored = EventJsonSupport.receive(
                legacy.getBytes(StandardCharsets.UTF_8), null, ArticleDeletedEvent.class);

        assertThat(restored).isInstanceOfSatisfying(ArticleDeletedEvent.class, e -> {
            assertThat(e.eventId()).isEqualTo(eventId);
            assertThat(e.articleId()).isEqualTo(7L);
            assertThat(e.seriesId()).isEqualTo(9L);
            assertThat(e.authorUuid()).isNull();
            assertThat(e.seriesUuid()).isNull();
        });
    }

    @Test
    @DisplayName("以舊類別全名的 __TypeId__ header 發送的訊息，仍依 listener 參數型別反序列化為新類別")
    void receive_withLegacyTypeIdHeader_shouldDeserializeByInferredType() {
        ArticleDeletedEvent original = sample(UUID.randomUUID(), UUID.randomUUID());

        Object restored = EventJsonSupport.receive(
                EventJsonSupport.toBody(original),
                "dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent",
                ArticleDeletedEvent.class);

        assertThat(restored).isEqualTo(original);
    }
}
