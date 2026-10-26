package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ArticleIdentityResolver} 單元測試。
 *
 * <p>series 的 uuid 一律<b>批次</b>取得（作者 uuid 自 M6-a 起直接存於 articles.author_uuid，不再經本元件）；SeriesFacade 以 {@link ObjectProvider} 延遲取得，
 * 只在確實有 series id 要轉換時才取用（打斷 SeriesFacadeImpl → ArticleFacade 的 bean 循環）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArticleIdentityResolver 單元測試")
class ArticleIdentityResolverTest {

    @Mock private ObjectProvider<SeriesFacade> seriesFacadeProvider;
    @Mock private SeriesFacade seriesFacade;

    private ArticleIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ArticleIdentityResolver(seriesFacadeProvider);
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
