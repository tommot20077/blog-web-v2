package dowob.xyz.blog.module.search.listener.dto;

import dowob.xyz.blog.infrastructure.config.RabbitMqConfig;
import dowob.xyz.blog.infrastructure.event.ArticleArchivedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleUpdatedEvent;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 共用事件契約（blog-infrastructure）與 search 鏡像 DTO 的 JSON 相容性測試。
 *
 * <p>search 模組刻意不 import 事件類別，而是用 {@code listener/dto/*Message} 鏡像反序列化。
 * 事件新增 {@code eventId}／{@code authorUuid}／{@code seriesUuid} 等欄位後，
 * 鏡像 DTO 沒有對應欄位——這裡用正式的 {@link RabbitMqConfig#messageConverter()}
 * 驗證「多出來的欄位不會讓反序列化失敗」，且鏡像 DTO 已有的欄位值仍正確帶入。
 * 同時驗證發送端 {@code __TypeId__} header 寫舊類別全名時，仍依 listener 參數型別推斷。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("共用事件 ↔ search 鏡像 DTO 相容性")
class ArticleEventMirrorCompatibilityTest {

    /** 與正式環境相同的訊息轉換器 */
    private final MessageConverter converter = new RabbitMqConfig().messageConverter();

    private Object roundTrip(Object event, String legacyTypeId, Class<?> listenerParamType) {
        Message sent = converter.toMessage(event, new MessageProperties());
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("__TypeId__", legacyTypeId);
        props.setInferredArgumentType(listenerParamType);
        return converter.fromMessage(new Message(sent.getBody(), props));
    }

    @Test
    @DisplayName("ArticlePublishedEvent（含 eventId／authorUuid）可被 ArticlePublishedMessage 反序列化")
    void publishedEvent_shouldDeserializeIntoMirrorMessage() {
        UUID articleUuid = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();
        ArticlePublishedEvent event = new ArticlePublishedEvent(
                UUID.randomUUID(), articleUuid, 7L, UUID.randomUUID(), "標題",
                LocalDateTime.of(2026, 10, 24, 12, 0), "slug", "摘要", "純文字",
                "alice", "愛麗絲", List.of(new TagInfo(tagId, "Java", "java")));

        Object restored = roundTrip(event, "dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent",
                ArticlePublishedMessage.class);

        assertThat(restored).isInstanceOfSatisfying(ArticlePublishedMessage.class, m -> {
            assertThat(m.getArticleUuid()).isEqualTo(articleUuid);
            assertThat(m.getAuthorId()).isEqualTo(7L);
            assertThat(m.getTitle()).isEqualTo("標題");
            assertThat(m.getPublishedAt()).isEqualTo(LocalDateTime.of(2026, 10, 24, 12, 0));
            assertThat(m.getSlug()).isEqualTo("slug");
            assertThat(m.getContentText()).isEqualTo("純文字");
            assertThat(m.getAuthorUsername()).isEqualTo("alice");
            assertThat(m.getAuthorNickname()).isEqualTo("愛麗絲");
            assertThat(m.getTags()).singleElement().satisfies(t -> assertThat(t.getId()).isEqualTo(tagId));
        });
    }

    @Test
    @DisplayName("ArticleUpdatedEvent（舊 __TypeId__ 為 article 模組全名）可被 ArticlePublishedMessage 反序列化")
    void updatedEvent_withLegacyTypeId_shouldDeserializeIntoMirrorMessage() {
        UUID articleUuid = UUID.randomUUID();
        ArticleUpdatedEvent event = new ArticleUpdatedEvent(
                UUID.randomUUID(), articleUuid, 7L, UUID.randomUUID(), "新標題",
                LocalDateTime.of(2026, 10, 24, 12, 0), "slug", "摘要", "純文字",
                "alice", "愛麗絲", List.of());

        Object restored = roundTrip(event, "dowob.xyz.blog.module.article.event.ArticleUpdatedEvent",
                ArticlePublishedMessage.class);

        assertThat(restored).isInstanceOfSatisfying(ArticlePublishedMessage.class, m -> {
            assertThat(m.getArticleUuid()).isEqualTo(articleUuid);
            assertThat(m.getAuthorId()).isEqualTo(7L);
            assertThat(m.getTitle()).isEqualTo("新標題");
        });
    }

    @Test
    @DisplayName("ArticleDeletedEvent（含 authorUuid／seriesUuid）可被 ArticleDeletedMessage 反序列化")
    void deletedEvent_withLegacyTypeId_shouldDeserializeIntoMirrorMessage() {
        UUID articleUuid = UUID.randomUUID();
        ArticleDeletedEvent event = new ArticleDeletedEvent(
                UUID.randomUUID(), articleUuid, 1L, List.of(), List.of(), Instant.parse("2026-10-24T00:00:00Z"), UUID.randomUUID(), UUID.randomUUID());

        Object restored = roundTrip(event, "dowob.xyz.blog.module.article.event.ArticleDeletedEvent",
                ArticleDeletedMessage.class);

        assertThat(restored).isInstanceOfSatisfying(ArticleDeletedMessage.class,
                m -> assertThat(m.getArticleUuid()).isEqualTo(articleUuid));
    }

    @Test
    @DisplayName("ArticleArchivedEvent 可被 ArticleArchivedMessage 反序列化")
    void archivedEvent_withLegacyTypeId_shouldDeserializeIntoMirrorMessage() {
        UUID eventId = UUID.randomUUID();
        UUID articleUuid = UUID.randomUUID();
        ArticleArchivedEvent event = new ArticleArchivedEvent(eventId, articleUuid, Instant.parse("2026-10-24T00:00:00Z"));

        Object restored = roundTrip(event, "dowob.xyz.blog.module.article.event.ArticleArchivedEvent",
                ArticleArchivedMessage.class);

        assertThat(restored).isInstanceOfSatisfying(ArticleArchivedMessage.class, m -> {
            assertThat(m.getEventId()).isEqualTo(eventId);
            assertThat(m.getArticleUuid()).isEqualTo(articleUuid);
        });
    }
}
