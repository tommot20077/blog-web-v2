package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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

    /** 測試用作者公開 UUID（M6-a 起文章直接存 author_uuid，事件不再經查詢取得） */
    private static final UUID AUTHOR_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    /**
     * 建立指定作者的 UserDisplayInfo 回傳，模擬 {@code UserFacade.getDisplayInfoByUuids} 查得到作者暱稱。
     *
     * @param authorUuid 作者公開 UUID
     * @param nickname   作者暱稱
     * @return facade 回傳的 Map
     */
    private static Map<UUID, UserDisplayInfo> displayInfo(UUID authorUuid, String nickname) {
        return Map.of(authorUuid, new UserDisplayInfo(authorUuid, nickname, null));
    }

    @Nested
    @DisplayName("publishContentChanged")
    class PublishContentChanged {

        @Test
        @DisplayName("正常：事件帶新的 eventId、articleUuid 與直接取自文章的 authorUuid，且不呼叫 UserFacade")
        void publishContentChanged_shouldFillEventIdAndAuthorUuid() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorUuid(AUTHOR_UUID);

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
            assertThat(event.authorUuid()).isEqualTo(AUTHOR_UUID);
            assertThat(event.action()).isEqualTo(ArticleContentChangedEvent.Action.SAVED);
            verifyNoInteractions(userFacade);
        }

        @Test
        @DisplayName("事件序列化後不含 articleId／authorId（version 已是最後一個讀取者，發送端不再填 Long 欄位）")
        void publishContentChanged_payloadHasNoLongIdentityFields() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorUuid(AUTHOR_UUID);

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
    }

    @Nested
    @DisplayName("publishUpdated / publishPublished")
    class PublishUpdatedAndPublished {

        private Article publishedArticle() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorUuid(AUTHOR_UUID);
            article.setTitle("標題");
            article.setSlug("slug");
            article.setSummary("摘要");
            article.setContent("# 內容");
            article.setPublishedAt(LocalDateTime.of(2026, 10, 24, 12, 0));
            return article;
        }

        @Test
        @DisplayName("publishUpdated：事件帶新的 eventId、文章的 authorUuid 與 UUID 版 facade 查得的 authorNickname，且只查一次")
        void publishUpdated_shouldFillEventIdAuthorUuidAndNickname() {
            Article article = publishedArticle();
            when(articleMapper.findTagsByArticleUuid(article.getUuid()))
                    .thenReturn(List.of(new TagInfo(UUID.randomUUID(), "Java", "java")));
            when(userFacade.getDisplayInfoByUuids(List.of(AUTHOR_UUID))).thenReturn(displayInfo(AUTHOR_UUID, "愛麗絲"));

            publisher.publishUpdated(article);

            ArgumentCaptor<ArticleUpdatedEvent> captor = ArgumentCaptor.forClass(ArticleUpdatedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_UPDATED), captor.capture());
            ArticleUpdatedEvent event = captor.getValue();
            assertThat(event.eventId()).isNotNull();
            assertThat(event.articleUuid()).isEqualTo(article.getUuid());
            assertThat(event.authorUuid()).isEqualTo(AUTHOR_UUID);
            assertThat(event.authorNickname()).isEqualTo("愛麗絲");
            assertThat(event.tags()).hasSize(1);
            verify(userFacade, times(1)).getDisplayInfoByUuids(any());
        }

        @Test
        @DisplayName("publishPublished：事件帶新的 eventId、文章的 authorUuid 與 UUID 版 facade 查得的 authorNickname，且只查一次")
        void publishPublished_shouldFillEventIdAuthorUuidAndNickname() {
            Article article = publishedArticle();
            when(userFacade.getDisplayInfoByUuids(List.of(AUTHOR_UUID))).thenReturn(displayInfo(AUTHOR_UUID, "愛麗絲"));

            publisher.publishPublished(article, List.of());

            ArgumentCaptor<ArticlePublishedEvent> captor = ArgumentCaptor.forClass(ArticlePublishedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_PUBLISHED), captor.capture());
            ArticlePublishedEvent event = captor.getValue();
            assertThat(event.eventId()).isNotNull();
            assertThat(event.articleUuid()).isEqualTo(article.getUuid());
            assertThat(event.authorUuid()).isEqualTo(AUTHOR_UUID);
            assertThat(event.authorNickname()).isEqualTo("愛麗絲");
            assertThat(event.contentText()).isEqualTo("內容");
            verify(userFacade, times(1)).getDisplayInfoByUuids(any());
        }

        @Test
        @DisplayName("publishPublished：facade 查無作者暱稱 → authorNickname 為 null，事件仍送出（authorUuid 照帶）")
        void publishPublished_whenNicknameMissing_shouldStillPublishWithNullNickname() {
            Article article = publishedArticle();
            when(userFacade.getDisplayInfoByUuids(List.of(AUTHOR_UUID))).thenReturn(Map.of());

            publisher.publishPublished(article, List.of());

            ArgumentCaptor<ArticlePublishedEvent> captor = ArgumentCaptor.forClass(ArticlePublishedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_PUBLISHED), captor.capture());
            assertThat(captor.getValue().authorUuid()).isEqualTo(AUTHOR_UUID);
            assertThat(captor.getValue().authorNickname()).isNull();
        }

        @Test
        @DisplayName("publishUpdated：facade 查無作者暱稱 → authorNickname 為 null，事件仍送出（authorUuid 照帶）")
        void publishUpdated_whenNicknameMissing_shouldStillPublishWithNullNickname() {
            Article article = publishedArticle();
            when(userFacade.getDisplayInfoByUuids(List.of(AUTHOR_UUID))).thenReturn(Map.of());

            publisher.publishUpdated(article);

            ArgumentCaptor<ArticleUpdatedEvent> captor = ArgumentCaptor.forClass(ArticleUpdatedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_UPDATED), captor.capture());
            assertThat(captor.getValue().authorUuid()).isEqualTo(AUTHOR_UUID);
            assertThat(captor.getValue().authorNickname()).isNull();
        }

        @Test
        @DisplayName("事件序列化後不含 authorId／authorUsername（登入識別符與 Long 識別不外流）")
        void publishedAndUpdated_payloadHasNoLongIdentityOrUsername() {
            Article article = publishedArticle();
            when(userFacade.getDisplayInfoByUuids(List.of(AUTHOR_UUID))).thenReturn(displayInfo(AUTHOR_UUID, "愛麗絲"));

            publisher.publishPublished(article, List.of());
            publisher.publishUpdated(article);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(rabbitTemplate, times(2)).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), any(String.class), captor.capture());
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            captor.getAllValues().forEach(event -> {
                Map<String, Object> payload = mapper.convertValue(
                        event, new com.fasterxml.jackson.core.type.TypeReference<>() { });
                assertThat(payload.keySet()).doesNotContain("authorId", "authorUsername")
                        .contains("authorUuid", "authorNickname");
            });
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
    @DisplayName("作者識別：直接取自文章，不再經任何查詢（M6-a）")
    class AuthorIdentityWithoutLookup {

        private Article publishedArticle() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorUuid(AUTHOR_UUID);
            article.setContent("內容");
            return article;
        }

        @Test
        @DisplayName("publishContentChanged：不碰 UserFacade")
        void publishContentChanged_noLookups() {
            publisher.publishContentChanged(publishedArticle(), ArticleContentChangedEvent.Action.SAVED);

            verifyNoInteractions(userFacade);
        }

        @Test
        @DisplayName("publishDeleted：不碰 UserFacade；seriesUuid 由呼叫端（取自文章的 series_uuid）傳入，不查任何東西")
        void publishDeleted_noLookups() {
            publisher.publishDeleted(publishedArticle(), UUID.randomUUID(), List.of(), List.of());

            verifyNoInteractions(userFacade);
        }

        @Test
        @DisplayName("publishPublished／publishUpdated：作者暱稱各只呼叫 UserFacade 一次")
        void publishPublishedAndUpdated_resolveNicknameOnceEach() {
            Article article = publishedArticle();
            when(userFacade.getDisplayInfoByUuids(List.of(AUTHOR_UUID))).thenReturn(displayInfo(AUTHOR_UUID, "愛麗絲"));

            publisher.publishPublished(article, List.of());
            publisher.publishUpdated(article);

            verify(userFacade, times(2)).getDisplayInfoByUuids(List.of(AUTHOR_UUID));
        }

        @Test
        @DisplayName("publishUpdated：UserFacade 查詢失敗 → 不外拋、事件不送出（既有 best-effort 行為），warn 帶事件類型")
        void publishUpdated_whenNicknameLookupFails_doesNotPropagateAndWarns() {
            Article article = publishedArticle();
            when(userFacade.getDisplayInfoByUuids(any())).thenThrow(new IllegalStateException("user down"));

            assertThatCode(() -> publisher.publishUpdated(article)).doesNotThrowAnyException();

            verify(rabbitTemplate, never()).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_UPDATED), any(Object.class));
        }
    }

    @Nested
    @DisplayName("publishDeleted")
    class PublishDeleted {

        @Test
        @DisplayName("M6-b：seriesUuid 由呼叫端（取自文章自己的 series_uuid）傳入 → 事件帶同一個 seriesUuid")
        void publishDeleted_withSeriesUuid_keepsUuidInEvent() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorUuid(AUTHOR_UUID);
            UUID seriesUuid = UUID.randomUUID();

            publisher.publishDeleted(article, seriesUuid, List.of(), List.of());

            ArgumentCaptor<ArticleDeletedEvent> captor = ArgumentCaptor.forClass(ArticleDeletedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_DELETED), captor.capture());
            assertThat(captor.getValue().seriesUuid()).isEqualTo(seriesUuid);
        }

        @Test
        @DisplayName("正常：publishDeleted 帶 rich payload 應正確構造 ArticleDeletedEvent")
        void publishDeleted_richPayload_constructsEventCorrectly() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorUuid(AUTHOR_UUID);

            UUID tagUuid = UUID.randomUUID();
            UUID categoryUuid = UUID.randomUUID();

            publisher.publishDeleted(article, UUID.randomUUID(), List.of(categoryUuid), List.of(tagUuid));

            ArgumentCaptor<ArticleDeletedEvent> captor = ArgumentCaptor.forClass(ArticleDeletedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE),
                    eq(ArticleEventContract.ROUTING_KEY_DELETED),
                    captor.capture()
            );
            ArticleDeletedEvent event = captor.getValue();
            assertThat(event.eventId()).isNotNull();
            assertThat(event.articleUuid()).isEqualTo(article.getUuid());
            assertThat(event.categoryIds()).containsExactly(categoryUuid);
            assertThat(event.tagIds()).containsExactly(tagUuid);
            assertThat(event.occurredAt()).isNotNull();
        }

        @Test
        @DisplayName("邊界：seriesId 為 null 時 event.seriesUuid() 應為 null")
        void publishDeleted_withNullSeriesId_eventSeriesUuidIsNull() {
            Article article = new Article();
            article.setId(10L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorUuid(AUTHOR_UUID);

            publisher.publishDeleted(article, (UUID) null, List.of(), List.of());

            ArgumentCaptor<ArticleDeletedEvent> captor = ArgumentCaptor.forClass(ArticleDeletedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE),
                    eq(ArticleEventContract.ROUTING_KEY_DELETED),
                    captor.capture()
            );
            ArticleDeletedEvent event = captor.getValue();
            assertThat(event.seriesUuid()).isNull();
            assertThat(event.categoryIds()).isEmpty();
            assertThat(event.tagIds()).isEmpty();
        }

        @Test
        @DisplayName("正常：事件帶文章自己的 authorUuid 與呼叫端傳入的 seriesUuid（不含 authorId）")
        void publishDeleted_shouldFillAuthorUuidAndSeriesUuid() {
            Article article = new Article();
            article.setId(100L);
            article.setUuid(UUID.randomUUID());
            UUID authorUuid = UUID.randomUUID();
            article.setAuthorUuid(authorUuid);
            UUID seriesUuid = UUID.randomUUID();

            publisher.publishDeleted(article, seriesUuid, List.of(), List.of());

            ArgumentCaptor<ArticleDeletedEvent> captor = ArgumentCaptor.forClass(ArticleDeletedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_DELETED), captor.capture());
            ArticleDeletedEvent event = captor.getValue();
            assertThat(event.authorUuid()).isEqualTo(authorUuid);
            assertThat(event.seriesUuid()).isEqualTo(seriesUuid);
        }

        @Test
        @DisplayName("邊界：seriesId 為 null 時不查 series，seriesUuid 為 null")
        void publishDeleted_withoutSeries_shouldNotResolveSeriesUuid() {
            Article article = new Article();
            article.setId(10L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorUuid(AUTHOR_UUID);

            publisher.publishDeleted(article, (UUID) null, List.of(), List.of());

            ArgumentCaptor<ArticleDeletedEvent> captor = ArgumentCaptor.forClass(ArticleDeletedEvent.class);
            verify(rabbitTemplate).convertAndSend(
                    eq(ArticleEventContract.EXCHANGE), eq(ArticleEventContract.ROUTING_KEY_DELETED), captor.capture());
            assertThat(captor.getValue().seriesUuid()).isNull();
        }

        @Test
        @DisplayName("防禦：categoryIds/tagIds 為 null 時應轉為 empty list")
        void publishDeleted_withNullLists_convertsToEmptyLists() {
            Article article = new Article();
            article.setId(20L);
            article.setUuid(UUID.randomUUID());
            article.setAuthorUuid(AUTHOR_UUID);

            publisher.publishDeleted(article, (UUID) null, null, null);

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
            article.setAuthorUuid(AUTHOR_UUID);

            doThrow(new RuntimeException("MQ down")).when(rabbitTemplate)
                    .convertAndSend(eq(ArticleEventContract.EXCHANGE),
                            eq(ArticleEventContract.ROUTING_KEY_DELETED),
                            any(Object.class));

            CapturingAppender appender = attachAppender();
            try {
                publisher.publishDeleted(article, (UUID) null, null, null);
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
            article.setAuthorUuid(AUTHOR_UUID);

            doThrow(new RuntimeException("MQ down")).when(rabbitTemplate)
                    .convertAndSend(eq(ArticleEventContract.EXCHANGE),
                            eq(ArticleEventContract.ROUTING_KEY_DELETED),
                            any(Object.class));

            assertThatCode(() -> publisher.publishDeleted(article, (UUID) null, null, null))
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
            article.setAuthorUuid(AUTHOR_UUID);

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
            article.setAuthorUuid(AUTHOR_UUID);

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
            article.setAuthorUuid(AUTHOR_UUID);

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
