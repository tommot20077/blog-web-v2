package dowob.xyz.blog.module.article.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.service.ArticleQueryService;
import dowob.xyz.blog.module.article.service.ArticleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文章管理員 REST Controller
 *
 * <p>
 * 提供管理員專用的文章管理端點，路由以 /api/v1/admin/articles 為前綴。
 * SecurityConfig 已設定 /api/v1/admin/** 僅允許 ADMIN 角色存取。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/admin/articles")
@RequiredArgsConstructor
public class AdminArticleController {

    /**
     * 文章服務（Write）
     */
    private final ArticleService articleService;

    /**
     * 文章查詢服務（Read，CQRS 分層）
     */
    private final ArticleQueryService articleQueryService;

    /**
     * 分頁取得待審文章列表（僅 ADMIN）
     *
     * @param page 頁碼，預設 1
     * @param size 每頁筆數，預設 10
     * @return 分頁待審文章摘要列表
     */
    @PreAuthorize("hasAuthority('SYSTEM_CONFIG')")
    @GetMapping("/pending")
    public ApiResponse<PageResult<ArticleSummaryResponse>> getPendingArticles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(articleQueryService.getPendingArticles(page, size));
    }

}
