package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.module.tag.support.TagCleanupITSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TagUserDeletedConsumer} 整合測試（真實 Postgres；直接呼叫處理方法，不需要真 RabbitMQ）：
 * 使用者刪帳後其 {@code user_tag_follows} 被清除，他人的追蹤與 {@code tags} 本身不動（DATA-02）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("TagUserDeletedConsumer 整合測試")
class TagUserDeletedConsumerIT extends TagCleanupITSupport {

    @Autowired private TagUserDeletedConsumer consumer;

    private static UserDeletedEvent eventOf(UUID userUuid) {
        return new UserDeletedEvent(UUID.randomUUID(), userUuid, Instant.now());
    }

    /** 清掉本測試寫入的追蹤紀錄。 */
    @AfterEach
    void cleanUpFollows() {
        jdbc.update("DELETE FROM user_tag_follows");
    }

    private void follow(UUID userUuid, UUID tagId) {
        jdbc.update("INSERT INTO user_tag_follows (user_id, tag_id) VALUES (?, ?)", userUuid, tagId);
    }

    private int followsOf(UUID userUuid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_tag_follows WHERE user_id = ?", Integer.class, userUuid);
    }

    @Test
    @DisplayName("處理事件：該使用者追蹤的所有標籤關聯消失，他人的追蹤與 tags 本身保留")
    void onUserDeleted_removesOnlyThatUsersFollows() throws Exception {
        UUID deletedUser = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        UUID tagA = insertTag("java", 0);
        UUID tagB = insertTag("spring", 0);
        follow(deletedUser, tagA);
        follow(deletedUser, tagB);
        follow(bystander, tagA);

        consumer.onUserDeleted(eventOf(deletedUser));

        assertThat(followsOf(deletedUser)).isZero();
        assertThat(followsOf(bystander)).isEqualTo(1);
        assertThat(countTags()).isEqualTo(2);
    }

    @Test
    @DisplayName("同一事件處理兩次：不出錯，第二次為 no-op，他人追蹤不受影響")
    void onUserDeleted_sameEventTwice_isIdempotent() throws Exception {
        UUID deletedUser = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        UUID tag = insertTag("java", 0);
        follow(deletedUser, tag);
        follow(bystander, tag);
        UserDeletedEvent event = eventOf(deletedUser);

        consumer.onUserDeleted(event);
        consumer.onUserDeleted(event);

        assertThat(followsOf(deletedUser)).isZero();
        assertThat(followsOf(bystander)).isEqualTo(1);
    }

    @Test
    @DisplayName("缺 userUuid 的舊 payload：不刪除任何資料，仍正常返回")
    void onUserDeleted_nullUserUuid_removesNothing() throws Exception {
        UUID user = UUID.randomUUID();
        follow(user, insertTag("java", 0));

        consumer.onUserDeleted(eventOf(null));

        assertThat(followsOf(user)).isEqualTo(1);
    }
}
