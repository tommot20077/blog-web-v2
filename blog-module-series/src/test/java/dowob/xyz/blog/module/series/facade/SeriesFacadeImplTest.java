package dowob.xyz.blog.module.series.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleNavRef;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeriesFacadeImplTest {

    @Mock private ArticleFacade articleFacade;
    @Mock private SeriesRepository seriesRepo;
    @Mock private SeriesMapper seriesMapper;
    @InjectMocks private SeriesFacadeImpl facade;

    private final Long articleId = 200L;
    private final Long seriesId = 100L;

    private Series series() {
        Series s = new Series();
        s.setId(seriesId);
        s.setUuid(UUID.randomUUID());
        s.setTitle("系列");
        s.setSlug("series-slug");
        return s;
    }

    private ArticleData articleAt(int position) {
        return new ArticleData(articleId, UUID.randomUUID(), 42L, "PUBLISHED", seriesId, position);
    }

    @Test
    void 導覽走ArticleFacade而非SeriesMapper() {
        UUID prevUuid = UUID.randomUUID();
        UUID nextUuid = UUID.randomUUID();
        when(articleFacade.findById(articleId)).thenReturn(Optional.of(articleAt(2)));
        when(seriesRepo.findById(seriesId)).thenReturn(Optional.of(series()));
        when(articleFacade.findPrevPublishedInSeries(seriesId, 2))
                .thenReturn(Optional.of(new ArticleNavRef(prevUuid, "前篇", "prev")));
        when(articleFacade.findNextPublishedInSeries(seriesId, 2))
                .thenReturn(Optional.of(new ArticleNavRef(nextUuid, "後篇", "next")));
        when(articleFacade.countPublishedBySeriesIds(List.of(seriesId)))
                .thenReturn(Map.of(seriesId, 5));

        SeriesNavigation nav = facade.getSeriesNavigation(articleId).orElseThrow();

        assertThat(nav.getPrev().getUuid()).isEqualTo(prevUuid);
        assertThat(nav.getNext().getUuid()).isEqualTo(nextUuid);
        assertThat(nav.getTotalCount()).isEqualTo(5);
        assertThat(nav.getPosition()).isEqualTo(2);
    }

    @Test
    void 首篇的prev為null() {
        when(articleFacade.findById(articleId)).thenReturn(Optional.of(articleAt(1)));
        when(seriesRepo.findById(seriesId)).thenReturn(Optional.of(series()));
        when(articleFacade.findPrevPublishedInSeries(seriesId, 1)).thenReturn(Optional.empty());
        when(articleFacade.findNextPublishedInSeries(seriesId, 1)).thenReturn(Optional.empty());
        when(articleFacade.countPublishedBySeriesIds(List.of(seriesId)))
                .thenReturn(Map.of(seriesId, 1));

        SeriesNavigation nav = facade.getSeriesNavigation(articleId).orElseThrow();

        assertThat(nav.getPrev()).isNull();
        assertThat(nav.getNext()).isNull();
        assertThat(nav.getTotalCount()).isEqualTo(1);
    }

    @Test
    void series無公開文章時totalCount為0() {
        when(articleFacade.findById(articleId)).thenReturn(Optional.of(articleAt(1)));
        when(seriesRepo.findById(seriesId)).thenReturn(Optional.of(series()));
        when(articleFacade.findPrevPublishedInSeries(seriesId, 1)).thenReturn(Optional.empty());
        when(articleFacade.findNextPublishedInSeries(seriesId, 1)).thenReturn(Optional.empty());
        when(articleFacade.countPublishedBySeriesIds(List.of(seriesId))).thenReturn(Map.of());

        SeriesNavigation nav = facade.getSeriesNavigation(articleId).orElseThrow();

        assertThat(nav.getTotalCount()).isZero();
    }
}
