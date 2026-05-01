package dowob.xyz.blog.module.comment.controller;

import dowob.xyz.blog.common.api.response.ApiResponse;
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
@Tag(name = "Comment Like")
public class CommentLikeController {

    private final CommentLikeService likeService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "按讚留言（idempotent；軟刪除留言阻擋 C0104）")
    public ApiResponse<Void> like(@AuthenticationPrincipal Long userId, @PathVariable UUID uuid) {
        likeService.likeComment(uuid, userId);
        return ApiResponse.success();
    }

    @DeleteMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "取消按讚（idempotent）")
    public ApiResponse<Void> unlike(@AuthenticationPrincipal Long userId, @PathVariable UUID uuid) {
        likeService.unlikeComment(uuid, userId);
        return ApiResponse.success();
    }
}
