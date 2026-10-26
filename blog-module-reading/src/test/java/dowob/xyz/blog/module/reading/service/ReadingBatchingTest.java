package dowob.xyz.blog.module.reading.service;

import dowob.xyz.blog.common.api.request.PageQuery;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.reading.mapper.ArticleLikeMapper;
import dowob.xyz.blog.module.reading.mapper.BookmarkMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * reading 模組的跨模組批次查詢守衛。
 *
 * <p>
 * 驗證 {@code batchIsBookmarked} / {@code batchIsLiked} 的 {@code IN (...)}
 * 經 {@link BatchedQuery} 切批。這兩處的輸入為「一頁文章的 uuid」，其上界即
 * {@link PageQuery#MAX_SIZE}——大於 {@link BatchedQuery#BATCH_SIZE}，故必須切批。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("reading 模組批次查詢守衛")
class ReadingBatchingTest {

    @Mock private BookmarkMapper bookmarkMapper;
    @Mock private ArticleLikeMapper articleLikeMapper;

    @InjectMocks private BookmarkService bookmarkService;
    @InjectMocks private ArticleLikeService articleLikeService;

    /** 記錄每次 mapper 呼叫收到的批次大小 */
    private final List<Integer> observedBatchSizes = new ArrayList<>();

    /** 查詢使用者 */
    private final UUID userUuid = UUID.randomUUID();

    /**
     * 產生 count 個互不相同的文章 uuid 清單。
     *
     * @param count 筆數
     * @return uuid 清單
     */
    private List<UUID> uuids(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
    }

    @Test
    @DisplayName("batchIsBookmarked 超過批次上限時切批，結果為各批聯集")
    void batchIsBookmarkedSplitsIntoBatches() {
        int total = BatchedQuery.BATCH_SIZE + 100;
        when(bookmarkMapper.findBookmarkedArticleUuidsByUser(eq(userUuid), anyList()))
                .thenAnswer(invocation -> {
                    List<UUID> batch = invocation.getArgument(1);
                    observedBatchSizes.add(batch.size());
                    return List.copyOf(batch);
                });

        Set<UUID> result = bookmarkService.batchIsBookmarked(userUuid, uuids(total));

        assertThat(observedBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
        assertThat(result).hasSize(total);
    }

    @Test
    @DisplayName("batchIsLiked 超過批次上限時切批，結果為各批聯集")
    void batchIsLikedSplitsIntoBatches() {
        int total = BatchedQuery.BATCH_SIZE + 100;
        when(articleLikeMapper.findLikedArticleUuidsByUser(eq(userUuid), anyList()))
                .thenAnswer(invocation -> {
                    List<UUID> batch = invocation.getArgument(1);
                    observedBatchSizes.add(batch.size());
                    return List.copyOf(batch);
                });

        Set<UUID> result = articleLikeService.batchIsLiked(userUuid, uuids(total));

        assertThat(observedBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
        assertThat(result).hasSize(total);
    }
}
