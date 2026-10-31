package dowob.xyz.blog.module.series.facade;

import dowob.xyz.blog.infrastructure.facade.dto.SeriesBasicInfo;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * series 模組的跨模組批次查詢守衛。
 *
 * <p>驗證 {@code batchGetSeriesBasicInfoByUuids} 的 {@code IN (...)} 經
 * {@link BatchedQuery} 切批；各批 key 不重疊，屬跨批可合併形狀。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("series 模組批次查詢守衛")
class SeriesBatchingTest {

    @Mock private SeriesMapper seriesMapper;
    @InjectMocks private SeriesFacadeImpl facade;

    /** 記錄每次 mapper 呼叫收到的批次大小 */
    private final List<Integer> observedBatchSizes = new ArrayList<>();

    @Test
    @DisplayName("batchGetSeriesBasicInfoByUuids 超過批次上限時切批，結果合併為單一 map")
    void batchGetSeriesBasicInfoByUuidsSplitsIntoBatches() {
        int total = BatchedQuery.BATCH_SIZE + 100;
        List<UUID> seriesUuids = IntStream.rangeClosed(1, total).mapToObj(i -> UUID.randomUUID()).toList();
        when(seriesMapper.findBasicInfoBySeriesUuids(anyList())).thenAnswer(invocation -> {
            List<UUID> batch = invocation.getArgument(0);
            observedBatchSizes.add(batch.size());
            return batch.stream()
                    .map(uuid -> new SeriesMapper.SeriesBasicRow(1L, uuid, "s-" + uuid))
                    .toList();
        });

        Map<UUID, SeriesBasicInfo> result = facade.batchGetSeriesBasicInfoByUuids(seriesUuids);

        assertThat(observedBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
        assertThat(result).hasSize(total);
    }
}
