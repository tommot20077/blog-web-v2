package dowob.xyz.blog.module.series.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SeriesFacadeImpl#filterExistingUuids} 單元測試。
 *
 * <p>供每日校正排程判斷「系列是否已被刪除」。系列沒有狀態欄位，存在即回傳。
 * 這裡驗證 facade 層行為（空輸入、去重、切批、結果轉換）；SQL 由 IT 對真實 Postgres 驗證。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SeriesFacadeImpl.filterExistingUuids（存在性查詢）")
class SeriesFacadeFilterExistingUuidsTest {

    @Mock private ArticleFacade articleFacade;
    @Mock private SeriesRepository seriesRepo;
    @Mock private SeriesMapper seriesMapper;
    @InjectMocks private SeriesFacadeImpl facade;

    @Test
    @DisplayName("輸入為 null 或空集合時不查 DB 並回傳空集合")
    void whenNullOrEmptyInput_doesNotQueryDatabase() {
        assertThat(facade.filterExistingUuids(null)).isEmpty();
        assertThat(facade.filterExistingUuids(List.of())).isEmpty();

        verify(seriesMapper, never()).findExistingUuidsIn(any());
    }

    @Test
    @DisplayName("只回傳 mapper 認定仍存在的 uuid")
    void returnsOnlyUuidsReportedByMapper() {
        UUID exists = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        when(seriesMapper.findExistingUuidsIn(List.of(exists, deleted)))
                .thenReturn(List.of(exists.toString()));

        Set<UUID> result = facade.filterExistingUuids(List.of(exists, deleted));

        assertThat(result).containsExactly(exists);
    }

    @Test
    @DisplayName("重複與 null 的 uuid 先去除再查詢")
    void deduplicatesAndDropsNullsBeforeQuery() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(seriesMapper.findExistingUuidsIn(List.of(a, b)))
                .thenReturn(List.of(a.toString(), b.toString()));

        Set<UUID> result = facade.filterExistingUuids(Arrays.asList(a, null, b, a, b));

        assertThat(result).containsExactlyInAnyOrder(a, b);
        verify(seriesMapper, times(1)).findExistingUuidsIn(List.of(a, b));
    }

    @Test
    @DisplayName("全為 null 時去重後為空，不查 DB")
    void whenOnlyNulls_doesNotQueryDatabase() {
        assertThat(facade.filterExistingUuids(Arrays.asList(null, null))).isEmpty();

        verify(seriesMapper, never()).findExistingUuidsIn(any());
    }

    @Test
    @DisplayName("超過批次上限時分批查詢，每批不超過上限且各批結果合併")
    void whenInputExceedsBatchSize_splitsIntoBatchesAndMergesResult() {
        int total = BatchedQuery.BATCH_SIZE * 2 + 200;
        List<UUID> uuids = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            uuids.add(UUID.randomUUID());
        }
        when(seriesMapper.findExistingUuidsIn(any())).thenAnswer(invocation -> {
            List<UUID> batch = invocation.getArgument(0);
            return batch.stream().map(UUID::toString).toList();
        });

        Set<UUID> result = facade.filterExistingUuids(uuids);

        ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
        verify(seriesMapper, times(3)).findExistingUuidsIn(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(BatchedQuery.BATCH_SIZE));
        assertThat(result).containsExactlyInAnyOrderElementsOf(uuids);
    }
}
