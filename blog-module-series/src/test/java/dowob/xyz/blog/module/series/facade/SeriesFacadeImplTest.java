package dowob.xyz.blog.module.series.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleNavRef;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeriesFacadeImplTest {

    @Mock private ArticleFacade articleFacade;
    @Mock private SeriesRepository seriesRepo;
    @Mock private SeriesMapper seriesMapper;
    @InjectMocks private SeriesFacadeImpl facade;

    private final UUID articleUuid = UUID.randomUUID();
    private final UUID seriesUuid = UUID.randomUUID();

    private Series series() {
        Series s = new Series();
        s.setId(100L);
        s.setUuid(seriesUuid);
        s.setTitle("系列");
        s.setSlug("series-slug");
        return s;
    }

    /** 文章在 series 內的位置（M6-b：ArticleData 已沒有 Long 的 series 主鍵，導覽只讀 UUID 欄位） */
    private ArticleData articleAt(int position) {
        return new ArticleData(200L, articleUuid, "PUBLISHED", position, UUID.randomUUID(), seriesUuid);
    }

    @Test
    @DisplayName("導覽走 ArticleFacade 的 UUID 版（findByUuid／prev／next／count）；不以 Long 主鍵查文章（findById）")
    void navigationUsesArticleFacadeUuidMethods() {
        UUID prevUuid = UUID.randomUUID();
        UUID nextUuid = UUID.randomUUID();
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(articleAt(2)));
        when(seriesRepo.findByUuid(seriesUuid)).thenReturn(Optional.of(series()));
        when(articleFacade.findPrevPublishedInSeries(seriesUuid, 2))
                .thenReturn(Optional.of(new ArticleNavRef(prevUuid, "前篇", "prev")));
        when(articleFacade.findNextPublishedInSeries(seriesUuid, 2))
                .thenReturn(Optional.of(new ArticleNavRef(nextUuid, "後篇", "next")));
        when(articleFacade.countPublishedBySeriesUuids(List.of(seriesUuid)))
                .thenReturn(Map.of(seriesUuid, 5));

        SeriesNavigation nav = facade.getSeriesNavigation(articleUuid).orElseThrow();

        assertThat(nav.getPrev().getUuid()).isEqualTo(prevUuid);
        assertThat(nav.getNext().getUuid()).isEqualTo(nextUuid);
        assertThat(nav.getTotalCount()).isEqualTo(5);
        assertThat(nav.getPosition()).isEqualTo(2);
        assertThat(nav.getSeriesUuid()).isEqualTo(seriesUuid);
        verify(articleFacade, never()).findById(any());
    }

    @Test
    @DisplayName("文章不存在 → empty")
    void unknownArticle_returnsEmpty() {
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.empty());

        assertThat(facade.getSeriesNavigation(articleUuid)).isEmpty();
    }

    @Test
    @DisplayName("文章不在任何 series（seriesUuid 為 null）→ empty，不查 series")
    void articleWithoutSeries_returnsEmpty() {
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                new ArticleData(200L, articleUuid, "PUBLISHED", null, UUID.randomUUID(), null)));

        assertThat(facade.getSeriesNavigation(articleUuid)).isEmpty();
        verify(seriesRepo, never()).findByUuid(any());
    }

    @Test
    @DisplayName("文章有 seriesUuid 但 series 已不存在 → empty")
    void seriesGone_returnsEmpty() {
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(articleAt(1)));
        when(seriesRepo.findByUuid(seriesUuid)).thenReturn(Optional.empty());

        assertThat(facade.getSeriesNavigation(articleUuid)).isEmpty();
    }

    @Test
    @DisplayName("首篇的 prev 為 null")
    void firstArticlePrevIsNull() {
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(articleAt(1)));
        when(seriesRepo.findByUuid(seriesUuid)).thenReturn(Optional.of(series()));
        when(articleFacade.findPrevPublishedInSeries(seriesUuid, 1)).thenReturn(Optional.empty());
        when(articleFacade.findNextPublishedInSeries(seriesUuid, 1)).thenReturn(Optional.empty());
        when(articleFacade.countPublishedBySeriesUuids(List.of(seriesUuid)))
                .thenReturn(Map.of(seriesUuid, 1));

        SeriesNavigation nav = facade.getSeriesNavigation(articleUuid).orElseThrow();

        assertThat(nav.getPrev()).isNull();
        assertThat(nav.getNext()).isNull();
        assertThat(nav.getTotalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("series 無公開文章時 totalCount 為 0")
    void whenSeriesHasNoPublishedArticles_totalCountIsZero() {
        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(articleAt(1)));
        when(seriesRepo.findByUuid(seriesUuid)).thenReturn(Optional.of(series()));
        when(articleFacade.findPrevPublishedInSeries(seriesUuid, 1)).thenReturn(Optional.empty());
        when(articleFacade.findNextPublishedInSeries(seriesUuid, 1)).thenReturn(Optional.empty());
        when(articleFacade.countPublishedBySeriesUuids(List.of(seriesUuid))).thenReturn(Map.of());

        SeriesNavigation nav = facade.getSeriesNavigation(articleUuid).orElseThrow();

        assertThat(nav.getTotalCount()).isZero();
    }
}
