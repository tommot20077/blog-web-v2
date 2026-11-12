package dowob.xyz.blog.module.comment.controller;

import dowob.xyz.blog.infrastructure.ratelimit.RateLimited;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.module.comment.service.CommentLikeService;
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
 * 留言按讚 Controller。
 *
 * <p>POST/DELETE 皆 idempotent；軟刪除留言阻擋。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@RestController
@RequestMapping("/api/v1/comments/{uuid}/like")
@RequiredArgsConstructor
@Tag(name = "留言按讚")
public class CommentLikeController {

    private final CommentLikeService likeService;

    @PostMapping
    @RateLimited("interaction-write")
    @PreAuthorize("hasAuthority('COMMENT_WRITE')")
    @Operation(summary = "按讚留言",
            description = "需 COMMENT_WRITE 權限。重複按讚為冪等；軟刪除的留言不可按讚。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "C0101 留言不存在；A0201 文章不存在或對呼叫者不可讀；C0104 留言已刪除，無法操作")
    public ApiResponse<Void> like(@AuthenticationPrincipal AuthUser user, @PathVariable UUID uuid) {
        likeService.likeComment(uuid, user.uuid(), SecurityUtils.isAdmin());
        return ApiResponse.success();
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('COMMENT_WRITE')")
    @Operation(summary = "取消按讚留言",
            description = "需 COMMENT_WRITE 權限。未按讚時為冪等。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "C0101 留言不存在")
    public ApiResponse<Void> unlike(@AuthenticationPrincipal AuthUser user, @PathVariable UUID uuid) {
        likeService.unlikeComment(uuid, user.uuid());
        return ApiResponse.success();
    }
}
