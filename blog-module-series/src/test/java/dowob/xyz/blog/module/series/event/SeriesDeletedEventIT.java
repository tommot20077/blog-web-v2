package dowob.xyz.blog.module.series.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.event.SeriesDeletedEvent;
import dowob.xyz.blog.infrastructure.event.SeriesEventContract;
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
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 刪除 series 時的 {@code SeriesDeletedEvent}（M4-b）整合測試：真實 Postgres、經 MockMvc → controller → service。
 *
 * <p>重點是<b>時序</b>：事件必須在刪除交易 <b>commit 後</b>才發送（{@code code-standards.md}「Transaction + MQ」）。
 * 做法是在 {@code RabbitTemplate.convertAndSend} 被呼叫的當下，以<b>另一條連線</b>查 series 是否已經消失——
 * 查得到代表資料還沒 commit。同時確認刪除語意不變：{@code articles.series_id} 仍由 FK
 * {@code ON DELETE SET NULL} 清空；發送失敗不影響刪除結果；失敗的請求不發事件。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("刪除 series 的 SeriesDeletedEvent")
class SeriesDeletedEventIT {

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
    @MockitoBean private FileFacade fileFacade;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.execute("UPDATE articles SET series_id = NULL, series_position = NULL");
        jdbc.execute("DELETE FROM series");
        jdbc.execute("DELETE FROM articles");
    }

    private RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
                new AuthUser(userId, SeededUsers.uuidOf(userId), role), null, authorities));
    }

    private UUID createSeriesViaApi(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/series")
                        .with(asUser(SeededUsers.USER1_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "T", "slug", slug))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).at("/data/uuid").asText());
    }

    private Article createArticleInSeries(UUID seriesUuid, int position) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorUuid(SeededUsers.USER1_UUID);
        article.setTitle("In Series " + UUID.randomUUID());
        article.setSlug("in-series-" + UUID.randomUUID());
        article.setContent("content");
        article.setContentHtml("<p>content</p>");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setSeriesId(jdbc.queryForObject("SELECT id FROM series WHERE uuid = ?", Long.class, seriesUuid));
        article.setSeriesPosition(position);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return articleRepo.save(article);
    }

    private int seriesRows(UUID seriesUuid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM series WHERE uuid = ?", Integer.class, seriesUuid);
    }

    @Test
    @DisplayName("DELETE /series/{uuid} → 發送一次 SeriesDeletedEvent（正確的 exchange、routing key、seriesUuid），且發送當下刪除已 commit")
    void deleteSeries_publishesEventAfterCommit() throws Exception {
        UUID seriesUuid = createSeriesViaApi("event-after-commit");
        AtomicReference<Integer> rowsVisibleAtSendTime = new AtomicReference<>();
        doAnswer(inv -> {
            rowsVisibleAtSendTime.set(seriesRows(seriesUuid));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(SeriesEventContract.EXCHANGE), eq(SeriesEventContract.ROUTING_KEY_DELETED), any(Object.class));

        mockMvc.perform(delete("/api/v1/series/{uuid}", seriesUuid)
                        .with(asUser(SeededUsers.USER1_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(
                eq(SeriesEventContract.EXCHANGE), eq(SeriesEventContract.ROUTING_KEY_DELETED), captor.capture());
        assertThat(captor.getValue()).isInstanceOfSatisfying(SeriesDeletedEvent.class, event -> {
            assertThat(event.seriesUuid()).isEqualTo(seriesUuid);
            assertThat(event.eventId()).isNotNull();
        });
        assertThat(rowsVisibleAtSendTime.get())
                .as("發送當下（另一條連線）series 列已消失，代表刪除交易已 commit").isZero();
    }

    @Test
    @DisplayName("刪除語意不變：成員文章的 series_id 仍由 ON DELETE SET NULL 清空，文章本身保留")
    void deleteSeries_stillUnlinksArticlesByForeignKey() throws Exception {
        UUID seriesUuid = createSeriesViaApi("event-unlink");
        Article article = createArticleInSeries(seriesUuid, 1);

        mockMvc.perform(delete("/api/v1/series/{uuid}", seriesUuid)
                        .with(asUser(SeededUsers.USER1_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        assertThat(seriesRows(seriesUuid)).isZero();
        Article unlinked = articleRepo.findById(article.getId()).orElseThrow();
        assertThat(unlinked.getSeriesId()).isNull();
    }

    @Test
    @DisplayName("MQ 發送失敗（best-effort）→ 刪除仍成功回 200，series 已刪除")
    void deleteSeries_whenBrokerFails_stillSucceeds() throws Exception {
        UUID seriesUuid = createSeriesViaApi("event-broker-down");
        doThrow(new AmqpConnectException(new RuntimeException("broker down")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        mockMvc.perform(delete("/api/v1/series/{uuid}", seriesUuid)
                        .with(asUser(SeededUsers.USER1_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(seriesRows(seriesUuid)).isZero();
    }

    @Test
    @DisplayName("非 owner 刪除（S0102）與 series 不存在（S0101）→ 不刪除、不發事件")
    void deleteSeries_rejectedRequests_publishNothing() throws Exception {
        UUID seriesUuid = createSeriesViaApi("event-denied");

        mockMvc.perform(delete("/api/v1/series/{uuid}", seriesUuid)
                        .with(asUser(SeededUsers.USER2_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("S0102"));
        mockMvc.perform(delete("/api/v1/series/{uuid}", UUID.randomUUID())
                        .with(asUser(SeededUsers.USER1_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("S0101"));

        assertThat(seriesRows(seriesUuid)).isEqualTo(1);
        verify(rabbitTemplate, never()).convertAndSend(
                eq(SeriesEventContract.EXCHANGE), anyString(), any(Object.class));
    }
}
