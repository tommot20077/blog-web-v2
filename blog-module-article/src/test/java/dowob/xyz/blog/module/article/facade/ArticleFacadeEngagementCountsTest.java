package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleEngagementCounts;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.ArticleMapper.EngagementRow;
import dowob.xyz.blog.module.article.service.ViewCountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleFacadeImpl#getEngagementCounts} 單元測試（B4：搜尋結果計數與詳情頁同源）。
 *
 * <p>守衛的是「不 N+1」：N 篇只有 1 次 DB 查詢加 1 次瀏覽增量合併（其內部為單次 MGET，見
 * {@code ViewCountServiceTest}）；以及合併邏輯委派給 {@link ViewCountService}，不在 facade 另寫一份。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleFacade.getEngagementCounts 單元測試")
class ArticleFacadeEngagementCountsTest {

    @Mock private ArticleMapper articleMapper;
    @Mock private ViewCountService viewCountService;
    private ArticleFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new ArticleFacadeImpl(articleMapper, null, null, null, null, null, null, null,
                new ArticleDataAssembler(), null, null, null, viewCountService);
    }

    @Test
    @DisplayName("瀏覽數取自 ViewCountService 合併後的值，按讚數取 DB 值")
    void viewCountFromMergedValue_likeCountFromDb() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(articleMapper.findEngagementRowsIn(anyList())).thenReturn(List.of(
                new EngagementRow(a, 100L, 7L), new EngagementRow(b, 0L, 0L)));
        when(viewCountService.addRedisDeltas(anyMap())).thenReturn(Map.of(a, 105L, b, 2L));

        Map<UUID, ArticleEngagementCounts> result = facade.getEngagementCounts(List.of(a, b));

        assertThat(result).containsEntry(a, new ArticleEngagementCounts(105L, 7L))
                .containsEntry(b, new ArticleEngagementCounts(2L, 0L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, Long>> dbViews = ArgumentCaptor.forClass(Map.class);
        verify(viewCountService).addRedisDeltas(dbViews.capture());
        assertThat(dbViews.getValue()).containsEntry(a, 100L).containsEntry(b, 0L);
    }

    @Test
    @DisplayName("N 篇只有 1 次 DB 查詢加 1 次瀏覽增量合併（無 N+1）")
    void nArticles_singleDbQuery_singleMerge() {
        List<UUID> uuids = IntStream.range(0, 50).mapToObj(i -> UUID.randomUUID()).toList();
        when(articleMapper.findEngagementRowsIn(anyList())).thenAnswer(inv -> {
            List<UUID> batch = inv.getArgument(0);
            return batch.stream().map(u -> new EngagementRow(u, 1L, 1L)).toList();
        });
        when(viewCountService.addRedisDeltas(anyMap())).thenAnswer(inv -> inv.getArgument(0));

        Map<UUID, ArticleEngagementCounts> result = facade.getEngagementCounts(uuids);

        assertThat(result).hasSize(50);
        verify(articleMapper, times(1)).findEngagementRowsIn(anyList());
        verify(viewCountService, times(1)).addRedisDeltas(anyMap());
        verify(articleMapper, never()).findViewCountByUuid(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("超過 BATCH_SIZE 時 DB 查詢切批，但瀏覽增量合併仍只呼叫一次")
    void overBatchSize_dbSplit_mergeStillOnce() {
        List<UUID> uuids = IntStream.range(0, BatchedQuery.BATCH_SIZE + 10)
                .mapToObj(i -> UUID.randomUUID()).toList();
        List<Integer> batchSizes = new ArrayList<>();
        when(articleMapper.findEngagementRowsIn(anyList())).thenAnswer(inv -> {
            List<UUID> batch = inv.getArgument(0);
            batchSizes.add(batch.size());
            return batch.stream().map(u -> new EngagementRow(u, 1L, 1L)).toList();
        });
        when(viewCountService.addRedisDeltas(anyMap())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(facade.getEngagementCounts(uuids)).hasSize(BatchedQuery.BATCH_SIZE + 10);

        assertThat(batchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 10);
        verify(viewCountService, times(1)).addRedisDeltas(anyMap());
    }

    @Test
    @DisplayName("DB 查無的文章不出現在結果；null／空輸入不查 DB 也不連 Redis")
    void missingArticleAbsent_emptyInputNoQuery() {
        UUID exists = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(articleMapper.findEngagementRowsIn(anyList())).thenReturn(List.of(new EngagementRow(exists, 3L, 1L)));
        when(viewCountService.addRedisDeltas(anyMap())).thenReturn(Map.of(exists, 3L));

        assertThat(facade.getEngagementCounts(List.of(exists, missing))).containsOnlyKeys(exists);

        org.mockito.Mockito.clearInvocations(articleMapper, viewCountService);
        assertThat(facade.getEngagementCounts(null)).isEmpty();
        assertThat(facade.getEngagementCounts(List.of())).isEmpty();
        verifyNoInteractions(articleMapper, viewCountService);
    }
}
