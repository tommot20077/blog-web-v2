package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.article.event.ArticleArchivedEvent;
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
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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

    @Nested
    @DisplayName("publishArchived")
    class PublishArchived {

        @Test
        @DisplayName("正常：publishArchived 應送往 article.events / article.archived，payload 帶 uuid 與 eventId")
        void publishArchived_sendsToArchivedRoutingKey() {
            Article article = new Article();
            article.setId(300L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(7L);

            publisher.publishArchived(article);

            ArgumentCaptor<ArticleArchivedEvent> captor = ArgumentCaptor.forClass(ArticleArchivedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleRabbitMqConfig.EXCHANGE),
                    eq(ArticleRabbitMqConfig.ROUTING_KEY_ARCHIVED),
                    captor.capture()
            );
            ArticleArchivedEvent event = captor.getValue();
            assertThat(event.eventId()).isNotNull();
            assertThat(event.articleUuid()).isEqualTo(article.getUuid());
            assertThat(event.occurredAt()).isNotNull();
        }

        @Test
        @DisplayName("契約：下架事件不得共用 article.deleted（該 key 另有 series 訂閱並遞減 article_count）")
        void publishArchived_doesNotReuseDeletedRoutingKey() {
            assertThat(ArticleRabbitMqConfig.ROUTING_KEY_ARCHIVED)
                    .isEqualTo("article.archived")
                    .isNotEqualTo(ArticleRabbitMqConfig.ROUTING_KEY_DELETED);
        }

        @Test
        @DisplayName("穩健性：MQ 發送失敗時不往外拋（best-effort，下架結果本身已 commit）")
        void publishArchived_whenMqThrows_doesNotPropagate() {
            Article article = new Article();
            article.setId(301L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(7L);

            doThrow(new RuntimeException("MQ down")).when(rabbitTemplate)
                    .convertAndSend(eq(ArticleRabbitMqConfig.EXCHANGE),
                            eq(ArticleRabbitMqConfig.ROUTING_KEY_ARCHIVED),
                            any(Object.class));

            assertThatCode(() -> publisher.publishArchived(article)).doesNotThrowAnyException();
        }

        /**
         * 下架是合規動作（法務／侵權撤下），且 ES 索引移除是唯一的撤下機制。
         * 發送失敗若只留 warn，維運不會察覺——「下架成功」的 200 與實際仍可被搜尋到並存。
         * 故失敗必須是 ERROR，且訊息要帶得出是哪一篇（articleUuid），才可被告警與人工補送。
         */
        @Test
        @DisplayName("可觀測性：MQ 發送失敗必須以 ERROR 記錄並帶 articleUuid")
        void publishArchived_whenMqThrows_logsErrorWithArticleUuid() {
            Article article = new Article();
            article.setId(302L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(7L);

            doThrow(new RuntimeException("MQ down")).when(rabbitTemplate)
                    .convertAndSend(eq(ArticleRabbitMqConfig.EXCHANGE),
                            eq(ArticleRabbitMqConfig.ROUTING_KEY_ARCHIVED),
                            any(Object.class));

            CapturingAppender appender = attachAppender();
            try {
                publisher.publishArchived(article);
            } finally {
                detachAppender(appender);
            }

            assertThat(appender.events).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getMessage().getFormattedMessage())
                        .contains(article.getUuid().toString());
            });
        }
    }

    /**
     * 掛上捕捉用 appender 以取得 {@link ArticleEventPublisher} 的日誌事件。
     *
     * @return 已啟動並掛載的 appender
     */
    private CapturingAppender attachAppender() {
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        ((Logger) LogManager.getLogger(ArticleEventPublisher.class)).addAppender(appender);
        return appender;
    }

    /**
     * 卸載測試用 appender，避免污染其他測試。
     *
     * @param appender 先前掛上的 appender
     */
    private void detachAppender(CapturingAppender appender) {
        ((Logger) LogManager.getLogger(ArticleEventPublisher.class)).removeAppender(appender);
        appender.stop();
    }

    /**
     * 捕捉日誌事件的測試用 appender（log4j2 為本專案的日誌實作，見 root pom 排除
     * spring-boot-starter-logging 並改用 log4j-slf4j2-impl）。
     */
    private static final class CapturingAppender extends AbstractAppender {

        /** 捕捉到的日誌事件 */
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("archive-test-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
