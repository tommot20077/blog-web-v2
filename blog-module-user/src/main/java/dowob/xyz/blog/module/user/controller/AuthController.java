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
import dowob.xyz.blog.module.user.model.dto.request.VerifyEmailCodeRequest;
import dowob.xyz.blog.module.user.model.dto.response.AuthResponse;
import dowob.xyz.blog.module.user.model.dto.response.LoginResult;
import dowob.xyz.blog.module.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 認證控制器
 *
 * <p>提供用戶認證相關的 REST API，包含：
 * 註冊、登入、登出、刷新 Token、電子信箱驗證、忘記密碼與重設密碼。</p>
 *
 * @author Yuan
 * @version 2.1
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

    /** Cookie Secure flag；dev 環境設 false 以支援 http + Firefox/Safari */
    @Value("${app.cookie.secure:true}")
    private boolean cookieSecure;

    /**
     * 用戶註冊
     *
     * @param request 包含信箱、密碼、用戶名與暱稱的註冊請求
     * @return 成功回應
     */
    @Operation(summary = "用戶註冊", description = "使用信箱註冊新帳號，完成後需驗證電子信箱才可登入")
    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        authService.register(request.getEmail(), request.getPassword(), request.getUsername(), request.getNickname(),
                resolveClientIp(httpRequest));
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
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                           HttpServletRequest httpRequest,
                                           HttpServletResponse response) {
        LoginResult loginResult = authService.login(request.getIdentifier(), request.getPassword(),
                resolveClientIp(httpRequest));

        ResponseCookie refreshCookie = ResponseCookie.from("refreshToken", loginResult.refreshToken())
                .httpOnly(true)
                .secure(cookieSecure)
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
            throw unauthenticatedRefresh();
        }

        if (!jwtService.validateRefreshToken(refreshToken)) {
            throw unauthenticatedRefresh();
        }

        Long userId = Long.parseLong(jwtService.getUserIdFromToken(refreshToken));

        Double score = redisTemplate.opsForZSet().score(RedisKeyConstant.getUserRefreshKey(userId), refreshToken);
        if (score == null) {
            throw unauthenticatedRefresh();
        }

        String version = (String) redisTemplate.opsForHash()
                .get(RedisKeyConstant.getUserAuthKey(userId), RedisKeyConstant.FIELD_VERSION);
        String status = (String) redisTemplate.opsForHash()
                .get(RedisKeyConstant.getUserAuthKey(userId), RedisKeyConstant.FIELD_STATUS);

        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(UserErrorCode.ACCOUNT_SUSPENDED);
        }

        String roleStr = (String) redisTemplate.opsForHash()
                .get(RedisKeyConstant.getUserAuthKey(userId), RedisKeyConstant.FIELD_ROLE);

        String newAccessToken = jwtService.generateAccessToken(
                userId,
                roleStr != null ? roleStr : "USER",
                version != null ? version : "v1"
        );

        return ApiResponse.success(new AuthResponse(newAccessToken));
    }

    private ResponseStatusException unauthenticatedRefresh() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "請先登入");
    }

    /**
     * 解析 client IP（供 IP 層級限流使用）
     *
     * <p>prod 環境前後端同域（{@code https://90030.xyz}）並走反向代理，故優先信任
     * {@code X-Forwarded-For} 標頭並取其第一個 IP（最接近真實 client 的位址）；
     * 若無此標頭則退回 {@link HttpServletRequest#getRemoteAddr()}。</p>
     *
     * @param request HTTP 請求
     * @return client IP 字串；無法解析時可能為 null
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 用戶登出
     *
     * <p>清除 Redis 中的 Refresh Token 並重置 Cookie。</p>
     *
     * @param userId       當前登入用戶的資料庫主鍵（由 Spring Security 自動注入）
     * @param refreshToken Cookie 中的 Refresh Token（可為 null）
     * @param response     HTTP 回應，用於清除 Cookie
     * @return 成功回應
     */
    @Operation(summary = "用戶登出", description = "清除 Refresh Token，使 Cookie 失效")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal Long userId,
                                    @CookieValue(name = "refreshToken", required = false) String refreshToken,
                                    HttpServletResponse response) {
        authService.logout(userId, refreshToken);

        ResponseCookie clearCookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(cookieSecure)
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
     * 使用驗證碼驗證電子信箱
     *
     * @param request 包含電子信箱與 6 位數驗證碼
     * @return 成功回應
     */
    @Operation(summary = "使用驗證碼驗證電子信箱", description = "透過驗證信中的 6 位數驗證碼啟用帳號")
    @PostMapping("/verify-email-code")
    public ApiResponse<Void> verifyEmailCode(@Valid @RequestBody VerifyEmailCodeRequest request) {
        authService.verifyEmailCode(request.getEmail(), request.getCode());
        return ApiResponse.success();
    }

    /**
     * 重發驗證信
     *
     * @param request 包含電子信箱的請求
     * @return 成功回應（無論信箱是否存在或帳號狀態，一律回傳成功以防資訊洩漏）
     */
    @Operation(summary = "重發驗證信", description = "重新發送電子信箱驗證信，每分鐘限 1 次，每日限 5 次")
    @PostMapping("/resend-verification")
    public ApiResponse<Void> resendVerification(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.resendVerification(request.getEmail());
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
