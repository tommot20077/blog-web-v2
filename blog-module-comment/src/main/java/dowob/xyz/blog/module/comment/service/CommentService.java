package dowob.xyz.blog.module.comment.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.comment.exception.CommentErrorCode;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.dto.request.CreateCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.response.CommentResponse;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
