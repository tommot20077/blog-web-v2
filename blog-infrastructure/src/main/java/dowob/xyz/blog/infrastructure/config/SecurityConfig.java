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

                        // 靜態資源與 Swagger
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/favicon.ico", "/error").permitAll()

                        // 認證相關 API（logout 需認證，refresh 靠 cookie 驗證故保持公開）
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .requestMatchers("/api/v1/auth/**").permitAll()

                        // 公開的 GET 請求（文章、標籤、檔案元資料、分類）
                        .requestMatchers(HttpMethod.GET, "/api/v1/articles/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/tags/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/files/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories/**").permitAll()

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
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
