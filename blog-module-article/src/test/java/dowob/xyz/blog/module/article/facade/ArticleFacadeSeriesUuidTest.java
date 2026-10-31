package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleNavRef;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleFacadeImpl} 的 series UUID 版方法單元測試（M4-a；M6-b 起直接以 {@code series_uuid} 查詢）。
 *
 * <p>驗證：series UUID 直接交給 mapper／service，article 不再經任何 series facade 做 uuid ↔ 主鍵轉換
 * （{@code articles} 已沒有 {@code series_id}）；空輸入不查 DB；批次計數經單一 mapper 呼叫。
 * 與真實 PostgreSQL 的結果見 {@code ArticleMapperSeriesUuidIT}（mapper SQL）與 series 模組的
 * {@code ArticleSeriesUuidParityIT}（facade 對真實 DB）。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleFacadeImpl series UUID 版方法")
class ArticleFacadeSeriesUuidTest {

    @Mock private ArticleMapper articleMapper;
    @Mock private ArticleService articleService;

    private ArticleFacadeImpl facade;

    private static final UUID SERIES_A = UUID.randomUUID();
    private static final UUID SERIES_B = UUID.randomUUID();
    private static final UUID SERIES_UNKNOWN = UUID.randomUUID();

    /**
     * 手動建構 facade 並注入真實的 {@link ArticleDataAssembler}，本測試不關心的依賴傳 null。
     */
    @BeforeEach
    void setUp() {
        facade = new ArticleFacadeImpl(articleMapper, null, null, articleService, null, null, null, null,
                null, new ArticleDataAssembler(), null, null, null);
    }

    private static Article article(long id, UUID seriesUuid, int position) {
        Article a = new Article();
        a.setId(id);
        a.setUuid(UUID.randomUUID());
        a.setAuthorUuid(UUID.randomUUID());
        a.setStatus(ArticleStatus.PUBLISHED);
        a.setSeriesUuid(seriesUuid);
        a.setSeriesPosition(position);
        return a;
    }

    @Nested
    @DisplayName("findBySeriesUuidOrderByPosition")
    class FindBySeriesUuidOrderByPosition {

        @Test
        @DisplayName("以 series uuid 直接查詢，保留 series_position 順序，並帶出 seriesUuid")
        void delegatesWithSeriesUuidAndKeepsOrder() {
            when(articleService.findBySeriesUuidOrderByPosition(SERIES_A))
                    .thenReturn(List.of(article(1L, SERIES_A, 1), article(2L, SERIES_A, 2), article(3L, SERIES_A, 3)));

            List<ArticleData> result = facade.findBySeriesUuidOrderByPosition(SERIES_A);

            assertThat(result).extracting(ArticleData::seriesPosition).containsExactly(1, 2, 3);
            assertThat(result).extracting(ArticleData::seriesUuid).containsOnly(SERIES_A);
        }

        @Test
        @DisplayName("series 沒有任何成員（含系列不存在）→ 回傳空清單")
        void noMembers_returnsEmpty() {
            when(articleService.findBySeriesUuidOrderByPosition(SERIES_UNKNOWN)).thenReturn(List.of());

            assertThat(facade.findBySeriesUuidOrderByPosition(SERIES_UNKNOWN)).isEmpty();
        }

        @Test
        @DisplayName("seriesUuid 為 null → 回傳空清單，不查文章")
        void nullSeriesUuid_returnsEmptyWithoutAnyLookup() {
            assertThat(facade.findBySeriesUuidOrderByPosition(null)).isEmpty();

            verifyNoInteractions(articleService);
        }
    }

    @Nested
    @DisplayName("countPublishedBySeriesUuids")
    class CountPublishedBySeriesUuids {

        @Test
        @DisplayName("null 或空集合 → 回傳空 Map，不查 DB")
        void nullOrEmpty_returnsEmptyWithoutAnyQuery() {
            assertThat(facade.countPublishedBySeriesUuids(null)).isEmpty();
            assertThat(facade.countPublishedBySeriesUuids(List.of())).isEmpty();
            assertThat(facade.countPublishedBySeriesUuids(Arrays.asList((UUID) null))).isEmpty();

            verify(articleMapper, never()).countPublishedBySeriesUuids(anyCollection());
        }

        @Test
        @DisplayName("整批只呼叫一次 mapper，結果以 UUID 為 key 且只含 count > 0（mapper 不回 count = 0 的列）")
        void batch_callsMapperOnceAndKeepsOnlyPositiveCounts() {
            // 只有 series A 有公開文章；series B 存在但 count = 0（mapper 不回該列）
            when(articleMapper.countPublishedBySeriesUuids(anyCollection()))
                    .thenReturn(List.of(new ArticleMapper.SeriesPublishedCountRow(SERIES_A, 3)));

            Map<UUID, Integer> result = facade.countPublishedBySeriesUuids(List.of(SERIES_A, SERIES_B));

            assertThat(result).containsOnlyKeys(SERIES_A).containsEntry(SERIES_A, 3);
            verify(articleMapper, times(1)).countPublishedBySeriesUuids(anyCollection());
        }

        @Test
        @DisplayName("兩個以上 series 都有公開文章 → 各自的 count 配對到正確的 UUID（A=3、B=2）")
        void multiplePositiveSeries_countsAreAssignedToCorrectUuids() {
            when(articleMapper.countPublishedBySeriesUuids(anyCollection())).thenReturn(List.of(
                    new ArticleMapper.SeriesPublishedCountRow(SERIES_B, 2),
                    new ArticleMapper.SeriesPublishedCountRow(SERIES_A, 3)));

            Map<UUID, Integer> result = facade.countPublishedBySeriesUuids(List.of(SERIES_A, SERIES_B));

            assertThat(result).isEqualTo(Map.of(SERIES_A, 3, SERIES_B, 2));
        }

        @Test
        @DisplayName("回傳的 Map 一律可變（空輸入、查無、非空皆同），與舊 Long 版一致")
        void result_isAlwaysMutable() {
            when(articleMapper.countPublishedBySeriesUuids(anyCollection())).thenReturn(List.of());

            Map<UUID, Integer> emptyInput = facade.countPublishedBySeriesUuids(List.of());
            Map<UUID, Integer> noRows = facade.countPublishedBySeriesUuids(List.of(SERIES_UNKNOWN));

            assertThatCode(() -> emptyInput.put(SERIES_A, 1)).doesNotThrowAnyException();
            assertThatCode(() -> noRows.put(SERIES_A, 1)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("輸入含重複與 null → 去重且忽略 null 後才查詢")
        void duplicatesAndNulls_areDeduplicatedBeforeQuerying() {
            when(articleMapper.countPublishedBySeriesUuids(anyCollection())).thenReturn(List.of());

            facade.countPublishedBySeriesUuids(Arrays.asList(SERIES_A, SERIES_A, null));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(articleMapper).countPublishedBySeriesUuids(captor.capture());
            assertThat(captor.getValue()).containsExactly(SERIES_A);
        }

        @Test
        @DisplayName("未知 series UUID → 不出現在結果")
        void unknownSeriesUuid_isAbsentFromResult() {
            when(articleMapper.countPublishedBySeriesUuids(anyCollection()))
                    .thenReturn(List.of(new ArticleMapper.SeriesPublishedCountRow(SERIES_A, 2)));

            Map<UUID, Integer> result = facade.countPublishedBySeriesUuids(List.of(SERIES_A, SERIES_UNKNOWN));

            assertThat(result).containsOnlyKeys(SERIES_A);
        }
    }

    @Nested
    @DisplayName("findPrev / findNextPublishedInSeries")
    class PrevNext {

        private final ArticleNavRef ref = new ArticleNavRef(UUID.randomUUID(), "t", "s");

        @Test
        @DisplayName("prev：以 series uuid 與位置委派 mapper")
        void prev_delegatesToMapper() {
            when(articleMapper.findPrevPublishedInSeries(SERIES_A, 3)).thenReturn(ref);

            assertThat(facade.findPrevPublishedInSeries(SERIES_A, 3)).contains(ref);
        }

        @Test
        @DisplayName("next：以 series uuid 與位置委派 mapper")
        void next_delegatesToMapper() {
            when(articleMapper.findNextPublishedInSeries(SERIES_A, 3)).thenReturn(ref);

            assertThat(facade.findNextPublishedInSeries(SERIES_A, 3)).contains(ref);
        }

        @Test
        @DisplayName("prev／next：邊界（mapper 回 null）→ Optional.empty")
        void boundary_mapperReturnsNull_returnsEmpty() {
            when(articleMapper.findPrevPublishedInSeries(SERIES_A, 1)).thenReturn(null);
            when(articleMapper.findNextPublishedInSeries(SERIES_A, 9)).thenReturn(null);

            assertThat(facade.findPrevPublishedInSeries(SERIES_A, 1)).isEmpty();
            assertThat(facade.findNextPublishedInSeries(SERIES_A, 9)).isEmpty();
        }

        @Test
        @DisplayName("prev／next：seriesUuid 為 null → Optional.empty，不查 DB")
        void nullSeriesUuid_returnsEmptyWithoutQuery() {
            assertThat(facade.findPrevPublishedInSeries((UUID) null, 2)).isEmpty();
            assertThat(facade.findNextPublishedInSeries((UUID) null, 2)).isEmpty();

            verifyNoMoreInteractions(articleMapper);
        }
    }
}
