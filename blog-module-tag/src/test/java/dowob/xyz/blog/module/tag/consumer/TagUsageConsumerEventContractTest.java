package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.event.ArticleTagEvent;
import dowob.xyz.blog.module.tag.config.TagRabbitMqConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * tag 模組的事件契約收斂守衛（findings ARCH-01）。
 *
 * <p>ArticleTagEvent 原本在 blog-infrastructure 與 tag 模組各有一份重複定義。
 * 收斂後 tag 模組只能使用 blog-infrastructure 的那一份，且 exchange／routing key 與共用契約一致。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("TagUsageConsumer 事件契約收斂")
class TagUsageConsumerEventContractTest {

    @Test
    @DisplayName("TagUsageConsumer 的 listener 參數型別為 blog-infrastructure 的 ArticleTagEvent")
    void handleArticleTagged_shouldAcceptSharedArticleTagEvent() {
        Method listener = Arrays.stream(TagUsageConsumer.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("handleArticleTagged"))
                .findFirst()
                .orElseThrow();

        assertThat(listener.getParameterTypes()[0]).isEqualTo(ArticleTagEvent.class);
    }

    @Test
    @DisplayName("tag 模組不再保留自己的 ArticleTagEvent 重複定義")
    void tagModule_shouldNotDefineItsOwnArticleTagEvent() {
        assertThatThrownBy(() -> Class.forName("dowob.xyz.blog.module.tag.event.ArticleTagEvent"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("tag 模組綁定的 exchange 與 routing key 等於共用契約")
    void tagConfig_shouldMatchSharedContract() {
        assertThat(TagRabbitMqConfig.ARTICLE_EVENTS_EXCHANGE).isEqualTo(ArticleEventContract.EXCHANGE);
        assertThat(TagRabbitMqConfig.ROUTING_KEY_ARTICLE_TAGGED).isEqualTo(ArticleEventContract.ROUTING_KEY_TAGGED);
    }
}
