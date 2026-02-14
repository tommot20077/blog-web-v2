package dowob.xyz.blog.infrastructure.security;

import dowob.xyz.blog.common.api.enums.Permission;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JwtAuthenticationFilter 單元測試
 *
 * <p>
 * 驗證過濾器在 JWT 有效且狀態正常時，
 * 所設定的 {@link Authentication#getAuthorities()} 應同時包含
 * Spring Security 角色字串（{@code ROLE_XXX}）與所有對應的 {@link Permission} 名稱。
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

    /** 使用者認證服務 Mock */
    @Mock
    private UserAuthService userAuthService;

    /** Redis 模板 Mock */
    @Mock
    private StringRedisTemplate redisTemplate;

    /** 受測物件 */
    @InjectMocks
    private JwtAuthenticationFilter filter;

    /** 假 JWT Token */
    private static final String FAKE_JWT = "fake.jwt.token";

    /** 測試用 userId */
    private static final Long USER_ID = 1L;

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
     * 建立模擬 JWT 認證環境的共用輔助方法
     *
     * @param role 要模擬的角色
     * @throws Exception 過濾器執行時可能拋出的例外
     */
    @SuppressWarnings("unchecked")
    private void performFilterWithRole(Role role) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);

        when(request.getHeader("Authorization")).thenReturn("Bearer " + FAKE_JWT);
        when(jwtService.validateToken(FAKE_JWT)).thenReturn(true);
        when(jwtService.getUserIdFromToken(FAKE_JWT)).thenReturn(String.valueOf(USER_ID));
        when(jwtService.getVersionFromToken(FAKE_JWT)).thenReturn(TOKEN_VERSION);
        when(jwtService.getRoleFromToken(FAKE_JWT)).thenReturn(role);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get(any(), eq(RedisKeyConstant.FIELD_VERSION))).thenReturn(TOKEN_VERSION);
        when(hashOps.get(any(), eq(RedisKeyConstant.FIELD_STATUS))).thenReturn("ACTIVE");

        filter.doFilterInternal(request, response, chain);
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
}
