package dowob.xyz.blog.infrastructure.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserEventContract} 單元測試。
 *
 * <p>exchange 與 routing key 的字串值是 user（producer）與 article／reading／version／tag／file／search
 * （帳號刪除連動的訂閱端）之間的線上協定，一旦有 queue 以這些值綁定就不得變動；本測試把值釘死。
 * {@code user.events} 與 user 模組內部既有的註冊／重設密碼事件共用同一個 exchange，
 * 該 exchange 的名稱因此必須維持 {@code user.events}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("UserEventContract 單元測試")
class UserEventContractTest {

    @Test
    @DisplayName("exchange 名稱為 user.events（與 user 模組既有宣告一致）")
    void exchange_shouldBeUserEvents() {
        assertThat(UserEventContract.EXCHANGE).isEqualTo("user.events");
    }

    @Test
    @DisplayName("帳號刪除事件的 routing key 為 user.deleted")
    void routingKeyDeleted_shouldBeUserDeleted() {
        assertThat(UserEventContract.ROUTING_KEY_DELETED).isEqualTo("user.deleted");
    }

    @Test
    @DisplayName("exchange 不與 article.events、series.events 相同（不同 producer 各自獨立）")
    void exchange_shouldDifferFromOtherProducers() {
        assertThat(UserEventContract.EXCHANGE)
                .isNotEqualTo(ArticleEventContract.EXCHANGE)
                .isNotEqualTo(SeriesEventContract.EXCHANGE);
    }
}
