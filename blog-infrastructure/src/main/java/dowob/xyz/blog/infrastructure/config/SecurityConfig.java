package dowob.xyz.blog.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.infrastructure.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import org.springframework.beans.factory.annotation.Value;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Spring Security 配置
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * UUID 形狀的路徑參數樣板（8-4-4-4-12 hex）。
     *
     * <p>用來限縮 articles 公開規則的比對範圍：若改用單段萬用字元 {@code /api/v1/articles/*}，
     * 任何未來新增的字面量子路徑（例如既有的 {@code /api/v1/articles/me}）都會被通配吃掉而
     * 意外變成 URL 層公開。以 UUID 形狀比對可讓「非 UUID 的字面量路徑」預設落入
     * {@code anyRequest().authenticated()}（fail-closed）。</p>
     */
    private static final String UUID_PATH_VARIABLE =
            "{uuid:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}";

    /** 公開的文章詳情端點：{@code GET /api/v1/articles/{uuid}} */
    private static final String PUBLIC_ARTICLE_DETAIL = "/api/v1/articles/" + UUID_PATH_VARIABLE;

    /** 公開的文章留言端點：{@code GET /api/v1/articles/{uuid}/comments} */
    private static final String PUBLIC_ARTICLE_COMMENTS = PUBLIC_ARTICLE_DETAIL + "/comments";

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private final ObjectMapper objectMapper;

    /** CORS 允許的來源清單，透過環境變數注入。生產環境同域部署時 CORS 不觸發。 */
    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5500,http://127.0.0.1:5500}")
    private List<String> allowedOrigins;

    /**
     * 建立 Spring Security 過濾器鏈
     *
     * <p>
     * 停用 CSRF 與 Session，設定 CORS、路由授權規則，
     * 並在 UsernamePasswordAuthenticationFilter 前插入 JWT 過濾器。
     * </p>
     *
     * @param http Spring Security HTTP 配置器
     * @return 已配置的 SecurityFilterChain
     * @throws Exception 配置過程中發生的例外
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Actuator Health（K3s liveness/readiness probe）
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()

                        // Actuator 其餘端點（metrics 等運維資訊）限 ADMIN。
                        // 必須排在上面 permitAll 之後（health/info 仍公開）、anyRequest 之前；
                        // 少了這條，暴露 metrics 等於讓任何已登入使用者讀到端點清單與呼叫量。
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // 靜態資源與 Swagger
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/favicon.ico", "/error").permitAll()

                        // 認證相關 API（logout 需認證，refresh 靠 cookie 驗證故保持公開）
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .requestMatchers("/api/v1/auth/**").permitAll()

                        // 公開的 GET 請求（文章、標籤、檔案元資料、分類、系列）
                        /*
                         * 公開的文章讀取端點（SEC-09 收窄）。
                         *
                         * 原本是 `GET /api/v1/articles/**` 整段 permitAll，使 me / edit / versions /
                         * highlights / progress 在 URL 層等同公開，只靠方法層 @PreAuthorize 兜底，
                         * 違反 ai-docs/security.md 原則 1「兩層防護缺一不可」——任何一個 handler
                         * 漏標 @PreAuthorize 就直接裸奔。
                         *
                         * 改為明確列舉「匿名可讀」的四條讀取端點 + 留言列表，其餘（含未來新增的
                         * 子資源）一律落入 anyRequest().authenticated()。
                         *
                         * 清單依據（逐條對應實作）：
                         *   GET /api/v1/articles                  ArticleController#getPublishedArticles（無 @PreAuthorize）
                         *   GET /api/v1/articles/archive          ArticleController#getArchive（無 @PreAuthorize）
                         *   GET /api/v1/articles/slug/{slug}      ArticleController#getArticleBySlug（無 @PreAuthorize）
                         *   GET /api/v1/articles/{uuid}           ArticleController#getArticle（無 @PreAuthorize）
                         *   GET /api/v1/articles/{uuid}/comments  CommentController#list（原則 7 豁免，JavaDoc 明載匿名可讀）
                         *
                         * 護欄：新增任何 articles 子端點時，預設就是「需認證」；要放行必須同步
                         * 更新本清單、ai-docs/security.md 的 Public Endpoints 表與 SecurityConfigTest，
                         * 且依 ai-docs/judgment.md §5 需 Yuan 拍板。
                         */
                        .requestMatchers(HttpMethod.GET, "/api/v1/articles").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/articles/archive").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/articles/slug/*").permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_ARTICLE_DETAIL).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_ARTICLE_COMMENTS).permitAll()

                        .requestMatchers(HttpMethod.GET, "/api/v1/tags/**").permitAll()
                        /*
                         * 檔案 GET 端點（含 /api/v1/files/{id} 元資料、/api/v1/files/{id}/content 內容代理）。
                         *
                         * 這裡的 permitAll 只代表「允許請求到達 Controller」，不代表資料本身公開！
                         * /api/v1/files/{id}/content 的真正授權判斷在 FileService#canRead()
                         * （依授權矩陣：AVATAR 與已發布文章圖片對匿名開放；草稿圖片與未綁定檔案
                         * 僅上傳者與 ADMIN 可讀，見 docs/superpowers/specs/2026-07-26-file-access-control-design.md §4）。
                         *
                         * 護欄：日後絕不可因為看到這行 permitAll 就以為「檔案是公開端點」而移除或繞過
                         * service 層的 canRead() 檢查——那等於讓草稿圖片對外洩漏，正是本次
                         * 檔案存取控制（B4）要防止的漏洞。
                         */
                        .requestMatchers(HttpMethod.GET, "/api/v1/files/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/series/**").permitAll()

                        /** 推薦 API（公開） */
                        .requestMatchers(HttpMethod.GET, "/api/v1/recommend/**").permitAll()

                        /** 搜尋 API（公開查詢與建議，歷史記錄仍需認證） */
                        .requestMatchers(HttpMethod.GET, "/api/v1/search").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/search/suggest").permitAll()

                        /** Admin 管理端點，僅 ADMIN 可存取 */
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

                        /** 其他所有請求需認證 */
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 自訂未認證入口點，將未登入請求回傳 401 + 統一 {@link ApiResponse} 結構。
     *
     * <p>避免 Spring 預設輸出 {@code {timestamp,status,error,path}}（與全站 ApiResponse 不一致）。</p>
     *
     * @return 回傳 401 的 {@link AuthenticationEntryPoint}
     */
    @Bean
    public AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) ->
                writeApiResponse(response, HttpServletResponse.SC_UNAUTHORIZED, CommonErrorCode.UNAUTHENTICATED);
    }

    /**
     * 自訂無權限拒絕處理器，將 403 回應改為統一 {@link ApiResponse} 結構。
     *
     * @return 回傳 403 的 {@link AccessDeniedHandler}
     */
    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                writeApiResponse(response, HttpServletResponse.SC_FORBIDDEN, CommonErrorCode.FORBIDDEN);
    }

    /**
     * 將給定 {@link CommonErrorCode} 序列化為 {@link ApiResponse} JSON 寫入 HTTP 回應。
     *
     * @param response   HTTP 回應物件
     * @param httpStatus HTTP 狀態碼
     * @param errorCode  業務錯誤碼
     */
    private void writeApiResponse(HttpServletResponse response, int httpStatus, CommonErrorCode errorCode)
            throws java.io.IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.failed(errorCode));
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
