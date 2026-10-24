package dowob.xyz.blog.module.series.integration;

import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.series.config.SeriesTestApplication;
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
 * {@link SeriesFacade#filterExistingUuids} 對真實 PostgreSQL 的整合測試。
 *
 * <p>驗證存在者回傳、已刪除者不回傳，以及超過 {@link BatchedQuery#BATCH_SIZE} 的輸入
 * 經切批後結果仍完整。系列直接以 SQL 寫入，不耦合 series 模組的建立流程。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("SeriesFacade.filterExistingUuids 整合測試")
class SeriesFacadeFilterExistingUuidsIT {

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

    /** testdata seed（R__series_test_seed.sql）建立的作者 */
    private static final long SEEDED_AUTHOR_ID = 1L;

    @Autowired private SeriesFacade seriesFacade;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    /** article 模組的 ArticleFileBinder 依賴 FileFacade，其實作在 blog-module-file（未被本測試 scan） */
    @MockitoBean private FileFacade fileFacade;

    /** 本測試寫入的 series uuid，供清理 */
    private final List<UUID> inserted = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        inserted.forEach(uuid -> jdbcTemplate.update("DELETE FROM series WHERE uuid = ?", uuid));
        inserted.clear();
    }

    private UUID insertSeries() {
        UUID uuid = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO series (uuid, title, slug, author_id) VALUES (?, ?, ?, ?)",
                uuid, "t-" + uuid, "slug-" + uuid, SEEDED_AUTHOR_ID);
        inserted.add(uuid);
        return uuid;
    }

    @Test
    @DisplayName("存在的系列回傳，已刪除（不存在）者不回傳")
    void returnsExistingButNotDeleted() {
        UUID first = insertSeries();
        UUID second = insertSeries();
        UUID deleted = insertSeries();
        jdbcTemplate.update("DELETE FROM series WHERE uuid = ?", deleted);

        Set<UUID> result = seriesFacade.filterExistingUuids(List.of(first, second, deleted));

        assertThat(result).containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("空輸入回傳空集合")
    void emptyInputReturnsEmptySet() {
        assertThat(seriesFacade.filterExistingUuids(List.of())).isEmpty();
        assertThat(seriesFacade.filterExistingUuids(null)).isEmpty();
    }

    @Test
    @DisplayName("輸入超過一個批次大小時正確切批，存在者全數回傳、不存在者不回傳")
    void inputLargerThanBatchSizeIsSplitCorrectly() {
        List<UUID> existing = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            existing.add(insertSeries());
        }
        List<UUID> input = new ArrayList<>();
        for (int i = 0; i < BatchedQuery.BATCH_SIZE * 2 + 100; i++) {
            input.add(UUID.randomUUID());
        }
        // 把存在者散在三個批次：第一批頭、第二批中、第三批尾
        input.set(0, existing.get(0));
        input.set(BatchedQuery.BATCH_SIZE + 10, existing.get(1));
        input.set(BatchedQuery.BATCH_SIZE * 2, existing.get(2));
        input.set(input.size() - 1, existing.get(3));

        Set<UUID> result = seriesFacade.filterExistingUuids(input);

        assertThat(result).containsExactlyInAnyOrderElementsOf(existing);
    }
}
