package dowob.xyz.blog.common.util;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.security.AuthUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SecurityUtils 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("SecurityUtils 單元測試")
class SecurityUtilsTest {

    @Test
    @DisplayName("null authentication → 回傳 null")
    void resolveRole_null_returnsNull() {
        assertThat(SecurityUtils.resolveRole(null)).isNull();
    }

    @Test
    @DisplayName("AnonymousAuthenticationToken → 回傳 null")
    void resolveRole_anonymous_returnsNull() {
        AnonymousAuthenticationToken anon = new AnonymousAuthenticationToken(
                "key", "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertThat(SecurityUtils.resolveRole(anon)).isNull();
    }

    @Test
    @DisplayName("ROLE_ADMIN → 回傳 Role.ADMIN")
    void resolveRole_admin_returnsAdminRole() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                1L, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThat(SecurityUtils.resolveRole(auth)).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("ROLE_AUTHOR → 回傳 Role.AUTHOR")
    void resolveRole_author_returnsAuthorRole() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                1L, null,
                List.of(new SimpleGrantedAuthority("ROLE_AUTHOR")));
        assertThat(SecurityUtils.resolveRole(auth)).isEqualTo(Role.AUTHOR);
    }

    @Test
    @DisplayName("unknown role string → 回傳 null")
    void resolveRole_unknownRole_returnsNull() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                1L, null,
                List.of(new SimpleGrantedAuthority("ROLE_UNKNOWN")));
        assertThat(SecurityUtils.resolveRole(auth)).isNull();
    }

    @Nested
    @DisplayName("isAdmin(Authentication)（帶參版）")
    class IsAdminWithAuthentication {

        @Test
        @DisplayName("authentication 為 null → false")
        void isAdmin_null_returnsFalse() {
            assertThat(SecurityUtils.isAdmin((Authentication) null)).isFalse();
        }

        @Test
        @DisplayName("AnonymousAuthenticationToken → false")
        void isAdmin_anonymous_returnsFalse() {
            Authentication auth = new AnonymousAuthenticationToken(
                "key", "anon",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
            );
            assertThat(SecurityUtils.isAdmin(auth)).isFalse();
        }

        @Test
        @DisplayName("已認證但 ROLE_USER → false")
        void isAdmin_user_returnsFalse() {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                "user", "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            assertThat(SecurityUtils.isAdmin(auth)).isFalse();
        }

        @Test
        @DisplayName("ROLE_ADMIN → true")
        void isAdmin_admin_returnsTrue() {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                "admin", "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            );
            assertThat(SecurityUtils.isAdmin(auth)).isTrue();
        }
    }

    @Nested
    @DisplayName("isAdmin()（無參版 — 從 SecurityContextHolder 取）")
    class IsAdminNoArg {

        @org.junit.jupiter.api.AfterEach
        void clearContext() {
            SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("SecurityContext 為空 → false")
        void isAdmin_emptyContext_returnsFalse() {
            SecurityContextHolder.clearContext();
            assertThat(SecurityUtils.isAdmin()).isFalse();
        }

        @Test
        @DisplayName("SecurityContext 有 ROLE_ADMIN → true")
        void isAdmin_adminInContext_returnsTrue() {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                "admin", "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
            assertThat(SecurityUtils.isAdmin()).isTrue();
        }

        @Test
        @DisplayName("SecurityContext 有 ROLE_USER → false")
        void isAdmin_userInContext_returnsFalse() {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                "user", "pwd",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
            assertThat(SecurityUtils.isAdmin()).isFalse();
        }
    }

    @Nested
    @DisplayName("currentAuthUser(Authentication)（取出 AuthUser principal）")
    class CurrentAuthUser {

        private static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

        private final AuthUser authUser = new AuthUser(7L, USER_UUID, Role.AUTHOR);

        @Test
        @DisplayName("authentication 為 null → empty")
        void currentAuthUser_null_returnsEmpty() {
            assertThat(SecurityUtils.currentAuthUser(null)).isEmpty();
        }

        @Test
        @DisplayName("AnonymousAuthenticationToken → empty")
        void currentAuthUser_anonymous_returnsEmpty() {
            Authentication auth = new AnonymousAuthenticationToken(
                    "key", "anonymousUser",
                    List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
            assertThat(SecurityUtils.currentAuthUser(auth)).isEmpty();
        }

        @Test
        @DisplayName("尚未認證的 token（即使 principal 是 AuthUser）→ empty")
        void currentAuthUser_unauthenticatedToken_returnsEmpty() {
            Authentication auth = new UsernamePasswordAuthenticationToken(authUser, null);
            assertThat(auth.isAuthenticated()).isFalse();
            assertThat(SecurityUtils.currentAuthUser(auth)).isEmpty();
        }

        @Test
        @DisplayName("principal 為舊式 Long → empty（不得誤當成已登入）")
        void currentAuthUser_longPrincipal_returnsEmpty() {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            assertThat(SecurityUtils.currentAuthUser(auth)).isEmpty();
        }

        @Test
        @DisplayName("principal 為 String（如 anonymousUser）→ empty")
        void currentAuthUser_stringPrincipal_returnsEmpty() {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    "anonymousUser", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            assertThat(SecurityUtils.currentAuthUser(auth)).isEmpty();
        }

        @Test
        @DisplayName("已認證且 principal 為 AuthUser → 回傳該 AuthUser")
        void currentAuthUser_authUserPrincipal_returnsAuthUser() {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    authUser, null, List.of(new SimpleGrantedAuthority("ROLE_AUTHOR")));

            Optional<AuthUser> result = SecurityUtils.currentAuthUser(auth);

            assertThat(result).contains(authUser);
            assertThat(result.orElseThrow().id()).isEqualTo(7L);
            assertThat(result.orElseThrow().uuid()).isEqualTo(USER_UUID);
            assertThat(result.orElseThrow().role()).isEqualTo(Role.AUTHOR);
        }
    }
}
