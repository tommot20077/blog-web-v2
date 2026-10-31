package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleService;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.ArticleRecommendMapper;
import dowob.xyz.blog.module.article.model.ArticleSummaryRow;
import dowob.xyz.blog.module.article.model.ArticleTagRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    @Mock private ArticleService articleService;
    @Mock private ArticleMapper articleMapper;
    @Mock private UserFacade userFacade;
    private ArticleFacadeImpl facade;

    /** 所有測試列共用的作者（M6-a：作者暱稱改由 UserFacade 整頁批次解析） */
    private static final UUID AUTHOR_UUID = UUID.randomUUID();

    /**
     * 手動建構 facade 並注入「真實」的 {@link ArticleDataAssembler}。
     * 不用 {@code @InjectMocks}：它會把 assembler 注入為 null，日後任何測試觸及 findByIds 等方法就會 NPE。
     * 本測試不關心的其餘依賴傳 null（與先前 {@code @InjectMocks} 的行為一致）。
     */
    @BeforeEach
    void setUp() {
        facade = new ArticleFacadeImpl(articleMapper, userFacade, recommendMapper, articleService, null, null, null, null,
                null, new ArticleDataAssembler(), null, null, null);
    }

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
        when(userFacade.getDisplayInfoByUuids(anyCollection())).thenReturn(
                java.util.Map.of(AUTHOR_UUID, new UserDisplayInfo(AUTHOR_UUID, "作者", null)));

        when(recommendMapper.findByUuids(anyList())).thenAnswer(invocation -> {
            List<UUID> batch = invocation.getArgument(0);
            uuidBatchSizes.add(batch.size());
            return batch.stream().map(u -> {
                ArticleSummaryRow row = new ArticleSummaryRow();
                row.setUuid(u.toString());
                row.setTitle("t");
                row.setAuthorUuid(AUTHOR_UUID);
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
        /* 作者暱稱整批只查一次：不因結果被切成多批的文章查詢而重複呼叫，也不隨文章數 N+1 */
        verify(userFacade, times(1)).getDisplayInfoByUuids(anyCollection());
    }

    @Test
    @DisplayName("facade 內的 ArticleDataAssembler 為真實物件：findBySeriesUuidOrderByPosition 不得因 assembler 為 null 而 NPE")
    void findBySeriesUuidOrderByPosition_withRealAssembler_returnsArticleData() {
        Article article = new Article();
        article.setId(1L);
        article.setUuid(UUID.randomUUID());
        article.setAuthorUuid(UUID.randomUUID());
        UUID seriesUuid = UUID.randomUUID();
        when(articleService.findBySeriesUuidOrderByPosition(seriesUuid)).thenReturn(List.of(article));

        List<ArticleData> result = facade.findBySeriesUuidOrderByPosition(seriesUuid);

        assertThat(result).hasSize(1);
    }
}
