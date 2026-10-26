package dowob.xyz.blog.module.series.race;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.series.config.SeriesTestApplication;
import dowob.xyz.blog.module.series.support.SeededUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * series 模組兩個併發問題的整合測試（真實 Postgres、經 MockMvc → controller → service → 交易，不 mock 任何約束）。
 *
 * <ul>
 *   <li><b>RACE-14</b>：{@code addArticleToSeries} 以「讀到的 seriesUuid 是否為 null」判斷是否為新成員
 *       （check-then-act）。兩個請求同時加入同一篇文章，都讀到 null，都 {@code article_count + 1}，
 *       結果 +2。新實作由寫入本身（{@code UPDATE ... WHERE series_uuid IS NULL} 的 affected rows）決定。
 *       移出同理：兩個請求都讀到「在此 series」，都 -1。</li>
 *   <li><b>RACE-15</b>：slug 唯一性以 {@code existsBySlug} 先查再 save，併發撞到 UNIQUE 會變成 500
 *       而非 {@code S0104}（建立與更新兩處）。新實作建立用 {@code INSERT ... ON CONFLICT DO NOTHING}，
 *       更新捕捉 {@code DuplicateKeyException}。</li>
 * </ul>
 *
 * <p>併發案例以 {@link CyclicBarrier} 讓兩個執行緒同時送出請求並重複多輪。正確實作在任何交錯下結果都相同，
 * 因此這些斷言不會是 flaky 的來源；舊實作則會在多輪中至少落敗一次。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("series 併發問題整合測試（RACE-14 重複加入／移出、RACE-15 slug 唯一性）")
class SeriesRaceConditionIT {

    /** 併發案例重複的輪數 */
    private static final int ROUNDS = 25;

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

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ArticleRepository articleRepo;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    /** article 模組的 ArticleFileBinder 依賴 FileFacade，其實作在 blog-module-file（未被本測試 scan） */
    @MockitoBean private FileFacade fileFacade;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.execute("DELETE FROM processed_events");
        jdbc.execute("UPDATE articles SET series_uuid = NULL, series_position = NULL");
        jdbc.execute("DELETE FROM series");
        jdbc.execute("DELETE FROM articles");
    }

    private RequestPostProcessor asAuthor() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Role.AUTHOR.getSpringSecurityRole()));
        Role.AUTHOR.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                new AuthUser(SeededUsers.USER1_ID, SeededUsers.USER1_UUID, Role.AUTHOR), null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    /** 以 HTTP 建立 series 並回傳其 uuid。 */
    private UUID createSeriesViaApi(String slug) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/series")
                        .with(asAuthor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "T-" + slug, "slug", slug, "description", "d"))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("建立 series %s", slug).isEqualTo(200);
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/uuid").asText());
    }

    private Article createPublishedArticle() {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorUuid(SeededUsers.USER1_UUID);
        article.setTitle("Race Article " + UUID.randomUUID());
        article.setSlug("race-article-" + UUID.randomUUID());
        article.setContent("content");
        article.setContentHtml("<p>content</p>");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return articleRepo.save(article);
    }

    private int addArticle(UUID seriesUuid, UUID articleUuid, int position) throws Exception {
        return mockMvc.perform(put("/api/v1/series/{s}/articles/{a}", seriesUuid, articleUuid)
                        .with(asAuthor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":" + position + "}"))
                .andReturn().getResponse().getStatus();
    }

    private int removeArticle(UUID seriesUuid, UUID articleUuid) throws Exception {
        return mockMvc.perform(delete("/api/v1/series/{s}/articles/{a}", seriesUuid, articleUuid)
                        .with(asAuthor()))
                .andReturn().getResponse().getStatus();
    }

    private int articleCountOf(UUID seriesUuid) {
        return jdbc.queryForObject("SELECT article_count FROM series WHERE uuid = ?", Integer.class, seriesUuid);
    }

    private int memberRowsOf(UUID seriesUuid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM articles WHERE series_uuid = ?", Integer.class, seriesUuid);
    }

    /** 回應的 HTTP 狀態碼與業務錯誤碼（成功為 {@code 00000}）。 */
    private record Outcome(int status, String code) {
    }

    private Outcome outcomeOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        String code = body.isBlank() ? "" : objectMapper.readTree(body).path("code").asText();
        return new Outcome(result.getResponse().getStatus(), code);
    }

    /** 讓兩個執行緒同時執行 action（各取一個 index），回傳兩者的結果；任一執行緒拋例外會讓測試失敗。 */
    private <T> List<T> runConcurrently(java.util.function.IntFunction<Callable<T>> actionOfThread) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<T> action = actionOfThread.apply(i);
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return action.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ─── RACE-14：加入 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("RACE-14：同一篇文章依序加入同一 series 兩次（第二次走更新位置）→ 兩次都 200、article_count 只 +1")
    void addSameArticleTwiceSequentially_countIncrementedOnce() throws Exception {
        UUID series = createSeriesViaApi("race-seq-add");
        Article article = createPublishedArticle();

        assertThat(addArticle(series, article.getUuid(), 1)).isEqualTo(200);
        assertThat(addArticle(series, article.getUuid(), 2)).isEqualTo(200);

        assertThat(articleCountOf(series)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT series_position FROM articles WHERE uuid = ?",
                Integer.class, article.getUuid())).as("第二次只更新位置").isEqualTo(2);
    }

    @Test
    @DisplayName("RACE-14：兩條執行緒同時把同一篇文章加入同一 series（重複多輪）→ 全部 200、article_count 只 +1")
    void addSameArticleConcurrently_countIncrementedOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID series = createSeriesViaApi("race-add-" + round);
            Article article = createPublishedArticle();

            List<Integer> statuses = runConcurrently(i ->
                    () -> addArticle(series, article.getUuid(), i + 1));

            assertThat(statuses).as("第 %d 輪的 HTTP 狀態", round).containsExactly(200, 200);
            assertThat(articleCountOf(series)).as("第 %d 輪的 article_count", round).isEqualTo(1);
            assertThat(memberRowsOf(series)).as("第 %d 輪的成員數", round).isEqualTo(1);
        }
    }

    // ─── RACE-14：「已在同一 series，只更新位置」路徑 ────────────────────────

    @Test
    @DisplayName("RACE-14：兩條執行緒同時更新同一篇已在 series 內文章的位置（重複多輪）→ 全部 200（不得 500）、article_count 與成員數不變")
    void updatePositionConcurrently_bothOk_noServerError_countUnchanged() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID series = createSeriesViaApi("race-pos-" + round);
            Article article = createPublishedArticle();
            assertThat(addArticle(series, article.getUuid(), 1)).isEqualTo(200);

            List<Integer> statuses = runConcurrently(i ->
                    () -> addArticle(series, article.getUuid(), i + 2));

            assertThat(statuses).as("第 %d 輪的 HTTP 狀態（讀改寫的位置更新會因樂觀鎖衝突回 500）", round)
                    .containsExactly(200, 200);
            assertThat(articleCountOf(series)).as("第 %d 輪的 article_count", round).isEqualTo(1);
            assertThat(memberRowsOf(series)).as("第 %d 輪的成員數", round).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT series_position FROM articles WHERE uuid = ?",
                    Integer.class, article.getUuid())).as("第 %d 輪的位置為其中一個請求的值", round).isIn(2, 3);
        }
    }

    @Test
    @DisplayName("RACE-14：一條執行緒移出、另一條同時更新位置（重複多輪）→ 不得 500，且 article_count 恆等於成員數")
    void removeAndUpdatePositionConcurrently_countAlwaysMatchesMembership() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID series = createSeriesViaApi("race-rm-pos-" + round);
            Article article = createPublishedArticle();
            assertThat(addArticle(series, article.getUuid(), 1)).isEqualTo(200);

            List<Integer> statuses = runConcurrently(i -> i == 0
                    ? () -> removeArticle(series, article.getUuid())
                    : () -> addArticle(series, article.getUuid(), 5));

            assertThat(statuses).as("第 %d 輪的 HTTP 狀態：只能是成功或業務錯誤，不得 500", round)
                    .allSatisfy(status -> assertThat(status).isIn(200, 400));
            assertThat(articleCountOf(series))
                    .as("第 %d 輪：文章被重新寫回 series 卻沒有 +1，會出現 article_count=0 但成員數=1", round)
                    .isEqualTo(memberRowsOf(series));
        }
    }

    // ─── RACE-14：移出 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("RACE-14：兩條執行緒同時把同一篇文章移出 series（重複多輪）→ 不得 500、article_count 只 -1、成員數一致")
    void removeSameArticleConcurrently_countDecrementedOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID series = createSeriesViaApi("race-remove-" + round);
            Article staying = createPublishedArticle();
            Article leaving = createPublishedArticle();
            assertThat(addArticle(series, staying.getUuid(), 1)).isEqualTo(200);
            assertThat(addArticle(series, leaving.getUuid(), 2)).isEqualTo(200);
            assertThat(articleCountOf(series)).isEqualTo(2);

            List<Integer> statuses = runConcurrently(i ->
                    () -> removeArticle(series, leaving.getUuid()));

            assertThat(statuses).as("第 %d 輪的 HTTP 狀態：一個成功，另一個與依序第二次移出相同（S0105，400）", round)
                    .containsExactlyInAnyOrder(200, 400);
            assertThat(articleCountOf(series)).as("第 %d 輪的 article_count", round).isEqualTo(1);
            assertThat(memberRowsOf(series)).as("第 %d 輪的成員數", round).isEqualTo(1);
        }
    }

    // ─── RACE-15：slug ─────────────────────────────────────────────────────

    @Test
    @DisplayName("RACE-15：以已存在的 slug 依序建立第二個 series → 400 S0104")
    void createDuplicateSlugSequentially_returnsSlugAlreadyUsed() throws Exception {
        createSeriesViaApi("race-dup-seq");

        MvcResult result = mockMvc.perform(post("/api/v1/series")
                        .with(asAuthor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "other", "slug", "race-dup-seq"))))
                .andReturn();

        assertThat(outcomeOf(result)).isEqualTo(new Outcome(400, "S0104"));
    }

    @Test
    @DisplayName("RACE-15：兩條執行緒同時以相同 slug 建立 series（重複多輪）→ 一個 200、另一個 400 S0104，絕不 500")
    void createSameSlugConcurrently_oneWins_otherSlugAlreadyUsed() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String slug = "race-create-" + round;

            List<Outcome> outcomes = runConcurrently(i -> () -> outcomeOf(mockMvc.perform(post("/api/v1/series")
                            .with(asAuthor())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("title", "title-" + i, "slug", slug))))
                    .andReturn()));

            assertThat(outcomes).as("第 %d 輪", round)
                    .containsExactlyInAnyOrder(new Outcome(200, "00000"), new Outcome(400, "S0104"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM series WHERE slug = ?", Integer.class, slug))
                    .as("第 %d 輪的 series 列數", round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("RACE-15：兩條執行緒同時把兩個不同 series 的 slug 改成同一個值（重複多輪）→ 一個 200、另一個 400 S0104，絕不 500")
    void updateToSameSlugConcurrently_oneWins_otherSlugAlreadyUsed() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID first = createSeriesViaApi("race-upd-a-" + round);
            UUID second = createSeriesViaApi("race-upd-b-" + round);
            String target = "race-upd-target-" + round;
            UUID[] series = {first, second};

            List<Outcome> outcomes = runConcurrently(i -> () -> outcomeOf(mockMvc.perform(
                            put("/api/v1/series/{uuid}", series[i])
                                    .with(asAuthor())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(Map.of("slug", target))))
                    .andReturn()));

            assertThat(outcomes).as("第 %d 輪", round)
                    .containsExactlyInAnyOrder(new Outcome(200, "00000"), new Outcome(400, "S0104"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM series WHERE slug = ?", Integer.class, target))
                    .as("第 %d 輪的 series 列數", round).isEqualTo(1);
        }
    }
}
