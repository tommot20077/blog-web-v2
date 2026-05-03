package dowob.xyz.blog.module.version.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent.Action;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import dowob.xyz.blog.module.version.config.VersionTestApplication;
import dowob.xyz.blog.module.version.consumer.ArticleVersionConsumer;
import dowob.xyz.blog.module.version.mapper.UserPreferenceMapper;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import dowob.xyz.blog.module.version.repository.UserPreferenceRepository;
import dowob.xyz.blog.module.version.service.PreferenceResolver;
import dowob.xyz.blog.module.version.service.VersioningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨模組整合測試：Article ↔ Version。
 *
 * <p>驗證以下端對端流程：</p>
 * <ol>
 *     <li>updateArticle → SAVED event → AUTO 快照寫入</li>
 *     <li>publishArticle → PUBLISHED event → freeze 凍結 + 清除 AUTO 快照</li>
 *     <li>restoreVersion → 文章內容更新 + ArticleUpdatedEvent 觸發</li>
 *     <li>deleteArticle → article_versions ON DELETE CASCADE 清除</li>
 *     <li>preference disabled → 跳過 AUTO 快照寫入</li>
 * </ol>
 *
 * <p>使用 VersionTestApplication，已掃 article 模組（ArticleController 可用），
 * RabbitMQ 由 @MockitoBean 替換，consumer 直接呼叫以模擬 event 處理。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = VersionTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("跨模組整合測試 — Article ↔ Version")
class CrossModuleVersionIT {

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
    @Autowired private ArticleVersionRepository versionRepo;
    @Autowired private UserPreferenceRepository prefRepo;
    @Autowired private UserPreferenceMapper userPreferenceMapper;
    @Autowired private ArticleVersionConsumer consumer;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    @MockitoBean private SeriesFacade seriesFacade;
    @MockitoBean private ArticleEventPublisher articleEventPublisher;

    private static final Long AUTHOR_ID = 1L;

    @BeforeEach
    void setUp() {
        versionRepo.deleteAll();
        prefRepo.deleteAll();
        articleRepo.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        versionRepo.deleteAll();
        prefRepo.deleteAll();
        articleRepo.deleteAll();
    }

    /**
     * 建立模擬認證的 RequestPostProcessor。
     */
    private RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    /** 直接透過 repository 建立 DRAFT 測試文章。 */
    private Article createDraftArticle(Long authorId, String title, String content) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(authorId);
        article.setTitle(title);
        article.setSlug("version-it-" + UUID.randomUUID());
        article.setContent(content);
        article.setContentHtml("<p>" + content + "</p>");
        article.setStatus(ArticleStatus.DRAFT);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return articleRepo.save(article);
    }

    /** 直接透過 repository 建立版本快照。 */
    private ArticleVersion createVersion(Long articleId, Long authorId, String type, String note) {
        ArticleVersion v = new ArticleVersion();
        v.setUuid(UUID.randomUUID());
        v.setArticleId(articleId);
        v.setAuthorId(authorId);
        v.setType(type);
        v.setTitle("Snapshot Title " + UUID.randomUUID());
        v.setSlug("snapshot-slug-" + UUID.randomUUID());
        v.setContent("snapshot content");
        v.setStatus("DRAFT");
        v.setNote(note);
        v.setCreatedAt(LocalDateTime.now());
        return versionRepo.save(v);
    }

    /** 計算某 article 的 AUTO 版本數。 */
    private long countAutoVersions(Long articleId) {
        long count = 0;
        for (ArticleVersion v : versionRepo.findAll()) {
            if (v.getArticleId().equals(articleId)
                    && VersioningService.TYPE_AUTO.equals(v.getType())) {
                count++;
            }
        }
        return count;
    }

    /** 計算某 article 的所有版本數。 */
    private long countAllVersions(Long articleId) {
        long count = 0;
        for (ArticleVersion v : versionRepo.findAll()) {
            if (v.getArticleId().equals(articleId)) count++;
        }
        return count;
    }

    // ─── Test 1: updateArticle → AUTO 快照 ──────────────────────────────────

    @Test
    @DisplayName("updateArticle → SAVED event → AUTO 快照寫入")
    void updateArticle_triggersAutoSnapshot() {
        // 1. 建立 DRAFT 文章
        Article article = createDraftArticle(AUTHOR_ID, "Auto Snapshot Title", "initial content");

        // 2. 直接呼叫 consumer.onContentChanged 模擬 SAVED event
        //    AutoSnapshotPolicy 第一次（無先前快照）會回 true
        ArticleContentChangedEvent event = new ArticleContentChangedEvent(
                article.getId(),
                article.getUuid(),
                AUTHOR_ID,
                Action.SAVED,
                Instant.now()
        );

        consumer.onContentChanged(event, org.mockito.Mockito.mock(com.rabbitmq.client.Channel.class), 1L);

        // 3. 驗證：article_versions 有至少 1 筆 type=AUTO
        long autoCount = countAutoVersions(article.getId());
        assertThat(autoCount).isGreaterThanOrEqualTo(1);
    }

    // ─── Test 2: publishArticle → freeze + 清 AUTO ───────────────────────────

    @Test
    @DisplayName("publishArticle → PUBLISHED event → freeze 凍結 + 清除 AUTO 快照")
    void publishArticle_freezesAndClearsAutos() {
        // 1. 建立 article + 直接寫 3 個 AUTO 版本
        Article article = createDraftArticle(AUTHOR_ID, "Freeze Test", "freeze content");
        for (int i = 0; i < 3; i++) {
            createVersion(article.getId(), AUTHOR_ID, VersioningService.TYPE_AUTO, null);
        }

        // 確認有 3 個 AUTO
        assertThat(countAutoVersions(article.getId())).isEqualTo(3);

        // 2. 呼叫 consumer.onContentChanged with PUBLISHED action
        ArticleContentChangedEvent event = new ArticleContentChangedEvent(
                article.getId(),
                article.getUuid(),
                AUTHOR_ID,
                Action.PUBLISHED,
                Instant.now()
        );

        consumer.onContentChanged(event, org.mockito.Mockito.mock(com.rabbitmq.client.Channel.class), 1L);

        // 3. 驗證：1 筆 PUBLISHED + 0 筆 AUTO（清光）
        List<ArticleVersion> allVersions = new ArrayList<>();
        versionRepo.findAll().forEach(v -> {
            if (v.getArticleId().equals(article.getId())) {
                allVersions.add(v);
            }
        });

        long publishedCount = allVersions.stream()
                .filter(v -> VersioningService.TYPE_PUBLISHED.equals(v.getType()))
                .count();
        long autoCount = allVersions.stream()
                .filter(v -> VersioningService.TYPE_AUTO.equals(v.getType()))
                .count();

        assertThat(publishedCount).isEqualTo(1);
        assertThat(autoCount).isZero();

        // 4. 驗證：PUBLISHED note = "Published v1"
        ArticleVersion published = allVersions.stream()
                .filter(v -> VersioningService.TYPE_PUBLISHED.equals(v.getType()))
                .findFirst()
                .orElseThrow();
        assertThat(published.getNote()).isEqualTo("Published v1");
    }

    // ─── Test 3: restoreVersion → article 更新 + ArticleUpdatedEvent ─────────

    @Test
    @DisplayName("restoreVersion (DRAFT) → 文章內容更新 + ContentChanged(RESTORED)，但不發 publishUpdated")
    void restoreVersion_draftSnapshot_publishesContentChangedOnly() throws Exception {
        // 1. 建立 article (title=A, content=A-content)
        Article article = createDraftArticle(AUTHOR_ID, "Title A", "A-content");

        // 2. 寫 1 筆 MANUAL 快照（使用 article 原始資料；status=DRAFT）
        ArticleVersion snapshot = new ArticleVersion();
        snapshot.setUuid(UUID.randomUUID());
        snapshot.setArticleId(article.getId());
        snapshot.setAuthorId(AUTHOR_ID);
        snapshot.setType(VersioningService.TYPE_MANUAL);
        snapshot.setTitle("Title A");
        snapshot.setSlug(article.getSlug());
        snapshot.setContent("A-content");
        snapshot.setStatus("DRAFT");
        snapshot.setNote("snapshot of A");
        snapshot.setCreatedAt(LocalDateTime.now());
        snapshot = versionRepo.save(snapshot);
        UUID snapshotUuid = snapshot.getUuid();

        // 3. 修改 article (title=B, content=B-content)
        article.setTitle("Title B");
        article.setContent("B-content");
        article.setUpdatedAt(LocalDateTime.now());
        articleRepo.save(article);

        // 確認已改
        Article modified = articleRepo.findById(article.getId()).orElseThrow();
        assertThat(modified.getTitle()).isEqualTo("Title B");

        // 4. 透過 POST /api/v1/articles/{uuid}/versions/{snapshotUuid}/restore 還原
        mockMvc.perform(post("/api/v1/articles/{articleUuid}/versions/{versionUuid}/restore",
                        article.getUuid(), snapshotUuid)
                        .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        // 5. 驗證 article title 已還原為 A
        Article restored = articleRepo.findById(article.getId()).orElseThrow();
        assertThat(restored.getTitle()).isEqualTo("Title A");
        assertThat(restored.getContent()).isEqualTo("A-content");

        // 6. 驗證事件：DRAFT 還原應發 ContentChanged(RESTORED) 但不發 publishUpdated
        //    （否則 search listener 會把文章 re-index 為 PUBLISHED 殘留索引）
        verify(articleEventPublisher).publishContentChanged(any(Article.class),
                eq(dowob.xyz.blog.module.article.event.ArticleContentChangedEvent.Action.RESTORED));
        verify(articleEventPublisher, org.mockito.Mockito.never()).publishUpdated(any(Article.class));
    }

    // ─── Test 4: deleteArticle → CASCADE 清除 versions ──────────────────────

    @Test
    @DisplayName("deleteArticle → article_versions ON DELETE CASCADE 清除")
    void deleteArticle_cascadesVersions() {
        // 1. 建立 article + 寫 2 筆版本
        Article article = createDraftArticle(AUTHOR_ID, "Cascade Test", "cascade content");
        createVersion(article.getId(), AUTHOR_ID, VersioningService.TYPE_AUTO, null);
        createVersion(article.getId(), AUTHOR_ID, VersioningService.TYPE_MANUAL, "manual snap");

        assertThat(countAllVersions(article.getId())).isEqualTo(2);

        // 2. 直接透過 repo 刪除 article（觸發 FK ON DELETE CASCADE）
        articleRepo.delete(article);

        // 3. 驗證：article_versions 中該 article 的 row 全清
        long versionCount = countAllVersions(article.getId());
        assertThat(versionCount).isZero();
    }

    // ─── Test 5: preferenceDisabled → 跳過 AUTO 快照 ───────────────────────

    @Test
    @DisplayName("preferenceDisabled → 跳過 AUTO 快照寫入")
    void preferenceDisabled_skipsAutoSnapshot() {
        // 1. 建立 article
        Article article = createDraftArticle(AUTHOR_ID, "Pref Disabled Test", "content");

        // 2. 透過 userPreferenceMapper.upsert 直接寫 enabled=false
        userPreferenceMapper.upsert(AUTHOR_ID, PreferenceResolver.KEY_ENABLED, "false");

        // 3. 呼叫 consumer.onContentChanged with SAVED
        ArticleContentChangedEvent event = new ArticleContentChangedEvent(
                article.getId(),
                article.getUuid(),
                AUTHOR_ID,
                Action.SAVED,
                Instant.now()
        );

        consumer.onContentChanged(event, org.mockito.Mockito.mock(com.rabbitmq.client.Channel.class), 1L);

        // 4. 驗證：article_versions 中該 article 的 AUTO row 為 0
        long autoCount = countAutoVersions(article.getId());
        assertThat(autoCount).isZero();
    }
}
