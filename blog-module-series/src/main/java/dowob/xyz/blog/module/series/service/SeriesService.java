package dowob.xyz.blog.module.series.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.series.exception.SeriesErrorCode;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.model.dto.request.CreateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.request.UpdateSeriesRequest;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Series Service — CRUD（create / update / delete）。
 *
 * <p>add/remove article 與 detail 留給 T11 / T12。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class SeriesService {

    private final SeriesRepository repo;
    private final SeriesMapper mapper;
    private final ArticleService articleService;

    @Transactional
    public Series createSeries(Long userId, CreateSeriesRequest req) {
        if (repo.existsBySlug(req.getSlug())) {
            throw new BusinessException(SeriesErrorCode.SLUG_ALREADY_USED);
        }
        Series s = new Series();
        s.setUuid(UUID.randomUUID());
        s.setTitle(req.getTitle());
        s.setSlug(req.getSlug());
        s.setDescription(req.getDescription());
        s.setCoverImageUrl(req.getCoverImageUrl());
        s.setAuthorId(userId);
        s.setArticleCount(0);
        return repo.save(s);
    }

    @Transactional
    public Series updateSeries(UUID seriesUuid, Long userId, boolean isAdmin, UpdateSeriesRequest req) {
        Series s = repo.findByUuid(seriesUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        if (!isAdmin && !s.getAuthorId().equals(userId)) {
            throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
        }
        if (req.getTitle() != null) s.setTitle(req.getTitle());
        if (req.getSlug() != null && !req.getSlug().equals(s.getSlug())) {
            if (repo.existsBySlug(req.getSlug())) {
                throw new BusinessException(SeriesErrorCode.SLUG_ALREADY_USED);
            }
            s.setSlug(req.getSlug());
        }
        if (req.getDescription() != null) s.setDescription(req.getDescription());
        if (req.getCoverImageUrl() != null) s.setCoverImageUrl(req.getCoverImageUrl());
        return repo.save(s);
    }

    @Transactional
    public void deleteSeries(UUID seriesUuid, Long userId, boolean isAdmin) {
        Series s = repo.findByUuid(seriesUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        if (!isAdmin && !s.getAuthorId().equals(userId)) {
            throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
        }
        repo.deleteById(s.getId());
    }

    /**
     * 將文章加入 Series（或更新排序位置）。
     *
     * <p>業務規則：
     * <ul>
     *   <li>文章必須為 PUBLISHED 狀態（S0103）</li>
     *   <li>文章若已屬於另一個 Series 則拒絕（S0106）</li>
     *   <li>文章已在同一個 Series 中時，僅更新位置，不重複 +1 計數</li>
     * </ul>
     * </p>
     *
     * @param seriesUuid  Series 公開 UUID
     * @param articleUuid 文章公開 UUID
     * @param userId      操作者 ID
     * @param isAdmin     是否管理員
     * @param position    在 Series 中的排序位置
     */
    @Transactional
    public void addArticleToSeries(UUID seriesUuid, UUID articleUuid, Long userId, boolean isAdmin, int position) {
        Series s = repo.findByUuid(seriesUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        if (!isAdmin && !s.getAuthorId().equals(userId)) {
            throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
        }

        Article article = articleService.findByUuid(articleUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.ARTICLE_NOT_IN_SERIES));
        if (article.getStatus() != ArticleStatus.PUBLISHED) {
            throw new BusinessException(SeriesErrorCode.ARTICLE_NOT_PUBLISHED);
        }
        if (!isAdmin && !article.getAuthorId().equals(userId)) {
            throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
        }
        if (article.getSeriesId() != null && !article.getSeriesId().equals(s.getId())) {
            throw new BusinessException(SeriesErrorCode.ARTICLE_IN_OTHER_SERIES);
        }

        boolean isNewMember = (article.getSeriesId() == null);
        articleService.updateSeriesAssignment(article.getId(), s.getId(), position);

        if (isNewMember) {
            mapper.incrementArticleCount(s.getId());
        }
    }

    /**
     * 將文章從 Series 移除。
     *
     * <p>文章不屬於此 Series 時拋出 S0105。</p>
     *
     * @param seriesUuid  Series 公開 UUID
     * @param articleUuid 文章公開 UUID
     * @param userId      操作者 ID
     * @param isAdmin     是否管理員
     */
    @Transactional
    public void removeArticleFromSeries(UUID seriesUuid, UUID articleUuid, Long userId, boolean isAdmin) {
        Series s = repo.findByUuid(seriesUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND));
        if (!isAdmin && !s.getAuthorId().equals(userId)) {
            throw new BusinessException(SeriesErrorCode.SERIES_ACCESS_DENIED);
        }

        Article article = articleService.findByUuid(articleUuid)
                .orElseThrow(() -> new BusinessException(SeriesErrorCode.ARTICLE_NOT_IN_SERIES));
        if (article.getSeriesId() == null || !article.getSeriesId().equals(s.getId())) {
            throw new BusinessException(SeriesErrorCode.ARTICLE_NOT_IN_SERIES);
        }

        articleService.updateSeriesAssignment(article.getId(), null, null);
        mapper.decrementArticleCount(s.getId());
    }
}
