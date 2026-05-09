package dowob.xyz.blog.module.comment.service;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.comment.exception.CommentErrorCode;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.CommentWithAuthor;
import dowob.xyz.blog.module.comment.model.dto.request.CreateCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.request.EditCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.response.ArticleCommentListResponse;
import dowob.xyz.blog.common.api.dto.AuthorSummary;
import dowob.xyz.blog.module.comment.model.dto.response.CommentResponse;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 留言 Service。
 *
 * <p>處理留言的建立、編輯、刪除、查詢。
 * 跨模組計數更新走 ArticleFacade 介面，遵守模組邊界（不直接依賴 article 模組 service）。</p>
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
    private final ArticleFacade articleFacade;

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
        Long articleId = articleFacade.findIdByUuid(articleUuid);
        if (articleId == null) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
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

        articleFacade.incrementCommentCount(articleId);

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
        articleFacade.decrementCommentCount(c.getArticleId());
    }

    /**
     * 列出文章留言（分頁，含 reply 與軟刪除佔位）。
     *
     * @param articleUuid    文章 UUID
     * @param currentUserId  當前使用者 id；null 代表未登入
     * @param sort           排序：newest / oldest（預設 newest）
     * @param page           頁碼（1-based）
     * @param size           每頁筆數（top-level 計數）
     */
    public ArticleCommentListResponse listComments(UUID articleUuid, Long currentUserId,
                                                     String sort, int page, int size) {
        Long articleId = articleFacade.findIdByUuid(articleUuid);
        if (articleId == null) {
            return new ArticleCommentListResponse(
                    PageResult.of(page, size, 0L, Collections.emptyList()),
                    0
            );
        }

        String sortKey = "oldest".equals(sort) ? "oldest" : "newest";
        int offset = Math.max(0, (page - 1) * size);

        // 1. top-level 列表（含軟刪除佔位）
        List<CommentWithAuthor> topLevels = commentMapper.findTopLevelByArticle(
                articleId, sortKey, size, offset);
        List<Long> topLevelIds = topLevels.stream().map(CommentWithAuthor::getId).toList();

        // 2. replies 一次撈完（已過濾 deleted leaf at SQL level）
        List<CommentWithAuthor> replies = topLevelIds.isEmpty()
                ? List.of()
                : commentMapper.findRepliesByParentIds(topLevelIds);

        // 3. 當前使用者的 liked 集合（一次 batch；未登入跳過）
        List<Long> allCommentIds = java.util.stream.Stream.concat(
                topLevels.stream().map(CommentWithAuthor::getId),
                replies.stream().map(CommentWithAuthor::getId)
        ).toList();

        Set<Long> likedIds = (currentUserId == null || allCommentIds.isEmpty())
                ? Collections.emptySet()
                : new HashSet<>(commentMapper.findLikedCommentIdsByUser(currentUserId, allCommentIds));

        // 4. group replies by parent id
        Map<Long, List<CommentResponse>> repliesByParent = replies.stream()
                .collect(Collectors.groupingBy(
                        CommentWithAuthor::getParentId,
                        Collectors.mapping(r -> toResponse(r, likedIds), Collectors.toList())
                ));

        // 5. assemble top-level DTOs with attached replies
        List<CommentResponse> topLevelDtos = topLevels.stream()
                .map(t -> {
                    CommentResponse dto = toResponse(t, likedIds);
                    dto.setReplies(repliesByParent.getOrDefault(t.getId(), List.of()));
                    return dto;
                })
                .toList();

        int totalTopLevel = commentMapper.countTopLevelByArticle(articleId);
        int totalAll = commentMapper.countByArticle(articleId);

        PageResult<CommentResponse> pageResult = PageResult.of(page, size, (long) totalTopLevel, topLevelDtos);
        return new ArticleCommentListResponse(pageResult, totalAll);
    }

    /** 將 CommentWithAuthor row 映射成 CommentResponse；軟刪除留言以佔位形式呈現。 */
    private CommentResponse toResponse(CommentWithAuthor c, Set<Long> likedIds) {
        CommentResponse dto = new CommentResponse();
        dto.setUuid(c.getUuid());
        dto.setParentUuid(c.getParentUuid());

        boolean isDeleted = c.getDeletedAt() != null;
        dto.setDeleted(isDeleted);
        dto.setDeletedByRole(c.getDeletedByRole());

        if (isDeleted) {
            dto.setContent("");
            dto.setContentHtml("");
            dto.setAuthor(null);
            dto.setLiked(false);
        } else {
            dto.setContent(c.getContent());
            dto.setContentHtml(c.getContentHtml());
            AuthorSummary author = new AuthorSummary(
                    c.getAuthorUuid(), c.getAuthorNickname(), c.getAuthorAvatarUrl());
            dto.setAuthor(author);
            dto.setLiked(likedIds.contains(c.getId()));
        }

        dto.setLikeCount(c.getLikeCount());
        dto.setCreatedAt(c.getCreatedAt());
        dto.setEditedAt(c.getEditedAt());
        dto.setReplies(List.of());     // top-level 在外面 set 真正 replies；reply 永遠空陣列（2 層）
        return dto;
    }
}
