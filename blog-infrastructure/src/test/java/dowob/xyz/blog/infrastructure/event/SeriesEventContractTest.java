package dowob.xyz.blog.infrastructure.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SeriesEventContract} 單元測試。
 *
 * <p>exchange 與 routing key 的字串值是 series 與日後訂閱模組（M6 的 article）之間的線上協定，
 * 一旦有 queue 以這些值綁定就不得變動；本測試把值釘死。系列 exchange 與 {@code article.events} 刻意分開：
 * 兩者的 producer 不同模組，拆服務時各自獨立。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("SeriesEventContract 單元測試")
class SeriesEventContractTest {

    @Test
    @DisplayName("exchange 名稱為 series.events")
    void exchange_shouldBeSeriesEvents() {
        assertThat(SeriesEventContract.EXCHANGE).isEqualTo("series.events");
    }

    @Test
    @DisplayName("刪除事件的 routing key 為 series.deleted")
    void routingKeyDeleted_shouldBeSeriesDeleted() {
        assertThat(SeriesEventContract.ROUTING_KEY_DELETED).isEqualTo("series.deleted");
    }

    @Test
    @DisplayName("exchange 不與 article.events 相同（不同 producer 各自獨立）")
    void exchange_shouldDifferFromArticleEvents() {
        assertThat(SeriesEventContract.EXCHANGE).isNotEqualTo(ArticleEventContract.EXCHANGE);
    }
}
