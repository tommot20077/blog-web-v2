package dowob.xyz.blog.module.comment.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.comment.exception.CommentErrorCode;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.dto.request.CreateCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.request.EditCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.response.CommentResponse;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 留言 Service。
 *
 * <p>處理留言的建立、編輯、刪除、查詢。
 * 跨模組計數更新走 ArticleService 介面，遵守中庸級別模組邊界。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    private static final Duration EDIT_WINDOW = Duration.ofMinutes(5);

    private final CommentRepository commentRepo;
    private final CommentMapper commentMapper;
    private final CommentMarkdownRenderer renderer;
    private final ArticleService articleService;

    /**
     * 建立留言（top-level 或 reply）。
     *
     * @param articleUuid 目標文章 UUID
     * @param userId      留言者 user id
     * @param req         留言請求（含 content + 可選 parentUuid）
     * @return 建立後的 CommentResponse（簡化版，列表 / 詳情走 listComments）
     */
    @Transactional
    public CommentResponse createComment(UUID articleUuid, Long userId, CreateCommentRequest req) {
        Long articleId = articleService.findIdByUuid(articleUuid);
        if (articleId == null) {
            throw new BusinessException(CommentErrorCode.PARENT_NOT_IN_ARTICLE);
        }

        Long parentId = null;
        if (req.getParentUuid() != null) {
            Comment parent = commentRepo.findByUuid(req.getParentUuid())
                    .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));

            if (!parent.getArticleId().equals(articleId)) {
                throw new BusinessException(CommentErrorCode.PARENT_NOT_IN_ARTICLE);
            }
            if (parent.getParentId() != null) {
                throw new BusinessException(CommentErrorCode.NESTING_TOO_DEEP);
            }
            if (parent.getDeletedAt() != null) {
                throw new BusinessException(CommentErrorCode.PARENT_FROZEN);
            }
            parentId = parent.getId();
        }

        String contentHtml = renderer.render(req.getContent());

        Comment c = new Comment();
        c.setUuid(UUID.randomUUID());
        c.setArticleId(articleId);
        c.setParentId(parentId);
        c.setUserId(userId);
        c.setContent(req.getContent());
        c.setContentHtml(contentHtml);
        c.setLikeCount(0);
        Comment saved = commentRepo.save(c);

        articleService.incrementCommentCount(articleId);

        // 簡化 response — 列表查詢有完整版（含 author / liked）
        CommentResponse resp = new CommentResponse();
        resp.setUuid(saved.getUuid());
        resp.setContent(saved.getContent());
        resp.setContentHtml(saved.getContentHtml());
        resp.setLikeCount(0);
        resp.setLiked(false);
        resp.setDeleted(false);
        resp.setCreatedAt(saved.getCreatedAt());
        return resp;
    }

    /**
     * 編輯留言內容，受 5 分鐘時限保護。
     *
     * <p>規則：
     * <ul>
     *   <li>軟刪除留言無法編輯（{@link CommentErrorCode#COMMENT_DELETED}）</li>
     *   <li>非作者且非 Admin 拋 {@link AccessDeniedException}</li>
     *   <li>作者超過 5 分鐘拋 {@link CommentErrorCode#EDIT_WINDOW_EXPIRED}</li>
     *   <li>Admin 不受時限限制</li>
     * </ul>
     *
     * @param commentUuid   目標留言 UUID
     * @param currentUserId 操作者 user id
     * @param isAdmin       是否具備 Admin 權限
     * @param req           編輯請求（含新內容）
     * @return 更新後的 CommentResponse
     */
    @Transactional
    public CommentResponse editComment(UUID commentUuid, Long currentUserId, boolean isAdmin,
                                       EditCommentRequest req) {
        Comment c = commentRepo.findByUuid(commentUuid)
                .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));

        if (c.getDeletedAt() != null) {
            throw new BusinessException(CommentErrorCode.COMMENT_DELETED);
        }
        if (!isAdmin && !c.getUserId().equals(currentUserId)) {
            throw new AccessDeniedException("不是留言作者");
        }
        if (!isAdmin) {
            Duration sinceCreate = Duration.between(c.getCreatedAt(), LocalDateTime.now());
            if (sinceCreate.compareTo(EDIT_WINDOW) > 0) {
                throw new BusinessException(CommentErrorCode.EDIT_WINDOW_EXPIRED);
            }
        }

        String contentHtml = renderer.render(req.getContent());
        c.setContent(req.getContent());
        c.setContentHtml(contentHtml);
        c.setEditedAt(LocalDateTime.now());
        commentRepo.save(c);

        CommentResponse resp = new CommentResponse();
        resp.setUuid(c.getUuid());
        resp.setContent(c.getContent());
        resp.setContentHtml(c.getContentHtml());
        resp.setLikeCount(c.getLikeCount());
        resp.setLiked(false);
        resp.setDeleted(false);
        resp.setCreatedAt(c.getCreatedAt());
        resp.setEditedAt(c.getEditedAt());
        return resp;
    }

    /**
     * 軟刪除留言。
     *
     * <p>記錄刪除者角色（AUTHOR / ADMIN）；已刪除留言再次刪除是 idempotent。</p>
     *
     * @param commentUuid    目標留言 UUID
     * @param currentUserId  當前操作者 user id
     * @param isAdmin        是否為 Admin（決定是否可以刪他人留言）
     */
    @Transactional
    public void deleteComment(UUID commentUuid, Long currentUserId, boolean isAdmin) {
        Comment c = commentRepo.findByUuid(commentUuid)
                .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));

        if (c.getDeletedAt() != null) {
            return;     // idempotent：已刪除直接 return
        }
        if (!isAdmin && !c.getUserId().equals(currentUserId)) {
            throw new AccessDeniedException("不是留言作者");
        }

        // role 判斷：admin 操作他人留言 → ADMIN；其他情況（含 admin 操作自己留言）→ AUTHOR
        String role = (isAdmin && !c.getUserId().equals(currentUserId)) ? "ADMIN" : "AUTHOR";
        commentMapper.softDelete(c.getId(), role);
        articleService.decrementCommentCount(c.getArticleId());
    }
}
