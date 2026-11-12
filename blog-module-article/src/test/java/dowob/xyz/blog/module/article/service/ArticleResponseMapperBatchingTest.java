package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.CategoryMapper;
import dowob.xyz.blog.module.article.model.CategoryWithArticleId;
import dowob.xyz.blog.module.article.model.TagWithArticleUuid;
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
 * ArticleResponseMapper 的批次查詢守衛。
 *
 * <p>這兩處的輸入為「一頁文章」的 uuid／id，上界即分頁 size 上限，
 * 大於 {@link BatchedQuery#BATCH_SIZE}，故必須切批。兩者皆以
 * {@code groupingBy} 收尾，屬跨批可合併形狀。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleResponseMapper 批次查詢守衛")
class ArticleResponseMapperBatchingTest {

    @Mock private ArticleMapper articleMapper;
    @Mock private CategoryMapper categoryMapper;
    @InjectMocks private ArticleResponseMapper responseMapper;

    /** 記錄每次 mapper 呼叫收到的批次大小 */
    private final List<Integer> observedBatchSizes = new ArrayList<>();

    @Test
    @DisplayName("batchToTagResponsesMap 超過批次上限時切批")
    void batchToTagResponsesMapSplitsIntoBatches() {
        int total = BatchedQuery.BATCH_SIZE + 100;
        List<UUID> uuids = IntStream.rangeClosed(1, total)
                .mapToObj(i -> UUID.randomUUID()).toList();
        when(articleMapper.findTagsByArticleUuids(anyList())).thenAnswer(invocation -> {
            List<UUID> batch = invocation.getArgument(0);
            observedBatchSizes.add(batch.size());
            return List.<TagWithArticleUuid>of();
        });

        responseMapper.batchToTagResponsesMap(uuids);

        assertThat(observedBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
    }

    @Test
    @DisplayName("batchToCategoryResponsesMap 超過批次上限時切批")
    void batchToCategoryResponsesMapSplitsIntoBatches() {
        int total = BatchedQuery.BATCH_SIZE + 100;
        List<Long> ids = IntStream.rangeClosed(1, total).mapToObj(Long::valueOf).toList();
        when(categoryMapper.findCategoriesByArticleIds(anyList())).thenAnswer(invocation -> {
            List<Long> batch = invocation.getArgument(0);
            observedBatchSizes.add(batch.size());
            return List.<CategoryWithArticleId>of();
        });

        responseMapper.batchToCategoryResponsesMap(ids);

        assertThat(observedBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
    }
}
