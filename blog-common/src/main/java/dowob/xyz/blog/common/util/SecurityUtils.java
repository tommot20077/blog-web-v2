package dowob.xyz.blog.common.util;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.security.AuthUser;
import lombok.experimental.UtilityClass;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Objects;
import java.util.Optional;

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

    /**
     * 取出已登入使用者的 {@link AuthUser}。
     *
     * <p>這是讀取 principal 的唯一入口（白名單之外不得直接呼叫
     * {@code Authentication.getPrincipal()}）。principal 型別不是 {@link AuthUser}
     * （例如舊式 {@code Long}、匿名的 {@code "anonymousUser"} 字串）一律視為未登入。</p>
     *
     * @param authentication Spring Security 認證物件（可為 null、匿名或尚未認證）
     * @return 已認證且 principal 為 {@link AuthUser} 時回傳之；其餘情況回傳 {@link Optional#empty()}
     */
    public static Optional<AuthUser> currentAuthUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof AuthUser authUser
                ? Optional.of(authUser)
                : Optional.empty();
    }
}
