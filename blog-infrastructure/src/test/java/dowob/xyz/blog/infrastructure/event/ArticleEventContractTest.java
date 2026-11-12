package dowob.xyz.blog.infrastructure.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleEventContract} 單元測試。
 *
 * <p>exchange 與 routing key 的字串值是 article 與各訂閱模組之間的線上協定（wire format），
 * 搬移到共用契約後<b>不得有任何變動</b>；本測試把這些值釘死，任何誤改都會在此失敗。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleEventContract 單元測試")
class ArticleEventContractTest {

    @Test
    @DisplayName("exchange 名稱維持 article.events")
    void exchange_shouldKeepWireValue() {
        assertThat(ArticleEventContract.EXCHANGE).isEqualTo("article.events");
    }

    @Test
    @DisplayName("各 routing key 字串值與搬移前完全一致")
    void routingKeys_shouldKeepWireValues() {
        assertThat(ArticleEventContract.ROUTING_KEY_PUBLISHED).isEqualTo("article.published");
        assertThat(ArticleEventContract.ROUTING_KEY_UPDATED).isEqualTo("article.updated");
        assertThat(ArticleEventContract.ROUTING_KEY_DELETED).isEqualTo("article.deleted");
        assertThat(ArticleEventContract.ROUTING_KEY_TAGGED).isEqualTo("article.tagged");
        assertThat(ArticleEventContract.ROUTING_KEY_CONTENT_CHANGED).isEqualTo("article.content.changed");
        assertThat(ArticleEventContract.ROUTING_KEY_ARCHIVED).isEqualTo("article.archived");
    }

    @Test
    @DisplayName("下架與刪除必須使用不同的 routing key（避免 series 計數多扣）")
    void archivedAndDeleted_shouldUseDifferentRoutingKeys() {
        assertThat(ArticleEventContract.ROUTING_KEY_ARCHIVED)
                .isNotEqualTo(ArticleEventContract.ROUTING_KEY_DELETED);
    }
}
