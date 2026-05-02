package dowob.xyz.blog.module.series.service;

import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.series.exception.SeriesErrorCode;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.model.dto.request.CreateSeriesRequest;
import dowob.xyz.blog.module.series.model.dto.request.UpdateSeriesRequest;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeriesServiceTest {

    @Mock private SeriesRepository repo;
    @Mock private SeriesMapper mapper;
    // 後續 task 會加 ArticleService / ReadingFacade mock
    @InjectMocks private SeriesService service;

    private final Long userId = 1L;
    private final Long seriesId = 100L;
    private final UUID seriesUuid = UUID.randomUUID();

    @Test
    void createSeries_validRequest_savesWithUuidAndDefaults() {
        when(repo.existsBySlug("vue-101")).thenReturn(false);
        when(repo.save(any(Series.class))).thenAnswer(inv -> {
            Series s = inv.getArgument(0);
            s.setId(seriesId);
            return s;
        });

        CreateSeriesRequest req = new CreateSeriesRequest();
        req.setTitle("Vue 101");
        req.setSlug("vue-101");
        req.setDescription("intro");

        service.createSeries(userId, req);

        ArgumentCaptor<Series> captor = ArgumentCaptor.forClass(Series.class);
        verify(repo).save(captor.capture());
        Series saved = captor.getValue();
        assertThat(saved.getUuid()).isNotNull();
        assertThat(saved.getAuthorId()).isEqualTo(userId);
        assertThat(saved.getTitle()).isEqualTo("Vue 101");
        assertThat(saved.getSlug()).isEqualTo("vue-101");
        assertThat(saved.getArticleCount()).isEqualTo(0);
    }

    @Test
    void createSeries_duplicateSlug_throwsS0104() {
        when(repo.existsBySlug("vue-101")).thenReturn(true);

        CreateSeriesRequest req = new CreateSeriesRequest();
        req.setTitle("Vue 101");
        req.setSlug("vue-101");

        assertThatThrownBy(() -> service.createSeries(userId, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SLUG_ALREADY_USED.getMessage());

        verify(repo, never()).save(any());
    }

    @Test
    void updateSeries_byOwner_updatesAllFields() {
        Series existing = new Series();
        existing.setId(seriesId); existing.setUuid(seriesUuid);
        existing.setAuthorId(userId);
        existing.setTitle("old"); existing.setSlug("old-slug");
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing));
        when(repo.existsBySlug("new-slug")).thenReturn(false);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("new");
        req.setSlug("new-slug");

        service.updateSeries(seriesUuid, userId, false, req);

        ArgumentCaptor<Series> captor = ArgumentCaptor.forClass(Series.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("new");
        assertThat(captor.getValue().getSlug()).isEqualTo("new-slug");
    }

    @Test
    void updateSeries_byNonOwnerNonAdmin_throwsS0102() {
        Series existing = new Series();
        existing.setId(seriesId); existing.setUuid(seriesUuid);
        existing.setAuthorId(999L);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing));

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("hijack");

        assertThatThrownBy(() -> service.updateSeries(seriesUuid, userId, false, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(SeriesErrorCode.SERIES_ACCESS_DENIED.getMessage());

        verify(repo, never()).save(any());
    }

    @Test
    void updateSeries_byAdmin_succeeds() {
        Series existing = new Series();
        existing.setId(seriesId); existing.setUuid(seriesUuid);
        existing.setAuthorId(999L);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateSeriesRequest req = new UpdateSeriesRequest();
        req.setTitle("admin override");

        service.updateSeries(seriesUuid, userId, /* isAdmin */ true, req);

        verify(repo).save(any());
    }

    @Test
    void deleteSeries_byOwner_deletes() {
        Series existing = new Series();
        existing.setId(seriesId); existing.setUuid(seriesUuid);
        existing.setAuthorId(userId);
        when(repo.findByUuid(seriesUuid)).thenReturn(Optional.of(existing));

        service.deleteSeries(seriesUuid, userId, false);

        verify(repo).deleteById(seriesId);
        // ON DELETE SET NULL 由 DB 處理；service 不需顯式 update articles
    }
}
