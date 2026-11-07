package dowob.xyz.blog.module.user.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.module.user.model.dto.response.AuthorSummaryResponse;
import dowob.xyz.blog.module.user.model.dto.response.PublicProfileResponse;
import dowob.xyz.blog.module.user.service.PublicAuthorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 公開作者控制器：公開作者頁資料與作者下拉搜尋
 *
 * <p>兩個端點都是<b>匿名可存取</b>（{@code SecurityConfig} 明確 {@code permitAll()}，並列於
 * {@code security.md} Public Endpoints 表），因此沒有 {@code @PreAuthorize}，也不取用
 * {@code @AuthenticationPrincipal}（security.md 原則 7：回應內容不隨登入者而變）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Tag(name = "公開作者", description = "公開作者 API（匿名可存取）")
@RestController
@RequiredArgsConstructor
public class PublicAuthorController {

    /** 公開作者業務服務 */
    private final PublicAuthorService publicAuthorService;

    /**
     * 取得公開作者頁資料（匿名可存取）
     *
     * @param uuid 使用者公開 UUID
     * @return 公開資料；不存在或非 ACTIVE 一律 404 {@code A0101}
     */
    @Operation(summary = "取得公開作者頁資料",
            description = "公開（匿名可存取）。僅 ACTIVE 使用者可見。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "A0101 作者不存在（不存在、已刪除、未驗證、停權一律回 404，彼此不可區分）")
    @GetMapping("/api/v1/users/{uuid}/profile")
    public ApiResponse<PublicProfileResponse> getPublicProfile(@PathVariable UUID uuid) {
        return ApiResponse.success(publicAuthorService.getPublicProfile(uuid));
    }

    /**
     * 搜尋作者（匿名可存取）
     *
     * @param keyword 暱稱關鍵字（選填；trim 後不分大小寫、任意位置比對；超過 50 字回 400）
     * @return 至少有 1 篇已發布文章的 ACTIVE 作者，最多 20 筆
     */
    @Operation(summary = "搜尋作者",
            description = "公開（匿名可存取）。只列 ACTIVE 且至少 1 篇已發布文章的作者；依已發布篇數 DESC、暱稱 ASC、uuid ASC 排序，固定最多 20 筆。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "A0001 參數校驗失敗（keyword 不合法）")
    @GetMapping("/api/v1/authors")
    public ApiResponse<List<AuthorSummaryResponse>> searchAuthors(
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(publicAuthorService.searchAuthors(keyword));
    }
}
