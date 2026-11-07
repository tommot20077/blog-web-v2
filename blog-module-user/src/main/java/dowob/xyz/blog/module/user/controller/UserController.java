package dowob.xyz.blog.module.user.controller;

import dowob.xyz.blog.infrastructure.ratelimit.RateLimited;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.module.user.model.dto.request.ChangePasswordRequest;
import dowob.xyz.blog.module.user.model.dto.request.DeleteAccountRequest;
import dowob.xyz.blog.module.user.model.dto.request.NotificationPreferencesRequest;
import dowob.xyz.blog.module.user.model.dto.request.UpdateProfileRequest;
import dowob.xyz.blog.module.user.model.dto.response.UserProfileResponse;
import dowob.xyz.blog.module.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 用戶自助服務控制器
 *
 * <p>提供已登入用戶的自助操作 API，包含更新個人資料、修改密碼與刪除帳號。
 * 所有端點均需要有效的 JWT 認證。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Tag(name = "使用者", description = "用戶自助服務 API")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    /** 用戶自助業務服務 */
    private final UserService userService;

    /**
     * 取得當前使用者個人資料
     *
     * @param user 當前登入者（AuthUser；由 Spring Security 自動注入）
     * @return 使用者個人資料
     */
    @Operation(summary = "取得當前使用者資訊",
            description = "登入。回傳當前登入使用者的個人資料。")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getMe(@AuthenticationPrincipal AuthUser user) {
        return ApiResponse.success(userService.getUserProfile(user.uuid()));
    }

    /**
     * 更新個人資料
     *
     * @param user  當前登入者（AuthUser；由 Spring Security 自動注入）
     * @param request 包含新暱稱與個人簡介的請求
     * @return 成功回應
     */
    @Operation(summary = "更新個人資料",
            description = "登入。更新暱稱、個人簡介、網站、社群連結、頭貼與所在地。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗（含保留暱稱、website／avatarUrl／socialLinks 格式不合法）；A0107 暱稱已被使用；A0407 頭像檔案無效（非本人上傳的頭像）；A0101 用戶不存在")
    @PreAuthorize("isAuthenticated()")
    @PatchMapping("/me/profile")
    @RateLimited("account-write")
    public ApiResponse<Void> updateProfile(@AuthenticationPrincipal AuthUser user,
                                            @Valid @RequestBody UpdateProfileRequest request) {
        userService.updateProfile(user.uuid(), request.getNickname(), request.getBio(), request.getWebsite(),
                request.getSocialLinks(), request.getAvatarUrl(), request.getLocation());
        return ApiResponse.success();
    }

    /**
     * 更新通知偏好
     *
     * @param user  當前登入者（AuthUser；由 Spring Security 自動注入）
     * @param request 包含 5 個通知偏好開關的請求
     * @return 成功回應
     */
    @Operation(summary = "更新通知偏好",
            description = "登入。更新留言、按讚、審核、追蹤與電子報通知偏好。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗；A0101 用戶不存在")
    @PreAuthorize("isAuthenticated()")
    @PatchMapping("/me/notifications")
    @RateLimited("account-write")
    public ApiResponse<Void> updateNotifications(@AuthenticationPrincipal AuthUser user,
                                                 @Valid @RequestBody NotificationPreferencesRequest request) {
        userService.updateNotificationPreferences(user.uuid(),
                request.getComment(), request.getLike(), request.getReview(),
                request.getFollow(), request.getNewsletter());
        return ApiResponse.success();
    }

    /**
     * 修改密碼
     *
     * @param user  當前登入者（AuthUser；由 Spring Security 自動注入）
     * @param request 包含舊密碼與新密碼的請求
     * @return 成功回應
     */
    @Operation(summary = "修改密碼",
            description = "登入。驗證舊密碼後更新密碼，所有現有 Token 將立即失效。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗；A0102 舊密碼錯誤；A0101 用戶不存在")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/me/change-password")
    @RateLimited("account-write")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal AuthUser user,
                                             @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(user.uuid(), request.getOldPassword(), request.getNewPassword());
        return ApiResponse.success();
    }

    /**
     * 刪除帳號
     *
     * @param user  當前登入者（AuthUser；由 Spring Security 自動注入）
     * @param request 包含當前密碼的刪除帳號請求（用於二次身份確認）
     * @return 成功回應
     */
    @Operation(summary = "刪除帳號",
            description = "登入。驗證密碼後將帳號標記為已刪除，所有現有 Token 將立即失效。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗；A0102 密碼錯誤；A0101 用戶不存在")
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/me")
    public ApiResponse<Void> deleteAccount(@AuthenticationPrincipal AuthUser user,
                                            @Valid @RequestBody DeleteAccountRequest request) {
        userService.deleteAccount(user.uuid(), request.getPassword());
        return ApiResponse.success();
    }
}
