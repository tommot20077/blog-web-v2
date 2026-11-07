package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ArticleLookupFacade#findUuidsUpdatedSinceAnyStatus} 對真實 PostgreSQL 的整合測試（B1）。
 *
 * <p>驗證：只回 {@code updated_at >= since} 的文章、<b>不分狀態</b>（草稿也要回）、依 uuid 升冪、
 * keyset 游標分頁不重不漏（排序由資料庫決定）。文章直接以 SQL 寫入並指定 {@code updated_at}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ArticleTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleLookupFacade.findUuidsUpdatedSinceAnyStatus 整合測試")
class ArticleLookupFacadeUpdatedSinceIT {

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

    private static final UUID AUTHOR_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    @Autowired private ArticleLookupFacade articleLookupFacade;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    @MockitoBean private SeriesFacade seriesFacade;

    private final List<UUID> inserted = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        inserted.forEach(uuid -> jdbcTemplate.update("DELETE FROM articles WHERE uuid = ?", uuid));
        inserted.clear();
    }

    private UUID insertArticle(String status, LocalDateTime updatedAt) {
        UUID uuid = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO articles (uuid, author_uuid, title, slug, content_md, status, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                uuid, AUTHOR_UUID, "t-" + uuid, "slug-" + uuid, "內容", status, updatedAt);
        inserted.add(uuid);
        return uuid;
    }

    @Test
    @DisplayName("只回 updated_at >= since 者，且不分狀態（DRAFT／PENDING／PUBLISHED／REJECTED 都要回）")
    void returnsRecentlyUpdatedArticlesOfAnyStatus() {
        LocalDateTime now = LocalDateTime.now();
        UUID draft = insertArticle("DRAFT", now.minusDays(1));
        UUID published = insertArticle("PUBLISHED", now.minusDays(2));
        UUID pending = insertArticle("PENDING", now.minusHours(1));
        UUID rejected = insertArticle("REJECTED", now.minusDays(2));
        UUID old = insertArticle("PUBLISHED", now.minusDays(10));
        UUID oldDraft = insertArticle("DRAFT", now.minusDays(4));

        List<UUID> result = articleLookupFacade.findUuidsUpdatedSinceAnyStatus(now.minusDays(3), null, 100);

        assertThat(result).contains(draft, published, pending, rejected).doesNotContain(old, oldDraft);
    }

    @Test
    @DisplayName("keyset 分頁：依 uuid 升冪、游標不含、逐頁串接不重不漏")
    void keysetPagination_coversAllExactlyOnceInAscendingOrder() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusDays(3);
        Set<UUID> expected = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            expected.add(insertArticle(i % 2 == 0 ? "DRAFT" : "PUBLISHED", now.minusHours(i + 1)));
        }

        List<UUID> collected = new ArrayList<>();
        UUID after = null;
        for (int guard = 0; guard < 20; guard++) {
            List<UUID> page = articleLookupFacade.findUuidsUpdatedSinceAnyStatus(since, after, 2);
            assertThat(page.size()).isLessThanOrEqualTo(2);
            if (page.isEmpty()) {
                break;
            }
            collected.addAll(page);
            after = page.get(page.size() - 1);
        }

        assertThat(collected).as("不重複").doesNotHaveDuplicates();
        assertThat(new HashSet<>(collected)).containsAll(expected);
        List<String> asText = collected.stream().map(UUID::toString).toList();
        assertThat(asText).as("PostgreSQL uuid 以位元組序排序，等同其小寫字串字典序").isSorted();
    }

    @Test
    @DisplayName("limit 非正數回空清單；since 為 null 拋 IllegalArgumentException")
    void invalidArguments() {
        assertThat(articleLookupFacade.findUuidsUpdatedSinceAnyStatus(LocalDateTime.now().minusDays(1), null, 0)).isEmpty();
        assertThatThrownBy(() -> articleLookupFacade.findUuidsUpdatedSinceAnyStatus(null, null, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
