package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.common.util.SecurityUtils;
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
    public ApiResponse<Void> like(@AuthenticationPrincipal AuthUser user,
                                    @PathVariable UUID articleUuid) {
        requireReadable(articleUuid, user);
        likeService.likeArticle(user.uuid(), articleUuid);
        return ApiResponse.success();
    }

    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "取消按讚（idempotent）")
    public ApiResponse<Void> unlike(@AuthenticationPrincipal AuthUser user,
                                      @PathVariable UUID articleUuid) {
        requireArticleExists(articleUuid);
        likeService.unlikeArticle(user.uuid(), articleUuid);
        return ApiResponse.success();
    }

    /**
     * 確認文章對操作者可讀（PUBLISHED，或作者本人，或 ADMIN），不可讀時以 {@code A0201} 回應（HTTP 400）。
     *
     * <p>「可讀才可互動」（SEC-16）：不可讀的回應與文章不存在完全相同，不形成草稿存在性 oracle，
     * 也不會預先污染 like_count。僅用於建立類（按讚）；取消按讚屬移除類，改用 {@link #requireArticleExists}。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @param user        當前登入者
     * @throws BusinessException {@code ARTICLE_NOT_FOUND} 若文章不存在或不可讀
     */
    private void requireReadable(UUID articleUuid, AuthUser user) {
        if (!articleFacade.isReadableBy(articleUuid, user.uuid(), SecurityUtils.isAdmin())) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
        }
    }

    /**
     * 確認文章存在（不分狀態），不存在時以 {@code A0201} 回應（HTTP 400，與解耦前相同）。
     *
     * <p>用於移除類（取消按讚）：已下架的文章仍允許使用者清理自己的資料。
     * 必須在寫入前呼叫：不存在的文章不得落任何列、也不得動計數。
     * 與寫入之間若文章剛好被刪除，殘留的列由 {@code ReadingArticleDeletedConsumer} 與每日校正排程清除。</p>
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
