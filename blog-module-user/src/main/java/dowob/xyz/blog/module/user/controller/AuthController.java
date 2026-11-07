package dowob.xyz.blog.module.user.controller;

import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.security.AuthState;
import dowob.xyz.blog.infrastructure.security.AuthStateLoader;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.web.ClientIpResolver;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.user.model.dto.request.ForgotPasswordRequest;
import dowob.xyz.blog.module.user.model.dto.request.LoginRequest;
import dowob.xyz.blog.module.user.model.dto.request.RegisterRequest;
import dowob.xyz.blog.module.user.model.dto.request.ResetPasswordRequest;
import dowob.xyz.blog.module.user.model.dto.request.VerifyEmailCodeRequest;
import dowob.xyz.blog.module.user.model.dto.request.VerifyEmailRequest;
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

import java.util.UUID;

/**
 * 認證控制器
 *
 * <p>提供用戶認證相關的 REST API，包含：
 * 註冊、登入、登出、刷新 Token、電子信箱驗證、忘記密碼與重設密碼。</p>
 *
 * @author Yuan
 * @version 2.1
 */
@Tag(name = "認證", description = "認證相關 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    /** 認證業務服務 */
    private final AuthService authService;

    /** JWT 服務，用於 Refresh Token 驗證 */
    private final JwtService jwtService;

    /** Client IP 解析器（登入／註冊／忘記密碼／重寄驗證信／驗證碼 IP 限流用；信任鏈由 app.http.trusted-proxy-count 控制） */
    private final ClientIpResolver clientIpResolver;

    /** Redis 操作模板，用於 Refresh Token 比對 */
    private final StringRedisTemplate redisTemplate;

    /** 認證狀態載入器（與 JwtAuthenticationFilter 共用，Redis miss 時查 DB 回填） */
    private final AuthStateLoader authStateLoader;

    /** Cookie Secure flag；dev 環境設 false 以支援 http + Firefox/Safari */
    @Value("${app.cookie.secure:true}")
    private boolean cookieSecure;

    /**
     * 用戶註冊
     *
     * @param request 包含信箱、密碼、用戶名與暱稱的註冊請求
     * @return 成功回應
     */
    @Operation(summary = "用戶註冊",
            description = "公開。使用信箱註冊新帳號，完成後需驗證電子信箱才可登入；信箱已被註冊時同樣回成功（不洩漏帳號是否存在），並通知原信箱擁有者。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗（含保留暱稱）；A0112 用戶名已被使用；A0107 暱稱已被使用；A0113 同 IP 註冊過於頻繁")
    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        authService.register(request.getEmail(), request.getPassword(), request.getUsername(), request.getNickname(),
                clientIpResolver.resolve(httpRequest));
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
    @Operation(summary = "用戶登入",
            description = "公開。使用信箱或用戶名登入，回傳 Access Token（Refresh Token 以 HttpOnly Cookie 下發）；連續失敗達上限會暫時鎖定帳號。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗；A0102 帳號或密碼錯誤；A0114 帳號因多次失敗被暫時鎖定；A0111 電子信箱尚未驗證；A0108 帳號已停權或刪除；A0113 同 IP 登入過於頻繁")
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                           HttpServletRequest httpRequest,
                                           HttpServletResponse response) {
        LoginResult loginResult = authService.login(request.getIdentifier(), request.getPassword(),
                clientIpResolver.resolve(httpRequest));

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
     * <p>從 Cookie 取得 Refresh Token，驗證有效性後發放新的 Access Token。帳號狀態與角色經
     * {@link AuthStateLoader} 取得（Redis 優先，miss 回填 DB），只有 ACTIVE 帳號可以續期。</p>
     *
     * @param refreshToken Cookie 中的 Refresh Token
     * @return 含新 Access Token 的認證回應
     */
    @Operation(summary = "刷新 Access Token",
            description = "公開（需帶 Refresh Token Cookie）。以 Cookie 中的 Refresh Token 換取新的 Access Token。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0108 帳號已停權或刪除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "A0005 缺少、無效或已撤銷的 Refresh Token Cookie")
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken) {

        if (refreshToken == null) {
            throw unauthenticatedRefresh();
        }

        if (!jwtService.validateRefreshToken(refreshToken)) {
            throw unauthenticatedRefresh();
        }

        UUID userUuid = jwtService.getUserUuidFromToken(refreshToken);

        Double score = redisTemplate.opsForZSet().score(RedisKeyConstant.getUserRefreshKey(userUuid), refreshToken);
        if (score == null) {
            throw unauthenticatedRefresh();
        }

        /** 與 JwtAuthenticationFilter 共用 loader：Redis 命中即用，miss 查 DB 回填（修正 SEC-26：miss 不再誤判停權） */
        AuthState authState = authStateLoader.load(userUuid).orElseThrow(this::unauthenticatedRefresh);

        /** 政策維持現狀：refresh 只接受 ACTIVE（比 filter 嚴格，PENDING_VERIFICATION 不可續期） */
        if (authState.status() != UserStatus.ACTIVE) {
            throw new BusinessException(UserErrorCode.ACCOUNT_SUSPENDED);
        }

        String newAccessToken = jwtService.generateAccessToken(
                userUuid,
                authState.role().name(),
                authState.tokenVersion()
        );

        return ApiResponse.success(new AuthResponse(newAccessToken));
    }

    private ResponseStatusException unauthenticatedRefresh() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "請先登入");
    }

    /**
     * 用戶登出
     *
     * <p>清除 Redis 中的 Refresh Token 並重置 Cookie。</p>
     *
     * @param user         當前登入用戶（由 Spring Security 自動注入）
     * @param refreshToken Cookie 中的 Refresh Token（可為 null）
     * @param response     HTTP 回應，用於清除 Cookie
     * @return 成功回應
     */
    @Operation(summary = "用戶登出",
            description = "登入。撤銷 Refresh Token 並清除 Cookie。")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthUser user,
                                    @CookieValue(name = "refreshToken", required = false) String refreshToken,
                                    HttpServletResponse response) {
        authService.logout(user.uuid(), refreshToken);

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
     * <p>
     * 採 POST + request body：token 屬憑證，不得經由 query string 傳遞
     * （security.md 原則 8，否則會進入 access log 與瀏覽器歷史）。
     * 驗證信中的連結指向前端頁面而非本端點，故此形狀變更不影響已寄出的信件。
     * </p>
     *
     * @param request 包含驗證 Token（來自驗證信連結）
     * @return 成功回應
     */
    @Operation(summary = "驗證電子信箱",
            description = "公開。透過驗證信中的 Token 啟用帳號。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗；A0104 Token 無效或已過期；A0101 用戶不存在")
    @PostMapping("/verify-email")
    public ApiResponse<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request.getToken());
        return ApiResponse.success();
    }

    /**
     * 使用驗證碼驗證電子信箱
     *
     * @param request     包含電子信箱與 6 位數驗證碼
     * @param httpRequest HTTP 請求（解析 client IP 供 IP 層級限流，A3）
     * @return 成功回應
     */
    @Operation(summary = "使用驗證碼驗證電子信箱",
            description = "公開。透過驗證信中的 6 位數驗證碼啟用帳號；失敗次數過多會被限流。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗；A0104 驗證碼錯誤或已過期；A0113 驗證嘗試次數過多")
    @PostMapping("/verify-email-code")
    public ApiResponse<Void> verifyEmailCode(@Valid @RequestBody VerifyEmailCodeRequest request,
                                             HttpServletRequest httpRequest) {
        authService.verifyEmailCode(request.getEmail(), request.getCode(), clientIpResolver.resolve(httpRequest));
        return ApiResponse.success();
    }

    /**
     * 重發驗證信
     *
     * @param request     包含電子信箱的請求
     * @param httpRequest HTTP 請求（解析 client IP 供 IP 層級限流，A3）
     * @return 成功回應（無論信箱是否存在或帳號狀態，一律回傳成功以防資訊洩漏）
     */
    @Operation(summary = "重發驗證信",
            description = "公開。重新發送電子信箱驗證信；頻率限制見錯誤回應。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗；A0113 超過重發頻率限制（每信箱每分鐘 1 次、每日 5 次、每 IP 每小時 10 次）")
    @PostMapping("/resend-verification")
    public ApiResponse<Void> resendVerification(@Valid @RequestBody ForgotPasswordRequest request,
                                                HttpServletRequest httpRequest) {
        authService.resendVerification(request.getEmail(), clientIpResolver.resolve(httpRequest));
        return ApiResponse.success();
    }

    /**
     * 申請忘記密碼
     *
     * @param request     包含電子信箱的忘記密碼請求
     * @param httpRequest HTTP 請求（解析 client IP 供 IP 層級限流，A3）
     * @return 成功回應（無論信箱是否存在，一律回傳成功以防資訊洩漏）
     */
    @Operation(summary = "忘記密碼",
            description = "公開。發送密碼重設信至指定信箱；無論信箱是否存在皆回成功（不洩漏帳號是否存在）。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗；A0113 超過請求頻率限制")
    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                            HttpServletRequest httpRequest) {
        authService.forgotPassword(request.getEmail(), clientIpResolver.resolve(httpRequest));
        return ApiResponse.success();
    }

    /**
     * 重設密碼
     *
     * @param request 包含重設 Token 與新密碼的請求
     * @return 成功回應
     */
    @Operation(summary = "重設密碼",
            description = "公開。透過密碼重設信中的 Token 設定新密碼。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗；A0104 Token 無效或已過期；A0101 用戶不存在")
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.getToken(), request.getNewPassword());
        return ApiResponse.success();
    }
}
