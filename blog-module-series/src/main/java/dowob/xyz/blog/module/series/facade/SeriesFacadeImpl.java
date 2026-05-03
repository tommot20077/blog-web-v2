package dowob.xyz.blog.module.series.facade;

import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

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
 * <p>ArticleServiceImpl → SeriesFacade（interface）→ SeriesFacadeImpl → ArticleService
 * 會形成循環依賴，因此 articleService 使用 {@code @Lazy} setter injection 打破循環。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class SeriesFacadeImpl implements SeriesFacade {

    /** 使用 @Lazy + setter injection 打破 ArticleServiceImpl <-> SeriesFacadeImpl 循環依賴 */
    @Setter(onMethod_ = {@Autowired, @Lazy})
    private ArticleService articleService;
    private final SeriesRepository seriesRepo;
    private final SeriesMapper seriesMapper;

    @Override
    public Optional<SeriesNavigation> getSeriesNavigation(Long articleId) {
        Optional<Article> articleOpt = articleService.findById(articleId);
        if (articleOpt.isEmpty()) return Optional.empty();
        Article article = articleOpt.get();
        if (article.getSeriesId() == null || article.getSeriesPosition() == null) {
            return Optional.empty();
        }

        Optional<Series> seriesOpt = seriesRepo.findById(article.getSeriesId());
        if (seriesOpt.isEmpty()) return Optional.empty();
        Series series = seriesOpt.get();

        SeriesMapper.NavRow prev = seriesMapper.findPrevNav(series.getId(), article.getSeriesPosition());
        SeriesMapper.NavRow next = seriesMapper.findNextNav(series.getId(), article.getSeriesPosition());
        int totalCount = seriesMapper.countPublishedInSeries(series.getId());

        SeriesNavigation nav = new SeriesNavigation();
        nav.setSeriesUuid(series.getUuid());
        nav.setSeriesTitle(series.getTitle());
        nav.setSeriesSlug(series.getSlug());
        nav.setPosition(article.getSeriesPosition());
        nav.setTotalCount(totalCount);

        if (prev != null) {
            nav.setPrev(new SeriesNavigation.SeriesArticleRef(prev.getUuid(), prev.getTitle(), prev.getSlug()));
        }
        if (next != null) {
            nav.setNext(new SeriesNavigation.SeriesArticleRef(next.getUuid(), next.getTitle(), next.getSlug()));
        }
        return Optional.of(nav);
    }

    @Override
    public Map<Long, SeriesBasicInfo> batchGetSeriesBasicInfo(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return seriesMapper.findSeriesByArticleIds(articleIds).stream()
                .collect(Collectors.toMap(
                        SeriesMapper.ArticleSeriesRow::getArticleId,
                        row -> new SeriesBasicInfo(row.getSeriesUuid(), row.getSeriesTitle())
                ));
    }
}
