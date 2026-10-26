package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.infrastructure.event.ArticleArchivedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleTagEvent;
import dowob.xyz.blog.infrastructure.event.ArticleUpdatedEvent;
import dowob.xyz.blog.infrastructure.event.TagInfo;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
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

    @Mock
    private ArticleIdentityResolver identityResolver;

    @InjectMocks
    private ArticleEventPublisher publisher;

    @Nested
    @DisplayName("publishContentChanged")
    class PublishContentChanged {

        @Test
        @DisplayName("正常：事件帶新的 eventId、articleUuid 與 authorUuid（Long 過渡欄位已移除）")
        void publishContentChanged_shouldFillEventIdAndAuthorUuid() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(1L);
            UUID authorUuid = UUID.randomUUID();
            when(identityResolver.resolveAuthorUuids(anyCollection())).thenReturn(Map.of(1L, authorUuid));

            publisher.publishContentChanged(article, ArticleContentChangedEvent.Action.SAVED);

            ArgumentCaptor<ArticleContentChangedEvent> captor =
                    ArgumentCaptor.forClass(ArticleContentChangedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE),
                    eq(ArticleEventContract.ROUTING_KEY_CONTENT_CHANGED),
                    captor.capture());
            ArticleContentChangedEvent event = captor.getValue();
            assertThat(event.eventId()).isNotNull();
            assertThat(event.articleUuid()).isEqualTo(article.getUuid());
            assertThat(event.authorUuid()).isEqualTo(authorUuid);
            assertThat(event.action()).isEqualTo(ArticleContentChangedEvent.Action.SAVED);
        }

        @Test
        @DisplayName("事件序列化後不含 articleId／authorId（version 已是最後一個讀取者，發送端不再填 Long 欄位）")
        void publishContentChanged_payloadHasNoLongIdentityFields() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(1L);
            when(identityResolver.resolveAuthorUuids(anyCollection())).thenReturn(Map.of(1L, UUID.randomUUID()));

            publisher.publishContentChanged(article, ArticleContentChangedEvent.Action.SAVED);

            ArgumentCaptor<ArticleContentChangedEvent> captor =
                    ArgumentCaptor.forClass(ArticleContentChangedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE),
                    eq(ArticleEventContract.ROUTING_KEY_CONTENT_CHANGED),
                    captor.capture());
            Map<String, Object> payload = new com.fasterxml.jackson.databind.ObjectMapper()
                    .findAndRegisterModules()
                    .convertValue(captor.getValue(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
            assertThat(payload.keySet())
                    .containsExactlyInAnyOrder("eventId", "articleUuid", "authorUuid", "action", "occurredAt");
        }

        @Test
        @DisplayName("防禦：authorUuid 查詢失敗不得阻擋事件發送（欄位為 null）")
        void publishContentChanged_whenUuidLookupFails_shouldStillPublish() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(1L);
            when(identityResolver.resolveAuthorUuids(anyCollection())).thenThrow(new IllegalStateException("db down"));

            publisher.publishContentChanged(article, ArticleContentChangedEvent.Action.PUBLISHED);

            ArgumentCaptor<ArticleContentChangedEvent> captor =
                    ArgumentCaptor.forClass(ArticleContentChangedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE),
                    eq(ArticleEventContract.ROUTING_KEY_CONTENT_CHANGED),
                    captor.capture());
            assertThat(captor.getValue().authorUuid()).isNull();
        }
    }

    @Nested
    @DisplayName("publishUpdated / publishPublished")
    class PublishUpdatedAndPublished {

        private Article publishedArticle() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(1L);
            article.setTitle("標題");
            article.setSlug("slug");
            article.setSummary("摘要");
            article.setContent("# 內容");
            article.setPublishedAt(LocalDateTime.of(2026, 10, 24, 12, 0));
            return article;
        }

        @Test
        @DisplayName("publishUpdated：事件帶新的 eventId 與 authorUuid，並保留 authorId 過渡欄位")
        void publishUpdated_shouldFillEventIdAndAuthorUuid() {
            Article article = publishedArticle();
            UUID authorUuid = UUID.randomUUID();
            when(articleMapper.findTagsByArticleUuid(article.getUuid()))
                    .thenReturn(List.of(new TagInfo(UUID.randomUUID(), "Java", "java")));
            when(userFacade.getUserUsernameById(1L)).thenReturn(Optional.of("alice"));
            when(userFacade.getUserNicknameById(1L)).thenReturn(Optional.of("愛麗絲"));
            when(identityResolver.resolveAuthorUuids(anyCollection())).thenReturn(Map.of(1L, authorUuid));

            publisher.publishUpdated(article);

            ArgumentCaptor<ArticleUpdatedEvent> captor = ArgumentCaptor.forClass(ArticleUpdatedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_UPDATED), captor.capture());
            ArticleUpdatedEvent event = captor.getValue();
            assertThat(event.eventId()).isNotNull();
            assertThat(event.articleUuid()).isEqualTo(article.getUuid());
            assertThat(event.authorId()).isEqualTo(1L);
            assertThat(event.authorUuid()).isEqualTo(authorUuid);
            assertThat(event.authorUsername()).isEqualTo("alice");
            assertThat(event.tags()).hasSize(1);
        }

        @Test
        @DisplayName("publishPublished：事件帶新的 eventId 與 authorUuid，並保留 authorId 過渡欄位")
        void publishPublished_shouldFillEventIdAndAuthorUuid() {
            Article article = publishedArticle();
            UUID authorUuid = UUID.randomUUID();
            when(userFacade.getUserUsernameById(1L)).thenReturn(Optional.of("alice"));
            when(userFacade.getUserNicknameById(1L)).thenReturn(Optional.of("愛麗絲"));
            when(identityResolver.resolveAuthorUuids(anyCollection())).thenReturn(Map.of(1L, authorUuid));

            publisher.publishPublished(article, List.of());

            ArgumentCaptor<ArticlePublishedEvent> captor = ArgumentCaptor.forClass(ArticlePublishedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_PUBLISHED), captor.capture());
            ArticlePublishedEvent event = captor.getValue();
            assertThat(event.eventId()).isNotNull();
            assertThat(event.articleUuid()).isEqualTo(article.getUuid());
            assertThat(event.authorId()).isEqualTo(1L);
            assertThat(event.authorUuid()).isEqualTo(authorUuid);
            assertThat(event.contentText()).isEqualTo("內容");
        }

        @Test
        @DisplayName("防禦：authorUuid 查詢失敗不得阻擋 publishPublished（欄位為 null，事件仍送出）")
        void publishPublished_whenUuidLookupFails_shouldStillPublish() {
            Article article = publishedArticle();
            when(userFacade.getUserUsernameById(1L)).thenReturn(Optional.of("alice"));
            when(userFacade.getUserNicknameById(1L)).thenReturn(Optional.of("愛麗絲"));
            when(identityResolver.resolveAuthorUuids(anyCollection())).thenThrow(new IllegalStateException("db down"));

            publisher.publishPublished(article, List.of());

            ArgumentCaptor<ArticlePublishedEvent> captor = ArgumentCaptor.forClass(ArticlePublishedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_PUBLISHED), captor.capture());
            assertThat(captor.getValue().authorUuid()).isNull();
            assertThat(captor.getValue().authorId()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("publishTagged")
    class PublishTagged {

        @Test
        @DisplayName("正常：以共用契約的 exchange／routing key 發送 ArticleTagEvent（帶 eventId、文章 UUID、標籤 UUID）")
        void publishTagged_shouldSendSharedArticleTagEvent() {
            Article article = new Article();
            article.setUuid(UUID.randomUUID());
            UUID tagId = UUID.randomUUID();

            publisher.publishTagged(article, List.of(tagId));

            ArgumentCaptor<ArticleTagEvent> captor = ArgumentCaptor.forClass(ArticleTagEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_TAGGED), captor.capture());
            assertThat(captor.getValue().eventId()).isNotNull();
            assertThat(captor.getValue().articleId()).isEqualTo(article.getUuid());
            assertThat(captor.getValue().tagIds()).containsExactly(tagId);
        }
    }

    @Nested
    @DisplayName("uuid 過渡欄位的查詢次數與失敗處理")
    class IdentityResolution {

        private Article publishedArticle() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(1L);
            article.setContent("內容");
            return article;
        }

        private List<String> warnMessagesWhile(Runnable action) {
            Logger logger = (Logger) LogManager.getLogger(ArticleEventPublisher.class);
            Level original = logger.getLevel();
            logger.setLevel(Level.WARN);
            CapturingAppender appender = attachAppender();
            try {
                action.run();
                return appender.events.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .map(e -> e.getMessage().getFormattedMessage())
                        .toList();
            } finally {
                detachAppender(appender);
                logger.setLevel(original);
            }
        }

        @Test
        @DisplayName("publishUpdated：authorUuid 查詢失敗不得阻擋事件發送（欄位為 null），warn 帶事件類型與 articleUuid")
        void publishUpdated_whenUuidLookupFails_shouldStillPublishAndWarnWithContext() {
            Article article = publishedArticle();
            when(userFacade.getUserUsernameById(1L)).thenReturn(Optional.of("alice"));
            when(userFacade.getUserNicknameById(1L)).thenReturn(Optional.of("愛麗絲"));
            when(identityResolver.resolveAuthorUuids(anyCollection())).thenThrow(new IllegalStateException("db down"));

            List<String> warns = warnMessagesWhile(() -> publisher.publishUpdated(article));

            ArgumentCaptor<ArticleUpdatedEvent> captor = ArgumentCaptor.forClass(ArticleUpdatedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_UPDATED), captor.capture());
            assertThat(captor.getValue().authorUuid()).isNull();
            assertThat(captor.getValue().authorId()).isEqualTo(1L);
            assertThat(warns).anySatisfy(m -> assertThat(m)
                    .contains("ArticleUpdatedEvent").contains(article.getUuid().toString()));
        }

        @Test
        @DisplayName("publishDeleted：series 查詢失敗的 warn 帶事件類型與 articleUuid")
        void publishDeleted_whenSeriesLookupFails_warnShouldCarryContext() {
            Article article = publishedArticle();
            when(identityResolver.resolveSeriesUuids(anyCollection())).thenThrow(new IllegalStateException("series down"));

            List<String> warns = warnMessagesWhile(() -> publisher.publishDeleted(article, 200L, List.of(), List.of()));

            assertThat(warns).anySatisfy(m -> assertThat(m)
                    .contains("ArticleDeletedEvent").contains(article.getUuid().toString()));
        }

        @Test
        @DisplayName("publishUpdated：作者查詢恰 1 次，不查 series")
        void publishUpdated_resolvesAuthorExactlyOnce() {
            Article article = publishedArticle();
            publisher.publishUpdated(article);

            verify(identityResolver, times(1)).resolveAuthorUuids(anyCollection());
            verify(identityResolver, never()).resolveSeriesUuids(anyCollection());
        }

        @Test
        @DisplayName("publishPublished：作者查詢恰 1 次，不查 series")
        void publishPublished_resolvesAuthorExactlyOnce() {
            Article article = publishedArticle();
            publisher.publishPublished(article, List.of());

            verify(identityResolver, times(1)).resolveAuthorUuids(anyCollection());
            verify(identityResolver, never()).resolveSeriesUuids(anyCollection());
        }

        @Test
        @DisplayName("publishContentChanged：作者查詢恰 1 次，不查 series")
        void publishContentChanged_resolvesAuthorExactlyOnce() {
            Article article = publishedArticle();
            publisher.publishContentChanged(article, ArticleContentChangedEvent.Action.SAVED);

            verify(identityResolver, times(1)).resolveAuthorUuids(anyCollection());
            verify(identityResolver, never()).resolveSeriesUuids(anyCollection());
        }

        @Test
        @DisplayName("publishDeleted（有系列）：作者 1 次 ＋ 系列 1 次")
        void publishDeleted_withSeries_resolvesAuthorOnceAndSeriesOnce() {
            Article article = publishedArticle();
            publisher.publishDeleted(article, 200L, List.of(), List.of());

            verify(identityResolver, times(1)).resolveAuthorUuids(anyCollection());
            verify(identityResolver, times(1)).resolveSeriesUuids(anyCollection());
        }

        @Test
        @DisplayName("publishDeleted（無系列）：作者 1 次，系列 0 次")
        void publishDeleted_withoutSeries_resolvesAuthorOnceAndNoSeries() {
            Article article = publishedArticle();
            publisher.publishDeleted(article, null, List.of(), List.of());

            verify(identityResolver, times(1)).resolveAuthorUuids(anyCollection());
            verify(identityResolver, never()).resolveSeriesUuids(anyCollection());
        }
    }

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
                    eq(ArticleEventContract.EXCHANGE),
                    eq(ArticleEventContract.ROUTING_KEY_DELETED),
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
                    eq(ArticleEventContract.EXCHANGE),
                    eq(ArticleEventContract.ROUTING_KEY_DELETED),
                    captor.capture()
            );
            ArticleDeletedEvent event = captor.getValue();
            assertThat(event.seriesId()).isNull();
            assertThat(event.categoryIds()).isEmpty();
            assertThat(event.tagIds()).isEmpty();
        }

        @Test
        @DisplayName("正常：事件帶 authorUuid 與 seriesUuid（經 ArticleDataAssembler 批次取得，不 JOIN series）")
        void publishDeleted_shouldFillAuthorUuidAndSeriesUuid() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(1L);
            UUID authorUuid = UUID.randomUUID();
            UUID seriesUuid = UUID.randomUUID();
            when(identityResolver.resolveAuthorUuids(anyCollection())).thenReturn(Map.of(1L, authorUuid));
            when(identityResolver.resolveSeriesUuids(anyCollection())).thenReturn(Map.of(200L, seriesUuid));

            publisher.publishDeleted(article, 200L, List.of(), List.of());

            ArgumentCaptor<ArticleDeletedEvent> captor = ArgumentCaptor.forClass(ArticleDeletedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_DELETED), captor.capture());
            ArticleDeletedEvent event = captor.getValue();
            assertThat(event.authorId()).isEqualTo(1L);
            assertThat(event.authorUuid()).isEqualTo(authorUuid);
            assertThat(event.seriesId()).isEqualTo(200L);
            assertThat(event.seriesUuid()).isEqualTo(seriesUuid);
        }

        @Test
        @DisplayName("邊界：seriesId 為 null 時不查 series，seriesUuid 為 null")
        void publishDeleted_withoutSeries_shouldNotResolveSeriesUuid() {
            Article article = new Article();
            article.setId(10L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(5L);

            publisher.publishDeleted(article, null, List.of(), List.of());

            ArgumentCaptor<ArticleDeletedEvent> captor = ArgumentCaptor.forClass(ArticleDeletedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_DELETED), captor.capture());
            assertThat(captor.getValue().seriesUuid()).isNull();
            verify(identityResolver, never()).resolveSeriesUuids(anyCollection());
        }

        @Test
        @DisplayName("防禦：uuid 過渡欄位查詢失敗不得阻擋事件發送（欄位為 null，事件仍送出）")
        void publishDeleted_whenUuidLookupFails_shouldStillPublishWithNullUuids() {
            Article article = new Article();
            article.setId(30L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(1L);
            when(identityResolver.resolveAuthorUuids(anyCollection())).thenThrow(new IllegalStateException("db down"));
            when(identityResolver.resolveSeriesUuids(anyCollection())).thenThrow(new IllegalStateException("series down"));

            publisher.publishDeleted(article, 200L, List.of(), List.of());

            ArgumentCaptor<ArticleDeletedEvent> captor = ArgumentCaptor.forClass(ArticleDeletedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_DELETED), captor.capture());
            assertThat(captor.getValue().authorUuid()).isNull();
            assertThat(captor.getValue().seriesUuid()).isNull();
            assertThat(captor.getValue().authorId()).isEqualTo(1L);
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
                    eq(ArticleEventContract.EXCHANGE),
                    eq(ArticleEventContract.ROUTING_KEY_DELETED),
                    captor.capture()
            );
            ArticleDeletedEvent event = captor.getValue();
            assertThat(event.categoryIds()).isEmpty();
            assertThat(event.tagIds()).isEmpty();
        }

        /**
         * 與 publishArchived 同一條判準：刪除事件是「把文章從 ES 索引移除」的唯一觸發，
         * 送不出去就會留下一筆搜尋得到、點進去 404 的幽靈 document，且 document 本身
         * 還帶著 title / summary / content。這種「端點回 200 刪除成功」與「文章仍搜尋得到」
         * 並存的狀態需要人介入補送，warn 不足以告警。
         */
        @Test
        @DisplayName("可觀測性：MQ 發送失敗必須以 ERROR 記錄並帶 articleUuid")
        void publishDeleted_whenMqThrows_logsErrorWithArticleUuid() {
            Article article = new Article();
            article.setId(21L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(3L);

            doThrow(new RuntimeException("MQ down")).when(rabbitTemplate)
                    .convertAndSend(eq(ArticleEventContract.EXCHANGE),
                            eq(ArticleEventContract.ROUTING_KEY_DELETED),
                            any(Object.class));

            CapturingAppender appender = attachAppender();
            try {
                publisher.publishDeleted(article, null, null, null);
            } finally {
                detachAppender(appender);
            }

            assertThat(appender.events).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getMessage().getFormattedMessage())
                        .contains(article.getUuid().toString());
            });
        }

        @Test
        @DisplayName("穩健性：MQ 發送失敗時不往外拋（best-effort，刪除結果本身已 commit）")
        void publishDeleted_whenMqThrows_doesNotPropagate() {
            Article article = new Article();
            article.setId(22L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(3L);

            doThrow(new RuntimeException("MQ down")).when(rabbitTemplate)
                    .convertAndSend(eq(ArticleEventContract.EXCHANGE),
                            eq(ArticleEventContract.ROUTING_KEY_DELETED),
                            any(Object.class));

            assertThatCode(() -> publisher.publishDeleted(article, null, null, null))
                    .doesNotThrowAnyException();
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
                    eq(ArticleEventContract.EXCHANGE),
                    eq(ArticleEventContract.ROUTING_KEY_ARCHIVED),
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
            assertThat(ArticleEventContract.ROUTING_KEY_ARCHIVED)
                    .isEqualTo("article.archived")
                    .isNotEqualTo(ArticleEventContract.ROUTING_KEY_DELETED);
        }

        @Test
        @DisplayName("穩健性：MQ 發送失敗時不往外拋（best-effort，下架結果本身已 commit）")
        void publishArchived_whenMqThrows_doesNotPropagate() {
            Article article = new Article();
            article.setId(301L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorId(7L);

            doThrow(new RuntimeException("MQ down")).when(rabbitTemplate)
                    .convertAndSend(eq(ArticleEventContract.EXCHANGE),
                            eq(ArticleEventContract.ROUTING_KEY_ARCHIVED),
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
                    .convertAndSend(eq(ArticleEventContract.EXCHANGE),
                            eq(ArticleEventContract.ROUTING_KEY_ARCHIVED),
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
