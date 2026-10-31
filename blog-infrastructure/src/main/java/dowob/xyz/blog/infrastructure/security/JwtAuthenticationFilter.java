package dowob.xyz.blog.infrastructure.security;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.security.AuthUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT 認證過濾器
 *
 * <p>驗證 Access Token 後，經 {@link AuthStateLoader} 取得 token version 與帳號狀態（Redis 優先、
 * miss 回填 DB），通過時放入 {@link AuthUser} 作為 principal。authorities 與 {@code AuthUser.role}
 * 同源，皆取自 token 的 role claim。通過的帳號狀態為 ACTIVE 與 PENDING_VERIFICATION。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AuthStateLoader authStateLoader;

    @Override
    protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response,
            @NotNull FilterChain chain)
            throws ServletException, IOException {

        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt) && jwtService.validateToken(jwt)) {
                UUID userUuid = jwtService.getUserUuidFromToken(jwt);
                String tokenVersion = jwtService.getVersionFromToken(jwt);
                Role role = jwtService.getRoleFromToken(jwt);

                /** Redis 命中即用，miss 由 loader 查 DB 回填；DB 也查不到代表使用者不存在 */
                Optional<AuthState> loaded = authStateLoader.load(userUuid);
                if (loaded.isEmpty()) {
                    log.info("User {} not found in DB, skip authentication", userUuid);
                    chain.doFilter(request, response);
                    return;
                }
                AuthState authState = loaded.get();
                String currentVersion = authState.tokenVersion();
                UserStatus currentStatus = authState.status();

                /** 1. 檢查版本號（必須完全一致） */
                if (Objects.equals(tokenVersion, currentVersion)) {

                    /** 2. 檢查狀態 */
                    if (currentStatus != UserStatus.ACTIVE && currentStatus != UserStatus.PENDING_VERIFICATION) {
                        log.info("User {} is not active (status={}), skip authentication", userUuid, currentStatus);
                        chain.doFilter(request, response);
                        return;
                    }

                    /** Token 有效且狀態正常：組合角色字串與所有對應 Permission 為 GrantedAuthority 列表 */
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
                    role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));

                    /** principal 的 role 與 authorities 同源（皆取自 token 的 role claim） */
                    AuthUser principal = new AuthUser(authState.uuid(), role);
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, authorities);

                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    log.info("Token version mismatch for user {}: token={}, server={}", userUuid, tokenVersion,
                            currentVersion);
                }
            }
        } catch (Exception e) {
            /** 不在 Filter 這裡拋出異常，讓 Spring Security 處理未認證狀態 */
            log.error("Cannot set user authentication: {}", e.getMessage());
        }

        chain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
