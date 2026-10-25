package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.request.PageQuery;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryResponse;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.reading.service.BookmarkQueryService;
import dowob.xyz.blog.module.reading.service.BookmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 收藏 Controller。
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Bookmark")
public class BookmarkController {

    private final BookmarkService bookmarkService;
    private final ArticleFacade articleFacade;
    private final BookmarkQueryService bookmarkQueryService;

    @PostMapping("/articles/{articleUuid}/bookmark")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "收藏文章（idempotent）")
    public ApiResponse<Void> bookmark(@AuthenticationPrincipal AuthUser user,
                                        @PathVariable UUID articleUuid) {
        requireArticleExists(articleUuid);
        bookmarkService.bookmark(user.uuid(), articleUuid);
        return ApiResponse.success();
    }

    @DeleteMapping("/articles/{articleUuid}/bookmark")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "取消收藏（idempotent）")
    public ApiResponse<Void> unbookmark(@AuthenticationPrincipal AuthUser user,
                                          @PathVariable UUID articleUuid) {
        requireArticleExists(articleUuid);
        bookmarkService.unbookmark(user.uuid(), articleUuid);
        return ApiResponse.success();
    }

    /**
     * 我的收藏列表。
     *
     * <p>編排在 {@link BookmarkQueryService}（ARCH-09）。{@code total} 為
     * <b>對本人可見</b>的收藏數，與 {@code pages} 一致——前端以 {@code pages}
     * 畫分頁器，若沿用收藏列數會產生空尾頁。</p>
     *
     * @param user         當前登入者（AuthUser）
     * @param authentication 當前認證資訊（用於判斷是否為 ADMIN）
     * @param pageQuery  分頁參數（query string 仍為 {@code page} / {@code size}；
     *                   {@code size} 上限見 {@link PageQuery#MAX_SIZE}）
     * @return 收藏文章摘要分頁列表（已濾除對本人不可見的文章）
     */
    @GetMapping("/users/me/bookmarks")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "我的收藏列表")
    public ApiResponse<PageResult<ArticleSummaryResponse>> myBookmarks(
            @AuthenticationPrincipal AuthUser user,
            Authentication authentication,
            PageQuery pageQuery) {
        return ApiResponse.success(bookmarkQueryService.listMyBookmarks(
                user.uuid(), SecurityUtils.isAdmin(authentication), pageQuery.page(), pageQuery.sizeOrDefault(20)));
    }

    /**
     * 確認文章存在（不分狀態），不存在時以 {@code A0201} 回應（HTTP 400，與解耦前相同）。
     *
     * <p>與 {@code ArticleLikeController.requireArticleExists} 同一套 idiom：必須在寫入前呼叫，
     * 不存在的文章不得落任何列（AUTH-08：避免未預期的 500）。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @throws BusinessException {@code ARTICLE_NOT_FOUND} 若查無此文章
     */
    private void requireArticleExists(UUID articleUuid) {
        if (!articleFacade.existsByUuid(articleUuid)) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
        }
    }
}
