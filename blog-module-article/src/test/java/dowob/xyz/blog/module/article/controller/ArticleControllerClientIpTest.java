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
 * ArticleController 文章詳情端點的 client IP 取得行為（瀏覽數去重用；SEC-01）。
 *
 * <p>預設 {@code app.http.trusted-proxy-count=0}：偽造 {@code X-Forwarded-For} 不得改變傳給 service 的 IP；
 * 設為 1 時的行為見 {@code ArticleControllerTrustedProxyTest}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(ArticleController.class)
@ContextConfiguration(classes = ArticleWebTestConfiguration.class)
@Import({SecurityConfig.class, ClientIpResolver.class})
@DisplayName("ArticleController client IP 取得")
class ArticleControllerClientIpTest {

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
    @DisplayName("預設 count=0：GET /slug/{slug} 帶偽造 XFF → 傳給 service 的是 remoteAddr")
    void getArticleBySlug_forgedXff_defaultCount_passesRemoteAddr() throws Exception {
        mockMvc.perform(get("/api/v1/articles/slug/hello")
                        .header("X-Forwarded-For", "6.6.6.6")
                        .with(req -> { req.setRemoteAddr("198.51.100.23"); return req; }))
                .andExpect(status().isOk());

        verify(articleQueryService).getArticleBySlug(eq("hello"), isNull(), any(), eq("198.51.100.23"));
    }

    @Test
    @DisplayName("預設 count=0：GET /{uuid} 帶偽造 XFF → 傳給 service 的是 remoteAddr")
    void getArticleByUuid_forgedXff_defaultCount_passesRemoteAddr() throws Exception {
        UUID uuid = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/articles/" + uuid)
                        .header("X-Forwarded-For", "6.6.6.6")
                        .with(req -> { req.setRemoteAddr("198.51.100.23"); return req; }))
                .andExpect(status().isOk());

        verify(articleQueryService).getArticleByUuid(eq(uuid), isNull(), any(), eq("198.51.100.23"));
    }
}
