package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.reading.service.ArticleLikeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 文章按讚 Controller。
 *
 * <p>POST/DELETE 皆為 idempotent — 重複按讚或重複取消都不會報錯。</p>
 *
 * <p>從 blog-module-article 搬到 blog-module-reading（T3）。
 * URL 路徑保留 {@code /api/v1/articles/{articleUuid}/like}，前端不破壞。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/articles/{articleUuid}/like")
@RequiredArgsConstructor
@Tag(name = "Article Like")
public class ArticleLikeController {

    private final ArticleLikeService likeService;
    private final ArticleFacade articleFacade;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "按讚文章（idempotent）")
    public ApiResponse<Void> like(@AuthenticationPrincipal Long userId,
                                    @PathVariable UUID articleUuid) {
        Long articleId = resolveArticleId(articleUuid);
        likeService.likeArticle(userId, articleId);
        return ApiResponse.success();
    }

    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "取消按讚（idempotent）")
    public ApiResponse<Void> unlike(@AuthenticationPrincipal Long userId,
                                      @PathVariable UUID articleUuid) {
        Long articleId = resolveArticleId(articleUuid);
        likeService.unlikeArticle(userId, articleId);
        return ApiResponse.success();
    }

    private Long resolveArticleId(UUID articleUuid) {
        Long articleId = articleFacade.findIdByUuid(articleUuid);
        if (articleId == null) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
        }
        return articleId;
    }
}
