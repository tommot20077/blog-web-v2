package dowob.xyz.blog.module.user.controller;

import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.user.model.dto.request.ForgotPasswordRequest;
import dowob.xyz.blog.module.user.model.dto.request.LoginRequest;
import dowob.xyz.blog.module.user.model.dto.request.RegisterRequest;
import dowob.xyz.blog.module.user.model.dto.request.ResetPasswordRequest;
import dowob.xyz.blog.module.user.model.dto.response.AuthResponse;
import dowob.xyz.blog.module.user.model.dto.response.LoginResult;
import dowob.xyz.blog.module.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 認證控制器
 *
 * <p>提供用戶認證相關的 REST API，包含：
 * 註冊、登入、登出、刷新 Token、電子信箱驗證、忘記密碼與重設密碼。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Tag(name = "Auth", description = "認證相關 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    /** 認證業務服務 */
    private final AuthService authService;

    /** JWT 服務，用於 Refresh Token 驗證 */
    private final JwtService jwtService;

    /** Redis 操作模板，用於 Refresh Token 比對 */
    private final StringRedisTemplate redisTemplate;

    /**
     * 用戶註冊
     *
     * @param request 包含信箱、密碼與暱稱的註冊請求
     * @return 成功回應
     */
    @Operation(summary = "用戶註冊", description = "使用信箱註冊新帳號，完成後需驗證電子信箱才可登入")
    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request.getEmail(), request.getPassword(), request.getNickname());
        return ApiResponse.success();
    }

    /**
     * 用戶登入（雙 Token 架構）
     *
     * <p>成功後將 Refresh Token 寫入 HttpOnly Cookie，
     * Access Token 透過回應 body 回傳。</p>
     *
     * @param request  包含識別符與密碼的登入請求
     * @param response HTTP 回應，用於寫入 Cookie
     * @return 含 Access Token 的認證回應
     */
    @Operation(summary = "用戶登入", description = "使用信箱或暱稱登入，取得 Access Token（Refresh Token 在 Cookie）")
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        LoginResult loginResult = authService.login(request.getIdentifier(), request.getPassword());

        ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", loginResult.refreshToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(604800)
                .path("/api/v1/auth")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());

        return ApiResponse.success(new AuthResponse(loginResult.accessToken()));
    }

    /**
     * 刷新 Access Token
     *
     * <p>從 Cookie 取得 Refresh Token，驗證有效性後發放新的 Access Token。</p>
     *
     * @param refreshToken Cookie 中的 Refresh Token
     * @return 含新 Access Token 的認證回應
     */
    @Operation(summary = "刷新 Access Token", description = "使用 Cookie 中的 Refresh Token 換取新的 Access Token")
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken) {

        if (refreshToken == null) {
            throw new BusinessException(UserErrorCode.TOKEN_INVALID);
        }

        if (!jwtService.validateToken(refreshToken)) {
            throw new BusinessException(UserErrorCode.TOKEN_INVALID);
        }

        if (!"refresh".equals(jwtService.getTokenTypeFromToken(refreshToken))) {
            throw new BusinessException(UserErrorCode.TOKEN_INVALID);
        }

        Long userId = Long.parseLong(jwtService.getUserIdFromToken(refreshToken));

        String storedToken = redisTemplate.opsForValue().get(RedisKeyConstant.getUserRefreshKey(userId));
        if (!refreshToken.equals(storedToken)) {
            throw new BusinessException(UserErrorCode.TOKEN_INVALID);
        }

        String version = (String) redisTemplate.opsForHash()
                .get(RedisKeyConstant.getUserAuthKey(userId), RedisKeyConstant.FIELD_VERSION);
        String status = (String) redisTemplate.opsForHash()
                .get(RedisKeyConstant.getUserAuthKey(userId), RedisKeyConstant.FIELD_STATUS);

        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(UserErrorCode.ACCOUNT_SUSPENDED);
        }

        String roleStr = (String) redisTemplate.opsForHash()
                .get(RedisKeyConstant.getUserAuthKey(userId), "role");

        String newAccessToken = jwtService.generateAccessToken(
                userId,
                roleStr != null ? roleStr : "USER",
                version != null ? version : "v1"
        );

        return ApiResponse.success(new AuthResponse(newAccessToken));
    }

    /**
     * 用戶登出
     *
     * <p>清除 Redis 中的 Refresh Token 並重置 Cookie。</p>
     *
     * @param authentication Spring Security 認證物件，用於取得當前用戶 ID
     * @param response        HTTP 回應，用於清除 Cookie
     * @return 成功回應
     */
    @Operation(summary = "用戶登出", description = "清除 Refresh Token，使 Cookie 失效")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(Authentication authentication, HttpServletResponse response) {
        Long userId = (Long) authentication.getPrincipal();
        authService.logout(userId);

        ResponseCookie clearCookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .maxAge(0)
                .path("/api/v1/auth")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());

        return ApiResponse.success();
    }

    /**
     * 驗證電子信箱
     *
     * @param token 驗證 Token 字串（來自驗證信連結）
     * @return 成功回應
     */
    @Operation(summary = "驗證電子信箱", description = "透過驗證信中的 Token 啟用帳號")
    @GetMapping("/verify-email")
    public ApiResponse<Void> verifyEmail(@RequestParam String token) {
        authService.verifyEmail(token);
        return ApiResponse.success();
    }

    /**
     * 申請忘記密碼
     *
     * @param request 包含電子信箱的忘記密碼請求
     * @return 成功回應（無論信箱是否存在，一律回傳成功以防資訊洩漏）
     */
    @Operation(summary = "忘記密碼", description = "發送密碼重設信至指定信箱")
    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.getEmail());
        return ApiResponse.success();
    }

    /**
     * 重設密碼
     *
     * @param request 包含重設 Token 與新密碼的請求
     * @return 成功回應
     */
    @Operation(summary = "重設密碼", description = "透過密碼重設信中的 Token 設定新密碼")
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.getToken(), request.getNewPassword());
        return ApiResponse.success();
    }
}
