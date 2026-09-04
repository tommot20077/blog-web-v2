package dowob.xyz.blog.module.article.controller;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.service.ArticleQueryService;
import dowob.xyz.blog.module.article.service.ArticleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

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

    /**
     * 下架已發布文章（PUBLISHED → ARCHIVED，僅 ADMIN）
     *
     * <p>
     * 把文章自所有公開讀取路徑撤下，並移除其 Elasticsearch 索引；資料列與留言、
     * 按讚、版本快照全部保留，可再以 {@link #unarchiveArticle} 復原。
     * 在此端點之前，已發布文章只剩硬刪（CASCADE、不可逆）一途。
     * </p>
     *
     * <p>
     * <b>放在 {@code /api/v1/admin/**} 之下是刻意的</b>：本操作僅 ADMIN 可執行，
     * 放在此前綴才同時具備 URL 層（{@code SecurityConfig} 的 {@code hasRole("ADMIN")}）
     * 與方法層（{@code @PreAuthorize}）雙重防護，符合 {@code ai-docs/security.md} 原則 1
     * 「兩層防護缺一不可」。順帶避開與公開端點 {@code GET /api/v1/articles/archive}
     * （唯讀的年度歸檔投影，名稱相近但語意無關）的混淆。
     * </p>
     *
     * <p>
     * 來源狀態非 PUBLISHED 一律回 A0204（狀態轉換不合法）；判斷交由
     * {@code ArticleCommandSubService.validateStatusTransition} 這個唯一守衛，不另設一套規則。
     * </p>
     *
     * @param uuid           文章公開 UUID
     * @param operatorId     當前登入用戶的資料庫主鍵
     * @param authentication 當前認證資訊（用於解析角色）
     * @return 下架後的文章完整資訊
     */
    @PreAuthorize("hasAuthority('SYSTEM_CONFIG')")
    @PostMapping("/{uuid}/archive")
    public ApiResponse<ArticleResponse> archiveArticle(@PathVariable UUID uuid,
                                                       @AuthenticationPrincipal Long operatorId,
                                                       Authentication authentication) {
        Role operatorRole = SecurityUtils.resolveRole(authentication);
        return ApiResponse.success(articleService.archiveArticle(operatorId, operatorRole, uuid));
    }

    /**
     * 復原已下架文章（ARCHIVED → DRAFT，僅 ADMIN）
     *
     * <p>
     * 復原後文章回到草稿狀態、可再次編輯；要重新公開必須重走
     * {@code POST /api/v1/articles/{uuid}/submit} 或 {@code /publish} 流程，
     * 刻意不直接回到 PUBLISHED，避免繞過既有的發布把關。
     * </p>
     *
     * <p>
     * 來源狀態非 ARCHIVED 一律回 A0204（狀態轉換不合法）。
     * </p>
     *
     * @param uuid           文章公開 UUID
     * @param operatorId     當前登入用戶的資料庫主鍵
     * @param authentication 當前認證資訊（用於解析角色）
     * @return 復原後的文章完整資訊
     */
    @PreAuthorize("hasAuthority('SYSTEM_CONFIG')")
    @PostMapping("/{uuid}/unarchive")
    public ApiResponse<ArticleResponse> unarchiveArticle(@PathVariable UUID uuid,
                                                         @AuthenticationPrincipal Long operatorId,
                                                         Authentication authentication) {
        Role operatorRole = SecurityUtils.resolveRole(authentication);
        return ApiResponse.success(articleService.unarchiveArticle(operatorId, operatorRole, uuid));
    }

}
