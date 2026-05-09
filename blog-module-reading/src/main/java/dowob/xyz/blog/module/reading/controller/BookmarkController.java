package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.article.service.ArticleQueryService;
import dowob.xyz.blog.module.reading.service.BookmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    private final ArticleQueryService articleQueryService;

    @PostMapping("/articles/{articleUuid}/bookmark")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "收藏文章（idempotent）")
    public ApiResponse<Void> bookmark(@AuthenticationPrincipal Long userId,
                                        @PathVariable UUID articleUuid) {
        Long articleId = articleFacade.findIdByUuid(articleUuid);
        bookmarkService.bookmark(userId, articleId);
        return ApiResponse.success();
    }

    @DeleteMapping("/articles/{articleUuid}/bookmark")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "取消收藏（idempotent）")
    public ApiResponse<Void> unbookmark(@AuthenticationPrincipal Long userId,
                                          @PathVariable UUID articleUuid) {
        Long articleId = articleFacade.findIdByUuid(articleUuid);
        bookmarkService.unbookmark(userId, articleId);
        return ApiResponse.success();
    }

    @GetMapping("/users/me/bookmarks")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "我的收藏列表")
    public ApiResponse<PageResult<ArticleSummaryResponse>> myBookmarks(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        int offset = Math.max(0, (page - 1) * size);
        List<Long> articleIds = bookmarkService.findMyBookmarkedArticleIds(userId, size, offset);
        long total = bookmarkService.countByUser(userId);

        List<ArticleSummaryResponse> records = articleQueryService.getArticleSummariesByIds(articleIds);
        PageResult<ArticleSummaryResponse> result = PageResult.of(page, size, total, records);
        return ApiResponse.success(result);
    }
}
