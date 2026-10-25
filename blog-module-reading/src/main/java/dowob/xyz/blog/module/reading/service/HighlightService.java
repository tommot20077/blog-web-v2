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
 * @version 2.0
 */
@Service
@RequiredArgsConstructor
public class HighlightService {

    private final UserHighlightRepository repo;
    private final ArticleFacade articleFacade;

    /**
     * 建立劃線（含可選註記）。
     *
     * <p>文章不存在（不分狀態，僅被硬刪者算不存在）時拋 {@code ARTICLE_NOT_FOUND}。劃線表已無指向 articles 的 FK
     * （V27），這個檢查是「不對不存在的文章寫入劃線」的唯一防線。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @param userUuid    劃線者公開 UUID
     * @param req         建立請求
     * @return 建立後的劃線
     * @throws BusinessException 文章不存在
     */
    @Transactional
    public HighlightResponse create(UUID articleUuid, UUID userUuid, CreateHighlightRequest req) {
        if (!articleFacade.existsByUuid(articleUuid)) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
        }

        UserHighlight h = new UserHighlight();
        h.setUuid(UUID.randomUUID());
        h.setUserUuid(userUuid);
        h.setArticleUuid(articleUuid);
        h.setSnippet(req.getSnippet());
        h.setPrefix(req.getPrefix() != null ? req.getPrefix() : "");
        h.setSuffix(req.getSuffix() != null ? req.getSuffix() : "");
        h.setColor(req.getColor());
        h.setNote(req.getNote());

        UserHighlight saved = repo.save(h);
        return toResponse(saved);
    }

    /**
     * 查詢某使用者在某篇文章的所有劃線（建立時間由舊到新）。
     *
     * @param articleUuid 文章公開 UUID
     * @param userUuid    使用者公開 UUID
     * @return 劃線列表
     * @throws BusinessException 文章不存在
     */
    public List<HighlightResponse> getByArticle(UUID articleUuid, UUID userUuid) {
        if (!articleFacade.existsByUuid(articleUuid)) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND);
        }
        return repo.findByUserUuidAndArticleUuidOrderByCreatedAtAsc(userUuid, articleUuid)
                .stream().map(this::toResponse).toList();
    }

    /**
     * 更新劃線的顏色與註記（只有擁有者可更新）。
     *
     * @param highlightUuid 劃線公開 UUID
     * @param userUuid      操作者公開 UUID
     * @param req           更新請求（欄位為 null 表示不更新）
     * @return 更新後的劃線
     * @throws BusinessException 劃線不存在，或操作者不是擁有者（{@code HIGHLIGHT_ACCESS_DENIED}）
     */
    @Transactional
    public HighlightResponse update(UUID highlightUuid, UUID userUuid, UpdateHighlightRequest req) {
        UserHighlight h = repo.findByUuid(highlightUuid)
                .orElseThrow(() -> new BusinessException(ReadingErrorCode.HIGHLIGHT_NOT_FOUND));
        if (!h.getUserUuid().equals(userUuid)) {
            throw new BusinessException(ReadingErrorCode.HIGHLIGHT_ACCESS_DENIED);
        }
        if (req.getColor() != null) h.setColor(req.getColor());
        if (req.getNote() != null) h.setNote(req.getNote());

        return toResponse(repo.save(h));
    }

    /**
     * 硬刪除劃線（只有擁有者可刪除）。
     *
     * @param highlightUuid 劃線公開 UUID
     * @param userUuid      操作者公開 UUID
     * @throws BusinessException 劃線不存在，或操作者不是擁有者（{@code HIGHLIGHT_ACCESS_DENIED}）
     */
    @Transactional
    public void delete(UUID highlightUuid, UUID userUuid) {
        UserHighlight h = repo.findByUuid(highlightUuid)
                .orElseThrow(() -> new BusinessException(ReadingErrorCode.HIGHLIGHT_NOT_FOUND));
        if (!h.getUserUuid().equals(userUuid)) {
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
