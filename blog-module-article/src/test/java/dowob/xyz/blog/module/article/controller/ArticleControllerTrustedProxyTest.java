package dowob.xyz.blog.module.article.controller;

import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.AuthStateLoader;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.infrastructure.web.ClientIpResolver;
import dowob.xyz.blog.module.article.config.ArticleWebTestConfiguration;
import dowob.xyz.blog.module.article.service.ArticleQueryService;
import dowob.xyz.blog.module.article.service.ArticleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ArticleController 在 {@code app.http.trusted-proxy-count=1}（prod：nginx 已覆寫 XFF）下的 client IP 行為。
 *
 * <p>取 XFF 最右節，偽造的左側節點不得成為瀏覽去重的 key；預設 0 的行為見 {@code ArticleControllerClientIpTest}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(ArticleController.class)
@ContextConfiguration(classes = ArticleWebTestConfiguration.class)
@Import({SecurityConfig.class, ClientIpResolver.class})
@TestPropertySource(properties = "app.http.trusted-proxy-count=1")
@DisplayName("ArticleController client IP 取得（count=1）")
class ArticleControllerTrustedProxyTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ArticleService articleService;

    @MockitoBean
    private ArticleQueryService articleQueryService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private UserAuthService userAuthService;

    /** Mock：認證狀態載入器（JwtAuthenticationFilter 所需） */
    @MockitoBean
    private AuthStateLoader authStateLoader;

    @Test
    @DisplayName("count=1：GET /slug/{slug} 帶偽造 XFF 前綴 → 傳給 service 的是 XFF 最右節")
    void getArticleBySlug_forgedPrefix_countOne_passesRightmostEntry() throws Exception {
        mockMvc.perform(get("/api/v1/articles/slug/hello")
                        .header("X-Forwarded-For", "6.6.6.6, 203.0.113.7")
                        .with(req -> { req.setRemoteAddr("10.0.0.5"); return req; }))
                .andExpect(status().isOk());

        verify(articleQueryService).getArticleBySlug(eq("hello"), isNull(), any(), eq("203.0.113.7"));
    }

    @Test
    @DisplayName("count=1：GET /{uuid} 帶偽造 XFF 前綴 → 傳給 service 的是 XFF 最右節")
    void getArticleByUuid_forgedPrefix_countOne_passesRightmostEntry() throws Exception {
        UUID uuid = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/articles/" + uuid)
                        .header("X-Forwarded-For", "6.6.6.6, 7.7.7.7, 203.0.113.7")
                        .with(req -> { req.setRemoteAddr("10.0.0.5"); return req; }))
                .andExpect(status().isOk());

        verify(articleQueryService).getArticleByUuid(eq(uuid), isNull(), any(), eq("203.0.113.7"));
    }
}
