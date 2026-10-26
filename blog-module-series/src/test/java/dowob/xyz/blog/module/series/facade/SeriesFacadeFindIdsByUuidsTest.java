package dowob.xyz.blog.module.series.facade;

import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SeriesFacadeImpl#findIdsByUuids} 單元測試（M4-a：series UUID → 主鍵的批次轉換）。
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SeriesFacadeImpl.findIdsByUuids")
class SeriesFacadeFindIdsByUuidsTest {

    @Mock private SeriesRepository seriesRepo;
    @Mock private SeriesMapper seriesMapper;
    @InjectMocks private SeriesFacadeImpl facade;

    @Test
    @DisplayName("回傳 uuid → id 對應；查無的 uuid 不出現在結果")
    void findIdsByUuids_returnsMappingForExistingOnly() {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(seriesMapper.findIdsByUuids(anyList()))
                .thenReturn(List.of(new SeriesMapper.SeriesUuidRow(7L, known)));

        Map<UUID, Long> result = facade.findIdsByUuids(List.of(known, unknown));

        assertThat(result).containsExactly(Map.entry(known, 7L));
    }

    @Test
    @DisplayName("null 或空集合 → 回傳空 Map 且完全不查 DB")
    void findIdsByUuids_nullOrEmpty_returnsEmptyWithoutQuery() {
        assertThat(facade.findIdsByUuids(null)).isEmpty();
        assertThat(facade.findIdsByUuids(List.of())).isEmpty();

        verify(seriesMapper, never()).findIdsByUuids(anyList());
        verifyNoInteractions(seriesRepo);
    }

    @Test
    @DisplayName("只含 null 元素 → 視同空輸入，不查 DB")
    void findIdsByUuids_onlyNulls_returnsEmptyWithoutQuery() {
        assertThat(facade.findIdsByUuids(Arrays.asList(null, null))).isEmpty();

        verify(seriesMapper, never()).findIdsByUuids(anyList());
    }

    @Test
    @DisplayName("輸入含重複與 null → 去重且忽略 null 後只查一次")
    void findIdsByUuids_duplicatesAndNulls_deduplicatedSingleQuery() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<List<UUID>> observed = new ArrayList<>();
        when(seriesMapper.findIdsByUuids(anyList())).thenAnswer(invocation -> {
            List<UUID> batch = invocation.getArgument(0);
            observed.add(List.copyOf(batch));
            return List.<SeriesMapper.SeriesUuidRow>of();
        });

        facade.findIdsByUuids(Arrays.asList(a, a, null, b));

        verify(seriesMapper, times(1)).findIdsByUuids(anyList());
        assertThat(observed).hasSize(1);
        assertThat(observed.get(0)).containsExactlyInAnyOrder(a, b);
    }

    @Test
    @DisplayName("超過批次上限 → 經 BatchedQuery 切批，結果合併為單一 Map")
    void findIdsByUuids_overBatchSize_splitsIntoBatches() {
        int total = BatchedQuery.BATCH_SIZE + 100;
        List<UUID> uuids = IntStream.range(0, total).mapToObj(i -> UUID.randomUUID()).toList();
        Map<UUID, Long> idByUuid = new java.util.HashMap<>();
        for (int i = 0; i < total; i++) {
            idByUuid.put(uuids.get(i), (long) (i + 1));
        }
        List<Integer> batchSizes = new ArrayList<>();
        when(seriesMapper.findIdsByUuids(anyList())).thenAnswer(invocation -> {
            List<UUID> batch = invocation.getArgument(0);
            batchSizes.add(batch.size());
            return batch.stream().map(u -> new SeriesMapper.SeriesUuidRow(idByUuid.get(u), u)).toList();
        });

        Map<UUID, Long> result = facade.findIdsByUuids(uuids);

        assertThat(batchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
        assertThat(result).hasSize(total).containsAllEntriesOf(idByUuid);
    }
}
