package dowob.xyz.blog.module.version.consumer;

import com.rabbitmq.client.Channel;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent.Action;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.version.config.VersionTestApplication;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import dowob.xyz.blog.module.version.service.VersioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArticleVersionConsumer 整合測試。
 *
 * <p>直接呼叫 {@code consumer.onContentChanged(event)}，不走真實 broker，
 * 驗證 SAVED / PUBLISHED / RESTORED 三種 action 的資料庫副作用。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = VersionTestApplication.class)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleVersionConsumer 整合測試")
class ArticleVersionConsumerIT {

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

    @Autowired
    private ArticleVersionConsumer consumer;

    @Autowired
    private VersioningService versioningService;

    @Autowired
    private ArticleRepository articleRepo;

    @Autowired
    private ArticleVersionRepository versionRepo;

    @MockitoBean
    private ConnectionFactory connectionFactory;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private ReadingFacade readingFacade;

    @MockitoBean
    private SeriesFacade seriesFacade;

    @MockitoBean
    private TagFacade tagFacade;

    @MockitoBean
    private UserFacade userFacade;

    @MockitoBean
    private UserAuthService userAuthService;

    private static final Long AUTHOR_ID = 1L;
    private static final long DELIVERY_TAG = 1L;

    private Article testArticle;
    private Channel mockChannel;

    @BeforeEach
    void setup() {
        // 清理版本與文章資料
        versionRepo.deleteAll();
        articleRepo.deleteAll();

        // 每個測試獨立的 mock channel
        mockChannel = Mockito.mock(Channel.class);

        // 建立測試文章
        testArticle = new Article();
        testArticle.setUuid(UUID.randomUUID());
        testArticle.setAuthorId(AUTHOR_ID);
        testArticle.setTitle("Version Test Article");
        testArticle.setSlug("version-test-article-" + UUID.randomUUID());
        testArticle.setContent("This is a test content for versioning");
        testArticle.setContentHtml("<p>This is a test content for versioning</p>");
        testArticle.setStatus(ArticleStatus.DRAFT);
        testArticle.setLikeCount(0);
        testArticle.setCommentCount(0);
        testArticle.setViewCount(0L);
        testArticle.setCreatedAt(LocalDateTime.now());
        testArticle.setUpdatedAt(LocalDateTime.now());
        testArticle = articleRepo.save(testArticle);
    }

    /**
     * SAVED action + shouldSnapshot() = true（無先前快照）→ 寫入一筆 AUTO。
     */
    @Test
    @DisplayName("SAVED action: 無先前快照時應寫入 AUTO 版本")
    void onContentChanged_savedAction_writesAutoVersion() {
        ArticleContentChangedEvent event = new ArticleContentChangedEvent(
                testArticle.getId(),
                testArticle.getUuid(),
                AUTHOR_ID,
                Action.SAVED,
                Instant.now()
        );

        consumer.onContentChanged(event, mockChannel, DELIVERY_TAG);

        List<ArticleVersion> versions = (List<ArticleVersion>) versionRepo.findAll();
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getType()).isEqualTo(VersioningService.TYPE_AUTO);
        assertThat(versions.get(0).getArticleId()).isEqualTo(testArticle.getId());
    }

    /**
     * PUBLISHED action → 寫入 PUBLISHED 快照 + 清除所有 AUTO。
     */
    @Test
    @DisplayName("PUBLISHED action: 清除所有 AUTO 並寫入 PUBLISHED 快照")
    void onContentChanged_publishedAction_freezesAndClearsAutos() {
        // 先寫入 3 個 AUTO 快照
        for (int i = 0; i < 3; i++) {
            ArticleVersion v = new ArticleVersion();
            v.setUuid(UUID.randomUUID());
            v.setArticleId(testArticle.getId());
            v.setAuthorId(AUTHOR_ID);
            v.setType(VersioningService.TYPE_AUTO);
            v.setTitle("Auto " + i);
            v.setSlug(testArticle.getSlug());
            v.setContent("content " + i);
            v.setStatus("DRAFT");
            v.setCreatedAt(LocalDateTime.now().minusMinutes(10 - i));
            versionRepo.save(v);
        }

        // 確認有 3 個 AUTO
        List<ArticleVersion> before = (List<ArticleVersion>) versionRepo.findAll();
        assertThat(before).hasSize(3);

        // 觸發 PUBLISHED action
        ArticleContentChangedEvent event = new ArticleContentChangedEvent(
                testArticle.getId(),
                testArticle.getUuid(),
                AUTHOR_ID,
                Action.PUBLISHED,
                Instant.now()
        );

        consumer.onContentChanged(event, mockChannel, DELIVERY_TAG);

        // 驗證：所有 AUTO 被清除，僅留 1 個 PUBLISHED
        List<ArticleVersion> after = (List<ArticleVersion>) versionRepo.findAll();
        assertThat(after).hasSize(1);
        assertThat(after.get(0).getType()).isEqualTo(VersioningService.TYPE_PUBLISHED);
        assertThat(after.get(0).getNote()).isEqualTo("Published v1");
    }

    /**
     * RESTORED action → no-op，資料庫不應有任何版本變化。
     */
    @Test
    @DisplayName("RESTORED action: no-op，不寫入任何快照")
    void onContentChanged_restoredAction_isNoOp() {
        long countBefore = countVersionsForArticle(testArticle.getId());
        assertThat(countBefore).isZero();

        ArticleContentChangedEvent event = new ArticleContentChangedEvent(
                testArticle.getId(),
                testArticle.getUuid(),
                AUTHOR_ID,
                Action.RESTORED,
                Instant.now()
        );

        consumer.onContentChanged(event, mockChannel, DELIVERY_TAG);

        long countAfter = countVersionsForArticle(testArticle.getId());
        assertThat(countAfter).isZero();
    }

    /**
     * SAVED action 多次呼叫 + retention 機制 → AUTO 快照數 ≤ retain（50）。
     *
     * <p>先建立 51 個 AUTO 快照，再直接呼叫 recordAutoSnapshot（模擬 consumer SAVED 路徑），
     * 驗證 retention 策略執行後快照數 ≤ 50。</p>
     */
    @Test
    @DisplayName("SAVED action 多次: retention 後 AUTO 快照數 ≤ 50")
    void onContentChanged_savedActionMultipleTimes_retainsCap() {
        // 先手動寫入 51 個 AUTO 快照（時間各不同，模擬歷史）
        for (int i = 0; i < 51; i++) {
            ArticleVersion v = new ArticleVersion();
            v.setUuid(UUID.randomUUID());
            v.setArticleId(testArticle.getId());
            v.setAuthorId(AUTHOR_ID);
            v.setType(VersioningService.TYPE_AUTO);
            v.setTitle("Auto-pre-" + i);
            v.setSlug(testArticle.getSlug());
            v.setContent("content-" + i);
            v.setStatus("DRAFT");
            v.setCreatedAt(LocalDateTime.now().minusMinutes(200 - i));
            versionRepo.save(v);
        }

        // 確認有 51 個 AUTO 快照
        assertThat(countAutoVersionsForArticle(testArticle.getId())).isEqualTo(51);

        // 直接呼叫 recordAutoSnapshot（模擬 consumer SAVED 路徑中 shouldSnapshot=true 的情況）
        // 這會寫入第 52 個快照，然後 retainAuto(articleId, 50) 刪掉超過 50 的
        versioningService.recordAutoSnapshot(testArticle.getId());

        // 驗證 AUTO 快照數 ≤ 50（retention 策略生效）
        long autoCount = countAutoVersionsForArticle(testArticle.getId());
        assertThat(autoCount).isLessThanOrEqualTo(50);
    }

    private long countVersionsForArticle(Long articleId) {
        long count = 0;
        for (ArticleVersion v : versionRepo.findAll()) {
            if (v.getArticleId().equals(articleId)) count++;
        }
        return count;
    }

    private long countAutoVersionsForArticle(Long articleId) {
        long count = 0;
        for (ArticleVersion v : versionRepo.findAll()) {
            if (v.getArticleId().equals(articleId)
                    && VersioningService.TYPE_AUTO.equals(v.getType())) {
                count++;
            }
        }
        return count;
    }
}
