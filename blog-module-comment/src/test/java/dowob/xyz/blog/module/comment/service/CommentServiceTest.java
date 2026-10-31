package dowob.xyz.blog.module.comment.service;

import dowob.xyz.blog.common.api.dto.AuthorSummary;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.module.comment.exception.CommentErrorCode;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.CommentRow;
import dowob.xyz.blog.module.comment.model.dto.request.CreateCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.request.EditCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.response.ArticleCommentListResponse;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock private CommentRepository commentRepo;
    @Mock private CommentMapper commentMapper;
    @Mock private CommentMarkdownRenderer renderer;
    @Mock private ArticleFacade articleFacade;
    @Mock private UserFacade userFacade;
    @InjectMocks private CommentService service;

    private final UUID userUuid = UUID.randomUUID();
    private final UUID otherUserUuid = UUID.randomUUID();
    private final Long articleId = 100L;
    private final UUID articleUuid = UUID.randomUUID();
    private final UUID authorUuid = UUID.randomUUID();

    // ── createComment ─────────────────────────────────────────────────────

    @Test
    void createComment_topLevel_savesWithUuidAndDefaults() {
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);
        when(renderer.render("hello")).thenReturn("<p>hello</p>");
        when(commentRepo.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("hello");

        service.createComment(articleUuid, userUuid, req);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepo).save(captor.capture());
        Comment saved = captor.getValue();
        assertThat(saved.getUuid()).isNotNull();
        assertThat(saved.getArticleUuid()).isEqualTo(articleUuid);
        assertThat(saved.getParentId()).isNull();
        assertThat(saved.getUserUuid()).isEqualTo(userUuid);
        assertThat(saved.getContent()).isEqualTo("hello");
        assertThat(saved.getContentHtml()).isEqualTo("<p>hello</p>");
        assertThat(saved.getLikeCount()).isEqualTo(0);
        assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("建立留言以 article_uuid／user_uuid 識別，並以不分狀態的 existsByUuid 檢查文章存在")
    void createComment_identifiesArticleAndUserByUuid() {
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);
        when(renderer.render(any())).thenReturn("<p>x</p>");
        when(commentRepo.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("x");

        service.createComment(articleUuid, userUuid, req);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepo).save(captor.capture());
        assertThat(captor.getValue().getArticleUuid()).isEqualTo(articleUuid);
        assertThat(captor.getValue().getUserUuid()).isEqualTo(userUuid);
        verify(articleFacade).existsByUuid(articleUuid);
    }

    @Test
    void createComment_topLevel_incrementsArticleCommentCount() {
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);
        when(renderer.render(any())).thenReturn("<p>x</p>");
        when(commentRepo.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("x");

        service.createComment(articleUuid, userUuid, req);

        verify(articleFacade).incrementCommentCount(articleUuid);
    }

    @Test
    @DisplayName("文章不存在時回 ARTICLE_NOT_FOUND，不寫入任何留言、不動計數（FK 已移除，這是唯一防線）")
    void createComment_articleNotFound_throwsAndWritesNothing() {
        when(articleFacade.existsByUuid(articleUuid)).thenReturn(false);

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("x");

        assertThatThrownBy(() -> service.createComment(articleUuid, userUuid, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage());

        verify(commentRepo, never()).save(any());
        verify(articleFacade, never()).incrementCommentCount(any(UUID.class));
    }

    @Test
    void createComment_replyToTopLevel_succeeds() {
        UUID parentUuid = UUID.randomUUID();
        Comment parent = new Comment();
        parent.setId(50L);
        parent.setUuid(parentUuid);
        parent.setArticleUuid(articleUuid);
        parent.setParentId(null);  // top-level

        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);
        when(commentRepo.findByUuid(parentUuid)).thenReturn(Optional.of(parent));
        when(renderer.render(any())).thenReturn("<p>reply</p>");
        when(commentRepo.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            c.setId(99L);
            return c;
        });

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("reply");
        req.setParentUuid(parentUuid);

        service.createComment(articleUuid, userUuid, req);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepo).save(captor.capture());
        assertThat(captor.getValue().getParentId()).isEqualTo(50L);
    }

    @Test
    void createComment_replyToReply_throwsNestingTooDeep() {
        UUID parentUuid = UUID.randomUUID();
        Comment reply = new Comment();
        reply.setId(50L);
        reply.setUuid(parentUuid);
        reply.setArticleUuid(articleUuid);
        reply.setParentId(40L);   // 已是 reply

        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);
        when(commentRepo.findByUuid(parentUuid)).thenReturn(Optional.of(reply));

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("nested");
        req.setParentUuid(parentUuid);

        assertThatThrownBy(() -> service.createComment(articleUuid, userUuid, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.NESTING_TOO_DEEP.getMessage());
    }

    @Test
    @DisplayName("父留言屬於不同文章（比對 article_uuid）時回 PARENT_NOT_IN_ARTICLE")
    void createComment_parentNotInSameArticle_throws() {
        UUID parentUuid = UUID.randomUUID();
        Comment parent = new Comment();
        parent.setId(50L);
        parent.setUuid(parentUuid);
        parent.setArticleUuid(UUID.randomUUID());    // 不同文章
        parent.setParentId(null);

        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);
        when(commentRepo.findByUuid(parentUuid)).thenReturn(Optional.of(parent));

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("x");
        req.setParentUuid(parentUuid);

        assertThatThrownBy(() -> service.createComment(articleUuid, userUuid, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.PARENT_NOT_IN_ARTICLE.getMessage());
        verify(commentRepo, never()).save(any());
    }

    @Test
    void createComment_parentSoftDeleted_throwsParentFrozen() {
        UUID parentUuid = UUID.randomUUID();
        Comment parent = new Comment();
        parent.setId(50L);
        parent.setUuid(parentUuid);
        parent.setArticleUuid(articleUuid);
        parent.setParentId(null);
        parent.setDeletedAt(LocalDateTime.now());     // 軟刪除

        when(articleFacade.existsByUuid(articleUuid)).thenReturn(true);
        when(commentRepo.findByUuid(parentUuid)).thenReturn(Optional.of(parent));

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("x");
        req.setParentUuid(parentUuid);

        assertThatThrownBy(() -> service.createComment(articleUuid, userUuid, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.PARENT_FROZEN.getMessage());
    }

    // ── editComment ───────────────────────────────────────────────────────

    @Test
    void editComment_within5Min_updatesContentAndSetsEditedAt() {
        UUID commentUuid = UUID.randomUUID();
        Comment c = new Comment();
        c.setId(1L);
        c.setUuid(commentUuid);
        c.setUserUuid(userUuid);
        c.setContent("original");
        c.setContentHtml("<p>original</p>");
        c.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        c.setDeletedAt(null);

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(renderer.render("edited")).thenReturn("<p>edited</p>");
        when(commentRepo.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        EditCommentRequest req = new EditCommentRequest();
        req.setContent("edited");

        service.editComment(commentUuid, userUuid, false, req);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepo).save(captor.capture());
        Comment saved = captor.getValue();
        assertThat(saved.getContent()).isEqualTo("edited");
        assertThat(saved.getContentHtml()).isEqualTo("<p>edited</p>");
        assertThat(saved.getEditedAt()).isNotNull();
    }

    @Test
    void editComment_after5Min_throwsEditWindowExpired() {
        UUID commentUuid = UUID.randomUUID();
        Comment c = new Comment();
        c.setId(1L);
        c.setUuid(commentUuid);
        c.setUserUuid(userUuid);
        c.setCreatedAt(LocalDateTime.now().minusMinutes(10));

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

        EditCommentRequest req = new EditCommentRequest();
        req.setContent("late");

        assertThatThrownBy(() -> service.editComment(commentUuid, userUuid, false, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.EDIT_WINDOW_EXPIRED.getMessage());
    }

    @Test
    void editComment_byAdminAfter5Min_succeeds() {
        UUID commentUuid = UUID.randomUUID();
        Comment c = new Comment();
        c.setId(1L);
        c.setUuid(commentUuid);
        c.setUserUuid(otherUserUuid);     // 不是 admin 自己的留言
        c.setCreatedAt(LocalDateTime.now().minusMinutes(10));

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(renderer.render(any())).thenReturn("<p>x</p>");
        when(commentRepo.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        EditCommentRequest req = new EditCommentRequest();
        req.setContent("admin edit");

        service.editComment(commentUuid, UUID.randomUUID(), true, req);    // isAdmin=true

        verify(commentRepo).save(any());
    }

    @Test
    @DisplayName("編輯他人留言（比對 user_uuid）拋 AccessDeniedException")
    void editComment_byOtherUser_throwsAccessDenied() {
        UUID commentUuid = UUID.randomUUID();
        Comment c = new Comment();
        c.setId(1L);
        c.setUuid(commentUuid);
        c.setUserUuid(otherUserUuid);     // 別人留的
        c.setCreatedAt(LocalDateTime.now());

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

        EditCommentRequest req = new EditCommentRequest();
        req.setContent("hijack");

        assertThatThrownBy(() -> service.editComment(commentUuid, userUuid, false, req))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void editComment_softDeletedComment_throws() {
        UUID commentUuid = UUID.randomUUID();
        Comment c = new Comment();
        c.setId(1L);
        c.setUuid(commentUuid);
        c.setUserUuid(userUuid);
        c.setCreatedAt(LocalDateTime.now());
        c.setDeletedAt(LocalDateTime.now());

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

        EditCommentRequest req = new EditCommentRequest();
        req.setContent("ghost");

        assertThatThrownBy(() -> service.editComment(commentUuid, userUuid, false, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.COMMENT_DELETED.getMessage());
    }

    // ── deleteComment ─────────────────────────────────────────────────────

    @Test
    void deleteComment_byOwner_softDeletesAndMarksAuthor() {
        UUID commentUuid = UUID.randomUUID();
        Comment c = new Comment();
        c.setId(1L);
        c.setUuid(commentUuid);
        c.setUserUuid(userUuid);
        c.setArticleUuid(articleUuid);
        c.setDeletedAt(null);

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(commentMapper.softDelete(1L, "AUTHOR")).thenReturn(1);

        service.deleteComment(commentUuid, userUuid, false);

        verify(commentMapper).softDelete(1L, "AUTHOR");
        verify(articleFacade).decrementCommentCount(articleUuid);
    }

    @Test
    void deleteComment_byAdmin_softDeletesAndMarksAdmin() {
        UUID commentUuid = UUID.randomUUID();
        Comment c = new Comment();
        c.setId(1L);
        c.setUuid(commentUuid);
        c.setUserUuid(otherUserUuid);     // 別人留的
        c.setArticleUuid(articleUuid);
        c.setDeletedAt(null);

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(commentMapper.softDelete(1L, "ADMIN")).thenReturn(1);

        service.deleteComment(commentUuid, userUuid, true);    // isAdmin=true，且不是留言作者

        verify(commentMapper).softDelete(1L, "ADMIN");
        verify(articleFacade).decrementCommentCount(articleUuid);
    }

    @Test
    void deleteComment_byOther_throwsAccessDenied() {
        UUID commentUuid = UUID.randomUUID();
        Comment c = new Comment();
        c.setId(1L);
        c.setUuid(commentUuid);
        c.setUserUuid(otherUserUuid);
        c.setDeletedAt(null);

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.deleteComment(commentUuid, userUuid, false))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verify(commentMapper, never()).softDelete(any(), any());
    }

    @Test
    void deleteComment_alreadyDeleted_isIdempotent() {
        UUID commentUuid = UUID.randomUUID();
        Comment c = new Comment();
        c.setId(1L);
        c.setUuid(commentUuid);
        c.setUserUuid(userUuid);
        c.setDeletedAt(LocalDateTime.now());     // 已軟刪除

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

        service.deleteComment(commentUuid, userUuid, false);

        verify(commentMapper, never()).softDelete(any(), any());
        verify(articleFacade, never()).decrementCommentCount(any(UUID.class));
    }

    @Test
    @DisplayName("RACE-09：softDelete 影響 0 列（另一個交易已先刪除）時不得再扣文章留言數")
    void deleteComment_lostRace_doesNotDecrementArticleCommentCount() {
        UUID commentUuid = UUID.randomUUID();
        Comment c = new Comment();
        c.setId(1L);
        c.setUuid(commentUuid);
        c.setUserUuid(userUuid);
        c.setArticleUuid(articleUuid);
        c.setDeletedAt(null);     // 兩個交易都讀到「尚未刪除」

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(commentMapper.softDelete(1L, "AUTHOR")).thenReturn(0);     // 對方先一步 UPDATE 成功

        service.deleteComment(commentUuid, userUuid, false);

        verify(commentMapper).softDelete(1L, "AUTHOR");
        verify(articleFacade, never()).decrementCommentCount(any(UUID.class));
    }

    // ── listComments ──────────────────────────────────────────────────────

    @Test
    void listComments_topLevelNewestFirstByDefault() {
        stubPublishedArticle();
        when(commentMapper.findTopLevelByArticle(eq(articleUuid), eq("newest"), anyInt(), anyInt()))
                .thenReturn(List.of(makeRow(1L, null), makeRow(2L, null)));
        when(commentMapper.findRepliesByParentIds(any())).thenReturn(List.of(makeRow(11L, 1L)));
        when(commentMapper.countByArticle(articleUuid)).thenReturn(3);
        when(commentMapper.countTopLevelByArticle(articleUuid)).thenReturn(2);

        ArticleCommentListResponse resp = service.listComments(articleUuid, null, false, "newest", 1, 20);

        assertThat(resp.getTopLevels().getRecords()).hasSize(2);
        assertThat(resp.getTotalCommentCount()).isEqualTo(3);
    }

    @Test
    void listComments_softDeletedTopLevelShownAsTombstone() {
        CommentRow deletedTop = new CommentRow();
        deletedTop.setId(1L);
        deletedTop.setUuid(UUID.randomUUID());
        deletedTop.setContent("original");
        deletedTop.setContentHtml("<p>x</p>");
        deletedTop.setDeletedAt(LocalDateTime.now());
        deletedTop.setDeletedByRole("ADMIN");
        deletedTop.setUserUuid(UUID.randomUUID());
        deletedTop.setLikeCount(5);
        deletedTop.setCreatedAt(LocalDateTime.now());

        stubPublishedArticle();
        when(commentMapper.findTopLevelByArticle(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(deletedTop));
        when(commentMapper.findRepliesByParentIds(any())).thenReturn(List.of());
        when(commentMapper.countByArticle(any())).thenReturn(1);
        when(commentMapper.countTopLevelByArticle(any())).thenReturn(1);

        ArticleCommentListResponse resp = service.listComments(articleUuid, null, false, "newest", 1, 20);

        var first = resp.getTopLevels().getRecords().get(0);
        assertThat(first.getDeleted()).isTrue();
        assertThat(first.getContent()).isEmpty();
        assertThat(first.getContentHtml()).isEmpty();
        assertThat(first.getAuthor()).isNull();
        assertThat(first.getLikeCount()).isEqualTo(5);
        assertThat(first.getDeletedByRole()).isEqualTo("ADMIN");
    }

    @Test
    void listComments_includesLikedFlagForCurrentUser() {
        UUID viewerUuid = UUID.randomUUID();
        stubPublishedArticle();
        when(commentMapper.findTopLevelByArticle(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(makeRow(1L, null), makeRow(2L, null)));
        when(commentMapper.findRepliesByParentIds(any())).thenReturn(List.of());
        when(commentMapper.countByArticle(any())).thenReturn(2);
        when(commentMapper.countTopLevelByArticle(any())).thenReturn(2);
        when(commentMapper.findLikedCommentIdsByUser(eq(viewerUuid), any()))
                .thenReturn(List.of(1L));

        ArticleCommentListResponse resp = service.listComments(articleUuid, viewerUuid, false, "newest", 1, 20);

        assertThat(resp.getTopLevels().getRecords().get(0).getLiked()).isTrue();
        assertThat(resp.getTopLevels().getRecords().get(1).getLiked()).isFalse();
    }

    @Test
    void listComments_unauthenticated_likedFlagAlwaysFalse() {
        stubPublishedArticle();
        when(commentMapper.findTopLevelByArticle(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(makeRow(1L, null)));
        when(commentMapper.findRepliesByParentIds(any())).thenReturn(List.of());
        when(commentMapper.countByArticle(any())).thenReturn(1);
        when(commentMapper.countTopLevelByArticle(any())).thenReturn(1);

        ArticleCommentListResponse resp = service.listComments(articleUuid, null, false, "newest", 1, 20);

        assertThat(resp.getTopLevels().getRecords().get(0).getLiked()).isFalse();
        verify(commentMapper, never()).findLikedCommentIdsByUser(any(), any());
    }

    // ── listComments：作者資訊改由 UserFacade 批次解析（取代 JOIN users）────────

    @Test
    @DisplayName("一頁（top-level ＋ replies）的作者只呼叫 getDisplayInfoByUuids 一次，且為去重後的 user_uuid 集合")
    void listComments_resolvesAllAuthorsWithSingleBatchedFacadeCall() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        CommentRow top1 = makeRow(1L, null);
        top1.setUserUuid(alice);
        CommentRow top2 = makeRow(2L, null);
        top2.setUserUuid(bob);
        CommentRow reply = makeRow(11L, 1L);
        reply.setUserUuid(alice);     // 與 top1 同一人，應去重

        stubPublishedArticle();
        when(commentMapper.findTopLevelByArticle(any(), any(), anyInt(), anyInt())).thenReturn(List.of(top1, top2));
        when(commentMapper.findRepliesByParentIds(any())).thenReturn(List.of(reply));
        when(commentMapper.countByArticle(any())).thenReturn(3);
        when(commentMapper.countTopLevelByArticle(any())).thenReturn(2);
        when(userFacade.getDisplayInfoByUuids(any())).thenReturn(Map.of(
                alice, new UserDisplayInfo(alice, "Alice", "https://img/alice.png"),
                bob, new UserDisplayInfo(bob, "Bob", null)));

        ArticleCommentListResponse resp = service.listComments(articleUuid, null, false, "newest", 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(userFacade, times(1)).getDisplayInfoByUuids(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(alice, bob);

        var records = resp.getTopLevels().getRecords();
        assertThat(records.get(0).getAuthor())
                .isEqualTo(new AuthorSummary(alice, "Alice", "https://img/alice.png"));
        assertThat(records.get(1).getAuthor()).isEqualTo(new AuthorSummary(bob, "Bob", null));
        assertThat(records.get(0).getReplies().get(0).getAuthor())
                .isEqualTo(new AuthorSummary(alice, "Alice", "https://img/alice.png"));
    }

    @Test
    @DisplayName("查無該使用者時，作者為 AuthorSummary(null, null, null)（與先前 LEFT JOIN 無匹配相同）")
    void listComments_unknownAuthor_yieldsEmptyAuthorSummary() {
        stubPublishedArticle();
        stubOneTopLevelComment();
        when(userFacade.getDisplayInfoByUuids(any())).thenReturn(Map.of());

        ArticleCommentListResponse resp = service.listComments(articleUuid, null, false, "newest", 1, 20);

        assertThat(resp.getTopLevels().getRecords().get(0).getAuthor())
                .isEqualTo(new AuthorSummary(null, null, null));
    }

    @Test
    @DisplayName("軟刪除留言不顯示作者（author = null），其作者也不必向 UserFacade 查詢")
    void listComments_softDeletedComment_hasNoAuthorAndIsNotResolved() {
        CommentRow deletedTop = makeRow(1L, null);
        deletedTop.setDeletedAt(LocalDateTime.now());
        deletedTop.setDeletedByRole("AUTHOR");

        stubPublishedArticle();
        when(commentMapper.findTopLevelByArticle(any(), any(), anyInt(), anyInt())).thenReturn(List.of(deletedTop));
        when(commentMapper.findRepliesByParentIds(any())).thenReturn(List.of());
        when(commentMapper.countByArticle(any())).thenReturn(1);
        when(commentMapper.countTopLevelByArticle(any())).thenReturn(1);

        ArticleCommentListResponse resp = service.listComments(articleUuid, null, false, "newest", 1, 20);

        assertThat(resp.getTopLevels().getRecords().get(0).getAuthor()).isNull();
        verify(userFacade, never()).getDisplayInfoByUuids(any());
    }

    @Test
    @DisplayName("混合頁：頂層已軟刪除、底下回覆存活且作者不同人 → 只查回覆者一次；頂層為佔位，回覆帶回覆者資料")
    void listComments_deletedTopLevelWithLiveReplyByAnotherUser_resolvesOnlyTheReplier() {
        UUID topAuthor = UUID.randomUUID();
        UUID replier = UUID.randomUUID();
        CommentRow deletedTop = makeRow(1L, null);
        deletedTop.setUserUuid(topAuthor);
        deletedTop.setDeletedAt(LocalDateTime.now());
        deletedTop.setDeletedByRole("AUTHOR");
        CommentRow liveReply = makeRow(11L, 1L);
        liveReply.setUserUuid(replier);

        stubPublishedArticle();
        when(commentMapper.findTopLevelByArticle(any(), any(), anyInt(), anyInt())).thenReturn(List.of(deletedTop));
        when(commentMapper.findRepliesByParentIds(any())).thenReturn(List.of(liveReply));
        when(commentMapper.countByArticle(any())).thenReturn(2);
        when(commentMapper.countTopLevelByArticle(any())).thenReturn(1);
        when(userFacade.getDisplayInfoByUuids(any())).thenReturn(Map.of(
                replier, new UserDisplayInfo(replier, "Replier", "https://img/replier.png")));

        ArticleCommentListResponse resp = service.listComments(articleUuid, null, false, "newest", 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(userFacade, times(1)).getDisplayInfoByUuids(captor.capture());
        assertThat(captor.getValue()).containsExactly(replier);

        var top = resp.getTopLevels().getRecords().get(0);
        assertThat(top.getDeleted()).isTrue();
        assertThat(top.getContent()).isEmpty();
        assertThat(top.getAuthor()).isNull();
        var reply = top.getReplies().get(0);
        assertThat(reply.getAuthor()).isEqualTo(new AuthorSummary(replier, "Replier", "https://img/replier.png"));
    }

    // ── 測試輔助方法 ──────────────────────────────────────────────────────

    private CommentRow makeRow(Long id, Long parentId) {
        CommentRow row = new CommentRow();
        row.setId(id);
        row.setUuid(UUID.randomUUID());
        row.setArticleUuid(articleUuid);
        row.setParentId(parentId);
        row.setUserUuid(UUID.randomUUID());
        row.setContent("c" + id);
        row.setContentHtml("<p>c" + id + "</p>");
        row.setLikeCount(0);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    // ── 文章可見性（下架／未發布文章的留言不得對外曝光）────────────────────

    @Test
    @DisplayName("文章已下架時，匿名取得空清單且不查詢任何留言")
    void listComments_archivedArticle_anonymous_returnsEmptyAndSkipsCommentQueries() {
        stubArticle("ARCHIVED", authorUuid);

        ArticleCommentListResponse resp = service.listComments(articleUuid, null, false, "newest", 1, 20);

        assertThat(resp.getTopLevels().getRecords()).isEmpty();
        assertThat(resp.getTopLevels().getTotal()).isZero();
        assertThat(resp.getTotalCommentCount()).isZero();
        verify(commentMapper, never())
                .findTopLevelByArticle(any(), any(), anyInt(), anyInt());
        verify(userFacade, never()).getDisplayInfoByUuids(any());
    }

    @Test
    @DisplayName("文章已下架時，非作者的登入者同樣取得空清單")
    void listComments_archivedArticle_otherUser_returnsEmpty() {
        stubArticle("ARCHIVED", authorUuid);

        ArticleCommentListResponse resp = service.listComments(articleUuid, otherUserUuid, false, "newest", 1, 20);

        assertThat(resp.getTopLevels().getRecords()).isEmpty();
    }

    @Test
    @DisplayName("文章已下架時，作者本人（以 UUID 比對）仍取得留言")
    void listComments_archivedArticle_author_returnsComments() {
        stubArticle("ARCHIVED", authorUuid);
        stubOneTopLevelComment();

        ArticleCommentListResponse resp = service.listComments(articleUuid, authorUuid, false, "newest", 1, 20);

        assertThat(resp.getTopLevels().getRecords()).hasSize(1);
    }

    @Test
    @DisplayName("文章已下架時，ADMIN 仍取得留言")
    void listComments_archivedArticle_admin_returnsComments() {
        stubArticle("ARCHIVED", authorUuid);
        stubOneTopLevelComment();

        ArticleCommentListResponse resp = service.listComments(articleUuid, otherUserUuid, true, "newest", 1, 20);

        assertThat(resp.getTopLevels().getRecords()).hasSize(1);
    }

    @Test
    @DisplayName("文章為 DRAFT 時，匿名取得空清單")
    void listComments_draftArticle_anonymous_returnsEmpty() {
        stubArticle("DRAFT", authorUuid);

        ArticleCommentListResponse resp = service.listComments(articleUuid, null, false, "newest", 1, 20);

        assertThat(resp.getTopLevels().getRecords()).isEmpty();
    }

    @Test
    @DisplayName("文章不存在時回傳空清單（與不可見文章同形狀，不成為存在性探測器）")
    void listComments_articleNotFound_returnsEmpty() {
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.empty());

        ArticleCommentListResponse resp = service.listComments(articleUuid, null, false, "newest", 1, 20);

        assertThat(resp.getTopLevels().getRecords()).isEmpty();
        assertThat(resp.getTotalCommentCount()).isZero();
        verify(commentMapper, never())
                .findTopLevelByArticle(any(), any(), anyInt(), anyInt());
    }

    /**
     * 以指定狀態／作者 stub 跨模組文章查詢。
     *
     * @param status    文章狀態名稱
     * @param ownerUuid 文章作者公開 UUID
     */
    private void stubArticle(String status, UUID ownerUuid) {
        when(articleFacade.findByUuid(articleUuid))
                .thenReturn(Optional.of(new ArticleData(articleUuid, status, null, ownerUuid, null)));
    }

    /**
     * stub 一篇 PUBLISHED 文章（多數既有測試的前提）。
     */
    private void stubPublishedArticle() {
        stubArticle("PUBLISHED", authorUuid);
    }

    /**
     * stub 「該文章有 1 則 top-level 留言」的 mapper 回應。
     */
    private void stubOneTopLevelComment() {
        when(commentMapper.findTopLevelByArticle(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(makeRow(1L, null)));
        when(commentMapper.findRepliesByParentIds(any())).thenReturn(List.of());
        when(commentMapper.countByArticle(any())).thenReturn(1);
        when(commentMapper.countTopLevelByArticle(any())).thenReturn(1);
    }
}
