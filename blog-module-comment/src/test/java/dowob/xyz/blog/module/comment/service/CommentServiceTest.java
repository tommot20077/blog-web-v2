package dowob.xyz.blog.module.comment.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.comment.exception.CommentErrorCode;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.dto.request.CreateCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.request.EditCommentRequest;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock private CommentRepository commentRepo;
    @Mock private CommentMapper commentMapper;
    @Mock private CommentMarkdownRenderer renderer;
    @Mock private ArticleService articleService;
    @InjectMocks private CommentService service;

    private final Long userId = 10L;
    private final Long articleId = 100L;
    private final UUID articleUuid = UUID.randomUUID();

    @Test
    void createComment_topLevel_savesWithUuidAndDefaults() {
        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(renderer.render("hello")).thenReturn("<p>hello</p>");
        when(commentRepo.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("hello");

        service.createComment(articleUuid, userId, req);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepo).save(captor.capture());
        Comment saved = captor.getValue();
        assertThat(saved.getUuid()).isNotNull();
        assertThat(saved.getArticleId()).isEqualTo(articleId);
        assertThat(saved.getParentId()).isNull();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getContent()).isEqualTo("hello");
        assertThat(saved.getContentHtml()).isEqualTo("<p>hello</p>");
        assertThat(saved.getLikeCount()).isEqualTo(0);
        assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    void createComment_topLevel_incrementsArticleCommentCount() {
        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(renderer.render(any())).thenReturn("<p>x</p>");
        when(commentRepo.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("x");

        service.createComment(articleUuid, userId, req);

        verify(articleService).incrementCommentCount(articleId);
    }

    @Test
    void createComment_replyToTopLevel_succeeds() {
        UUID parentUuid = UUID.randomUUID();
        Comment parent = new Comment();
        parent.setId(50L);
        parent.setUuid(parentUuid);
        parent.setArticleId(articleId);
        parent.setParentId(null);  // top-level

        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
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

        service.createComment(articleUuid, userId, req);

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
        reply.setArticleId(articleId);
        reply.setParentId(40L);   // 已是 reply

        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(commentRepo.findByUuid(parentUuid)).thenReturn(Optional.of(reply));

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("nested");
        req.setParentUuid(parentUuid);

        assertThatThrownBy(() -> service.createComment(articleUuid, userId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.NESTING_TOO_DEEP.getMessage());
    }

    @Test
    void createComment_parentNotInSameArticle_throws() {
        UUID parentUuid = UUID.randomUUID();
        Comment parent = new Comment();
        parent.setId(50L);
        parent.setUuid(parentUuid);
        parent.setArticleId(999L);    // 不同文章
        parent.setParentId(null);

        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(commentRepo.findByUuid(parentUuid)).thenReturn(Optional.of(parent));

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("x");
        req.setParentUuid(parentUuid);

        assertThatThrownBy(() -> service.createComment(articleUuid, userId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.PARENT_NOT_IN_ARTICLE.getMessage());
    }

    @Test
    void createComment_parentSoftDeleted_throwsParentFrozen() {
        UUID parentUuid = UUID.randomUUID();
        Comment parent = new Comment();
        parent.setId(50L);
        parent.setUuid(parentUuid);
        parent.setArticleId(articleId);
        parent.setParentId(null);
        parent.setDeletedAt(LocalDateTime.now());     // 軟刪除

        when(articleService.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(commentRepo.findByUuid(parentUuid)).thenReturn(Optional.of(parent));

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("x");
        req.setParentUuid(parentUuid);

        assertThatThrownBy(() -> service.createComment(articleUuid, userId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.PARENT_FROZEN.getMessage());
    }

    @Test
    void editComment_within5Min_updatesContentAndSetsEditedAt() {
        UUID commentUuid = UUID.randomUUID();
        Comment c = new Comment();
        c.setId(1L);
        c.setUuid(commentUuid);
        c.setUserId(userId);
        c.setContent("original");
        c.setContentHtml("<p>original</p>");
        c.setCreatedAt(LocalDateTime.now().minusMinutes(2));
        c.setDeletedAt(null);

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(renderer.render("edited")).thenReturn("<p>edited</p>");
        when(commentRepo.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        EditCommentRequest req = new EditCommentRequest();
        req.setContent("edited");

        service.editComment(commentUuid, userId, false, req);

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
        c.setUserId(userId);
        c.setCreatedAt(LocalDateTime.now().minusMinutes(10));

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

        EditCommentRequest req = new EditCommentRequest();
        req.setContent("late");

        assertThatThrownBy(() -> service.editComment(commentUuid, userId, false, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.EDIT_WINDOW_EXPIRED.getMessage());
    }

    @Test
    void editComment_byAdminAfter5Min_succeeds() {
        UUID commentUuid = UUID.randomUUID();
        Comment c = new Comment();
        c.setId(1L);
        c.setUuid(commentUuid);
        c.setUserId(999L);     // 不是 admin 自己的留言
        c.setCreatedAt(LocalDateTime.now().minusMinutes(10));

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));
        when(renderer.render(any())).thenReturn("<p>x</p>");
        when(commentRepo.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        EditCommentRequest req = new EditCommentRequest();
        req.setContent("admin edit");

        service.editComment(commentUuid, 1L, true, req);    // adminUserId=1L, isAdmin=true

        verify(commentRepo).save(any());
    }

    @Test
    void editComment_byOtherUser_throwsAccessDenied() {
        UUID commentUuid = UUID.randomUUID();
        Comment c = new Comment();
        c.setId(1L);
        c.setUuid(commentUuid);
        c.setUserId(999L);     // 別人留的
        c.setCreatedAt(LocalDateTime.now());

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

        EditCommentRequest req = new EditCommentRequest();
        req.setContent("hijack");

        assertThatThrownBy(() -> service.editComment(commentUuid, userId, false, req))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void editComment_softDeletedComment_throws() {
        UUID commentUuid = UUID.randomUUID();
        Comment c = new Comment();
        c.setId(1L);
        c.setUuid(commentUuid);
        c.setUserId(userId);
        c.setCreatedAt(LocalDateTime.now());
        c.setDeletedAt(LocalDateTime.now());

        when(commentRepo.findByUuid(commentUuid)).thenReturn(Optional.of(c));

        EditCommentRequest req = new EditCommentRequest();
        req.setContent("ghost");

        assertThatThrownBy(() -> service.editComment(commentUuid, userId, false, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommentErrorCode.COMMENT_DELETED.getMessage());
    }
}
