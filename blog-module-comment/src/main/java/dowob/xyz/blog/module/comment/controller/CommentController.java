package dowob.xyz.blog.module.comment.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.module.comment.model.dto.request.CreateCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.request.EditCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.response.ArticleCommentListResponse;
import dowob.xyz.blog.module.comment.model.dto.response.CommentResponse;
import dowob.xyz.blog.module.comment.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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

import java.util.UUID;

/**
 * 留言 Controller。
 *
 * <p>4 個端點：
 * <ul>
 *   <li>GET /articles/{uuid}/comments — 公開列表（含軟刪除佔位）</li>
 *   <li>POST /articles/{uuid}/comments — 登入：建立 top-level 或 reply</li>
 *   <li>PUT /comments/{uuid} — 登入 + 作者 / Admin：編輯（5 分鐘窗）</li>
 *   <li>DELETE /comments/{uuid} — 登入 + 作者 / Admin：軟刪除</li>
 * </ul>
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Comment")
public class CommentController {

    private final CommentService commentService;

    /**
     * 列出文章留言。
     *
     * <p>
     * <b>匿名可存取</b>——本端點依 {@code security.md} 原則 7 的「選填認證公開端點」豁免，
     * 故不標註 {@code @PreAuthorize}。三項豁免條件均成立：
     * </p>
     * <ol>
     *   <li>路徑 {@code /api/v1/articles/**} 於 {@code SecurityConfig} 明確 {@code permitAll}（GET）；</li>
     *   <li>{@code currentUserId} 允許為 null，僅用於選填個人化（如「已按讚」標記）；</li>
     *   <li>本 JavaDoc 即為所需的豁免標註。</li>
     * </ol>
     *
     * @param articleUuid   文章公開 UUID
     * @param page          頁碼（自 1 起）
     * @param size          每頁筆數
     * @param sort          排序方式（newest / oldest 等）
     * @param currentUserId 當前使用者 ID（未登入為 null，僅供個人化）
     * @return 留言列表（含軟刪除佔位）
     */
    @GetMapping("/articles/{articleUuid}/comments")
    @Operation(summary = "列出文章留言（匿名可存取，含軟刪除佔位）")
    public ApiResponse<ArticleCommentListResponse> list(
            @PathVariable UUID articleUuid,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "newest") String sort,
            @AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(commentService.listComments(articleUuid, currentUserId, sort, page, size));
    }

    @PostMapping("/articles/{articleUuid}/comments")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "建立留言（top-level 或 reply）")
    public ApiResponse<CommentResponse> create(
            @PathVariable UUID articleUuid,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateCommentRequest req) {
        return ApiResponse.success(commentService.createComment(articleUuid, userId, req));
    }

    @PutMapping("/comments/{uuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "編輯留言（5 分鐘窗，Admin 不受限）")
    public ApiResponse<CommentResponse> edit(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody EditCommentRequest req) {
        boolean isAdmin = SecurityUtils.isAdmin();
        return ApiResponse.success(commentService.editComment(uuid, userId, isAdmin, req));
    }

    @DeleteMapping("/comments/{uuid}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "軟刪除留言")
    public ApiResponse<Void> delete(
            @PathVariable UUID uuid,
            @AuthenticationPrincipal Long userId) {
        boolean isAdmin = SecurityUtils.isAdmin();
        commentService.deleteComment(uuid, userId, isAdmin);
        return ApiResponse.success();
    }
}
