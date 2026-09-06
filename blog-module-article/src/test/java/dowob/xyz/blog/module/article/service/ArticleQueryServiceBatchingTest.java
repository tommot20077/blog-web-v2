package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse;
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
 * ArticleQueryService enrich 路徑的批次查詢守衛。
 *
 * <p>{@code enrich} 以 {@code findIdsByUuids} 做 uuid → id 的批量轉換，
 * 輸入為一頁 {@code ArticleSummaryResponse}，其上界即分頁 size 上限
 * （{@code PageQuery.MAX_SIZE}），大於 {@link BatchedQuery#BATCH_SIZE}，故必須切批。
 * 該查詢為 {@code WHERE uuid IN} 投影，無排序與聚合，屬跨批可合併形狀。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleQueryService enrich 批次查詢守衛")
class ArticleQueryServiceBatchingTest {

    @Mock private ArticleService articleService;
    @Mock private ArticleMapper articleMapper;
    @InjectMocks private ArticleQueryService queryService;

    /** findIdsByUuids 每次收到的批次大小 */
    private final List<Integer> observedBatchSizes = new ArrayList<>();

    @Test
    @DisplayName("enrich 的 uuid → id 轉換超過批次上限時切批")
    void enrichSplitsUuidLookupIntoBatches() {
        int total = BatchedQuery.BATCH_SIZE + 100;
        List<Long> articleIds = IntStream.rangeClosed(1, total).mapToObj(Long::valueOf).toList();
        List<ArticleSummaryResponse> records = IntStream.rangeClosed(1, total)
                .mapToObj(i -> ArticleSummaryResponse.builder().uuid(UUID.randomUUID()).build())
                .toList();

        when(articleService.getArticleSummariesByIds(anyList())).thenReturn(records);
        when(articleMapper.findIdsByUuids(anyList())).thenAnswer(invocation -> {
            List<UUID> batch = invocation.getArgument(0);
            observedBatchSizes.add(batch.size());
            return List.<Article>of();
        });

        queryService.getArticleSummariesByIds(articleIds, false);

        assertThat(observedBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
    }
}
