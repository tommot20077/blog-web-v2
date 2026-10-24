package dowob.xyz.blog.infrastructure.security;

import dowob.xyz.blog.common.api.enums.Permission;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.common.util.SecurityUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JwtAuthenticationFilter 單元測試
 *
 * <p>
 * 驗證過濾器在 JWT 有效且狀態正常時，
 * 所設定的 {@link Authentication#getAuthorities()} 應同時包含
 * Spring Security 角色字串（{@code ROLE_XXX}）與所有對應的 {@link Permission} 名稱，
 * principal 為 {@link AuthUser}，且 token version 與帳號狀態的判斷語意維持不變。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter 權限設定測試")
class JwtAuthenticationFilterTest {

    /** JWT 服務 Mock */
    @Mock
    private JwtService jwtService;

    /** 認證狀態載入器 Mock（Redis 優先、miss 回填 DB 的邏輯由 AuthStateLoaderTest 驗證） */
    @Mock
    private AuthStateLoader authStateLoader;

    /** 受測物件 */
    @InjectMocks
    private JwtAuthenticationFilter filter;

    /** 假 JWT Token */
    private static final String FAKE_JWT = "fake.jwt.token";

    /** 測試用 userId（AuthUser 過渡欄位） */
    private static final Long USER_ID = 1L;

    /** 測試用使用者 uuid（JWT subject） */
    private static final UUID USER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** 測試用 Token 版本號 */
    private static final String TOKEN_VERSION = "1";

    /**
     * 每次測試前清空 SecurityContext
     */
    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 每次測試後清空 SecurityContext。
     *
     * <p>本測試以真實 {@link JwtAuthenticationFilter} 直接寫入 {@link SecurityContextHolder}
     * 的 ThreadLocal；若不於測試後清理，最後一個測試殘留的 Authentication 會沿同一 JVM fork
     * 洩漏到後續測試類別（例如 {@code SecurityConfigTest} 的未認證端點斷言），造成順序相依的假失敗。</p>
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 建立認證狀態：預設 ACTIVE、版本與 token 相同。
     *
     * @param status 帳號狀態
     * @param role   資料庫中的角色
     * @param version 伺服端 Token 版本
     * @return 認證狀態
     */
    private AuthState state(UserStatus status, Role role, String version) {
        return new AuthState(USER_ID, USER_UUID, status, role, version);
    }

    /**
     * 準備一次帶有效 JWT 的請求並執行過濾器。
     *
     * @param tokenRole 寫在 token role claim 的角色
     * @param tokenVersion 寫在 token 的版本
     * @param loaded loader 回傳的狀態（null 表示 Redis 與 DB 都查不到）
     * @param chain 過濾鏈 Mock
     * @throws Exception 過濾器執行時可能拋出的例外
     */
    private void performFilter(Role tokenRole, String tokenVersion, AuthState loaded, FilterChain chain)
            throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getHeader("Authorization")).thenReturn("Bearer " + FAKE_JWT);
        when(jwtService.validateToken(FAKE_JWT)).thenReturn(true);
        when(jwtService.getUserUuidFromToken(FAKE_JWT)).thenReturn(USER_UUID);
        when(jwtService.getVersionFromToken(FAKE_JWT)).thenReturn(tokenVersion);
        when(jwtService.getRoleFromToken(FAKE_JWT)).thenReturn(tokenRole);
        when(authStateLoader.load(USER_UUID)).thenReturn(Optional.ofNullable(loaded));

        filter.doFilterInternal(request, response, chain);
    }

    /**
     * 以 ACTIVE 狀態、版本一致的情境執行過濾器。
     *
     * @param role 要模擬的角色（token 與資料庫一致）
     * @throws Exception 過濾器執行時可能拋出的例外
     */
    private void performFilterWithRole(Role role) throws Exception {
        performFilter(role, TOKEN_VERSION, state(UserStatus.ACTIVE, role, TOKEN_VERSION), mock(FilterChain.class));
    }

    @Test
    @DisplayName("AUTHOR 角色的 Authentication 應包含 ROLE_AUTHOR 與 ARTICLE_CREATE，不含 SYSTEM_CONFIG")
    void author_authoritiesShouldContainRoleAndPermissions() throws Exception {
        performFilterWithRole(Role.AUTHOR);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();

        Set<String> authorityNames = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .collect(Collectors.toSet());

        assertThat(authorityNames).contains("ROLE_AUTHOR");
        assertThat(authorityNames).contains(Permission.ARTICLE_CREATE.name());
        assertThat(authorityNames).contains(Permission.ARTICLE_EDIT.name());
        assertThat(authorityNames).contains(Permission.ARTICLE_DELETE.name());
        assertThat(authorityNames).contains(Permission.COMMENT_WRITE.name());
        assertThat(authorityNames).contains(Permission.FILE_UPLOAD.name());
        assertThat(authorityNames).doesNotContain(Permission.SYSTEM_CONFIG.name());
    }

    @Test
    @DisplayName("USER 角色的 Authentication 應包含 ROLE_USER 與 COMMENT_WRITE, COMMENT_DELETE，不含 ARTICLE_CREATE")
    void user_authoritiesShouldContainCommentWriteOnly() throws Exception {
        performFilterWithRole(Role.USER);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();

        Set<String> authorityNames = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .collect(Collectors.toSet());

        assertThat(authorityNames).contains("ROLE_USER");
        assertThat(authorityNames).contains(Permission.COMMENT_WRITE.name());
        assertThat(authorityNames).contains(Permission.COMMENT_DELETE.name());
        assertThat(authorityNames).doesNotContain(Permission.ARTICLE_CREATE.name());
        assertThat(authorityNames).doesNotContain(Permission.SYSTEM_CONFIG.name());
    }

    @Test
    @DisplayName("ADMIN 角色的 Authentication 應包含所有 Permission")
    void admin_authoritiesShouldContainAllPermissions() throws Exception {
        performFilterWithRole(Role.ADMIN);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();

        Set<String> authorityNames = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .collect(Collectors.toSet());

        assertThat(authorityNames).contains("ROLE_ADMIN");
        for (Permission permission : Permission.values()) {
            assertThat(authorityNames)
                    .as("ADMIN 應包含權限 %s", permission)
                    .contains(permission.name());
        }
    }

    @Test
    @DisplayName("通過驗證時 principal 為 AuthUser(id, uuid, role)，role 取自 token 的 role claim")
    void authenticated_principalShouldBeAuthUserWithTokenRole() throws Exception {
        performFilterWithRole(Role.AUTHOR);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(SecurityUtils.currentAuthUser(auth))
                .contains(new AuthUser(USER_ID, USER_UUID, Role.AUTHOR));
    }

    @Test
    @DisplayName("AuthUser.role 與 authorities 同源：以 token 的 role claim 為準，不受 loader 回傳的角色影響")
    void roleSource_shouldBeTokenClaimForBothPrincipalAndAuthorities() throws Exception {
        performFilter(Role.ADMIN, TOKEN_VERSION, state(UserStatus.ACTIVE, Role.USER, TOKEN_VERSION),
                mock(FilterChain.class));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(SecurityUtils.currentAuthUser(auth)).get().extracting(AuthUser::role).isEqualTo(Role.ADMIN);
        assertThat(auth.getAuthorities().stream().map(a -> a.getAuthority()).collect(Collectors.toSet()))
                .contains("ROLE_ADMIN");
    }

    /** ===== 未覆蓋路徑 ===== */

    @Test
    @DisplayName("請求無 Authorization Header 時，應直接放行至下一個 Filter，SecurityContext 保持空白")
    void noAuthorizationHeader_shouldPassThroughWithoutSettingAuthentication() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(authStateLoader);
    }

    @Test
    @DisplayName("JWT 驗證失敗時（含 subject 為數字的舊 token），應直接放行至下一個 Filter，SecurityContext 保持空白")
    void invalidJwt_shouldPassThroughWithoutSettingAuthentication() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(request.getHeader("Authorization")).thenReturn("Bearer invalid.token.here");
        when(jwtService.validateToken("invalid.token.here")).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(authStateLoader);
    }

    @Test
    @DisplayName("Redis 與 DB 都查無用戶時，應放行請求至下一個 Filter，SecurityContext 保持空白")
    void userNotFound_shouldPassThroughWithoutSettingAuthentication() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        performFilter(Role.USER, TOKEN_VERSION, null, chain);

        verify(chain).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("用戶狀態為 BANNED 時，應放行請求至下一個 Filter，SecurityContext 保持空白")
    void bannedUser_shouldPassThroughWithoutSettingAuthentication() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        performFilter(Role.USER, TOKEN_VERSION, state(UserStatus.BANNED, Role.USER, TOKEN_VERSION), chain);

        verify(chain).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("用戶狀態為 DELETED 時，不得通過認證")
    void deletedUser_shouldNotAuthenticate() throws Exception {
        performFilter(Role.USER, TOKEN_VERSION, state(UserStatus.DELETED, Role.USER, TOKEN_VERSION),
                mock(FilterChain.class));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("用戶狀態為 PENDING_VERIFICATION 時，維持現行行為：可通過認證")
    void pendingVerification_shouldAuthenticate() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        performFilter(Role.USER, TOKEN_VERSION, state(UserStatus.PENDING_VERIFICATION, Role.USER, TOKEN_VERSION),
                chain);

        verify(chain).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("JWT 版本號與伺服端版本不一致時，應放行請求但 SecurityContext 保持空白")
    void tokenVersionMismatch_shouldPassThroughWithoutSettingAuthentication() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        performFilter(Role.USER, "1", state(UserStatus.ACTIVE, Role.USER, "2"), chain);

        verify(chain).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("loader 拋出例外時，不得讓例外外洩，SecurityContext 保持空白並放行")
    void loaderThrows_shouldPassThroughWithoutSettingAuthentication() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(request.getHeader("Authorization")).thenReturn("Bearer " + FAKE_JWT);
        when(jwtService.validateToken(FAKE_JWT)).thenReturn(true);
        when(jwtService.getUserUuidFromToken(FAKE_JWT)).thenReturn(USER_UUID);
        when(jwtService.getVersionFromToken(FAKE_JWT)).thenReturn(TOKEN_VERSION);
        when(jwtService.getRoleFromToken(FAKE_JWT)).thenReturn(Role.USER);
        when(authStateLoader.load(USER_UUID)).thenThrow(new IllegalStateException("redis down"));

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
