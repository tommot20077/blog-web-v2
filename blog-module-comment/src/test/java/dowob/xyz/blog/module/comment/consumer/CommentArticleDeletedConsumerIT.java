package dowob.xyz.blog.module.comment.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.support.CommentCleanupITSupport;
import dowob.xyz.blog.module.comment.support.SeededUsers;
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
 * {@link CommentArticleDeletedConsumer} 整合測試（真實 Postgres；直接呼叫處理方法，不需要真 RabbitMQ）。
 *
 * <p>V25 起 {@code comments} 沒有指向 {@code articles} 的 FK，consumer 是「文章刪除 → 留言刪除」的唯一即時機制
 * （每日校正排程為事件遺失時的第二道保險）。本測試驗證 consumer 的刪除語意：事件處理後該文章的
 * comments 與 comment_likes（經模組內 FK CASCADE）全部消失、其他文章的留言保留。
 * 測試中文章仍在、僅收到事件，以隔離出 consumer 本身的效果。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("CommentArticleDeletedConsumer 整合測試")
class CommentArticleDeletedConsumerIT extends CommentCleanupITSupport {

    private static final long DELIVERY_TAG = 7L;

    @Autowired private CommentArticleDeletedConsumer consumer;

    private static ArticleDeletedEvent eventOf(UUID articleUuid) {
        return new ArticleDeletedEvent(UUID.randomUUID(), null, articleUuid, null, null,
                List.of(), List.of(), Instant.now(), null, null);
    }

    @Test
    @DisplayName("處理事件：該文章的留言（含回覆）與留言讚全部消失，其他文章的留言保留")
    void onArticleDeleted_removesOnlyThatArticlesCommentsAndLikes() throws Exception {
        Article target = createArticle(ArticleStatus.PUBLISHED);
        Article other = createArticle(ArticleStatus.PUBLISHED);
        Comment top = createComment(target, null);
        Comment reply = createComment(target, top.getId());
        createLike(top.getId(), SeededUsers.USER_UUID);
        createLike(reply.getId(), SeededUsers.OTHER_USER_UUID);
        Comment otherComment = createComment(other, null);
        createLike(otherComment.getId(), SeededUsers.USER_UUID);
        Channel channel = Mockito.mock(Channel.class);

        consumer.onArticleDeleted(eventOf(target.getUuid()), channel, DELIVERY_TAG);

        assertThat(countComments(target.getUuid())).isZero();
        assertThat(countComments(other.getUuid())).isEqualTo(1);
        assertThat(countAllLikes()).as("只剩其他文章那則留言的讚").isEqualTo(1);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("同一事件處理兩次：不出錯，第二次為 no-op，其他資料不受影響")
    void onArticleDeleted_sameEventTwice_isIdempotent() throws Exception {
        Article target = createArticle(ArticleStatus.PUBLISHED);
        Article other = createArticle(ArticleStatus.PUBLISHED);
        createComment(target, null);
        createComment(other, null);
        ArticleDeletedEvent event = eventOf(target.getUuid());
        Channel channel = Mockito.mock(Channel.class);

        consumer.onArticleDeleted(event, channel, 1L);
        consumer.onArticleDeleted(event, channel, 2L);

        assertThat(countComments(target.getUuid())).isZero();
        assertThat(countComments(other.getUuid())).isEqualTo(1);
        verify(channel, times(1)).basicAck(1L, false);
        verify(channel, times(1)).basicAck(2L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }
}
