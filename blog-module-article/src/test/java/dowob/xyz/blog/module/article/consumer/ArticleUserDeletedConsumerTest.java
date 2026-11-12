package dowob.xyz.blog.module.article.consumer;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.article.mapper.ArticleAuthorCleanupMapper;
import dowob.xyz.blog.module.article.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleUserDeletedConsumer} 單元測試（DATA-02）。
 *
 * <p>{@link ConsumerExecutor} 使用真實實例（非 Mock），ack／nack 與日誌走真實路徑。
 * 刪除一律走既有的 {@link ArticleService#deleteArticle}，讓 {@code ArticleDeletedEvent} 的連動自然生效。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleUserDeletedConsumer")
class ArticleUserDeletedConsumerTest {

    @Mock private ArticleAuthorCleanupMapper cleanupMapper;
    @Mock private ArticleService articleService;

    private ArticleUserDeletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ArticleUserDeletedConsumer(cleanupMapper, articleService, new ConsumerExecutor());
    }

    private static UserDeletedEvent event(UUID userUuid) {
        return new UserDeletedEvent(UUID.randomUUID(), userUuid, Instant.now());
    }

    @Test
    @DisplayName("只查草稿、待審、被駁回三種狀態（已發布與已下架文章保留）")
    void onUserDeleted_queriesOnlyRemovableStatuses() throws Exception {
        UUID userUuid = UUID.randomUUID();
        when(cleanupMapper.findUuidsByAuthorAndStatuses(any(), anyList())).thenReturn(List.of());

        consumer.onUserDeleted(event(userUuid));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> statuses = ArgumentCaptor.forClass(List.class);
        verify(cleanupMapper).findUuidsByAuthorAndStatuses(org.mockito.ArgumentMatchers.eq(userUuid), statuses.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(
                ArticleStatus.DRAFT.name(), ArticleStatus.PENDING_REVIEW.name(), ArticleStatus.REJECTED.name());
    }

    @Test
    @DisplayName("每篇可移除文章都走既有刪除流程（以作者本人身分），然後正常返回")
    void onUserDeleted_deletesEachRemovableArticleThroughArticleService() throws Exception {
        UUID userUuid = UUID.randomUUID();
        UUID draft = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        when(cleanupMapper.findUuidsByAuthorAndStatuses(any(), anyList()))
                .thenReturn(List.of(draft.toString(), pending.toString()));

        consumer.onUserDeleted(event(userUuid));

        verify(articleService).deleteArticle(userUuid, Role.USER, draft);
        verify(articleService).deleteArticle(userUuid, Role.USER, pending);
    }

    @Test
    @DisplayName("沒有可移除的文章：不呼叫刪除、正常返回（重送不出錯）")
    void onUserDeleted_noRemovableArticles_doesNothing() throws Exception {
        when(cleanupMapper.findUuidsByAuthorAndStatuses(any(), anyList())).thenReturn(List.of());

        consumer.onUserDeleted(event(UUID.randomUUID()));

        verifyNoInteractions(articleService);
    }

    @Test
    @DisplayName("某篇已被併發刪除（ARTICLE_NOT_FOUND）：視為已完成，繼續處理其餘文章，不往外拋")
    void onUserDeleted_articleAlreadyGone_continuesWithoutThrowing() throws Exception {
        UUID userUuid = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID remaining = UUID.randomUUID();
        when(cleanupMapper.findUuidsByAuthorAndStatuses(any(), anyList()))
                .thenReturn(List.of(gone.toString(), remaining.toString()));
        doThrow(new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND))
                .when(articleService).deleteArticle(userUuid, Role.USER, gone);

        consumer.onUserDeleted(event(userUuid));

        verify(articleService).deleteArticle(userUuid, Role.USER, remaining);
    }

    @Test
    @DisplayName("某篇刪除失敗：其餘文章仍會嘗試，最後把例外往外拋（交由容器重試，重試只會處理剩下的）")
    void onUserDeleted_oneDeletionFails_attemptsAllThenThrows() throws Exception {
        UUID userUuid = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(cleanupMapper.findUuidsByAuthorAndStatuses(any(), anyList()))
                .thenReturn(List.of(broken.toString(), healthy.toString()));
        doThrow(new IllegalStateException("DB down")).when(articleService).deleteArticle(userUuid, Role.USER, broken);

        assertThatThrownBy(() -> consumer.onUserDeleted(event(userUuid)))
                .isInstanceOf(IllegalStateException.class).hasMessage("DB down");

        verify(articleService).deleteArticle(userUuid, Role.USER, healthy);
    }

    @Test
    @DisplayName("缺 userUuid 的 payload：不查詢、不刪除、仍正常返回（避免無限重送）")
    void onUserDeleted_nullUserUuid_doesNothing() throws Exception {
        consumer.onUserDeleted(event(null));

        verifyNoInteractions(cleanupMapper, articleService);
        verify(articleService, never()).deleteArticle(any(), any(), any());
    }
}
