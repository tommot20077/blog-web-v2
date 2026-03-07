package dowob.xyz.blog.common.util;

import dowob.xyz.blog.common.api.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

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
}
