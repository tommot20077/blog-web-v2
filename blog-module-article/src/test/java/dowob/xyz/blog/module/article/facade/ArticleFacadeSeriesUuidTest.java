package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleNavRef;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleIdentityResolver;
import dowob.xyz.blog.module.article.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleFacadeImpl} 的 series UUID 版方法單元測試（M4-a）。
 *
 * <p>驗證：series UUID → 主鍵一律經 {@link SeriesFacade}（批次者只呼叫一次 {@code findIdsByUuids}，
 * 不逐筆呼叫 {@code findIdByUuid}）、空輸入不查 DB、未知 UUID 的行為與 Long 版遇到不存在 series id 一致。
 * 與真實 PostgreSQL 的結果對照（UUID 版 = Long 版）見 series 模組的 {@code ArticleSeriesUuidParityIT}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleFacadeImpl series UUID 版方法")
class ArticleFacadeSeriesUuidTest {

    @Mock private ArticleMapper articleMapper;
    @Mock private ArticleService articleService;
    @Mock private ObjectProvider<SeriesFacade> seriesFacadeProvider;
    @Mock private SeriesFacade seriesFacade;

    private ArticleFacadeImpl facade;

    private static final UUID SERIES_A = UUID.randomUUID();
    private static final UUID SERIES_B = UUID.randomUUID();
    private static final UUID SERIES_UNKNOWN = UUID.randomUUID();

    /**
     * 手動建構 facade 並注入真實的 {@link ArticleDataAssembler}（包裝 mock 的 mapper 與 provider），
     * 本測試不關心的依賴傳 null。
     */
    @BeforeEach
    void setUp() {
        facade = new ArticleFacadeImpl(articleMapper, null, null, articleService, null, null, null, null,
                null, new ArticleDataAssembler(new ArticleIdentityResolver(seriesFacadeProvider)),
                null, null, null);
    }

    /** 讓 provider 回傳 mock 的 SeriesFacade（只在測試確實會取用時才呼叫，避免 strict stubs 報未使用） */
    private void seriesFacadeAvailable() {
        when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
    }

    private static Article article(long id, long seriesId, int position) {
        Article a = new Article();
        a.setId(id);
        a.setUuid(UUID.randomUUID());
        a.setAuthorUuid(UUID.randomUUID());
        a.setStatus(ArticleStatus.PUBLISHED);
        a.setSeriesId(seriesId);
        a.setSeriesPosition(position);
        return a;
    }

    @Nested
    @DisplayName("findBySeriesUuidOrderByPosition")
    class FindBySeriesUuidOrderByPosition {

        @Test
        @DisplayName("series 存在 → 以其主鍵查詢，保留 series_position 順序，並帶出 seriesUuid")
        void knownSeries_delegatesWithResolvedIdAndKeepsOrder() {
            seriesFacadeAvailable();
            when(seriesFacade.findIdByUuid(SERIES_A)).thenReturn(Optional.of(10L));
            when(articleService.findBySeriesIdOrderByPosition(10L))
                    .thenReturn(List.of(article(1L, 10L, 1), article(2L, 10L, 2), article(3L, 10L, 3)));
            when(seriesFacade.findUuidsByIds(anyCollection())).thenReturn(Map.of(10L, SERIES_A));

            List<ArticleData> result = facade.findBySeriesUuidOrderByPosition(SERIES_A);

            assertThat(result).extracting(ArticleData::id).containsExactly(1L, 2L, 3L);
            assertThat(result).extracting(ArticleData::seriesUuid).containsOnly(SERIES_A);
        }

        @Test
        @DisplayName("series 不存在 → 回傳空清單（與 Long 版查無 series id 相同），不查文章")
        void unknownSeries_returnsEmptyWithoutQueryingArticles() {
            seriesFacadeAvailable();
            when(seriesFacade.findIdByUuid(SERIES_UNKNOWN)).thenReturn(Optional.empty());

            assertThat(facade.findBySeriesUuidOrderByPosition(SERIES_UNKNOWN)).isEmpty();

            verify(articleService, never()).findBySeriesIdOrderByPosition(anyLong());
        }

        @Test
        @DisplayName("seriesUuid 為 null → 回傳空清單，不取用 SeriesFacade 也不查文章")
        void nullSeriesUuid_returnsEmptyWithoutAnyLookup() {
            assertThat(facade.findBySeriesUuidOrderByPosition(null)).isEmpty();

            verifyNoInteractions(seriesFacadeProvider, articleService);
        }
    }

    @Nested
    @DisplayName("countPublishedBySeriesUuids")
    class CountPublishedBySeriesUuids {

        @Test
        @DisplayName("null 或空集合 → 回傳空 Map，不取用 SeriesFacade 也不查 DB")
        void nullOrEmpty_returnsEmptyWithoutAnyQuery() {
            assertThat(facade.countPublishedBySeriesUuids(null)).isEmpty();
            assertThat(facade.countPublishedBySeriesUuids(List.of())).isEmpty();
            assertThat(facade.countPublishedBySeriesUuids(Arrays.asList((UUID) null))).isEmpty();

            verifyNoInteractions(seriesFacadeProvider);
            verify(articleMapper, never()).countPublishedBySeriesIds(anyCollection());
        }

        @Test
        @DisplayName("整批只呼叫一次 findIdsByUuids（不逐筆 findIdByUuid），結果以 UUID 為 key 且只含 count > 0")
        void batch_callsFindIdsByUuidsOnceAndKeepsOnlyPositiveCounts() {
            seriesFacadeAvailable();
            when(seriesFacade.findIdsByUuids(anyCollection())).thenReturn(Map.of(SERIES_A, 10L, SERIES_B, 20L));
            // 只有 series 10 有公開文章；series 20 存在但 count = 0（mapper 不回該列）
            when(articleMapper.countPublishedBySeriesIds(anyCollection()))
                    .thenReturn(List.of(new ArticleMapper.SeriesPublishedCountRow(10L, 3)));

            Map<UUID, Integer> result = facade.countPublishedBySeriesUuids(List.of(SERIES_A, SERIES_B));

            assertThat(result).containsOnlyKeys(SERIES_A).containsEntry(SERIES_A, 3);
            verify(seriesFacade, times(1)).findIdsByUuids(anyCollection());
            verify(seriesFacade, never()).findIdByUuid(any());
            verify(articleMapper, times(1)).countPublishedBySeriesIds(anyCollection());
        }

        @Test
        @DisplayName("兩個以上 series 都有公開文章 → 各自的 count 配對到正確的 UUID（A=3、B=2）")
        void multiplePositiveSeries_countsAreAssignedToCorrectUuids() {
            seriesFacadeAvailable();
            when(seriesFacade.findIdsByUuids(anyCollection())).thenReturn(Map.of(SERIES_A, 10L, SERIES_B, 20L));
            when(articleMapper.countPublishedBySeriesIds(anyCollection())).thenReturn(List.of(
                    new ArticleMapper.SeriesPublishedCountRow(20L, 2),
                    new ArticleMapper.SeriesPublishedCountRow(10L, 3)));

            Map<UUID, Integer> result = facade.countPublishedBySeriesUuids(List.of(SERIES_A, SERIES_B));

            assertThat(result).isEqualTo(Map.of(SERIES_A, 3, SERIES_B, 2));
        }

        @Test
        @DisplayName("回傳的 Map 一律可變（空輸入、全未知、非空皆同），與 Long 版一致")
        void result_isAlwaysMutableLikeLongVersion() {
            seriesFacadeAvailable();
            when(seriesFacade.findIdsByUuids(anyCollection())).thenReturn(Map.of());

            Map<UUID, Integer> emptyInput = facade.countPublishedBySeriesUuids(List.of());
            Map<UUID, Integer> allUnknown = facade.countPublishedBySeriesUuids(List.of(SERIES_UNKNOWN));

            assertThatCode(() -> emptyInput.put(SERIES_A, 1)).doesNotThrowAnyException();
            assertThatCode(() -> allUnknown.put(SERIES_A, 1)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("輸入含重複與 null → 去重且忽略 null 後才換主鍵")
        void duplicatesAndNulls_areDeduplicatedBeforeResolving() {
            seriesFacadeAvailable();
            when(seriesFacade.findIdsByUuids(anyCollection())).thenReturn(Map.of(SERIES_A, 10L));
            when(articleMapper.countPublishedBySeriesIds(anyCollection())).thenReturn(List.of());

            facade.countPublishedBySeriesUuids(Arrays.asList(SERIES_A, SERIES_A, null));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(seriesFacade).findIdsByUuids(captor.capture());
            assertThat(captor.getValue()).containsExactly(SERIES_A);
        }

        @Test
        @DisplayName("未知 series UUID → 不出現在結果（與 Long 版查無 series id 相同）")
        void unknownSeriesUuid_isAbsentFromResult() {
            seriesFacadeAvailable();
            when(seriesFacade.findIdsByUuids(anyCollection())).thenReturn(Map.of(SERIES_A, 10L));
            when(articleMapper.countPublishedBySeriesIds(anyCollection()))
                    .thenReturn(List.of(new ArticleMapper.SeriesPublishedCountRow(10L, 2)));

            Map<UUID, Integer> result = facade.countPublishedBySeriesUuids(List.of(SERIES_A, SERIES_UNKNOWN));

            assertThat(result).containsOnlyKeys(SERIES_A);
        }

        @Test
        @DisplayName("全部 UUID 都未知 → 回傳空 Map，不查文章計數")
        void allUnknown_returnsEmptyWithoutCounting() {
            seriesFacadeAvailable();
            when(seriesFacade.findIdsByUuids(anyCollection())).thenReturn(Map.of());

            assertThat(facade.countPublishedBySeriesUuids(List.of(SERIES_UNKNOWN))).isEmpty();

            verify(articleMapper, never()).countPublishedBySeriesIds(anyCollection());
        }
    }

    @Nested
    @DisplayName("findPrev / findNextPublishedInSeries")
    class PrevNext {

        private final ArticleNavRef ref = new ArticleNavRef(UUID.randomUUID(), "t", "s");

        @Test
        @DisplayName("prev：series 存在 → 以主鍵與位置委派 mapper")
        void prev_knownSeries_delegatesToMapper() {
            seriesFacadeAvailable();
            when(seriesFacade.findIdByUuid(SERIES_A)).thenReturn(Optional.of(10L));
            when(articleMapper.findPrevPublishedInSeries(10L, 3)).thenReturn(ref);

            assertThat(facade.findPrevPublishedInSeries(SERIES_A, 3)).contains(ref);
        }

        @Test
        @DisplayName("next：series 存在 → 以主鍵與位置委派 mapper")
        void next_knownSeries_delegatesToMapper() {
            seriesFacadeAvailable();
            when(seriesFacade.findIdByUuid(SERIES_A)).thenReturn(Optional.of(10L));
            when(articleMapper.findNextPublishedInSeries(10L, 3)).thenReturn(ref);

            assertThat(facade.findNextPublishedInSeries(SERIES_A, 3)).contains(ref);
        }

        @Test
        @DisplayName("prev／next：邊界（mapper 回 null）→ Optional.empty")
        void boundary_mapperReturnsNull_returnsEmpty() {
            seriesFacadeAvailable();
            when(seriesFacade.findIdByUuid(SERIES_A)).thenReturn(Optional.of(10L));
            when(articleMapper.findPrevPublishedInSeries(10L, 1)).thenReturn(null);
            when(articleMapper.findNextPublishedInSeries(10L, 9)).thenReturn(null);

            assertThat(facade.findPrevPublishedInSeries(SERIES_A, 1)).isEmpty();
            assertThat(facade.findNextPublishedInSeries(SERIES_A, 9)).isEmpty();
        }

        @Test
        @DisplayName("prev／next：series 不存在 → Optional.empty，不查文章")
        void unknownSeries_returnsEmptyWithoutQueryingArticles() {
            seriesFacadeAvailable();
            when(seriesFacade.findIdByUuid(SERIES_UNKNOWN)).thenReturn(Optional.empty());

            assertThat(facade.findPrevPublishedInSeries(SERIES_UNKNOWN, 2)).isEmpty();
            assertThat(facade.findNextPublishedInSeries(SERIES_UNKNOWN, 2)).isEmpty();

            verify(articleMapper, never()).findPrevPublishedInSeries(anyLong(), anyInt());
            verify(articleMapper, never()).findNextPublishedInSeries(anyLong(), anyInt());
        }

        @Test
        @DisplayName("prev／next：seriesUuid 為 null → Optional.empty，不取用 SeriesFacade")
        void nullSeriesUuid_returnsEmptyWithoutLookup() {
            assertThat(facade.findPrevPublishedInSeries((UUID) null, 2)).isEmpty();
            assertThat(facade.findNextPublishedInSeries((UUID) null, 2)).isEmpty();

            verifyNoInteractions(seriesFacadeProvider);
            verifyNoMoreInteractions(articleMapper);
        }
    }

    @Nested
    @DisplayName("updateSeriesAssignment(UUID, UUID, Integer)")
    class UpdateSeriesAssignment {

        private final UUID articleUuid = UUID.randomUUID();

        @Test
        @DisplayName("文章與 series 皆存在 → 以兩個主鍵委派 Long 版寫入")
        void bothKnown_delegatesWithResolvedIds() {
            seriesFacadeAvailable();
            when(articleService.findIdByUuid(articleUuid)).thenReturn(5L);
            when(seriesFacade.findIdByUuid(SERIES_A)).thenReturn(Optional.of(10L));

            facade.updateSeriesAssignment(articleUuid, SERIES_A, 4);

            verify(articleService).updateSeriesAssignment(5L, 10L, 4);
        }

        @Test
        @DisplayName("seriesUuid 為 null → 移出 series：series 欄位寫 null，不取用 SeriesFacade")
        void nullSeriesUuid_clearsAssignmentWithoutSeriesLookup() {
            when(articleService.findIdByUuid(articleUuid)).thenReturn(5L);

            facade.updateSeriesAssignment(articleUuid, null, null);

            verify(articleService).updateSeriesAssignment(5L, null, null);
            verifyNoInteractions(seriesFacadeProvider);
        }

        @Test
        @DisplayName("文章不存在 → no-op（與 Long 版相同），不檢查 series、不寫入")
        void unknownArticle_isNoOp() {
            when(articleService.findIdByUuid(articleUuid)).thenReturn(null);

            facade.updateSeriesAssignment(articleUuid, SERIES_A, 1);

            verify(articleService, never()).updateSeriesAssignment(anyLong(), any(), any());
            verifyNoInteractions(seriesFacadeProvider);
        }

        @Test
        @DisplayName("series 不存在 → 拋 IllegalArgumentException（呼叫端程式錯誤，非 BusinessException），寫入前即拒絕")
        void unknownSeries_throwsIllegalArgument() {
            seriesFacadeAvailable();
            when(articleService.findIdByUuid(articleUuid)).thenReturn(5L);
            when(seriesFacade.findIdByUuid(SERIES_UNKNOWN)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> facade.updateSeriesAssignment(articleUuid, SERIES_UNKNOWN, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(SERIES_UNKNOWN.toString());

            verify(articleService, never()).updateSeriesAssignment(eq(5L), any(), any());
        }
    }
}
