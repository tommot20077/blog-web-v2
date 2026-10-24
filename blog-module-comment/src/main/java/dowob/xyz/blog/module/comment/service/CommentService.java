package dowob.xyz.blog.module.comment.service;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.response.PageResult;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.util.ArticleVisibility;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.comment.exception.CommentErrorCode;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.CommentRow;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 留言 Service。
 *
 * <p>處理留言的建立、編輯、刪除、查詢。
 * 跨模組一律以 UUID 識別，且只經由 facade 溝通（不直接依賴他模組的類別、不 JOIN 他模組的表）：
 * 文章存在性與留言計數走 {@link ArticleFacade}，列表的作者資訊走 {@link UserFacade}。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    private static final Duration EDIT_WINDOW = Duration.ofMinutes(5);

    private final CommentRepository commentRepo;
    private final CommentMapper commentMapper;
    private final CommentMarkdownRenderer renderer;
    private final ArticleFacade articleFacade;
    private final UserFacade userFacade;

    /**
     * 建立留言（top-level 或 reply）。
     *
     * <p>文章存在性以 {@link ArticleFacade#existsByUuid(UUID)}（不分狀態）檢查：{@code comments} 已沒有指向
     * {@code articles} 的 FK，這個檢查是「不對不存在的文章寫入留言」的唯一防線。
     * 父留言是否屬於同一篇文章，比對的是 {@code article_uuid}。</p>
     *
     * @param articleUuid 目標文章 UUID
     * @param userUuid    留言者 UUID
     * @param req         留言請求（含 content + 可選 parentUuid）
     * @return 建立後的 CommentResponse（簡化版，列表 / 詳情走 listComments）
     */
    @Transactional
    public CommentResponse createComment(UUID articleUuid, UUID userUuid, CreateCommentRequest req) {
        if (!articleFacade.existsByUuid(articleUuid)) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
        }

        Long parentId = null;
        if (req.getParentUuid() != null) {
            Comment parent = commentRepo.findByUuid(req.getParentUuid())
                    .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));

            if (!articleUuid.equals(parent.getArticleUuid())) {
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
        c.setArticleUuid(articleUuid);
        c.setParentId(parentId);
        c.setUserUuid(userUuid);
        c.setContent(req.getContent());
        c.setContentHtml(contentHtml);
        c.setLikeCount(0);
        Comment saved = commentRepo.save(c);

        articleFacade.incrementCommentCount(articleUuid);

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
     * @param commentUuid     目標留言 UUID
     * @param currentUserUuid 操作者 UUID
     * @param isAdmin         是否具備 Admin 權限
     * @param req             編輯請求（含新內容）
     * @return 更新後的 CommentResponse
     */
    @Transactional
    public CommentResponse editComment(UUID commentUuid, UUID currentUserUuid, boolean isAdmin,
                                       EditCommentRequest req) {
        Comment c = commentRepo.findByUuid(commentUuid)
                .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));

        if (c.getDeletedAt() != null) {
            throw new BusinessException(CommentErrorCode.COMMENT_DELETED);
        }
        if (!isAdmin && !c.getUserUuid().equals(currentUserUuid)) {
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
     * <p>記錄刪除者角色（AUTHOR / ADMIN）；已刪除留言再次刪除是 idempotent。
     * 併發雙刪時只有一個交易的 UPDATE 會命中，只有它會扣 {@code articles.comment_count}（RACE-09）。</p>
     *
     * @param commentUuid     目標留言 UUID
     * @param currentUserUuid 當前操作者 UUID
     * @param isAdmin         是否為 Admin（決定是否可以刪他人留言）
     */
    @Transactional
    public void deleteComment(UUID commentUuid, UUID currentUserUuid, boolean isAdmin) {
        Comment c = commentRepo.findByUuid(commentUuid)
                .orElseThrow(() -> new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));

        if (c.getDeletedAt() != null) {
            return;     // idempotent：已刪除直接 return
        }
        if (!isAdmin && !c.getUserUuid().equals(currentUserUuid)) {
            throw new AccessDeniedException("不是留言作者");
        }

        // role 判斷：admin 操作他人留言 → ADMIN；其他情況（含 admin 操作自己留言）→ AUTHOR
        String role = (isAdmin && !c.getUserUuid().equals(currentUserUuid)) ? "ADMIN" : "AUTHOR";
        // RACE-09：只有真正把這列從「未刪除」改成「已刪除」的交易才扣文章留言數（affected rows == 1）
        int affected = commentMapper.softDelete(c.getId(), role);
        if (affected == 1) {
            articleFacade.decrementCommentCount(c.getArticleUuid());
        }
    }

    /**
     * 列出文章留言（分頁，含 reply 與軟刪除佔位）。
     *
     * <p><b>可見性前置判斷</b>：留言隸屬於文章，文章不對此檢視者公開時（DRAFT / PENDING_REVIEW /
     * REJECTED / <b>ARCHIVED</b>），整串留言一律不得回傳——留言內容常引述文章原文，曝光等同內容外洩。
     * 判斷委派 {@link ArticleVisibility#isReadableBy(String, UUID, UUID, boolean)}（UUID 版），
     * 與文章詳情端點同一套政策：作者本人與 ADMIN 仍看得到。</p>
     *
     * <p>不可讀時回傳 <b>200 + 空清單</b>（與「文章不存在」同形狀），而非 404：一來維持本端點
     * 「匿名可讀、恆 200」的既有契約（{@code SecurityConfigTest.unauthenticatedListComments_shouldReturn200}），
     * 二來不讓回應差異成為「這個 UUID 是否存在」的探測器。</p>
     *
     * <p><b>作者資訊</b>：不再 JOIN 使用者表。一頁（top-level ＋ replies）內所有「未刪除」留言的
     * {@code userUuid} 合併去重後，只呼叫 {@link UserFacade#getDisplayInfoByUuids} 一次；
     * 查無該使用者時作者為 {@code AuthorSummary(null, null, null)}，軟刪除留言維持 {@code author = null}
     * （其作者不顯示，因此也不必查詢）。</p>
     *
     * @param articleUuid     文章 UUID
     * @param currentUserUuid 當前使用者 UUID；null 代表未登入
     * @param isAdmin         當前使用者是否為 ADMIN
     * @param sort            排序：newest / oldest（預設 newest）
     * @param page            頁碼（1-based）
     * @param size            每頁筆數（top-level 計數）
     */
    public ArticleCommentListResponse listComments(UUID articleUuid, UUID currentUserUuid, boolean isAdmin,
                                                     String sort, int page, int size) {
        ArticleData article = articleFacade.findByUuid(articleUuid).orElse(null);
        if (article == null
                || !ArticleVisibility.isReadableBy(article.status(), article.authorUuid(), currentUserUuid, isAdmin)) {
            return new ArticleCommentListResponse(
                    PageResult.of(page, size, 0L, Collections.emptyList()),
                    0
            );
        }

        String sortKey = "oldest".equals(sort) ? "oldest" : "newest";
        int offset = Math.max(0, (page - 1) * size);

        // 1. top-level 列表（含軟刪除佔位）
        List<CommentRow> topLevels = commentMapper.findTopLevelByArticle(
                articleUuid, sortKey, size, offset);
        List<Long> topLevelIds = topLevels.stream().map(CommentRow::getId).toList();

        // 2. replies 一次撈完（已過濾 deleted leaf at SQL level）
        List<CommentRow> replies =
                BatchedQuery.queryInBatches(topLevelIds, commentMapper::findRepliesByParentIds);

        // 3. 當前使用者的 liked 集合（一次 batch；未登入跳過）
        List<Long> allCommentIds = java.util.stream.Stream.concat(
                topLevels.stream().map(CommentRow::getId),
                replies.stream().map(CommentRow::getId)
        ).toList();

        Set<Long> likedIds = currentUserUuid == null
                ? Collections.emptySet()
                : new HashSet<>(BatchedQuery.queryInBatches(allCommentIds,
                        batch -> commentMapper.findLikedCommentIdsByUser(currentUserUuid, batch)));

        // 4. 作者資訊：整頁（top-level ＋ replies）合併去重後，只向 UserFacade 批次查詢一次
        Map<UUID, UserDisplayInfo> authors = resolveAuthors(topLevels, replies);

        // 5. group replies by parent id
        Map<Long, List<CommentResponse>> repliesByParent = replies.stream()
                .collect(Collectors.groupingBy(
                        CommentRow::getParentId,
                        Collectors.mapping(r -> toResponse(r, likedIds, authors), Collectors.toList())
                ));

        // 6. assemble top-level DTOs with attached replies
        List<CommentResponse> topLevelDtos = topLevels.stream()
                .map(t -> {
                    CommentResponse dto = toResponse(t, likedIds, authors);
                    dto.setReplies(repliesByParent.getOrDefault(t.getId(), List.of()));
                    return dto;
                })
                .toList();

        int totalTopLevel = commentMapper.countTopLevelByArticle(articleUuid);
        int totalAll = commentMapper.countByArticle(articleUuid);

        PageResult<CommentResponse> pageResult = PageResult.of(page, size, (long) totalTopLevel, topLevelDtos);
        return new ArticleCommentListResponse(pageResult, totalAll);
    }

    /**
     * 收集一頁留言中「會顯示作者」的 userUuid（未刪除者；去重），向 UserFacade 批次查詢一次。
     *
     * @param topLevels 本頁 top-level 留言
     * @param replies   本頁所有 replies
     * @return userUuid → 顯示資訊；沒有需要查詢的作者時回傳空 Map 且不呼叫 UserFacade
     */
    private Map<UUID, UserDisplayInfo> resolveAuthors(List<CommentRow> topLevels, List<CommentRow> replies) {
        Set<UUID> userUuids = java.util.stream.Stream.concat(topLevels.stream(), replies.stream())
                .filter(r -> r.getDeletedAt() == null)
                .map(CommentRow::getUserUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (userUuids.isEmpty()) {
            return Map.of();
        }
        return userFacade.getDisplayInfoByUuids(userUuids);
    }

    /** 將 CommentRow 映射成 CommentResponse；軟刪除留言以佔位形式呈現。 */
    private CommentResponse toResponse(CommentRow c, Set<Long> likedIds, Map<UUID, UserDisplayInfo> authors) {
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
            UserDisplayInfo info = c.getUserUuid() == null ? null : authors.get(c.getUserUuid());
            dto.setAuthor(info == null
                    ? new AuthorSummary(null, null, null)
                    : new AuthorSummary(info.uuid(), info.nickname(), info.avatarUrl()));
            dto.setLiked(likedIds.contains(c.getId()));
        }

        dto.setLikeCount(c.getLikeCount());
        dto.setCreatedAt(c.getCreatedAt());
        dto.setEditedAt(c.getEditedAt());
        dto.setReplies(List.of());     // top-level 在外面 set 真正 replies；reply 永遠空陣列（2 層）
        return dto;
    }
}
