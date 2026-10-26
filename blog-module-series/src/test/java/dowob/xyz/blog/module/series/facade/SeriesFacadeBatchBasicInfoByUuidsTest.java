package dowob.xyz.blog.module.series.facade;

import dowob.xyz.blog.infrastructure.facade.dto.SeriesBasicInfo;
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
 * {@link SeriesFacadeImpl#batchGetSeriesBasicInfoByUuids} 單元測試（M6-b：article 以 series UUID 取基本資訊）。
 *
 * <p>驗證回傳以 UUID 為 key、查無者不出現、null／空輸入不查 DB、去重、超過批次上限時切批。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SeriesFacadeImpl.batchGetSeriesBasicInfoByUuids")
class SeriesFacadeBatchBasicInfoByUuidsTest {

    @Mock private SeriesRepository seriesRepo;
    @Mock private SeriesMapper seriesMapper;
    @InjectMocks private SeriesFacadeImpl facade;

    @Test
    @DisplayName("回傳 uuid → 基本資訊；查無的 uuid 不出現在結果")
    void returnsInfoKeyedByUuid_unknownOmitted() {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(seriesMapper.findBasicInfoBySeriesUuids(anyList()))
                .thenReturn(List.of(new SeriesMapper.SeriesBasicRow(7L, known, "標題")));

        Map<UUID, SeriesBasicInfo> result = facade.batchGetSeriesBasicInfoByUuids(List.of(known, unknown));

        assertThat(result).containsOnlyKeys(known);
        assertThat(result.get(known).getSeriesUuid()).isEqualTo(known);
        assertThat(result.get(known).getSeriesTitle()).isEqualTo("標題");
    }

    @Test
    @DisplayName("null 或空集合 → 回傳空 Map 且完全不查 DB")
    void nullOrEmpty_returnsEmptyWithoutQuery() {
        assertThat(facade.batchGetSeriesBasicInfoByUuids(null)).isEmpty();
        assertThat(facade.batchGetSeriesBasicInfoByUuids(List.of())).isEmpty();

        verify(seriesMapper, never()).findBasicInfoBySeriesUuids(anyList());
        verifyNoInteractions(seriesRepo);
    }

    @Test
    @DisplayName("只含 null 元素 → 視同空輸入，不查 DB")
    void onlyNulls_returnsEmptyWithoutQuery() {
        assertThat(facade.batchGetSeriesBasicInfoByUuids(Arrays.asList(null, null))).isEmpty();

        verify(seriesMapper, never()).findBasicInfoBySeriesUuids(anyList());
    }

    @Test
    @DisplayName("輸入含重複與 null → 去重且忽略 null 後只查一次")
    void duplicatesAndNulls_deduplicatedSingleQuery() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<List<UUID>> observed = new ArrayList<>();
        when(seriesMapper.findBasicInfoBySeriesUuids(anyList())).thenAnswer(invocation -> {
            List<UUID> batch = invocation.getArgument(0);
            observed.add(List.copyOf(batch));
            return List.<SeriesMapper.SeriesBasicRow>of();
        });

        facade.batchGetSeriesBasicInfoByUuids(Arrays.asList(a, a, null, b));

        verify(seriesMapper, times(1)).findBasicInfoBySeriesUuids(anyList());
        assertThat(observed).hasSize(1);
        assertThat(observed.get(0)).containsExactlyInAnyOrder(a, b);
    }

    @Test
    @DisplayName("超過批次上限 → 經 BatchedQuery 切批，結果合併為單一 Map")
    void overBatchSize_splitsIntoBatches() {
        int total = BatchedQuery.BATCH_SIZE + 100;
        List<UUID> uuids = IntStream.range(0, total).mapToObj(i -> UUID.randomUUID()).toList();
        List<Integer> batchSizes = new ArrayList<>();
        when(seriesMapper.findBasicInfoBySeriesUuids(anyList())).thenAnswer(invocation -> {
            List<UUID> batch = invocation.getArgument(0);
            batchSizes.add(batch.size());
            return batch.stream().map(u -> new SeriesMapper.SeriesBasicRow(1L, u, "t-" + u)).toList();
        });

        Map<UUID, SeriesBasicInfo> result = facade.batchGetSeriesBasicInfoByUuids(uuids);

        assertThat(batchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
        assertThat(result).hasSize(total);
    }
}
