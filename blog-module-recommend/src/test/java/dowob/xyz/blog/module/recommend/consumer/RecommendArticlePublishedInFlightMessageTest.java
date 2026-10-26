package dowob.xyz.blog.module.recommend.consumer;

import dowob.xyz.blog.infrastructure.config.RabbitMqConfig;
import dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * recommend 模組訂閱 {@link ArticlePublishedEvent} 時的 in-flight 訊息相容性測試（M6-a）。
 *
 * <p>M6-a 從事件移除 Long 的 {@code authorId} 與登入識別符 {@code authorUsername}；升級當下佇列裡仍可能有
 * 含這兩個欄位的舊訊息。以正式的 {@link RabbitMqConfig#messageConverter()} 反序列化，必須成功且多出的欄位被忽略，
 * consumer 實際讀取的 {@code articleUuid} 仍正確帶入，否則推薦快取不會被清除。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("recommend 模組：ArticlePublishedEvent in-flight 舊訊息相容性")
class RecommendArticlePublishedInFlightMessageTest {

    /** 與正式環境相同的訊息轉換器 */
    private static final MessageConverter CONVERTER = new RabbitMqConfig().messageConverter();

    @Test
    @DisplayName("含已移除的 authorId／authorUsername 的舊訊息仍可反序列化，articleUuid 與 authorUuid 正確")
    void legacyMessageWithRemovedFields_stillDeserializes() {
        UUID articleUuid = UUID.randomUUID();
        UUID authorUuid = UUID.randomUUID();
        String legacy = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"articleUuid\":\"" + articleUuid
                + "\",\"authorId\":4,\"authorUuid\":\"" + authorUuid + "\",\"title\":\"t\","
                + "\"publishedAt\":[2026,10,24,12,0],\"slug\":\"s\",\"summary\":\"m\",\"contentText\":\"c\","
                + "\"authorUsername\":\"u\",\"authorNickname\":\"n\",\"tags\":[]}";
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setInferredArgumentType(ArticlePublishedEvent.class);

        Object restored = CONVERTER.fromMessage(new Message(legacy.getBytes(StandardCharsets.UTF_8), props));

        assertThat(restored).isInstanceOfSatisfying(ArticlePublishedEvent.class, e -> {
            assertThat(e.articleUuid()).isEqualTo(articleUuid);
            assertThat(e.authorUuid()).isEqualTo(authorUuid);
            assertThat(e.authorNickname()).isEqualTo("n");
        });
    }
}
