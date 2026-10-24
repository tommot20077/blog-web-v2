package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleIdentityResolver} 單元測試。
 *
 * <p>作者與 series 的 uuid 一律<b>批次</b>取得；SeriesFacade 以 {@link ObjectProvider} 延遲取得，
 * 只在確實有 series id 要轉換時才取用（打斷 SeriesFacadeImpl → ArticleFacade 的 bean 循環）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleIdentityResolver 單元測試")
class ArticleIdentityResolverTest {

    @Mock private ArticleMapper articleMapper;
    @Mock private ObjectProvider<SeriesFacade> seriesFacadeProvider;
    @Mock private SeriesFacade seriesFacade;

    private ArticleIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ArticleIdentityResolver(articleMapper, seriesFacadeProvider);
    }

    @Test
    @DisplayName("作者數超過批次上限 → 經 BatchedQuery 切批，且每批不超過上限")
    void resolveAuthorUuids_overBatchSize_splitsIntoBatches() {
        int total = BatchedQuery.BATCH_SIZE + 100;
        List<Long> authorIds = IntStream.rangeClosed(1, total).mapToObj(Long::valueOf).toList();
        List<Integer> batchSizes = new ArrayList<>();
        when(articleMapper.findUserUuidsByIds(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> batch = invocation.getArgument(0);
            batchSizes.add(batch.size());
            return batch.stream().map(id -> new ArticleMapper.UserUuidRow(id, UUID.randomUUID())).toList();
        });

        Map<Long, UUID> result = resolver.resolveAuthorUuids(authorIds);

        assertThat(batchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
        assertThat(result).hasSize(total);
    }

    @Test
    @DisplayName("resolveAuthorUuids → null 與空集合不發查詢")
    void resolveAuthorUuids_nullAndEmpty_noQuery() {
        assertThat(resolver.resolveAuthorUuids(null)).isEmpty();
        assertThat(resolver.resolveAuthorUuids(List.of())).isEmpty();

        verify(articleMapper, never()).findUserUuidsByIds(anyCollection());
    }

    @Test
    @DisplayName("resolveAuthorUuids → 去重並忽略 null 後只送出一次查詢")
    void resolveAuthorUuids_duplicatesAndNulls_sentOnceDistinct() {
        UUID uuid = UUID.randomUUID();
        when(articleMapper.findUserUuidsByIds(anyCollection()))
                .thenReturn(List.of(new ArticleMapper.UserUuidRow(7L, uuid)));

        Map<Long, UUID> result = resolver.resolveAuthorUuids(Arrays.asList(7L, null, 7L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(articleMapper).findUserUuidsByIds(captor.capture());
        assertThat(captor.getValue()).containsExactly(7L);
        assertThat(result).containsEntry(7L, uuid);
        verifyNoInteractions(seriesFacadeProvider);
    }

    @Test
    @DisplayName("resolveSeriesUuids → null／空集合／只有 null 時不取用 SeriesFacade")
    void resolveSeriesUuids_nothingToResolve_seriesFacadeUntouched() {
        assertThat(resolver.resolveSeriesUuids(null)).isEmpty();
        assertThat(resolver.resolveSeriesUuids(List.of())).isEmpty();
        assertThat(resolver.resolveSeriesUuids(Arrays.asList((Long) null))).isEmpty();

        verifyNoInteractions(seriesFacadeProvider, seriesFacade);
    }

    @Test
    @DisplayName("resolveSeriesUuids → 經延遲取得的 SeriesFacade 一次批次轉換（去重、忽略 null）")
    void resolveSeriesUuids_withIds_delegatesToSeriesFacadeOnce() {
        UUID seriesUuid = UUID.randomUUID();
        when(seriesFacadeProvider.getObject()).thenReturn(seriesFacade);
        when(seriesFacade.findUuidsByIds(anyCollection())).thenReturn(Map.of(3L, seriesUuid));

        Map<Long, UUID> result = resolver.resolveSeriesUuids(Arrays.asList(3L, null, 3L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(seriesFacade).findUuidsByIds(captor.capture());
        assertThat(captor.getValue()).containsExactly(3L);
        assertThat(result).containsEntry(3L, seriesUuid);
    }
}
