package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.article.mapper.ArticleRecommendMapper;
import dowob.xyz.blog.module.article.model.ArticleSummaryRow;
import dowob.xyz.blog.module.article.model.ArticleTagRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * ArticleFacadeImpl 推薦查詢路徑的批次查詢守衛。
 *
 * <p>{@code getPublishedArticlesByUuids} 與其內部的
 * {@code assembleSummaryInfoList} 各有一個 {@code IN (...)}；兩者皆為
 * {@code WHERE ... IN} 投影（後者以 {@code groupingBy} 收尾），屬跨批可合併形狀。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleFacade 推薦路徑批次查詢守衛")
class ArticleFacadeBatchingTest {

    @Mock private ArticleRecommendMapper recommendMapper;
    @InjectMocks private ArticleFacadeImpl facade;

    /** findByUuids 每次收到的批次大小 */
    private final List<Integer> uuidBatchSizes = new ArrayList<>();

    /** findTagsByArticleUuids 每次收到的批次大小 */
    private final List<Integer> tagBatchSizes = new ArrayList<>();

    @Test
    @DisplayName("getPublishedArticlesByUuids 的兩個 IN 查詢都切批")
    void bothInQueriesSplitIntoBatches() {
        int total = BatchedQuery.BATCH_SIZE + 100;
        List<UUID> uuids = IntStream.rangeClosed(1, total)
                .mapToObj(i -> UUID.randomUUID()).toList();

        when(recommendMapper.findByUuids(anyList())).thenAnswer(invocation -> {
            List<UUID> batch = invocation.getArgument(0);
            uuidBatchSizes.add(batch.size());
            return batch.stream().map(u -> {
                ArticleSummaryRow row = new ArticleSummaryRow();
                row.setUuid(u.toString());
                row.setTitle("t");
                return row;
            }).toList();
        });
        when(recommendMapper.findTagsByArticleUuids(anyList())).thenAnswer(invocation -> {
            List<String> batch = invocation.getArgument(0);
            tagBatchSizes.add(batch.size());
            return List.<ArticleTagRow>of();
        });

        List<ArticleSummaryInfo> result = facade.getPublishedArticlesByUuids(uuids);

        assertThat(uuidBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
        assertThat(tagBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
        assertThat(result).hasSize(total);
    }
}
