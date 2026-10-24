package dowob.xyz.blog.module.series.facade;

import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SeriesFacadeImpl} 過渡期 uuid ↔ id 轉換方法測試（設計 §3.1）。
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SeriesFacadeImpl uuid ↔ id 轉換")
class SeriesFacadeUuidLookupTest {

    @Mock private SeriesRepository seriesRepo;
    @Mock private SeriesMapper seriesMapper;
    @InjectMocks private SeriesFacadeImpl facade;

    @Test
    @DisplayName("findIdByUuid → series 存在 → 回傳其主鍵")
    void findIdByUuid_exists_returnsId() {
        UUID uuid = UUID.randomUUID();
        Series series = new Series();
        series.setId(42L);
        series.setUuid(uuid);
        when(seriesRepo.findByUuid(uuid)).thenReturn(Optional.of(series));

        assertThat(facade.findIdByUuid(uuid)).contains(42L);
    }

    @Test
    @DisplayName("findIdByUuid → series 不存在 → 回傳 empty")
    void findIdByUuid_notFound_returnsEmpty() {
        UUID uuid = UUID.randomUUID();
        when(seriesRepo.findByUuid(uuid)).thenReturn(Optional.empty());

        assertThat(facade.findIdByUuid(uuid)).isEmpty();
    }

    @Test
    @DisplayName("findIdByUuid → uuid 為 null → 回傳 empty 且不查 repository")
    void findIdByUuid_null_returnsEmptyWithoutQuery() {
        assertThat(facade.findIdByUuid(null)).isEmpty();

        verifyNoInteractions(seriesRepo);
    }

    @Test
    @DisplayName("findUuidsByIds → 回傳 id → uuid 對應；查無的 id 不出現在結果")
    void findUuidsByIds_returnsMappingForExistingOnly() {
        UUID uuid1 = UUID.randomUUID();
        when(seriesMapper.findUuidsByIds(anyCollection()))
                .thenReturn(List.of(new SeriesMapper.SeriesUuidRow(1L, uuid1)));

        Map<Long, UUID> result = facade.findUuidsByIds(List.of(1L, 2L));

        assertThat(result).containsExactly(Map.entry(1L, uuid1));
    }

    @Test
    @DisplayName("findUuidsByIds → null 或空集合 → 回傳空 Map 且不查詢")
    void findUuidsByIds_nullOrEmpty_returnsEmptyWithoutQuery() {
        assertThat(facade.findUuidsByIds(null)).isEmpty();
        assertThat(facade.findUuidsByIds(List.of())).isEmpty();

        verify(seriesMapper, never()).findUuidsByIds(anyCollection());
    }

    @Test
    @DisplayName("findUuidsByIds → 輸入含重複與 null → 去重且忽略 null 後才查詢")
    void findUuidsByIds_duplicatesAndNulls_deduplicated() {
        List<Collection<Long>> observed = new ArrayList<>();
        when(seriesMapper.findUuidsByIds(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> batch = invocation.getArgument(0);
            observed.add(List.copyOf(batch));
            return List.<SeriesMapper.SeriesUuidRow>of();
        });

        facade.findUuidsByIds(java.util.Arrays.asList(1L, 1L, null, 2L));

        assertThat(observed).hasSize(1);
        assertThat(observed.get(0)).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("findUuidsByIds → 超過批次上限時經 BatchedQuery 切批，結果合併為單一 Map")
    void findUuidsByIds_overBatchSize_splitsIntoBatches() {
        int total = BatchedQuery.BATCH_SIZE + 100;
        List<Long> ids = IntStream.rangeClosed(1, total).mapToObj(Long::valueOf).toList();
        List<Integer> batchSizes = new ArrayList<>();
        when(seriesMapper.findUuidsByIds(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> batch = invocation.getArgument(0);
            batchSizes.add(batch.size());
            return batch.stream().map(id -> new SeriesMapper.SeriesUuidRow(id, UUID.randomUUID())).toList();
        });

        Map<Long, UUID> result = facade.findUuidsByIds(ids);

        assertThat(batchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
        assertThat(result).hasSize(total);
    }
}
