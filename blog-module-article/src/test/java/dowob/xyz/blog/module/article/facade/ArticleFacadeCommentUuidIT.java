package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.config.ArticleTestApplication;
import org.junit.jupiter.api.AfterEach;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * comment 模組使用的 UUID 版 {@link ArticleFacade} 方法，對真實 PostgreSQL 的整合測試：
 * {@code existsByUuid}、{@code incrementCommentCount(UUID)}、{@code decrementCommentCount(UUID)}。
 *
 * <p>文章直接以 SQL 寫入，避免耦合 article 模組的建立流程（事件、標籤、檔案綁定）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ArticleTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleFacade 留言用 UUID 版方法整合測試")
class ArticleFacadeCommentUuidIT {

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

    /** 測試用作者公開 UUID（articles.author_uuid 無 FK，直接寫入固定值） */
    private static final UUID SEEDED_AUTHOR_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Autowired private ArticleFacade articleFacade;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private FileFacade fileFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    @MockitoBean private SeriesFacade seriesFacade;

    /** 本測試寫入的文章 uuid，供清理 */
    private final List<UUID> inserted = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        inserted.forEach(uuid -> jdbcTemplate.update("DELETE FROM articles WHERE uuid = ?", uuid));
        inserted.clear();
    }

    private UUID insertArticle(String status, int commentCount) {
        UUID uuid = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO articles (uuid, author_uuid, title, slug, content_md, status, comment_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                uuid, SEEDED_AUTHOR_UUID, "t-" + uuid, "slug-" + uuid, "內容", status, commentCount);
        inserted.add(uuid);
        return uuid;
    }

    private int commentCountOf(UUID uuid) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT comment_count FROM articles WHERE uuid = ?", Integer.class, uuid);
        return n == null ? -1 : n;
    }

    @Test
    @DisplayName("existsByUuid：不分狀態，草稿與已發布皆為 true")
    void existsByUuid_anyStatus_returnsTrue() {
        assertThat(articleFacade.existsByUuid(insertArticle("DRAFT", 0))).isTrue();
        assertThat(articleFacade.existsByUuid(insertArticle("PUBLISHED", 0))).isTrue();
        assertThat(articleFacade.existsByUuid(insertArticle("PENDING", 0))).isTrue();
        assertThat(articleFacade.existsByUuid(insertArticle("REJECTED", 0))).isTrue();
    }

    @Test
    @DisplayName("existsByUuid：曾存在後被刪除、或從未存在者為 false")
    void existsByUuid_deletedOrNeverExisted_returnsFalse() {
        UUID deleted = insertArticle("DRAFT", 0);
        jdbcTemplate.update("DELETE FROM articles WHERE uuid = ?", deleted);

        assertThat(articleFacade.existsByUuid(deleted)).isFalse();
        assertThat(articleFacade.existsByUuid(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("incrementCommentCount(UUID)：只讓該文章的 comment_count +1，不影響其他文章")
    void incrementCommentCountByUuid_incrementsOnlyTargetArticle() {
        UUID target = insertArticle("PUBLISHED", 2);
        UUID other = insertArticle("PUBLISHED", 5);

        articleFacade.incrementCommentCount(target);

        assertThat(commentCountOf(target)).isEqualTo(3);
        assertThat(commentCountOf(other)).isEqualTo(5);
    }

    @Test
    @DisplayName("decrementCommentCount(UUID)：-1，且不影響其他文章")
    void decrementCommentCountByUuid_decrementsOnlyTargetArticle() {
        UUID target = insertArticle("PUBLISHED", 2);
        UUID other = insertArticle("PUBLISHED", 5);

        articleFacade.decrementCommentCount(target);

        assertThat(commentCountOf(target)).isEqualTo(1);
        assertThat(commentCountOf(other)).isEqualTo(5);
    }

    @Test
    @DisplayName("decrementCommentCount(UUID)：計數為 0 時守衛 > 0，不會變成負數")
    void decrementCommentCountByUuid_atZero_staysZero() {
        UUID target = insertArticle("PUBLISHED", 0);

        articleFacade.decrementCommentCount(target);

        assertThat(commentCountOf(target)).isZero();
    }

    @Test
    @DisplayName("increment／decrement 對不存在的 uuid 是 no-op，不拋例外")
    void countOperations_unknownUuid_areNoOps() {
        UUID unknown = UUID.randomUUID();

        articleFacade.incrementCommentCount(unknown);
        articleFacade.decrementCommentCount(unknown);

        assertThat(articleFacade.existsByUuid(unknown)).isFalse();
    }
}
