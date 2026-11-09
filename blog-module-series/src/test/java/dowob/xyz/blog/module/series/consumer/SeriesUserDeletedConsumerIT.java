package dowob.xyz.blog.module.series.consumer;

import dowob.xyz.blog.infrastructure.event.SeriesDeletedEvent;
import dowob.xyz.blog.infrastructure.event.SeriesEventContract;
import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.series.config.SeriesTestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link SeriesUserDeletedConsumer} 對真實 PostgreSQL（含真實 article 模組 facade）的整合測試。
 *
 * <p>驗證作者刪帳（DATA-02）時：只有「已無保留文章（PUBLISHED、ARCHIVED 以外）」的系列被刪；
 * 仍有已發布或已下架文章的系列保留；判斷一律回問 article facade（series 表已沒有計數欄位）；
 * 重送同一事件不會再刪、也不會重發 {@code SeriesDeletedEvent}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("SeriesUserDeletedConsumer 作者刪帳連動")
class SeriesUserDeletedConsumerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("blog_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:8.8.0-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.url", redis::getRedisURI);
    }

    @Autowired private SeriesUserDeletedConsumer consumer;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;

    /** 被刪帳的作者（隨機 UUID，與任何 id 無關） */
    private UUID deletedAuthor;

    /** 另一位未刪帳的作者 */
    private UUID otherAuthor;

    @BeforeEach
    void seed() {
        cleanUp();
        reset(rabbitTemplate);
        deletedAuthor = UUID.randomUUID();
        otherAuthor = UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("UPDATE articles SET series_uuid = NULL, series_position = NULL");
        jdbc.execute("DELETE FROM series");
        jdbc.execute("DELETE FROM articles");
        jdbc.update("DELETE FROM processed_events WHERE consumer_name = ?", SeriesUserDeletedConsumer.CONSUMER_NAME);
    }

    /** 建立系列。 */
    private UUID insertSeries(UUID authorUuid, String slug) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO series (uuid, title, slug, author_uuid) VALUES (?, ?, ?, ?)",
                uuid, "T-" + slug, slug, authorUuid);
        return uuid;
    }

    private void insertArticle(UUID authorUuid, UUID seriesUuid, int position, String status) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO articles (uuid, author_uuid, title, slug, content_md, status, series_uuid, series_position) "
                        + "VALUES (?, ?, ?, ?, 'md', ?, ?, ?)",
                uuid, authorUuid, "t-" + uuid, "slug-" + uuid, status, seriesUuid, position);
    }

    private boolean seriesExists(UUID seriesUuid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM series WHERE uuid = ?", Integer.class, seriesUuid) == 1;
    }

    private UserDeletedEvent event() {
        return new UserDeletedEvent(UUID.randomUUID(), deletedAuthor, Instant.now());
    }

    @Test
    @DisplayName("只有草稿／待審／駁回文章的系列被刪；有已發布或已下架文章的系列保留；沒有任何文章的系列被刪")
    void deletesOnlySeriesWithoutRetainedArticles() {
        UUID draftOnly = insertSeries(deletedAuthor, "draft-only");
        insertArticle(deletedAuthor, draftOnly, 1, "DRAFT");
        insertArticle(deletedAuthor, draftOnly, 2, "PENDING_REVIEW");
        insertArticle(deletedAuthor, draftOnly, 3, "REJECTED");
        UUID withPublished = insertSeries(deletedAuthor, "with-published");
        insertArticle(deletedAuthor, withPublished, 1, "PUBLISHED");
        insertArticle(deletedAuthor, withPublished, 2, "DRAFT");
        UUID withArchived = insertSeries(deletedAuthor, "with-archived");
        insertArticle(deletedAuthor, withArchived, 1, "ARCHIVED");
        UUID noArticles = insertSeries(deletedAuthor, "no-articles");
        UUID othersSeries = insertSeries(otherAuthor, "others");
        insertArticle(otherAuthor, othersSeries, 1, "DRAFT");

        consumer.onUserDeleted(event());

        assertThat(seriesExists(draftOnly)).as("只有草稿／待審／駁回 → 刪除").isFalse();
        assertThat(seriesExists(noArticles)).as("沒有任何文章 → 刪除").isFalse();
        assertThat(seriesExists(withPublished)).as("仍有已發布文章 → 保留").isTrue();
        assertThat(seriesExists(withArchived)).as("仍有已下架文章 → 保留").isTrue();
        assertThat(seriesExists(othersSeries)).as("別的作者的系列不受影響").isTrue();

        ArgumentCaptor<SeriesDeletedEvent> captor = ArgumentCaptor.forClass(SeriesDeletedEvent.class);
        verify(rabbitTemplate, times(2)).convertAndSend(
                eq(SeriesEventContract.EXCHANGE), eq(SeriesEventContract.ROUTING_KEY_DELETED), captor.capture());
        assertThat(captor.getAllValues()).extracting(SeriesDeletedEvent::seriesUuid)
                .containsExactlyInAnyOrder(draftOnly, noArticles);
    }

    @Test
    @DisplayName("冪等：同一事件重送 → 不再刪除、不重發 SeriesDeletedEvent，processed_events 只有一列")
    void replayedEvent_isIdempotent() {
        UUID draftOnly = insertSeries(deletedAuthor, "idem-draft");
        insertArticle(deletedAuthor, draftOnly, 1, "DRAFT");
        UUID retained = insertSeries(deletedAuthor, "idem-retained");
        insertArticle(deletedAuthor, retained, 1, "PUBLISHED");
        UserDeletedEvent ev = event();

        consumer.onUserDeleted(ev);
        reset(rabbitTemplate);
        // 重送之前，在作者名下補一個空系列：若重送真的又執行了業務，這個系列會被刪掉
        UUID lateEmpty = insertSeries(deletedAuthor, "idem-late-empty");
        consumer.onUserDeleted(ev);

        assertThat(seriesExists(draftOnly)).isFalse();
        assertThat(seriesExists(retained)).isTrue();
        assertThat(seriesExists(lateEmpty)).as("重送被冪等跳過，不應再動任何系列").isTrue();
        verifyNoInteractions(rabbitTemplate);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ? AND consumer_name = ?",
                Integer.class, ev.eventId(), SeriesUserDeletedConsumer.CONSUMER_NAME)).isEqualTo(1);
    }

    @Test
    @DisplayName("跨作者：已刪帳作者的系列內有他人的 PUBLISHED 文章（admin 才能放入）→ 系列保留")
    void seriesContainingOthersPublishedArticle_isRetained() {
        UUID series = insertSeries(deletedAuthor, "cross-published");
        insertArticle(otherAuthor, series, 1, "PUBLISHED");

        consumer.onUserDeleted(event());

        assertThat(seriesExists(series)).isTrue();
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("跨作者：已刪帳作者的系列內只有他人的草稿 → 系列被刪，他人文章列仍在（只是等 SeriesDeletedEvent 後脫離系列）")
    void seriesContainingOnlyOthersDraft_isDeletedButArticleRemains() {
        UUID series = insertSeries(deletedAuthor, "cross-draft");
        insertArticle(otherAuthor, series, 1, "DRAFT");

        consumer.onUserDeleted(event());

        assertThat(seriesExists(series)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM articles WHERE author_uuid = ?", Integer.class, otherAuthor))
                .as("他人的文章不會被刪").isEqualTo(1);
        verify(rabbitTemplate).convertAndSend(
                eq(SeriesEventContract.EXCHANGE), eq(SeriesEventContract.ROUTING_KEY_DELETED), any(SeriesDeletedEvent.class));
    }

    @Test
    @DisplayName("作者沒有任何系列 → 不報錯、不發事件")
    void authorWithoutSeries_isNoOp() {
        insertSeries(otherAuthor, "unrelated");

        consumer.onUserDeleted(event());

        verifyNoInteractions(rabbitTemplate);
    }
}
