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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 文字劃線 Service。
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class HighlightService {

    private final UserHighlightRepository repo;
    private final ArticleFacade articleFacade;

    @Transactional
    public HighlightResponse create(UUID articleUuid, Long userId, CreateHighlightRequest req) {
        Long articleId = articleFacade.findIdByUuid(articleUuid);
        if (articleId == null) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
        }

        UserHighlight h = new UserHighlight();
        h.setUuid(UUID.randomUUID());
        h.setUserId(userId);
        h.setArticleId(articleId);
        h.setSnippet(req.getSnippet());
        h.setPrefix(req.getPrefix() != null ? req.getPrefix() : "");
        h.setSuffix(req.getSuffix() != null ? req.getSuffix() : "");
        h.setColor(req.getColor());
        h.setNote(req.getNote());

        UserHighlight saved = repo.save(h);
        return toResponse(saved);
    }

    public List<HighlightResponse> getByArticle(UUID articleUuid, Long userId) {
        Long articleId = articleFacade.findIdByUuid(articleUuid);
        if (articleId == null) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
        }
        return repo.findByUserIdAndArticleIdOrderByCreatedAtAsc(userId, articleId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public HighlightResponse update(UUID highlightUuid, Long userId, UpdateHighlightRequest req) {
        UserHighlight h = repo.findByUuid(highlightUuid)
                .orElseThrow(() -> new BusinessException(ReadingErrorCode.HIGHLIGHT_NOT_FOUND));
        if (!h.getUserId().equals(userId)) {
            throw new BusinessException(ReadingErrorCode.HIGHLIGHT_ACCESS_DENIED);
        }
        if (req.getColor() != null) h.setColor(req.getColor());
        if (req.getNote() != null) h.setNote(req.getNote());

        return toResponse(repo.save(h));
    }

    @Transactional
    public void delete(UUID highlightUuid, Long userId) {
        UserHighlight h = repo.findByUuid(highlightUuid)
                .orElseThrow(() -> new BusinessException(ReadingErrorCode.HIGHLIGHT_NOT_FOUND));
        if (!h.getUserId().equals(userId)) {
            throw new BusinessException(ReadingErrorCode.HIGHLIGHT_ACCESS_DENIED);
        }
        repo.deleteById(h.getId());
    }

    private HighlightResponse toResponse(UserHighlight h) {
        HighlightResponse r = new HighlightResponse();
        r.setUuid(h.getUuid());
        r.setSnippet(h.getSnippet());
        r.setPrefix(h.getPrefix());
        r.setSuffix(h.getSuffix());
        r.setColor(h.getColor());
        r.setNote(h.getNote());
        r.setCreatedAt(h.getCreatedAt());
        r.setUpdatedAt(h.getUpdatedAt());
        return r;
    }
}
