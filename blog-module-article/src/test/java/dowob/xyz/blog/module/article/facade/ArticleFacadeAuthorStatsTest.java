package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.dto.AuthorArticleStats;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 公開作者頁相關 {@link ArticleFacadeImpl} 方法的單元測試（{@code countPublishedByAuthor}、
 * {@code getPublishedStatsByAuthor}）。
 *
 * <p>SQL 本身（只計 PUBLISHED、分組、加總）由 {@code ArticleFacadeAuthorStatsIT} 對真實 PostgreSQL 驗證；
 * 本類驗證 facade 層的映射與 null／空值契約。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
class ArticleFacadeAuthorStatsTest {

    @Mock
    private ArticleMapper articleMapper;

    private ArticleFacadeImpl facade;

    @BeforeEach
    void setUp() {
        facade = new ArticleFacadeImpl(articleMapper, null, null, null, null, null, null, null,
                new ArticleDataAssembler(), null, null, null, null);
    }

    @Test
    @DisplayName("countPublishedByAuthor：mapper 的分組列轉成 作者 uuid → 篇數 的 Map")
    void countPublishedByAuthor_mapsRowsToMap() {
        UUID authorA = UUID.randomUUID();
        UUID authorB = UUID.randomUUID();
        when(articleMapper.countPublishedByAuthor()).thenReturn(List.of(
                new ArticleMapper.AuthorPublishedCountRow(authorA, 3L),
                new ArticleMapper.AuthorPublishedCountRow(authorB, 1L)));

        Map<UUID, Long> result = facade.countPublishedByAuthor();

        assertThat(result).containsOnlyKeys(authorA, authorB);
        assertThat(result.get(authorA)).isEqualTo(3L);
        assertThat(result.get(authorB)).isEqualTo(1L);
    }

    @Test
    @DisplayName("countPublishedByAuthor：沒有任何已發布文章時回傳可變的空 Map")
    void countPublishedByAuthor_noRows_returnsMutableEmptyMap() {
        when(articleMapper.countPublishedByAuthor()).thenReturn(List.of());

        Map<UUID, Long> result = facade.countPublishedByAuthor();

        assertThat(result).isEmpty();
        result.put(UUID.randomUUID(), 1L);
    }

    @Test
    @DisplayName("getPublishedStatsByAuthor：mapper 的統計列轉成 AuthorArticleStats")
    void getPublishedStatsByAuthor_mapsRow() {
        UUID author = UUID.randomUUID();
        when(articleMapper.getPublishedStatsByAuthor(author))
                .thenReturn(new ArticleMapper.AuthorStatsRow(4L, 1200L, 37L));

        AuthorArticleStats stats = facade.getPublishedStatsByAuthor(author);

        assertThat(stats).isEqualTo(new AuthorArticleStats(4L, 1200L, 37L));
    }

    @Test
    @DisplayName("getPublishedStatsByAuthor：mapper 回 null 時回傳全 0，不是 null")
    void getPublishedStatsByAuthor_nullRow_returnsZeros() {
        UUID author = UUID.randomUUID();
        when(articleMapper.getPublishedStatsByAuthor(author)).thenReturn(null);

        assertThat(facade.getPublishedStatsByAuthor(author)).isEqualTo(new AuthorArticleStats(0L, 0L, 0L));
    }

    @Test
    @DisplayName("getPublishedStatsByAuthor：authorUuid 為 null 時回傳全 0 且不查 DB")
    void getPublishedStatsByAuthor_nullUuid_returnsZerosWithoutQuery() {
        assertThat(facade.getPublishedStatsByAuthor(null)).isEqualTo(new AuthorArticleStats(0L, 0L, 0L));

        verifyNoInteractions(articleMapper);
    }
}
