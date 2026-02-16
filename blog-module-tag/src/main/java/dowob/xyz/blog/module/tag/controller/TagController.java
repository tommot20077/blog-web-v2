package dowob.xyz.blog.module.tag.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.model.dto.TagDetailResponse;
import dowob.xyz.blog.module.tag.service.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * 標籤公開 API 控制器
 *
 * <p>
 * 提供標籤自動補全、熱門標籤、標籤詳情，
 * 以及已認證使用者的追蹤/取消追蹤功能。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
public class TagController {

    /**
     * 標籤業務服務
     */
    private final TagService tagService;

    /**
     * 用戶 Facade（跨模組查詢使用者 UUID）
     */
    private final UserFacade userFacade;

    /**
     * 取得標籤自動補全建議（公開）
     *
     * @param prefix 搜尋前綴
     * @param limit  最大返回數量，預設 10
     * @return 建議的 Slug 列表
     */
    @GetMapping("/suggest")
    public ApiResponse<List<String>> suggest(
            @RequestParam("q") String prefix,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        return ApiResponse.success(tagService.suggest(prefix, limit));
    }

    /**
     * 取得熱門標籤列表（公開）
     *
     * @param limit 最大返回數量，預設 20
     * @return 熱門標籤列表
     */
    @GetMapping("/hot")
    public ApiResponse<List<Tag>> getHotTags(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResponse.success(tagService.getHotTags(limit));
    }

    /**
     * 依 Slug 取得標籤詳情（公開）
     *
     * @param slug 標籤 Slug
     * @return 標籤詳情
     */
    @GetMapping("/{slug}")
    public ApiResponse<TagDetailResponse> getTagDetail(@PathVariable String slug) {
        return ApiResponse.success(tagService.getTagDetail(slug));
    }

    /**
     * 使用者追蹤標籤（需登入）
     *
     * <p>透過 {@link UserFacade} 以使用者內部 ID 查詢其真實 UUID，確保操作對應實際使用者。</p>
     *
     * @param id     標籤 ID
     * @param userId 當前登入使用者 ID（從 JWT 注入）
     * @return 成功回應
     * @throws ResponseStatusException 404 若使用者不存在
     */
    @PostMapping("/{id}/follow")
    public ApiResponse<Void> followTag(
            @PathVariable UUID id,
            @AuthenticationPrincipal Long userId) {
        UUID userUuid = userFacade.getUserUuidById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        tagService.followTag(id, userUuid);
        return ApiResponse.success();
    }

    /**
     * 使用者取消追蹤標籤（需登入）
     *
     * <p>透過 {@link UserFacade} 以使用者內部 ID 查詢其真實 UUID，確保操作對應實際使用者。</p>
     *
     * @param id     標籤 ID
     * @param userId 當前登入使用者 ID（從 JWT 注入）
     * @return 成功回應
     * @throws ResponseStatusException 404 若使用者不存在
     */
    @DeleteMapping("/{id}/follow")
    public ApiResponse<Void> unfollowTag(
            @PathVariable UUID id,
            @AuthenticationPrincipal Long userId) {
        UUID userUuid = userFacade.getUserUuidById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        tagService.unfollowTag(id, userUuid);
        return ApiResponse.success();
    }
}
