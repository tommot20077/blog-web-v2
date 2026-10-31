package dowob.xyz.blog.module.series.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「系列刪除後、文章殘留舊歸屬」的兩個最終一致視窗（M6-b 審查）整合測試：真實 Postgres，經 MockMvc → controller → service。
 *
 * <ul>
 *   <li><b>視窗 A</b>：series 被刪除之後、article 的 {@code ArticleSeriesDeletedConsumer} 處理完之前，
 *       把該文章加入另一個 series 不得誤回 S0106（舊 FK 同步清空歸屬，這種情況過去不可能發生）；
 *       殘留的舊歸屬由 {@code SeriesService} 條件式清掉，且不動任何 article_count。</li>
 *   <li><b>視窗 B</b>：加入流程讀到 series 存在 → 另一個交易刪掉該 series 並 commit → 加入的條件式 UPDATE 才寫入。
 *       沒有 FK 擋下，article_count 的 +1 影響 0 列，此時必須回 S0101 並讓整個交易回滾，不得留下指向
 *       已不存在系列的 ghost 歸屬。</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("系列刪除後的殘留歸屬視窗")
class SeriesStaleMembershipIT {

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
    @Autowired private JdbcTemplate jdbc;

    /** Spy：預設走真實實作，個別案例在「指派」之前先把 series 刪掉，模擬併發刪除 */
    @MockitoSpyBean private ArticleFacade articleFacade;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    @MockitoBean private FileFacade fileFacade;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.execute("UPDATE articles SET series_uuid = NULL, series_position = NULL");
        jdbc.execute("DELETE FROM series");
        jdbc.execute("DELETE FROM articles");
    }

    private RequestPostProcessor asAuthor() {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(Role.AUTHOR.getSpringSecurityRole()));
        Role.AUTHOR.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
                new AuthUser(SeededUsers.USER1_UUID, Role.AUTHOR), null, authorities));
    }

    private UUID insertSeries(String slug) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO series (uuid, title, slug, author_uuid, article_count) VALUES (?, ?, ?, ?, 0)",
                uuid, "T-" + slug, slug, SeededUsers.USER1_UUID);
        return uuid;
    }

    private UUID insertPublishedArticle(UUID seriesUuid, Integer position) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO articles (uuid, author_uuid, title, slug, content_md, content_html, status, "
                        + "series_uuid, series_position) VALUES (?, ?, ?, ?, 'md', '<p>md</p>', 'PUBLISHED', ?, ?)",
                uuid, SeededUsers.USER1_UUID, "t-" + uuid, "slug-" + uuid, seriesUuid, position);
        return uuid;
    }

    private int articleCountOf(UUID seriesUuid) {
        return jdbc.queryForObject("SELECT article_count FROM series WHERE uuid = ?", Integer.class, seriesUuid);
    }

    private UUID seriesUuidOf(UUID articleUuid) {
        return jdbc.queryForObject("SELECT series_uuid FROM articles WHERE uuid = ?", UUID.class, articleUuid);
    }

    @Test
    @DisplayName("視窗 A：series 被刪除、consumer 尚未處理 → 把文章加入另一個 series 應成功（清掉殘留歸屬，舊 series 已不在，不動任何 article_count）")
    void addArticleToOtherSeries_afterSeriesDeleted_beforeConsumerRuns_succeeds() throws Exception {
        UUID oldSeries = insertSeries("stale-old");
        UUID newSeries = insertSeries("stale-new");
        UUID article = insertPublishedArticle(oldSeries, 4);
        jdbc.update("UPDATE series SET article_count = 1 WHERE uuid = ?", oldSeries);
        mockMvc.perform(delete("/api/v1/series/{uuid}", oldSeries).with(asAuthor())).andExpect(status().isOk());
        assertThat(seriesUuidOf(article)).as("V32 起沒有 FK、consumer 也還沒跑：殘留舊歸屬").isEqualTo(oldSeries);

        mockMvc.perform(put("/api/v1/series/{s}/articles/{a}", newSeries, article)
                        .with(asAuthor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(seriesUuidOf(article)).isEqualTo(newSeries);
        assertThat(articleCountOf(newSeries)).isEqualTo(1);
    }

    @Test
    @DisplayName("視窗 A 的對照：文章所屬的另一個 series 仍存在 → 照舊回 S0106，歸屬不被動")
    void addArticleToOtherSeries_whenOldSeriesStillExists_stillS0106() throws Exception {
        UUID oldSeries = insertSeries("alive-old");
        UUID newSeries = insertSeries("alive-new");
        UUID article = insertPublishedArticle(oldSeries, 1);

        mockMvc.perform(put("/api/v1/series/{s}/articles/{a}", newSeries, article)
                        .with(asAuthor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("S0106"));

        assertThat(seriesUuidOf(article)).isEqualTo(oldSeries);
        assertThat(articleCountOf(newSeries)).isZero();
    }

    @Test
    @DisplayName("視窗 B：加入流程讀到 series 存在、指派前 series 被另一個交易刪除並 commit → 回 S0101，交易回滾，文章不會歸屬到已不存在的 series")
    void addArticle_seriesDeletedBetweenReadAndAssign_rollsBackWithoutGhostMembership() throws Exception {
        UUID series = insertSeries("race-ghost");
        UUID article = insertPublishedArticle(null, null);
        doAnswer(invocation -> {
            jdbc.update("DELETE FROM series WHERE uuid = ?", series);
            return invocation.callRealMethod();
        }).when(articleFacade).assignToSeriesIfUnassigned(any(), any(), any());

        mockMvc.perform(put("/api/v1/series/{s}/articles/{a}", series, article)
                        .with(asAuthor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("S0101"));

        assertThat(seriesUuidOf(article)).as("交易回滾：不得留下指向已刪除系列的 ghost 歸屬").isNull();
    }
}
