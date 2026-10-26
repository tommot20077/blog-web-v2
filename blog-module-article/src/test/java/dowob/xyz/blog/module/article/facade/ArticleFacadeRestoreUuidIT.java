package dowob.xyz.blog.module.article.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreSource;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.config.ArticleTestApplication;
import dowob.xyz.blog.module.article.service.ArticleMarkdownRenderer;
import dowob.xyz.blog.module.article.service.ArticleTocCodec;
import dowob.xyz.blog.module.article.service.RenderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * M3-a：UUID 版 {@link ArticleFacade#findContentByUuid} 與
 * {@link ArticleFacade#applyRestoreContent(UUID, ArticleRestoreSource)} 對真實 PostgreSQL 的整合測試。
 *
 * <p>驗證重點：UUID 版（article 內部渲染）與 Long 版（呼叫端自行 render 後傳入）還原出的文章
 * 在 title／slug／content／content_html／toc／summary／cover／tags 上逐欄位相同；
 * 還原不改狀態（SEC-02）；內容凍結守衛照舊；{@code findContentByUuid} 與 {@code findContentById} 結果相等。</p>
 *
 * <p>文章直接以 SQL 寫入，避免耦合 article 模組的建立流程。tag／file／user 等跨模組 facade 與 RabbitMQ 以 mock 取代，
 * tags 以 {@code TagFacade.syncArticleTags} 收到的參數作為比對對象。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ArticleTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleFacade M3-a UUID 版還原與內容查詢整合測試")
class ArticleFacadeRestoreUuidIT {

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

    private static final String MARKDOWN =
            "# 標題\n\n前言\n\n## 第一節\n\n內容\n\n### 第一節之一\n\n細節\n\n## 第二節\n\n結尾";

    @Autowired private ArticleFacade articleFacade;
    @Autowired private ArticleMarkdownRenderer renderer;
    @Autowired private ArticleTocCodec tocCodec;
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

    @BeforeEach
    void setUp() {
        reset(tagFacade, fileFacade, rabbitTemplate);
    }

    @AfterEach
    void cleanUp() {
        insertedArticles.forEach(uuid -> jdbcTemplate.update("DELETE FROM articles WHERE uuid = ?", uuid));
        insertedArticles.clear();
    }

    private UUID insertArticle(String status) {
        UUID uuid = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO articles (uuid, author_uuid, title, slug, summary, content_md, content_html, toc, "
                        + "cover_image_url, status, reject_reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                uuid, SEEDED_AUTHOR_UUID, "舊標題-" + uuid, "old-" + uuid, "舊摘要", "舊內容", "<p>舊</p>", "[]",
                "https://cdn/old.jpg", status, "REJECTED".equals(status) ? "內部評語" : null);
        insertedArticles.add(uuid);
        return uuid;
    }

    private long idOf(UUID uuid) {
        return jdbcTemplate.queryForObject("SELECT id FROM articles WHERE uuid = ?", Long.class, uuid);
    }

    private Map<String, Object> rowOf(UUID uuid) {
        return jdbcTemplate.queryForMap(
                "SELECT title, slug, summary, content_md, content_html, toc, cover_image_url, status, reject_reason "
                        + "FROM articles WHERE uuid = ?", uuid);
    }

    private ArticleRestoreSource source(String slug, List<UUID> tags) {
        return new ArticleRestoreSource("新標題", slug, MARKDOWN, "新摘要", "https://cdn/new.jpg", tags);
    }

    private List<UUID> capturedTags(UUID articleUuid) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
        verify(tagFacade).syncArticleTags(eq(articleUuid), captor.capture());
        return captor.getValue();
    }

    // ─── 還原：UUID 版 vs Long 版 ───

    @Test
    @DisplayName("同一份快照內容：UUID 版與 Long 版還原後，title／slug／content／html／toc／summary／cover／tags 逐欄位相同")
    void applyRestoreContent_uuidVersionAndLongVersion_produceIdenticalArticles() {
        UUID viaLong = insertArticle("DRAFT");
        UUID viaUuid = insertArticle("DRAFT");
        String slug = "parity-" + UUID.randomUUID();
        List<UUID> tags = List.of(UUID.randomUUID(), UUID.randomUUID());

        /* Long 版：呼叫端（測試）自行 render 後傳入 */
        RenderResult rendered = renderer.render(MARKDOWN);
        articleFacade.applyRestoreContent(idOf(viaLong), new ArticleRestoreData(
                "新標題", slug, MARKDOWN, "新摘要", "https://cdn/new.jpg",
                rendered.html(), tocCodec.serialize(rendered.toc()), tags));
        Map<String, Object> longRow = rowOf(viaLong);
        List<UUID> longTags = capturedTags(viaLong);
        /* slug 有 UNIQUE 約束：把 Long 版那篇的 slug 挪開，UUID 版才能還原成同一個 slug */
        jdbcTemplate.update("UPDATE articles SET slug = ? WHERE uuid = ?", "moved-" + viaLong, viaLong);

        /* UUID 版：由 article 內部渲染 */
        articleFacade.applyRestoreContent(viaUuid, source(slug, tags));
        Map<String, Object> uuidRow = rowOf(viaUuid);
        List<UUID> uuidTags = capturedTags(viaUuid);

        assertThat(longRow.get("slug")).isEqualTo(slug);
        assertThat(longRow.get("content_html")).asString().contains("heading-");
        assertThat(longRow.get("toc")).asString().isNotEqualTo("[]");
        assertThat(uuidRow).isEqualTo(longRow);
        assertThat(uuidTags).isEqualTo(longTags).isEqualTo(tags);
    }

    @Test
    @DisplayName("UUID 版還原：content_html 與 toc 等於 renderer／codec 對同一份 Markdown 的結果")
    void applyRestoreContent_uuidVersion_persistsRendererAndCodecOutput() {
        UUID uuid = insertArticle("DRAFT");
        RenderResult expected = renderer.render(MARKDOWN);

        articleFacade.applyRestoreContent(uuid, source("render-" + uuid, List.of()));

        Map<String, Object> row = rowOf(uuid);
        assertThat(row.get("content_html")).isEqualTo(expected.html());
        assertThat(row.get("toc")).isEqualTo(tocCodec.serialize(expected.toc()));
    }

    @Test
    @DisplayName("SEC-02：UUID 版還原不改變文章狀態（DRAFT／REJECTED 皆然，rejectReason 亦不動）")
    void applyRestoreContent_uuidVersion_doesNotChangeStatus() {
        UUID draft = insertArticle("DRAFT");
        UUID rejected = insertArticle("REJECTED");

        articleFacade.applyRestoreContent(draft, source("sec02-d-" + draft, List.of()));
        articleFacade.applyRestoreContent(rejected, source("sec02-r-" + rejected, List.of()));

        assertThat(rowOf(draft).get("status")).isEqualTo("DRAFT");
        Map<String, Object> rejectedRow = rowOf(rejected);
        assertThat(rejectedRow.get("status")).isEqualTo("REJECTED");
        assertThat(rejectedRow.get("reject_reason")).isEqualTo("內部評語");
    }

    @Test
    @DisplayName("UUID 版還原：tags 為 null 以空清單同步；還原後重新綁定檔案")
    void applyRestoreContent_uuidVersion_nullTagsSyncEmptyAndRebindsFiles() {
        UUID uuid = insertArticle("DRAFT");

        articleFacade.applyRestoreContent(uuid, source("tags-" + uuid, null));

        assertThat(capturedTags(uuid)).isEmpty();
        verify(fileFacade, times(1)).bindFilesToArticle(eq(uuid), any());
    }

    @Test
    @DisplayName("UUID 版還原：內容凍結狀態拒絕（A0209）且內容不變；與 Long 版一致")
    void applyRestoreContent_uuidVersion_frozenStatusRejected() {
        for (String frozen : List.of("PENDING_REVIEW", "PUBLISHED", "ARCHIVED")) {
            UUID uuid = insertArticle(frozen);

            assertThatThrownBy(() -> articleFacade.applyRestoreContent(uuid, source("frozen-" + uuid, List.of())))
                    .isInstanceOf(BusinessException.class)
                    .extracting(t -> ((BusinessException) t).getCode())
                    .isEqualTo("A0209");
            assertThat(rowOf(uuid).get("content_md")).isEqualTo("舊內容");
            assertThat(rowOf(uuid).get("status")).isEqualTo(frozen);
        }
    }

    @Test
    @DisplayName("UUID 版還原：文章不存在 → A0201，與 Long 版相同")
    void applyRestoreContent_uuidVersion_articleNotFound() {
        assertThatThrownBy(() -> articleFacade.applyRestoreContent(UUID.randomUUID(), source("nf", List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getCode())
                .isEqualTo("A0201");
        assertThatThrownBy(() -> articleFacade.applyRestoreContent(-1L,
                new ArticleRestoreData("T", "nf", "c", "s", null, "<p>c</p>", "[]", List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getCode())
                .isEqualTo("A0201");
    }

    // ─── findContentByUuid ───

    @Test
    @DisplayName("findContentByUuid 與 findContentById 對同一篇文章（各種狀態）結果相等，authorUuid 非 null")
    void findContentByUuid_equalsFindContentById_forEveryStatus() {
        for (String status : List.of("DRAFT", "PENDING_REVIEW", "PUBLISHED", "REJECTED", "ARCHIVED")) {
            UUID uuid = insertArticle(status);

            Optional<ArticleContentData> byUuid = articleFacade.findContentByUuid(uuid);
            Optional<ArticleContentData> byId = articleFacade.findContentById(idOf(uuid));

            assertThat(byUuid).as("status=%s", status).isPresent();
            assertThat(byUuid).isEqualTo(byId);
            assertThat(byUuid.get().authorUuid()).isNotNull();
            assertThat(byUuid.get().uuid()).isEqualTo(uuid);
            assertThat(byUuid.get().status()).isEqualTo(status);
        }
    }

    @Test
    @DisplayName("findContentByUuid：查無或 null → empty")
    void findContentByUuid_unknownOrNull_returnsEmpty() {
        assertThat(articleFacade.findContentByUuid(UUID.randomUUID())).isEmpty();
        assertThat(articleFacade.findContentByUuid(null)).isEmpty();
    }
}
