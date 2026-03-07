package dowob.xyz.blog.common.util;

import dowob.xyz.blog.common.api.enums.Role;
import lombok.experimental.UtilityClass;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

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
}
