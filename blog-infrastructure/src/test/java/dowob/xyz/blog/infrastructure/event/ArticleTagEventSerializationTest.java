package dowob.xyz.blog.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleTagEvent} 序列化測試。
 *
 * <p>ArticleTagEvent 原本在 infrastructure 與 tag 模組各有一份重複定義（ARCH-01），現收斂為本份。
 * 兩份的 JSON 形狀完全相同（eventId／articleId／tagIds，其中 {@code articleId} 實際存放文章公開 UUID），
 * 欄位名稱凍結不改；本事件已有 {@code eventId} 與 UUID 型別的文章識別，不需再新增欄位。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleTagEvent 序列化")
class ArticleTagEventSerializationTest {

    @Test
    @DisplayName("JSON 欄位維持 eventId／articleId／tagIds，articleId 為 UUID 字串")
    void serialize_shouldKeepOriginalFieldsAndTypes() {
        UUID eventId = UUID.randomUUID();
        UUID articleUuid = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();

        JsonNode json = EventJsonSupport.toJson(new ArticleTagEvent(eventId, articleUuid, List.of(tagId)));

        assertThat(EventJsonSupport.fieldNames(json))
                .containsExactlyInAnyOrderElementsOf(Set.of("eventId", "articleId", "tagIds"));
        assertThat(json.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(json.get("articleId").isTextual()).isTrue();
        assertThat(json.get("articleId").asText()).isEqualTo(articleUuid.toString());
        assertThat(json.get("tagIds").isArray()).isTrue();
        assertThat(json.get("tagIds").get(0).asText()).isEqualTo(tagId.toString());
    }

    @Test
    @DisplayName("舊訊息（沒有 eventId）仍可反序列化，eventId 為 null")
    void deserialize_legacyJsonWithoutEventId_shouldSucceed() {
        UUID articleUuid = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();
        String legacy = "{\"articleId\":\"" + articleUuid + "\",\"tagIds\":[\"" + tagId + "\"]}";

        Object restored = EventJsonSupport.receive(
                legacy.getBytes(StandardCharsets.UTF_8), null, ArticleTagEvent.class);

        assertThat(restored).isEqualTo(new ArticleTagEvent(null, articleUuid, List.of(tagId)));
    }

    @Test
    @DisplayName("以 tag 模組舊類別全名的 __TypeId__ header 發送的訊息，仍依 listener 參數型別反序列化為共用類別")
    void receive_withLegacyTagModuleTypeId_shouldDeserializeByInferredType() {
        ArticleTagEvent original = new ArticleTagEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID()));

        Object restored = EventJsonSupport.receive(
                EventJsonSupport.toBody(original),
                "dowob.xyz.blog.module.tag.event.ArticleTagEvent",
                ArticleTagEvent.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("發送端舊的 infrastructure 全名 __TypeId__ header 同樣可反序列化")
    void receive_withInfrastructureTypeId_shouldDeserializeByInferredType() {
        ArticleTagEvent original = new ArticleTagEvent(UUID.randomUUID(), UUID.randomUUID(), List.of());

        Object restored = EventJsonSupport.receive(
                EventJsonSupport.toBody(original),
                "dowob.xyz.blog.infrastructure.event.ArticleTagEvent",
                ArticleTagEvent.class);

        assertThat(restored).isEqualTo(original);
    }
}
