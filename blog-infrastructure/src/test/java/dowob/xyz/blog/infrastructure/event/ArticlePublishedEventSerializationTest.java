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
 * {@link ArticlePublishedEvent} 序列化測試：保留欄位名稱與型別不變，含 eventId 與 authorUuid，且不含已移除的 authorId／authorUsername（M6-a）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticlePublishedEvent 序列化")
class ArticlePublishedEventSerializationTest {

    private static ArticlePublishedEvent sample() {
        return new ArticlePublishedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "標題",
                LocalDateTime.of(2026, 10, 24, 12, 0), "slug", "摘要", "純文字",
                "愛麗絲", List.of(new TagInfo(UUID.randomUUID(), "Java", "java")));
    }

    @Test
    @DisplayName("JSON 含 eventId、authorUuid 與其餘保留欄位（名稱型別不變），不含 authorId／authorUsername")
    void serialize_shouldKeepOriginalFieldsAndAddNewOnes() {
        ArticlePublishedEvent event = sample();

        JsonNode json = EventJsonSupport.toJson(event);

        assertThat(EventJsonSupport.fieldNames(json)).containsExactlyInAnyOrderElementsOf(Set.of(
                "eventId", "articleUuid", "authorUuid", "title", "publishedAt", "slug", "summary",
                "contentText", "authorNickname", "tags"));
        assertThat(json.get("articleUuid").asText()).isEqualTo(event.articleUuid().toString());
        assertThat(json.has("authorId")).as("authorId 已移除（M6-a）").isFalse();
        assertThat(json.get("title").asText()).isEqualTo("標題");
        assertThat(json.get("publishedAt").isArray()).isTrue();
        assertThat(json.get("slug").asText()).isEqualTo("slug");
        assertThat(json.get("summary").asText()).isEqualTo("摘要");
        assertThat(json.get("contentText").asText()).isEqualTo("純文字");
        assertThat(json.has("authorUsername")).as("登入識別符不外流（M6-a）").isFalse();
        assertThat(json.get("authorNickname").asText()).isEqualTo("愛麗絲");
        assertThat(json.get("tags").isArray()).isTrue();
        assertThat(EventJsonSupport.fieldNames(json.get("tags").get(0)))
                .containsExactlyInAnyOrderElementsOf(Set.of("id", "name", "slug"));
        assertThat(json.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(json.get("authorUuid").asText()).isEqualTo(event.authorUuid().toString());
    }

    @Test
    @DisplayName("升級前（不含 eventId／authorUuid、仍含 authorId／authorUsername）的舊 JSON 仍可反序列化，新欄位為 null")
    void deserialize_legacyJson_shouldSucceed() {
        UUID articleUuid = UUID.randomUUID();
        String legacy = "{\"articleUuid\":\"" + articleUuid + "\",\"authorId\":4,\"title\":\"t\","
                + "\"publishedAt\":[2026,10,24,12,0],\"slug\":\"s\",\"summary\":\"m\",\"contentText\":\"c\","
                + "\"authorUsername\":\"u\",\"authorNickname\":\"n\",\"tags\":[]}";

        Object restored = EventJsonSupport.receive(
                legacy.getBytes(StandardCharsets.UTF_8), null, ArticlePublishedEvent.class);

        assertThat(restored).isInstanceOfSatisfying(ArticlePublishedEvent.class, e -> {
            assertThat(e.articleUuid()).isEqualTo(articleUuid);
            assertThat(e.eventId()).isNull();
            assertThat(e.authorUuid()).isNull();
        });
    }

    @Test
    @DisplayName("以舊類別全名的 __TypeId__ header 發送的訊息，仍依 listener 參數型別反序列化為新類別")
    void receive_withLegacyTypeIdHeader_shouldDeserializeByInferredType() {
        ArticlePublishedEvent original = sample();

        Object restored = EventJsonSupport.receive(
                EventJsonSupport.toBody(original),
                "dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent",
                ArticlePublishedEvent.class);

        assertThat(restored).isEqualTo(original);
    }
}
