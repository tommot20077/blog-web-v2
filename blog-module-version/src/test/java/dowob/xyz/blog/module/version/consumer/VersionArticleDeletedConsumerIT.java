package dowob.xyz.blog.module.version.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.version.support.SeededVersionUsers;
import dowob.xyz.blog.module.version.support.VersionCleanupITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link VersionArticleDeletedConsumer} 整合測試（真實 Postgres；直接呼叫處理方法，不需要真 RabbitMQ）。
 *
 * <p>V28 起 {@code article_versions} 沒有指向 {@code articles} 的 FK，consumer 是「文章刪除 → 版本快照刪除」
 * 的唯一即時機制（每日校正排程為事件遺失時的第二道保險）。測試中文章仍在、僅收到事件，
 * 以隔離出 consumer 本身的效果。{@code user_preferences} 不受文章刪除影響（使用者只軟刪除）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("VersionArticleDeletedConsumer 整合測試")
class VersionArticleDeletedConsumerIT extends VersionCleanupITSupport {

    private static final long DELIVERY_TAG = 7L;

    @Autowired private VersionArticleDeletedConsumer consumer;

    private static ArticleDeletedEvent eventOf(UUID articleUuid) {
        return new ArticleDeletedEvent(UUID.randomUUID(), articleUuid, List.of(), List.of(), Instant.now(), null, null);
    }

    @Test
    @DisplayName("處理事件：該文章的 AUTO／MANUAL／PUBLISHED 快照全部消失，其他文章的保留，偏好設定不受影響")
    void onArticleDeleted_removesOnlyThatArticlesVersions() throws Exception {
        Article target = createArticle(ArticleStatus.DRAFT);
        Article other = createArticle(ArticleStatus.DRAFT);
        insertVersion(target.getUuid(), "AUTO");
        insertVersion(target.getUuid(), "MANUAL");
        insertVersion(target.getUuid(), "PUBLISHED");
        insertVersion(other.getUuid(), "AUTO");
        jdbc.update("INSERT INTO user_preferences (user_uuid, pref_key, pref_value) VALUES (?, 'version.auto.retain', '30')",
                SeededVersionUsers.USER1_UUID);
        Channel channel = Mockito.mock(Channel.class);

        consumer.onArticleDeleted(eventOf(target.getUuid()), channel, DELIVERY_TAG);

        assertThat(countVersions(target.getUuid())).isZero();
        assertThat(countVersions(other.getUuid())).isEqualTo(1);
        assertThat(countPreferences()).as("user_preferences 不新增連動").isEqualTo(1);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("同一事件處理兩次：不出錯，第二次為 no-op，其他資料不受影響")
    void onArticleDeleted_sameEventTwice_isIdempotent() throws Exception {
        Article target = createArticle(ArticleStatus.DRAFT);
        Article other = createArticle(ArticleStatus.DRAFT);
        insertVersion(target.getUuid(), "MANUAL");
        insertVersion(other.getUuid(), "MANUAL");
        ArticleDeletedEvent event = eventOf(target.getUuid());
        Channel channel = Mockito.mock(Channel.class);

        consumer.onArticleDeleted(event, channel, 1L);
        consumer.onArticleDeleted(event, channel, 2L);

        assertThat(countVersions(target.getUuid())).isZero();
        assertThat(countVersions(other.getUuid())).isEqualTo(1);
        verify(channel, times(1)).basicAck(1L, false);
        verify(channel, times(1)).basicAck(2L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("缺 articleUuid 的舊 payload：不刪除任何資料，仍 ack")
    void onArticleDeleted_nullArticleUuid_removesNothingAndAcks() throws Exception {
        Article article = createArticle(ArticleStatus.DRAFT);
        insertVersion(article.getUuid(), "MANUAL");
        Channel channel = Mockito.mock(Channel.class);

        consumer.onArticleDeleted(eventOf(null), channel, DELIVERY_TAG);

        assertThat(countVersions(article.getUuid())).isEqualTo(1);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("文章真的被刪除後收到事件：孤兒快照被清除（刪除連動與改造前『CASCADE』結果一致）")
    void onArticleDeleted_afterRealArticleDeletion_clearsOrphanVersions() throws Exception {
        Article article = createArticle(ArticleStatus.DRAFT);
        insertVersion(article.getUuid(), "AUTO");
        insertVersion(article.getUuid(), "MANUAL");
        articleRepo.delete(article);
        assertThat(countVersions(article.getUuid())).as("FK 已移除，DB 層不再 CASCADE").isEqualTo(2);
        Channel channel = Mockito.mock(Channel.class);

        consumer.onArticleDeleted(eventOf(article.getUuid()), channel, DELIVERY_TAG);

        assertThat(countVersions(article.getUuid())).isZero();
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
    }
}
