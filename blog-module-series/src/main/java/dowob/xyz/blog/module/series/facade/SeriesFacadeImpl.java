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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
 * @version 2.0（移除 Long 主鍵方法）
 */
@Service
@RequiredArgsConstructor
public class SeriesFacadeImpl implements SeriesFacade {

    private final ArticleFacade articleFacade;
    private final SeriesRepository seriesRepo;
    private final SeriesMapper seriesMapper;

    /**
     * {@inheritDoc}
     *
     * <p>全程使用 {@link ArticleFacade} 的 UUID 方法（{@code findByUuid}、{@code findPrev/NextPublishedInSeries}、
     * {@code countPublishedBySeriesUuids}）；series 以 {@code ArticleData.seriesUuid} 查自己的表。</p>
     */
    @Override
    public Optional<SeriesNavigation> getSeriesNavigation(UUID articleUuid) {
        Optional<ArticleData> articleOpt = articleFacade.findByUuid(articleUuid);
        if (articleOpt.isEmpty()) return Optional.empty();
        ArticleData article = articleOpt.get();
        if (article.seriesUuid() == null || article.seriesPosition() == null) {
            return Optional.empty();
        }

        Optional<Series> seriesOpt = seriesRepo.findByUuid(article.seriesUuid());
        if (seriesOpt.isEmpty()) return Optional.empty();
        Series series = seriesOpt.get();

        Optional<ArticleNavRef> prev =
                articleFacade.findPrevPublishedInSeries(series.getUuid(), article.seriesPosition());
        Optional<ArticleNavRef> next =
                articleFacade.findNextPublishedInSeries(series.getUuid(), article.seriesPosition());
        int totalCount = articleFacade
                .countPublishedBySeriesUuids(List.of(series.getUuid()))
                .getOrDefault(series.getUuid(), 0);

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
     *
     * <p>先去重並濾掉 null，再經 {@link BatchedQuery} 切批；空輸入不發查詢，避免產生 {@code IN ()}。
     * 各批 key 不重疊，屬跨批可合併形狀。</p>
     */
    @Override
    public Map<UUID, SeriesBasicInfo> batchGetSeriesBasicInfoByUuids(Collection<UUID> seriesUuids) {
        if (seriesUuids == null || seriesUuids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<UUID> distinct = seriesUuids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return BatchedQuery.queryInBatches(distinct, seriesMapper::findBasicInfoBySeriesUuids).stream()
                .collect(Collectors.toMap(
                        SeriesMapper.SeriesBasicRow::seriesUuid,
                        row -> new SeriesBasicInfo(row.seriesUuid(), row.seriesTitle())));
    }

    /**
     * {@inheritDoc}
     *
     * <p>輸入無上界，故去重後經 {@link BatchedQuery} 切批；空輸入不發查詢，避免產生
     * {@code IN ()}。結果為集合聯集，屬跨批可合併形狀。</p>
     */
    @Override
    public Set<UUID> filterExistingUuids(Collection<UUID> uuids) {
        if (uuids == null) {
            return Set.of();
        }
        List<UUID> distinct = uuids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return BatchedQuery.queryInBatches(distinct, seriesMapper::findExistingUuidsIn).stream()
                .map(UUID::fromString)
                .collect(Collectors.toSet());
    }
}
