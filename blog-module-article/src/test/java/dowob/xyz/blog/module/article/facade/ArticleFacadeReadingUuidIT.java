package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryResponse;
import dowob.xyz.blog.infrastructure.facade.dto.AuthorInfo;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesBasicInfo;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.config.ArticleTestApplication;
import dowob.xyz.blog.module.article.service.ArticleQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * reading 模組使用的 UUID 版 {@link ArticleFacade} 方法，對真實 PostgreSQL 的整合測試（M2-a）：
 * {@code getSummariesByUuids}、{@code filterReadableUuids}、{@code incrementLikeCount(UUID)}、
 * {@code decrementLikeCount(UUID)}。
 *
 * <p>文章直接以 SQL 寫入，避免耦合 article 模組的建立流程（事件、標籤、檔案綁定）。
 * 跨模組 facade（reading、series、user）以 mock 取代，用來設定登入者的 liked／bookmarked／progress
 * 與 series 導覽補值，並驗證這些補值查詢都是整批一次（不 N+1）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ArticleTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleFacade reading 用 UUID 版方法整合測試")
class ArticleFacadeReadingUuidIT {

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
    @Autowired private ArticleQueryService articleQueryService;
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
    /** 本測試寫入的使用者 id，供清理 */
    private final List<Long> insertedUsers = new ArrayList<>();
    /** 本測試寫入的 series id，供清理 */
    private final List<Long> insertedSeries = new ArrayList<>();
    /** 本測試寫入的 tag id，供清理 */
    private final List<UUID> insertedTags = new ArrayList<>();

    /** 種子作者（id=1）的 uuid */
    private UUID author1Uuid;
    /** 本測試另建的第二位使用者 */
    private long author2Id;
    private UUID author2Uuid;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        reset(readingFacade, seriesFacade, userFacade);
        author1Uuid = jdbcTemplate.queryForObject(
                "SELECT uuid FROM users WHERE id = ?", UUID.class, SEEDED_AUTHOR_ID);
        author2Uuid = UUID.randomUUID();
        author2Id = insertUser(author2Uuid);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        insertedArticles.forEach(uuid -> jdbcTemplate.update("DELETE FROM articles WHERE uuid = ?", uuid));
        insertedTags.forEach(id -> jdbcTemplate.update("DELETE FROM tags WHERE id = ?", id));
        insertedSeries.forEach(id -> jdbcTemplate.update("DELETE FROM series WHERE id = ?", id));
        insertedUsers.forEach(id -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", id));
        insertedArticles.clear();
        insertedTags.clear();
        insertedSeries.clear();
        insertedUsers.clear();
    }

    /**
     * 寫入一位測試使用者。種子使用者是帶明確 id 插入的，users 的序列不會前進，
     * 因此這裡也以明確 id 插入（取目前最大 id + 1000），避開與種子資料的主鍵衝突。
     */
    private long insertUser(UUID uuid) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO users (id, uuid, username, email, password_hash, nickname, role, status, email_verified) "
                        + "VALUES ((SELECT COALESCE(MAX(id), 0) + 1000 FROM users), ?, ?, ?, 'x', ?, 'AUTHOR', 'ACTIVE', TRUE) "
                        + "RETURNING id",
                Long.class, uuid, "u" + uuid.toString().substring(0, 8), "u-" + uuid + "@test.com",
                "nick-" + uuid.toString().substring(0, 8));
        insertedUsers.add(id);
        return id;
    }

    private UUID insertArticle(long authorId, String status, int likeCount) {
        UUID uuid = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO articles (uuid, author_id, title, slug, content_md, status, like_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                uuid, authorId, "t-" + uuid, "slug-" + uuid, "內容", status, likeCount);
        insertedArticles.add(uuid);
        return uuid;
    }

    private long idOf(UUID articleUuid) {
        return jdbcTemplate.queryForObject("SELECT id FROM articles WHERE uuid = ?", Long.class, articleUuid);
    }

    private long likeCountOf(UUID uuid) {
        Long n = jdbcTemplate.queryForObject("SELECT like_count FROM articles WHERE uuid = ?", Long.class, uuid);
        return n == null ? -1 : n;
    }

    private void loginAs(long id, UUID uuid, Role role) {
        AuthUser principal = new AuthUser(id, uuid, role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    /** 比照 filterReadableIds 的結果轉成 uuid，作為 filterReadableUuids 的對照基準 */
    private List<UUID> readableUuidsViaIds(List<UUID> candidates, Long viewerId, boolean isAdmin) {
        List<UUID> known = candidates.stream().filter(u -> !isUnknown(u)).toList();
        List<Long> ids = known.stream().map(this::idOf).toList();
        List<Long> readable = articleFacade.filterReadableIds(ids, viewerId, isAdmin);
        return known.stream().filter(u -> readable.contains(idOf(u))).toList();
    }

    private boolean isUnknown(UUID uuid) {
        return !insertedArticles.contains(uuid);
    }

    // ─── filterReadableUuids ───

    @Test
    @DisplayName("filterReadableUuids：各種狀態 × 匿名／作者／他人／ADMIN，結果與 filterReadableIds 一致且保留輸入順序")
    void filterReadableUuids_matchesFilterReadableIds_forEveryViewerAndStatus() {
        UUID publishedByA1 = insertArticle(SEEDED_AUTHOR_ID, "PUBLISHED", 0);
        UUID draftByA1 = insertArticle(SEEDED_AUTHOR_ID, "DRAFT", 0);
        UUID pendingByA1 = insertArticle(SEEDED_AUTHOR_ID, "PENDING_REVIEW", 0);
        UUID archivedByA1 = insertArticle(SEEDED_AUTHOR_ID, "ARCHIVED", 0);
        UUID publishedByA2 = insertArticle(author2Id, "PUBLISHED", 0);
        UUID draftByA2 = insertArticle(author2Id, "DRAFT", 0);
        UUID rejectedByA2 = insertArticle(author2Id, "REJECTED", 0);
        /** 刻意與建立順序不同，驗證輸出順序以輸入為準 */
        List<UUID> candidates = List.of(rejectedByA2, publishedByA1, draftByA2, archivedByA1,
                publishedByA2, draftByA1, pendingByA1);

        Object[][] viewers = {
                {null, null, false},
                {SEEDED_AUTHOR_ID, author1Uuid, false},
                {author2Id, author2Uuid, false},
                {author2Id, author2Uuid, true},
        };
        for (Object[] v : viewers) {
            Long viewerId = (Long) v[0];
            UUID viewerUuid = (UUID) v[1];
            boolean admin = (Boolean) v[2];

            List<UUID> byUuid = articleFacade.filterReadableUuids(candidates, viewerUuid, admin);

            assertThat(byUuid)
                    .as("viewer=%s admin=%s", viewerUuid, admin)
                    .containsExactlyElementsOf(readableUuidsViaIds(candidates, viewerId, admin));
        }
    }

    @Test
    @DisplayName("filterReadableUuids：匿名只看得到 PUBLISHED；作者本人加看自己的非公開；ADMIN 全看得到")
    void filterReadableUuids_explicitExpectations() {
        UUID publishedByA1 = insertArticle(SEEDED_AUTHOR_ID, "PUBLISHED", 0);
        UUID draftByA1 = insertArticle(SEEDED_AUTHOR_ID, "DRAFT", 0);
        UUID draftByA2 = insertArticle(author2Id, "DRAFT", 0);
        List<UUID> candidates = List.of(draftByA2, draftByA1, publishedByA1);

        assertThat(articleFacade.filterReadableUuids(candidates, null, false))
                .containsExactly(publishedByA1);
        assertThat(articleFacade.filterReadableUuids(candidates, author1Uuid, false))
                .containsExactly(draftByA1, publishedByA1);
        assertThat(articleFacade.filterReadableUuids(candidates, author2Uuid, false))
                .containsExactly(draftByA2, publishedByA1);
        assertThat(articleFacade.filterReadableUuids(candidates, author2Uuid, true))
                .containsExactly(draftByA2, draftByA1, publishedByA1);
    }

    @Test
    @DisplayName("filterReadableUuids：查無的 uuid 被略過；null 與空清單回空清單")
    void filterReadableUuids_unknownNullAndEmpty() {
        UUID published = insertArticle(SEEDED_AUTHOR_ID, "PUBLISHED", 0);

        assertThat(articleFacade.filterReadableUuids(List.of(UUID.randomUUID(), published), null, false))
                .containsExactly(published);
        assertThat(articleFacade.filterReadableUuids(null, null, false)).isEmpty();
        assertThat(articleFacade.filterReadableUuids(List.of(), null, false)).isEmpty();
    }

    // ─── like count ───

    @Test
    @DisplayName("incrementLikeCount(UUID)：只讓該文章 like_count +1，不影響其他文章")
    void incrementLikeCountByUuid_incrementsOnlyTargetArticle() {
        UUID target = insertArticle(SEEDED_AUTHOR_ID, "PUBLISHED", 2);
        UUID other = insertArticle(SEEDED_AUTHOR_ID, "PUBLISHED", 5);

        articleFacade.incrementLikeCount(target);

        assertThat(likeCountOf(target)).isEqualTo(3);
        assertThat(likeCountOf(other)).isEqualTo(5);
    }

    @Test
    @DisplayName("decrementLikeCount(UUID)：-1，且不影響其他文章")
    void decrementLikeCountByUuid_decrementsOnlyTargetArticle() {
        UUID target = insertArticle(SEEDED_AUTHOR_ID, "PUBLISHED", 2);
        UUID other = insertArticle(SEEDED_AUTHOR_ID, "PUBLISHED", 5);

        articleFacade.decrementLikeCount(target);

        assertThat(likeCountOf(target)).isEqualTo(1);
        assertThat(likeCountOf(other)).isEqualTo(5);
    }

    @Test
    @DisplayName("decrementLikeCount(UUID)：計數為 0 時守衛 > 0，不會變成負數")
    void decrementLikeCountByUuid_atZero_staysZero() {
        UUID target = insertArticle(SEEDED_AUTHOR_ID, "PUBLISHED", 0);

        articleFacade.decrementLikeCount(target);

        assertThat(likeCountOf(target)).isZero();
    }

    @Test
    @DisplayName("like count 的 UUID 版與 Long 版結果一致")
    void likeCount_uuidVersionMatchesLongVersion() {
        UUID viaUuid = insertArticle(SEEDED_AUTHOR_ID, "PUBLISHED", 1);
        UUID viaId = insertArticle(SEEDED_AUTHOR_ID, "PUBLISHED", 1);

        articleFacade.incrementLikeCount(viaUuid);
        articleFacade.incrementLikeCount(idOf(viaId));
        articleFacade.decrementLikeCount(viaUuid);
        articleFacade.decrementLikeCount(idOf(viaId));

        assertThat(likeCountOf(viaUuid)).isEqualTo(likeCountOf(viaId)).isEqualTo(1);
    }

    @Test
    @DisplayName("increment／decrement 對不存在的 uuid 是 no-op，不拋例外")
    void likeCountOperations_unknownUuid_areNoOps() {
        UUID unknown = UUID.randomUUID();

        articleFacade.incrementLikeCount(unknown);
        articleFacade.decrementLikeCount(unknown);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM articles WHERE uuid = ?", Integer.class, unknown)).isZero();
    }

    // ─── getSummariesByUuids ───

    /** 建立三篇文章（a 屬於 series 且有 tag），並設定作者與 series 的 mock 回應；回傳 [a, b, c] */
    private List<UUID> seedSummaryArticles() {
        UUID a = insertArticle(SEEDED_AUTHOR_ID, "PUBLISHED", 3);
        UUID b = insertArticle(author2Id, "PUBLISHED", 1);
        UUID c = insertArticle(SEEDED_AUTHOR_ID, "DRAFT", 0);

        Long seriesId = jdbcTemplate.queryForObject(
                "INSERT INTO series (title, slug, author_uuid) VALUES ('系列', ?, ?) RETURNING id",
                Long.class, "s-" + UUID.randomUUID(), author1Uuid);
        insertedSeries.add(seriesId);
        jdbcTemplate.update("UPDATE articles SET series_id = ?, series_position = 2 WHERE uuid = ?", seriesId, a);

        UUID tagId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tags (id, name, slug) VALUES (?, ?, ?)",
                tagId, "tag-" + tagId, "tag-" + tagId);
        insertedTags.add(tagId);
        jdbcTemplate.update("INSERT INTO article_tags (article_id, tag_id) VALUES (?, ?)", a, tagId);

        when(userFacade.getAuthorInfoByIds(anyCollection())).thenReturn(Map.of(
                SEEDED_AUTHOR_ID, new AuthorInfo(author1Uuid, "TestAuthor"),
                author2Id, new AuthorInfo(author2Uuid, "Author2")));
        when(seriesFacade.batchGetSeriesBasicInfo(anyCollection())).thenReturn(Map.of(
                seriesId, new SeriesBasicInfo(UUID.randomUUID(), "系列")));
        return List.of(a, b, c);
    }

    @Test
    @DisplayName("getSummariesByUuids（已登入）：順序＝輸入順序、查無者略過，且與 getArticleSummariesByIds 逐欄位相等")
    void getSummariesByUuids_loggedIn_matchesIdVersionFieldByField() {
        List<UUID> seeded = seedSummaryArticles();
        UUID a = seeded.get(0);
        UUID b = seeded.get(1);
        UUID c = seeded.get(2);
        long viewerId = author2Id;
        loginAs(viewerId, author2Uuid, Role.AUTHOR);
        when(readingFacade.batchIsLiked(any(), anyList())).thenReturn(Set.of(a));
        when(readingFacade.batchIsBookmarked(any(), anyList())).thenReturn(Set.of(b));
        when(readingFacade.batchGetProgress(any(), anyList()))
                .thenReturn(Map.of(c, new BigDecimal("0.50")));

        List<ArticleSummaryResponse> byUuid =
                articleFacade.getSummariesByUuids(List.of(c, UUID.randomUUID(), a, b));
        List<ArticleSummaryResponse> byId =
                articleQueryService.getArticleSummariesByIds(List.of(idOf(c), idOf(a), idOf(b)));

        assertThat(byUuid).extracting(ArticleSummaryResponse::getUuid).containsExactly(c, a, b);
        assertThat(byUuid).usingRecursiveComparison().isEqualTo(byId);
        assertThat(byUuid).extracting(ArticleSummaryResponse::getLiked).containsExactly(false, true, false);
        assertThat(byUuid).extracting(ArticleSummaryResponse::getBookmarked).containsExactly(false, false, true);
        assertThat(byUuid.get(0).getLastReadProgress()).isEqualByComparingTo("0.50");
        assertThat(byUuid.get(1).getSeriesTitle()).isEqualTo("系列");
        assertThat(byUuid.get(1).getSeriesUuid()).isNotNull();
        assertThat(byUuid.get(1).getTags()).hasSize(1);
        assertThat(byUuid.get(1).getAuthorUuid()).isEqualTo(author1Uuid);
    }

    @Test
    @DisplayName("getSummariesByUuids：補值查詢整批一次（liked／bookmarked／progress／series／作者各 1 次），不隨文章數 N+1")
    void getSummariesByUuids_enrichmentIsBatched() {
        List<UUID> seeded = seedSummaryArticles();
        loginAs(author2Id, author2Uuid, Role.AUTHOR);
        when(readingFacade.batchIsLiked(any(), anyList())).thenReturn(Set.of());
        when(readingFacade.batchIsBookmarked(any(), anyList())).thenReturn(Set.of());
        when(readingFacade.batchGetProgress(any(), anyList())).thenReturn(Map.of());

        articleFacade.getSummariesByUuids(seeded);

        verify(readingFacade, times(1)).batchIsLiked(any(), anyList());
        verify(readingFacade, times(1)).batchIsBookmarked(any(), anyList());
        verify(readingFacade, times(1)).batchGetProgress(any(), anyList());
        verify(seriesFacade, times(1)).batchGetSeriesBasicInfo(anyCollection());
        verify(userFacade, times(1)).getAuthorInfoByIds(anyCollection());
    }

    @Test
    @DisplayName("getSummariesByUuids（未登入）：liked／bookmarked 為 false、progress 為 null，且不查 reading")
    void getSummariesByUuids_anonymous_defaultsAndNoReadingQuery() {
        List<UUID> seeded = seedSummaryArticles();

        List<ArticleSummaryResponse> byUuid = articleFacade.getSummariesByUuids(seeded);
        List<ArticleSummaryResponse> byId = articleQueryService.getArticleSummariesByIds(
                seeded.stream().map(this::idOf).toList());

        assertThat(byUuid).hasSize(3);
        assertThat(byUuid).allSatisfy(s -> {
            assertThat(s.getLiked()).isFalse();
            assertThat(s.getBookmarked()).isFalse();
            assertThat(s.getLastReadProgress()).isNull();
        });
        assertThat(byUuid).usingRecursiveComparison().isEqualTo(byId);
        verify(readingFacade, never()).batchIsLiked(any(), anyList());
        verify(readingFacade, never()).batchIsBookmarked(any(), anyList());
        verify(readingFacade, never()).batchGetProgress(any(), anyList());
    }

    @Test
    @DisplayName("getSummariesByUuids：null、空清單、全部查無都回空清單")
    void getSummariesByUuids_nullEmptyAndUnknown_returnEmpty() {
        assertThat(articleFacade.getSummariesByUuids(null)).isEmpty();
        assertThat(articleFacade.getSummariesByUuids(List.of())).isEmpty();
        assertThat(articleFacade.getSummariesByUuids(List.of(UUID.randomUUID()))).isEmpty();
        verify(readingFacade, never()).batchIsLiked(any(), anyList());
    }

    @Test
    @DisplayName("getSummariesByUuids：不過濾狀態（物件級讀取），草稿也回傳；重複 uuid 只回一筆")
    void getSummariesByUuids_isStatusAgnostic_andDeduplicates() {
        List<UUID> seeded = seedSummaryArticles();
        UUID draft = seeded.get(2);

        List<ArticleSummaryResponse> result = articleFacade.getSummariesByUuids(List.of(draft, draft));

        assertThat(result).extracting(ArticleSummaryResponse::getUuid).containsExactly(draft);
    }
}
