package dowob.xyz.blog.infrastructure.security;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.core.StringRedisTemplate;
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

/**
 * JWT 認證過濾器
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserAuthService userAuthService;
    private final StringRedisTemplate redisTemplate;

    @Override
    protected void doFilterInternal(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response,
            @NotNull FilterChain chain)
            throws ServletException, IOException {

        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt) && jwtService.validateToken(jwt)) {
                String userIdStr = jwtService.getUserIdFromToken(jwt);
                Long userId = Long.parseLong(userIdStr);
                String tokenVersion = jwtService.getVersionFromToken(jwt);
                Role role = jwtService.getRoleFromToken(jwt);

                // 檢查 Redis 中的版本號與狀態
                // Key format: user:auth:{userId} -> Hash
                String redisKey = RedisKeyConstant.getUserAuthKey(userId);
                Object redisVersionObj = redisTemplate.opsForHash().get(redisKey, RedisKeyConstant.FIELD_VERSION);
                Object redisStatusObj = redisTemplate.opsForHash().get(redisKey, RedisKeyConstant.FIELD_STATUS);

                String currentVersion;
                String currentStatus;

                if (redisVersionObj == null || redisStatusObj == null) {
                    // Redis Miss -> 查 DB 回填
                    currentVersion = userAuthService.getUserTokenVersion(userId);
                    UserAuthService.SimpleUserDetail userDetail = userAuthService.getUserDetail(userId);

                    if (userDetail == null) {
                        return; // User not found
                    }

                    // 根據 enabled 簡單判斷狀態 (這裡為了簡化，若 enabled=true 視為 ACTIVE)
                    // TODO: 之後 UserDetail 應直接回傳 UserStatus Enum
                    currentStatus = userDetail.enabled() ? "ACTIVE" : "SUSPENDED";

                    redisTemplate.opsForHash().put(redisKey, RedisKeyConstant.FIELD_VERSION, currentVersion);
                    redisTemplate.opsForHash().put(redisKey, RedisKeyConstant.FIELD_STATUS, currentStatus);
                } else {
                    currentVersion = (String) redisVersionObj;
                    currentStatus = (String) redisStatusObj;
                }

                // 1. 檢查版本號 (必須完全一致)
                if (Objects.equals(tokenVersion, currentVersion)) {

                    // 2. 檢查狀態
                    if (!"ACTIVE".equals(currentStatus) && !"PENDING_VERIFICATION".equals(currentStatus)) {
                        log.info("User {} is not active (status={})", userId, currentStatus);
                        return;
                    }

                    // Token 有效且狀態正常：組合角色字串與所有對應 Permission 為 GrantedAuthority 列表
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
                    role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userId, null, authorities);

                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    log.info("Token version mismatch for user {}: token={}, server={}", userId, tokenVersion,
                            currentVersion);
                }
            }
        } catch (Exception e) {
            // 不在 Filter 這裡拋出異常，讓 Spring Security 處理未認證狀態
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
