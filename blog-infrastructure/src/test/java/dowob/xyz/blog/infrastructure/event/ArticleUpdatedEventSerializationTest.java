package dowob.xyz.blog.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleUpdatedEvent} 序列化測試：只增不減——原有欄位名稱與型別不變，另含 eventId 與 authorUuid。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleUpdatedEvent 序列化")
class ArticleUpdatedEventSerializationTest {

    private static ArticleUpdatedEvent sample() {
        return new ArticleUpdatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), 1L, UUID.randomUUID(), "標題",
                LocalDateTime.of(2026, 10, 24, 12, 0), "slug", "摘要", "純文字",
                "alice", "愛麗絲", List.of(new TagInfo(UUID.randomUUID(), "Java", "java")));
    }

    @Test
    @DisplayName("JSON 含原有全部欄位（名稱型別不變）＋ eventId ＋ authorUuid")
    void serialize_shouldKeepOriginalFieldsAndAddNewOnes() {
        ArticleUpdatedEvent event = sample();

        JsonNode json = EventJsonSupport.toJson(event);

        assertThat(EventJsonSupport.fieldNames(json)).containsExactlyInAnyOrderElementsOf(Set.of(
                "eventId", "articleUuid", "authorId", "authorUuid", "title", "publishedAt", "slug", "summary",
                "contentText", "authorUsername", "authorNickname", "tags"));
        assertThat(json.get("articleUuid").asText()).isEqualTo(event.articleUuid().toString());
        assertThat(json.get("authorId").isIntegralNumber()).isTrue();
        assertThat(json.get("authorId").asLong()).isEqualTo(1L);
        assertThat(json.get("title").asText()).isEqualTo("標題");
        assertThat(json.get("publishedAt").isArray()).isTrue();
        assertThat(json.get("slug").asText()).isEqualTo("slug");
        assertThat(json.get("summary").asText()).isEqualTo("摘要");
        assertThat(json.get("contentText").asText()).isEqualTo("純文字");
        assertThat(json.get("authorUsername").asText()).isEqualTo("alice");
        assertThat(json.get("authorNickname").asText()).isEqualTo("愛麗絲");
        assertThat(json.get("tags").isArray()).isTrue();
        assertThat(EventJsonSupport.fieldNames(json.get("tags").get(0)))
                .containsExactlyInAnyOrderElementsOf(Set.of("id", "name", "slug"));
        assertThat(json.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(json.get("authorUuid").asText()).isEqualTo(event.authorUuid().toString());
    }

    @Test
    @DisplayName("升級前（不含 eventId／authorUuid）的舊 JSON 仍可反序列化，新欄位為 null")
    void deserialize_legacyJson_shouldSucceed() {
        UUID articleUuid = UUID.randomUUID();
        String legacy = "{\"articleUuid\":\"" + articleUuid + "\",\"authorId\":4,\"title\":\"t\","
                + "\"publishedAt\":[2026,10,24,12,0],\"slug\":\"s\",\"summary\":\"m\",\"contentText\":\"c\","
                + "\"authorUsername\":\"u\",\"authorNickname\":\"n\",\"tags\":[]}";

        Object restored = EventJsonSupport.receive(
                legacy.getBytes(StandardCharsets.UTF_8), null, ArticleUpdatedEvent.class);

        assertThat(restored).isInstanceOfSatisfying(ArticleUpdatedEvent.class, e -> {
            assertThat(e.articleUuid()).isEqualTo(articleUuid);
            assertThat(e.authorId()).isEqualTo(4L);
            assertThat(e.eventId()).isNull();
            assertThat(e.authorUuid()).isNull();
        });
    }

    @Test
    @DisplayName("以舊類別全名的 __TypeId__ header 發送的訊息，仍依 listener 參數型別反序列化為新類別")
    void receive_withLegacyTypeIdHeader_shouldDeserializeByInferredType() {
        ArticleUpdatedEvent original = sample();

        Object restored = EventJsonSupport.receive(
                EventJsonSupport.toBody(original),
                "dowob.xyz.blog.infrastructure.event.ArticleUpdatedEvent",
                ArticleUpdatedEvent.class);

        assertThat(restored).isEqualTo(original);
    }
}
