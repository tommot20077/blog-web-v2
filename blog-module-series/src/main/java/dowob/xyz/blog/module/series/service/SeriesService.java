package dowob.xyz.blog.module.series.service;

import dowob.xyz.blog.common.api.dto.AuthorSummary;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.series.exception.SeriesErrorCode;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.model.SeriesWithAuthor;
import dowob.xyz.blog.module.series.model.dto.request.CreateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.request.UpdateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.response.MyProgress;
import dowob.xyz.blog.module.series.model.dto.response.SeriesDetailResponse;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Series Service — CRUD（create / update / delete）與詳情查詢。
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
    private final ReadingFacade readingFacade;

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

    /**
     * 取得 Series 詳情（含文章列表與我的進度）。
     *
     * <p>
     * articles sub-list 暫為空，Task 14 補完整 ArticleSummaryResponse mapping。
     * myProgress 僅在 currentUserId != null（已登入）時計算；
     * progress >= 0.95 視為已讀完。
     * </p>
     *
     * @param slug          Series URL slug
     * @param currentUserId 當前使用者 ID（未登入為 null）
     * @return Series 詳情 response
     */
    @Transactional(readOnly = true)
    public SeriesDetailResponse getSeriesDetail(String slug, Long currentUserId) {
        SeriesWithAuthor row = mapper.findBySlugWithAuthor(slug);
        if (row == null) {
            throw new BusinessException(SeriesErrorCode.SERIES_NOT_FOUND);
        }

        List<Article> articles = articleService.findBySeriesIdOrderByPosition(row.getId());

        SeriesDetailResponse resp = toDetailResponse(row, articles);

        if (currentUserId != null) {
            List<Long> articleIds = articles.stream().map(Article::getId).toList();
            Map<Long, BigDecimal> progressMap = readingFacade.batchGetProgress(currentUserId, articleIds);

            BigDecimal threshold = new BigDecimal("0.95");
            int readCount = (int) progressMap.values().stream()
                    .filter(p -> p.compareTo(threshold) >= 0).count();

            UUID nextUnread = articles.stream()
                    .filter(a -> {
                        BigDecimal p = progressMap.get(a.getId());
                        return p == null || p.compareTo(threshold) < 0;
                    })
                    .map(Article::getUuid)
                    .findFirst().orElse(null);

            resp.setMyProgress(new MyProgress(readCount, articles.size(), nextUnread));
        }
        return resp;
    }

    /**
     * 將 SeriesWithAuthor row 轉為 SeriesDetailResponse。
     *
     * <p>articles sub-list 暫為 List.of()，Task 14 補完。</p>
     */
    private SeriesDetailResponse toDetailResponse(SeriesWithAuthor row, List<Article> articles) {
        SeriesDetailResponse r = new SeriesDetailResponse();
        r.setUuid(row.getUuid());
        r.setTitle(row.getTitle());
        r.setSlug(row.getSlug());
        r.setDescription(row.getDescription());
        r.setCoverImageUrl(row.getCoverImageUrl());
        r.setArticleCount(row.getArticleCount());
        r.setCreatedAt(row.getCreatedAt());
        r.setUpdatedAt(row.getUpdatedAt());
        r.setAuthor(new AuthorSummary(
                row.getAuthorUuid(), row.getAuthorNickname(), row.getAuthorAvatarUrl()));
        r.setArticles(List.of());  // T14 補完 ArticleSummaryResponse mapping
        return r;
    }
}
