package dowob.xyz.blog.module.article.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.service.ArticleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文章管理員 REST Controller
 *
 * <p>
 * 提供管理員專用的文章管理端點，路由以 /api/admin/articles 為前綴。
 * SecurityConfig 已設定 /api/admin/** 僅允許 ADMIN 角色存取。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/admin/articles")
@RequiredArgsConstructor
public class AdminArticleController {

    /**
     * 文章服務
     */
    private final ArticleService articleService;

    /**
     * 分頁取得待審文章列表（僅 ADMIN）
     *
     * @param pageNum  頁碼，預設 1
     * @param pageSize 每頁筆數，預設 10
     * @return 分頁待審文章摘要列表
     */
    @GetMapping("/pending")
    public ApiResponse<PageResult<ArticleSummaryResponse>> getPendingArticles(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.success(articleService.getPendingArticles(pageNum, pageSize));
    }
}
