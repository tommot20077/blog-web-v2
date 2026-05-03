package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.article.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * ArticleEventPublisher 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleEventPublisher 單元測試")
class ArticleEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private UserFacade userFacade;

    @Mock
    private ArticleMapper articleMapper;

    @InjectMocks
    private ArticleEventPublisher publisher;

    @Nested
    @DisplayName("publishDeleted")
    class PublishDeleted {

        @Test
        @DisplayName("正常：publishDeleted 帶 rich payload 應正確構造 ArticleDeletedEvent")
        void publishDeleted_richPayload_constructsEventCorrectly() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(1L);

            UUID tagUuid = UUID.randomUUID();
            UUID categoryUuid = UUID.randomUUID();

            publisher.publishDeleted(article, 200L, List.of(categoryUuid), List.of(tagUuid));

            ArgumentCaptor<ArticleDeletedEvent> captor = ArgumentCaptor.forClass(ArticleDeletedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_DELETED),
                    captor.capture()
            );
            ArticleDeletedEvent event = captor.getValue();
            assertThat(event.eventId()).isNotNull();
            assertThat(event.articleId()).isEqualTo(100L);
            assertThat(event.articleUuid()).isEqualTo(article.getUuid());
            assertThat(event.authorId()).isEqualTo(1L);
            assertThat(event.seriesId()).isEqualTo(200L);
            assertThat(event.categoryIds()).containsExactly(categoryUuid);
            assertThat(event.tagIds()).containsExactly(tagUuid);
            assertThat(event.occurredAt()).isNotNull();
        }

        @Test
        @DisplayName("邊界：seriesId 為 null 時 event.seriesId() 應為 null")
        void publishDeleted_withNullSeriesId_eventSeriesIdIsNull() {
            Article article = new Article();
            article.setId(10L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(5L);

            publisher.publishDeleted(article, null, List.of(), List.of());

            ArgumentCaptor<ArticleDeletedEvent> captor = ArgumentCaptor.forClass(ArticleDeletedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_DELETED),
                    captor.capture()
            );
            ArticleDeletedEvent event = captor.getValue();
            assertThat(event.seriesId()).isNull();
            assertThat(event.categoryIds()).isEmpty();
            assertThat(event.tagIds()).isEmpty();
        }

        @Test
        @DisplayName("防禦：categoryIds/tagIds 為 null 時應轉為 empty list")
        void publishDeleted_withNullLists_convertsToEmptyLists() {
            Article article = new Article();
            article.setId(20L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(3L);

            publisher.publishDeleted(article, null, null, null);

            ArgumentCaptor<ArticleDeletedEvent> captor = ArgumentCaptor.forClass(ArticleDeletedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_DELETED),
                    captor.capture()
            );
            ArticleDeletedEvent event = captor.getValue();
            assertThat(event.categoryIds()).isEmpty();
            assertThat(event.tagIds()).isEmpty();
        }
    }
}
