package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleViewSubService")
class ArticleViewSubServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ArticleEventPublisher articleEventPublisher;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ArticleViewSubService articleViewSubService;

    @BeforeEach
    void setUp() {
        articleViewSubService = new ArticleViewSubService(stringRedisTemplate, articleEventPublisher);
    }

    @Test
    @DisplayName("PUBLISHED first visit writes view key with 5 minute TTL and publishes event")
    void recordView_publishedFirstVisit_publishesViewedEvent() {
        UUID articleUuid = UUID.randomUUID();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("view:" + articleUuid + ":127.0.0.1", "1", 5, TimeUnit.MINUTES))
                .thenReturn(true);

        articleViewSubService.recordView(articleUuid, ArticleStatus.PUBLISHED, "127.0.0.1");

        verify(valueOperations).setIfAbsent("view:" + articleUuid + ":127.0.0.1", "1", 5, TimeUnit.MINUTES);
        verify(articleEventPublisher).publishViewed(articleUuid);
    }

    @Test
    @DisplayName("DRAFT does not touch Redis or publish")
    void recordView_draft_doesNotTouchRedisOrPublisher() {
        articleViewSubService.recordView(UUID.randomUUID(), ArticleStatus.DRAFT, "127.0.0.1");

        verifyNoInteractions(stringRedisTemplate, articleEventPublisher);
    }

    @Test
    @DisplayName("PENDING_REVIEW does not touch Redis or publish")
    void recordView_pendingReview_doesNotTouchRedisOrPublisher() {
        articleViewSubService.recordView(UUID.randomUUID(), ArticleStatus.PENDING_REVIEW, "127.0.0.1");

        verifyNoInteractions(stringRedisTemplate, articleEventPublisher);
    }

    @Test
    @DisplayName("ARCHIVED does not touch Redis or publish")
    void recordView_archived_doesNotTouchRedisOrPublisher() {
        articleViewSubService.recordView(UUID.randomUUID(), ArticleStatus.ARCHIVED, "127.0.0.1");

        verifyNoInteractions(stringRedisTemplate, articleEventPublisher);
    }

    @Test
    @DisplayName("REJECTED does not touch Redis or publish")
    void recordView_rejected_doesNotTouchRedisOrPublisher() {
        articleViewSubService.recordView(UUID.randomUUID(), ArticleStatus.REJECTED, "127.0.0.1");

        verifyNoInteractions(stringRedisTemplate, articleEventPublisher);
    }

    @Test
    @DisplayName("PUBLISHED repeat visit writes no event when Redis key already exists")
    void recordView_publishedRepeatVisit_doesNotPublishViewedEvent() {
        UUID articleUuid = UUID.randomUUID();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("view:" + articleUuid + ":127.0.0.1", "1", 5, TimeUnit.MINUTES))
                .thenReturn(false);

        articleViewSubService.recordView(articleUuid, ArticleStatus.PUBLISHED, "127.0.0.1");

        verify(valueOperations).setIfAbsent("view:" + articleUuid + ":127.0.0.1", "1", 5, TimeUnit.MINUTES);
        verify(articleEventPublisher, never()).publishViewed(any());
    }
}
