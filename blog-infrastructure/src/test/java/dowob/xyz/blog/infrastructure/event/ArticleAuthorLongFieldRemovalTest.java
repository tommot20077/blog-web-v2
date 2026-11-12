package dowob.xyz.blog.infrastructure.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6-a：文章事件移除 Long 作者識別（{@code authorId}）與登入識別符（{@code authorUsername}）的契約測試。
 *
 * <p>search、recommend 已於 M7-search／先前階段改讀 {@code authorUuid}／{@code authorNickname}，
 * 事件上已無任何讀取者，因此直接從 record 移除（不得保留為恆 null 的欄位）。
 * 升級當下佇列中仍含舊欄位的 in-flight 訊息必須仍可被反序列化（多出的欄位被忽略）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("文章事件移除 authorId／authorUsername")
class ArticleAuthorLongFieldRemovalTest {

    /**
     * 取得 record 的元件名稱。
     *
     * @param type record 類別
     * @return 元件名稱清單
     */
    private static List<String> components(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    @Test
    @DisplayName("ArticlePublishedEvent 不含 authorId／authorUsername，保留 authorUuid／authorNickname")
    void publishedEvent_hasNoLongAuthorIdAndNoUsername() {
        assertThat(components(ArticlePublishedEvent.class))
                .doesNotContain("authorId", "authorUsername")
                .contains("eventId", "authorUuid", "authorNickname");
    }

    @Test
    @DisplayName("ArticleUpdatedEvent 不含 authorId／authorUsername，保留 authorUuid／authorNickname")
    void updatedEvent_hasNoLongAuthorIdAndNoUsername() {
        assertThat(components(ArticleUpdatedEvent.class))
                .doesNotContain("authorId", "authorUsername")
                .contains("eventId", "authorUuid", "authorNickname");
    }

    @Test
    @DisplayName("ArticleDeletedEvent 不含 authorId，保留 authorUuid")
    void deletedEvent_hasNoLongAuthorId() {
        assertThat(components(ArticleDeletedEvent.class))
                .doesNotContain("authorId")
                .contains("eventId", "authorUuid", "seriesUuid");
    }

    @Test
    @DisplayName("in-flight 發布訊息（仍含 authorId／authorUsername）可反序列化，authorUuid／authorNickname 正確帶入")
    void inFlightPublishedJson_withRemovedFields_stillDeserializes() {
        UUID articleUuid = UUID.randomUUID();
        UUID authorUuid = UUID.randomUUID();
        String inFlight = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"articleUuid\":\"" + articleUuid
                + "\",\"authorId\":4,\"authorUuid\":\"" + authorUuid + "\",\"title\":\"t\","
                + "\"publishedAt\":[2026,10,24,12,0],\"slug\":\"s\",\"summary\":\"m\",\"contentText\":\"c\","
                + "\"authorUsername\":\"u\",\"authorNickname\":\"n\",\"tags\":[]}";

        Object restored = EventJsonSupport.receive(
                inFlight.getBytes(StandardCharsets.UTF_8), null, ArticlePublishedEvent.class);

        assertThat(restored).isInstanceOfSatisfying(ArticlePublishedEvent.class, e -> {
            assertThat(e.articleUuid()).isEqualTo(articleUuid);
            assertThat(e.authorUuid()).isEqualTo(authorUuid);
            assertThat(e.authorNickname()).isEqualTo("n");
        });
    }

    @Test
    @DisplayName("in-flight 更新訊息（仍含 authorId／authorUsername）可反序列化，authorUuid／authorNickname 正確帶入")
    void inFlightUpdatedJson_withRemovedFields_stillDeserializes() {
        UUID articleUuid = UUID.randomUUID();
        UUID authorUuid = UUID.randomUUID();
        String inFlight = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"articleUuid\":\"" + articleUuid
                + "\",\"authorId\":4,\"authorUuid\":\"" + authorUuid + "\",\"title\":\"t\","
                + "\"publishedAt\":[2026,10,24,12,0],\"slug\":\"s\",\"summary\":\"m\",\"contentText\":\"c\","
                + "\"authorUsername\":\"u\",\"authorNickname\":\"n\",\"tags\":[]}";

        Object restored = EventJsonSupport.receive(
                inFlight.getBytes(StandardCharsets.UTF_8), null, ArticleUpdatedEvent.class);

        assertThat(restored).isInstanceOfSatisfying(ArticleUpdatedEvent.class, e -> {
            assertThat(e.articleUuid()).isEqualTo(articleUuid);
            assertThat(e.authorUuid()).isEqualTo(authorUuid);
            assertThat(e.authorNickname()).isEqualTo("n");
        });
    }

    @Test
    @DisplayName("in-flight 刪除訊息（仍含 authorId）可反序列化，authorUuid／seriesUuid 正確帶入")
    void inFlightDeletedJson_withRemovedField_stillDeserializes() {
        UUID articleUuid = UUID.randomUUID();
        UUID authorUuid = UUID.randomUUID();
        UUID seriesUuid = UUID.randomUUID();
        String inFlight = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"articleUuid\":\"" + articleUuid
                + "\",\"authorId\":3,\"categoryIds\":[],\"tagIds\":[],\"occurredAt\":1792800000.000000000,"
                + "\"authorUuid\":\"" + authorUuid + "\",\"seriesUuid\":\"" + seriesUuid + "\"}";

        Object restored = EventJsonSupport.receive(
                inFlight.getBytes(StandardCharsets.UTF_8), null, ArticleDeletedEvent.class);

        assertThat(restored).isInstanceOfSatisfying(ArticleDeletedEvent.class, e -> {
            assertThat(e.articleUuid()).isEqualTo(articleUuid);
            assertThat(e.authorUuid()).isEqualTo(authorUuid);
            assertThat(e.seriesUuid()).isEqualTo(seriesUuid);
        });
    }
}
