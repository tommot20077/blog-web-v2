package dowob.xyz.blog.module.version.consumer;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.version.support.VersionCleanupITSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VersionUserDeletedConsumer} 整合測試（真實 Postgres；直接呼叫處理方法，不需要真 RabbitMQ）：
 * 使用者刪帳後其 {@code user_preferences} 被清除，他人的偏好不動；文章版本快照不受影響
 * （已發布文章的版本歷史隨文章保留，作者顯示經 users 列匿名化）（DATA-02）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("VersionUserDeletedConsumer 整合測試")
class VersionUserDeletedConsumerIT extends VersionCleanupITSupport {

    @Autowired private VersionUserDeletedConsumer consumer;

    private static UserDeletedEvent eventOf(UUID userUuid) {
        return new UserDeletedEvent(UUID.randomUUID(), userUuid, Instant.now());
    }

    /** 清掉本測試寫入的偏好設定。 */
    @AfterEach
    void cleanUpPreferences() {
        jdbc.update("DELETE FROM user_preferences");
    }

    private void insertPreference(UUID userUuid, String key) {
        jdbc.update("INSERT INTO user_preferences (user_uuid, pref_key, pref_value) VALUES (?, ?, '30')", userUuid, key);
    }

    private int preferencesOf(UUID userUuid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_preferences WHERE user_uuid = ?", Integer.class, userUuid);
    }

    @Test
    @DisplayName("處理事件：該使用者的所有偏好設定消失，他人的保留")
    void onUserDeleted_removesOnlyThatUsersPreferences() throws Exception {
        UUID deletedUser = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        insertPreference(deletedUser, "version.auto.retain");
        insertPreference(deletedUser, "draft.auto_save_interval");
        insertPreference(bystander, "version.auto.retain");

        consumer.onUserDeleted(eventOf(deletedUser));

        assertThat(preferencesOf(deletedUser)).isZero();
        assertThat(preferencesOf(bystander)).isEqualTo(1);
    }

    @Test
    @DisplayName("文章版本快照不受影響（已發布文章的版本歷史隨文章保留）")
    void onUserDeleted_keepsArticleVersions() throws Exception {
        UUID deletedUser = UUID.randomUUID();
        Article published = createArticle(ArticleStatus.PUBLISHED);
        insertVersion(published.getUuid(), "PUBLISHED");
        insertPreference(deletedUser, "version.auto.retain");

        consumer.onUserDeleted(eventOf(deletedUser));

        assertThat(countVersions(published.getUuid())).isEqualTo(1);
    }

    @Test
    @DisplayName("同一事件處理兩次：不出錯，第二次為 no-op，他人偏好不受影響")
    void onUserDeleted_sameEventTwice_isIdempotent() throws Exception {
        UUID deletedUser = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        insertPreference(deletedUser, "version.auto.retain");
        insertPreference(bystander, "version.auto.retain");
        UserDeletedEvent event = eventOf(deletedUser);

        consumer.onUserDeleted(event);
        consumer.onUserDeleted(event);

        assertThat(preferencesOf(deletedUser)).isZero();
        assertThat(preferencesOf(bystander)).isEqualTo(1);
    }

    @Test
    @DisplayName("缺 userUuid 的舊 payload：不刪除任何資料，仍正常返回")
    void onUserDeleted_nullUserUuid_removesNothing() throws Exception {
        UUID user = UUID.randomUUID();
        insertPreference(user, "version.auto.retain");

        consumer.onUserDeleted(eventOf(null));

        assertThat(preferencesOf(user)).isEqualTo(1);
    }
}
