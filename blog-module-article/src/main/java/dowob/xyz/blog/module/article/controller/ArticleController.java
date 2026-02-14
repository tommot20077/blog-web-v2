package dowob.xyz.blog.module.article.controller;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.RejectArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.service.ArticleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 文章 REST Controller
 *
 * <p>
 * 提供文章 CRUD 相關的 HTTP 端點。
 * 從 SecurityContextHolder 取得當前用戶 ID 與角色，傳入 Service。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/articles")
@RequiredArgsConstructor
public class ArticleController {

    /**
     * 文章服務
     */
    private final ArticleService articleService;

    /**
     * 分頁取得已發布文章列表（公開）
     *
     * @param pageNum  頁碼，預設 1
     * @param pageSize 每頁筆數，預設 10
     * @return 分頁文章摘要列表
     */
    @GetMapping
    public ApiResponse<PageResult<ArticleSummaryResponse>> getPublishedArticles(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.success(articleService.getPublishedArticles(pageNum, pageSize));
    }

    /**
     * 取得單篇文章詳情
     *
     * @param uuid 文章公開 UUID
     * @return 文章完整資訊
     */
    @GetMapping("/{uuid}")
    public ApiResponse<ArticleResponse> getArticle(@PathVariable UUID uuid) {
        Long viewerId = getCurrentUserId();
        Role viewerRole = getCurrentUserRole();
        return ApiResponse.success(articleService.getArticleByUuid(uuid, viewerId, viewerRole));
    }

    /**
     * 建立文章（需 AUTHOR 或 ADMIN 角色）
     *
     * @param request 建立文章請求
     * @return 建立後的文章完整資訊
     */
    @PreAuthorize("hasAuthority('ARTICLE_CREATE')")
    @PostMapping
    public ApiResponse<ArticleResponse> createArticle(@Valid @RequestBody CreateArticleRequest request) {
        Long authorId = getCurrentUserId();
        return ApiResponse.success(articleService.createArticle(authorId, request));
    }

    /**
     * 更新文章（需 AUTHOR 本人或 ADMIN）
     *
     * @param uuid    文章公開 UUID
     * @param request 更新請求
     * @return 更新後的文章完整資訊
     */
    @PreAuthorize("hasAuthority('ARTICLE_EDIT')")
    @PutMapping("/{uuid}")
    public ApiResponse<ArticleResponse> updateArticle(
            @PathVariable UUID uuid,
            @Valid @RequestBody UpdateArticleRequest request) {
        Long operatorId = getCurrentUserId();
        Role operatorRole = getCurrentUserRole();
        return ApiResponse.success(articleService.updateArticle(operatorId, operatorRole, uuid, request));
    }

    /**
     * 刪除文章（需 AUTHOR 本人或 ADMIN）
     *
     * @param uuid 文章公開 UUID
     * @return 成功回應
     */
    @PreAuthorize("hasAuthority('ARTICLE_DELETE')")
    @DeleteMapping("/{uuid}")
    public ApiResponse<Void> deleteArticle(@PathVariable UUID uuid) {
        Long operatorId = getCurrentUserId();
        Role operatorRole = getCurrentUserRole();
        articleService.deleteArticle(operatorId, operatorRole, uuid);
        return ApiResponse.success();
    }

    /**
     * 取得我的文章列表（需登入）
     *
     * @param pageNum  頁碼，預設 1
     * @param pageSize 每頁筆數，預設 10
     * @return 分頁文章摘要列表
     */
    @GetMapping("/me")
    public ApiResponse<PageResult<ArticleSummaryResponse>> getMyArticles(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Long authorId = getCurrentUserId();
        return ApiResponse.success(articleService.getMyArticles(authorId, pageNum, pageSize));
    }

    /**
     * 發布文章（需 AUTHOR 本人或 ADMIN）
     *
     * @param uuid 文章公開 UUID
     * @return 發布後的文章完整資訊
     */
    @PreAuthorize("hasAuthority('ARTICLE_EDIT')")
    @PostMapping("/{uuid}/publish")
    public ApiResponse<ArticleResponse> publishArticle(@PathVariable UUID uuid) {
        Long operatorId = getCurrentUserId();
        Role operatorRole = getCurrentUserRole();
        return ApiResponse.success(articleService.publishArticle(operatorId, operatorRole, uuid));
    }

    /**
     * 駁回文章（僅 ADMIN）
     *
     * @param uuid    文章公開 UUID
     * @param request 駁回請求（含原因）
     * @return 駁回後的文章完整資訊
     */
    @PostMapping("/{uuid}/reject")
    public ApiResponse<ArticleResponse> rejectArticle(
            @PathVariable UUID uuid,
            @RequestBody RejectArticleRequest request) {
        Long operatorId = getCurrentUserId();
        Role operatorRole = getCurrentUserRole();
        return ApiResponse.success(articleService.rejectArticle(operatorId, operatorRole, uuid, request.getReason()));
    }


    /**
     * 從 SecurityContextHolder 取得當前用戶 ID
     *
     * @return 用戶 ID，未登入則回傳 null
     */
    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() instanceof String) {
            return null;
        }
        return (Long) auth.getPrincipal();
    }

    /**
     * 從 SecurityContextHolder 取得當前用戶角色
     *
     * @return 用戶角色，未登入則回傳 null
     */
    private Role getCurrentUserRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(roleStr -> {
                    try {
                        return Role.fromSpringSecurityRole(roleStr);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(r -> r != null)
                .findFirst()
                .orElse(null);
    }
}
