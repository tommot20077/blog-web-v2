package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleFacade#filterExistingUuids} 對真實 PostgreSQL 的整合測試。
 *
 * <p>驗證「不分狀態」語意（草稿、已發布皆算存在；硬刪者不算），以及超過
 * {@link BatchedQuery#BATCH_SIZE} 的輸入經切批後結果仍完整。文章直接以 SQL 寫入，
 * 避免耦合 article 模組的建立流程（事件、標籤、檔案綁定）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ArticleTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleFacade.filterExistingUuids 整合測試")
class ArticleFacadeFilterExistingUuidsIT {

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

    /** testdata seed（R__article_test_seed.sql）建立的作者 */
    private static final long SEEDED_AUTHOR_ID = 1L;

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

    private UUID insertArticle(String status) {
        UUID uuid = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO articles (uuid, author_id, title, slug, content_md, status) VALUES (?, ?, ?, ?, ?, ?)",
                uuid, SEEDED_AUTHOR_ID, "t-" + uuid, "slug-" + uuid, "內容", status);
        inserted.add(uuid);
        return uuid;
    }

    @Test
    @DisplayName("草稿與已發布皆回傳，曾存在後來被刪除者不回傳（校正排程實際會遇到的情境）")
    void returnsDraftAndPublishedButNotDeleted() {
        UUID draft = insertArticle("DRAFT");
        UUID published = insertArticle("PUBLISHED");
        UUID deleted = insertArticle("DRAFT");
        jdbcTemplate.update("DELETE FROM articles WHERE uuid = ?", deleted);

        Set<UUID> result = articleFacade.filterExistingUuids(List.of(draft, published, deleted));

        assertThat(result).containsExactlyInAnyOrder(draft, published);
    }

    @Test
    @DisplayName("從未寫入的隨機 uuid 同樣不回傳")
    void neverInsertedUuidIsNotReturned() {
        UUID draft = insertArticle("DRAFT");
        UUID neverInserted = UUID.randomUUID();

        Set<UUID> result = articleFacade.filterExistingUuids(List.of(draft, neverInserted));

        assertThat(result).containsExactly(draft);
    }

    @Test
    @DisplayName("不分狀態：PENDING 與 REJECTED 也算存在（與 filterPublishedUuids 不同）")
    void nonPublishedStatusesAreAlsoReportedAsExisting() {
        UUID pending = insertArticle("PENDING");
        UUID rejected = insertArticle("REJECTED");
        UUID draft = insertArticle("DRAFT");

        assertThat(articleFacade.filterExistingUuids(List.of(pending, rejected, draft)))
                .containsExactlyInAnyOrder(pending, rejected, draft);
        assertThat(articleFacade.filterPublishedUuids(List.of(pending, rejected, draft))).isEmpty();
    }

    @Test
    @DisplayName("空輸入回傳空集合")
    void emptyInputReturnsEmptySet() {
        assertThat(articleFacade.filterExistingUuids(List.of())).isEmpty();
        assertThat(articleFacade.filterExistingUuids(null)).isEmpty();
    }

    @Test
    @DisplayName("輸入超過一個批次大小時正確切批，存在者全數回傳、不存在者不回傳")
    void inputLargerThanBatchSizeIsSplitCorrectly() {
        int existingCount = 5;
        List<UUID> existing = new ArrayList<>();
        for (int i = 0; i < existingCount; i++) {
            existing.add(insertArticle(i % 2 == 0 ? "DRAFT" : "PUBLISHED"));
        }
        List<UUID> input = new ArrayList<>();
        // 把存在者散在三個批次：第一批頭、第二批中、第三批尾
        for (int i = 0; i < BatchedQuery.BATCH_SIZE * 2 + 100; i++) {
            input.add(UUID.randomUUID());
        }
        input.set(0, existing.get(0));
        input.set(1, existing.get(1));
        input.set(BatchedQuery.BATCH_SIZE + 10, existing.get(2));
        input.set(BatchedQuery.BATCH_SIZE * 2, existing.get(3));
        input.set(input.size() - 1, existing.get(4));

        Set<UUID> result = articleFacade.filterExistingUuids(input);

        assertThat(result).containsExactlyInAnyOrderElementsOf(existing);
    }
}
