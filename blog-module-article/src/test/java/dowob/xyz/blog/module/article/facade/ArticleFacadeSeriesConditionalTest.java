package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
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

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleFacadeImpl} 條件式 series 指派／移出的單元測試（M4-b，RACE-14）。
 *
 * <p>驗證：「這次呼叫是否真的改變了歸屬」完全由 mapper 回傳的 affected rows 決定（沒有先讀再寫），
 * series UUID 一律經 {@link SeriesFacade} 換成主鍵，未知 series 的處理與既有 UUID 版
 * {@code updateSeriesAssignment} 一致。與真實 PostgreSQL 的行為（含併發）見 series 模組的
 * {@code ArticleSeriesConditionalAssignmentIT}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleFacadeImpl 條件式 series 指派／移出")
class ArticleFacadeSeriesConditionalTest {

    @Mock private ArticleMapper articleMapper;
    @Mock private ArticleService articleService;
    @Mock private ObjectProvider<SeriesFacade> seriesFacadeProvider;
    @Mock private SeriesFacade seriesFacade;

    private ArticleFacadeImpl facade;

    private static final UUID ARTICLE = UUID.randomUUID();
    private static final UUID SERIES = UUID.randomUUID();
    private static final long SERIES_ID = 10L;

    /** 手動建構 facade 並注入真實的 {@link ArticleDataAssembler}（包裝 mock 的 mapper 與 provider）。 */
    @BeforeEach
    void setUp() {
        facade = new ArticleFacadeImpl(articleMapper, null, null, articleService, null, null, null, null,
                null, new ArticleDataAssembler(new ArticleIdentityResolver(articleMapper, seriesFacadeProvider)),
                null, null, null);
    }

    private void seriesExists() {
        when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
        when(seriesFacade.findIdByUuid(SERIES)).thenReturn(Optional.of(SERIES_ID));
    }

    @Nested
    @DisplayName("assignToSeriesIfUnassigned")
    class AssignToSeriesIfUnassigned {

        @Test
        @DisplayName("mapper 回報 1 列 → true，並以 series 主鍵寫入（只有一次 UPDATE，沒有先讀）")
        void whenOneRowAffected_returnsTrue() {
            seriesExists();
            when(articleMapper.assignToSeriesIfUnassigned(eq(ARTICLE), eq(SERIES_ID), eq(3), any(LocalDateTime.class))).thenReturn(1);

            assertThat(facade.assignToSeriesIfUnassigned(ARTICLE, SERIES, 3)).isTrue();

            verify(articleMapper).assignToSeriesIfUnassigned(eq(ARTICLE), eq(SERIES_ID), eq(3), any(LocalDateTime.class));
            verifyNoInteractions(articleService);
        }

        @Test
        @DisplayName("mapper 回報 0 列（已屬於某 series 或文章不存在）→ false")
        void whenNoRowAffected_returnsFalse() {
            seriesExists();
            when(articleMapper.assignToSeriesIfUnassigned(eq(ARTICLE), eq(SERIES_ID), eq(3), any(LocalDateTime.class))).thenReturn(0);

            assertThat(facade.assignToSeriesIfUnassigned(ARTICLE, SERIES, 3)).isFalse();
        }

        @Test
        @DisplayName("seriesUuid 查無 → IllegalArgumentException，不寫入")
        void unknownSeries_throwsIllegalArgumentAndWritesNothing() {
            when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
            when(seriesFacade.findIdByUuid(SERIES)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> facade.assignToSeriesIfUnassigned(ARTICLE, SERIES, 1))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(articleMapper, never()).assignToSeriesIfUnassigned(any(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("seriesUuid 為 null → IllegalArgumentException，不查 series 也不寫入")
        void nullSeries_throwsIllegalArgument() {
            assertThatThrownBy(() -> facade.assignToSeriesIfUnassigned(ARTICLE, null, 1))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(seriesFacadeProvider, articleMapper);
        }
    }

    @Nested
    @DisplayName("寫入的 updated_at 由 Java 傳入（JVM 時鐘）")
    class UpdatedAtComesFromJvmClock {

        @Test
        @DisplayName("assign／remove／updatePosition 都以 LocalDateTime.now() 範圍內的時間呼叫 mapper")
        void allThreeWrites_passJvmTime() {
            seriesExists();
            when(articleMapper.assignToSeriesIfUnassigned(any(), anyLong(), any(), any())).thenReturn(1);
            when(articleMapper.removeFromSeriesIfAssigned(any(), anyLong(), any())).thenReturn(1);
            when(articleMapper.updateSeriesPositionIfAssigned(any(), anyLong(), any(), any())).thenReturn(1);
            LocalDateTime before = LocalDateTime.now();

            facade.assignToSeriesIfUnassigned(ARTICLE, SERIES, 1);
            facade.removeFromSeriesIfAssigned(ARTICLE, SERIES);
            facade.updateSeriesPositionIfAssigned(ARTICLE, SERIES, 2);

            ArgumentCaptor<LocalDateTime> assignAt = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> removeAt = ArgumentCaptor.forClass(LocalDateTime.class);
            ArgumentCaptor<LocalDateTime> positionAt = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(articleMapper).assignToSeriesIfUnassigned(any(), anyLong(), any(), assignAt.capture());
            verify(articleMapper).removeFromSeriesIfAssigned(any(), anyLong(), removeAt.capture());
            verify(articleMapper).updateSeriesPositionIfAssigned(any(), anyLong(), any(), positionAt.capture());
            LocalDateTime after = LocalDateTime.now();
            assertThat(assignAt.getValue()).isBetween(before, after);
            assertThat(removeAt.getValue()).isBetween(before, after);
            assertThat(positionAt.getValue()).isBetween(before, after);
        }
    }

    @Nested
    @DisplayName("updateSeriesPositionIfAssigned")
    class UpdateSeriesPositionIfAssigned {

        @Test
        @DisplayName("mapper 回報 1 列 → true；0 列（已被移出或移到別處）→ false；只有一次 UPDATE，沒有讀改寫")
        void returnsAffectedRowsAsBoolean() {
            seriesExists();
            when(articleMapper.updateSeriesPositionIfAssigned(eq(ARTICLE), eq(SERIES_ID), eq(7), any(LocalDateTime.class)))
                    .thenReturn(1, 0);

            assertThat(facade.updateSeriesPositionIfAssigned(ARTICLE, SERIES, 7)).isTrue();
            assertThat(facade.updateSeriesPositionIfAssigned(ARTICLE, SERIES, 7)).isFalse();

            verifyNoInteractions(articleService);
        }

        @Test
        @DisplayName("seriesUuid 為 null 或查無 → false，不寫入")
        void nullOrUnknownSeries_returnsFalseWithoutWriting() {
            assertThat(facade.updateSeriesPositionIfAssigned(ARTICLE, null, 1)).isFalse();
            when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
            when(seriesFacade.findIdByUuid(SERIES)).thenReturn(Optional.empty());
            assertThat(facade.updateSeriesPositionIfAssigned(ARTICLE, SERIES, 1)).isFalse();

            verify(articleMapper, never()).updateSeriesPositionIfAssigned(any(), anyLong(), any(), any());
        }
    }

    @Nested
    @DisplayName("removeFromSeriesIfAssigned")
    class RemoveFromSeriesIfAssigned {

        @Test
        @DisplayName("mapper 回報 1 列 → true")
        void whenOneRowAffected_returnsTrue() {
            seriesExists();
            when(articleMapper.removeFromSeriesIfAssigned(eq(ARTICLE), eq(SERIES_ID), any(LocalDateTime.class))).thenReturn(1);

            assertThat(facade.removeFromSeriesIfAssigned(ARTICLE, SERIES)).isTrue();

            verifyNoInteractions(articleService);
        }

        @Test
        @DisplayName("mapper 回報 0 列（不屬於該 series、已被另一個請求移出或文章不存在）→ false")
        void whenNoRowAffected_returnsFalse() {
            seriesExists();
            when(articleMapper.removeFromSeriesIfAssigned(eq(ARTICLE), eq(SERIES_ID), any(LocalDateTime.class))).thenReturn(0);

            assertThat(facade.removeFromSeriesIfAssigned(ARTICLE, SERIES)).isFalse();
        }

        @Test
        @DisplayName("seriesUuid 查無 → false（不可能屬於不存在的 series），不寫入")
        void unknownSeries_returnsFalseWithoutWriting() {
            when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
            when(seriesFacade.findIdByUuid(SERIES)).thenReturn(Optional.empty());

            assertThat(facade.removeFromSeriesIfAssigned(ARTICLE, SERIES)).isFalse();

            verify(articleMapper, never()).removeFromSeriesIfAssigned(any(), anyLong(), any());
        }

        @Test
        @DisplayName("seriesUuid 為 null → false，不查 series 也不寫入")
        void nullSeries_returnsFalseWithoutAnyLookup() {
            assertThat(facade.removeFromSeriesIfAssigned(ARTICLE, null)).isFalse();

            verifyNoInteractions(seriesFacadeProvider, articleMapper);
        }
    }
}
