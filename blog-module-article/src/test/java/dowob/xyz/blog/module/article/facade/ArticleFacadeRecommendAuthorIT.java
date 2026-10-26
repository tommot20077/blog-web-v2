package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.config.ArticleTestApplication;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 推薦用 {@link ArticleFacade} 方法的作者解析整合測試（M6-a）。
 *
 * <p>推薦查詢原為 {@code INNER JOIN users}：查無作者的文章整列被排除。M6-a 改為讀 {@code articles.author_uuid}
 * ＋ 整頁一次 {@code UserFacade.getDisplayInfoByUuids}，本測試對真實 PostgreSQL 驗證兩件事：
 * ① 查無作者的文章<b>仍不出現在推薦結果</b>（與 INNER JOIN 相同語意）；② 整頁只呼叫 UserFacade 一次。
 * 文章與標籤直接以 SQL 寫入，users 表完全不寫入——證明查詢不再依賴 users。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ArticleTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleFacade 推薦查詢作者解析整合測試")
class ArticleFacadeRecommendAuthorIT {

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
        /** 與 blog-start 的 application.yaml 相同：載入 ArticleRecommendMapper.xml（模組測試預設不載入 XML mapper） */
        registry.add("mybatis.mapper-locations", () -> "classpath*:mapper/*.xml");
    }

    /** UserFacade 查得到的作者 */
    private static final UUID KNOWN_AUTHOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    /** UserFacade 查無的作者（模擬 INNER JOIN users 時找不到對應 users 列） */
    private static final UUID MISSING_AUTHOR = UUID.fromString("00000000-0000-0000-0000-0000000000f9");

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
    private final List<UUID> insertedArticles = new ArrayList<>();
    /** 本測試寫入的 tag id，供清理 */
    private final List<UUID> insertedTags = new ArrayList<>();

    @BeforeEach
    void setUp() {
        reset(userFacade);
        when(userFacade.getDisplayInfoByUuids(anyCollection())).thenAnswer(invocation -> {
            Collection<UUID> requested = invocation.getArgument(0);
            return requested.contains(KNOWN_AUTHOR)
                    ? Map.of(KNOWN_AUTHOR, new UserDisplayInfo(KNOWN_AUTHOR, "已知作者", null))
                    : Map.of();
        });
    }

    @AfterEach
    void cleanUp() {
        insertedArticles.forEach(uuid -> jdbcTemplate.update("DELETE FROM articles WHERE uuid = ?", uuid));
        /* V33 起刪文章不再 CASCADE 清除 article_tags（FK 已移除），刪標籤前先顯式清掉其關聯列 */
        insertedTags.forEach(id -> jdbcTemplate.update("DELETE FROM article_tags WHERE tag_id = ?", id));
        insertedTags.forEach(id -> jdbcTemplate.update("DELETE FROM tags WHERE id = ?", id));
        insertedArticles.clear();
        insertedTags.clear();
    }

    private UUID insertArticle(UUID authorUuid, String status, String title) {
        UUID uuid = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO articles (uuid, author_uuid, title, slug, content_md, status, published_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW())",
                uuid, authorUuid, title, "slug-" + uuid, "內容", status);
        insertedArticles.add(uuid);
        return uuid;
    }

    private UUID insertTag(UUID articleUuid, String name) {
        UUID tagId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tags (id, name, slug) VALUES (?, ?, ?)", tagId, name + tagId, "tag-" + tagId);
        jdbcTemplate.update("INSERT INTO article_tags (article_id, tag_id) VALUES (?, ?)", articleUuid, tagId);
        insertedTags.add(tagId);
        return tagId;
    }

    @Test
    @DisplayName("getRecentPublishedArticles：查無作者的已發布文章不出現（維持 INNER JOIN 語意），其餘帶出作者暱稱")
    void getRecentPublishedArticles_excludesArticlesWhoseAuthorIsMissing() {
        UUID kept = insertArticle(KNOWN_AUTHOR, "PUBLISHED", "有作者");
        insertArticle(MISSING_AUTHOR, "PUBLISHED", "查無作者");
        insertArticle(KNOWN_AUTHOR, "DRAFT", "草稿不該出現");

        List<ArticleSummaryInfo> result = articleFacade.getRecentPublishedArticles(UUID.randomUUID(), 50);

        assertThat(result).extracting(ArticleSummaryInfo::uuid).containsExactly(kept);
        assertThat(result.get(0).authorNickname()).isEqualTo("已知作者");
    }

    @Test
    @DisplayName("getPublishedArticlesByUuids：查無作者的文章不出現")
    void getPublishedArticlesByUuids_excludesArticlesWhoseAuthorIsMissing() {
        UUID kept = insertArticle(KNOWN_AUTHOR, "PUBLISHED", "有作者");
        UUID dropped = insertArticle(MISSING_AUTHOR, "PUBLISHED", "查無作者");

        List<ArticleSummaryInfo> result = articleFacade.getPublishedArticlesByUuids(List.of(kept, dropped));

        assertThat(result).extracting(ArticleSummaryInfo::uuid).containsExactly(kept);
        assertThat(result.get(0).authorNickname()).isEqualTo("已知作者");
    }

    @Test
    @DisplayName("getArticlesByTagIds：查無作者的文章不出現，標籤名稱照常帶出")
    void getArticlesByTagIds_excludesArticlesWhoseAuthorIsMissing() {
        UUID kept = insertArticle(KNOWN_AUTHOR, "PUBLISHED", "有作者");
        UUID dropped = insertArticle(MISSING_AUTHOR, "PUBLISHED", "查無作者");
        UUID sharedTag = insertTag(kept, "共同標籤");
        jdbcTemplate.update("INSERT INTO article_tags (article_id, tag_id) VALUES (?, ?)", dropped, sharedTag);

        List<ArticleSummaryInfo> result = articleFacade.getArticlesByTagIds(List.of(sharedTag), UUID.randomUUID(), 10);

        assertThat(result).extracting(ArticleSummaryInfo::uuid).containsExactly(kept);
        assertThat(result.get(0).tagNames()).hasSize(1);
    }

    @Test
    @DisplayName("整頁作者只呼叫 UserFacade 一次（不隨文章數 N+1）")
    void resolvesAuthorsWithSingleUserFacadeCall() {
        insertArticle(KNOWN_AUTHOR, "PUBLISHED", "一");
        insertArticle(KNOWN_AUTHOR, "PUBLISHED", "二");
        insertArticle(MISSING_AUTHOR, "PUBLISHED", "三");

        articleFacade.getRecentPublishedArticles(UUID.randomUUID(), 50);

        verify(userFacade, times(1)).getDisplayInfoByUuids(anyCollection());
    }
}
