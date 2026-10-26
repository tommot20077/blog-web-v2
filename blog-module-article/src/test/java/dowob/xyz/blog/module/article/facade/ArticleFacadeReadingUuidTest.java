package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryResponse;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.ArticleMapper.ArticleUuidVisibilityRow;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleFacadeImpl} 供 reading 模組使用的 UUID 版方法單元測試（M2-a）：
 * {@code getSummariesByUuids}、{@code filterReadableUuids}、{@code incrementLikeCount(UUID)}、
 * {@code decrementLikeCount(UUID)}。
 *
 * <p>這裡只驗證 facade 層接線（委派、順序、去重、略過、空輸入不查 DB、切批）；
 * SQL 語意與跨欄位相等由 {@link ArticleFacadeReadingUuidIT} 對真實 PostgreSQL 驗證。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleFacadeImpl reading 用 UUID 版方法")
class ArticleFacadeReadingUuidTest {

    @Mock private ArticleMapper articleMapper;
    @Mock private ObjectProvider<ArticleQueryService> queryServiceProvider;
    @Mock private ArticleQueryService queryService;

    private ArticleFacadeImpl facade;

    /**
     * 手動建構 facade：只提供本測試用到的 mapper 與 ObjectProvider，其餘依賴傳 null。
     */
    @BeforeEach
    void setUp() {
        facade = new ArticleFacadeImpl(articleMapper, null, null, null, null, null, null, null, null,
                null, queryServiceProvider, null, null);
    }

    private static Article article(long id, UUID uuid) {
        Article a = new Article();
        a.setId(id);
        a.setUuid(uuid);
        return a;
    }

    private static ArticleSummaryResponse summary(UUID uuid) {
        return ArticleSummaryResponse.builder().uuid(uuid).title("t-" + uuid).build();
    }

    @Nested
    @DisplayName("getSummariesByUuids")
    class GetSummariesByUuids {

        @Test
        @DisplayName("null 輸入回空清單，不查 DB 也不取得 ArticleQueryService")
        void whenNull_returnsEmptyWithoutQuery() {
            assertThat(facade.getSummariesByUuids(null)).isEmpty();

            verifyNoInteractions(articleMapper, queryServiceProvider, queryService);
        }

        @Test
        @DisplayName("空清單回空清單，不查 DB 也不取得 ArticleQueryService")
        void whenEmpty_returnsEmptyWithoutQuery() {
            assertThat(facade.getSummariesByUuids(List.of())).isEmpty();

            verifyNoInteractions(articleMapper, queryServiceProvider, queryService);
        }

        @Test
        @DisplayName("輸出順序與輸入 uuids 相同，且以同樣順序的 id 呼叫 ArticleQueryService")
        void outputOrderFollowsInputOrder() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            when(articleMapper.findIdsByUuids(List.of(c, a, b)))
                    .thenReturn(List.of(article(1L, a), article(2L, b), article(3L, c)));
            when(queryServiceProvider.getObject()).thenReturn(queryService);
            when(queryService.getArticleSummariesByIds(List.of(3L, 1L, 2L)))
                    .thenReturn(List.of(summary(c), summary(a), summary(b)));

            List<ArticleSummaryResponse> result = facade.getSummariesByUuids(List.of(c, a, b));

            assertThat(result).extracting(ArticleSummaryResponse::getUuid).containsExactly(c, a, b);
        }

        @Test
        @DisplayName("ArticleQueryService 回傳順序不同時，仍以輸入 uuids 的順序輸出")
        void whenQueryServiceReturnsDifferentOrder_stillFollowsInputOrder() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(articleMapper.findIdsByUuids(List.of(a, b)))
                    .thenReturn(List.of(article(1L, a), article(2L, b)));
            when(queryServiceProvider.getObject()).thenReturn(queryService);
            when(queryService.getArticleSummariesByIds(List.of(1L, 2L)))
                    .thenReturn(List.of(summary(b), summary(a)));

            List<ArticleSummaryResponse> result = facade.getSummariesByUuids(List.of(a, b));

            assertThat(result).extracting(ArticleSummaryResponse::getUuid).containsExactly(a, b);
        }

        @Test
        @DisplayName("查無的 uuid 與 null 元素被略過，不影響其餘文章的順序")
        void unknownUuidsAndNullElementsAreSkipped() {
            UUID a = UUID.randomUUID();
            UUID unknown = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            List<UUID> input = new ArrayList<>(List.of(a, unknown, b));
            input.add(null);
            when(articleMapper.findIdsByUuids(List.of(a, unknown, b)))
                    .thenReturn(List.of(article(1L, a), article(2L, b)));
            when(queryServiceProvider.getObject()).thenReturn(queryService);
            when(queryService.getArticleSummariesByIds(List.of(1L, 2L)))
                    .thenReturn(List.of(summary(a), summary(b)));

            List<ArticleSummaryResponse> result = facade.getSummariesByUuids(input);

            assertThat(result).extracting(ArticleSummaryResponse::getUuid).containsExactly(a, b);
        }

        @Test
        @DisplayName("重複的 uuid 只保留第一次出現的位置，且只查一次")
        void duplicateUuidsAreDeduplicatedKeepingFirstOccurrence() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(articleMapper.findIdsByUuids(List.of(a, b)))
                    .thenReturn(List.of(article(1L, a), article(2L, b)));
            when(queryServiceProvider.getObject()).thenReturn(queryService);
            when(queryService.getArticleSummariesByIds(List.of(1L, 2L)))
                    .thenReturn(List.of(summary(a), summary(b)));

            List<ArticleSummaryResponse> result = facade.getSummariesByUuids(List.of(a, b, a));

            assertThat(result).extracting(ArticleSummaryResponse::getUuid).containsExactly(a, b);
        }

        @Test
        @DisplayName("全部查無時回空清單，不呼叫 ArticleQueryService")
        void whenNoneFound_returnsEmptyWithoutCallingQueryService() {
            UUID a = UUID.randomUUID();
            when(articleMapper.findIdsByUuids(List.of(a))).thenReturn(List.of());

            assertThat(facade.getSummariesByUuids(List.of(a))).isEmpty();

            verifyNoInteractions(queryServiceProvider, queryService);
        }

        @Test
        @DisplayName("輸入超過批次上限時 uuid→id 切批查詢，id 順序仍跨批保持")
        void whenInputExceedsBatchLimit_splitsUuidLookupAndKeepsOrder() {
            int total = BatchedQuery.BATCH_SIZE + 100;
            List<UUID> uuids = IntStream.range(0, total).mapToObj(i -> UUID.randomUUID()).toList();
            List<Integer> batchSizes = new ArrayList<>();
            when(articleMapper.findIdsByUuids(anyList())).thenAnswer(invocation -> {
                List<UUID> batch = invocation.getArgument(0);
                batchSizes.add(batch.size());
                return batch.stream().map(u -> article(uuids.indexOf(u) + 1L, u)).toList();
            });
            when(queryServiceProvider.getObject()).thenReturn(queryService);
            when(queryService.getArticleSummariesByIds(anyList())).thenAnswer(invocation -> {
                List<Long> ids = invocation.getArgument(0);
                return ids.stream().map(id -> summary(uuids.get((int) (id - 1)))).toList();
            });

            List<ArticleSummaryResponse> result = facade.getSummariesByUuids(uuids);

            assertThat(batchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
            assertThat(result).extracting(ArticleSummaryResponse::getUuid).containsExactlyElementsOf(uuids);
        }
    }

    @Nested
    @DisplayName("filterReadableUuids")
    class FilterReadableUuids {

        private final UUID author = UUID.randomUUID();
        private final UUID other = UUID.randomUUID();

        @Test
        @DisplayName("null 候選回空清單，不查 DB")
        void whenNull_returnsEmptyWithoutQuery() {
            assertThat(facade.filterReadableUuids(null, author, false)).isEmpty();

            verifyNoInteractions(articleMapper);
        }

        @Test
        @DisplayName("空候選回空清單，不查 DB")
        void whenEmpty_returnsEmptyWithoutQuery() {
            assertThat(facade.filterReadableUuids(List.of(), author, false)).isEmpty();

            verify(articleMapper, never()).findVisibilityRowsByUuids(any());
        }

        @Test
        @DisplayName("匿名（viewerUuid 為 null）只看得到 PUBLISHED")
        void anonymousViewer_seesOnlyPublished() {
            UUID published = UUID.randomUUID();
            UUID draft = UUID.randomUUID();
            when(articleMapper.findVisibilityRowsByUuids(anyCollection())).thenReturn(List.of(
                    new ArticleUuidVisibilityRow(published, "PUBLISHED", author),
                    new ArticleUuidVisibilityRow(draft, "DRAFT", author)));

            assertThat(facade.filterReadableUuids(List.of(published, draft), null, false))
                    .containsExactly(published);
        }

        @Test
        @DisplayName("作者本人看得到自己的非公開文章，看不到他人的")
        void authorViewer_seesOwnNonPublishedOnly() {
            UUID published = UUID.randomUUID();
            UUID ownDraft = UUID.randomUUID();
            UUID othersDraft = UUID.randomUUID();
            when(articleMapper.findVisibilityRowsByUuids(anyCollection())).thenReturn(List.of(
                    new ArticleUuidVisibilityRow(published, "PUBLISHED", other),
                    new ArticleUuidVisibilityRow(ownDraft, "DRAFT", author),
                    new ArticleUuidVisibilityRow(othersDraft, "DRAFT", other)));

            assertThat(facade.filterReadableUuids(List.of(published, ownDraft, othersDraft), author, false))
                    .containsExactly(published, ownDraft);
        }

        @Test
        @DisplayName("ADMIN 看得到全部（含 ARCHIVED 與他人草稿）")
        void adminViewer_seesEverything() {
            UUID archived = UUID.randomUUID();
            UUID othersDraft = UUID.randomUUID();
            when(articleMapper.findVisibilityRowsByUuids(anyCollection())).thenReturn(List.of(
                    new ArticleUuidVisibilityRow(archived, "ARCHIVED", other),
                    new ArticleUuidVisibilityRow(othersDraft, "DRAFT", other)));

            assertThat(facade.filterReadableUuids(List.of(archived, othersDraft), author, true))
                    .containsExactly(archived, othersDraft);
        }

        @Test
        @DisplayName("結果保留輸入順序，不依賴 mapper 回傳順序；查無的 uuid 被略過")
        void resultKeepsInputOrder_andSkipsUnknown() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            UUID unknown = UUID.randomUUID();
            when(articleMapper.findVisibilityRowsByUuids(anyCollection())).thenReturn(List.of(
                    new ArticleUuidVisibilityRow(a, "PUBLISHED", author),
                    new ArticleUuidVisibilityRow(b, "PUBLISHED", author),
                    new ArticleUuidVisibilityRow(c, "PUBLISHED", author)));

            assertThat(facade.filterReadableUuids(List.of(c, unknown, a, b), null, false))
                    .containsExactly(c, a, b);
        }

        @Test
        @DisplayName("作者 uuid 為 null 的文章（作者資料缺失）非公開時任何非 ADMIN 都不可見")
        void articleWithoutAuthor_isNotReadableByNonAdmin() {
            UUID orphanDraft = UUID.randomUUID();
            when(articleMapper.findVisibilityRowsByUuids(anyCollection())).thenReturn(List.of(
                    new ArticleUuidVisibilityRow(orphanDraft, "DRAFT", null)));

            assertThat(facade.filterReadableUuids(List.of(orphanDraft), null, false)).isEmpty();
        }

        @Test
        @DisplayName("重複與 null 候選：查詢只送去重後的 uuid，輸出保留輸入中的重複、丟棄 null")
        void duplicatesAndNulls_queryIsDistinct() {
            UUID a = UUID.randomUUID();
            List<UUID> candidates = new ArrayList<>(List.of(a, a));
            candidates.add(null);
            when(articleMapper.findVisibilityRowsByUuids(List.of(a))).thenReturn(List.of(
                    new ArticleUuidVisibilityRow(a, "PUBLISHED", author)));

            assertThat(facade.filterReadableUuids(candidates, null, false)).containsExactly(a, a);
        }

        @Test
        @DisplayName("輸入超過批次上限時切批查詢，結果合併後仍保留輸入順序")
        void whenInputExceedsBatchLimit_splitsIntoBatchesAndKeepsOrder() {
            int total = BatchedQuery.BATCH_SIZE + 100;
            List<UUID> candidates = IntStream.range(0, total).mapToObj(i -> UUID.randomUUID()).toList();
            List<Integer> batchSizes = new ArrayList<>();
            when(articleMapper.findVisibilityRowsByUuids(anyCollection())).thenAnswer(invocation -> {
                Collection<UUID> batch = invocation.getArgument(0);
                batchSizes.add(batch.size());
                return batch.stream()
                        .map(u -> new ArticleUuidVisibilityRow(u, "PUBLISHED", author))
                        .toList();
            });

            List<UUID> result = facade.filterReadableUuids(candidates, null, false);

            assertThat(batchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
            assertThat(result).containsExactlyElementsOf(candidates);
        }
    }

    @Nested
    @DisplayName("按讚計數 UUID 版")
    class LikeCountByUuid {

        @Test
        @DisplayName("incrementLikeCount(UUID) 委派 mapper 的 UUID 版原子 +1")
        void incrementLikeCountByUuid_delegatesToMapper() {
            UUID uuid = UUID.randomUUID();

            facade.incrementLikeCount(uuid);

            verify(articleMapper).incrementLikeCountByUuid(uuid);
        }

        @Test
        @DisplayName("decrementLikeCount(UUID) 委派 mapper 的 UUID 版原子 -1")
        void decrementLikeCountByUuid_delegatesToMapper() {
            UUID uuid = UUID.randomUUID();

            facade.decrementLikeCount(uuid);

            verify(articleMapper).decrementLikeCountByUuid(uuid);
        }
    }
}
