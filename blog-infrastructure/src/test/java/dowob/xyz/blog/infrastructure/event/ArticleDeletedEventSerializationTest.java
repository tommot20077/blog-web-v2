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
 * {@link ArticleDeletedEvent} 序列化測試。
 *
 * <p>M4-b 起 {@code articleId}、{@code seriesId} 兩個 Long 過渡欄位已移除（series 是最後一個讀取者，
 * 已改讀 {@code seriesUuid}）；{@code authorId} 已於 M6-a 移除。升級當下佇列中含舊欄位的 in-flight 訊息
 * 必須仍可反序列化（多出的欄位被忽略）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleDeletedEvent 序列化")
class ArticleDeletedEventSerializationTest {

    private static ArticleDeletedEvent sample(UUID authorUuid, UUID seriesUuid) {
        return new ArticleDeletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID()), List.of(UUID.randomUUID()), Instant.parse("2026-10-24T00:00:00Z"), authorUuid, seriesUuid);
    }

    @Test
    @DisplayName("JSON 不再含 articleId／seriesId／authorId；其餘欄位（含 authorUuid、seriesUuid）名稱型別不變")
    void serialize_shouldNotContainRemovedLongFields() {
        UUID authorUuid = UUID.randomUUID();
        UUID seriesUuid = UUID.randomUUID();
        ArticleDeletedEvent event = sample(authorUuid, seriesUuid);

        JsonNode json = EventJsonSupport.toJson(event);

        assertThat(EventJsonSupport.fieldNames(json)).containsExactlyInAnyOrderElementsOf(Set.of(
                "eventId", "articleUuid",
                "categoryIds", "tagIds", "occurredAt", "authorUuid", "seriesUuid"));
        assertThat(json.has("articleId")).as("articleId 已移除，articleUuid 是唯一的文章識別").isFalse();
        assertThat(json.has("seriesId")).as("seriesId 已移除，seriesUuid 是唯一的 series 識別").isFalse();
        assertThat(json.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(json.get("articleUuid").asText()).isEqualTo(event.articleUuid().toString());
        assertThat(json.has("authorId")).as("authorId 已移除，authorUuid 是唯一的作者識別").isFalse();
        assertThat(json.get("categoryIds").isArray()).isTrue();
        assertThat(json.get("tagIds").isArray()).isTrue();
        assertThat(json.get("occurredAt").isNumber()).isTrue();
        assertThat(json.get("authorUuid").asText()).isEqualTo(authorUuid.toString());
        assertThat(json.get("seriesUuid").asText()).isEqualTo(seriesUuid.toString());
    }

    @Test
    @DisplayName("文章不在 series 時 seriesUuid 序列化為 null")
    void serialize_withoutSeries_shouldWriteNulls() {
        ArticleDeletedEvent event = new ArticleDeletedEvent(
                UUID.randomUUID(), UUID.randomUUID(), List.of(), List.of(), Instant.parse("2026-10-24T00:00:00Z"), UUID.randomUUID(), null);

        JsonNode json = EventJsonSupport.toJson(event);

        assertThat(json.get("seriesUuid").isNull()).isTrue();
    }

    @Test
    @DisplayName("升級前（含 articleId／seriesId、不含 authorUuid／seriesUuid）的舊 JSON 仍可反序列化，多出的欄位被忽略")
    void deserialize_legacyJsonWithLongFieldsAndWithoutUuidFields_shouldSucceed() {
        UUID eventId = UUID.randomUUID();
        UUID articleUuid = UUID.randomUUID();
        String legacy = "{\"eventId\":\"" + eventId + "\",\"articleId\":7,\"articleUuid\":\"" + articleUuid
                + "\",\"authorId\":3,\"seriesId\":9,\"categoryIds\":[],\"tagIds\":[],\"occurredAt\":1792800000.000000000}";

        Object restored = EventJsonSupport.receive(
                legacy.getBytes(StandardCharsets.UTF_8), null, ArticleDeletedEvent.class);

        assertThat(restored).isInstanceOfSatisfying(ArticleDeletedEvent.class, e -> {
            assertThat(e.eventId()).isEqualTo(eventId);
            assertThat(e.articleUuid()).isEqualTo(articleUuid);
            assertThat(e.authorUuid()).isNull();
            assertThat(e.seriesUuid()).isNull();
        });
    }

    @Test
    @DisplayName("in-flight 訊息（同時含舊 Long 欄位與 seriesUuid）仍可反序列化，consumer 讀得到 seriesUuid")
    void deserialize_inFlightJsonWithLongFieldsAndSeriesUuid_shouldReadSeriesUuid() {
        UUID eventId = UUID.randomUUID();
        UUID articleUuid = UUID.randomUUID();
        UUID seriesUuid = UUID.randomUUID();
        UUID authorUuid = UUID.randomUUID();
        String inFlight = "{\"eventId\":\"" + eventId + "\",\"articleId\":7,\"articleUuid\":\"" + articleUuid
                + "\",\"authorId\":3,\"seriesId\":9,\"categoryIds\":[],\"tagIds\":[],"
                + "\"occurredAt\":1792800000.000000000,\"authorUuid\":\"" + authorUuid
                + "\",\"seriesUuid\":\"" + seriesUuid + "\"}";

        Object restored = EventJsonSupport.receive(
                inFlight.getBytes(StandardCharsets.UTF_8), null, ArticleDeletedEvent.class);

        assertThat(restored).isInstanceOfSatisfying(ArticleDeletedEvent.class, e -> {
            assertThat(e.eventId()).isEqualTo(eventId);
            assertThat(e.authorUuid()).isEqualTo(authorUuid);
            assertThat(e.seriesUuid()).isEqualTo(seriesUuid);
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
