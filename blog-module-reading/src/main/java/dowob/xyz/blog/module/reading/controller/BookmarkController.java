package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
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
    public ApiResponse<Void> bookmark(@AuthenticationPrincipal Long userId,
                                        @PathVariable UUID articleUuid) {
        Long articleId = articleFacade.findIdByUuid(articleUuid);
        if (articleId == null) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
        }
        bookmarkService.bookmark(userId, articleId);
        return ApiResponse.success();
    }

    @DeleteMapping("/articles/{articleUuid}/bookmark")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "取消收藏（idempotent）")
    public ApiResponse<Void> unbookmark(@AuthenticationPrincipal Long userId,
                                          @PathVariable UUID articleUuid) {
        Long articleId = articleFacade.findIdByUuid(articleUuid);
        if (articleId == null) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
        }
        bookmarkService.unbookmark(userId, articleId);
        return ApiResponse.success();
    }

    /**
     * 我的收藏列表。
     *
     * <p>編排在 {@link BookmarkQueryService}（ARCH-09）。{@code total} 為
     * <b>對本人可見</b>的收藏數，與 {@code pages} 一致——前端以 {@code pages}
     * 畫分頁器，若沿用收藏列數會產生空尾頁。</p>
     *
     * @param userId         當前登入用戶的資料庫主鍵
     * @param authentication 當前認證資訊（用於判斷是否為 ADMIN）
     * @param page           頁碼（自 1 起）
     * @param size           每頁筆數
     * @return 收藏文章摘要分頁列表（已濾除對本人不可見的文章）
     */
    @GetMapping("/users/me/bookmarks")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "我的收藏列表")
    public ApiResponse<PageResult<ArticleSummaryResponse>> myBookmarks(
            @AuthenticationPrincipal Long userId,
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(bookmarkQueryService.listMyBookmarks(
                userId, SecurityUtils.isAdmin(authentication), page, size));
    }
}
