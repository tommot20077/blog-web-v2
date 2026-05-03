package dowob.xyz.blog.common.util;

import dowob.xyz.blog.common.api.enums.Role;
import lombok.experimental.UtilityClass;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Objects;

/**
 * Spring Security 工具類
 *
 * <p>提供從 {@link Authentication} 解析使用者角色的共用方法，避免各 Controller 重複實作。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@UtilityClass
public class SecurityUtils {

    /**
     * 從 Authentication 解析使用者角色
     *
     * @param authentication Spring Security 認證物件（可為 null 或 AnonymousAuthenticationToken）
     * @return 使用者角色；未登入、匿名或無法識別的角色回傳 {@code null}
     */
    public static Role resolveRole(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(roleStr -> {
                    try {
                        return Role.fromSpringSecurityRole(roleStr);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * 從 SecurityContextHolder 直接取 — controller 內最簡呼叫方式。
     *
     * @return 當前 thread 的 authentication 是否為 ROLE_ADMIN
     */
    public static boolean isAdmin() {
        return isAdmin(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * 帶參版 — unit test / 自行傳 Authentication 的 caller 用。
     *
     * @param authentication Spring Security 認證物件（可為 null / AnonymousAuthenticationToken / 認證 token）
     * @return 是否為 ROLE_ADMIN（null / 匿名 / 未認證 / 其他角色一律 false）
     */
    public static boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
