package dowob.xyz.blog.module.article.controller;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.RejectArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.response.ArticleArchiveResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleResponse;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
import dowob.xyz.blog.module.article.model.dto.response.EditorArticleResponse;
import dowob.xyz.blog.module.article.service.ArticleQueryService;
import dowob.xyz.blog.module.article.service.ArticleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
     * 文章服務（Write）
     */
    private final ArticleService articleService;

    /**
     * 文章查詢服務（Read，CQRS 分層）
     */
    private final ArticleQueryService articleQueryService;

    /**
     * 分頁取得已發布文章列表（公開）
     *
     * <p>
     * 支援以 categorySlug 篩選特定分類下的文章。
     * categorySlug 為 null 或空白時，回傳所有已發布文章。
     * </p>
     *
     * @param page         頁碼，預設 1
     * @param size         每頁筆數，預設 10
     * @param categorySlug 分類 slug（可選）
     * @return 分頁文章摘要列表
     */
    @GetMapping
    public ApiResponse<PageResult<ArticleSummaryResponse>> getPublishedArticles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String categorySlug) {
        if (categorySlug != null && !categorySlug.isBlank()) {
            return ApiResponse.success(articleQueryService.getPublishedArticlesByCategorySlug(categorySlug, page, size));
        }
        return ApiResponse.success(articleQueryService.getPublishedArticles(page, size));
    }

    /**
     * 取得全部已發布文章的歸檔精簡投影（公開）
     *
     * <p>
     * 供前端「年度歸檔」頁面使用，回傳全部已發布文章的精簡投影
     * （uuid / title / slug / publishedAt / tags），依 publishedAt 由新到舊排序，
     * 無已發布文章時回傳空清單。此端點為公開，與 {@link #getPublishedArticles} 同等不需登入。
     * </p>
     *
     * @return 歸檔文章投影列表（依 publishedAt 由新到舊排序）
     */
    @GetMapping("/archive")
    public ApiResponse<List<ArticleArchiveResponse>> getArchive() {
        return ApiResponse.success(articleQueryService.getArchive());
    }

    /**
     * 根據 slug 取得單篇文章詳情（公開）
     *
     * @param slug           文章 URL slug
     * @param request        HTTP 請求（用於取得客戶端 IP）
     * @param viewerId       當前登入用戶的資料庫主鍵（匿名為 null）
     * @param authentication 當前認證資訊（用於解析角色）
     * @return 文章完整資訊
     */
    @GetMapping("/slug/{slug}")
    public ApiResponse<ArticleResponse> getArticleBySlug(@PathVariable String slug,
                                                          HttpServletRequest request,
                                                          @AuthenticationPrincipal Long viewerId,
                                                          Authentication authentication) {
        Role viewerRole = SecurityUtils.resolveRole(authentication);
        return ApiResponse.success(articleQueryService.getArticleBySlug(slug, viewerId, viewerRole, getClientIp(request)));
    }

    /**
     * 取得單篇文章詳情
     *
     * <p>
     * <b>匿名可存取</b>——本端點依 {@code security.md} 原則 7 的「選填認證公開端點」豁免，
     * 故不標註 {@code @PreAuthorize}。三項豁免條件均成立：
     * </p>
     * <ol>
     *   <li>路徑 {@code GET /api/v1/articles/{uuid}} 於 {@code SecurityConfig} 以明確清單 {@code permitAll}
     *       （UUID 形狀比對，非萬用字元；見 {@code SecurityConfig.PUBLIC_ARTICLE_DETAIL}）；</li>
     *   <li>{@code viewerId} 允許為 null，僅用於選填個人化與未發布文章的作者可見性判斷；</li>
     *   <li>本 JavaDoc 即為所需的豁免標註。</li>
     * </ol>
     *
     * @param uuid           文章公開 UUID
     * @param request        HTTP 請求（用於取得客戶端 IP）
     * @param viewerId       當前登入用戶的資料庫主鍵（匿名為 null）
     * @param authentication 當前認證資訊（用於解析角色）
     * @return 文章完整資訊
     */
    @GetMapping("/{uuid}")
    public ApiResponse<ArticleResponse> getArticle(@PathVariable UUID uuid, HttpServletRequest request,
                                                    @AuthenticationPrincipal Long viewerId,
                                                    Authentication authentication) {
        Role viewerRole = SecurityUtils.resolveRole(authentication);
        return ApiResponse.success(articleQueryService.getArticleByUuid(uuid, viewerId, viewerRole, getClientIp(request)));
    }

    /**
     * 建立文章（需 AUTHOR 或 ADMIN 角色）
     *
     * @param request  建立文章請求
     * @param authorId 當前登入用戶的資料庫主鍵（作者 ID）
     * @return 建立後的文章完整資訊
     */
    @PreAuthorize("hasAuthority('ARTICLE_CREATE')")
    @PostMapping
    public ApiResponse<EditorArticleResponse> createArticle(@Valid @RequestBody CreateArticleRequest request,
                                                             @AuthenticationPrincipal Long authorId) {
        return ApiResponse.success(articleService.createArticle(authorId, request));
    }

    /**
     * 更新文章（需 AUTHOR 本人或 ADMIN）
     *
     * @param uuid           文章公開 UUID
     * @param request        更新請求
     * @param operatorId     當前登入用戶的資料庫主鍵
     * @param authentication 當前認證資訊（用於解析角色）
     * @return 更新後的文章完整資訊
     */
    @PreAuthorize("hasAuthority('ARTICLE_EDIT')")
    @PutMapping("/{uuid}")
    public ApiResponse<EditorArticleResponse> updateArticle(
            @PathVariable UUID uuid,
            @Valid @RequestBody UpdateArticleRequest request,
            @AuthenticationPrincipal Long operatorId,
            Authentication authentication) {
        Role operatorRole = SecurityUtils.resolveRole(authentication);
        return ApiResponse.success(articleService.updateArticle(operatorId, operatorRole, uuid, request));
    }

    /**
     * 取得文章供 Editor 編輯（需認證，僅作者本人）
     *
     * @param uuid        文章公開 UUID
     * @param requesterId 當前登入用戶的資料庫主鍵
     * @return Editor 用文章資訊
     */
    @PreAuthorize("hasAuthority('ARTICLE_EDIT')")
    @GetMapping("/{uuid}/edit")
    public ApiResponse<EditorArticleResponse> getArticleForEdit(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal Long requesterId) {
        return ApiResponse.success(articleService.getArticleForEdit(uuid, requesterId));
    }

    /**
     * 刪除文章（需 AUTHOR 本人或 ADMIN）
     *
     * @param uuid           文章公開 UUID
     * @param operatorId     當前登入用戶的資料庫主鍵
     * @param authentication 當前認證資訊（用於解析角色）
     * @return 成功回應
     */
    @PreAuthorize("hasAuthority('ARTICLE_DELETE')")
    @DeleteMapping("/{uuid}")
    public ApiResponse<Void> deleteArticle(@PathVariable UUID uuid,
                                           @AuthenticationPrincipal Long operatorId,
                                           Authentication authentication) {
        Role operatorRole = SecurityUtils.resolveRole(authentication);
        articleService.deleteArticle(operatorId, operatorRole, uuid);
        return ApiResponse.success();
    }

    /**
     * 取得我的文章列表（需登入）
     *
     * @param page     頁碼，預設 1
     * @param size     每頁筆數，預設 10
     * @param status   文章狀態篩選（可選）
     * @param authorId 當前登入用戶的資料庫主鍵（作者 ID）
     * @return 分頁文章摘要列表
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    public ApiResponse<PageResult<ArticleSummaryResponse>> getMyArticles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) ArticleStatus status,
            @AuthenticationPrincipal Long authorId) {
        return ApiResponse.success(articleQueryService.getMyArticles(authorId, page, size, status));
    }

    /**
     * 提交文章審核（需 AUTHOR 本人或 ADMIN）
     *
     * @param uuid           文章公開 UUID
     * @param operatorId     當前登入用戶的資料庫主鍵
     * @param authentication 當前認證資訊（用於解析角色）
     * @return 提交審核後的文章完整資訊
     */
    @PreAuthorize("hasAuthority('ARTICLE_EDIT')")
    @PostMapping("/{uuid}/submit")
    public ApiResponse<ArticleResponse> submitForReview(@PathVariable UUID uuid,
                                                         @AuthenticationPrincipal Long operatorId,
                                                         Authentication authentication) {
        Role operatorRole = SecurityUtils.resolveRole(authentication);
        return ApiResponse.success(articleService.submitForReview(operatorId, operatorRole, uuid));
    }

    /**
     * 抽回送審文章（僅限文章作者本人）
     *
     * <p>
     * 將 PENDING_REVIEW 的文章退回 DRAFT，供作者在審核完成前反悔並繼續編輯。
     * 僅 PENDING_REVIEW 狀態可抽回，其餘狀態回傳 A0204（狀態轉換不合法）。
     * </p>
     *
     * <p>
     * 權限嚴於其他寫入端點：<b>僅作者本人可抽回，ADMIN 抽回他人文章會得到 A0203</b>。
     * 抽回與駁回為職責分離的兩個動作，ADMIN 審核不通過應改呼叫 {@code /reject}。
     * </p>
     *
     * @param uuid           文章公開 UUID
     * @param operatorId     當前登入用戶的資料庫主鍵
     * @param authentication 當前認證資訊（用於解析角色）
     * @return 抽回後的文章完整資訊
     */
    @PreAuthorize("hasAuthority('ARTICLE_EDIT')")
    @PostMapping("/{uuid}/withdraw")
    public ApiResponse<ArticleResponse> withdrawArticle(@PathVariable UUID uuid,
                                                        @AuthenticationPrincipal Long operatorId,
                                                        Authentication authentication) {
        Role operatorRole = SecurityUtils.resolveRole(authentication);
        return ApiResponse.success(articleService.withdrawArticle(operatorId, operatorRole, uuid));
    }

    /**
     * 發布文章（需 AUTHOR 本人或 ADMIN）
     *
     * @param uuid           文章公開 UUID
     * @param operatorId     當前登入用戶的資料庫主鍵
     * @param authentication 當前認證資訊（用於解析角色）
     * @return 發布後的文章完整資訊
     */
    @PreAuthorize("hasAuthority('ARTICLE_EDIT')")
    @PostMapping("/{uuid}/publish")
    public ApiResponse<ArticleResponse> publishArticle(@PathVariable UUID uuid,
                                                       @AuthenticationPrincipal Long operatorId,
                                                       Authentication authentication) {
        Role operatorRole = SecurityUtils.resolveRole(authentication);
        return ApiResponse.success(articleService.publishArticle(operatorId, operatorRole, uuid));
    }

    /**
     * 駁回文章（僅 ADMIN）
     *
     * @param uuid           文章公開 UUID
     * @param request        駁回請求（含原因）
     * @param operatorId     當前登入用戶的資料庫主鍵
     * @param authentication 當前認證資訊（用於解析角色）
     * @return 駁回後的文章完整資訊
     */
    @PreAuthorize("hasAuthority('SYSTEM_CONFIG')")
    @PostMapping("/{uuid}/reject")
    public ApiResponse<ArticleResponse> rejectArticle(
            @PathVariable UUID uuid,
            @RequestBody RejectArticleRequest request,
            @AuthenticationPrincipal Long operatorId,
            Authentication authentication) {
        Role operatorRole = SecurityUtils.resolveRole(authentication);
        return ApiResponse.success(articleService.rejectArticle(operatorId, operatorRole, uuid, request.getReason()));
    }


    /**
     * 取得客戶端真實 IP
     *
     * <p>
     * 搭配 {@code server.forward-headers-strategy=framework} 設定，
     * Spring 框架會自動解析 X-Forwarded-For 並更新 RemoteAddr，
     * 此處直接使用 RemoteAddr 可避免手動解析 header 被偽造的安全風險。
     * </p>
     *
     * @param request HTTP 請求
     * @return 客戶端 IP 字串
     */
    private String getClientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

}
