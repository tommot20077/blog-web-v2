package dowob.xyz.blog.module.reading.exception;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.reading.config.ReadingTestApplication;
import dowob.xyz.blog.module.reading.model.UserBookmark;
import dowob.xyz.blog.module.reading.repository.UserBookmarkRepository;
import dowob.xyz.blog.module.reading.support.SeededReadingUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import dowob.xyz.blog.common.exception.GlobalExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GlobalExceptionHandler 資料衝突兜底（ARCH-11）整合測試。
 *
 * <p>以真實 PostgreSQL 與完整 Spring MVC／Security 堆疊，經測試專用 controller 製造
 * <b>繞過 service 攔截</b>的真實約束衝突與樂觀鎖失敗，證明回應是 409 而非 500，
 * 且 body 不含約束名、SQL 與欄位值。涵蓋三種真實例外形態：
 * JdbcTemplate 直接拋出的 {@code DuplicateKeyException}、Spring Data JDBC {@code save} 拋出的包裝例外、
 * 以及 {@code @Version} 的樂觀鎖失敗（直接拋出，未包裝）。唯一鍵衝突回 409；
 * 其餘完整性違反（NOT NULL）是程式缺陷，回 500 並記 ERROR。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ReadingTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(DataConflictFallbackIT.ConflictProbeController.class)
@DisplayName("GlobalExceptionHandler 資料衝突兜底整合測試（ARCH-11）")
class DataConflictFallbackIT {

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

    /** 約束名稱：回應 body 不得出現 */
    private static final String BOOKMARK_UNIQUE_CONSTRAINT = "uq_user_bookmarks_user_article";

    @Autowired private MockMvc mockMvc;
    @Autowired private ArticleRepository articleRepo;
    @Autowired private UserBookmarkRepository bookmarkRepo;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private FileFacade fileFacade;
    @MockitoBean private UserAuthService userAuthService;

    private UUID articleUuid;
    private Long articleId;

    /**
     * 建立一篇文章，供樂觀鎖與重複收藏情境使用。
     */
    @BeforeEach
    void setup() {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorUuid(SeededReadingUsers.OTHER_USER_UUID);
        article.setTitle("Data conflict IT");
        article.setSlug("data-conflict-it-" + UUID.randomUUID());
        article.setContent("test");
        article.setContentHtml("<p>test</p>");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        Article saved = articleRepo.save(article);
        articleUuid = saved.getUuid();
        articleId = saved.getId();
    }

    /**
     * 清除測試資料。
     */
    @AfterEach
    void cleanUp() {
        bookmarkRepo.deleteAll();
        articleRepo.deleteAll();
    }

    private RequestPostProcessor asUser() {
        Role role = Role.USER;
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                new AuthUser(SeededReadingUsers.USER_UUID, role), null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    /**
     * 驗證：JdbcTemplate 重複 INSERT（真實唯一約束）→ 409 + DATA_CONFLICT，body 不含約束名／SQL／uuid。
     */
    @Test
    @DisplayName("JdbcTemplate 重複 INSERT 撞唯一約束 → 409，body 不含約束名／SQL／欄位值")
    void jdbcDuplicateInsert_returns409_withoutLeakingDetails() throws Exception {
        String body = mockMvc.perform(post("/api/v1/it/arch11/jdbc-duplicate/{uuid}", articleUuid).with(asUser()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.DATA_CONFLICT.getCode()))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(BOOKMARK_UNIQUE_CONSTRAINT, "INSERT", "user_bookmarks", "duplicate key",
                articleUuid.toString(), SeededReadingUsers.USER_UUID.toString());
        assertThat(bookmarkRepo.count()).as("第二次 INSERT 被拒，仍只有 1 列").isEqualTo(1L);
    }

    /**
     * 驗證：Spring Data JDBC repository.save 重複（真實唯一約束，包裝例外形態）→ 409 而非 500。
     */
    @Test
    @DisplayName("Spring Data JDBC save 重複撞唯一約束（包裝例外）→ 409 而非 500")
    void repositoryDuplicateSave_returns409_notFiveHundred() throws Exception {
        String body = mockMvc.perform(post("/api/v1/it/arch11/repo-duplicate/{uuid}", articleUuid).with(asUser()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.DATA_CONFLICT.getCode()))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(BOOKMARK_UNIQUE_CONSTRAINT, "INSERT", "user_bookmarks", "duplicate key",
                articleUuid.toString());
    }

    /**
     * 驗證：NOT NULL 違反（真實 Postgres 約束）是程式缺陷、重試無效 → 500（沿用系統錯誤訊息）並以 ERROR 記錄完整例外，
     * 而不是被誤報成「資料衝突」409；body 不含欄位名／SQL。
     */
    @Test
    @DisplayName("NOT NULL 違反 → 500 + ERROR log，body 不含欄位名／SQL")
    void notNullViolation_returns500_logsError() throws Exception {
        LogCaptor captor = LogCaptor.attach(GlobalExceptionHandler.class);
        try {
            String body = mockMvc.perform(post("/api/v1/it/arch11/not-null").with(asUser()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("500"))
                    .andExpect(jsonPath("$.message").value("系統內部錯誤"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("article_uuid", "user_bookmarks", "null value", "violates");
            assertThat(captor.events()).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getThrown()).isInstanceOf(DataIntegrityViolationException.class);
            });
        } finally {
            captor.detach();
        }
    }

    /**
     * 驗證：@Version 樂觀鎖失敗（過期版本 save；Spring Data JDBC 直接拋出未包裝的
     * {@code OptimisticLockingFailureException}）→ 409 + OPTIMISTIC_LOCK_CONFLICT。
     */
    @Test
    @DisplayName("@Version 過期版本 save → 409 + OPTIMISTIC_LOCK_CONFLICT")
    void staleVersionSave_returns409() throws Exception {
        String body = mockMvc.perform(post("/api/v1/it/arch11/optimistic-lock/{id}", articleId).with(asUser()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.OPTIMISTIC_LOCK_CONFLICT.getCode()))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("articles", "version", "UPDATE");
    }

    /**
     * 測試專用 controller：刻意不做任何 service 層攔截，讓真實的資料庫例外直接冒到 GlobalExceptionHandler。
     */
    @RestController
    @RequestMapping("/api/v1/it/arch11")
    static class ConflictProbeController {

        private final JdbcTemplate jdbcTemplate;
        private final UserBookmarkRepository bookmarkRepository;
        private final ArticleRepository articleRepository;

        ConflictProbeController(JdbcTemplate jdbcTemplate, UserBookmarkRepository bookmarkRepository,
                                ArticleRepository articleRepository) {
            this.jdbcTemplate = jdbcTemplate;
            this.bookmarkRepository = bookmarkRepository;
            this.articleRepository = articleRepository;
        }

        @PostMapping("/jdbc-duplicate/{uuid}")
        ApiResponse<Void> jdbcDuplicate(@org.springframework.web.bind.annotation.PathVariable UUID uuid) {
            String sql = "INSERT INTO user_bookmarks (user_uuid, article_uuid, created_at) VALUES (?, ?, ?)";
            jdbcTemplate.update(sql, SeededReadingUsers.USER_UUID, uuid, LocalDateTime.now());
            jdbcTemplate.update(sql, SeededReadingUsers.USER_UUID, uuid, LocalDateTime.now());
            return ApiResponse.success();
        }

        @PostMapping("/repo-duplicate/{uuid}")
        ApiResponse<Void> repoDuplicate(@org.springframework.web.bind.annotation.PathVariable UUID uuid) {
            bookmarkRepository.save(new UserBookmark(null, SeededReadingUsers.USER_UUID, uuid, LocalDateTime.now()));
            bookmarkRepository.save(new UserBookmark(null, SeededReadingUsers.USER_UUID, uuid, LocalDateTime.now()));
            return ApiResponse.success();
        }

        @PostMapping("/not-null")
        ApiResponse<Void> notNull() {
            jdbcTemplate.update("INSERT INTO user_bookmarks (user_uuid, article_uuid, created_at) VALUES (?, NULL, ?)",
                    SeededReadingUsers.USER_UUID, LocalDateTime.now());
            return ApiResponse.success();
        }

        @PostMapping("/optimistic-lock/{id}")
        ApiResponse<Void> optimisticLock(@org.springframework.web.bind.annotation.PathVariable Long id) {
            Article first = articleRepository.findById(id).orElseThrow();
            Article second = articleRepository.findById(id).orElseThrow();
            first.setTitle("first writer");
            articleRepository.save(first);
            second.setTitle("second writer（持有過期版本）");
            articleRepository.save(second);
            return ApiResponse.success();
        }
    }

    /**
     * 擷取指定類別 logger 輸出的 log4j2 appender（僅供斷言 log 等級與例外）。
     */
    private static final class LogCaptor extends AbstractAppender {

        private final List<LogEvent> captured = new CopyOnWriteArrayList<>();
        private final org.apache.logging.log4j.core.Logger logger;
        private final Level originalLevel;

        private LogCaptor(org.apache.logging.log4j.core.Logger logger) {
            super("conflict-it-captor-" + System.nanoTime(), null, null, true, Property.EMPTY_ARRAY);
            this.logger = logger;
            this.originalLevel = logger.getLevel();
        }

        static LogCaptor attach(Class<?> target) {
            LoggerContext context = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
            org.apache.logging.log4j.core.Logger logger = context.getLogger(target.getName());
            LogCaptor captor = new LogCaptor(logger);
            captor.start();
            logger.addAppender(captor);
            logger.setLevel(Level.WARN);
            return captor;
        }

        void detach() {
            logger.removeAppender(this);
            logger.setLevel(originalLevel);
            stop();
        }

        List<LogEvent> events() {
            return new ArrayList<>(captured);
        }

        @Override
        public void append(LogEvent event) {
            captured.add(event.toImmutable());
        }
    }
}
