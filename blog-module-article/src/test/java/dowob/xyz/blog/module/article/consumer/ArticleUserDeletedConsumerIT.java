package dowob.xyz.blog.module.article.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.module.article.support.ArticleSeriesITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ArticleUserDeletedConsumer} 整合測試（真實 Postgres；直接呼叫處理方法，不需要真 RabbitMQ）。
 *
 * <p>驗證 DATA-02 的文章面：作者刪帳後，其草稿／待審／被駁回文章走既有刪除流程被刪除
 * （並因此發出 {@code ArticleDeletedEvent}，讓 comment／reading／tag／version／search 的既有連動生效），
 * 已發布與已下架文章保留（作者顯示因 users 列匿名化而自動變成匿名暱稱）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleUserDeletedConsumer 整合測試")
class ArticleUserDeletedConsumerIT extends ArticleSeriesITSupport {

    @Autowired private ArticleUserDeletedConsumer consumer;
    @Autowired private RabbitTemplate rabbitTemplate;

    private static UserDeletedEvent eventOf(UUID userUuid) {
        return new UserDeletedEvent(UUID.randomUUID(), userUuid, Instant.now());
    }

    /**
     * 以 SQL 寫入指定作者與狀態的文章。
     *
     * @param authorUuid 作者公開 UUID
     * @param status     文章狀態
     * @return 文章公開 UUID
     */
    private UUID insertArticleOf(UUID authorUuid, String status) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO articles (uuid, author_uuid, title, slug, content_md, status) "
                        + "VALUES (?, ?, ?, ?, '內容', ?)",
                uuid, authorUuid, "t-" + uuid, "slug-" + uuid, status);
        return uuid;
    }

    private boolean exists(UUID articleUuid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM articles WHERE uuid = ?", Integer.class, articleUuid) == 1;
    }

    @Test
    @DisplayName("處理事件：該作者的草稿、待審、被駁回文章被刪除；已發布與已下架文章保留")
    void onUserDeleted_removesDraftsPendingAndRejected_keepsPublishedAndArchived() throws Exception {
        UUID author = UUID.randomUUID();
        UUID draft = insertArticleOf(author, "DRAFT");
        UUID pending = insertArticleOf(author, "PENDING_REVIEW");
        UUID rejected = insertArticleOf(author, "REJECTED");
        UUID published = insertArticleOf(author, "PUBLISHED");
        UUID archived = insertArticleOf(author, "ARCHIVED");

        consumer.onUserDeleted(eventOf(author));

        assertThat(exists(draft)).isFalse();
        assertThat(exists(pending)).isFalse();
        assertThat(exists(rejected)).isFalse();
        assertThat(exists(published)).as("已發布文章保留").isTrue();
        assertThat(exists(archived)).as("已下架文章保留（可能涉及法務留存，不由刪帳連動銷毀）").isTrue();
    }

    @Test
    @DisplayName("處理事件：其他作者的文章（任何狀態）不受影響")
    void onUserDeleted_doesNotTouchOtherAuthorsArticles() throws Exception {
        UUID author = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        insertArticleOf(author, "DRAFT");
        UUID othersDraft = insertArticleOf(bystander, "DRAFT");
        UUID othersPending = insertArticleOf(bystander, "PENDING_REVIEW");

        consumer.onUserDeleted(eventOf(author));

        assertThat(exists(othersDraft)).isTrue();
        assertThat(exists(othersPending)).isTrue();
    }

    @Test
    @DisplayName("走既有刪除流程：每篇被刪除的文章都發出 ArticleDeletedEvent（帶作者 uuid），連動清理自然生效；保留的文章不發")
    void onUserDeleted_publishesArticleDeletedEventPerRemovedArticle() throws Exception {
        UUID author = UUID.randomUUID();
        UUID draft = insertArticleOf(author, "DRAFT");
        UUID rejected = insertArticleOf(author, "REJECTED");
        UUID published = insertArticleOf(author, "PUBLISHED");
        Mockito.clearInvocations(rabbitTemplate);

        consumer.onUserDeleted(eventOf(author));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, atLeast(2)).convertAndSend(
                eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_DELETED), payload.capture());
        List<ArticleDeletedEvent> deletedEvents = payload.getAllValues().stream()
                .filter(ArticleDeletedEvent.class::isInstance).map(ArticleDeletedEvent.class::cast).toList();
        assertThat(deletedEvents).extracting(ArticleDeletedEvent::articleUuid)
                .containsExactlyInAnyOrder(draft, rejected).doesNotContain(published);
        assertThat(deletedEvents).extracting(ArticleDeletedEvent::authorUuid).containsOnly(author);
    }

    @Test
    @DisplayName("同一事件處理兩次：不出錯，第二次為 no-op，保留的文章不受影響")
    void onUserDeleted_sameEventTwice_isIdempotent() throws Exception {
        UUID author = UUID.randomUUID();
        insertArticleOf(author, "DRAFT");
        UUID published = insertArticleOf(author, "PUBLISHED");
        UserDeletedEvent event = eventOf(author);

        consumer.onUserDeleted(event);
        Mockito.clearInvocations(rabbitTemplate);
        consumer.onUserDeleted(event);

        assertThat(exists(published)).isTrue();
        verify(rabbitTemplate, never()).convertAndSend(
                eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_DELETED), Mockito.any(Object.class));
    }

    @Test
    @DisplayName("缺 userUuid 的舊 payload：不刪除任何文章，仍正常返回")
    void onUserDeleted_nullUserUuid_removesNothing() throws Exception {
        UUID author = UUID.randomUUID();
        UUID draft = insertArticleOf(author, "DRAFT");

        consumer.onUserDeleted(eventOf(null));

        assertThat(exists(draft)).isTrue();
    }
}
