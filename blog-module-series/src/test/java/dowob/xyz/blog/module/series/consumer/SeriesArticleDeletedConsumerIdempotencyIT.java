package dowob.xyz.blog.module.series.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.series.config.SeriesTestApplication;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.support.SeededUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link SeriesArticleDeletedConsumer} 冪等交易的整合測試：真實 Postgres、真實 Spring 交易與 MyBatis。
 *
 * <p>證明三件事：(1) 業務（series.article_count -1）與 {@code processed_events} 標記在同一交易——
 * 兩者都已寫入後 commit 失敗，兩者一起消失；(2) 之後同一 eventId 重送會真的重做，而不是被冪等跳過；
 * (3) 成功後再重送才被冪等跳過。commit 失敗用 DEFERRED constraint trigger 製造（在 commit 時才 RAISE），
 * 因此失敗發生在 UPDATE 與標記 INSERT 都已執行之後。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("SeriesArticleDeletedConsumer 冪等交易")
class SeriesArticleDeletedConsumerIdempotencyIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("blog_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:7-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.url", redis::getRedisURI);
    }

    @Autowired private SeriesArticleDeletedConsumer consumer;
    @Autowired private SeriesMapper seriesMapper;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    @MockitoBean private FileFacade fileFacade;

    /** 本測試建立的 series 公開 UUID */
    private UUID seriesUuid;

    @BeforeEach
    void seed() {
        dropFailingTrigger();
        jdbc.execute("DELETE FROM series");
        jdbc.update("DELETE FROM processed_events WHERE consumer_name = ?", SeriesArticleDeletedConsumer.CONSUMER_NAME);
        seriesUuid = UUID.randomUUID();
        Series s = new Series();
        s.setUuid(seriesUuid);
        s.setTitle("idem");
        s.setSlug("idem-" + seriesUuid);
        s.setDescription("d");
        s.setCoverImageUrl("https://img/idem");
        s.setAuthorUuid(SeededUsers.USER1_UUID);
        s.setArticleCount(3);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        seriesMapper.insertIfSlugAbsent(s);
    }

    @AfterEach
    void cleanUp() {
        dropFailingTrigger();
        jdbc.execute("DELETE FROM series");
        jdbc.update("DELETE FROM processed_events WHERE consumer_name = ?", SeriesArticleDeletedConsumer.CONSUMER_NAME);
    }

    /** 安裝「commit 時才失敗」的 DEFERRED constraint trigger（UPDATE series 後，於 commit 階段 RAISE）。 */
    private void installCommitTimeFailure() {
        jdbc.execute("CREATE OR REPLACE FUNCTION it_fail_at_commit() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'IT 模擬 commit 時失敗'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE CONSTRAINT TRIGGER it_fail_at_commit_trg AFTER UPDATE ON series "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION it_fail_at_commit()");
    }

    /** 移除上面安裝的 trigger 與函式（不存在時略過）。 */
    private void dropFailingTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_at_commit_trg ON series");
        jdbc.execute("DROP FUNCTION IF EXISTS it_fail_at_commit()");
    }

    private ArticleDeletedEvent event(UUID eventId) {
        return new ArticleDeletedEvent(eventId, UUID.randomUUID(), List.of(), List.of(), Instant.now(),
                null, seriesUuid);
    }

    private int articleCount() {
        return jdbc.queryForObject("SELECT article_count FROM series WHERE uuid = ?", Integer.class, seriesUuid);
    }

    private int markCount(UUID eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ? AND consumer_name = ?",
                Integer.class, eventId, SeriesArticleDeletedConsumer.CONSUMER_NAME);
    }

    @Test
    @DisplayName("業務與標記都已寫入後 commit 失敗：計數與標記一起 rollback、例外往外拋；同一 eventId 重送會真的重做，之後再重送才被跳過")
    void commitFailure_rollsBackCountAndMark_thenRedeliveryReallyReruns() throws Exception {
        UUID eventId = UUID.randomUUID();
        installCommitTimeFailure();

        assertThatThrownBy(() -> consumer.onArticleDeleted(event(eventId))).isInstanceOf(RuntimeException.class);

        assertThat(articleCount()).as("計數寫入已 rollback").isEqualTo(3);
        assertThat(markCount(eventId)).as("標記一起 rollback").isZero();

        dropFailingTrigger();
        consumer.onArticleDeleted(event(eventId));

        assertThat(articleCount()).as("重送真的執行了業務").isEqualTo(2);
        assertThat(markCount(eventId)).isEqualTo(1);
        consumer.onArticleDeleted(event(eventId));

        assertThat(articleCount()).as("成功後再重送被冪等跳過").isEqualTo(2);
        assertThat(markCount(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("seriesUuid 為 null（文章不在 series）：no-op 正常返回，不寫 processed_events")
    void noSeries_acksWithoutWritingMark() throws Exception {
        UUID eventId = UUID.randomUUID();
        ArticleDeletedEvent noSeries = new ArticleDeletedEvent(eventId, UUID.randomUUID(), List.of(), List.of(),
                Instant.now(), null, null);

        consumer.onArticleDeleted(noSeries);

        assertThat(markCount(eventId)).isZero();
        assertThat(articleCount()).isEqualTo(3);
    }
}
