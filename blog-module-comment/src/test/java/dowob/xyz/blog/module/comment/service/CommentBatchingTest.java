package dowob.xyz.blog.module.comment.service;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.comment.mapper.CommentMapper;
import dowob.xyz.blog.module.comment.model.CommentWithAuthor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * CommentService 留言列表的批次查詢守衛。
 *
 * <p>本路徑有兩個無上界的 {@code IN (...)}：{@code findRepliesByParentIds}
 * 的輸入為一頁 top-level 留言 id，{@code findLikedCommentIdsByUser} 的輸入是
 * <b>top-level ＋ 全部 replies</b>——後者可遠大於頁大小，是本 repo 最需要切批的一處。</p>
 *
 * <p>{@code findRepliesByParentIds} 帶 {@code ORDER BY c.parent_id, c.created_at}，
 * 但呼叫端以 {@code groupingBy(parentId)} 消費，且同一 parent 的 replies 必落在同一批，
 * 故「同 parent 內按 created_at」這個唯一被消費的順序性質在切批後仍成立。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommentService 批次查詢守衛")
class CommentBatchingTest {

    @Mock private CommentMapper commentMapper;
    @Mock private ArticleFacade articleFacade;
    @Mock private CommentMarkdownRenderer renderer;
    @InjectMocks private CommentService commentService;

    /** findRepliesByParentIds 每次收到的批次大小 */
    private final List<Integer> replyBatchSizes = new ArrayList<>();

    /** findLikedCommentIdsByUser 每次收到的批次大小 */
    private final List<Integer> likedBatchSizes = new ArrayList<>();

    /**
     * 建立指定 id 的 top-level 留言列。
     *
     * @param id 留言主鍵
     * @return 留言列
     */
    private CommentWithAuthor topLevel(long id) {
        CommentWithAuthor c = new CommentWithAuthor();
        c.setId(id);
        return c;
    }

    @Test
    @DisplayName("replies 與 liked 兩個 IN 查詢都切批")
    void bothInQueriesSplitIntoBatches() {
        int total = BatchedQuery.BATCH_SIZE + 100;
        UUID articleUuid = UUID.randomUUID();

        when(articleFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                new ArticleData(1L, articleUuid, 9L, "PUBLISHED", null, null)));
        when(commentMapper.findTopLevelByArticle(anyLong(), anyString(), anyInt(), anyInt()))
                .thenReturn(IntStream.rangeClosed(1, total).mapToObj(i -> topLevel(i)).toList());
        when(commentMapper.findRepliesByParentIds(anyList())).thenAnswer(invocation -> {
            List<Long> batch = invocation.getArgument(0);
            replyBatchSizes.add(batch.size());
            return List.<CommentWithAuthor>of();
        });
        when(commentMapper.findLikedCommentIdsByUser(eq(5L), anyList())).thenAnswer(invocation -> {
            List<Long> batch = invocation.getArgument(1);
            likedBatchSizes.add(batch.size());
            return List.<Long>of();
        });

        commentService.listComments(articleUuid, 5L, false, "newest", 1, total);

        assertThat(replyBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
        assertThat(likedBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
    }
}
