package dowob.xyz.blog.module.comment.consumer;

import dowob.xyz.blog.infrastructure.config.RabbitMqConfig;
import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * comment 模組訂閱 {@link ArticleDeletedEvent} 時的 in-flight 訊息相容性測試（M6-a）。
 *
 * <p>M6-a 從事件移除 Long 的 {@code authorId}；升級當下佇列裡仍可能有舊格式的訊息
 * （含已移除的 {@code authorId}，以及更早 M4-b 移除的 {@code articleId}、{@code seriesId}）。
 * 以正式的 {@link RabbitMqConfig#messageConverter()} 反序列化，必須成功且多出的欄位被忽略，
 * consumer 實際讀取的 {@code articleUuid}／{@code authorUuid}／{@code seriesUuid} 仍正確帶入——
 * 否則這些訊息會被視為毒訊息而遺失刪除連動。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("comment 模組：ArticleDeletedEvent in-flight 舊訊息相容性")
class CommentArticleDeletedInFlightMessageTest {

    /** 與正式環境相同的訊息轉換器 */
    private static final MessageConverter CONVERTER = new RabbitMqConfig().messageConverter();

    @Test
    @DisplayName("含已移除的 authorId／articleId／seriesId 的舊訊息仍可反序列化，uuid 欄位正確")
    void legacyMessageWithRemovedLongFields_stillDeserializes() {
        UUID eventId = UUID.randomUUID();
        UUID articleUuid = UUID.randomUUID();
        UUID authorUuid = UUID.randomUUID();
        UUID seriesUuid = UUID.randomUUID();
        String legacy = "{\"eventId\":\"" + eventId + "\",\"articleId\":7,\"articleUuid\":\"" + articleUuid
                + "\",\"authorId\":3,\"seriesId\":9,\"categoryIds\":[],\"tagIds\":[],"
                + "\"occurredAt\":1792800000.000000000,\"authorUuid\":\"" + authorUuid
                + "\",\"seriesUuid\":\"" + seriesUuid + "\"}";
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setInferredArgumentType(ArticleDeletedEvent.class);

        Object restored = CONVERTER.fromMessage(new Message(legacy.getBytes(StandardCharsets.UTF_8), props));

        assertThat(restored).isInstanceOfSatisfying(ArticleDeletedEvent.class, e -> {
            assertThat(e.eventId()).isEqualTo(eventId);
            assertThat(e.articleUuid()).isEqualTo(articleUuid);
            assertThat(e.authorUuid()).isEqualTo(authorUuid);
            assertThat(e.seriesUuid()).isEqualTo(seriesUuid);
        });
    }
}
