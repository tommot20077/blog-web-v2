package dowob.xyz.blog.module.series.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleNavRef;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * SeriesFacade 實作。
 *
 * <p>位於 series 模組，避免跨模組循環依賴（infrastructure 只定 interface）。</p>
 *
 * <p>透過 inject {@link ArticleFacade}（infrastructure interface）解耦：
 * ArticleFacadeImpl（article 模組）→ ArticleFacade（infrastructure interface）→ SeriesFacadeImpl
 * 形成 DAG，無循環依賴。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class SeriesFacadeImpl implements SeriesFacade {

    private final ArticleFacade articleFacade;
    private final SeriesRepository seriesRepo;
    private final SeriesMapper seriesMapper;

    @Override
    public Optional<SeriesNavigation> getSeriesNavigation(Long articleId) {
        Optional<ArticleData> articleOpt = articleFacade.findById(articleId);
        if (articleOpt.isEmpty()) return Optional.empty();
        ArticleData article = articleOpt.get();
        if (article.seriesId() == null || article.seriesPosition() == null) {
            return Optional.empty();
        }

        Optional<Series> seriesOpt = seriesRepo.findById(article.seriesId());
        if (seriesOpt.isEmpty()) return Optional.empty();
        Series series = seriesOpt.get();

        Optional<ArticleNavRef> prev =
                articleFacade.findPrevPublishedInSeries(series.getId(), article.seriesPosition());
        Optional<ArticleNavRef> next =
                articleFacade.findNextPublishedInSeries(series.getId(), article.seriesPosition());
        int totalCount = articleFacade
                .countPublishedBySeriesIds(List.of(series.getId()))
                .getOrDefault(series.getId(), 0);

        SeriesNavigation nav = new SeriesNavigation();
        nav.setSeriesUuid(series.getUuid());
        nav.setSeriesTitle(series.getTitle());
        nav.setSeriesSlug(series.getSlug());
        nav.setPosition(article.seriesPosition());
        nav.setTotalCount(totalCount);

        prev.ifPresent(p -> nav.setPrev(
                new SeriesNavigation.SeriesArticleRef(p.uuid(), p.title(), p.slug())));
        next.ifPresent(n -> nav.setNext(
                new SeriesNavigation.SeriesArticleRef(n.uuid(), n.title(), n.slug())));
        return Optional.of(nav);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<Long, SeriesBasicInfo> batchGetSeriesBasicInfo(Collection<Long> seriesIds) {
        if (seriesIds == null || seriesIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return BatchedQuery.queryInBatches(seriesIds, seriesMapper::findBasicInfoBySeriesIds).stream()
                .collect(Collectors.toMap(
                        SeriesMapper.SeriesBasicRow::seriesId,
                        row -> new SeriesBasicInfo(row.seriesUuid(), row.seriesTitle())));
    }
}
