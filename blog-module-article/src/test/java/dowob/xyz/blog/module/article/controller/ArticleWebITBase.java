package dowob.xyz.blog.module.article.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.config.ArticleTestApplication;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * 文章 Controller 整合測試的共用骨架（真 PostgreSQL + Redis、MockMvc、跨模組 facade 以 mock 取代）
 *
 * <p>僅供「單一主題」的小型 IT 繼承（內容長度上限、樂觀鎖版本等），避免各自複製一份容器與 mock 設定。
 * 既有的 {@link ArticleControllerIT} 保持自成一體，不受影響。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ArticleTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class ArticleWebITBase {

    /*
     * 單例容器模式：容器在 JVM 內只啟動一次、由 Ryuk 在 JVM 結束時回收，不用 @Container。
     * 若改用 @Container 的 static 欄位，Testcontainers 會在每個子類別結束時停掉容器、下一個子類別再起新容器（新埠號），
     * 但 Spring 的 context cache 會重用第一個子類別建的 context，仍指向已停掉的舊埠，造成
     * 「Could not open JDBC Connection」。
     */

    /** PostgreSQL TestContainer（單例） */
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("blog_test")
            .withUsername("test")
            .withPassword("test");

    /** Redis TestContainer（單例） */
    static final com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:7-alpine");

    static {
        postgres.start();
        redis.start();
    }

    /**
     * 動態注入容器連線設定
     *
     * @param registry Spring 動態屬性源
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.url", redis::getRedisURI);
    }

    /** 測試用作者 ID */
    protected static final Long AUTHOR_ID = 1L;

    /** MockMvc 測試客戶端 */
    @Autowired
    protected MockMvc mockMvc;

    /** JSON 序列化工具 */
    @Autowired
    protected ObjectMapper objectMapper;

    /** 文章 Repository（用於清理與讀回 DB 實況） */
    @Autowired
    protected ArticleRepository articleRepository;

    /** 清理歸檔頁快取用 */
    @Autowired
    protected StringRedisTemplate stringRedisTemplate;

    /** Mock RabbitMQ ConnectionFactory */
    @MockitoBean
    private ConnectionFactory connectionFactory;

    /** Mock RabbitTemplate（避免 IT 真正發送 MQ 訊息） */
    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    /** Mock TagFacade */
    @MockitoBean
    private TagFacade tagFacade;

    /** Mock UserFacade */
    @MockitoBean
    protected UserFacade userFacade;

    /** Mock UserAuthService */
    @MockitoBean
    private UserAuthService userAuthService;

    /** Mock ReadingFacade */
    @MockitoBean
    private ReadingFacade readingFacade;

    /** Mock SeriesFacade */
    @MockitoBean
    private SeriesFacade seriesFacade;

    /** 每次測試後清掉文章與歸檔快取 */
    @AfterEach
    void cleanUpArticles() {
        articleRepository.deleteAll();
        stringRedisTemplate.delete(RedisKeyConstant.ARTICLE_ARCHIVE_KEY);
    }

    /**
     * 建立模擬認證（含角色對應的全部權限 authority）
     *
     * @param userId 用戶 ID（轉為非同構 UUID）
     * @param role   角色
     * @return RequestPostProcessor
     */
    protected RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                new AuthUser(TestUsers.uuidOf(userId), role), null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    /** 讓 userFacade 對任何 uuid 都回傳一位暱稱固定的作者 */
    protected void stubUserFacade() {
        when(userFacade.getDisplayInfoByUuids(anyCollection())).thenAnswer(invocation -> {
            Collection<UUID> uuids = invocation.getArgument(0);
            Map<UUID, UserDisplayInfo> result = new HashMap<>();
            uuids.stream().filter(Objects::nonNull)
                    .forEach(u -> result.put(u, new UserDisplayInfo(u, "TestAuthor", null)));
            return result;
        });
    }
}
