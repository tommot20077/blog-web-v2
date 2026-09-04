package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.util.ArticleVisibility;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.article.service.ArticleQueryService;
import dowob.xyz.blog.module.reading.service.BookmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /**
     * 我的收藏列表。
     *
     * <p><b>可見性過濾</b>：收藏端點（{@link #bookmark}）不檢查 status，且文章下架
     * （PUBLISHED → ARCHIVED）只改狀態、不刪 bookmark 列（硬刪才會被 FK CASCADE 清掉），
     * 因此收藏列表必須自行過濾——否則已下架文章的 title / summary / slug / tags / author
     * 會繼續留在收藏它的人的列表裡。過濾責任在 caller 端，與 {@code SeriesService.getSeriesDetail}
     * 同 pattern（{@code ArticleFacade} 的 read 依契約不限狀態）。</p>
     *
     * <p>判斷委派 {@link ArticleVisibility}，與文章詳情端點同一套政策：作者本人與 ADMIN
     * 仍看得到自己收藏的非公開文章（點進詳情也讀得到，兩邊一致）。</p>
     *
     * <p><b>total 的口徑</b>：仍為該使用者的 bookmark 列數（含被過濾掉的），因為要精確計算
     * 「可見的收藏數」必須把該使用者全部 bookmark 撈出來比對狀態，代價與收藏數成正比。
     * 此處只是呼叫者自己的收藏總數，不透露任何被過濾文章的內容或身分，與 series 詳情
     * 「articleCount 會反推出隱藏文章數」的公開端點情境不同。</p>
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

        int offset = Math.max(0, (page - 1) * size);
        List<Long> articleIds = bookmarkService.findMyBookmarkedArticleIds(userId, size, offset);
        long total = bookmarkService.countByUser(userId);

        List<Long> visibleIds = filterReadable(articleIds, userId, SecurityUtils.isAdmin(authentication));
        List<ArticleSummaryResponse> records = articleQueryService.getArticleSummariesByIds(visibleIds);
        PageResult<ArticleSummaryResponse> result = PageResult.of(page, size, total, records);
        return ApiResponse.success(result);
    }

    /**
     * 濾掉對當前使用者不可見的文章 id，並維持原本的收藏排序。
     *
     * @param articleIds 原始文章主鍵列表（依收藏時間排序）
     * @param viewerId   檢視者資料庫主鍵
     * @param isAdmin    檢視者是否為 ADMIN
     * @return 可見的文章主鍵列表（順序與輸入一致）
     */
    private List<Long> filterReadable(List<Long> articleIds, Long viewerId, boolean isAdmin) {
        if (articleIds.isEmpty()) {
            return List.of();
        }
        Set<Long> readableIds = articleFacade.findByIds(articleIds).stream()
                .filter(a -> ArticleVisibility.isReadableBy(a.status(), a.authorId(), viewerId, isAdmin))
                .map(ArticleData::id)
                .collect(Collectors.toSet());
        return articleIds.stream().filter(readableIds::contains).toList();
    }
}
