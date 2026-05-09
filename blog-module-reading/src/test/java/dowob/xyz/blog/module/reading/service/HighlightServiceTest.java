package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.reading.exception.ReadingErrorCode;
import dowob.xyz.blog.module.reading.model.UserHighlight;
import dowob.xyz.blog.module.reading.model.dto.request.CreateHighlightRequest;
import dowob.xyz.blog.module.reading.model.dto.request.UpdateHighlightRequest;
import dowob.xyz.blog.module.reading.model.dto.response.HighlightResponse;
import dowob.xyz.blog.module.reading.repository.UserHighlightRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HighlightServiceTest {

    @Mock private UserHighlightRepository repo;
    @Mock private ArticleFacade articleFacade;
    @InjectMocks private HighlightService service;

    private final Long userId = 1L;
    private final Long articleId = 100L;
    private final UUID articleUuid = UUID.randomUUID();
    private final UUID highlightUuid = UUID.randomUUID();

    @Test
    void createHighlight_savesWithUuidAndAllFields() {
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(repo.save(any(UserHighlight.class))).thenAnswer(inv -> {
            UserHighlight h = inv.getArgument(0);
            h.setId(1L);
            return h;
        });

        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("hello world");
        req.setPrefix("intro: ");
        req.setSuffix(" end");
        req.setColor("#FFEB3B");
        req.setNote("important");

        service.create(articleUuid, userId, req);

        ArgumentCaptor<UserHighlight> captor = ArgumentCaptor.forClass(UserHighlight.class);
        verify(repo).save(captor.capture());
        UserHighlight saved = captor.getValue();
        assertThat(saved.getUuid()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getArticleId()).isEqualTo(articleId);
        assertThat(saved.getSnippet()).isEqualTo("hello world");
        assertThat(saved.getColor()).isEqualTo("#FFEB3B");
        assertThat(saved.getNote()).isEqualTo("important");
    }

    @Test
    void createHighlight_emptyNote_savesAsNull() {
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("test");
        req.setColor("#FFEB3B");
        req.setNote(null);

        service.create(articleUuid, userId, req);

        ArgumentCaptor<UserHighlight> captor = ArgumentCaptor.forClass(UserHighlight.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getNote()).isNull();
    }

    @Test
    void getByArticle_returnsUserOwnedOnly() {
        UserHighlight h1 = new UserHighlight();
        h1.setId(1L); h1.setUuid(UUID.randomUUID());
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(articleId);
        when(repo.findByUserIdAndArticleIdOrderByCreatedAtAsc(userId, articleId))
                .thenReturn(List.of(h1));

        List<HighlightResponse> results = service.getByArticle(articleUuid, userId);

        assertThat(results).hasSize(1);
        verify(repo).findByUserIdAndArticleIdOrderByCreatedAtAsc(userId, articleId);
    }

    @Test
    void updateHighlight_byOwner_updatesColorAndNote() {
        UserHighlight h = new UserHighlight();
        h.setId(1L); h.setUuid(highlightUuid); h.setUserId(userId);
        h.setColor("#FFEB3B"); h.setNote("old");
        when(repo.findByUuid(highlightUuid)).thenReturn(Optional.of(h));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateHighlightRequest req = new UpdateHighlightRequest();
        req.setColor("#00FF00");
        req.setNote("new");

        service.update(highlightUuid, userId, req);

        ArgumentCaptor<UserHighlight> captor = ArgumentCaptor.forClass(UserHighlight.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getColor()).isEqualTo("#00FF00");
        assertThat(captor.getValue().getNote()).isEqualTo("new");
    }

    @Test
    void updateHighlight_partialUpdate_keepsUntouched() {
        UserHighlight h = new UserHighlight();
        h.setId(1L); h.setUuid(highlightUuid); h.setUserId(userId);
        h.setColor("#FFEB3B"); h.setNote("kept");
        when(repo.findByUuid(highlightUuid)).thenReturn(Optional.of(h));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateHighlightRequest req = new UpdateHighlightRequest();
        req.setColor("#00FF00");

        service.update(highlightUuid, userId, req);

        ArgumentCaptor<UserHighlight> captor = ArgumentCaptor.forClass(UserHighlight.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getColor()).isEqualTo("#00FF00");
        assertThat(captor.getValue().getNote()).isEqualTo("kept");
    }

    @Test
    void updateHighlight_byNonOwner_throwsAccessDenied() {
        UserHighlight h = new UserHighlight();
        h.setId(1L); h.setUuid(highlightUuid); h.setUserId(999L);
        when(repo.findByUuid(highlightUuid)).thenReturn(Optional.of(h));

        UpdateHighlightRequest req = new UpdateHighlightRequest();
        req.setColor("#00FF00");

        assertThatThrownBy(() -> service.update(highlightUuid, userId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ReadingErrorCode.HIGHLIGHT_ACCESS_DENIED.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void deleteHighlight_byOwner_hardDeletes() {
        UserHighlight h = new UserHighlight();
        h.setId(1L); h.setUuid(highlightUuid); h.setUserId(userId);
        when(repo.findByUuid(highlightUuid)).thenReturn(Optional.of(h));

        service.delete(highlightUuid, userId);

        verify(repo).deleteById(1L);
    }

    @Test
    void deleteHighlight_byNonOwner_throwsAccessDenied() {
        UserHighlight h = new UserHighlight();
        h.setId(1L); h.setUuid(highlightUuid); h.setUserId(999L);
        when(repo.findByUuid(highlightUuid)).thenReturn(Optional.of(h));

        assertThatThrownBy(() -> service.delete(highlightUuid, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ReadingErrorCode.HIGHLIGHT_ACCESS_DENIED.getMessage());
        verify(repo, never()).deleteById(any());
    }

    @Test
    void deleteHighlight_nonExistent_throwsR0201() {
        when(repo.findByUuid(highlightUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(highlightUuid, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ReadingErrorCode.HIGHLIGHT_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("create：article 不存在 → throw ARTICLE_NOT_FOUND")
    void create_articleNotFound_throwsArticleNotFound() {
        UUID articleUuid = UUID.randomUUID();
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(null);

        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("text");
        req.setColor("yellow");

        assertThatThrownBy(() -> service.create(articleUuid, 1L, req))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getCode())
                .isEqualTo(ArticleErrorCode.ARTICLE_NOT_FOUND.getCode());
        verify(repo, never()).save(any(UserHighlight.class));
    }

    @Test
    @DisplayName("getByArticle：article 不存在 → throw ARTICLE_NOT_FOUND")
    void getByArticle_articleNotFound_throwsArticleNotFound() {
        UUID articleUuid = UUID.randomUUID();
        when(articleFacade.findIdByUuid(articleUuid)).thenReturn(null);

        assertThatThrownBy(() -> service.getByArticle(articleUuid, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getCode())
                .isEqualTo(ArticleErrorCode.ARTICLE_NOT_FOUND.getCode());
        verify(repo, never()).findByUserIdAndArticleIdOrderByCreatedAtAsc(any(), any());
    }
}
