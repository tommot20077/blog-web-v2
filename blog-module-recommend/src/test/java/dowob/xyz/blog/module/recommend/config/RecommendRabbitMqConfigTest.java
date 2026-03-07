package dowob.xyz.blog.module.recommend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RecommendRabbitMqConfig 單元測試
 *
 * <p>驗證 Queue 宣告的正確性。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("RecommendRabbitMqConfig 單元測試")
class RecommendRabbitMqConfigTest {

    /**
     * 受測物件
     */
    private final RecommendRabbitMqConfig config = new RecommendRabbitMqConfig();

    /**
     * 驗證文章發布 Queue 持久化且帶有 DLQ 參數
     */
    @Test
    @DisplayName("recommendArticlePublishedQueue 應為持久化且包含 DLQ args")
    void recommendArticlePublishedQueue_isDurable_withDlqArgs() {
        Queue queue = config.recommendArticlePublishedQueue();

        assertThat(queue.getName()).isEqualTo(RecommendRabbitMqConfig.QUEUE_RECOMMEND_ARTICLE_PUBLISHED);
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "blog.dlq");
    }

}
