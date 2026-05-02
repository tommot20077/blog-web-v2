package dowob.xyz.blog.module.series.service;

import dowob.xyz.blog.common.exception.BusinessException;
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
}
