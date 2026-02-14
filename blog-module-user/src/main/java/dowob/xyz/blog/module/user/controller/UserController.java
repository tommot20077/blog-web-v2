package dowob.xyz.blog.module.user.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.user.model.dto.request.ChangePasswordRequest;
import dowob.xyz.blog.module.user.model.dto.request.UpdateProfileRequest;
import dowob.xyz.blog.module.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
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
@Tag(name = "User", description = "用戶自助服務 API")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    /** 用戶自助業務服務 */
    private final UserService userService;

    /**
     * 更新個人資料
     *
     * @param authentication Spring Security 認證物件，用於取得當前用戶 ID
     * @param request         包含新暱稱與個人簡介的請求
     * @return 成功回應
     */
    @Operation(summary = "更新個人資料", description = "更新當前登入用戶的暱稱與個人簡介")
    @PatchMapping("/me/profile")
    public ApiResponse<Void> updateProfile(Authentication authentication,
                                            @Valid @RequestBody UpdateProfileRequest request) {
        Long userId = (Long) authentication.getPrincipal();
        userService.updateProfile(userId, request.getNickname(), request.getBio(), request.getWebsite(), request.getSocialLinks());
        return ApiResponse.success();
    }

    /**
     * 修改密碼
     *
     * @param authentication Spring Security 認證物件，用於取得當前用戶 ID
     * @param request         包含舊密碼與新密碼的請求
     * @return 成功回應
     */
    @Operation(summary = "修改密碼", description = "驗證舊密碼後更新密碼，所有現有 Token 將立即失效")
    @PostMapping("/me/change-password")
    public ApiResponse<Void> changePassword(Authentication authentication,
                                             @Valid @RequestBody ChangePasswordRequest request) {
        Long userId = (Long) authentication.getPrincipal();
        userService.changePassword(userId, request.getOldPassword(), request.getNewPassword());
        return ApiResponse.success();
    }

    /**
     * 刪除帳號
     *
     * @param authentication Spring Security 認證物件，用於取得當前用戶 ID
     * @param password        當前密碼（用於二次身份確認）
     * @return 成功回應
     */
    @Operation(summary = "刪除帳號", description = "驗證密碼後將帳號標記為已刪除，所有現有 Token 將立即失效")
    @DeleteMapping("/me")
    public ApiResponse<Void> deleteAccount(Authentication authentication,
                                            @RequestParam String password) {
        Long userId = (Long) authentication.getPrincipal();
        userService.deleteAccount(userId, password);
        return ApiResponse.success();
    }
}
